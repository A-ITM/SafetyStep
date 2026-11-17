package io.github.aitm.pleosapp.accident

import org.junit.Assert.*
import org.junit.Test

class AccidentModelsTest {
    private val now = 1_800_000_000_000L
    private val gps = AccidentLocation(37.5, 127.0, now, now, 5.0, origin = LocationOrigin.GPS)

    @Test fun currentLocationHasInclusiveFifteenSecondAgeAndFiveSecondFutureTolerance() {
        assertTrue(gps.current(now + 15_000))
        assertFalse(gps.current(now + 15_001))
        assertTrue(gps.current(now - 5_000))
        assertFalse(gps.current(now - 5_001))
    }

    @Test fun freshReceiptDoesNotGiveAnOldKnownFixAnotherFifteenSecondsOfLife() {
        val justReceived = gps.copy(fixObservedAt = now - 15_000)
        assertTrue(justReceived.structurallyValid())
        assertTrue(justReceived.current(now))
        assertFalse(justReceived.current(now + 1))
        assertFalse(justReceived.copy(origin = LocationOrigin.NAVI).current(now + 1))
        val futureFix = gps.copy(fixObservedAt = now + 5_000)
        assertTrue(futureFix.current(now))
        assertFalse(futureFix.current(now - 1))
    }

    @Test fun gpsRequiresItsOriginalFixTimeAndAValidatedAccuracy() {
        assertFalse(gps.copy(fixObservedAt = null).structurallyValid())
        assertFalse(gps.copy(accuracyMeters = null).structurallyValid())
        assertTrue(gps.copy(fixObservedAt = now - 15_000, accuracyMeters = 100.0).structurallyValid())
        assertFalse(gps.copy(fixObservedAt = now - 15_001).structurallyValid())
        assertTrue(gps.copy(fixObservedAt = now + 5_000).structurallyValid())
        assertFalse(gps.copy(fixObservedAt = now + 5_001).structurallyValid())
        for (accuracy in listOf(0.0, -1.0, 100.01, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertFalse("accuracy=$accuracy", gps.copy(accuracyMeters = accuracy).structurallyValid())
        }
    }

    @Test fun missingNavigationFixMetadataIsValidButInvalidCoordinatesAndHeadingsAreNot() {
        val navigation = gps.copy(origin = LocationOrigin.NAVI, fixObservedAt = null, accuracyMeters = null)
        assertTrue(navigation.structurallyValid())
        assertTrue(navigation.copy(latitude = -90.0, longitude = 180.0, headingDegrees = 0.0).structurallyValid())
        assertTrue(navigation.copy(latitude = 90.0, longitude = -180.0, headingDegrees = 359.99).structurallyValid())
        for (latitude in listOf(-90.01, 90.01, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertFalse("latitude=$latitude", navigation.copy(latitude = latitude).structurallyValid())
        }
        for (longitude in listOf(-180.01, 180.01, Double.NaN, Double.NEGATIVE_INFINITY)) {
            assertFalse("longitude=$longitude", navigation.copy(longitude = longitude).structurallyValid())
        }
        for (heading in listOf(-0.01, 360.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertFalse("heading=$heading", navigation.copy(headingDegrees = heading).structurallyValid())
        }
        assertFalse(navigation.copy(receivedAt = 0).current(now))
        assertFalse(navigation.copy(address = "가".repeat(301)).structurallyValid())
    }

    @Test fun greatCircleDistanceMatchesKnownMeridianAndQuarterEarthDistances() {
        fun at(latitude: Double, longitude: Double) = gps.copy(latitude = latitude, longitude = longitude)
        assertEquals(0.0, accidentDistance(gps, gps), 0.0)
        assertEquals(111_194.92664455874, accidentDistance(at(0.0, 0.0), at(1.0, 0.0)), 0.000001)
        assertEquals(10_007_543.398010286, accidentDistance(at(0.0, 0.0), at(0.0, 90.0)), 0.000001)
        assertEquals(accidentDistance(at(-35.0, -140.0), at(37.5, 127.0)),
            accidentDistance(at(37.5, 127.0), at(-35.0, -140.0)), 0.000001)
    }

    @Test fun datelineAndAntipodalPointsUseTheShortGreatCircleArcWithoutNaN() {
        val west = gps.copy(latitude = 0.0, longitude = 179.999)
        val east = gps.copy(latitude = 0.0, longitude = -179.999)
        assertEquals(222.38985328911747, accidentDistance(west, east), 0.00001)
        val antipode = accidentDistance(gps.copy(latitude = 0.0, longitude = 0.0),
            gps.copy(latitude = 0.0, longitude = 180.0))
        assertTrue(antipode.isFinite())
        assertEquals(20_015_086.79602057, antipode, 0.000001)
    }
}
