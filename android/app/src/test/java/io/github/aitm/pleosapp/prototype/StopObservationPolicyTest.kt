package io.github.aitm.pleosapp.prototype

import org.junit.Assert.*
import org.junit.Test

class StopObservationPolicyTest {
    private val start = 1_000_000L
    private fun point(offset: Long, speed: Double? = 0.0, kind: RoadKind = RoadKind.MAINLINE) =
        RoadPosition("test-highway", "east", 1_000.0, speed, start + offset, kind)

    @Test fun fiveSecondsRequiresNewObservationsAndMonotonicDuration() {
        val policy = StopObservationPolicy()
        assertFalse(policy.observe(point(0), start, 10).qualified)
        assertFalse(policy.observe(point(0), start + 5_000, 5_010).qualified)
        assertTrue(policy.observe(point(5_000), start + 5_000, 5_010).qualified)
        assertEquals(start, policy.observe(point(6_000), start + 6_000, 6_010).firstObservedAt)
        val clockJump = StopObservationPolicy()
        clockJump.observe(point(0), start, 10)
        assertFalse(clockJump.observe(point(5_000), start + 5_000, 20).qualified)
    }

    @Test fun facilityUnknownSpeedInvalidAndStaleBreakCandidate() {
        for (invalid in listOf(point(5_000, kind = RoadKind.FACILITY), point(5_000, null), point(5_000).copy(road = "parallel-highway"), null)) {
            val policy = StopObservationPolicy()
            policy.observe(point(0), start, 10)
            assertFalse(policy.observe(invalid, start + 5_000, 5_010).qualified)
            assertFalse(policy.observe(point(6_000), start + 6_000, 6_010).qualified)
        }
        val policy = StopObservationPolicy()
        policy.observe(point(0), start, 10)
        assertFalse(policy.observe(point(0), start + 20_000, 20_010).qualified)
    }

    @Test fun restartNeedsContinuousFreshThreeSecondsAboveThreshold() {
        val policy = StopObservationPolicy()
        policy.observe(point(0), start, 10)
        policy.observe(point(5_000), start + 5_000, 5_010)
        assertNull(policy.observe(point(6_000, 2.5), start + 6_000, 6_010).restarted)
        assertNull(policy.observe(point(8_000, null), start + 8_000, 8_010).restarted)
        assertNull(policy.observe(point(9_000, 2.5), start + 9_000, 9_010).restarted)
        val evidence = policy.observe(point(12_000, 2.0), start + 12_000, 12_010).restarted!!
        assertEquals(start + 9_000, evidence.startedAt)
        assertEquals(3_000L, evidence.durationMs)
        assertEquals(2.0, evidence.speedMps, 0.0)
    }
}
