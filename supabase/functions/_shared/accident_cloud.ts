import {
  ACCIDENT_EVENT_KEYS,
  ACCIDENT_ID_PATTERN,
  exactKeys,
  FUTURE_TOLERANCE_MS,
  object,
  POSITION_FRESH_MS,
  validAccidentEvent,
  validAccidentLocation,
} from "./accident_types.ts";

export type PilotOwner = "A" | "B";
export type PilotActor = { userId: string; owner: PilotOwner };
export type RegistrationError =
  | "VERSION_CONFLICT"
  | "INVALID_POSITION"
  | "INVALID_REQUEST"
  | null;
export type RpcResult = { status: number; body: unknown };
export type AccidentCloudServices = {
  userId(authorization: string, apiKey: string): Promise<string | null>;
  register(
    actor: PilotActor,
    event: Record<string, unknown>,
    validationError: RegistrationError,
  ): Promise<RpcResult>;
  nearby(
    actor: PilotActor,
    query: Record<string, unknown>,
  ): Promise<RpcResult>;
};
export type AccidentCloudConfig = {
  supabaseUrl: string;
  serviceRoleKey: string;
  userAId: string;
  userBId: string;
};

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const USER_JWT = /^Bearer [A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+$/;
const MAX_BODY_BYTES = 8192;

export function validateAccidentCloudConfig(config: AccidentCloudConfig) {
  const url = new URL(config.supabaseUrl);
  if (
    (url.protocol !== "https:" &&
      !(url.protocol === "http:" &&
        ["localhost", "127.0.0.1"].includes(url.hostname))) ||
    url.username || url.password || url.search || url.hash ||
    url.pathname !== "/" || !config.serviceRoleKey ||
    !UUID.test(config.userAId) || !UUID.test(config.userBId) ||
    config.userAId.toLowerCase() === config.userBId.toLowerCase()
  ) throw new Error("Invalid accident pilot configuration");
}

/** Uses the same verified-user endpoint as Supabase Auth getUser(jwt). */
export function createSupabaseAccidentServices(
  config: AccidentCloudConfig,
  fetcher: typeof fetch = fetch,
): AccidentCloudServices {
  validateAccidentCloudConfig(config);
  const base = config.supabaseUrl.replace(/\/$/, "");
  async function rpc(name: string, body: unknown): Promise<RpcResult> {
    const response = await fetcher(`${base}/rest/v1/rpc/${name}`, {
      method: "POST",
      headers: {
        apikey: config.serviceRoleKey,
        Authorization: `Bearer ${config.serviceRoleKey}`,
        "Content-Type": "application/json",
      },
      body: JSON.stringify(body),
      signal: AbortSignal.timeout(5000),
      redirect: "error",
    });
    // Never forward database error details or log request bodies/coordinates.
    if (!response.ok) throw new Error("Accident database unavailable");
    const result: unknown = await response.json();
    if (
      !object(result) || !exactKeys(result, ["status", "body"]) ||
      !Number.isInteger(result.status) ||
      ![200, 201, 400, 403, 409, 410, 422, 429].includes(
        result.status as number,
      )
    ) throw new Error("Invalid accident database response");
    return result as RpcResult;
  }
  return {
    async userId(authorization, apiKey) {
      const response = await fetcher(`${base}/auth/v1/user`, {
        headers: { Authorization: authorization, apikey: apiKey },
        signal: AbortSignal.timeout(3000),
        redirect: "error",
      });
      if ([400, 401, 403].includes(response.status)) return null;
      if (!response.ok) throw new Error("Supabase Auth unavailable");
      const user: unknown = await response.json();
      return object(user) && typeof user.id === "string" && UUID.test(user.id)
        ? user.id.toLowerCase()
        : null;
    },
    register(actor, event, validationError) {
      return rpc("accident_pilot_register", {
        p_actor: actor.userId,
        p_owner: actor.owner,
        p_event: event,
        p_validation_error: validationError,
      });
    },
    nearby(actor, query) {
      return rpc("accident_pilot_nearby", {
        p_actor: actor.userId,
        p_owner: actor.owner,
        p_request_id: query.requestId,
        p_location: query.location,
      });
    },
  };
}

async function readBody(request: Request): Promise<unknown> {
  if (!request.body) throw new Error("Missing JSON");
  const reader = request.body.getReader();
  const chunks: Uint8Array[] = [];
  let size = 0;
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      size += value.length;
      if (size > MAX_BODY_BYTES) {
        await reader.cancel();
        throw new Error("Request body too large");
      }
      chunks.push(value);
    }
  } finally {
    reader.releaseLock();
  }
  const bytes = new Uint8Array(size);
  let offset = 0;
  for (const chunk of chunks) {
    bytes.set(chunk, offset);
    offset += chunk.length;
  }
  return JSON.parse(new TextDecoder("utf-8", { fatal: true }).decode(bytes));
}

