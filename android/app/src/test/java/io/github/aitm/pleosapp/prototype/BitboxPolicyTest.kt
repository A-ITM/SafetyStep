package io.github.aitm.pleosapp.prototype

import io.github.aitm.pleosapp.vehicle.*
import org.junit.Assert.*
import org.junit.Test

class BitboxPolicyTest {
    @Test fun parkAloneOrStaleSpeedCannotCompleteStationaryCheck() {
        val parked = VehicleSnapshot(gear = VehicleReading("GEAR_PARK", 1_000))
        assertFalse(BitboxPolicy.stationary(parked, 1_000))
        assertTrue(BitboxPolicy.stationary(parked.copy(speedMps = VehicleReading(0f, 1_000)), 1_000))
        assertFalse(BitboxPolicy.stationary(parked.copy(speedMps = VehicleReading(2f, 1_000)), 1_000))
        assertFalse(BitboxPolicy.stationary(parked.copy(speedMps = VehicleReading(0f, 1_000)), 32_000))
    }
    @Test fun emptyUnusedOrUnknownSeatCannotCreateDepartureAndRemainingOccupantStillGetsQuestion() {
        val seats = listOf(SeatObservation(1, "driver", VehicleReading(true, 1_000)), SeatObservation(4, "passenger", VehicleReading(false, 1_000)))
        assertFalse(BitboxPolicy.somebodyLeft(VehicleSnapshot(seats = seats), setOf(1), 1_000))
        assertTrue(BitboxPolicy.somebodyLeft(VehicleSnapshot(seats = seats), setOf(1, 4), 1_000))
        assertFalse(BitboxPolicy.somebodyLeft(VehicleSnapshot(seats = seats), setOf(1, 4), 32_000))
        val waiting = Incident("event", 1_000, "TEST").beginResponse()
        assertEquals(SupportScreen.RESPONSE, SupportPolicy.screen(waiting, SupportJourney(departureDeclared = true)))
        assertEquals(HazardState.ACTIVE, waiting.hazard)
    }
}
