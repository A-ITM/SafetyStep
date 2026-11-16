import {
  createPrototypeHandler,
  EVENT_TTL_MS,
  type Hazard,
} from "./prototype.ts";

type Evidence = {
  source: string;
  startedAt: number;
  observedAt: number;
  speedMps: number;
  durationMs: number;
};
type Fixture = {
  contractVersion: number;
  clockNow: number;
  staleClockNow: number;
  create: Hazard;
  retract: { expectedVersion: number; evidence: Evidence };
  resolve: { expectedVersion: number; reason: string };
  query: Record<string, string | number>;
  staleQuery: Record<string, string | number>;
  snapshot: Record<string, unknown>;
  emptySnapshot: Record<string, unknown>;
  staleSnapshot: Record<string, unknown>;
  retractedSnapshot: Record<string, unknown>;
  retractAck: Record<string, unknown>;
  versionError: Record<string, unknown>;
};
const fixture: Fixture = JSON.parse(
  await Deno.readTextFile(
    new URL(
      "../../../docs/contracts/fixtures/prototype-v06.json",
      import.meta.url,
    ),
  ),
);
const base = fixture.create;
const collection = "/prototype/incidents";
const retractPath = (id = base.id) => `${collection}/${id}/retract`;
const resolvePath = (id = base.id) =>
  `/prototype/admin/incidents/${id}/resolve`;
const handleAt = (clock = fixture.clockNow) =>
  createPrototypeHandler(() => clock);

function assert(
  condition: unknown,
  message = "Assertion failed",
): asserts condition {
  if (!condition) throw new Error(message);
}

