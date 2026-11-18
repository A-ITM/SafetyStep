package io.github.aitm.pleosapp.accident

import android.content.Context
import io.github.aitm.pleosapp.BuildConfig
import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** Configuration errors and expired pilot sessions hold the outbox; they never quarantine its body. */
class PilotSessionException(val code: String, detail: String) : IllegalStateException("$code · $detail")

sealed class AccidentEnvironment {
    abstract val storeName: String
    abstract val label: String
    open fun requireUsable(now: Long) = Unit
    open fun diagnostic(now: Long): String = "127.0.0.1 로컬 시험 서버"

    data object Local : AccidentEnvironment() {
        override val storeName = "accident-location-v07"
        override val label = "로컬 시험"
    }

    class Blocked internal constructor(private val code: String = "PILOT_CONFIG_INVALID") : AccidentEnvironment() {
        override val storeName = "accident-location-v07-cloud-invalid"
        override val label = "클라우드 설정 확인 필요"
        override fun requireUsable(now: Long): Nothing = throw PilotSessionException(code, "클라우드 설정을 다시 연결해주세요. 로컬로 전환하지 않았어요")
        override fun diagnostic(now: Long) = "$code · 파일럿 설정을 다시 연결해주세요"
        override fun toString() = "Blocked($code)"
    }

    /** Tokens are runtime-only private-file values; toString never includes a credential. */
    class Cloud internal constructor(
        val origin: String,
        val owner: String,
        val userId: String,
        private val publishableKey: String,
        private val accessToken: String,
        val provisionedAt: Long,
        val expiresAt: Long,
    ) : AccidentEnvironment() {
        override val storeName = "accident-location-v07-cloud-" + MessageDigest.getInstance("SHA-256")
            .digest("$origin|$owner|$userId".toByteArray(Charsets.UTF_8)).take(12).joinToString("") { "%02x".format(it) }
        override val label = "클라우드 파일럿 · $owner"
        override fun requireUsable(now: Long) {
            if (now < provisionedAt - 5_000 || now >= expiresAt) {
                throw PilotSessionException("PILOT_SESSION_EXPIRED", "파일럿 사용자 세션을 다시 연결해주세요")
            }
        }
        override fun diagnostic(now: Long): String = if (now < provisionedAt - 5_000 || now >= expiresAt)
            "PILOT_SESSION_EXPIRED · 사용자 세션을 다시 연결해주세요" else "${URI(origin).host} · 임시 사용자 세션"
        internal fun headers(now: Long): Map<String, String> {
            requireUsable(now)
            return mapOf("apikey" to publishableKey, "Authorization" to "Bearer $accessToken")
        }
        override fun toString() = "Cloud(origin=$origin, owner=$owner, credentials=redacted)"
    }

    companion object {
        const val FILE_NAME = "safetystep-pilot.json"
        internal fun read(context: Context, owner: String): AccidentEnvironment {
            val file = File(context.noBackupFilesDir, FILE_NAME)
            if (!file.exists()) return Local
            if (!BuildConfig.DEBUG) return Blocked("PILOT_DEBUG_REQUIRED")
            return try {
                if (!file.isFile || file.length() !in 1..16_384) return Blocked()
                accidentEnvironmentFromText(file.readText(Charsets.UTF_8), owner)
            } catch (_: Exception) { Blocked() }
        }
    }
}

internal fun accidentEnvironmentFromText(text: String?, expectedOwner: String): AccidentEnvironment =
    if (text == null) AccidentEnvironment.Local else try {
        require(text.length in 1..16_384)
        accidentEnvironmentFromJson(JSONObject(text), expectedOwner)
    } catch (_: Exception) { AccidentEnvironment.Blocked() }

internal fun AccidentEnvironment.endpoint(path: String, localOrigin: String = "http://127.0.0.1:8787"): String {
    require(path in setOf("/prototype/accidents", "/prototype/accidents/nearby"))
    return when (this) {
        AccidentEnvironment.Local -> localOrigin + path
        is AccidentEnvironment.Cloud -> origin + "/functions/v1/accidents" + path.removePrefix("/prototype/accidents")
        is AccidentEnvironment.Blocked -> requireUsable(System.currentTimeMillis())
    }
}

