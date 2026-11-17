package io.github.aitm.pleosapp.accident

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AccidentContractTest {
    @Test fun sharedFixtureDecodesAndRoundTripsAllLocationMetadataIncludingExplicitNulls() {
        val fixture = accidentFixture()
        val event = accidentEventFromJson(fixture.getJSONObject("create"))
        assertEquals("accident-001", event.id)
        assertEquals("A", event.owner)
        assertEquals("OPERATOR_EVENT", event.source)
        assertEquals(LocationOrigin.GPS, event.location.origin)
        assertTrue(event.location.mock)
        assertEquals(37.4127, event.location.latitude, 0.0)
        assertEquals(127.0946, event.location.longitude, 0.0)
        assertEquals(5.0, event.location.accuracyMeters!!, 0.0)
        assertTrue(event.location.current(fixture.getLong("clockNow")))
        val encoded = event.json()
        val location = encoded.getJSONObject("location")
        assertEquals(setOf("latitude", "longitude", "receivedAt", "fixObservedAt", "accuracyMeters",
            "headingDegrees", "address", "origin", "mock"), location.keys().asSequence().toSet())
        assertTrue(location.has("headingDegrees") && location.isNull("headingDegrees"))
        assertTrue(location.has("address") && location.isNull("address"))
        assertEquals(event, accidentEventFromJson(JSONObject(encoded.toString())))
    }

    @Test fun expiredSnapshotRetainsActiveStatusVersionAndOriginalLocationTimes() {
        val fixture = accidentFixture()
        val original = accidentEventFromJson(fixture.getJSONObject("create"))
        val expired = accidentEventFromJson(fixture.getJSONObject("staleSnapshot").getJSONArray("events").getJSONObject(0))
        assertEquals(original, expired)
        assertEquals("ACTIVE", expired.status)
        assertEquals(1, expired.version)
        assertFalse(expired.location.current(fixture.getLong("staleClockNow")))
        val resolved = accidentEventFromJson(fixture.getJSONObject("resolvedSnapshot").getJSONArray("events").getJSONObject(0))
        assertEquals(original.copy(status = "RESOLVED", version = 2), resolved)
        assertEquals(original.location, resolved.location)
    }

    @Test fun accidentExpiryIsExactlyFiveMinutesAndStatusRequiresMatchingVersion() {
        for (offset in listOf(-1L, 1L, 300_000L)) {
            val event = accidentFixture().getJSONObject("create")
            event.put("expiresAt", event.getLong("expiresAt") + offset)
            rejects("expiry offset=$offset") { accidentEventFromJson(event) }
        }
        for ((status, version) in listOf("ACTIVE" to 0, "ACTIVE" to 2, "RESOLVED" to 1, "EXPIRED" to 1)) {
            val event = accidentFixture().getJSONObject("create").put("status", status).put("version", version)
            rejects("$status/$version") { accidentEventFromJson(event) }
        }
    }

    @Test fun captureWindowAcceptsOnlyReceiptsFromFifteenSecondsBeforeThroughThirtySecondsAfterAccident() {
        for ((offset, valid) in listOf(-15_001L to false, -15_000L to true, 30_000L to true, 30_001L to false)) {
            val event = accidentFixture().getJSONObject("create")
            event.getJSONObject("location").put("origin", "NAVI").put("fixObservedAt", JSONObject.NULL)
                .put("accuracyMeters", JSONObject.NULL).put("receivedAt", event.getLong("occurredAt") + offset)
            if (valid) assertEquals(LocationOrigin.NAVI, accidentEventFromJson(event).location.origin)
            else rejects("receipt offset=$offset") { accidentEventFromJson(event) }
        }
    }

    @Test fun missingOrExtraWireFieldsAreRejectedAtBothEventAndLocationLevels() {
        val eventFields = accidentFixture().getJSONObject("create").keys().asSequence().map { it as String }.toList()
        for (field in eventFields) {
            val event = accidentFixture().getJSONObject("create").apply { remove(field) }
            rejects("missing event $field") { accidentEventFromJson(event) }
        }
        rejects("extra event field") { accidentEventFromJson(accidentFixture().getJSONObject("create").put("road", "test-highway")) }
        val locationFields = accidentFixture().getJSONObject("create").getJSONObject("location").keys().asSequence().map { it as String }.toList()
        for (field in locationFields) {
            val location = accidentFixture().getJSONObject("create").getJSONObject("location").apply { remove(field) }
            rejects("missing location $field") { accidentLocationFromJson(location) }
        }
        rejects("extra location field") { accidentLocationFromJson(accidentFixture().getJSONObject("create")
            .getJSONObject("location").put("roadDirection", "east")) }
    }

    @Test fun numericStringsFractionalTimesAndWrongBooleanTypesDoNotBecomeValidWireValues() {
        for ((field, value) in listOf("occurredAt" to "1794880000000", "occurredAt" to 1794880000000.5,
            "occurredAt" to -1L, "occurredAt" to 9_007_199_254_740_992.0, "simulation" to "true", "owner" to 1)) {
            rejects("event $field=$value") { accidentEventFromJson(accidentFixture().getJSONObject("create").put(field, value)) }
        }
        for ((field, value) in listOf("latitude" to "37.4127", "receivedAt" to 1794880000000.5,
            "mock" to "true", "accuracyMeters" to "5.0", "origin" to "FUSED")) {
            rejects("location $field=$value") { accidentLocationFromJson(accidentFixture().getJSONObject("create")
                .getJSONObject("location").put(field, value)) }
        }
        rejects("stop observation is not an accident source") {
            accidentEventFromJson(accidentFixture().getJSONObject("create").put("source", "STOP_OBSERVATION"))
        }
    }

    @Test fun fixtureCoordinatesProduceAboutOneHundredElevenMetersOfStraightLineSeparation() {
        val fixture = accidentFixture()
        val event = accidentEventFromJson(fixture.getJSONObject("create"))
        val receiver = accidentLocationFromJson(fixture.getJSONObject("query").getJSONObject("location"))
        assertEquals(111.19492664455873, accidentDistance(event.location, receiver), 0.000001)
    }

    private fun rejects(label: String, block: () -> Unit) {
        assertThrows(label, Exception::class.java) { block() }
    }
}
