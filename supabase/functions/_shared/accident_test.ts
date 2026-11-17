import { createAccidentHandler } from "./accident.ts";
import { AccidentRepository } from "./accident_repository.ts";
import {
  ACCIDENT_TTL_MS,
  accidentDistanceMeters,
  type AccidentEvent,
  type AccidentLocation,
} from "./accident_types.ts";

type Nearby = {
  requestId: string;
  serverNow: number;
  radiusMeters: number;
  complete: boolean;
  events: AccidentEvent[];
};
type Ack = { accepted: boolean; duplicate: boolean; event: AccidentEvent };
type Fixture = {
  clockNow: number;
  staleClockNow: number;
  create: AccidentEvent;
  createAck: Ack;
  duplicateAck: Ack;
  query: { requestId: string; location: AccidentLocation };
  snapshot: Nearby;
  emptySnapshot: Nearby;
  staleQuery: { requestId: string; location: AccidentLocation };
  staleSnapshot: Nearby;
  resolve: { expectedVersion: number; reason: string };
  resolveAck: Ack;
  resolvedSnapshot: Nearby;
  versionError: unknown;
};
const fixture: Fixture = JSON.parse(
  await Deno.readTextFile(
    new URL(
      "../../../docs/contracts/fixtures/accident-v07.json",
      import.meta.url,
    ),
  ),
);
const base = fixture.create;
const collection = "/prototype/accidents";
const nearbyPath = `${collection}/nearby`;
const resolvePath = (id = base.id) =>
  `/prototype/admin/accidents/${id}/resolve`;

function assert(value: unknown, message = "Assertion failed"): asserts value {
  if (!value) throw new Error(message);
}
function canonical(value: unknown): unknown {
  if (Array.isArray(value)) return value.map(canonical);
  if (value && typeof value === "object") {
    return Object.fromEntries(
      Object.entries(value).sort(([a], [b]) => a.localeCompare(b))
        .map(([key, item]) => [key, canonical(item)]),
    );
  }
  return value;
}
function equal(actual: unknown, expected: unknown) {
  const a = JSON.stringify(canonical(actual));
  const e = JSON.stringify(canonical(expected));
  assert(a === e, `Expected ${e}, got ${a}`);
}
function request(
  path: string,
  body?: unknown,
  identity: string | null = "A",
  method = "POST",
): Request {
  const headers: Record<string, string> = {
    "Content-Type": "application/json",
  };
  if (identity !== null) {
    headers.Authorization = identity === "ADMIN"
      ? "Bearer prototype-test-admin"
      : `Bearer prototype-vehicle-${identity}`;
  }
  return new Request(`http://127.0.0.1${path}`, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  });
}
const post = (event: unknown = base, identity: string | null = "A") =>
  request(collection, event, identity);
const query = (location: unknown = fixture.query.location, identity = "B") =>
  request(nearbyPath, { ...fixture.query, location }, identity);
function setup(now: () => number = () => fixture.clockNow) {
  let writes = 0;
  const repository = new AccidentRepository([], () => {
    writes++;
    return Promise.resolve();
  });
  return {
    repository,
    handle: createAccidentHandler(repository, now),
    writes: () => writes,
  };
}
async function data<T = unknown>(response: Response, status = 200): Promise<T> {
  equal(response.status, status);
  equal(response.headers.get("Cache-Control"), "no-store");
  assert(response.headers.get("Content-Type")?.startsWith("application/json"));
  return await response.json() as T;
}
async function failure(response: Response, status: number, code: string) {
  const value = await data<{ error: { code: string; message: string } }>(
    response,
    status,
  );
  equal(Object.keys(value), ["error"]);
  equal(Object.keys(value.error).sort(), ["code", "message"]);
  equal(value.error.code, code);
  assert(typeof value.error.message === "string");
}

Deno.test("accident create and nearby consume the exact shared GPS contract", async () => {
  const { handle, writes } = setup();
  equal(await data(await handle(post()), 201), fixture.createAck);
  equal(await data(await handle(query())), fixture.snapshot);
  equal(await data(await handle(post())), fixture.duplicateAck);
  equal(writes(), 1);
});

