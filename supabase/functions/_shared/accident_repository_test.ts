import { createAccidentHandler } from "./accident.ts";
import {
  AccidentRepository,
  openAccidentRepository,
  type StoredAccident,
} from "./accident_repository.ts";
import { type AccidentEvent } from "./accident_types.ts";

const fixture: {
  clockNow: number;
  staleClockNow: number;
  create: AccidentEvent;
  query: unknown;
  staleQuery: unknown;
  resolve: unknown;
  resolveAck: { event: AccidentEvent };
} = JSON.parse(
  await Deno.readTextFile(
    new URL(
      "../../../docs/contracts/fixtures/accident-v07.json",
      import.meta.url,
    ),
  ),
);
const base = fixture.create;
const storageRoot = "output/prototype-accident-tests";
function assert(value: unknown, message = "Assertion failed"): asserts value {
  if (!value) throw new Error(message);
}
function equal(actual: unknown, expected: unknown) {
  assert(
    JSON.stringify(actual) === JSON.stringify(expected),
    `Expected ${JSON.stringify(expected)}, got ${JSON.stringify(actual)}`,
  );
}
function request(path: string, body: unknown, identity = "A") {
  return new Request(`http://127.0.0.1${path}`, {
    method: "POST",
    headers: {
      Authorization: identity === "ADMIN"
        ? "Bearer prototype-test-admin"
        : `Bearer prototype-vehicle-${identity}`,
    },
    body: JSON.stringify(body),
  });
}
const post = (event: unknown = base) => request("/prototype/accidents", event);
const query = (body = fixture.query) =>
  request("/prototype/accidents/nearby", body, "B");
const resolve = () =>
  request(
    `/prototype/admin/accidents/${base.id}/resolve`,
    fixture.resolve,
    "ADMIN",
  );
async function temporary<T>(run: (path: string) => Promise<T>): Promise<T> {
  await Deno.mkdir(storageRoot, { recursive: true });
  const directory = await Deno.makeTempDir({
    dir: storageRoot,
    prefix: "case-",
  });
  try {
    return await run(`${directory}/accidents.json`);
  } finally {
    await Deno.remove(directory, { recursive: true });
  }
}
async function rejects(run: () => Promise<unknown>) {
  try {
    await run();
  } catch {
    return;
  }
  throw new Error("Expected rejection");
}

Deno.test("durable ACK survives restart; nearby reads leave the file byte-for-byte unchanged", async () => {
  await temporary(async (path) => {
    const first = createAccidentHandler(
      await openAccidentRepository(path),
      () => fixture.clockNow,
    );
    equal((await first(post())).status, 201);
    const saved = await Deno.readTextFile(path);
    equal(JSON.parse(saved), {
      schemaVersion: 1,
      records: [{ created: base, event: base }],
    });
    const receiver = {
      ...(fixture.query as Record<string, unknown>),
      requestId: "receiver-only-id",
    };
    equal((await first(query(receiver))).status, 200);
    equal(await Deno.readTextFile(path), saved);
    const restarted = createAccidentHandler(
      await openAccidentRepository(path),
      () => fixture.clockNow,
    );
    equal((await restarted(post())).status, 200);
    const replay = await (await restarted(post())).json();
    equal(replay, { accepted: true, duplicate: true, event: base });
    const nearby = await (await restarted(query())).json();
    equal(nearby.events, [base]);
    equal(
      (await Array.fromAsync(
        Deno.readDir(path.slice(0, path.lastIndexOf("/"))),
      )).map((entry) => entry.name),
      ["accidents.json"],
    );
  });
});

Deno.test("resolved state and original create replay survive a durable restart", async () => {
  await temporary(async (path) => {
    const first = createAccidentHandler(
      await openAccidentRepository(path),
      () => fixture.clockNow,
    );
    await first(post());
    equal((await first(resolve())).status, 200);
    const second = createAccidentHandler(
      await openAccidentRepository(path),
      () => fixture.staleClockNow,
    );
    equal(await (await second(resolve())).json(), {
      accepted: true,
      duplicate: true,
      event: fixture.resolveAck.event,
    });
    equal(await (await second(post())).json(), {
      accepted: true,
      duplicate: true,
      event: fixture.resolveAck.event,
    });
    equal((await (await second(query(fixture.staleQuery))).json()).events, [
      fixture.resolveAck.event,
    ]);
  });
});

