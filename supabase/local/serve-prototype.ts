import { createPrototypeHandler } from "../functions/_shared/prototype.ts";

// Bind loopback only. Android connects through adb reverse, never a public service.
Deno.serve({ hostname: "127.0.0.1", port: 8787 }, createPrototypeHandler());
