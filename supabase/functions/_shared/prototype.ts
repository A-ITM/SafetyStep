/** Loopback TEST transport only. Public fixture identities are not production Auth. */
export type Hazard = {
  id: string;
  owner: "A" | "B";
  version: number;
  eventType: "ACCIDENT" | "STOPPED_VEHICLE";
  status: "ACTIVE" | "RETRACTED" | "RESOLVED";
  source:
    | "MANUAL_TEST"
    | "SDK_AIRBAG"
    | "GLEO_REQUEST"
    | "OPERATOR_EVENT"
    | "STOP_OBSERVATION";
  simulation: true;
  observedAt: number;
  expiresAt: number;
  road: "test-highway";
  direction: "east" | "west";
  meters: number;
};

type ObjectValue = Record<string, unknown>;
type RetractCommand = {
  expectedVersion: number;
  evidence: {
    source: "TEST";
    startedAt: number;
    observedAt: number;
    speedMps: number;
    durationMs: number;
  };
};
type ResolveCommand = { expectedVersion: number; reason: "CONTROLLED_TEST" };
type TerminalCommand = RetractCommand | ResolveCommand;
type Identity = "A" | "B" | "ADMIN";

const TEST_IDENTITIES = new Map<string, Identity>([
  ["Bearer prototype-vehicle-A", "A"],
  ["Bearer prototype-vehicle-B", "B"],
  ["Bearer prototype-test-admin", "ADMIN"],
]);

export const EVENT_TTL_MS = 180_000;
const POSITION_FRESH_MS = 15_000;
const FUTURE_TOLERANCE_MS = 5_000;
const MAX_RECORDS = 200;
const ID_PATTERN = /^[a-zA-Z0-9-]{1,80}$/;
const EVENT_KEYS = [
  "id",
  "owner",
  "version",
  "eventType",
  "status",
  "source",
  "simulation",
  "observedAt",
  "expiresAt",
  "road",
  "direction",
  "meters",
] as const;
const SOURCES = [
  "MANUAL_TEST",
  "SDK_AIRBAG",
  "GLEO_REQUEST",
  "OPERATOR_EVENT",
  "STOP_OBSERVATION",
];
const QUERY_KEYS = [
  "requestId",
  "road",
  "direction",
  "meters",
  "observedAt",
  "speedMps",
];

function object(value: unknown): value is ObjectValue {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}

function exactKeys(value: ObjectValue, keys: readonly string[]): boolean {
  const found = Object.keys(value);
  return found.length === keys.length &&
    found.every((key) => keys.includes(key));
}

function finite(value: unknown): value is number {
  return typeof value === "number" && Number.isFinite(value);
}

function integer(value: unknown): value is number {
  return typeof value === "number" && Number.isSafeInteger(value);
}

function member(value: unknown, allowed: readonly string[]): value is string {
  return typeof value === "string" && allowed.includes(value);
}

function fresh(observedAt: number, now: number): boolean {
  return observedAt >= now - POSITION_FRESH_MS &&
    observedAt <= now + FUTURE_TOLERANCE_MS;
}

function sameEvent(left: ObjectValue, right: Hazard): boolean {
  return EVENT_KEYS.every((key) => left[key] === right[key]);
}

function sameCommand(left: TerminalCommand, right: TerminalCommand): boolean {
  if (left.expectedVersion !== right.expectedVersion) return false;
  if ("reason" in left) {
    return "reason" in right && left.reason === right.reason;
  }
  return "evidence" in right &&
    Object.keys(left.evidence).every((key) =>
      left.evidence[key as keyof RetractCommand["evidence"]] ===
        right.evidence[key as keyof RetractCommand["evidence"]]
    );
}

function terminalCommand(
  value: ObjectValue,
  retract: boolean,
): TerminalCommand | null {
  if (!integer(value.expectedVersion) || value.expectedVersion < 1) return null;
  if (!retract) {
    return exactKeys(value, ["expectedVersion", "reason"]) &&
        value.reason === "CONTROLLED_TEST"
      ? value as ResolveCommand
      : null;
  }
  const evidence = value.evidence;
  if (
    !exactKeys(value, ["expectedVersion", "evidence"]) || !object(evidence) ||
    !exactKeys(evidence, [
      "source",
      "startedAt",
      "observedAt",
      "speedMps",
      "durationMs",
    ]) ||
    evidence.source !== "TEST" || !integer(evidence.startedAt) ||
    !integer(evidence.observedAt) ||
    !finite(evidence.speedMps) || !integer(evidence.durationMs)
  ) return null;
  return value as RetractCommand;
}

