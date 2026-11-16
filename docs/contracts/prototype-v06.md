# Prototype 0.6 wire and integration contract — version 1

2026-11-17. Applies only to loopback TEST transport, foreground demo/pleosDebug and the scope in [the 0.6 plan](../safetystep-prototype-v06-plan.md). This is not production Auth, GPS validation or a live public feed. Shared examples are in [prototype-v06.json](fixtures/prototype-v06.json). Root owns this contract and fixture; workers request changes instead of editing them independently.

## Types and clock

UTC epoch milliseconds, meters, nonnegative meters/second. TEST directions increase along `meters` in the direction of travel. `test-highway` and `parallel-highway` are known query roads, `east/west` known directions, valid meters 0–10000. Create events only on test-highway. Future tolerance 5000ms, position freshness 15000ms. Unknown receiver speed is null in app state and omitted from GET; not zero. No unknown sensor value is silently converted to safe/empty/stationary.

Every HTTP response is JSON with Cache-Control:no-store. A/B use public fixture headers `Bearer prototype-vehicle-A` / `Bearer prototype-vehicle-B`. Local controlled resolution uses `Bearer prototype-test-admin`. These are identifiers, not secrets or authentication suitable for public deployment. Bind 127.0.0.1 only.

## Create and retry

`POST /prototype/incidents` has exactly these required fields:

```json
{"id":"test-stop-001","owner":"A","version":1,"eventType":"STOPPED_VEHICLE","status":"ACTIVE","source":"STOP_OBSERVATION","simulation":true,"observedAt":1800000005000,"expiresAt":1800000185000,"road":"test-highway","direction":"east","meters":1000}
```

- ID: 1–80 alphanumeric/hyphen characters. Owner must match A/B. version 1 and ACTIVE at creation. simulation must be true. Unknown/additional fields are rejected.
- eventType: ACCIDENT or STOPPED_VEHICLE. source: MANUAL_TEST, SDK_AIRBAG, GLEO_REQUEST, OPERATOR_EVENT, STOP_OBSERVATION. STOP_OBSERVATION is only STOPPED_VEHICLE. When creating a new shared event, explicit accident reports map to ACCIDENT and breakdown/help/qualified stop to STOPPED_VEHICLE; an arbitrary source name does not verify an accident. If this continuous stop already has an ACTIVE STOPPED_VEHICLE, a later accident/help report changes local support context only and reuses that shared ID/type. Do not create a duplicate or mutate type. A genuinely separate event needs an explicit new-event input, not a late answer.
- observedAt is the latest observation used for creation, not the beginning of the stop. That earlier time stays in local metrics linked by incident ID. Accept safe-integer observations no more than 1 day old or 5 seconds in the future; expiresAt is observedAt+180000. A new ACTIVE event already expired is 410.
- On new accepted event: 201 `{accepted:true,duplicate:false,event:Hazard}`. Exact replay of the current created record: 200 `{accepted:true,duplicate:true,event:Hazard}`. Match replay before the expiry test, so an accepted event's ACK retry still succeeds after TTL.
- Same ID but changed payload, reversed version or attempted status update: 409. Another owner cannot overwrite an ID: 403. Event ID, position, type, source and observation times are immutable.
- No ACTIVE heartbeat/update in 0.6. Long stops become STALE after 180 seconds; do not manufacture new observations/IDs to evade this limit. Store at most 200 records; capacity returns 429, without deleting ACTIVE records to make room.
- A help utterance alone does not qualify sharing. Consent, valid location and stationary observation conditions are evaluated independently of words/response; local support can run without a shared event.

## Retraction and controlled resolution

`POST /prototype/incidents/{id}/retract`, A/B owner only:

```json
{"expectedVersion":1,"evidence":{"source":"TEST","startedAt":1800000008000,"observedAt":1800000011000,"speedMps":2.5,"durationMs":3000}}
```

Only ACTIVE STOPPED_VEHICLE can transition to RETRACTED. Require expectedVersion=currentVersion, evidence source TEST, safe-integer timestamps, startedAt > event.observedAt, observedAt-startedAt=durationMs≥3000, speedMps≥2 and finite, observation within 15000ms old/5000ms future. The Android policy verifies continuous observations; server validates this TEST claim's consistency, not physical sensor truth. Transition changes status and version (+1), retaining original fields. Exact terminal command replay is duplicate ACK, other/stale commands 409. Wrong owner/type/role is 403. TTL expiry alone is not retraction.

`POST /prototype/admin/incidents/{id}/resolve`, test-admin only:

```json
{"expectedVersion":1,"reason":"CONTROLLED_TEST"}
```

Only ACTIVE ACCIDENT transitions to RESOLVED/+1. Exact command retry is duplicate ACK. A/B cannot resolve an accident; no admin screen is required. Missing incident is 404. Unknown request/evidence fields are rejected. Neither terminal operation changes a local occupant support session or another event.

Both terminal endpoints return HTTP 200 `{accepted:true,duplicate:false,event:Hazard}` on first success and HTTP 200 `{accepted:true,duplicate:true,event:Hazard}` on exact retry. `event` contains the complete resulting terminal record, without command evidence or occupant details.

After identity/ownership and request-shape validation, match a previously accepted exact command before testing its old expectedVersion or observation freshness. A delayed ACK retry must not become an expired-evidence failure. Android validates returned ID, owner, resulting type/status and expectedVersion+1 before removing that command.

## Scoped GET

