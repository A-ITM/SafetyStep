// Test-only PostgreSQL/WASM dependency: never imported by an Edge Function.
import { PGlite } from "@electric-sql/pglite";
import {
  type AccidentCloudServices,
  createAccidentCloudHandler,
  type RpcResult,
} from "../functions/_shared/accident_cloud.ts";

const userA = "11111111-1111-4111-8111-111111111111";
const userB = "22222222-2222-4222-8222-222222222222";
const migration = await Deno.readTextFile(
  new URL("../migrations/202611180001_accident_pilot.sql", import.meta.url),
);
const fixture = JSON.parse(
  await Deno.readTextFile(
    new URL("../../docs/contracts/fixtures/accident-v07.json", import.meta.url),
  ),
);
function assert(value: unknown, message = "Assertion failed"): asserts value {
  if (!value) throw new Error(message);
}
function equal(actual: unknown, expected: unknown) {
  assert(
    JSON.stringify(actual) === JSON.stringify(expected),
    `Expected ${JSON.stringify(expected)}, got ${JSON.stringify(actual)}`,
  );
}
async function rejects(run: () => Promise<unknown>) {
  try {
    await run();
  } catch {
    return;
  }
  throw new Error("Expected permission/constraint rejection");
}
function event(changes: Record<string, unknown> = {}) {
  const now = Date.now();
  return {
    ...fixture.create,
    occurredAt: now,
    expiresAt: now + 300000,
    location: {
      ...fixture.create.location,
      receivedAt: now,
      fixObservedAt: now,
    },
    ...changes,
  };
}
function request(path: string, body: unknown, owner = "A") {
  return new Request(
    `https://pilot.supabase.co/functions/v1/accidents${path}`,
    {
      method: "POST",
      headers: {
        Authorization: `Bearer a.b.${owner}`,
        apikey: "sb_publishable_fixture",
      },
      body: JSON.stringify(body),
    },
  );
}
function query(latitude = 37.4137) {
  const now = Date.now();
  return {
    requestId: "nearby-001",
    location: {
      ...fixture.query.location,
      latitude,
      receivedAt: now,
      fixObservedAt: now,
    },
  };
}
async function setup() {
  const db = new PGlite();
  await db.exec(
    "create role anon; create role authenticated; create role service_role;",
  );
  await db.exec(migration);
  const services: AccidentCloudServices = {
    userId(token) {
      return Promise.resolve(token.endsWith(".A") ? userA : userB);
    },
    async register(actor, value, validationError) {
      return await db.transaction(async (tx) => {
        await tx.exec("set local role service_role");
        const result = await tx.query<{ reply: RpcResult }>(
          "select public.accident_pilot_register($1::uuid,$2,$3::jsonb,$4) as reply",
          [actor.userId, actor.owner, JSON.stringify(value), validationError],
        );
        return result.rows[0].reply;
      });
    },
    async nearby(actor, value) {
      return await db.transaction(async (tx) => {
        await tx.exec("set local role service_role");
        const result = await tx.query<{ reply: RpcResult }>(
          "select public.accident_pilot_nearby($1::uuid,$2,$3,$4::jsonb) as reply",
          [
            actor.userId,
            actor.owner,
            value.requestId,
            JSON.stringify(value.location),
          ],
        );
        return result.rows[0].reply;
      });
    },
  };
  return {
    db,
    services,
    handle: createAccidentCloudHandler({
      supabaseUrl: "https://pilot.supabase.co",
      serviceRoleKey: "test-server-only",
      userAId: userA,
      userBId: userB,
    }, services),
  };
}

Deno.test("actual PostgreSQL migration enforces private storage, RLS and service-only RPC execution", async () => {
  const { db } = await setup();
  try {
    for (const role of ["anon", "authenticated", "service_role"]) {
      await rejects(() =>
        db.transaction(async (tx) => {
          await tx.exec(`set local role ${role}`);
          await tx.query("select * from accident_pilot.events");
        })
      );
      if (role !== "service_role") {
        await rejects(() =>
          db.transaction(async (tx) => {
            await tx.exec(`set local role ${role}`);
            await tx.query(
              "select public.accident_pilot_register($1::uuid,'A',$2::jsonb,null)",
              [userA, JSON.stringify(event())],
            );
          })
        );
      }
    }
    const result = await db.query<
      { relrowsecurity: boolean; relforcerowsecurity: boolean }
    >(
      "select relrowsecurity,relforcerowsecurity from pg_class where oid='accident_pilot.events'::regclass",
    );
    assert(result.rows[0].relrowsecurity && result.rows[0].relforcerowsecurity);
  } finally {
    await db.close();
  }
});

Deno.test("actual PostgreSQL register/replay/conflict/owner binding work through the cloud handler", async () => {
  const { db, handle } = await setup();
  try {
    const original = event();
    equal((await handle(request("", original))).status, 201);
    equal((await (await handle(request("", original))).json()).duplicate, true);
    equal(
      (await handle(
        request("", {
          ...original,
          location: { ...original.location, latitude: 38 },
        }),
      )).status,
      409,
    );
    equal(
      (await handle(request("", { ...original, owner: "B" }, "B"))).status,
      403,
    );
    equal(
      (await handle(request("", { ...original, source: "STOP_OBSERVATION" })))
        .status,
      409,
    );
    equal(
      (await handle(
        request("", event({ id: "not-accident", source: "STOP_OBSERVATION" })),
      )).status,
      400,
    );
    const reassigned = await db.query<{ reply: RpcResult }>(
      "select public.accident_pilot_register($1::uuid,'A',$2::jsonb,null) as reply",
      [userB, JSON.stringify(original)],
    );
    equal(reassigned.rows[0].reply.status, 403);
    const rows = await db.query("select event from accident_pilot.events");
    equal(rows.rows.length, 1);
  } finally {
    await db.close();
  }
});

