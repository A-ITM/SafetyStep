package io.github.aitm.pleosapp.prototype

import io.github.aitm.pleosapp.BuildConfig
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Deliberately local, debug-only HTTP transport. No real locations or cloud secrets. */
class HazardClient(private val owner: String) {
    suspend fun post(payload: JSONObject): JSONObject = request("POST", payload)
    suspend fun list(): List<Hazard> {
        val events = request("GET").getJSONArray("events")
        return (0 until events.length()).map { hazardFromJson(events.getJSONObject(it)) }
    }

    private suspend fun request(method: String, body: JSONObject? = null): JSONObject = withContext(Dispatchers.IO) {
        check(BuildConfig.DEBUG) { "로컬 전파는 debug 전용입니다." }
        val connection = URL("http://127.0.0.1:8787/prototype/incidents").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 2_000
            connection.readTimeout = 2_000
            connection.setRequestProperty("Authorization", "Bearer prototype-vehicle-$owner")
            // Keep local fault injection deterministic: removing adb reverse must not
            // leave a pooled socket carrying requests through the previous tunnel.
            connection.setRequestProperty("Connection", "close")
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            check(status in 200..299) { "HTTP $status: ${text.take(160)}" }
            JSONObject(text)
        } finally { connection.disconnect() }
    }
}

fun Incident.toLocalJson(): JSONObject = JSONObject().apply {
    put("id", id); put("observedAt", observedAt); put("source", source)
    put("response", response.name); put("responder", responder.name); put("help", help.name)
    put("evacuated", evacuated); put("hazard", hazard.name); put("version", version)
}

fun incidentFromJson(o: JSONObject) = Incident(
    o.getString("id"), o.getLong("observedAt"), o.getString("source"),
    ResponseState.valueOf(o.getString("response")), Responder.valueOf(o.getString("responder")),
    HelpState.valueOf(o.getString("help")), o.getBoolean("evacuated"),
    HazardState.valueOf(o.getString("hazard")), o.getInt("version"),
)

fun Incident.toSharedJson(owner: String): JSONObject = JSONObject().apply {
    put("id", id); put("owner", owner); put("version", version)
    put("observedAt", observedAt); put("expiresAt", observedAt + 180_000)
    put("road", "test-highway"); put("direction", "east"); put("meters", 1000.0)
    put("status", hazard.name); put("source", source); put("simulation", true)
}

fun hazardFromJson(o: JSONObject) = Hazard(
    o.getString("id"), o.getString("owner"), o.getInt("version"), o.getLong("observedAt"),
    o.getLong("expiresAt"), o.getString("road"), o.getString("direction"), o.getDouble("meters"),
    HazardState.valueOf(o.getString("status")), o.getString("source"),
)

class PrototypeStore(context: android.content.Context, preferencesName: String = "prototype") {
    private val prefs = context.getSharedPreferences(preferencesName, android.content.Context.MODE_PRIVATE)
    fun incident(): Incident? = runCatching {
        prefs.getString("incident", null)?.let { incidentFromJson(JSONObject(it)) }
    }.getOrNull()
    fun saveIncident(value: Incident?) {
        prefs.edit().putString("incident", value?.toLocalJson()?.toString()).apply()
    }
    fun hazardAttempted(id: String) = prefs.getString("hazardIncident", null) == id
    fun hazardCommands(id: String) = if (hazardAttempted(id)) prefs.getInt("hazardCommands", 0) else 0
    // Commit the claim before touching the vehicle: a process restart must not replay a command.
    fun claimHazard(id: String): Boolean {
        if (hazardAttempted(id)) return false
        check(prefs.edit().putString("hazardIncident", id).putInt("hazardCommands", 0).commit()) { "비상등 요청 기록 저장 실패" }
        return true
    }
    fun recordHazardCommand(id: String) {
        check(hazardAttempted(id))
        check(prefs.edit().putInt("hazardCommands", 1).commit()) { "비상등 명령 기록 저장 실패" }
    }
    fun outbox(): List<JSONObject> = runCatching {
        val a = JSONArray(prefs.getString("outbox", "[]"))
        (0 until a.length()).map { a.getJSONObject(it) }
    }.getOrDefault(emptyList())
    fun saveOutbox(values: List<JSONObject>) {
        prefs.edit().putString("outbox", JSONArray(values).toString()).apply()
    }
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
        val control = ControlState.valueOf(o.optString("trunk", "IDLE"))
        SupportJourney(
            collision = CollisionContext.valueOf(o.optString("collision", "UNKNOWN")),
            // Safety confirmations expire on a new process. Never replay a previous consent to actuate.
            stationaryConfirmed = false, routeConfirmed = false,
            departureDeclared = o.optBoolean("departureDeclared"),
            trunk = if (control == ControlState.CHECKING) ControlState.UNCONFIRMED else control,
            trunkNote = if (control == ControlState.CHECKING) "이전 요청 결과를 확인하지 못했어요" else o.optString("trunkNote", "열기 요청 전"),
            trunkAttempts = o.optInt("trunkAttempts"),
            autoTrunkClaimed = o.optBoolean("autoTrunkClaimed"),
        )
    }.getOrDefault(SupportJourney())

    fun saveJourney(incidentId: String?, value: SupportJourney) {
        val o = JSONObject().put("incidentId", incidentId).put("collision", value.collision.name)
            .put("departureDeclared", value.departureDeclared).put("trunk", value.trunk.name)
            .put("trunkNote", value.trunkNote).put("trunkAttempts", value.trunkAttempts)
            .put("autoTrunkClaimed", value.autoTrunkClaimed)
        val edit = prefs.edit().putString("journey", o.toString())
        if (value.autoTrunkClaimed || value.trunkAttempts > 0) check(edit.commit()) { "차량 요청 기록 저장 실패" }
        else edit.apply()
    }
}