function canonical(value: unknown): unknown {
  if (Array.isArray(value)) return value.map(canonical);
  if (value && typeof value === "object") {
    return Object.fromEntries(
      Object.entries(value).sort(([a], [b]) => a.localeCompare(b)).map((
        [key, item],
      ) => [key, canonical(item)]),
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
  method: string,
  path = collection,
  body?: unknown,
  identity: string | null = "A",
) {
  const headers: Record<string, string> = {};
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
const post = (body: unknown = base, identity: string | null = "A") =>
  request("POST", collection, body, identity);
function query(
  changes: Record<string, string | number | null | undefined> = {},
) {
  const params = new URLSearchParams();
  for (const [key, value] of Object.entries({ ...fixture.query, ...changes })) {
    if (value !== null && value !== undefined) params.set(key, String(value));
  }
  return `${collection}?${params}`;
}

async function data(response: Response, status = 200) {
  equal(response.status, status);
  equal(response.headers.get("cache-control"), "no-store");
  assert(response.headers.get("content-type")?.startsWith("application/json"));
  return await response.json();
}

async function failure(response: Response, status: number, code: string) {
  const value = await data(response, status);
  equal(Object.keys(value), ["error"]);
  equal(Object.keys(value.error).sort(), ["code", "message"]);
  equal(value.error.code, code);
  assert(
    typeof value.error.message === "string" && value.error.message.length > 0,
  );
}

Deno.test("shared fixture create and scoped complete envelope match the frozen contract", async () => {
  equal(fixture.contractVersion, 1);
  const handle = handleAt();
  equal(await data(await handle(post()), 201), {
    accepted: true,
    duplicate: false,
    event: base,
  });
  const snapshot = await data(
    await handle(request("GET", query(), undefined, "B")),
  );
  assert(typeof snapshot.epoch === "string" && snapshot.epoch.length > 0);
  equal({ ...snapshot, epoch: fixture.snapshot.epoch }, fixture.snapshot);
  equal(Object.keys(snapshot.events[0]).sort(), Object.keys(base).sort());
});

Deno.test("creation exact replay works after TTL and after the one-day observation window", async () => {
  let now = fixture.clockNow;
  const handle = createPrototypeHandler(() => now);
  await data(await handle(post()), 201);
  now = base.observedAt + 2 * 86_400_000;
  equal(await data(await handle(post())), {
    accepted: true,
    duplicate: true,
    event: base,
  });
});

Deno.test("creation is immutable and never accepts version or status updates", async () => {
  const handle = handleAt();
  await handle(post());
  for (
    const change of [
      { meters: 999 },
      { eventType: "ACCIDENT" },
      { source: "MANUAL_TEST" },
      { version: 0 },
      { version: 2 },
      { status: "RETRACTED" },
      { version: 2, status: "RESOLVED" },
      { observedAt: base.observedAt + 1, expiresAt: base.expiresAt + 1 },
      { direction: "west" },
    ]
  ) {
    await failure(
      await handle(post({ ...base, ...change })),
      409,
      "VERSION_CONFLICT",
    );
  }
  equal((await data(await handle(request("GET", query())))).events, [base]);
});

Deno.test("other owners cannot overwrite or replay an event ID", async () => {
  const handle = handleAt();
  await handle(post());
  await failure(
    await handle(post({ ...base, owner: "B" }, "B")),
    403,
    "FORBIDDEN",
  );
  await failure(await handle(post(base, "B")), 403, "FORBIDDEN");
});

Deno.test("new events require initial lifecycle and expire without being created", async () => {
  for (
    const change of [{ version: 2 }, { version: 0 }, { status: "RESOLVED" }, {
      status: "RETRACTED",
    }]
  ) {
    await failure(
      await handleAt()(post({ ...base, ...change })),
      409,
      "VERSION_CONFLICT",
    );
  }
  await failure(await handleAt(base.expiresAt)(post()), 410, "EXPIRED_EVENT");
  const handle = handleAt();
  await failure(
    await handle(
      post({
        ...base,
        observedAt: fixture.clockNow - EVENT_TTL_MS,
        expiresAt: fixture.clockNow,
      }),
    ),
    410,
    "EXPIRED_EVENT",
  );
  equal((await data(await handle(request("GET", query())))).events, []);
});

Deno.test("create validates exact fields, types, source and observation times", async () => {
  const { source: _source, ...missing } = base;
  for (
    const value of [
      null,
      [],
      missing,
      { ...base, help: "NEEDS_HELP" },
      { ...base, id: "invalid_id" },
      { ...base, id: "a".repeat(81) },
      { ...base, owner: "C" },
      { ...base, owner: ["A"] },
      { ...base, owner: {} },
      { ...base, eventType: true },
      { ...base, source: {} },
      { ...base, eventType: "UNKNOWN" },
      { ...base, eventType: "ACCIDENT" },
      { ...base, source: "LIVE" },
      { ...base, source: ["STOP_OBSERVATION"] },
      { ...base, eventType: ["STOPPED_VEHICLE"] },
      { ...base, simulation: false },
      { ...base, observedAt: String(base.observedAt) },
      {
        ...base,
        observedAt: fixture.clockNow + 5001,
        expiresAt: fixture.clockNow + 5001 + EVENT_TTL_MS,
      },
      {
        ...base,
        observedAt: fixture.clockNow - 86_400_001,
        expiresAt: fixture.clockNow - 86_400_001 + EVENT_TTL_MS,
      },
      { ...base, observedAt: 1.5 },
      { ...base, expiresAt: base.expiresAt + 1 },
    ]
  ) await failure(await handleAt()(post(value)), 400, "INVALID_REQUEST");
});

Deno.test("all allowed sources are explicit typed TEST events", async () => {
  for (
    const source of [
      "MANUAL_TEST",
      "SDK_AIRBAG",
      "GLEO_REQUEST",
      "OPERATOR_EVENT",
    ]
  ) {
    const event = { ...base, eventType: "ACCIDENT", source };
    equal((await data(await handleAt()(post(event)), 201)).event, event);
  }
  equal(
    (await data(await handleAt()(post()), 201)).event.eventType,
    "STOPPED_VEHICLE",
  );
});

Deno.test("create position is finite and confined to supported TEST road", async () => {
  for (
    const change of [
      { road: "parallel-highway" },
      { road: "unknown" },
      { direction: "north" },
      { direction: ["east"] },
      { meters: -1 },
      { meters: 10001 },
      { meters: "1000" },
      { meters: null },
    ]
  ) {
    await failure(
      await handleAt()(post({ ...base, ...change })),
      422,
      "INVALID_POSITION",
    );
  }
  for (const meters of [0, 10000]) {
    await data(await handleAt()(post({ ...base, meters })), 201);
  }
});

Deno.test("snapshot scope filters road, direction and strict 0..1000m window", async () => {
  const handle = handleAt();
  for (
    const event of [
      base,
      { ...base, id: "boundary", meters: 1600 },
      { ...base, id: "far", meters: 1600.1 },
      { ...base, id: "at", meters: 600 },
      { ...base, id: "behind", meters: 599 },
      { ...base, id: "west", direction: "west" },
    ]
  ) await data(await handle(post(event)), 201);
  equal(
    (await data(await handle(request("GET", query())))).events.map((
      e: Hazard,
    ) => e.id),
    [base.id, "boundary"],
  );
  equal(
    (await data(
      await handle(request("GET", query({ road: "parallel-highway" }))),
    )).events,
    [],
  );
  equal(
    (await data(await handle(request("GET", query({ direction: "west" })))))
      .events.map((e: Hazard) => e.id),
    ["west"],
  );
});

Deno.test("snapshot supports omitted unknown speed and preserves zero as known speed", async () => {
  const handle = handleAt();
  await handle(post());
  for (const speedMps of [null, 0, 25]) {
    equal(
      (await data(await handle(request("GET", query({ speedMps }))))).events,
      [base],
    );
  }
});

Deno.test("snapshot query rejects missing, duplicate, unknown and invalid request IDs", async () => {
  const handle = handleAt();
  for (
    const key of Object.keys(fixture.query).filter((key) => key !== "speedMps")
  ) {
    await failure(
      await handle(request("GET", query({ [key]: null }))),
      400,
      "INVALID_REQUEST",
    );
  }
  for (
    const path of [
      query({ requestId: "" }),
      query({ requestId: "bad_id" }),
      query({ requestId: "x".repeat(81) }),
      query({ extra: 1 }),
      `${query()}&road=test-highway`,
      `${query()}&speedMps=25`,
    ]
  ) await failure(await handle(request("GET", path)), 400, "INVALID_REQUEST");
});

Deno.test("snapshot query rejects invalid position, speed and stale/future observation", async () => {
  const handle = handleAt();
  for (
    const change of [
      { road: "unknown" },
      { direction: "north" },
      { meters: "" },
      { meters: " " },
      { meters: "NaN" },
      { meters: "Infinity" },
      { meters: "0x10" },
      { meters: -1 },
      { meters: 10001 },
      { speedMps: "" },
      { speedMps: "NaN" },
      { speedMps: "Infinity" },
      { speedMps: -0.1 },
      { observedAt: "" },
      { observedAt: fixture.clockNow - 15001 },
      { observedAt: fixture.clockNow + 5001 },
      { observedAt: 1.5 },
    ]
  ) {
    await failure(
      await handle(request("GET", query(change))),
      422,
      "INVALID_POSITION",
    );
  }
  for (
    const observedAt of [fixture.clockNow - 15000, fixture.clockNow + 5000]
  ) await data(await handle(request("GET", query({ observedAt }))));
});

Deno.test("TTL preserves ACTIVE lifecycle and the shared stale fixture", async () => {
  let now = fixture.clockNow;
  const handle = createPrototypeHandler(() => now);
  await handle(post());
  now = fixture.staleClockNow;
  const snapshot = await data(
    await handle(request("GET", query(fixture.staleQuery))),
  );
  equal(
    { ...snapshot, epoch: fixture.staleSnapshot.epoch },
    fixture.staleSnapshot,
  );
  equal(snapshot.events[0].status, "ACTIVE");
  assert(snapshot.serverNow >= snapshot.events[0].expiresAt);
});

Deno.test("epoch is stable per handler and changes only on a server data reset", async () => {
  const handle = handleAt();
  const health = await data(
    await handle(request("GET", "/health", undefined, null)),
  );
  const first = await data(await handle(request("GET", query())));
  const moved = await data(
    await handle(request("GET", query({ meters: 700, requestId: "poll-002" }))),
  );
  equal(first.epoch, health.epoch);
  equal(moved.epoch, first.epoch);
  equal(moved.scopeKey, first.scopeKey);
  const reset = await data(await handleAt()(request("GET", query())));
  assert(reset.epoch !== first.epoch);
  equal(
    { ...first, epoch: fixture.emptySnapshot.epoch },
    fixture.emptySnapshot,
  );
});

Deno.test("owner retract ACK and terminal snapshot consume the shared fixtures", async () => {
  const handle = handleAt(fixture.retract.evidence.observedAt);
  await handle(post());
  equal(
    await data(await handle(request("POST", retractPath(), fixture.retract))),
    fixture.retractAck,
  );
  const snapshot = await data(
    await handle(
      request(
        "GET",
        query({ observedAt: fixture.retract.evidence.observedAt }),
      ),
    ),
  );
  equal(
    { ...snapshot, epoch: fixture.retractedSnapshot.epoch },
    fixture.retractedSnapshot,
  );
  equal(Object.keys(snapshot.events[0]).sort(), Object.keys(base).sort());
});

Deno.test("exact terminal retry succeeds after evidence freshness and expected version expire", async () => {
  let now = fixture.retract.evidence.observedAt;
  const handle = createPrototypeHandler(() => now);
  await handle(post());
  const first = await data(
    await handle(request("POST", retractPath(), fixture.retract)),
  );
  now += 86_400_000;
  equal(
    await data(await handle(request("POST", retractPath(), fixture.retract))),
    { ...first, duplicate: true },
  );
  const changed = {
    ...fixture.retract,
    evidence: { ...fixture.retract.evidence, speedMps: 3 },
  };
  equal(
    await data(await handle(request("POST", retractPath(), changed)), 409),
    fixture.versionError,
  );
  await failure(
    await handle(
      request("POST", retractPath(), {
        ...fixture.retract,
        expectedVersion: 2,
      }),
    ),
    409,
    "VERSION_CONFLICT",
  );
  await failure(await handle(post()), 409, "VERSION_CONFLICT");
  await failure(await handle(post(first.event)), 409, "VERSION_CONFLICT");
});

Deno.test("retract owner and event type restrictions leave unrelated events intact", async () => {
  const handle = handleAt(fixture.retract.evidence.observedAt);
  const accident = {
    ...base,
    id: "accident",
    eventType: "ACCIDENT",
    source: "MANUAL_TEST",
  };
  const other = { ...base, id: "other", owner: "B" };
  await handle(post());
  await handle(post(accident));
  await handle(post(other, "B"));
  for (const identity of ["B", "ADMIN"]) {
    await failure(
      await handle(request("POST", retractPath(), fixture.retract, identity)),
      403,
      "FORBIDDEN",
    );
  }
  await failure(
    await handle(request("POST", retractPath(accident.id), fixture.retract)),
    403,
    "FORBIDDEN",
  );
  await handle(request("POST", retractPath(), fixture.retract));
  const snapshot = await data(await handle(request("GET", query())));
  equal(snapshot.events, [
    { ...base, status: "RETRACTED", version: 2 },
    accident,
    other,
  ]);
});

Deno.test("retract validates exact command/evidence fields and safe numeric types", async () => {
  const handle = handleAt(fixture.retract.evidence.observedAt);
  await handle(post());
  for (
    const command of [
      {},
      { ...fixture.retract, extra: true },
      { ...fixture.retract, expectedVersion: "1" },
      { ...fixture.retract, expectedVersion: 0 },
      { expectedVersion: 1, evidence: null },
      {
        expectedVersion: 1,
        evidence: { ...fixture.retract.evidence, occupant: "driver" },
      },
      ...[{ source: "GPS" }, { speedMps: "2.5" }, { observedAt: 1.5 }, {
        startedAt: Number.MAX_SAFE_INTEGER + 1,
      }, { durationMs: "3000" }].map((change) => ({
        expectedVersion: 1,
        evidence: { ...fixture.retract.evidence, ...change },
      })),
    ]
  ) {
    await failure(
      await handle(request("POST", retractPath(), command)),
      400,
      "INVALID_REQUEST",
    );
  }
  await data(await handle(request("POST", retractPath(), fixture.retract)));
  await failure(
    await handle(
      request("POST", retractPath(), { ...fixture.retract, extra: true }),
    ),
    400,
    "INVALID_REQUEST",
  );
});

Deno.test("retract requires consistent continuous TEST restart evidence", async () => {
  const handle = handleAt(fixture.retract.evidence.observedAt);
  await handle(post());
  for (
    const change of [
      { startedAt: base.observedAt, observedAt: base.observedAt + 3000 },
      { durationMs: 2999 },
      { durationMs: 3001 },
      { speedMps: 1.99 },
      { durationMs: -3000 },
    ]
  ) {
    await failure(
      await handle(
        request("POST", retractPath(), {
          expectedVersion: 1,
          evidence: { ...fixture.retract.evidence, ...change },
        }),
      ),
      400,
      "INVALID_REQUEST",
    );
  }
  await failure(
    await handle(
      request("POST", retractPath(), {
        ...fixture.retract,
        expectedVersion: 2,
      }),
    ),
    409,
    "VERSION_CONFLICT",
  );
  await data(
    await handle(
      request("POST", retractPath(), {
        expectedVersion: 1,
        evidence: { ...fixture.retract.evidence, speedMps: 2 },
      }),
    ),
  );
});

Deno.test("retract validates observation freshness before accepting a new command", async () => {
  const past = handleAt(fixture.retract.evidence.observedAt + 15001);
  await past(post());
  await failure(
    await past(request("POST", retractPath(), fixture.retract)),
    422,
    "INVALID_POSITION",
  );
  const future = handleAt(fixture.clockNow);
  await future(post());
  const observedAt = fixture.clockNow + 5001;
  await failure(
    await future(
      request("POST", retractPath(), {
        expectedVersion: 1,
        evidence: {
          ...fixture.retract.evidence,
          startedAt: observedAt - 3000,
          observedAt,
        },
      }),
    ),
    422,
    "INVALID_POSITION",
  );
});

Deno.test("test admin alone resolves ACCIDENT and retry is idempotent", async () => {
  let now = fixture.clockNow;
  const handle = createPrototypeHandler(() => now);
  const accident = { ...base, eventType: "ACCIDENT", source: "OPERATOR_EVENT" };
  await handle(post(accident));
  for (const identity of ["A", "B"]) {
    await failure(
      await handle(request("POST", resolvePath(), fixture.resolve, identity)),
      403,
      "FORBIDDEN",
    );
  }
  const first = await data(
    await handle(request("POST", resolvePath(), fixture.resolve, "ADMIN")),
  );
  equal(first, {
    accepted: true,
    duplicate: false,
    event: { ...accident, version: 2, status: "RESOLVED" },
  });
  now += 2 * 86_400_000;
  equal(
    await data(
      await handle(request("POST", resolvePath(), fixture.resolve, "ADMIN")),
    ),
    { ...first, duplicate: true },
  );
  await failure(
    await handle(
      request(
        "POST",
        resolvePath(),
        { ...fixture.resolve, expectedVersion: 2 },
        "ADMIN",
      ),
    ),
    409,
    "VERSION_CONFLICT",
  );
});

Deno.test("admin resolve rejects stopped events, malformed commands and missing IDs", async () => {
  const handle = handleAt();
  await handle(post());
  await failure(
    await handle(request("POST", resolvePath(), fixture.resolve, "ADMIN")),
    403,
    "FORBIDDEN",
  );
  const accident = {
    ...base,
    id: "accident",
    eventType: "ACCIDENT",
    source: "MANUAL_TEST",
  };
  await handle(post(accident));
  for (
    const command of [
      { ...fixture.resolve, reason: "CLEARED" },
      { ...fixture.resolve, extra: true },
      { expectedVersion: 1 },
      { ...fixture.resolve, expectedVersion: 1.1 },
    ]
  ) {
    await failure(
      await handle(request("POST", resolvePath(accident.id), command, "ADMIN")),
      400,
      "INVALID_REQUEST",
    );
  }
  await failure(
    await handle(request("POST", retractPath("missing"), fixture.retract)),
    404,
    "NOT_FOUND",
  );
  await failure(
    await handle(
      request("POST", resolvePath("missing"), fixture.resolve, "ADMIN"),
    ),
    404,
    "NOT_FOUND",
  );
});

Deno.test("terminal records obey scope filtering just like active records", async () => {
  const handle = handleAt(fixture.retract.evidence.observedAt);
  await handle(post());
  await handle(request("POST", retractPath(), fixture.retract));
  for (
    const changes of [{ road: "parallel-highway" }, { direction: "west" }, {
      meters: 1000,
    }, { meters: 1001 }]
  ) {
    equal(
      (await data(await handle(request("GET", query(changes))))).events,
      [],
    );
  }
});

Deno.test("capacity keeps all 200 records including expired and terminal ones", async () => {
  let now = fixture.clockNow;
  const handle = createPrototypeHandler(() => now);
  for (let index = 0; index < 200; index++) {
    await data(await handle(post({ ...base, id: `capacity-${index}` })), 201);
  }
  await data(
    await handle(request("POST", retractPath("capacity-0"), fixture.retract)),
  );
  now = fixture.staleClockNow;
  const newEvent = {
    ...base,
    id: "overflow",
    observedAt: now,
    expiresAt: now + EVENT_TTL_MS,
  };
  await failure(await handle(post(newEvent)), 429, "CAPACITY_EXCEEDED");
  equal(
    (await data(await handle(request("GET", query(fixture.staleQuery))))).events
      .length,
    200,
  );
  equal(
    (await data(await handle(post({ ...base, id: "capacity-1" })))).duplicate,
    true,
  );
});

Deno.test("HTTP identities, paths, methods, JSON and error envelopes stay strict", async () => {
  const handle = handleAt();
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
      "toString",
      "prototype-vehicle-A",
      "Bearer prototype-vehicle-A extra",
    ]
  ) {
    await failure(
      await handle(
        new Request(`http://127.0.0.1${query()}`, {
          headers: { Authorization: authorization },
        }),
      ),
      401,
      "TEST_IDENTITY_REQUIRED",
    );
  }
  await failure(
    await handle(request("GET", query(), undefined, "ADMIN")),
    403,
    "FORBIDDEN",
  );
  await failure(await handle(post(base, "ADMIN")), 403, "FORBIDDEN");
  await failure(await handle(request("GET", "/unknown")), 404, "NOT_FOUND");
  for (const path of [collection, retractPath(), resolvePath()]) {
    await failure(
      await handle(
        request(
          "DELETE",
          path,
          undefined,
          path === resolvePath() ? "ADMIN" : "A",
        ),
      ),
      405,
      "METHOD_NOT_ALLOWED",
    );
  }
  await failure(
    await handle(request("POST", `${collection}?extra=1`, base)),
    400,
    "INVALID_REQUEST",
  );
  await failure(
    await handle(request("POST", retractPath("invalid_id"), fixture.retract)),
    400,
    "INVALID_REQUEST",
  );
  for (const body of ["{", "x".repeat(4097)]) {
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