Deno.test("actual PostgreSQL nearby filters distance/self and never persists receiver position", async () => {
  const { db, handle } = await setup();
  try {
    const original = event();
    await handle(request("", original));
    await handle(request("", event({ id: "own-B", owner: "B" }), "B"));
    await handle(
      request(
        "",
        event({ id: "far", location: { ...original.location, latitude: 35 } }),
      ),
    );
    const before = await db.query(
      "select * from accident_pilot.events order by id",
    );
    const near = await (await handle(request("/nearby", query(), "B"))).json();
    equal(near.events.map((e: { id: string }) => e.id), [original.id]);
    equal(near.radiusMeters, 1000);
    equal(near.complete, true);
    const far = await (await handle(request("/nearby", query(40), "B"))).json();
    equal(far.events, []);
    equal(
      (await db.query("select * from accident_pilot.events order by id")).rows,
      before.rows,
    );
  } finally {
    await db.close();
  }
});

Deno.test("actual PostgreSQL expired create replays ACK while nearby never discloses expired coordinates", async () => {
  const { db, handle } = await setup();
  try {
    const time = Date.now() - 600000;
    const expired = event({
      occurredAt: time,
      expiresAt: time + 300000,
      location: {
        ...fixture.create.location,
        receivedAt: time,
        fixObservedAt: time,
      },
    });
    await db.query(
      "insert into accident_pilot.events(id,actor,owner,event,latitude,longitude) values($1,$2,'A',$3,37.4127,127.0946)",
      [expired.id, userA, JSON.stringify(expired)],
    );
    const response = await handle(request("", expired));
    equal(response.status, 200);
    equal((await response.json()).duplicate, true);
    equal(
      (await (await handle(request("/nearby", query(), "B"))).json()).events,
      [],
    );
    const saved = await db.query<{ status: string }>(
      "select event->>'status' as status from accident_pilot.events",
    );
    equal(saved.rows, [{ status: "ACTIVE" }]);
    equal(
      (await handle(request("", { ...expired, id: "never-accepted" }))).status,
      410,
    );
  } finally {
    await db.close();
  }
});

Deno.test("actual PostgreSQL purges 24h retry data and atomically bounds capacity at 500", async () => {
  const { db, handle } = await setup();
  try {
    const records = Array.from(
      { length: 500 },
      (_, i) => event({ id: `capacity-${i}` }),
    );
    await db.query(
      "insert into accident_pilot.events(id,actor,owner,event,latitude,longitude) select e->>'id',$1,'A',e,37.4127,127.0946 from jsonb_array_elements($2::jsonb) e",
      [userA, JSON.stringify(records)],
    );
    equal((await handle(request("", event({ id: "overflow" })))).status, 429);
    equal((await handle(request("", records[0]))).status, 200);
    await db.exec(
      "update accident_pilot.events set created_at=clock_timestamp()-interval '25 hours'",
    );
    equal((await handle(request("", event({ id: "reclaimed" })))).status, 201);
    const saved = await db.query<{ id: string }>(
      "select id from accident_pilot.events",
    );
    equal(saved.rows, [{ id: "reclaimed" }]);
  } finally {
    await db.close();
  }
});

Deno.test("actual PostgreSQL transaction rollback gives no ACK and queued create retries stay idempotent", async () => {
  const { db, handle } = await setup();
  try {
    await db.exec(
      "create function accident_pilot.fail_write() returns trigger language plpgsql as $$begin raise exception 'injected write failure'; end;$$; create trigger fail_write before insert on accident_pilot.events for each row execute function accident_pilot.fail_write();",
    );
    const original = event();
    equal((await handle(request("", original))).status, 503);
    equal((await db.query("select * from accident_pilot.events")).rows, []);
    await db.exec("drop trigger fail_write on accident_pilot.events");
    const responses = await Promise.all([
      handle(request("", original)),
      handle(request("", original)),
      handle(request("", original)),
    ]);
    equal(responses.map((response) => response.status), [201, 200, 200]);
    equal(
      (await db.query("select * from accident_pilot.events")).rows.length,
      1,
    );
  } finally {
    await db.close();
  }
});

Deno.test("actual PostgreSQL data/roles/functions survive export and reopening", async () => {
  const { db, handle } = await setup();
  const original = event();
  equal((await handle(request("", original))).status, 201);
  const archive = await db.dumpDataDir("none");
  await db.close();
  const reopened = new PGlite({ loadDataDir: archive });
  try {
    await reopened.exec("set role service_role");
    const reply = await reopened.query<
      { reply: { status: number; body: { duplicate: boolean } } }
    >(
      "select public.accident_pilot_register($1::uuid,'A',$2::jsonb,null) as reply",
      [userA, JSON.stringify(original)],
    );
    equal(reply.rows[0].reply.status, 200);
    equal(reply.rows[0].reply.body.duplicate, true);
    await rejects(() => reopened.query("select * from accident_pilot.events"));
  } finally {
    await reopened.close();
  }
});
