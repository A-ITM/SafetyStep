/** Strict, public-fixture data for the loopback accident-location prototype. */
export type AccidentLocation = {
  latitude: number;
  longitude: number;
  receivedAt: number;
  fixObservedAt: number | null;
  accuracyMeters: number | null;
  headingDegrees: number | null;
  address: string | null;
  origin: "GPS" | "NAVI" | "TEST";
  mock: boolean;
};

export type AccidentEvent = {
  id: string;
  owner: "A" | "B";
  version: number;
  eventType: "ACCIDENT";
  status: "ACTIVE" | "RESOLVED";
  source: "MANUAL_TEST" | "SDK_AIRBAG" | "GLEO_REQUEST" | "OPERATOR_EVENT";
  simulation: true;
  occurredAt: number;
  expiresAt: number;
  location: AccidentLocation;
};

export const ACCIDENT_TTL_MS = 300_000;
export const ACCIDENT_RADIUS_METERS = 1_000;
export const MAX_ACCIDENT_RECORDS = 500;
export const POSITION_FRESH_MS = 15_000;
export const FUTURE_TOLERANCE_MS = 5_000;
export const ACCIDENT_ID_PATTERN = /^[a-zA-Z0-9-]{1,80}$/;
export const ACCIDENT_EVENT_KEYS = [
  "id",
  "owner",
  "version",
  "eventType",
  "status",
  "source",
  "simulation",
  "occurredAt",
  "expiresAt",
  "location",
] as const;
export const ACCIDENT_LOCATION_KEYS = [
  "latitude",
  "longitude",
  "receivedAt",
  "fixObservedAt",
  "accuracyMeters",
  "headingDegrees",
  "address",
  "origin",
  "mock",
] as const;

export type ObjectValue = Record<string, unknown>;
export function object(value: unknown): value is ObjectValue {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}
export function exactKeys(
  value: ObjectValue,
  keys: readonly string[],
): boolean {
  const found = Object.keys(value);
  return found.length === keys.length &&
    found.every((key) => keys.includes(key));
}
export function finite(value: unknown): value is number {
  return typeof value === "number" && Number.isFinite(value);
}
export function timestamp(value: unknown): value is number {
  return typeof value === "number" && Number.isSafeInteger(value) && value >= 0;
}

export function validAccidentLocation(
  value: unknown,
): value is AccidentLocation {
  if (
    !object(value) || !exactKeys(value, ACCIDENT_LOCATION_KEYS) ||
    !finite(value.latitude) || value.latitude < -90 || value.latitude > 90 ||
    !finite(value.longitude) || value.longitude < -180 ||
    value.longitude > 180 || !timestamp(value.receivedAt) ||
    !["GPS", "NAVI", "TEST"].includes(String(value.origin)) ||
    typeof value.origin !== "string" || typeof value.mock !== "boolean" ||
    !(value.address === null ||
      (typeof value.address === "string" && value.address.length <= 300)) ||
    !(value.headingDegrees === null ||
      (finite(value.headingDegrees) && value.headingDegrees >= 0 &&
        value.headingDegrees < 360))
  ) return false;
  if (
    value.fixObservedAt !== null &&
    (!timestamp(value.fixObservedAt) ||
      value.fixObservedAt < value.receivedAt - POSITION_FRESH_MS ||
      value.fixObservedAt > value.receivedAt + FUTURE_TOLERANCE_MS)
  ) return false;
  if (
    value.accuracyMeters !== null &&
    (!finite(value.accuracyMeters) || value.accuracyMeters <= 0 ||
      value.accuracyMeters > 100)
  ) return false;
  return value.origin !== "GPS" ||
    (value.fixObservedAt !== null && value.accuracyMeters !== null);
}

/** Does not apply current time: persisted and frozen accident fixes may be old. */
export function validAccidentEvent(value: unknown): value is AccidentEvent {
  if (
    !object(value) || !exactKeys(value, ACCIDENT_EVENT_KEYS) ||
    typeof value.id !== "string" || !ACCIDENT_ID_PATTERN.test(value.id) ||
    (value.owner !== "A" && value.owner !== "B") ||
    value.eventType !== "ACCIDENT" || value.simulation !== true ||
    typeof value.source !== "string" ||
    !["MANUAL_TEST", "SDK_AIRBAG", "GLEO_REQUEST", "OPERATOR_EVENT"].includes(
      value.source,
    ) ||
    !timestamp(value.occurredAt) || !timestamp(value.expiresAt) ||
    value.expiresAt !== value.occurredAt + ACCIDENT_TTL_MS ||
    !validAccidentLocation(value.location) ||
    value.location.receivedAt < value.occurredAt - POSITION_FRESH_MS ||
    value.location.receivedAt > value.occurredAt + 30_000
  ) return false;
  return (value.status === "ACTIVE" && value.version === 1) ||
    (value.status === "RESOLVED" && value.version === 2);
}

export function sameAccident(left: unknown, right: AccidentEvent): boolean {
  return object(left) && exactKeys(left, ACCIDENT_EVENT_KEYS) &&
    ACCIDENT_EVENT_KEYS.every((key) =>
      key === "location"
        ? object(left.location) &&
          exactKeys(left.location, ACCIDENT_LOCATION_KEYS) &&
          ACCIDENT_LOCATION_KEYS.every((field) =>
            (left.location as ObjectValue)[field] === right.location[field]
          )
        : left[key] === right[key]
    );
}

/** Haversine great-circle distance, with no claim about roads or direction. */
export function accidentDistanceMeters(
  a: AccidentLocation,
  b: AccidentLocation,
): number {
  const radians = Math.PI / 180;
  const latitudeDelta = (b.latitude - a.latitude) * radians;
  const longitudeDelta = (b.longitude - a.longitude) * radians;
  const term = Math.sin(latitudeDelta / 2) ** 2 +
    Math.cos(a.latitude * radians) * Math.cos(b.latitude * radians) *
      Math.sin(longitudeDelta / 2) ** 2;
  return 6_371_000 * 2 * Math.asin(Math.sqrt(Math.min(1, Math.max(0, term))));
}
