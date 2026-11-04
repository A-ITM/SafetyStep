// Public connectivity check. No database access, user data, secrets, or API calls.
Deno.serve((request: Request): Response => {
  if (request.method !== "GET") {
    return new Response(null, {
      status: 405,
      headers: { Allow: "GET" },
    });
  }

  return Response.json(
    { status: "ok", service: "pleos-app" },
    { headers: { "Cache-Control": "no-store" } },
  );
});