/** Claim checks reject unsuitable credentials locally; Auth and /session perform real verification. */
internal fun accidentEnvironmentFromJson(json: JSONObject, expectedOwner: String): AccidentEnvironment = try {
    val keys = json.keys().asSequence().toSet()
    if (json.get("mode") == "local") {
        require(keys == setOf("mode"))
        AccidentEnvironment.Local
    } else {
        require(json.get("mode") == "cloud")
        require(keys == setOf("mode", "supabaseUrl", "publishableKey", "owner", "userId", "accessToken", "provisionedAt", "expiresAt"))
        fun string(key: String) = json.get(key) as? String ?: error("Invalid setting")
        fun integer(key: String) = (json.get(key) as? Number)?.toDouble()?.let {
            require(it.isFinite() && it > 0 && it <= 9_007_199_254_740_991.0 && it % 1.0 == 0.0); it.toLong()
        } ?: error("Invalid time")
        val uri = URI(string("supabaseUrl"))
        require(uri.scheme == "https" && uri.host != null && uri.host.matches(Regex("[A-Za-z0-9.-]+")) &&
            uri.host.contains('.') && uri.userInfo == null && uri.port == -1 && uri.rawQuery == null &&
            uri.rawFragment == null && uri.rawPath in setOf("", "/"))
        val origin = "https://${uri.host.lowercase()}"
        val key = string("publishableKey")
        require(key.matches(Regex("sb_publishable_[A-Za-z0-9_-]{8,}")))
        val owner = string("owner")
        require(owner in setOf("A", "B") && owner == expectedOwner)
        val userId = string("userId").lowercase()
        require(UUID.fromString(userId).toString() == userId)
        val token = string("accessToken")
        require(token.length in 40..12_000 && token.matches(Regex("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+")))
        val claims = JSONObject(String(Base64.getUrlDecoder().decode(token.split('.')[1]), Charsets.UTF_8))
        require(claims.get("sub") == userId && claims.get("iss") == "$origin/auth/v1" && claims.get("role") == "authenticated")
        val aud = claims.get("aud")
        require(aud == "authenticated" || aud is JSONArray && (0 until aud.length()).any { aud.get(it) == "authenticated" })
        fun claimTime(name: String): Long {
            val value = (claims.get(name) as? Number)?.toDouble() ?: error("Invalid claim")
            require(value.isFinite() && value > 0 && value <= 9_007_199_254_740.0 && value % 1.0 == 0.0)
            return value.toLong() * 1_000
        }
        val issuedAt = claimTime("iat")
        val tokenExpiresAt = claimTime("exp")
        val provisionedAt = integer("provisionedAt")
        val expiresAt = integer("expiresAt")
        require(issuedAt <= provisionedAt + 60_000 && tokenExpiresAt > issuedAt &&
            expiresAt > provisionedAt && expiresAt <= tokenExpiresAt && expiresAt - provisionedAt <= 3_600_000)
        AccidentEnvironment.Cloud(origin, owner, userId, key, token, provisionedAt, expiresAt)
    }
} catch (_: Exception) { AccidentEnvironment.Blocked() }

internal fun verifyPilotOwner(body: JSONObject, expectedOwner: String) {
    if (body.keys().asSequence().toSet() != setOf("owner") || body.opt("owner") != expectedOwner) {
        throw PilotSessionException("PILOT_OWNER_MISMATCH", "이 앱 차량과 로그인한 파일럿 사용자가 달라요")
    }
}

internal fun mayCaptureAccident(
    environment: AccidentEnvironment, incidentId: String, restoredIncidentId: String?, targetHasIncident: Boolean,
): Boolean = environment !is AccidentEnvironment.Blocked &&
    !(environment is AccidentEnvironment.Cloud && incidentId == restoredIncidentId && !targetHasIncident)
