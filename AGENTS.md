# Development instructions

- Keep vehicle access in the Android app behind `VehicleGateway`.
- Keep the `demo` flavor runnable without Pleos services or backend credentials. Label sample data explicitly.
- The `pleos` flavor must report SDK errors and unavailable capabilities; never substitute sample data silently.
- Add only permissions needed by implemented features. The starter reads door state and never controls the vehicle.
- Keep shared backend code in `supabase/`. Add database schemas and user authentication when product requirements are defined.
- Never put secret/service-role keys, database passwords, or external API secrets in the Android app. Mobile configuration is public even when injected during the build.
- Verify Android changes with `./scripts/check-android.sh`. Validate Edge Functions with Deno format/lint/type checks.

## Host Java and Gradle storage

- Reuse centrally installed JDKs. On the project owner's Mac, JDK 17 is `/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home`.
- Required JDKs already listed in `/Users/yeongj/.gradle/gradle.properties` must not be downloaded, copied, or unpacked again.
- Host builds on that Mac must use `GRADLE_USER_HOME=/Users/yeongj/.gradle`. Never create a task-specific Gradle home or Docker Gradle volume.
- Android uses JDK 17 with the pinned Gradle/AGP versions. Do not switch this build to Java 25 without verifying Gradle and AGP compatibility.
- GitHub-hosted CI uses its own runner JDK and shared Gradle cache.
