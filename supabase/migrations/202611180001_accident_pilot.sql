-- Two-user pilot. No mobile role can read the table or execute these RPCs.
create schema if not exists accident_pilot;
revoke all on schema accident_pilot from public, anon, authenticated, service_role;

create table accident_pilot.events (
  id text primary key check (id ~ '^[A-Za-z0-9-]{1,80}$'),
  actor uuid not null,
  owner text not null check (owner in ('A', 'B')),
  event jsonb not null,
  latitude double precision not null check (latitude between -90 and 90),
  longitude double precision not null check (longitude between -180 and 180),
  created_at timestamptz not null default clock_timestamp(),
  check (
    jsonb_typeof(event) = 'object' and event->>'id' = id and event->>'owner' = owner
    and event->>'eventType' = 'ACCIDENT' and event->>'status' = 'ACTIVE'
    and event->'version' = '1'::jsonb and event->'simulation' = 'true'::jsonb
    and event->>'source' in ('MANUAL_TEST', 'SDK_AIRBAG', 'GLEO_REQUEST', 'OPERATOR_EVENT')
    and jsonb_typeof(event->'occurredAt') = 'number'
    and jsonb_typeof(event->'expiresAt') = 'number'
    and (event->>'expiresAt')::bigint = (event->>'occurredAt')::bigint + 300000
    and jsonb_typeof(event->'location') = 'object'
    and (event->'location'->>'latitude')::double precision = latitude
    and (event->'location'->>'longitude')::double precision = longitude
  )
);
create index events_created_at_idx on accident_pilot.events (created_at);
alter table accident_pilot.events enable row level security;
alter table accident_pilot.events force row level security;
revoke all on accident_pilot.events from public, anon, authenticated, service_role;

create function accident_pilot.error_reply(p_status integer, p_code text)
returns jsonb language sql immutable set search_path = '' as $$
  select jsonb_build_object('status', p_status, 'body', jsonb_build_object('error',
    jsonb_build_object('code', p_code, 'message', case p_code
      when 'VERSION_CONFLICT' then 'Conflicting command'
      when 'FORBIDDEN' then 'Forbidden pilot owner'
      when 'EXPIRED_EVENT' then 'Event already expired'
      when 'CAPACITY_EXCEEDED' then 'Accident pilot capacity reached'
      when 'INVALID_POSITION' then 'Invalid or stale accident position'
      else 'Invalid request' end)));
$$;

create function accident_pilot.purge_expired()
returns integer language plpgsql security definer set search_path = '' as $$
declare removed integer;
begin
  perform pg_catalog.pg_advisory_xact_lock(2107655017);
  delete from accident_pilot.events where created_at <= clock_timestamp() - interval '24 hours';
  get diagnostics removed = row_count;
  return removed;
end;
$$;

create function public.accident_pilot_register(
  p_actor uuid, p_owner text, p_event jsonb, p_validation_error text
) returns jsonb language plpgsql security definer set search_path = '' as $$
declare
  previous accident_pilot.events%rowtype;
  now_ms bigint;
  occurred bigint;
begin
  -- One transaction lock bounds the entire pilot, including concurrent capacity checks.
  perform pg_catalog.pg_advisory_xact_lock(2107655017);
  perform accident_pilot.purge_expired();
  now_ms := floor(extract(epoch from clock_timestamp()) * 1000)::bigint;
  if p_actor is null or p_owner not in ('A', 'B') or p_owner is null
    or jsonb_typeof(p_event) is distinct from 'object'
    or p_event->>'owner' is distinct from p_owner
    or coalesce(p_event->>'id', '') !~ '^[A-Za-z0-9-]{1,80}$' then
    return accident_pilot.error_reply(400, 'INVALID_REQUEST');
  end if;
  select * into previous from accident_pilot.events where id = p_event->>'id';
  if found then
    if previous.actor <> p_actor or previous.owner <> p_owner then
      return accident_pilot.error_reply(403, 'FORBIDDEN');
    end if;
    if previous.event <> p_event then
      return accident_pilot.error_reply(409, 'VERSION_CONFLICT');
    end if;
    return jsonb_build_object('status', 200, 'body',
      jsonb_build_object('accepted', true, 'duplicate', true, 'event', previous.event));
  end if;
  -- Computed with shared accident_types.ts by the trusted Edge Function only.
  -- It is intentionally evaluated after immutable replay, including expired retries.
  if p_validation_error is not null then
    return accident_pilot.error_reply(case p_validation_error
      when 'VERSION_CONFLICT' then 409 when 'INVALID_POSITION' then 422 else 400 end,
      case when p_validation_error in ('VERSION_CONFLICT', 'INVALID_POSITION')
        then p_validation_error else 'INVALID_REQUEST' end);
  end if;
  occurred := (p_event->>'occurredAt')::bigint;
  if occurred is null or occurred < now_ms - 86400000 or occurred > now_ms + 5000
    or (p_event->>'expiresAt')::bigint is distinct from occurred + 300000 then
    return accident_pilot.error_reply(400, 'INVALID_REQUEST');
  end if;
  if (p_event->>'expiresAt')::bigint <= now_ms then
    return accident_pilot.error_reply(410, 'EXPIRED_EVENT');
  end if;
  if (select count(*) from accident_pilot.events) >= 500 then
    return accident_pilot.error_reply(429, 'CAPACITY_EXCEEDED');
  end if;
  insert into accident_pilot.events(id, actor, owner, event, latitude, longitude)
    values (p_event->>'id', p_actor, p_owner, p_event,
      (p_event->'location'->>'latitude')::double precision,
      (p_event->'location'->>'longitude')::double precision);
  return jsonb_build_object('status', 201, 'body',
    jsonb_build_object('accepted', true, 'duplicate', false, 'event', p_event));