function numericQuery(value: string | null): number | null {
  // Reject blank strings and non-decimal encodings instead of coercing them to zero.
  if (
    value === null ||
    !/^-?(?:\d+(?:\.\d+)?|\.\d+)(?:[eE][+-]?\d+)?$/.test(value)
  ) return null;
  const number = Number(value);
  return Number.isFinite(number) ? number : null;
}

export function createPrototypeHandler(now: () => number = Date.now) {
  const events = new Map<string, Hazard>();
  const terminalCommands = new Map<string, TerminalCommand>();
  const epoch = crypto.randomUUID();
  const json = (data: unknown, status = 200) =>
    Response.json(data, { status, headers: { "Cache-Control": "no-store" } });
  const error = (status: number, code: string, message: string) =>
    json({ error: { code, message } }, status);
  const invalid = (message = "Invalid request") =>
    error(400, "INVALID_REQUEST", message);
  const conflict = () => error(409, "VERSION_CONFLICT", "Conflicting command");
  const forbidden = () =>
    error(403, "FORBIDDEN", "Forbidden TEST role, owner or event type");
  const invalidPosition = () =>
    error(422, "INVALID_POSITION", "Invalid or stale TEST position");
  const ack = (event: Hazard, duplicate: boolean, status = 200) =>
    json({ accepted: true, duplicate, event }, status);

  return async (request: Request): Promise<Response> => {
    const url = new URL(request.url);
    if (url.pathname === "/health" && request.method === "GET") {
      return json({ status: "ok", mode: "local-simulation", epoch });
    }
    const identity = TEST_IDENTITIES.get(
      request.headers.get("Authorization") ?? "",
    );
    if (!identity) {
      return error(401, "TEST_IDENTITY_REQUIRED", "TEST identity required");
    }
    const retractMatch = url.pathname.match(
      /^\/prototype\/incidents\/([^/]+)\/retract$/,
    );
    const resolveMatch = url.pathname.match(
      /^\/prototype\/admin\/incidents\/([^/]+)\/resolve$/,
    );
    const collection = url.pathname === "/prototype/incidents";
    if (!collection && !retractMatch && !resolveMatch) {
      return error(404, "NOT_FOUND", "Not found");
    }
    if (
      collection
        ? !["GET", "POST"].includes(request.method)
        : request.method !== "POST"
    ) return error(405, "METHOD_NOT_ALLOWED", "Method not allowed");
    if (
      (resolveMatch && identity !== "ADMIN") ||
      (!resolveMatch && identity === "ADMIN")
    ) return forbidden();
    const clockNow = now();

    if (collection && request.method === "GET") {
      const params = url.searchParams;
      if (
        [...params.keys()].some((key) =>
          !QUERY_KEYS.includes(key) || params.getAll(key).length !== 1
        ) ||
        QUERY_KEYS.filter((key) => key !== "speedMps").some((key) =>
          !params.has(key)
        ) ||
        !ID_PATTERN.test(params.get("requestId") ?? "")
      ) return invalid("Invalid snapshot query fields");
      const road = params.get("road");
      const direction = params.get("direction");
      const meters = numericQuery(params.get("meters"));
      const observedAt = numericQuery(params.get("observedAt"));
      const speed = params.has("speedMps")
        ? numericQuery(params.get("speedMps"))
        : undefined;
      if (
        !["test-highway", "parallel-highway"].includes(road ?? "") ||
        !["east", "west"].includes(direction ?? "") ||
        meters === null || meters < 0 || meters > 10_000 ||
        observedAt === null || !Number.isSafeInteger(observedAt) ||
        !fresh(observedAt, clockNow) ||
        (speed !== undefined && (speed === null || speed < 0))
      ) return invalidPosition();
      return json({
        requestId: params.get("requestId"),
        epoch,
        scopeKey: `v1:${road}:${direction}`,
        scopeVersion: 1,
        serverNow: clockNow,
        complete: true,
        sourceHealth: "TEST_ONLY",
        events: [...events.values()].filter((event) =>
          event.road === road && event.direction === direction &&
          event.meters > meters && event.meters - meters <= 1_000
        ),
      });
    }
    if ([...url.searchParams].length) return invalid("Unexpected query fields");
    let value: unknown;
    try {
      const raw = await request.text();
      if (new TextEncoder().encode(raw).length > 4096) {
        return invalid("Request body too large");
      }
      value = JSON.parse(raw);
    } catch {
      return invalid("Invalid JSON");
    }
    if (!object(value)) return invalid();
    if (collection) {
      if (
        !exactKeys(value, EVENT_KEYS) || typeof value.id !== "string" ||
        !ID_PATTERN.test(value.id)
      ) return invalid("Invalid event fields or identifier");
      if (!member(value.owner, ["A", "B"])) {
        return invalid("Invalid event owner");
      }
      if (value.owner !== identity) return forbidden();
      const previous = events.get(value.id);
      if (previous) {
        if (previous.owner !== identity) return forbidden();
        // Creation replay precedes observation/TTL checks and cannot update existing records.
        return previous.status === "ACTIVE" && sameEvent(value, previous)
          ? ack(previous, true)
          : conflict();
      }
      if (value.version !== 1 || value.status !== "ACTIVE") return conflict();
      if (
        !member(value.eventType, ["ACCIDENT", "STOPPED_VEHICLE"]) ||
        !member(value.source, SOURCES) ||
        value.simulation !== true ||
        (value.source === "STOP_OBSERVATION" &&
          value.eventType !== "STOPPED_VEHICLE") ||
        !integer(value.observedAt) || !integer(value.expiresAt) ||
        value.observedAt < clockNow - 86_400_000 ||
        value.observedAt > clockNow + FUTURE_TOLERANCE_MS ||
        value.expiresAt !== value.observedAt + EVENT_TTL_MS
      ) return invalid("Invalid TEST event");
      if (
        value.road !== "test-highway" ||
        !member(value.direction, ["east", "west"]) || !finite(value.meters) ||
        value.meters < 0 || value.meters > 10_000
      ) return invalidPosition();
      if (value.expiresAt <= clockNow) {
        return error(410, "EXPIRED_EVENT", "Event already expired");
      }
      if (events.size >= MAX_RECORDS) {
        return error(429, "CAPACITY_EXCEEDED", "Local TEST session full");
      }
      const event = value as Hazard;
      events.set(event.id, event);
      return ack(event, false, 201);
    }
    const id = (retractMatch ?? resolveMatch)![1];
    if (!ID_PATTERN.test(id)) return invalid("Invalid incident identifier");
    const event = events.get(id);
    if (!event) return error(404, "NOT_FOUND", "Incident not found");
    const retract = retractMatch !== null;
    if (
      retract
        ? event.owner !== identity || event.eventType !== "STOPPED_VEHICLE"
        : event.eventType !== "ACCIDENT"
    ) return forbidden();
    const command = terminalCommand(value, retract);
    if (!command) return invalid("Invalid terminal command fields");
    const acceptedCommand = terminalCommands.get(id);
    // Exact terminal ACK replay survives old expectedVersion and expired evidence.
    if (acceptedCommand && sameCommand(command, acceptedCommand)) {
      return ack(event, true);
    }
    if (
      event.status !== "ACTIVE" || command.expectedVersion !== event.version
    ) return conflict();
    if ("evidence" in command) {
      const e = command.evidence;
      if (
        e.startedAt <= event.observedAt ||
        e.observedAt - e.startedAt !== e.durationMs || e.durationMs < 3_000 ||
        e.speedMps < 2
      ) return invalid("Inconsistent TEST restart evidence");
      if (!fresh(e.observedAt, clockNow)) return invalidPosition();
    }
    const terminal: Hazard = {
      ...event,
      version: event.version + 1,
      status: retract ? "RETRACTED" : "RESOLVED",
    };
    events.set(id, terminal);
    terminalCommands.set(id, command);
    return ack(terminal, false);
  };
}
