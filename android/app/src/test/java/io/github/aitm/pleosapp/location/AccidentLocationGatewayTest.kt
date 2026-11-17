package io.github.aitm.pleosapp.location

import io.github.aitm.pleosapp.accident.LocationOrigin
import org.junit.Assert.*
import org.junit.Test

class AccidentLocationGatewayTest {
    private val now = 1_800_000_000_000L
    private val elapsed = 50_000_000_000L
    private val fix = GpsFix(37.5, 127.0, now - 2_000, elapsed - 2_000_000_000, 5.0, 90.0, true)

    @Test fun gpsPreservesActualFixTimeAccuracyBearingAndMockWithoutAddingNavigationAddress() {
        val location = requireNotNull(accidentLocationFromGps(fix, now, elapsed))
        assertEquals(LocationOrigin.GPS, location.origin)
        assertEquals(now, location.receivedAt)
        assertEquals(now - 2_000, location.fixObservedAt)
        assertEquals(5.0, location.accuracyMeters!!, 0.0)
        assertEquals(90.0, location.headingDegrees!!, 0.0)
        assertTrue(location.mock)
        assertNull(location.address)
    }

    @Test fun staleElapsedFixIsRejectedEvenWithFreshWallClockTime() {
        assertNull(accidentLocationFromGps(fix.copy(elapsedRealtimeNanos = elapsed - 15_000_000_001), now, elapsed))
        assertNull(accidentLocationFromGps(fix.copy(elapsedRealtimeNanos = elapsed + 5_000_000_001), now, elapsed))
        assertNotNull(accidentLocationFromGps(fix.copy(elapsedRealtimeNanos = elapsed - 15_000_000_000), now, elapsed))
    }

    @Test fun originalWallFixTimeMustAlsoSatisfyTheContractAndIsNeverStampedAsNow() {
        assertNull(accidentLocationFromGps(fix.copy(fixObservedAt = now - 15_001), now, elapsed))
        assertNull(accidentLocationFromGps(fix.copy(fixObservedAt = now + 5_001), now, elapsed))
        assertNull(accidentLocationFromGps(fix.copy(fixObservedAt = 0), now, elapsed))
        val wallOnly = fix.copy(elapsedRealtimeNanos = null, fixObservedAt = now - 15_000)
        assertEquals(now - 15_000, requireNotNull(accidentLocationFromGps(wallOnly, now, elapsed)).fixObservedAt)
        assertNull(accidentLocationFromGps(wallOnly.copy(fixObservedAt = now - 15_001), now, elapsed))
    }

    @Test fun gpsRequiresFiniteCoordinatesAndPositiveAccuracyAtMostOneHundredMeters() {
        for (accuracy in listOf(null, 0.0, -1.0, 100.01, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertNull(accidentLocationFromGps(fix.copy(accuracyMeters = accuracy), now, elapsed))
        }
        assertNotNull(accidentLocationFromGps(fix.copy(accuracyMeters = 100.0), now, elapsed))
        assertNull(accidentLocationFromGps(fix.copy(latitude = Double.NaN), now, elapsed))
        assertNull(accidentLocationFromGps(fix.copy(latitude = 90.01), now, elapsed))
        assertNull(accidentLocationFromGps(fix.copy(longitude = -180.01), now, elapsed))
        assertNull(requireNotNull(accidentLocationFromGps(fix.copy(headingDegrees = 360.0), now, elapsed)).headingDegrees)
    }

    @Test fun navigationUsesItsOwnCoordinatePairAddressAndReceiptWithoutInventingFixMetadata() {
        val report = reportLocationFromProvider(35.1, 129.0, " 부산 ", 15.0, now - 1_000)
        val location = requireNotNull(accidentLocationFromNavigation(report, now))
        assertEquals(LocationOrigin.NAVI, location.origin)
        assertEquals(35.1, location.latitude, 0.0)
        assertEquals(129.0, location.longitude, 0.0)
        assertEquals("부산", location.address)
        assertEquals(now - 1_000, location.receivedAt)
        assertNull(location.fixObservedAt)
        assertNull(location.accuracyMeters)
        assertFalse(location.mock)
    }

    @Test fun missingNavigationCoordinatesSamplesAndOldReceiptsNeverBecomeCurrentLocations() {
        val report = reportLocationFromProvider(37.5, 127.0, null, null, now)
        assertNull(accidentLocationFromNavigation(report.copy(sample = true), now))
        assertNull(accidentLocationFromNavigation(report.copy(receivedAt = null), now))
        assertNull(accidentLocationFromNavigation(report.copy(receivedAt = now - 15_001), now))
        assertNull(accidentLocationFromNavigation(report.copy(receivedAt = now + 5_001), now))
        assertNull(accidentLocationFromNavigation(report.copy(status = ReportLocationStatus.UNAVAILABLE), now))
        assertNull(accidentLocationFromNavigation(reportLocationFromProvider(null, null, "주소만", null, now), now))
        assertNotNull(accidentLocationFromNavigation(report.copy(receivedAt = now - 15_000), now))
    }
}