Deno.test("nearby filters distance and self without asserting driving direction", async () => {
  const { handle } = setup();
  const receiver = fixture.query.location;
  const latitudeOffset = (meters: number) => meters / 6_371_000 * 180 / Math.PI;
  for (
    const [id, offset] of [
      ["within", 999.9],
      ["outside", 1000.1],
      ["other-side", -500],
    ] as const
  ) {
    await data(
      await handle(post({
        ...base,
        id,
        location: {
          ...base.location,
          latitude: receiver.latitude + latitudeOffset(offset),
          longitude: receiver.longitude,
          headingDegrees: 270,
        },
      })),
      201,
    );
  }
  await data(await handle(post({ ...base, id: "self", owner: "B" }, "B")), 201);
  equal(
    (await data<Nearby>(await handle(query()))).events.map((e) => e.id),
    ["within", "other-side"],
  );
  equal(
    (await data<Nearby>(
      await handle(query({
        ...receiver,
        latitude: 35,
        longitude: 129,
      })),
    )).events,
    [],
  );
  // Both antimeridian and pole calculations stay finite.
  assert(
    accidentDistanceMeters(
      { ...receiver, longitude: 179.999 },
      { ...receiver, longitude: -179.999 },
    ) < 1000,
  );
  equal(accidentDistanceMeters(receiver, receiver), 0);
});

Deno.test("query never persists receiver locations or creates accidents", async () => {
  const { handle, writes, repository } = setup();
  equal(await data(await handle(query())), fixture.emptySnapshot);
  equal(writes(), 0);
  equal(repository.snapshot(), []);
  await handle(post());
  const before = repository.snapshot();
  await handle(query({
    ...fixture.query.location,
    address: "receiver-only-secret",
  }));
  equal(writes(), 1);
  equal(repository.snapshot(), before);
});

Deno.test("exact create replay survives expiration, property order and resolution", async () => {
  let now = fixture.clockNow;
  const { handle, writes } = setup(() => now);
  await handle(post());
  now = fixture.staleClockNow;
  const reordered: Record<string, unknown> = Object.fromEntries(
    Object.entries(base).reverse(),
  );
  reordered.location = Object.fromEntries(
    Object.entries(base.location).reverse(),
  );
  equal(await data(await handle(post(reordered))), fixture.duplicateAck);
  equal(
    await data(await handle(request(resolvePath(), fixture.resolve, "ADMIN"))),
    fixture.resolveAck,
  );
  equal(
    await data(await handle(post())),
    { ...fixture.resolveAck, duplicate: true },
  );
  equal(writes(), 2);
});

Deno.test("owner and ID are immutable and all changed creation replays conflict", async () => {
  const { handle, writes } = setup();
  await handle(post());
  await failure(
    await handle(post({ ...base, owner: "B" }, "B")),
    403,
    "FORBIDDEN",
  );
  await failure(await handle(post(base, "B")), 403, "FORBIDDEN");
  for (
    const change of [
      { status: "RESOLVED", version: 2 },
      { source: "MANUAL_TEST" },
      { occurredAt: base.occurredAt + 1, expiresAt: base.expiresAt + 1 },
      {
        location: {
          ...base.location,
          latitude: base.location.latitude + 0.001,
        },
      },
      { location: { ...base.location, address: "changed" } },
    ]
  ) {
    equal(
      await data(await handle(post({ ...base, ...change })), 409),
      fixture.versionError,
    );
  }
  equal(writes(), 1);
});

