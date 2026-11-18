package io.github.aitm.pleosapp.accident

import io.github.aitm.pleosapp.prototype.HazardHttpException
import java.util.Base64
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AccidentEnvironmentTest {
    private val now = 1_800_000_000_000L
    private val user = "1e4b421d-df7b-4b23-a885-c3f59904b370"
    private val origin = "https://pilot-example.supabase.co"
    private fun encoded(json: JSONObject) = Base64.getUrlEncoder().withoutPadding().encodeToString(json.toString().toByteArray())
    // Synthetic parser fixture only. The real /session endpoint must verify every production JWT.
    private fun document(owner: String = "A", expiresIn: Long = 3_600_000): JSONObject {
        val claims = JSONObject().put("sub", user).put("iss", "$origin/auth/v1").put("role", "authenticated")
            .put("aud", "authenticated").put("iat", now / 1000).put("exp", (now + expiresIn) / 1000)
        return JSONObject().put("mode", "cloud").put("supabaseUrl", origin).put("publishableKey", "sb_publishable_fixture_only")
            .put("owner", owner).put("userId", user).put("accessToken", "${encoded(JSONObject().put("alg", "ES256"))}.${encoded(claims)}.fixture_signature")
            .put("provisionedAt", now).put("expiresAt", now + minOf(expiresIn, 3_600_000))
    }
    private fun cloud(json: JSONObject = document(), owner: String = "A") =
        accidentEnvironmentFromJson(json, owner) as AccidentEnvironment.Cloud

    @Test fun absentConfigurationKeepsLegacyLocalButMalformedExplicitConfigurationNeverFallsBack() {
        assertSame(AccidentEnvironment.Local, accidentEnvironmentFromText(null, "A"))
        assertSame(AccidentEnvironment.Local, accidentEnvironmentFromText("{\"mode\":\"local\"}", "A"))
        for (text in listOf("", "{", "{}", "{\"mode\":\"cloud\"}", "{\"mode\":\"typo\"}")) {
            val blocked = accidentEnvironmentFromText(text, "A")
            assertTrue(blocked is AccidentEnvironment.Blocked)
            assertNotEquals(AccidentEnvironment.Local.storeName, blocked.storeName)
            assertThrows(PilotSessionException::class.java) { blocked.endpoint("/prototype/accidents") }
        }
    }

    @Test fun cloudRoutesUseEdgeFunctionAndOnlyPublishableKeyPlusUserBearer() {
        val document = document()
        val target = cloud(document)
        assertEquals("$origin/functions/v1/accidents", target.endpoint("/prototype/accidents"))
        assertEquals("$origin/functions/v1/accidents/nearby", target.endpoint("/prototype/accidents/nearby"))
        assertEquals("http://127.0.0.1:8787/prototype/accidents", AccidentEnvironment.Local.endpoint("/prototype/accidents"))
        assertEquals(setOf("apikey", "Authorization"), target.headers(now).keys)
        assertEquals("Bearer ${document.getString("accessToken")}", target.headers(now)["Authorization"])
        assertFalse(target.headers(now).values.any { "prototype-vehicle" in it })
        assertFalse(target.toString().contains(document.getString("accessToken")))
        assertFalse(target.toString().contains(document.getString("publishableKey")))
        assertThrows(IllegalArgumentException::class.java) { target.endpoint("/prototype/admin/accidents/other/resolve") }
    }

    @Test fun namespaceSeparatesProjectVehicleAndUserWhileTokenRenewalKeepsTheSameOutbox() {
        val original = cloud()
        val renewed = document().put("provisionedAt", now + 1_000).put("expiresAt", now + 3_600_000)
        assertEquals(original.storeName, cloud(renewed).storeName)
        assertNotEquals(AccidentEnvironment.Local.storeName, original.storeName)
        assertNotEquals(original.storeName, cloud(document("B"), "B").storeName)
        val otherProject = document().toString().replace("pilot-example.supabase.co", "another-example.supabase.co")
        val projectJson = JSONObject(otherProject)
        val claims = JSONObject(String(Base64.getUrlDecoder().decode(document().getString("accessToken").split('.')[1])))
            .put("iss", "https://another-example.supabase.co/auth/v1")
        projectJson.put("accessToken", "header.${encoded(claims)}.fixture_signature")
        assertNotEquals(original.storeName, cloud(projectJson).storeName)
        val otherUser = "a0235691-f11b-459f-b8d8-e7ecb2f8c902"
        val userJson = document().put("userId", otherUser)
        claims.put("iss", "$origin/auth/v1").put("sub", otherUser)
        userJson.put("accessToken", "header.${encoded(claims)}.fixture_signature")
        assertNotEquals(original.storeName, cloud(userJson).storeName)
    }

    @Test fun localSessionCapHonorsShortTokensAndAllowsLongerProjectJwtWithoutExtendingLocalLifetime() {
        val short = cloud(document(expiresIn = 300_000))
        short.requireUsable(now + 299_999)
        assertThrows(PilotSessionException::class.java) { short.requireUsable(now + 300_000) }
        val longToken = cloud(document(expiresIn = 86_400_000))
        longToken.requireUsable(now + 3_599_999)
        assertThrows(PilotSessionException::class.java) { longToken.requireUsable(now + 3_600_000) }
        assertTrue(accidentEnvironmentFromJson(document().put("expiresAt", now + 3_600_001), "A") is AccidentEnvironment.Blocked)
        assertThrows(PilotSessionException::class.java) { longToken.requireUsable(now - 5_001) }
    }

    @Test fun wrongFlavorSecretKeysHttpOriginsAndOperatorTokensAreBlocked() {
        val variations = listOf(document("B"), document().put("publishableKey", "sb_secret_do_not_use"),
            document().put("supabaseUrl", "http://pilot-example.supabase.co"),
            document().put("supabaseUrl", "$origin/unexpected/path"),
            document().put("accessToken", "prototype-vehicle-A"),
            document().put("password", "must-not-be-a-config-setting"))
        variations.forEach { assertTrue(accidentEnvironmentFromJson(it, "A") is AccidentEnvironment.Blocked) }
    }

    @Test fun authenticatedServerOwnerMustMatchAndAuthFailuresHoldRatherThanQuarantinePendingBody() {
        verifyPilotOwner(JSONObject().put("owner", "A"), "A")
        for (body in listOf(JSONObject().put("owner", "B"), JSONObject().put("owner", "A").put("accepted", true), JSONObject())) {
            assertThrows(PilotSessionException::class.java) { verifyPilotOwner(body, "A") }
        }
        for (status in listOf(401, 403)) {
            assertTrue(accidentHttpFailure(status, "UNAUTHORIZED", cloud = true) is PilotSessionException)
            assertFalse(accidentHttpFailure(status, "UNAUTHORIZED", cloud = true) is HazardHttpException)
        }
        assertFalse((accidentHttpFailure(400, "INVALID_LOCATION", cloud = true) as HazardHttpException).retryable)
        assertTrue((accidentHttpFailure(503, "UNAVAILABLE", cloud = true) as HazardHttpException).retryable)
    }

    @Test fun connectingCloudCannotRecaptureRestoredLocalIncidentEvenWithinItsOriginalCaptureWindow() {
        val target = cloud()
        assertFalse(mayCaptureAccident(target, "local-incident", "local-incident", targetHasIncident = false))
        assertTrue(mayCaptureAccident(target, "cloud-pending", "cloud-pending", targetHasIncident = true))
        assertTrue(mayCaptureAccident(target, "new-explicit-incident", "local-incident", targetHasIncident = false))
        assertTrue(mayCaptureAccident(AccidentEnvironment.Local, "local-incident", "local-incident", targetHasIncident = false))
        assertFalse(mayCaptureAccident(AccidentEnvironment.Blocked(), "new-incident", null, targetHasIncident = false))
    }
}
