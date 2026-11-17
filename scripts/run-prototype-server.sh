#!/usr/bin/env bash
set -euo pipefail
repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${repo_dir}"
port=8787
data_path="${repo_dir}/output/prototype-accidents.json"
seen_port=0
seen_data=0
for argument in "$@"; do
  case "$argument" in
    --port=*)
      if [[ "$seen_port" == 1 ]]; then
        echo "Duplicate --port option" >&2
        exit 2
      fi
      seen_port=1
      port="${argument#--port=}"
      ;;
    --data=*)
      if [[ "$seen_data" == 1 ]]; then
        echo "Duplicate --data option" >&2
        exit 2
      fi
      seen_data=1
      data_path="${argument#--data=}"
      ;;
    *)
      echo "Usage: run-prototype-server.sh [--port=1..65535] [--data=path]" >&2
      exit 2
      ;;
  esac
done
if [[ ! "$port" =~ ^[0-9]{1,5}$ ]] || (( 10#$port < 1 || 10#$port > 65535 )); then
  echo "Port must be an integer between 1 and 65535" >&2
  exit 2
fi
if [[ -z "$data_path" || "$data_path" == *,* ]]; then
  echo "Data path must be nonempty and contain no commas" >&2
  exit 2
fi
if [[ "$data_path" != /* ]]; then
  data_path="${repo_dir}/${data_path}"
fi
data_dir="$(dirname "$data_path")"
if command -v deno >/dev/null; then
  deno_command=(deno)
else
  deno_command=(npx --yes deno@2.9.6)
fi
# Atomic replacement needs sibling temporary files and a directory fsync.
exec "${deno_command[@]}" run --allow-net="127.0.0.1:${port}" \
  --allow-read="${data_dir}" --allow-write="${data_dir}" \
  --config supabase/functions/deno.json supabase/local/serve-prototype.ts \
  "--port=${port}" "--data=${data_path}"
