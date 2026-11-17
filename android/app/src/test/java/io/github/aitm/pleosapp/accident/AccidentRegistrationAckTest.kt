package io.github.aitm.pleosapp.accident

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AccidentRegistrationAckTest {
    @Test fun createAndDuplicateFixtureAcknowledgementsMatchTheOriginalImmutableRequest() {
        val fixture = accidentFixture()
        val event = accidentEventFromJson(fixture.getJSONObject("create"))
        assertEquals(event, accidentRegistrationAck(fixture.getJSONObject("createAck"), event))
        assertEquals(event, accidentRegistrationAck(fixture.getJSONObject("duplicateAck"), event))
    }

    @Test fun retryAcknowledgementMayReturnResolvedVersionWithoutChangingOriginalEventMetadata() {
        val fixture = accidentFixture()
        val event = accidentEventFromJson(fixture.getJSONObject("create"))
        // A repeated create after an administrator resolves the event is a duplicate of that create.
        val duplicateResolved = fixture.getJSONObject("resolveAck").put("duplicate", true)
        assertEquals(event.copy(status = "RESOLVED", version = 2), accidentRegistrationAck(duplicateResolved, event))
    }

    @Test fun structurallyValidButDifferentEventOrCoordinatesCannotAcknowledgeThisRequest() {
        val event = accidentEventFromJson(accidentFixture().getJSONObject("create"))
        val mutations: List<Pair<String, (JSONObject) -> Unit>> = listOf(
            "id" to { it.put("id", "another-accident") },
            "owner" to { it.put("owner", "B") },
            "source" to { it.put("source", "MANUAL_TEST") },
            "occurrence" to { it.put("occurredAt", it.getLong("occurredAt") + 1)
                .put("expiresAt", it.getLong("expiresAt") + 1) },
            "latitude" to { it.getJSONObject("location").put("latitude", 37.6) },
            "longitude" to { it.getJSONObject("location").put("longitude", 127.6) },
            "receipt" to { it.getJSONObject("location").put("receivedAt", event.location.receivedAt + 1) },
            "fix time" to { it.getJSONObject("location").put("fixObservedAt", event.location.fixObservedAt!! + 1) },
            "accuracy" to { it.getJSONObject("location").put("accuracyMeters", 6.0) },
            "heading" to { it.getJSONObject("location").put("headingDegrees", 90.0) },
            "address" to { it.getJSONObject("location").put("address", "unrelated address") },
            "origin" to { it.getJSONObject("location").put("origin", "TEST") },
            "mock" to { it.getJSONObject("location").put("mock", false) },
        )
        for ((label, mutate) in mutations) {
            val ack = accidentFixture().getJSONObject("createAck")
            mutate(ack.getJSONObject("event"))
            // Ensure this exercises request matching rather than only the event decoder's validation.
            accidentEventFromJson(ack.getJSONObject("event"))
            assertThrows(label, IllegalArgumentException::class.java) { accidentRegistrationAck(ack, event) }
        }
    }

    @Test fun incompleteAmbiguousOrUnacceptedEnvelopesNeverConfirmRegistration() {
        val event = accidentEventFromJson(accidentFixture().getJSONObject("create"))
        for ((field, value) in listOf("accepted" to false, "accepted" to "true", "duplicate" to "false")) {
            val ack = accidentFixture().getJSONObject("createAck").put(field, value)
            assertThrows("$field=$value", Exception::class.java) { accidentRegistrationAck(ack, event) }
        }
        for (field in listOf("accepted", "duplicate", "event")) {
            val ack = accidentFixture().getJSONObject("createAck").apply { remove(field) }
            assertThrows("missing $field", Exception::class.java) { accidentRegistrationAck(ack, event) }
        }
        val extra = accidentFixture().getJSONObject("createAck").put("delivered", true)
        assertThrows("extra acknowledgement field", Exception::class.java) { accidentRegistrationAck(extra, event) }
    }
}
