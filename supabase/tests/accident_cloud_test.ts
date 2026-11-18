import {
  type AccidentCloudConfig,
  type AccidentCloudServices,
  createAccidentCloudHandler,
  createSupabaseAccidentServices,
} from "../functions/_shared/accident_cloud.ts";

export const config: AccidentCloudConfig = {
  supabaseUrl: "https://pilot.supabase.co",
  serviceRoleKey: "server-only-key",
  userAId: "11111111-1111-4111-8111-111111111111",
  userBId: "22222222-2222-4222-8222-222222222222",
};
const fixture = JSON.parse(
  await Deno.readTextFile(
    new URL("../../docs/contracts/fixtures/accident-v07.json", import.meta.url),
  ),
);
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
  assert(
    JSON.stringify(canonical(actual)) === JSON.stringify(canonical(expected)),
    `Expected ${JSON.stringify(expected)}, got ${JSON.stringify(actual)}`,
  );
}
function request(path: string, body?: unknown, token = "A", method = "POST") {
  return new Request(
    `https://pilot.supabase.co/functions/v1/accidents${path}`,
    {
      method,
      headers: {
        Authorization: `Bearer header.payload.${token}`,
        apikey: "sb_publishable_fixture",
      },
      body: body === undefined ? undefined : JSON.stringify(body),
    },
  );
}
function setup() {
  const calls: Array<{ operation: string; value: unknown }> = [];
  const services: AccidentCloudServices = {
    userId(token) {
      calls.push({ operation: "auth", value: token });
      return Promise.resolve(
        token.endsWith(".A")
          ? config.userAId
          : token.endsWith(".B")
          ? config.userBId
          : token.endsWith(".C")
          ? "33333333-3333-4333-8333-333333333333"
          : null,
      );
    },
    register(actor, event, validationError) {
      calls.push({
        operation: "register",
        value: { actor, event, validationError },
      });
      return Promise.resolve({ status: 201, body: fixture.createAck });
    },
    nearby(actor, query) {
      calls.push({ operation: "nearby", value: { actor, query } });
      return Promise.resolve({ status: 200, body: fixture.snapshot });
    },
  };
  return {
    calls,
    services,
    handle: createAccidentCloudHandler(
      config,
      services,
      () => fixture.clockNow,
    ),
  };
}
async function body(response: Response, status = 200) {
  equal(response.status, status);
  equal(response.headers.get("Cache-Control"), "no-store");
  assert(response.headers.get("Content-Type")?.startsWith("application/json"));
  return await response.json();
}

Deno.test("cloud pilot session verifies Auth, returns only authoritative owner, and never accesses data", async () => {
  for (const owner of ["A", "B"]) {
    const { handle, calls } = setup();
    equal(
      await body(await handle(request("/session", undefined, owner, "GET"))),
      { owner },
    );
    equal(calls.map((call) => call.operation), ["auth"]);
  }
});

Deno.test("cloud pilot rejects missing, prototype, secret-key and unauthorized identities before data access", async () => {
  const { handle, calls } = setup();
  for (
    const authorization of [
      "",
      "Bearer prototype-vehicle-A",
      "Bearer prototype-test-admin",
      "Bearer sb_secret_secret",
    ]
  ) {
    const response = await handle(
      new Request("https://pilot.supabase.co/functions/v1/accidents/session", {
        headers: {
          Authorization: authorization,
          apikey: "sb_publishable_fixture",
        },
      }),
    );
    equal((await body(response, 401)).error.code, "AUTH_REQUIRED");
  }
  equal(calls, []);
  equal(
    (await body(
      await handle(request("/session", undefined, "invalid", "GET")),
      401,
    )).error.code,
    "AUTH_REQUIRED",
  );
  equal(
    (await body(await handle(request("/session", undefined, "C", "GET")), 403))
      .error.code,
    "FORBIDDEN",
  );
  equal(calls.map((call) => call.operation), ["auth", "auth"]);
});

Deno.test("cloud accident and nearby preserve the shared wire contract and bind the verified actor", async () => {
  const { handle, calls } = setup();
  equal(
    await body(await handle(request("", fixture.create)), 201),
    fixture.createAck,
  );
  equal(
    await body(await handle(request("/nearby", fixture.query, "B"))),
    fixture.snapshot,
  );
  equal(calls[1], {
    operation: "register",
    value: {
      actor: { userId: config.userAId, owner: "A" },
      event: fixture.create,
      validationError: null,
    },
  });
  equal((calls[3].value as { actor: unknown }).actor, {
    userId: config.userBId,
    owner: "B",
  });
  const before = calls.length;
  equal(
    (await body(await handle(request("", fixture.create, "B")), 403)).error
      .code,
    "FORBIDDEN",
  );
  equal(calls.length, before + 1);
});

