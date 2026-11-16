package io.github.aitm.pleosapp.prototype

import android.content.Context
import android.os.SystemClock
import java.io.File
import org.json.JSONObject

/** Local diagnostics only. No audio or transcript text is accepted by this interface. */
class PrototypeMetrics(context: Context, private val owner: String) {
    val file = File(context.filesDir, "prototype-metrics.jsonl")
    var runId: String = "foreground-${System.currentTimeMillis()}"
    @Synchronized fun record(
        name: String, incidentId: String? = null, supportSessionId: String? = null,
        utc: Long = System.currentTimeMillis(), elapsed: Long = SystemClock.elapsedRealtime(),
        details: Map<String, Any?> = emptyMap(),
    ) {
        val row = JSONObject().put("metric", name).put("runId", runId).put("owner", owner)
            .put("utcMs", utc).put("elapsedMs", elapsed).put("clockDomain", "THIS_AVD_BOOT")
            .put("incidentId", incidentId ?: JSONObject.NULL).put("supportSessionId", supportSessionId ?: JSONObject.NULL)
        details.forEach { (key, value) -> row.put(key, value ?: JSONObject.NULL) }
        runCatching { file.appendText(row.toString() + "\n") }
    }
}
