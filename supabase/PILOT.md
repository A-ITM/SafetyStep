# Accident cloud pilot

This is an explicitly provisioned, two-user pilot. It does not deploy the
loopback fixture identities, an administrator endpoint, or vehicle controls.

## Server configuration

Apply `migrations/202611180001_accident_pilot.sql`, then deploy the `accidents`
function with JWT verification enabled (`functions.accidents` in `config.toml`).
The handler independently verifies each caller through the official Supabase
Auth `GET /auth/v1/user` route; it never trusts a decoded JWT or user metadata.

Set two function secrets to the exact, distinct Supabase Auth user UUIDs:

- `ACCIDENT_PILOT_USER_A_ID`
- `ACCIDENT_PILOT_USER_B_ID`

The function also needs the platform-provided `SUPABASE_URL` and
`SUPABASE_SERVICE_ROLE_KEY`. The service-role key is server-only. Never put it,
Auth user passwords, refresh tokens, or the allowlist secrets into an APK or
repository. The client sends its publishable key in `apikey` and its current
Supabase user access token in `Authorization: Bearer <JWT>`.

## HTTP contract

- `GET /functions/v1/accidents/session`: authenticated identity check returning
  only `{"owner":"A"}` or `{"owner":"B"}`. No location or database access.
- `POST /functions/v1/accidents`: existing accident-location v0.7 create JSON.
  The payload owner must match the allowlisted Auth user. Creation ACK remains
  `{accepted:true,duplicate:boolean,event:AccidentEvent}`, with HTTP 201/200.
- `POST /functions/v1/accidents/nearby`: existing `{requestId,location}` body and
  `{requestId,serverNow,radiusMeters:1000,complete:true,events}` response.

All responses use JSON and `Cache-Control: no-store`. The existing strict GPS,
receipt/fix freshness, frozen accident capture window, five-minute event TTL,
and explicit accident source validation are reused. `simulation:true` and TEST
location labeling retain their existing meaning: this is a pilot, not validated
real-crash detection. No cloud administrator or resolve route exists.

## Persistence and disclosure

Only service-role RPCs can reach a private, RLS-enabled table. Authenticated and
anonymous clients have neither table access nor RPC execution privileges.
Database rows bind the verified Auth UUID as well as owner label. RPC arguments
are parameterized; transaction-level locking makes immutable replay, conflict,
purge and the global 500-record capacity check one atomic operation. The internal
`p_validation_error` is computed by shared TypeScript validation and accepted
only from the service-role Edge Function, after SQL checks an existing replay.

The hosted nearby endpoint returns **only unexpired** events inside the 1000 m
Haversine radius and excludes the caller's own events. This differs from the
local v0.7 test transport, which can return stale ACTIVE events. Expiration or
omission does not resolve an accident. The original ACTIVE record is retained
for up to 24 hours after acceptance solely for immutable retry ACKs; an exact
original create retry still ACKs after its five-minute visibility TTL. Once
purged, a previous ID has no retained replay guarantee.

Each data RPC purges records older than 24 hours. If `pg_cron` is installed when
the migration runs, it also schedules `accident-pilot-retention` hourly, bounding
idle retention to 24–25 hours. **Before relying on idle-time deletion, enable
Supabase Cron and verify that job exists.** If Cron is enabled after applying the
migration, schedule the job explicitly:

```sql
select cron.schedule('accident-pilot-retention', '0 * * * *',
  'select accident_pilot.purge_expired()');
```

Without Cron or another scheduler, cleanup happens on the next data request;
records can remain physically stored longer during inactivity. Receiver
coordinates and request IDs are never stored. The function does not log request
bodies, coordinates, tokens, Auth metadata, or upstream error details. Operator
and platform logging settings remain outside this source code.

## Local validation

```sh
deno fmt --check supabase/functions supabase/local supabase/tests
deno lint supabase/functions supabase/local supabase/tests
deno check --config supabase/functions/deno.json supabase/functions/accidents/index.ts
deno test --config supabase/tests/deno.json \
  --allow-read=docs/contracts/fixtures/accident-v07.json,supabase/migrations,supabase/tests/node_modules \
  supabase/tests/accident_cloud_test.ts supabase/tests/accident_postgres_test.ts
```

PGlite is pinned to 0.5.16 in the separate test configuration/lockfile and runs
actual PostgreSQL 18.6 SQL in memory. It is not imported by the production Edge
bundle. Tests cover permissions/RLS, replay, ownership, near/far/self filtering,
expired visibility versus replay, retention/capacity, rollback without ACK,
queued retries, and export/reopen. PGlite has one connection, so these tests do
not prove contention between independent PostgreSQL backends. Supabase platform
JWT routing, real Auth sessions, Cron execution and independent concurrent RPCs
still need deployment validation. No deployment is performed by these tests.

References: [Supabase Auth getUser](https://supabase.com/docs/reference/javascript/auth-getuser),
[Edge authorization headers](https://supabase.com/docs/guides/functions/auth-headers),
[PGlite](https://pglite.dev/docs/).