```text
GET /prototype/incidents?requestId=poll-001&road=test-highway&direction=east&meters=600&observedAt=1800000010000&speedMps=25
```

Required: requestId (same ID character rules), road, direction, meters, observedAt. Optional speedMps; omit for unknown. Reject duplicates/unknown query keys, blank/NaN/infinite/out-of-range values and invalid freshness. A known parallel-highway query is a valid empty snapshot, not an invalid road. Current position must be genuine TEST input; app polling must not refresh an old position timestamp.

Return only same-road, same-direction records where `0 < event.meters - query.meters <= 1000`. Apply this to ACTIVE/STALE and terminal records alike. Do not send unrelated detail and expect Android to hide it. Response:

```json
{"requestId":"poll-001","epoch":"server-start-uuid","scopeKey":"v1:test-highway:east","scopeVersion":1,"serverNow":1800000010000,"complete":true,"sourceHealth":"TEST_ONLY","events":[]}
```

epoch is stable for one server process. scopeKey is `v1:{road}:{direction}`, independent of meters. complete is mandatory boolean true, sourceHealth exactly TEST_ONLY. Never truncate and label complete. TTL does not change lifecycle or delete a record.

## Errors and Android application

Error shape: `{error:{code:string,message:string}}`.

| Status | Code |
| --- | --- |
| 400 | INVALID_REQUEST |
| 401 | TEST_IDENTITY_REQUIRED |
| 403 | FORBIDDEN |
| 404 | NOT_FOUND |
| 409 | VERSION_CONFLICT |
| 410 | EXPIRED_EVENT |
| 422 | INVALID_POSITION |
| 429 | CAPACITY_EXCEEDED |
| 405 | METHOD_NOT_ALLOWED |

Android uploader and receiver have separate sequential jobs, each with at most one request in flight. Network/5xx/429 can retry with a bounded delay; other permanent rejection is recorded and quarantined rather than blocking GET or being silently discarded. Accept only the matching ACK, removing only that command from outbox. Distinguish CREATE/RETRACT commands in persistence.

Snapshot application order:

1. Validate the whole envelope and every event before touching state, including requested scope, allowed source/type, simulation, numbers, lifecycle and version. One invalid item rejects the whole response.
2. Discard response if captured requestId, scope or contextGeneration is obsolete. generation changes on road/direction changes, explicit scenario/position reset, receiver off/on or lifecycle restart; normal trajectory ticks retain generation.
3. Within the same epoch, any lower version for a known ID rejects the response and marks freshness unconfirmed. Keep per-epoch version tombstones even when an ID leaves the displayed snapshot so a terminal event cannot resurrect.
4. A valid complete list replaces the current display set atomically. Absence means not currently published in this scope, never RESOLVED. Individual terminal states remove only their warning.
5. ACTIVE with serverNow>=expiresAt is STALE. Advance freshness using elapsed monotonic time after receive; never make a stale event fresh by polling or changing the device clock.
6. New epoch means server data reset. Clear snapshot/version baselines, apply the new list, record a reset diagnostic. It is not road safety confirmation. Retain completed-spoken event IDs across epoch changes.
7. HTTP/parse/complete=false failure preserves relevant existing cards as latest-state-unconfirmed, disables fresh-warning success claims, and does not apply an empty list.

## Warning and recording

Valid location with unknown speed may still receive/show a card. Use 1000m initial warning distance and no low-speed suppression for unknown speed. Known speed follows the 0.6 plan. Warn at most once per event ID after actual voice completion; store the IDs across app restart. Failures/interruptions do not mark spoken, but automatic retry must be bounded (one further attempt per foreground session, no repeat loop). A stopped-event low-speed hold is not a completed warning.

Warning received and actual speech completed are separate metrics. No TTS implementation means no voice success. The foreground support session takes priority over receiver TTS; do not cancel urgent occupant help to speak a following-car warning.

## Operator TEST input and integration ownership

Extend the existing debug emulator-only OPERATOR broadcast with `command=position`, string extras `road`, `direction`, `roadKind` (MAINLINE/SHOULDER/FACILITY/UNKNOWN), `meters`, `speedMps` (omit for unknown), `observedAt`, and optional `reset=true`. Input source is always TEST. `reset=true` changes generation, consecutive trajectory ticks do not. Invalid input does not inherit a fresh previous coordinate. Add `command=receiver_enabled,value=true/false`; shared consent remains separate. No package/serial is hardcoded as proof of emulator: existing ranchu/goldfish checks stay.

QA drives a constant-speed trajectory with newly observed TEST points until it actually crosses event.meters. Log crossing with runId/incidentId and UTC/monotonic clocks, as well as received/voice-complete. Report measured Y only from that crossing; static distance/speed is projected TTC. H pairs first_stop_observed with the receiving event ID; supportSessionId is optional. Same AVD boot clock may be compared explicitly; different-device monotonic clocks must not be subtracted.

A owns PrototypeUi/VM/network/store/state and publishes new fields/method signatures before C binds UI. C owns `speech/` and `location/` gateways (including flavor factories) and `ui/`; A wires them. Required semantics: receiver enabled/status; nullable warning distance/type; report location data/status; stop-candidate/shared ID/status; actual voice timing. Keep legacy API signatures where inexpensive, but no old automatic-control path is reachable from the new normal flow. Changes to MainActivity, manifests or build versions belong to A. B owns server/tests and the HTTP smoke; root owns this shared fixture and final integration tooling/review coordination.
