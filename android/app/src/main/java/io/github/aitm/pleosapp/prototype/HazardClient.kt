package io.github.aitm.pleosapp.prototype

import android.content.Context
import io.github.aitm.pleosapp.BuildConfig
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class HazardHttpException(val status: Int, val code: String, message: String) : Exception(message) {
    val retryable: Boolean get() = status == 429 || status >= 500
}
enum class CommandKind { CREATE, RETRACT }
data class HazardCommand(val kind: CommandKind, val event: Hazard, val payload: JSONObject) {
    val key: String get() = "${kind.name}:${event.id}:${event.version}"
    fun json(): JSONObject = JSONObject().put("kind", kind.name).put("event", event.toJson()).put("payload", payload)
    companion object {
        fun fromJson(o: JSONObject): HazardCommand = HazardCommand(CommandKind.valueOf(o.getString("kind")), hazardFromJson(o.getJSONObject("event")), o.getJSONObject("payload"))
    }
}

/** Loopback TEST transport. The caller supplies a genuinely observed position on every GET. */
class HazardClient(private val owner: String, private val baseUrl: String = "http://127.0.0.1:8787") {
    suspend fun post(payload: JSONObject): JSONObject = request("POST", "/prototype/incidents", payload)
    suspend fun send(command: HazardCommand): Hazard {
        val path = if (command.kind == CommandKind.CREATE) "/prototype/incidents" else "/prototype/incidents/${command.event.id}/retract"
        val ack = request("POST", path, command.payload)
        require(ack.keys().asSequence().toSet() == setOf("accepted", "duplicate", "event") && ack.get("accepted") == true && ack.get("duplicate") is Boolean) { "접수 응답 형식 오류" }
        val result = hazardFromJson(ack.getJSONObject("event"))
        val expected = if (command.kind == CommandKind.CREATE) command.event else command.event.copy(status = HazardState.RETRACTED, version = command.event.version + 1)
        require(result == expected) { "접수 응답의 사건·소유자·유형·버전 불일치" }
        return result
    }
    suspend fun list(context: PollContext): HazardSnapshot {
        val p = context.position
        val values = linkedMapOf("requestId" to context.requestId, "road" to p.road, "direction" to p.direction,
            "meters" to p.meters.toString(), "observedAt" to p.observedAt.toString())
        p.speedMps?.let { values["speedMps"] = it.toString() }
        val query = values.entries.joinToString("&") { "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}" }
        return snapshotFromJson(request("GET", "/prototype/incidents?$query"), context)
    }

    private suspend fun request(method: String, path: String, body: JSONObject? = null): JSONObject = withContext(Dispatchers.IO) {
        check(BuildConfig.DEBUG) { "로컬 전파는 debug 전용입니다." }
        require(owner in setOf("A", "B"))
        val connection = URL(baseUrl + path).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 2_000; connection.readTimeout = 2_000
            connection.setRequestProperty("Authorization", "Bearer prototype-vehicle-$owner")
            connection.setRequestProperty("Connection", "close")
            connection.setRequestProperty("Cache-Control", "no-store")
            if (body != null) {
                connection.doOutput = true; connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            require(text.length <= 200_000) { "응답 크기 초과" }
            if (status !in 200..299) {
                val error = runCatching { JSONObject(text).getJSONObject("error") }.getOrNull()
                throw HazardHttpException(status, error?.optString("code") ?: "HTTP_ERROR", "HTTP $status: ${error?.optString("message") ?: "요청 실패"}")
            }
            require(connection.getHeaderField("Cache-Control")?.split(',')?.any { it.trim().equals("no-store", true) } == true) { "캐시 금지 응답 확인 실패" }
            JSONObject(text)
        } finally { connection.disconnect() }
    }
}

