/** Local-only transport spike. No Supabase deployment or production authentication. */
export type Hazard = {
  id: string;
  owner: string;
  version: number;
  observedAt: number;
  expiresAt: number;
  road: string;
  direction: string;
  meters: number;
  status: "ACTIVE" | "RESOLVED";
  source: "MANUAL_TEST" | "SDK_AIRBAG" | "GLEO_REQUEST" | "OPERATOR_EVENT";
  simulation: true;
};

export const EVENT_TTL_MS = 180_000;
const allowedKeys = [
  "id",
  "owner",
  "version",
  "observedAt",
  "expiresAt",
  "road",
  "direction",
  "meters",
  "status",
  "source",
  "simulation",
];

export function createPrototypeHandler(now: () => number = Date.now) {
  const events = new Map<string, Hazard>();
  const epoch = crypto.randomUUID();
  const json = (data: unknown, status = 200) =>
    Response.json(data, { status, headers: { "Cache-Control": "no-store" } });

  return async (request: Request): Promise<Response> => {
    const url = new URL(request.url);
    if (url.pathname === "/health" && request.method === "GET") {
      return json({ status: "ok", mode: "local-simulation", epoch });
    }
    // Public fixture credentials distinguish two test clients; they are NOT secrets or Auth.
    const owner = ({
      "Bearer prototype-vehicle-A": "A",
      "Bearer prototype-vehicle-B": "B",
    } as Record<string, string>)[request.headers.get("Authorization") ?? ""];
    if (!owner) {
      return json({ error: "Prototype vehicle identity required" }, 401);
    }
    if (url.pathname !== "/prototype/incidents") {
      return json({ error: "Not found" }, 404);
    }
    if (request.method === "GET") {
      return json({
        epoch,
        mode: "local-simulation",
        events: [...events.values()],
      });
    }
    if (request.method !== "POST") {
      return json({ error: "Method not allowed" }, 405);
    }
    const raw = await request.text();
    if (raw.length > 4096) return json({ error: "Too large" }, 413);
    let e: Hazard;
    try {
      e = JSON.parse(raw);
    } catch {
      return json({ error: "Invalid JSON" }, 400);
    }
    if (
      !e || typeof e !== "object" || Array.isArray(e) ||
      Object.keys(e).some((key) => !allowedKeys.includes(key)) ||
      typeof e.id !== "string" || !/^[a-zA-Z0-9-]{1,80}$/.test(e.id) ||
      e.owner !== owner || e.simulation !== true ||
      !Number.isSafeInteger(e.version) || e.version < 1 ||
      !Number.isSafeInteger(e.observedAt) ||
      !Number.isSafeInteger(e.expiresAt) ||
      e.observedAt > now() + 5000 || e.observedAt < now() - 86_400_000 ||
      e.expiresAt !== e.observedAt + EVENT_TTL_MS ||
      e.road !== "test-highway" || !["east", "west"].includes(e.direction) ||
      typeof e.meters !== "number" || !Number.isFinite(e.meters) ||
      e.meters < 0 || e.meters > 10000 ||
      !["ACTIVE", "RESOLVED"].includes(e.status) ||
      !["MANUAL_TEST", "SDK_AIRBAG", "GLEO_REQUEST", "OPERATOR_EVENT"].includes(
        e.source,
      )
    ) {
      return json(
        { error: "Invalid simulation event or forbidden fields" },
        400,
      );
    }
    if (e.status === "ACTIVE" && e.expiresAt <= now()) {
      return json({ error: "Expired event" }, 410);
    }
    const previous = events.get(e.id);
    if (previous) {
      if (previous.owner !== owner) {
        return json({ error: "Not event owner" }, 403);
      }
      if (
        e.version === previous.version &&
        allowedKeys.every((key) =>
          e[key as keyof Hazard] === previous[key as keyof Hazard]
        )
      ) {
        return json({ accepted: true, duplicate: true, event: previous });
      }
      const immutable = [
        "observedAt",
        "expiresAt",
        "road",
        "direction",
        "meters",
        "source",
      ] as const;
      if (
        e.version !== previous.version + 1 || previous.status !== "ACTIVE" ||
        e.status !== "RESOLVED" || immutable.some((key) =>
          e[key] !== previous[key]
        )
      ) {
        return json({ error: "Version conflict or invalid transition" }, 409);
      }
    } else {
      if (e.version !== 1 || e.status !== "ACTIVE") {
        return json({ error: "Unknown incident" }, 409);
      }
      if (events.size >= 200) {
        return json({ error: "Local session full; restart test server" }, 429);
      }
    }
    events.set(e.id, e);
    return json(
      { accepted: true, duplicate: false, event: e },
      previous ? 200 : 201,
    );
  };
}