Deno.test("only explicit accident sources can create this route; stopping cannot", async () => {
  for (
    const source of [
      "MANUAL_TEST",
      "SDK_AIRBAG",
      "GLEO_REQUEST",
      "OPERATOR_EVENT",
    ]
  ) {
    await data(await setup().handle(post({ ...base, source })), 201);
  }
  for (
    const change of [
      { source: "STOP_OBSERVATION" },
      { eventType: "STOPPED_VEHICLE", source: "STOP_OBSERVATION" },
      { simulation: false },
      { source: ["OPERATOR_EVENT"] },
      { eventType: true },
    ]
  ) {
    const { handle, repository, writes } = setup();
    await failure(
      await handle(post({ ...base, ...change })),
      400,
      "INVALID_REQUEST",
    );
    equal(repository.snapshot(), []);
    equal(writes(), 0);
  }
});

Deno.test("strict event shape rejects private fields and unsafe lifecycle/time values", async () => {
  const { source: _source, ...missing } = base;
  for (
    const body of [
      null,
      [],
      missing,
      { ...base, occupants: [] },
      { ...base, id: "bad_id" },
      { ...base, id: "x".repeat(81) },
      { ...base, owner: "C" },
      { ...base, owner: {} },
      { ...base, occurredAt: "1794880000000" },
      { ...base, occurredAt: 1.5 },
      { ...base, expiresAt: base.expiresAt + 1 },
      {
        ...base,
        occurredAt: fixture.clockNow + 5001,
        expiresAt: fixture.clockNow + 5001 + ACCIDENT_TTL_MS,
        location: {
          ...base.location,
          receivedAt: fixture.clockNow + 5001,
          fixObservedAt: fixture.clockNow + 5001,
        },
      },
    ]
  ) {
    await failure(await setup().handle(post(body)), 400, "INVALID_REQUEST");
  }
  for (
    const change of [{ status: "RESOLVED" }, { version: 0 }, { version: 2 }]
  ) {
    await failure(
      await setup().handle(post({ ...base, ...change })),
      409,
      "VERSION_CONFLICT",
    );
  }
  await failure(
    await setup(() => base.expiresAt).handle(post()),
    410,
    "EXPIRED_EVENT",
  );
});

Deno.test("GPS rejects stale fixes even if the receipt time was refreshed", async () => {
  const { accuracyMeters: _accuracy, ...missing } = base.location;
  for (
    const location of [
      null,
      missing,
      { ...base.location, latitude: 90.01 },
      { ...base.location, longitude: -180.01 },
      { ...base.location, latitude: null },
      { ...base.location, accuracyMeters: null },
      { ...base.location, accuracyMeters: 0 },
      { ...base.location, accuracyMeters: 100.01 },
      { ...base.location, fixObservedAt: null },
      { ...base.location, fixObservedAt: base.location.receivedAt - 15001 },
      { ...base.location, fixObservedAt: base.location.receivedAt + 5001 },
      { ...base.location, headingDegrees: 360 },
      { ...base.location, origin: "NETWORK" },
      { ...base.location, mock: "true" },
      { ...base.location, rawVoice: "private" },
    ]
  ) {
    await failure(
      await setup().handle(post({ ...base, location })),
      422,
      "INVALID_POSITION",
    );
  }
  const refreshedReceipt = {
    ...fixture.query.location,
    fixObservedAt: fixture.clockNow - 15001,
    receivedAt: fixture.clockNow,
  };
  await failure(
    await setup().handle(query(refreshedReceipt)),
    422,
    "INVALID_POSITION",
  );
});

Deno.test("NAVI preserves unknown fix metadata and TEST stays explicitly labeled", async () => {
  for (const origin of ["NAVI", "TEST"]) {
    const event = {
      ...base,
      location: {
        ...base.location,
        origin,
        fixObservedAt: null,
        accuracyMeters: null,
        mock: false,
      },
    };
    equal(
      (await data<Ack>(await setup().handle(post(event)), 201)).event,
      event,
    );
  }
});

