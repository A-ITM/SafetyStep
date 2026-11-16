package io.github.aitm.pleosapp.prototype

import io.github.aitm.pleosapp.vehicle.*
import org.junit.Assert.*
import org.junit.Test

class CabinPolicyTest {
    private fun seats(now: Long, vararg values: Boolean?) = values.mapIndexed { i, value -> SeatObservation(i, "seat$i", VehicleReading(value, now)) }
    @Test fun rawUnknownCannotBecomeVacantByBooleanInversion() {
        assertNull(CabinPolicy.decode(0)); assertNull(CabinPolicy.decode(null)); assertNull(CabinPolicy.decode(9))
        assertEquals(false, CabinPolicy.decode(1)); assertEquals(true, CabinPolicy.decode(2))
    }
    @Test fun driverLeavingDoesNotClearPassengerOrUnknownSeat() {
        assertEquals(CabinPresence.OCCUPIED, CabinPolicy.presence(seats(1000, false, true, false, false),1000))
        assertEquals(CabinPresence.UNKNOWN, CabinPolicy.presence(seats(1000, false, null),1000))
        assertEquals(CabinPresence.UNKNOWN, CabinPolicy.presence(emptyList(),1000))
        assertEquals(CabinPresence.UNKNOWN, CabinPolicy.presence(seats(1000, false, false),32000))
    }
    @Test fun clearRequiresBaselineStabilityAndFreshReadingsAndDoesNotResolveHazard() {
        val gate=CabinClearGate()
        assertFalse(gate.update(seats(0,false,false),0)); assertFalse(gate.update(seats(4000,false,false),4000))
        assertFalse(gate.update(seats(5000,true,false),5000))
        assertFalse(gate.update(seats(6000,false,false),6000))
        assertFalse(gate.update(seats(8000,false,null),8000))
        assertFalse(gate.update(seats(9000,false,false),9000))
        assertTrue(gate.update(seats(12000,false,false),12000))
        val incident=Incident("event",1000,"TEST")
        assertEquals(SupportScreen.MONITORING,SupportPolicy.screen(incident,SupportJourney(),true))
        assertEquals(HazardState.ACTIVE,incident.hazard); assertFalse(incident.evacuated)
        assertFalse(gate.update(seats(13000,false,true),13000))
        assertFalse(gate.update(seats(14000,false),14000))
        assertFalse(gate.update(seats(18000,false),18000))
    }
    @Test fun initiallyEmptyPassengerBecomingUnknownCannotDisappearFromClearGate() {
        val gate = CabinClearGate()
        assertFalse(gate.update(seats(1_000, true, false), 1_000))
        assertFalse(gate.update(seats(2_000, false, null), 2_000))
        assertFalse(gate.update(seats(6_000, false, null), 6_000))
        assertFalse(gate.update(seats(7_000, false), 7_000))
        assertFalse(gate.update(seats(11_000, false), 11_000))
    }
}
