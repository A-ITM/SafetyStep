import {
  type AccidentCloudConfig,
  createAccidentCloudHandler,
} from "../_shared/accident_cloud.ts";

const config: AccidentCloudConfig = {
  supabaseUrl: Deno.env.get("SUPABASE_URL") ?? "",
  serviceRoleKey: Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "",
  userAId: Deno.env.get("ACCIDENT_PILOT_USER_A_ID") ?? "",
  userBId: Deno.env.get("ACCIDENT_PILOT_USER_B_ID") ?? "",
};

// Fail closed if either designated Auth user or the server database key is absent.
Deno.serve(createAccidentCloudHandler(config));
