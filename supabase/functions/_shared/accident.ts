import { type AccidentRepository } from "./accident_repository.ts";
import {
  ACCIDENT_EVENT_KEYS,
  ACCIDENT_ID_PATTERN,
  ACCIDENT_RADIUS_METERS,
  accidentDistanceMeters,
  type AccidentEvent,
  exactKeys,
  FUTURE_TOLERANCE_MS,
  MAX_ACCIDENT_RECORDS,
  object,
  POSITION_FRESH_MS,
  sameAccident,
  validAccidentEvent,
  validAccidentLocation,
} from "./accident_types.ts";

type Identity = "A" | "B" | "ADMIN";
const TEST_IDENTITIES = new Map<string, Identity>([
  ["Bearer prototype-vehicle-A", "A"],
  ["Bearer prototype-vehicle-B", "B"],
  ["Bearer prototype-test-admin", "ADMIN"],
]);
const collection = "/prototype/accidents";
const nearby = `${collection}/nearby`;
const resolvePattern = /^\/prototype\/admin\/accidents\/([^/]+)\/resolve$/;

export function isAccidentRoute(pathname: string): boolean {
  return pathname === collection || pathname.startsWith(`${collection}/`) ||
    pathname.startsWith("/prototype/admin/accidents/");
}

export function createAccidentHandler(
  repository: AccidentRepository,
  now: () => number = Date.now,
) {
  const json = (data: unknown, status = 200) =>
    Response.json(data, { status, headers: { "Cache-Control": "no-store" } });
  const error = (status: number, code: string, message: string) =>
    json({ error: { code, message } }, status);
  const invalid = (message = "Invalid request") =>
    error(400, "INVALID_REQUEST", message);
  const forbidden = () =>
    error(403, "FORBIDDEN", "Forbidden TEST role, owner or event type");
  const conflict = () => error(409, "VERSION_CONFLICT", "Conflicting command");
  const invalidPosition = () =>
    error(422, "INVALID_POSITION", "Invalid or stale accident position");
  const ack = (event: AccidentEvent, duplicate: boolean, status = 200) =>
    json({ accepted: true, duplicate, event }, status);

  return async (request: Request): Promise<Response> => {
    const url = new URL(request.url);
    const identity = TEST_IDENTITIES.get(
      request.headers.get("Authorization") ?? "",
    );
    if (!identity) {
      return error(401, "TEST_IDENTITY_REQUIRED", "TEST identity required");
    }
    const resolveMatch = url.pathname.match(resolvePattern);
    const creating = url.pathname === collection;
    const querying = url.pathname === nearby;
    if (!creating && !querying && !resolveMatch) {
      return error(404, "NOT_FOUND", "Not found");
    }
    if (request.method !== "POST") {
      return error(405, "METHOD_NOT_ALLOWED", "Method not allowed");
    }
    if (
      (resolveMatch && identity !== "ADMIN") ||
      (!resolveMatch && identity === "ADMIN")
    ) return forbidden();
    if ([...url.searchParams].length) return invalid("Unexpected query fields");
    let value: unknown;
    try {
      const raw = await request.text();
      if (new TextEncoder().encode(raw).length > 8192) {
        return invalid("Request body too large");
      }
      value = JSON.parse(raw);
    } catch {
      return invalid("Invalid JSON");
    }
    if (!object(value)) return invalid();
    const clockNow = now();
    if (querying) {
      if (
        !exactKeys(value, ["requestId", "location"]) ||
        typeof value.requestId !== "string" ||
        !ACCIDENT_ID_PATTERN.test(value.requestId)
      ) return invalid("Invalid nearby request fields");
      if (
        !validAccidentLocation(value.location) ||
        value.location.receivedAt < clockNow - POSITION_FRESH_MS ||
        value.location.receivedAt > clockNow + FUTURE_TOLERANCE_MS ||
        (value.location.fixObservedAt !== null &&
          (value.location.fixObservedAt < clockNow - POSITION_FRESH_MS ||
            value.location.fixObservedAt > clockNow + FUTURE_TOLERANCE_MS))
      ) return invalidPosition();
      // This read never passes the receiver's location to durable storage.
      const location = value.location;
      return json({
        requestId: value.requestId,
        serverNow: clockNow,
        radiusMeters: ACCIDENT_RADIUS_METERS,
        complete: true,
        events: repository.snapshot().map((record) => record.event).filter((
          e,
        ) =>
          e.owner !== identity &&
          accidentDistanceMeters(location, e.location) <= ACCIDENT_RADIUS_METERS
        ),
      });
    }
    if (creating) {
      if (
        !exactKeys(value, ACCIDENT_EVENT_KEYS) ||
        typeof value.id !== "string" ||
        !ACCIDENT_ID_PATTERN.test(value.id) ||
        (value.owner !== "A" && value.owner !== "B")
      ) return invalid("Invalid event fields or identifier");
      if (value.owner !== identity) return forbidden();
      const body = value;
      try {
        return await repository.transact((records) => {
          const previous = records.find((record) =>
            record.event.id === body.id
          );
          if (previous) {
            if (previous.event.owner !== identity) {
              return { result: forbidden() };
            }
            // Original creation replay is safe after expiration or resolution.
            return {
              result: sameAccident(body, previous.created)
                ? ack(previous.event, true)
                : conflict(),
            };
          }
          if (body.version !== 1 || body.status !== "ACTIVE") {
            return { result: conflict() };
          }
          if (!validAccidentLocation(body.location)) {
            return { result: invalidPosition() };
          }
          if (
            !validAccidentEvent(body) ||
            body.occurredAt < clockNow - 86_400_000 ||
            body.occurredAt > clockNow + FUTURE_TOLERANCE_MS
          ) return { result: invalid("Invalid TEST accident") };
          if (body.expiresAt <= clockNow) {
            return {
              result: error(410, "EXPIRED_EVENT", "Event already expired"),
            };
          }
          if (records.length >= MAX_ACCIDENT_RECORDS) {
            return {
              result: error(
                429,
                "CAPACITY_EXCEEDED",
                "Local TEST session full",
              ),
            };
          }
          return {
            records: [...records, { created: body, event: body }],
            result: ack(body, false, 201),
          };
        });
      } catch {
        return error(
          503,
          "STORAGE_UNAVAILABLE",
          "Accident storage unavailable",
        );
      }
    }
    const id = resolveMatch![1];
    if (!ACCIDENT_ID_PATTERN.test(id)) {
      return invalid("Invalid accident identifier");
    }
    if (
      !exactKeys(value, ["expectedVersion", "reason"]) ||
      !Number.isSafeInteger(value.expectedVersion) ||
      (value.expectedVersion as number) < 1 ||
      value.reason !== "CONTROLLED_TEST"
    ) return invalid("Invalid terminal command fields");
    const command = value;
    try {
      return await repository.transact((records) => {
        const index = records.findIndex((record) => record.event.id === id);
        if (index < 0) {
          return { result: error(404, "NOT_FOUND", "Accident not found") };
        }
        const record = records[index];
        if (command.expectedVersion !== 1) return { result: conflict() };
        if (record.event.status === "RESOLVED") {
          return { result: ack(record.event, true) };
        }
        const event: AccidentEvent = {
          ...record.event,
          version: 2,
          status: "RESOLVED",
        };
        records[index] = { ...record, event };
        return { records, result: ack(event, false) };
      });
    } catch {
      return error(503, "STORAGE_UNAVAILABLE", "Accident storage unavailable");
    }
  };
}