Deno.test("address length matches the Android parser at 300 characters", async () => {
  const { handle } = setup();
  const location = { ...base.location, address: "가".repeat(300) };
  equal(
    (await data<Ack>(await handle(post({ ...base, location })), 201)).event
      .location,
    location,
  );
  await data(
    await handle(query({
      ...fixture.query.location,
      address: "가".repeat(300),
    })),
  );
  await failure(
    await handle(post({
      ...base,
      id: "overlong-address",
      location: { ...base.location, address: "가".repeat(301) },
    })),
    422,
    "INVALID_POSITION",
  );
  await failure(
    await handle(query({
      ...fixture.query.location,
      address: "가".repeat(301),
    })),
    422,
    "INVALID_POSITION",
  );
});

Deno.test("accident fix freezes within the event window and permits delayed first send", async () => {
  for (const delta of [-15000, 30000]) {
    const time = base.occurredAt + delta;
    await data(
      await setup().handle(post({
        ...base,
        location: { ...base.location, receivedAt: time, fixObservedAt: time },
      })),
      201,
    );
  }
  for (const delta of [-15001, 30001]) {
    const time = base.occurredAt + delta;
    await failure(
      await setup().handle(post({
        ...base,
        location: { ...base.location, receivedAt: time, fixObservedAt: time },
      })),
      400,
      "INVALID_REQUEST",
    );
  }
  await data(await setup(() => base.expiresAt - 1).handle(post()), 201);
});

Deno.test("nearby requires the actual current position with exact fields", async () => {
  const { handle } = setup();
  for (const delta of [-15000, 5000]) {
    const time = fixture.clockNow + delta;
    await data(
      await handle(query({
        ...fixture.query.location,
        receivedAt: time,
        fixObservedAt: time,
      })),
    );
  }
  for (const delta of [-15001, 5001]) {
    const time = fixture.clockNow + delta;
    await failure(
      await handle(query({
        ...fixture.query.location,
        receivedAt: time,
        fixObservedAt: time,
      })),
      422,
      "INVALID_POSITION",
    );
  }
  for (
    const body of [
      { ...fixture.query, requestId: "" },
      { ...fixture.query, requestId: "bad_id" },
      { ...fixture.query, speedMps: 10 },
      { location: fixture.query.location },
    ]
  ) {
    await failure(
      await handle(request(nearbyPath, body, "B")),
      400,
      "INVALID_REQUEST",
    );
  }
});

Deno.test("nearby requires known fix time to be current independently of receipt time", async () => {
  const { handle } = setup();
  for (
    const [receiptDelta, fixDelta] of [
      [-1000, -15001],
      [-15000, -30000],
      [1, 5001],
    ]
  ) {
    await failure(
      await handle(query({
        ...fixture.query.location,
        receivedAt: fixture.clockNow + receiptDelta,
        fixObservedAt: fixture.clockNow + fixDelta,
      })),
      422,
      "INVALID_POSITION",
    );
  }
  await data(
    await handle(query({
      ...fixture.query.location,
      receivedAt: fixture.clockNow - 1000,
      fixObservedAt: fixture.clockNow - 15000,
    })),
  );
  await data(
    await handle(query({
      ...fixture.query.location,
      origin: "NAVI",
      fixObservedAt: null,
      accuracyMeters: null,
    })),
  );
});

Deno.test("expiration remains ACTIVE with original timestamps until admin resolves", async () => {
  let now = fixture.clockNow;
  const { handle, writes } = setup(() => now);
  await handle(post());
  now = fixture.staleClockNow;
  equal(
    await data(await handle(request(nearbyPath, fixture.staleQuery, "B"))),
    fixture.staleSnapshot,
  );
  equal(writes(), 1);
  for (const identity of ["A", "B"]) {
    await failure(
      await handle(request(resolvePath(), fixture.resolve, identity)),
      403,
      "FORBIDDEN",
    );
  }
  equal(
    await data(await handle(request(resolvePath(), fixture.resolve, "ADMIN"))),
    fixture.resolveAck,
  );
  equal(
    await data(await handle(request(resolvePath(), fixture.resolve, "ADMIN"))),
    { ...fixture.resolveAck, duplicate: true },
  );
  equal(writes(), 2);
});

