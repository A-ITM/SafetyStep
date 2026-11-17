package io.github.aitm.pleosapp.prototype

import io.github.aitm.pleosapp.vehicle.*
import org.junit.Assert.*
import org.junit.Test

class CabinPolicyTest {
    private fun seats(now: Long, vararg values: Boolean?) = values.mapIndexed { i, value ->
        SeatObservation(i, "seat$i", VehicleReading(value, now))
    }
    private fun areas(now: Long, vararg values: Pair<Int, Boolean?>) = values.map { (area, value) ->
        SeatObservation(area, "seat$area", VehicleReading(value, now))
    }
    private fun confirmQuiet(gate: CabinClearGate, start: Long = 1_000L) {
        assertFalse(gate.update(areas(start, 1 to true, 4 to false), start))
        for (step in 1L..3L) {
            val now = start + step * 1_000
            assertFalse(gate.update(areas(now, 1 to false, 4 to false), now))
        }
        val confirmedAt = start + 4_000
        assertTrue(gate.update(areas(confirmedAt, 1 to false, 4 to false), confirmedAt))
    }

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
        val gate = CabinClearGate()
        assertFalse(gate.update(seats(0, false, false), 0))
        assertFalse(gate.update(seats(4_000, false, false), 4_000))
        assertFalse(gate.update(seats(5_000, true, false), 5_000))
        assertFalse(gate.update(seats(6_000, false, false), 6_000))
        assertFalse(gate.update(seats(8_000, false, null), 8_000))
        for (now in 9_000L..11_000L step 1_000) assertFalse(gate.update(seats(now, false, false), now))
        assertTrue(gate.update(seats(12_000, false, false), 12_000))
        val incident = Incident("event", 1_000, "TEST")
        assertEquals(SupportScreen.MONITORING, SupportPolicy.screen(incident, SupportJourney(), true))
        assertEquals(HazardState.ACTIVE, incident.hazard)
        assertFalse(incident.evacuated)
        assertFalse(gate.update(seats(13_000, false, true), 13_000))
        assertFalse(gate.update(seats(14_000, false), 14_000))
        assertFalse(gate.update(seats(18_000, false), 18_000))
    }
    @Test fun initiallyEmptyPassengerBecomingUnknownCannotDisappearFromClearGate() {
        val gate = CabinClearGate()
        assertFalse(gate.update(seats(1_000, true, false), 1_000))
        assertFalse(gate.update(seats(2_000, false, null), 2_000))
        assertFalse(gate.update(seats(6_000, false, null), 6_000))
        assertFalse(gate.update(seats(7_000, false), 7_000))
        assertFalse(gate.update(seats(11_000, false), 11_000))
    }

    @Test fun confirmedQuietSurvivesUnknownMissingEmptyAndStaleObservationsUntilPositiveOccupancy() {
        val gate = CabinClearGate()
        confirmQuiet(gate)
        assertTrue(gate.update(areas(6_000, 1 to false, 4 to null), 6_000))
        assertTrue(gate.update(emptyList(), 7_000))
        assertTrue(gate.update(areas(8_000, 1 to false), 8_000))
        assertTrue(gate.update(areas(8_000, 1 to true, 4 to false), 39_000))
        assertTrue(gate.update(areas(40_001, 1 to true, 4 to false), 40_000))
        assertFalse(gate.update(areas(41_000, 1 to false, 4 to true), 41_000))
        assertFalse(gate.update(emptyList(), 42_000))
    }

    @Test fun olderPositiveReplayCannotInventAnOccupiedBaselineAfterNewerEmptyEvidence() {
        val gate = CabinClearGate()
        assertFalse(gate.update(areas(2_000, 1 to false, 4 to false), 2_000))
        assertFalse(gate.update(areas(1_000, 1 to true, 4 to false), 2_500))
        for (now in 3_000L..6_000L step 1_000) assertFalse(gate.update(areas(now, 1 to false, 4 to false), now))
        assertNull(gate.quietConfirmedAt)
        confirmQuiet(gate, start = 7_000)
        assertEquals(11_000L, gate.quietConfirmedAt)
    }

    @Test fun olderOrEqualPositiveReplayDuringEmptyRunDoesNotResetNewerEvidence() {
        val gate = CabinClearGate()
        assertFalse(gate.update(areas(1_000, 1 to true, 4 to false), 1_000))
        assertFalse(gate.update(areas(2_000, 1 to false, 4 to false), 2_000))
        assertFalse(gate.update(areas(1_000, 1 to true, 4 to false), 2_400))
        assertFalse(gate.update(areas(2_000, 1 to true, 4 to false), 2_800))
        assertFalse(gate.update(areas(3_000, 1 to false, 4 to false), 3_000))
        assertFalse(gate.update(areas(4_000, 1 to false, 4 to false), 4_000))
        assertTrue(gate.update(areas(5_000, 1 to false, 4 to false), 5_000))
    }

    @Test fun onlyNewerPositiveEvidenceCanRearmConfirmedQuiet() {
        val gate = CabinClearGate()
        confirmQuiet(gate)
        assertEquals(5_000L, gate.quietConfirmedAt)
        assertTrue(gate.update(areas(4_000, 16 to true), 5_500))
        assertTrue(gate.update(areas(4_000, 1 to true, 4 to false), 6_000))
        assertTrue(gate.update(areas(5_000, 1 to false, 4 to true), 7_000))
        assertTrue(gate.update(areas(8_000, 1 to false, 4 to false), 8_000))
        assertTrue(gate.update(areas(7_000, 1 to true, 4 to false), 9_000))
        assertEquals(5_000L, gate.quietConfirmedAt)
        assertFalse(gate.update(areas(10_000, 1 to true, 4 to false), 10_000))
        assertNull(gate.quietConfirmedAt)
        for (now in 11_000L..13_000L step 1_000) assertFalse(gate.update(areas(now, 1 to false, 4 to false), now))
        assertTrue(gate.update(areas(14_000, 1 to false, 4 to false), 14_000))
        assertEquals(14_000L, gate.quietConfirmedAt)
    }

    @Test fun restoredConfirmationWatermarkRejectsReplayedPositivesUntilNewerReentry() {
        val gate = CabinClearGate()
        gate.restoreQuiet(5_000)
        assertEquals(5_000L, gate.quietConfirmedAt)
        assertTrue(gate.update(emptyList(), 8_000))
        assertTrue(gate.update(areas(4_000, 1 to true, 4 to false), 8_000))
        assertTrue(gate.update(areas(5_000, 1 to true, 4 to true), 9_000))
        assertTrue(gate.update(areas(4_500, 16 to false), 9_500))
        // Learning an older partial empty response must not replace the durable restore watermark.
        assertTrue(gate.update(areas(4_750, 16 to true), 10_000))
        assertFalse(gate.update(areas(10_001, 1 to false, 4 to true), 10_001))
        assertNull(gate.quietConfirmedAt)
        gate.reset()
        assertNull(gate.quietConfirmedAt)
        assertFalse(gate.update(areas(11_000, 1 to false, 4 to false), 11_000))
    }

    @Test fun unknownOrMissingAreaBeforeConfirmationRestartsTheEmptyRun() {
        val gate = CabinClearGate()
        assertFalse(gate.update(areas(1_000, 1 to true, 4 to false), 1_000))
        assertFalse(gate.update(areas(2_000, 1 to false, 4 to false), 2_000))
        assertFalse(gate.update(areas(3_000, 1 to false, 4 to null), 3_000))
        assertFalse(gate.update(areas(4_000, 1 to false), 4_000))
        for (now in 5_000L..7_000L step 1_000) assertFalse(gate.update(areas(now, 1 to false, 4 to false), now))
        assertTrue(gate.update(areas(8_000, 1 to false, 4 to false), 8_000))
    }

    @Test fun areasObservedBeforeOccupiedBaselineCannotDisappearFromExpectedCoverage() {
        val gate = CabinClearGate()
        assertFalse(gate.update(areas(1_000, 1 to null, 4 to null), 1_000))
        assertFalse(gate.update(areas(2_000, 1 to true), 2_000))
        for (now in 3_000L..7_000L step 1_000) assertFalse(gate.update(areas(now, 1 to false), now))
        for (now in 8_000L..10_000L step 1_000) assertFalse(gate.update(areas(now, 4 to false, 1 to false), now))
        assertTrue(gate.update(areas(11_000, 4 to false, 1 to false), 11_000))
    }

    @Test fun changedCoverageAndDuplicateAreaIdsCannotConfirmEmptyCabin() {
        val gate = CabinClearGate()
        assertFalse(gate.update(areas(1_000, 1 to true, 4 to false), 1_000))
        assertFalse(gate.update(areas(2_000, 1 to false, 4 to false), 2_000))
        assertFalse(gate.update(areas(3_000, 1 to false, 16 to false), 3_000))
        assertFalse(gate.update(areas(4_000, 1 to false, 4 to false), 4_000))
        assertFalse(gate.update(areas(5_000, 1 to false, 4 to false, 4 to false, 16 to false), 5_000))
        for (now in 6_000L..8_000L step 1_000) assertFalse(gate.update(areas(now, 1 to false, 4 to false, 16 to false), now))
        assertTrue(gate.update(areas(9_000, 16 to false, 4 to false, 1 to false), 9_000))
    }

    @Test fun freshCachedEmptySnapshotDoesNotCountAsThreeSecondsOfNewEvidence() {
        val gate = CabinClearGate()
        assertFalse(gate.update(areas(1_000, 1 to true, 4 to false), 1_000))
        val cached = areas(2_000, 1 to false, 4 to false)
        for (now in 2_000L..6_000L step 500) assertFalse(gate.update(cached, now))
        for (now in 7_000L..9_000L step 1_000) assertFalse(gate.update(areas(now, 1 to false, 4 to false), now))
        assertTrue(gate.update(areas(10_000, 1 to false, 4 to false), 10_000))
    }

    @Test fun longPollingGapRestartsPreConfirmationButTwoSecondCadenceIsAccepted() {
        val gate = CabinClearGate()
        assertFalse(gate.update(areas(1_000, 1 to true, 4 to false), 1_000))
        assertFalse(gate.update(areas(2_000, 1 to false, 4 to false), 2_000))
        assertFalse(gate.update(areas(3_000, 1 to false, 4 to false), 3_000))
        assertFalse(gate.update(areas(6_000, 1 to false, 4 to false), 6_000))
        assertFalse(gate.update(areas(8_000, 1 to false, 4 to false), 8_000))
        assertTrue(gate.update(areas(9_000, 1 to false, 4 to false), 9_000))
    }

    @Test fun oldOrOutOfOrderEmptyEvidenceCannotClearAnOccupiedBaseline() {
        val gate = CabinClearGate()
        assertFalse(gate.update(areas(2_000, 1 to true, 4 to false), 2_000))
        assertFalse(gate.update(areas(1_000, 1 to false, 4 to false), 3_000))
        assertFalse(gate.update(areas(4_000, 1 to false, 4 to false), 4_000))
        val reordered = listOf(areas(3_000, 1 to false).single(), areas(5_000, 4 to false).single())
        assertFalse(gate.update(reordered, 5_000))
        for (now in 6_000L..8_000L step 1_000) assertFalse(gate.update(areas(now, 1 to false, 4 to false), now))
        assertTrue(gate.update(areas(9_000, 1 to false, 4 to false), 9_000))
    }

    @Test fun staleOrFutureEmptyReadingsCannotCompletePreConfirmationRun() {
        val gate = CabinClearGate()
        assertFalse(gate.update(areas(1_000, 1 to true, 4 to false), 1_000))
        assertFalse(gate.update(areas(2_000, 1 to false, 4 to false), 2_000))
        assertFalse(gate.update(areas(2_000, 1 to false, 4 to false), 32_001))
        assertFalse(gate.update(areas(32_002, 1 to false, 4 to false), 32_001))
        for (now in 33_000L..35_000L step 1_000) assertFalse(gate.update(areas(now, 1 to false, 4 to false), now))
        assertTrue(gate.update(areas(36_000, 1 to false, 4 to false), 36_000))
    }

    @Test fun restoringSameIncidentQuietDoesNotRequireFreshEmptySignalsAndReentryRearmsGate() {
        val gate = CabinClearGate()
        gate.restoreQuiet()
        assertTrue(gate.update(emptyList(), 1_000))
        assertTrue(gate.update(areas(2_000, 1 to null), 2_000))
        assertFalse(gate.reliableEmptyNow(areas(3_000, 1 to false), 3_000))
        confirmQuiet(gate, start = 4_000)
        gate.reset()
        for (now in 9_000L..13_000L step 1_000) assertFalse(gate.update(areas(now, 1 to false), now))
        assertFalse(gate.update(areas(14_000, 1 to true), 14_000))
        for (now in 15_000L..17_000L step 1_000) assertFalse(gate.update(areas(now, 1 to false), now))
        assertTrue(gate.update(areas(18_000, 1 to false), 18_000))
    }

    @Test fun reliableEmptyNowSeparatesLatestCompleteObservationFromLatchedQuiet() {
        val gate = CabinClearGate()
        assertFalse(gate.reliableEmptyNow(emptyList(), 1_000))
        assertFalse(gate.reliableEmptyNow(areas(1_000, 1 to false, 4 to false), 1_000))
        confirmQuiet(gate)
        assertFalse(gate.reliableEmptyNow(areas(4_000, 1 to false, 4 to false), 6_000))
        assertTrue(gate.reliableEmptyNow(areas(6_000, 1 to false, 4 to false), 6_000))
        assertFalse(gate.reliableEmptyNow(areas(7_000, 1 to false), 7_000))
        assertFalse(gate.reliableEmptyNow(areas(8_000, 1 to false, 4 to null), 8_000))
        assertFalse(gate.reliableEmptyNow(areas(8_000, 1 to false, 4 to false), 39_000))
        assertTrue(gate.update(areas(9_000, 1 to false), 9_000))
    }

    @Test fun passengerRemainsRequiresPreviouslyOccupiedDriverNowExplicitlyEmptyAndFreshPassenger() {
        val now = 10_000L
        val occupiedPassenger = areas(now, 1 to false, 4 to true, 16 to false)
        assertTrue(CabinPolicy.passengerRemains(occupiedPassenger, now, setOf(1)))
        assertTrue(CabinPolicy.passengerRemains(areas(now, 1 to false, 16 to true), now, setOf(1, 16)))
        assertFalse(CabinPolicy.passengerRemains(occupiedPassenger, now, emptySet()))
        assertFalse(CabinPolicy.passengerRemains(occupiedPassenger, now, setOf(4)))
        assertFalse(CabinPolicy.passengerRemains(areas(now, 1 to true, 4 to true), now, setOf(1)))
        assertFalse(CabinPolicy.passengerRemains(areas(now, 1 to null, 4 to true), now, setOf(1)))
        assertFalse(CabinPolicy.passengerRemains(areas(now, 4 to true), now, setOf(1)))
        assertFalse(CabinPolicy.passengerRemains(areas(now, 1 to false, 4 to null), now, setOf(1)))
        assertFalse(CabinPolicy.passengerRemains(areas(now, 1 to false, 4 to false), now, setOf(1)))
        assertFalse(CabinPolicy.passengerRemains(emptyList(), now, setOf(1)))
    }

    @Test fun passengerRemainsDoesNotUseStaleFutureOrAmbiguousDriverSignals() {
        val now = 40_000L
        assertFalse(CabinPolicy.passengerRemains(listOf(areas(9_999, 1 to false).single(),
            areas(now, 4 to true).single()), now, setOf(1)))
        assertFalse(CabinPolicy.passengerRemains(listOf(areas(now, 1 to false).single(),
            areas(9_999, 4 to true).single()), now, setOf(1)))
        assertFalse(CabinPolicy.passengerRemains(areas(now + 1, 1 to false, 4 to true), now, setOf(1)))
        assertFalse(CabinPolicy.passengerRemains(areas(now, 1 to false, 1 to true, 4 to true), now, setOf(1)))
    }
}