export function createAccidentCloudHandler(
  config: AccidentCloudConfig,
  services: AccidentCloudServices = createSupabaseAccidentServices(config),
  now: () => number = Date.now,
) {
  validateAccidentCloudConfig(config);
  const json = (body: unknown, status = 200) =>
    Response.json(body, { status, headers: { "Cache-Control": "no-store" } });
  const error = (status: number, code: string, message: string) =>
    json({ error: { code, message } }, status);
  const invalid = () => error(400, "INVALID_REQUEST", "Invalid request");
  return async (request: Request): Promise<Response> => {
    const url = new URL(request.url);
    const path = url.pathname.replace(/^\/functions\/v1(?=\/)/, "");
    const session = path === "/accidents/session";
    const register = path === "/accidents";
    const nearby = path === "/accidents/nearby";
    if (!session && !register && !nearby) {
      return error(404, "NOT_FOUND", "Not found");
    }
    if (request.method !== (session ? "GET" : "POST")) {
      return error(405, "METHOD_NOT_ALLOWED", "Method not allowed");
    }
    if ([...url.searchParams].length) return invalid();
    const authorization = request.headers.get("Authorization") ?? "";
    const apiKey = request.headers.get("apikey") ?? "";
    if (
      authorization.length > 8192 || !USER_JWT.test(authorization) ||
      !apiKey || apiKey.length > 4096
    ) return error(401, "AUTH_REQUIRED", "Supabase user session required");
    let userId: string | null;
    try {
      userId = await services.userId(authorization, apiKey);
    } catch {
      return error(503, "AUTH_UNAVAILABLE", "Authentication unavailable");
    }
    if (!userId) {
      return error(401, "AUTH_REQUIRED", "Supabase user session required");
    }
    const owner = userId === config.userAId.toLowerCase()
      ? "A"
      : userId === config.userBId.toLowerCase()
      ? "B"
      : null;
    if (!owner) return error(403, "FORBIDDEN", "Pilot user not allowed");
    if (session) return json({ owner });
    let value: unknown;
    try {
      value = await readBody(request);
    } catch {
      return invalid();
    }
    if (!object(value)) return invalid();
    const actor: PilotActor = { userId, owner };
    if (register) {
      if (
        !exactKeys(value, ACCIDENT_EVENT_KEYS) ||
        typeof value.id !== "string" ||
        !ACCIDENT_ID_PATTERN.test(value.id) ||
        (value.owner !== "A" && value.owner !== "B")
      ) return invalid();
      if (value.owner !== owner) {
        return error(403, "FORBIDDEN", "Accident owner does not match session");
      }
      // SQL checks existing immutable bodies before this validation result, so
      // exact retries survive TTL and changed requests always conflict.
      const validationError: RegistrationError =
        value.version !== 1 || value.status !== "ACTIVE"
          ? "VERSION_CONFLICT"
          : !validAccidentLocation(value.location)
          ? "INVALID_POSITION"
          : !validAccidentEvent(value)
          ? "INVALID_REQUEST"
          : null;
      try {
        const result = await services.register(actor, value, validationError);
        return json(result.body, result.status);
      } catch {
        return error(
          503,
          "STORAGE_UNAVAILABLE",
          "Accident storage unavailable",
        );
      }
    }
    if (
      !exactKeys(value, ["requestId", "location"]) ||
      typeof value.requestId !== "string" ||
      !ACCIDENT_ID_PATTERN.test(value.requestId)
    ) return invalid();
    const time = now();
    if (
      !validAccidentLocation(value.location) ||
      value.location.receivedAt < time - POSITION_FRESH_MS ||
      value.location.receivedAt > time + FUTURE_TOLERANCE_MS ||
      (value.location.fixObservedAt !== null &&
        (value.location.fixObservedAt < time - POSITION_FRESH_MS ||
          value.location.fixObservedAt > time + FUTURE_TOLERANCE_MS))
    ) {
      return error(
        422,
        "INVALID_POSITION",
        "Invalid or stale accident position",
      );
    }
    try {
      const result = await services.nearby(actor, value);
      return json(result.body, result.status);
    } catch {
      return error(503, "STORAGE_UNAVAILABLE", "Accident storage unavailable");
    }
  };
}
