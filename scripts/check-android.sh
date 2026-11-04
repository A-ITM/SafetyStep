#!/usr/bin/env bash
set -euo pipefail
repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
host_jdk17="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"

if [[ "${HOME}" == "/Users/yeongj" ]]; then
  export JAVA_HOME="${host_jdk17}"
  export GRADLE_USER_HOME="/Users/yeongj/.gradle"
  export ANDROID_HOME="${ANDROID_HOME:-/Users/yeongj/Library/Android/sdk}"
elif [[ -z "${JAVA_HOME:-}" && -d "${host_jdk17}" ]]; then
  export JAVA_HOME="${host_jdk17}"
fi
if [[ -z "${JAVA_HOME:-}" || ! -x "${JAVA_HOME}/bin/java" ]]; then
  echo "Set JAVA_HOME to an existing JDK 17 installation." >&2
  exit 1
fi
cd "${repo_dir}/android"
exec ./gradlew --no-daemon lintDemoDebug lintPleosDebug assembleDemoDebug assemblePleosDebug "$@"
