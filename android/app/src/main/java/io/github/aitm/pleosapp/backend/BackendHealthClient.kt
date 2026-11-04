package io.github.aitm.pleosapp.backend

import java.net.URL
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

class BackendHealthClient(private val projectUrl: String) {
    val isConfigured: Boolean get() = projectUrl.isNotBlank()

    suspend fun check(): String = withContext(Dispatchers.IO) {
        check(isConfigured) { "Supabase 프로젝트 URL을 먼저 설정해 주세요." }
        val connection = URL("$projectUrl/functions/v1/health").openConnection() as HttpsURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 5_000
            connection.readTimeout = 5_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/json")
            check(connection.responseCode == 200) { "서버 응답: HTTP ${connection.responseCode}" }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            check(JSONObject(body).optString("status") == "ok") { "예상한 health 응답이 아닙니다." }
            "Supabase Edge Function 응답 정상 · DB/Auth는 별도 확인 필요"
        } finally {
            connection.disconnect()
        }
    }
}
