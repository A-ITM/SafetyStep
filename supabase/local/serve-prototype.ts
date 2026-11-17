import { resolve } from "node:path";
import {
  createAccidentHandler,
  isAccidentRoute,
} from "../functions/_shared/accident.ts";
import { openAccidentRepository } from "../functions/_shared/accident_repository.ts";
import { createPrototypeHandler } from "../functions/_shared/prototype.ts";

// Bind loopback only. An optional isolated port supports HTTP smoke without sharing app state.
let port = 8787;
let dataPath = resolve("output/prototype-accidents.json");
const seen = new Set<string>();
for (const argument of Deno.args) {
  const match = argument.match(/^--(port|data)=(.+)$/);
  if (!match || seen.has(match[1])) {
    throw new Error(
      "Usage: serve-prototype.ts [--port=1..65535] [--data=path]",
    );
  }
  seen.add(match[1]);
  if (match[1] === "data") {
    dataPath = resolve(match[2]);
  } else {
    port = /^\d+$/.test(match[2]) ? Number(match[2]) : NaN;
  }
}
if (!Number.isInteger(port) || port < 1 || port > 65535) {
  throw new Error("Port must be an integer between 1 and 65535");
}
const accidents = createAccidentHandler(await openAccidentRepository(dataPath));
const legacy = createPrototypeHandler();
Deno.serve(
  { hostname: "127.0.0.1", port },
  (request) =>
    isAccidentRoute(new URL(request.url).pathname)
      ? accidents(request)
      : legacy(request),
);