private val idPattern = Regex("[A-Za-z0-9-]{1,80}")
private val allowedSources = setOf("MANUAL_TEST", "SDK_AIRBAG", "GLEO_REQUEST", "OPERATOR_EVENT", "STOP_OBSERVATION")
private val eventFields = setOf("id", "owner", "version", "eventType", "status", "source", "simulation", "observedAt", "expiresAt", "road", "direction", "meters")
private fun JSONObject.exact(fields: Set<String>) { require(keys().asSequence().toSet() == fields) { "필수/추가 필드 오류" } }
private fun JSONObject.string(key: String): String = (get(key) as? String) ?: error("$key 문자열 오류")
private fun JSONObject.integer(key: String): Long {
    val value = get(key)
    require(value is Number) { "$key 숫자 오류" }
    val d = value.toDouble()
    require(d.isFinite() && d >= 0 && d <= 9_007_199_254_740_991.0 && d % 1.0 == 0.0) { "$key 정수 오류" }
    return value.toLong()
}
fun hazardFromJson(o: JSONObject): Hazard {
    o.exact(eventFields)
    val id = o.string("id"); val owner = o.string("owner")
    val version = o.integer("version"); val observed = o.integer("observedAt"); val expires = o.integer("expiresAt")
    val type = EventType.valueOf(o.string("eventType")); val status = HazardState.valueOf(o.string("status"))
    val source = o.string("source"); val road = o.string("road"); val direction = o.string("direction")
    val metersValue = o.get("meters"); require(metersValue is Number)
    val meters = metersValue.toDouble()
    require(id.matches(idPattern) && owner in setOf("A", "B") && o.get("simulation") == true)
    require(observed > 0 && expires == observed + 180_000 && road == "test-highway" && direction in setOf("east", "west") && meters.isFinite() && meters in 0.0..10_000.0)
    require(source in allowedSources && (source != "STOP_OBSERVATION" || type == EventType.STOPPED_VEHICLE))
    require((status == HazardState.ACTIVE && version == 1L) ||
        (status == HazardState.RETRACTED && type == EventType.STOPPED_VEHICLE && version == 2L) ||
        (status == HazardState.RESOLVED && type == EventType.ACCIDENT && version == 2L))
    return Hazard(id, owner, version.toInt(), observed, expires, road, direction, meters, status, source, type, true)
}
fun snapshotFromJson(o: JSONObject, context: PollContext): HazardSnapshot {
    o.exact(setOf("requestId", "epoch", "scopeKey", "scopeVersion", "serverNow", "complete", "sourceHealth", "events"))
    require(o.string("requestId") == context.requestId && context.requestId.matches(idPattern))
    require(o.string("scopeKey") == context.position.scopeKey && o.integer("scopeVersion") == 1L)
    require(o.get("complete") == true && o.string("sourceHealth") == "TEST_ONLY")
    val epoch = o.string("epoch"); require(epoch.isNotBlank() && epoch.length <= 100)
    val serverNow = o.integer("serverNow"); require(serverNow > 0)
    val raw = o.getJSONArray("events"); require(raw.length() <= 200)
    val events = (0 until raw.length()).map { hazardFromJson(raw.getJSONObject(it)) }
    require(events.map { it.id }.distinct().size == events.size) { "중복 사건 ID" }
    require(events.all { it.road == context.position.road && it.direction == context.position.direction &&
        it.meters - context.position.meters > 0 && it.meters - context.position.meters <= 1_000 && it.observedAt <= serverNow + 5_000 }) { "조회 범위 밖 사건" }
    return HazardSnapshot(context.requestId, epoch, context.position.scopeKey, 1, serverNow, true, "TEST_ONLY", events)
}
fun Hazard.toJson(): JSONObject = JSONObject().apply {
    put("id", id); put("owner", owner); put("version", version); put("eventType", eventType.name)
    put("observedAt", observedAt); put("expiresAt", expiresAt); put("road", road); put("direction", direction)
    put("meters", meters); put("status", status.name); put("source", source); put("simulation", simulation)
}
fun Incident.toLocalJson(): JSONObject = JSONObject().apply {
    put("id", id); put("observedAt", observedAt); put("source", source); put("eventType", eventType.name)
    put("response", response.name); put("responder", responder.name); put("help", help.name)
    put("evacuated", evacuated); put("hazard", hazard.name); put("version", version)
}
fun incidentFromJson(o: JSONObject) = Incident(
    o.getString("id"), o.getLong("observedAt"), o.getString("source"), ResponseState.valueOf(o.getString("response")),
    Responder.valueOf(o.getString("responder")), HelpState.valueOf(o.getString("help")), o.getBoolean("evacuated"),
    HazardState.valueOf(o.getString("hazard")), o.getInt("version"), EventType.valueOf(o.optString("eventType", "STOPPED_VEHICLE")),
)
/** Compatibility helper for test fixtures; application sharing always supplies an observed RoadPosition. */
fun Incident.toSharedJson(owner: String): JSONObject = Hazard(id, owner, 1, observedAt, observedAt + 180_000,
    "test-highway", "east", 1_000.0, HazardState.ACTIVE, source, eventType).toJson()