Deno.test("cloud validation reuses strict location rules and defers create errors until SQL replay check", async () => {
  for (
    const [change, validationError] of [
      [{ source: "STOP_OBSERVATION" }, "INVALID_REQUEST"],
      [
        { location: { ...fixture.create.location, accuracyMeters: null } },
        "INVALID_POSITION",
      ],
      [{ status: "RESOLVED", version: 2 }, "VERSION_CONFLICT"],
    ] as const
  ) {
    const { handle, calls } = setup();
    await handle(request("", { ...fixture.create, ...change }));
    equal(
      (calls[1].value as { validationError: string }).validationError,
      validationError,
    );
  }
  const { handle, calls } = setup();
  for (
    const location of [
      {
        ...fixture.query.location,
        receivedAt: fixture.clockNow - 15001,
        fixObservedAt: fixture.clockNow - 15001,
      },
      {
        ...fixture.query.location,
        receivedAt: fixture.clockNow - 1000,
        fixObservedAt: fixture.clockNow - 15001,
      },
      { ...fixture.query.location, latitude: 91 },
      { ...fixture.query.location, address: "x".repeat(301) },
    ]
  ) {
    equal(
      (await body(
        await handle(request("/nearby", { ...fixture.query, location }, "B")),
        422,
      )).error.code,
      "INVALID_POSITION",
    );
  }
  equal(calls.filter((call) => call.operation === "nearby"), []);
});

Deno.test("cloud transport has no admin route and rejects malformed/extra/oversized data", async () => {
  const { handle, calls } = setup();
  for (const path of ["/admin/resolve", "/missing"]) {
    equal(
      (await body(await handle(request(path, {})), 404)).error.code,
      "NOT_FOUND",
    );
  }
  equal(
    (await body(await handle(request("", undefined, "A", "GET")), 405)).error
      .code,
    "METHOD_NOT_ALLOWED",
  );
  equal(
    (await body(await handle(request("/session", {}, "A")), 405)).error.code,
    "METHOD_NOT_ALLOWED",
  );
  for (
    const value of [null, [], { ...fixture.create, occupants: [] }, {
      ...fixture.create,
      id: "invalid_id",
    }]
  ) {
    equal(
      (await body(await handle(request("", value)), 400)).error.code,
      "INVALID_REQUEST",
    );
  }
  equal(
    (await body(await handle(request("?unexpected=1", fixture.create)), 400))
      .error.code,
    "INVALID_REQUEST",
  );
  equal(
    (await body(
      await handle(
        new Request("https://pilot.supabase.co/accidents", {
          method: "POST",
          headers: { Authorization: "Bearer a.b.A", apikey: "public" },
          body: "x".repeat(8193),
        }),
      ),
      400,
    )).error.code,
    "INVALID_REQUEST",
  );
  assert(
    !calls.some((call) => ["register", "nearby"].includes(call.operation)),
  );
});

Deno.test("Auth and storage failures remain explicit without leaking upstream details", async () => {
  const { services } = setup();
  const auth = createAccidentCloudHandler(config, {
    ...services,
    userId: () => Promise.reject(new Error("private detail")),
  });
  equal(
    (await body(await auth(request("/session", undefined, "A", "GET")), 503))
      .error.code,
    "AUTH_UNAVAILABLE",
  );
  const storage = createAccidentCloudHandler(config, {
    ...services,
    register: () => Promise.reject(new Error("private GPS detail")),
  });
  const result = await body(await storage(request("", fixture.create)), 503);
  equal(result.error.code, "STORAGE_UNAVAILABLE");
  assert(!JSON.stringify(result).includes("private"));
});

Deno.test("Supabase services verify the JWT over official Auth route and keep database credentials server-side", async () => {
  const requests: Request[] = [];
  const fetcher: typeof fetch = (input, init) => {
    const request = new Request(input, init);
    requests.push(request);
    return Promise.resolve(
      Response.json(
        request.url.endsWith("/auth/v1/user")
          ? { id: config.userAId, user_metadata: { owner: "B" } }
          : { status: 201, body: fixture.createAck },
      ),
    );
  };
  const services = createSupabaseAccidentServices(config, fetcher);
  equal(
    await services.userId("Bearer signed.user.jwt", "sb_publishable_fixture"),
    config.userAId,
  );
  equal(requests[0].url, "https://pilot.supabase.co/auth/v1/user");
  equal(requests[0].headers.get("Authorization"), "Bearer signed.user.jwt");
  equal(requests[0].headers.get("apikey"), "sb_publishable_fixture");
  await services.register(
    { owner: "A", userId: config.userAId },
    fixture.create,
    null,
  );
  equal(
    requests[1].url,
    "https://pilot.supabase.co/rest/v1/rpc/accident_pilot_register",
  );
  equal(requests[1].headers.get("Authorization"), "Bearer server-only-key");
  equal(requests[1].headers.get("apikey"), "server-only-key");
  equal((await requests[1].json()).p_actor, config.userAId);
});

Deno.test("cloud pilot fails closed if either configured Auth identity is missing or reused", () => {
  for (
    const changed of [{ userAId: "" }, { userBId: config.userAId }, {
      serviceRoleKey: "",
    }, { supabaseUrl: "http://example.com" }]
  ) {
    let failed = false;
    try {
      createAccidentCloudHandler({ ...config, ...changed }, setup().services);
    } catch {
      failed = true;
    }
    assert(failed);
  }
});
