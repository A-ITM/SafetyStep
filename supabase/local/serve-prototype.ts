import { createPrototypeHandler } from "../functions/_shared/prototype.ts";

// Bind loopback only. An optional isolated port supports HTTP smoke without sharing app state.
const portArgument = Deno.args[0] ?? "--port=8787";
const port = /^--port=\d+$/.test(portArgument)
  ? Number(portArgument.slice(7))
  : NaN;
if (
  Deno.args.length > 1 || !Number.isInteger(port) || port < 1 || port > 65535
) {
  throw new Error("Usage: serve-prototype.ts [--port=1..65535]");
}
Deno.serve({ hostname: "127.0.0.1", port }, createPrototypeHandler());