class PrototypeStore(context: Context, preferencesName: String = "prototype") {
    private val prefs = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    fun incident(): Incident? = runCatching { prefs.getString("incident", null)?.let { incidentFromJson(JSONObject(it)) } }.getOrNull()
    fun saveIncident(value: Incident?) { prefs.edit().putString("incident", value?.toLocalJson()?.toString()).apply() }
    fun outbox(): List<JSONObject> = runCatching {
        val a = JSONArray(prefs.getString("outbox-v06", "[]")); (0 until a.length()).map { a.getJSONObject(it) }
    }.getOrDefault(emptyList())
    fun saveOutbox(values: List<JSONObject>) { check(prefs.edit().putString("outbox-v06", JSONArray(values).toString()).commit()) }
    fun quarantine(command: HazardCommand, reason: String) {
        val a = JSONArray(prefs.getString("quarantine-v06", "[]"))
        a.put(command.json().put("rejection", reason.take(200)))
        check(prefs.edit().putString("quarantine-v06", a.toString()).commit())
    }
    fun sharedStop(): Hazard? = runCatching { prefs.getString("shared-stop-v06", null)?.let { hazardFromJson(JSONObject(it)) } }.getOrNull()
    fun saveSharedStop(event: Hazard?) { check(prefs.edit().putString("shared-stop-v06", event?.toJson()?.toString()).commit()) }
    var stopReportStatus: StopReportStatus
        get() = runCatching { StopReportStatus.valueOf(prefs.getString("stop-report-status-v06", "NONE")!!) }.getOrDefault(StopReportStatus.NONE)
        set(value) { check(prefs.edit().putString("stop-report-status-v06", value.name).commit()) }
    fun saveTransport(event: Hazard?, status: StopReportStatus, commands: List<HazardCommand>) {
        check(prefs.edit().putString("shared-stop-v06", event?.toJson()?.toString())
            .putString("stop-report-status-v06", status.name)
            .putString("outbox-v06", JSONArray(commands.map { it.json() }).toString()).commit())
    }
    fun spokenIds(): Set<String> = prefs.getStringSet("spoken-event-ids-v06", emptySet())!!.toSet()
    fun markSpoken(id: String) { check(prefs.edit().putStringSet("spoken-event-ids-v06", spokenIds() + id).commit()) }
    var receiverEnabled: Boolean
        get() = prefs.getBoolean("receiver-enabled-v06", false)
        set(value) { check(prefs.edit().putBoolean("receiver-enabled-v06", value).commit()) }
    var consent: Boolean
        get() = prefs.getBoolean("consent", false)
        set(value) { prefs.edit().putBoolean("consent", value).apply() }
    var lastDeployment: Boolean?
        get() = if (prefs.contains("airbag")) prefs.getBoolean("airbag", false) else null
        set(value) { if (value != null) prefs.edit().putBoolean("airbag", value).apply() }
    var theme: ThemePreference
        get() = runCatching { ThemePreference.valueOf(prefs.getString("theme", "SYSTEM")!!) }.getOrDefault(ThemePreference.SYSTEM)
        set(value) { prefs.edit().putString("theme", value.name).apply() }
    fun journey(incidentId: String?): SupportJourney = runCatching {
        val o = JSONObject(prefs.getString("journey", "{}")!!)
        if (incidentId == null || o.optString("incidentId") != incidentId) return SupportJourney()
        SupportJourney(collision = CollisionContext.valueOf(o.optString("collision", "UNKNOWN")),
            departureDeclared = o.optBoolean("departureDeclared"), trunk = ControlState.HELD,
            trunkNote = "이 프로토타입에서는 트렁크를 제어하지 않아요")
    }.getOrDefault(SupportJourney())
    fun saveJourney(incidentId: String?, value: SupportJourney) {
        val o = JSONObject().put("incidentId", incidentId).put("collision", value.collision.name)
            .put("departureDeclared", value.departureDeclared)
        prefs.edit().putString("journey", o.toString()).apply()
    }
}