Deno.test("bad saved JSON/schema/records fail startup and never silently reset storage", async () => {
  await temporary(async (path) => {
    for (
      const raw of [
        "{",
        JSON.stringify({ schemaVersion: 2, records: [] }),
        JSON.stringify({ schemaVersion: 1, records: [], receivers: [] }),
        JSON.stringify({
          schemaVersion: 1,
          records: [{
            created: base,
            event: { ...base, source: "STOP_OBSERVATION" },
          }],
        }),
        JSON.stringify({
          schemaVersion: 1,
          records: [{
            created: base,
            event: {
              ...base,
              location: { ...base.location, longitude: 180.01 },
            },
          }],
        }),
        JSON.stringify({
          schemaVersion: 1,
          records: [{
            created: base,
            event: {
              ...base,
              location: {
                ...base.location,
                latitude: base.location.latitude + 0.01,
              },
            },
          }],
        }),
        JSON.stringify({
          schemaVersion: 1,
          records: [{ created: base, event: base }, {
            created: base,
            event: base,
          }],
        }),
      ]
    ) {
      await Deno.writeTextFile(path, raw);
      await rejects(() => openAccidentRepository(path));
      equal(await Deno.readTextFile(path), raw);
    }
  });
});

Deno.test("atomic rename failure returns no ACK, exposes no mutation and leaves no temp file", async () => {
  await temporary(async (path) => {
    const repository = await openAccidentRepository(path);
    const handle = createAccidentHandler(repository, () => fixture.clockNow);
    // A directory at the data-file destination forces rename to fail after the file sync.
    await Deno.mkdir(path);
    const response = await handle(post());
    equal(response.status, 503);
    equal((await response.json()).error.code, "STORAGE_UNAVAILABLE");
    equal(repository.snapshot(), []);
    equal((await (await handle(query())).json()).events, []);
    const entries = await Array.fromAsync(
      Deno.readDir(path.slice(0, path.lastIndexOf("/"))),
    );
    equal(entries.map((entry) => entry.name), ["accidents.json"]);
    await Deno.remove(path);
    equal((await handle(post())).status, 201);
    equal((await openAccidentRepository(path)).snapshot(), [{
      created: base,
      event: base,
    }]);
  });
});

Deno.test("failed resolution rolls back state and allows an exact retry", async () => {
  let fail = false;
  const repository = new AccidentRepository([], () => {
    return fail
      ? Promise.reject(new Error("Injected disk failure"))
      : Promise.resolve();
  });
  const handle = createAccidentHandler(repository, () => fixture.clockNow);
  await handle(post());
  fail = true;
  equal((await handle(resolve())).status, 503);
  equal(repository.snapshot(), [{ created: base, event: base }]);
  fail = false;
  equal((await handle(resolve())).status, 200);
  equal(repository.snapshot()[0].event, fixture.resolveAck.event);
});

Deno.test("concurrent create/replay mutations serialize; pending data stays invisible until save", async () => {
  let release!: () => void;
  const saving = new Promise<void>((done) => {
    release = done;
  });
  let entered!: () => void;
  const started = new Promise<void>((done) => {
    entered = done;
  });
  const saved: StoredAccident[][] = [];
  const repository = new AccidentRepository([], async (records) => {
    entered();
    await saving;
    saved.push(structuredClone([...records]));
  });
  const handle = createAccidentHandler(repository, () => fixture.clockNow);
  const first = handle(post());
  await started;
  const duplicate = handle(post());
  const next = handle(post({ ...base, id: "second" }));
  equal(repository.snapshot(), []);
  equal((await (await handle(query())).json()).events, []);
  release();
  const responses = await Promise.all([first, duplicate, next]);
  equal(responses.map((response) => response.status), [201, 200, 201]);
  equal((await responses[1].json()).duplicate, true);
  equal(saved.map((records) => records.length), [1, 2]);
  equal(repository.snapshot().map((record) => record.event.id), [
    base.id,
    "second",
  ]);
});
