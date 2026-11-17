package io.github.aitm.pleosapp.accident

import io.github.aitm.pleosapp.BuildConfig
import io.github.aitm.pleosapp.prototype.HazardHttpException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

data class NearbySnapshot(val requestId: String, val serverNow: Long, val events: List<AccidentEvent>)

class AccidentClient(private val owner: String, private val baseUrl: String = "http://127.0.0.1:8787") {
    suspend fun register(event: AccidentEvent, allowed: () -> Boolean = { true }): AccidentEvent {
        val body = request("/prototype/accidents", event.json(), allowed)
        return accidentRegistrationAck(body, event)
    }

    suspend fun nearby(requestId: String, location: AccidentLocation): NearbySnapshot {
        val body = request("/prototype/accidents/nearby", JSONObject().put("requestId", requestId).put("location", location.json()))
        body.exact(setOf("requestId", "serverNow", "radiusMeters", "complete", "events"))
        require(body.string("requestId") == requestId && body.get("complete") == true && body.integer("radiusMeters") == 1000L)
        val serverNow = body.integer("serverNow")
        val raw = body.getJSONArray("events"); require(raw.length() <= 500)
        val events = (0 until raw.length()).map { accidentEventFromJson(raw.getJSONObject(it)) }
        require(events.map { it.id }.distinct().size == events.size)
        require(events.all { it.owner != owner && accidentDistance(location, it.location) <= 1000.01 && it.occurredAt <= serverNow + 5000 })
        return NearbySnapshot(requestId, serverNow, events)
    }

    private suspend fun request(path: String, body: JSONObject, allowed: () -> Boolean = { true }): JSONObject = withContext(Dispatchers.IO) {
        check(BuildConfig.DEBUG); require(owner in setOf("A", "B"))
        val connection = URL(baseUrl + path).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"; connection.instanceFollowRedirects = false
            connection.connectTimeout = 2000; connection.readTimeout = 2000; connection.doOutput = true
            connection.setRequestProperty("Authorization", "Bearer prototype-vehicle-$owner")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Cache-Control", "no-store")
            currentCoroutineContext().ensureActive()
            if (!allowed()) throw CancellationException("사고 전송 보류")
            connection.outputStream.use {
                currentCoroutineContext().ensureActive()
                if (!allowed()) throw CancellationException("사고 전송 보류")
                it.write(body.toString().toByteArray(Charsets.UTF_8))
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            require(text.length <= 600_000)
            if (status !in 200..299) {
                val error = runCatching { JSONObject(text).getJSONObject("error") }.getOrNull()
                throw HazardHttpException(status, error?.optString("code") ?: "HTTP_ERROR", "HTTP $status: ${error?.optString("message") ?: "사고 위치 통신 실패"}")
            }
            require(connection.getHeaderField("Cache-Control")?.split(',')?.any { it.trim().equals("no-store", true) } == true)
            JSONObject(text)
        } finally { connection.disconnect() }
    }
}

internal fun accidentRegistrationAck(body: JSONObject, event: AccidentEvent): AccidentEvent {
    body.exact(setOf("accepted", "duplicate", "event"))
    require(body.get("accepted") == true && body.get("duplicate") is Boolean)
    val ack = accidentEventFromJson(body.getJSONObject("event"))
    require(ack.copy(status = event.status, version = event.version) == event &&
        (ack.status == "ACTIVE" && ack.version == 1 || ack.status == "RESOLVED" && ack.version == 2)) { "사고 등록 응답 불일치" }
    return ack
}

private fun JSONObject.exact(keys: Set<String>) { require(keys().asSequence().toSet() == keys) { "사고 데이터 필드 오류" } }
private fun JSONObject.string(key: String): String = get(key) as? String ?: error("$key 문자열 오류")
private fun JSONObject.number(key: String): Double = (get(key) as? Number)?.toDouble()?.also { require(it.isFinite()) } ?: error("$key 숫자 오류")
private fun JSONObject.integer(key: String): Long = number(key).also { require(it >= 0 && it <= 9_007_199_254_740_991.0 && it % 1.0 == 0.0) }.toLong()
private fun JSONObject.nullableNumber(key: String): Double? = if (isNull(key)) null else number(key)
fun AccidentLocation.json(): JSONObject = JSONObject().apply {
    put("latitude", latitude); put("longitude", longitude); put("receivedAt", receivedAt)
    put("fixObservedAt", fixObservedAt ?: JSONObject.NULL); put("accuracyMeters", accuracyMeters ?: JSONObject.NULL)
    put("headingDegrees", headingDegrees ?: JSONObject.NULL); put("address", address ?: JSONObject.NULL)
    put("origin", origin.name); put("mock", mock)
}
fun accidentLocationFromJson(o: JSONObject): AccidentLocation {
    o.exact(setOf("latitude", "longitude", "receivedAt", "fixObservedAt", "accuracyMeters", "headingDegrees", "address", "origin", "mock"))
    require(o.get("mock") is Boolean)
    return AccidentLocation(o.number("latitude"), o.number("longitude"), o.integer("receivedAt"),
        if (o.isNull("fixObservedAt")) null else o.integer("fixObservedAt"), o.nullableNumber("accuracyMeters"),
        o.nullableNumber("headingDegrees"), if (o.isNull("address")) null else o.string("address"),
        LocationOrigin.valueOf(o.string("origin")), o.getBoolean("mock")).also { require(it.structurallyValid()) }
}
fun AccidentEvent.json(): JSONObject = JSONObject().apply {
    put("id", id); put("owner", owner); put("version", version); put("eventType", "ACCIDENT")
    put("status", status); put("source", source); put("simulation", true)
    put("occurredAt", occurredAt); put("expiresAt", expiresAt); put("location", location.json())
}
fun accidentEventFromJson(o: JSONObject): AccidentEvent {
    o.exact(setOf("id", "owner", "version", "eventType", "status", "source", "simulation", "occurredAt", "expiresAt", "location"))
    val event = AccidentEvent(o.string("id"), o.string("owner"), o.string("source"), o.integer("occurredAt"),
        accidentLocationFromJson(o.getJSONObject("location")), o.integer("expiresAt"), o.integer("version").also { require(it <= 2) }.toInt(), o.string("status"))
    require(event.id.matches(Regex("[A-Za-z0-9-]{1,80}")) && event.owner in setOf("A", "B") && o.string("eventType") == "ACCIDENT" && o.get("simulation") == true)
    require(event.source in setOf("MANUAL_TEST", "SDK_AIRBAG", "GLEO_REQUEST", "OPERATOR_EVENT"))
    require(event.occurredAt > 0 && event.expiresAt == event.occurredAt + 300_000 &&
        event.location.receivedAt - event.occurredAt in -15_000..30_000)
    require(event.status == "ACTIVE" && event.version == 1 || event.status == "RESOLVED" && event.version == 2)
    return event
}
