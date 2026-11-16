package io.github.aitm.pleosapp.location

import org.junit.Assert.*
import org.junit.Test

class ReportLocationTest {
    @Test fun providerReceiptDoesNotInventFixTimeAccuracyOrRoadDirection() {
        val report = reportLocationFromProvider(37.5, 127.0, "서울", 90.0, 123_456L)
        assertEquals(ReportLocationStatus.AVAILABLE, report.status)
        assertEquals(123_456L, report.receivedAt)
        assertNull(report.fixObservedAt)
        assertNull(report.accuracyMeters)
        assertFalse(report.sample)
        assertEquals(90.0, report.headingDegrees!!, 0.0)
    }

    @Test fun missingOrInvalidProviderCoordinatesAreNotConvertedToZero() {
        val unavailable = reportLocationFromProvider(Double.NaN, 127.0, "  ", Double.POSITIVE_INFINITY, 123L)
        assertEquals(ReportLocationStatus.UNAVAILABLE, unavailable.status)
        assertNull(unavailable.latitude)
        assertNull(unavailable.longitude)
        assertNull(unavailable.headingDegrees)
        val addressOnly = reportLocationFromProvider(91.0, 127.0, "  도로 표지 근처  ", -1.0, 124L)
        assertEquals(ReportLocationStatus.AVAILABLE, addressOnly.status)
        assertEquals("도로 표지 근처", addressOnly.address)
        assertFalse(addressOnly.hasCoordinates)
    }

    @Test(expected = IllegalArgumentException::class)
    fun unavailableCoordinatesCannotBeLabelledAvailable() {
        ReportLocation(status = ReportLocationStatus.AVAILABLE, latitude = 37.5)
    }
}
