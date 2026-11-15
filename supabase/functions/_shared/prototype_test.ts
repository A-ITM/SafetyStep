import {
  createPrototypeHandler,
  EVENT_TTL_MS,
  type Hazard,
} from "./prototype.ts";

const base: Hazard = {
  id: "test-one",
  owner: "A",
  version: 1,
  observedAt: 10_000,
  expiresAt: 10_000 + EVENT_TTL_MS,
  road: "test-highway",
  direction: "east",
  meters: 1000,
  status: "ACTIVE",
  source: "MANUAL_TEST",
  simulation: true,
};
const post = (data: unknown, vehicle = "A") =>
  new Request("http://localhost/prototype/incidents", {
    method: "POST",
    headers: { Authorization: `Bearer prototype-vehicle-${vehicle}` },
    body: JSON.stringify(data),
  });
function equal(actual: unknown, expected: unknown) {
  if (actual !== expected) {
    throw new Error(`Expected ${expected}, got ${actual}`);
  }
}

Deno.test("acknowledges retry once, rejects conflicting and reversed versions", async () => {
  const handle = createPrototypeHandler(() => 20_000);
  equal((await handle(post(base))).status, 201);
  const retry = await handle(post(base));
  equal((await retry.json()).duplicate, true);
  equal((await handle(post({ ...base, meters: 800 }))).status, 409);
  equal(
    (await handle(post({ ...base, version: 3, status: "RESOLVED" }))).status,
    409,
  );
  equal(
    (await handle(post({ ...base, version: 2, status: "RESOLVED" }))).status,
    200,
  );
  equal((await handle(post(base))).status, 409);
});
Deno.test("only original owner can close an incident; other events survive", async () => {
  const handle = createPrototypeHandler(() => 20_000);
  await handle(post(base));
  await handle(post({ ...base, id: "two", owner: "B" }, "B"));
  equal(
    (await handle(
      post({ ...base, owner: "B", version: 2, status: "RESOLVED" }, "B"),
    )).status,
    403,
  );
  await handle(post({ ...base, version: 2, status: "RESOLVED" }));
  const response = await handle(
    new Request("http://localhost/prototype/incidents", {
      headers: { Authorization: "Bearer prototype-vehicle-B" },
    }),
  );
  const data = await response.json();
  equal(data.events.filter((e: Hazard) => e.status === "ACTIVE").length, 1);
});
Deno.test("rejects missing identity, health fields, stale and non-simulation data", async () => {
  const handle = createPrototypeHandler(() => 20_000);
  equal(
    (await handle(new Request("http://localhost/prototype/incidents"))).status,
    401,
  );
  equal((await handle(post({ ...base, response: "unconscious" }))).status, 400);
  equal((await handle(post({ ...base, simulation: false }))).status, 400);
  equal((await handle(post({ ...base, observedAt: 30_000 }))).status, 400);
  equal((await createPrototypeHandler(() => 200_000)(post(base))).status, 410);
});
Deno.test("expiry is not a resolution and does not manufacture all-clear", async () => {
  let now = 20_000;
  const handle = createPrototypeHandler(() => now);
  await handle(post(base));
  now = 200_000;
  const response = await handle(
    new Request("http://localhost/prototype/incidents", {
      headers: { Authorization: "Bearer prototype-vehicle-B" },
    }),
  );
  const data = await response.json();
  equal(data.events[0].status, "ACTIVE");
  equal(data.events[0].expiresAt < now, true);
});

Deno.test("operator accident reaches B with the same ID and no occupant data", async () => {
  const handle = createPrototypeHandler(() => 20_000);
  const accident = { ...base, source: "OPERATOR_EVENT" };
  equal((await handle(post(accident))).status, 201);
  const response = await handle(
    new Request("http://localhost/prototype/incidents", {
      headers: { Authorization: "Bearer prototype-vehicle-B" },
    }),
  );
  const received = (await response.json()).events[0];
  equal(received.id, accident.id);
  equal(received.source, "OPERATOR_EVENT");
  equal(received.status, "ACTIVE");
  equal(
    "response" in received || "help" in received || "voiceText" in received,
    false,
  );
});