end;
$$;

create function public.accident_pilot_nearby(
  p_actor uuid, p_owner text, p_request_id text, p_location jsonb
) returns jsonb language plpgsql security definer set search_path = '' as $$
declare
  now_ms bigint;
  matches jsonb;
begin
  perform pg_catalog.pg_advisory_xact_lock(2107655017);
  perform accident_pilot.purge_expired();
  now_ms := floor(extract(epoch from clock_timestamp()) * 1000)::bigint;
  if p_actor is null or p_owner not in ('A', 'B') or p_owner is null
    or coalesce(p_request_id, '') !~ '^[A-Za-z0-9-]{1,80}$' then
    return accident_pilot.error_reply(400, 'INVALID_REQUEST');
  end if;
  if (p_location->>'receivedAt')::bigint not between now_ms - 15000 and now_ms + 5000
    or (p_location->'fixObservedAt' <> 'null'::jsonb and
      (p_location->>'fixObservedAt')::bigint not between now_ms - 15000 and now_ms + 5000) then
    return accident_pilot.error_reply(422, 'INVALID_POSITION');
  end if;
  -- At most 500 retained events: a bounded scan avoids a new PostGIS dependency.
  -- Receiver coordinates are parameters only and are never inserted or logged.
  select coalesce(jsonb_agg(e.event order by e.created_at, e.id), '[]'::jsonb) into matches
  from accident_pilot.events e
  where e.owner <> p_owner and e.actor <> p_actor
    and (e.event->>'expiresAt')::bigint > now_ms
    and 6371000 * 2 * asin(sqrt(least(1.0, greatest(0.0,
      power(sin(radians(e.latitude - (p_location->>'latitude')::double precision) / 2), 2)
      + cos(radians((p_location->>'latitude')::double precision)) * cos(radians(e.latitude))
      * power(sin(radians(e.longitude - (p_location->>'longitude')::double precision) / 2), 2)
    )))) <= 1000;
  return jsonb_build_object('status', 200, 'body', jsonb_build_object(
    'requestId', p_request_id, 'serverNow', now_ms, 'radiusMeters', 1000,
    'complete', true, 'events', matches));
end;
$$;

revoke all on all functions in schema accident_pilot from public, anon, authenticated, service_role;
revoke all on function public.accident_pilot_register(uuid, text, jsonb, text) from public, anon, authenticated;
revoke all on function public.accident_pilot_nearby(uuid, text, text, jsonb) from public, anon, authenticated;
grant execute on function public.accident_pilot_register(uuid, text, jsonb, text) to service_role;
grant execute on function public.accident_pilot_nearby(uuid, text, text, jsonb) to service_role;

-- Supabase's optional Cron extension bounds idle retention to 24..25 hours.
-- Without Cron, every data RPC still purges records older than 24 hours;
-- an operator must enable/schedule Cron before relying on idle-time deletion.
do $$
begin
  if exists (select 1 from pg_extension where extname = 'pg_cron') then
    perform cron.schedule('accident-pilot-retention', '0 * * * *',
      'select accident_pilot.purge_expired()');
  end if;
end;
$$;