Deno.test("resolve preserves immutable fields and rejects malformed/stale commands", async () => {
  const { handle } = setup();
  await handle(post());
  for (
    const command of [
      { expectedVersion: 1, reason: "EMPTY_SEAT" },
      { ...fixture.resolve, extra: true },
      { reason: "CONTROLLED_TEST" },
      { ...fixture.resolve, expectedVersion: 1.1 },
      { ...fixture.resolve, expectedVersion: "1" },
    ]
  ) {
    await failure(
      await handle(request(resolvePath(), command, "ADMIN")),
      400,
      "INVALID_REQUEST",
    );
  }
  await failure(
    await handle(
      request(
        resolvePath(),
        { ...fixture.resolve, expectedVersion: 2 },
        "ADMIN",
      ),
    ),
    409,
    "VERSION_CONFLICT",
  );
  await failure(
    await handle(request(resolvePath("missing"), fixture.resolve, "ADMIN")),
    404,
    "NOT_FOUND",
  );
  await handle(request(resolvePath(), fixture.resolve, "ADMIN"));
  equal(await data(await handle(query())), fixture.resolvedSnapshot);
});

Deno.test("capacity retains expired and resolved events and still ACKs creation replay", async () => {
  let now = fixture.clockNow;
  const { handle, writes } = setup(() => now);
  for (let i = 0; i < 500; i++) {
    await data(await handle(post({ ...base, id: `capacity-${i}` })), 201);
  }
  await handle(request(resolvePath("capacity-0"), fixture.resolve, "ADMIN"));
  now = fixture.staleClockNow;
  const event = {
    ...base,
    id: "overflow",
    occurredAt: now,
    expiresAt: now + ACCIDENT_TTL_MS,
    location: { ...base.location, receivedAt: now, fixObservedAt: now },
  };
  await failure(await handle(post(event)), 429, "CAPACITY_EXCEEDED");
  equal(
    (await data<Nearby>(
      await handle(request(nearbyPath, fixture.staleQuery, "B")),
    )).events.length,
    500,
  );
  equal(
    (await data<Ack>(await handle(post({ ...base, id: "capacity-1" }))))
      .duplicate,
    true,
  );
  equal(writes(), 501);
});

Deno.test("accident HTTP identity, methods, routes and JSON errors stay strict", async () => {
  const { handle } = setup();
  for (const identity of [null, "C"]) {
    await failure(
      await handle(post(base, identity)),
      401,
      "TEST_IDENTITY_REQUIRED",
    );
  }
  for (
    const authorization of [
      "constructor",
      "__proto__",
      "Bearer prototype-vehicle-A extra",
    ]
  ) {
    await failure(
      await handle(
        new Request(`http://127.0.0.1${collection}`, {
          method: "POST",
          headers: { Authorization: authorization },
          body: JSON.stringify(base),
        }),
      ),
      401,
      "TEST_IDENTITY_REQUIRED",
    );
  }
  await failure(await handle(post(base, "ADMIN")), 403, "FORBIDDEN");
  await failure(
    await handle(query(fixture.query.location, "ADMIN")),
    403,
    "FORBIDDEN",
  );
  await failure(
    await handle(request("/prototype/accidents/unknown", base)),
    404,
    "NOT_FOUND",
  );
  await failure(
    await handle(request(collection, undefined, "A", "GET")),
    405,
    "METHOD_NOT_ALLOWED",
  );
  await failure(
    await handle(request(`${collection}?extra=1`, base)),
    400,
    "INVALID_REQUEST",
  );
  await failure(
    await handle(request(resolvePath("bad_id"), fixture.resolve, "ADMIN")),
    400,
    "INVALID_REQUEST",
  );
  for (const body of ["{", "x".repeat(8193)]) {
    await failure(
      await handle(
        new Request(`http://127.0.0.1${collection}`, {
          method: "POST",
          headers: { Authorization: "Bearer prototype-vehicle-A" },
          body,
        }),
      ),
      400,
      "INVALID_REQUEST",
    );
  }
});
