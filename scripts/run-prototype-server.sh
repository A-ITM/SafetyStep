#!/usr/bin/env bash
set -euo pipefail
repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${repo_dir}"
if command -v deno >/dev/null; then
  deno_command=(deno)
else
  deno_command=(npx --yes deno@2.9.6)
fi
exec "${deno_command[@]}" run --allow-net=127.0.0.1:8787 --config supabase/functions/deno.json supabase/local/serve-prototype.ts
