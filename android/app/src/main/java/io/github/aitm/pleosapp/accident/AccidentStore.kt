package io.github.aitm.pleosapp.accident

import android.content.Context
import org.json.JSONArray

data class StoredAccident(val event: AccidentEvent, val queued: Boolean, val registered: Boolean = false, val failure: String? = null)

/** Store the immutable location before any network write, keeping retries on the same incident ID. */
class AccidentStore(context: Context) {
    private val prefs = context.getSharedPreferences("accident-location-v07", Context.MODE_PRIVATE)
    val entries = linkedMapOf<String, StoredAccident>()
    var readFailure: Boolean = false
        private set
    val spoken = prefs.getStringSet("spoken", emptySet()).orEmpty().toMutableSet()
    init {
        try {
            val raw = JSONArray(prefs.getString("events", "[]"))
            require(raw.length() <= 500)
            for (i in 0 until raw.length()) {
                val item = raw.getJSONObject(i); val event = accidentEventFromJson(item.getJSONObject("event"))
                entries[event.id] = StoredAccident(event, item.getBoolean("queued"), item.getBoolean("registered"), if (item.isNull("failure")) null else item.getString("failure"))
            }
        } catch (_: Exception) { entries.clear(); readFailure = true }
    }
    fun save(value: StoredAccident) {
        check(!readFailure && (entries.containsKey(value.event.id) || entries.size < 500)) { "저장된 사고 자료 확인 필요" }
        val next = LinkedHashMap(entries); next[value.event.id] = value
        val raw = JSONArray(next.values.map { item -> org.json.JSONObject().put("event", item.event.json())
            .put("queued", item.queued).put("registered", item.registered).put("failure", item.failure ?: org.json.JSONObject.NULL) })
        check(prefs.edit().putString("events", raw.toString()).commit()) { "사고 위치 로컬 저장 실패" }
        entries.clear(); entries.putAll(next)
    }
    fun markSpoken(id: String) {
        val next = (spoken + id).toList().takeLast(500).toSet()
        check(prefs.edit().putStringSet("spoken", next).commit())
        spoken.clear(); spoken.addAll(next)
    }
}
