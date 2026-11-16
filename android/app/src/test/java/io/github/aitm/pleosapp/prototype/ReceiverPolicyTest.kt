package io.github.aitm.pleosapp.prototype

import org.junit.Assert.*
import org.junit.Test

class ReceiverPolicyTest {
    private val now = 1_000_000L
    private val position = RoadPosition("test-highway", "east", 600.0, 25.0, now, RoadKind.MAINLINE)
    private val context = PollContext("poll-one", position, 1)
    private val event = Hazard("one", "A", 1, now, now + 180_000, "test-highway", "east", 1_000.0, HazardState.ACTIVE, "STOP_OBSERVATION")
    private fun snapshot(events: List<Hazard> = listOf(event), epoch: String = "epoch-one", time: Long = now) =
        HazardSnapshot("poll-one", epoch, position.scopeKey, 1, time, true, "TEST_ONLY", events)

    @Test fun absentCompleteSnapshotReplacesDisplayButVersionTombstonePreventsResurrection() {
        val state = ReceiverSnapshotState()
        assertEquals(SnapshotApplication.APPLIED, state.apply(snapshot(), context, 1, position.scopeKey, 100))
        val terminal = event.copy(version = 2, status = HazardState.RETRACTED)
        state.apply(snapshot(listOf(terminal)), context, 1, position.scopeKey, 200)
        state.apply(snapshot(emptyList()), context, 1, position.scopeKey, 300)
        assertTrue(state.events.isEmpty())
        assertEquals(SnapshotApplication.REJECTED, state.apply(snapshot(), context, 1, position.scopeKey, 400))
        assertTrue(state.events.isEmpty())
        assertFalse(state.confirmed)
    }

    @Test fun obsoleteContextAndInvalidEnvelopeNeverApplyPartialLists() {
        val state = ReceiverSnapshotState()
        state.apply(snapshot(), context, 1, position.scopeKey, 100)
        assertEquals(SnapshotApplication.OBSOLETE, state.apply(snapshot(emptyList()), context, 2, position.scopeKey, 200))
        assertEquals(listOf(event), state.events)
        assertEquals(SnapshotApplication.REJECTED, state.apply(snapshot(emptyList()).copy(complete = false), context, 1, position.scopeKey, 300))
        assertEquals(listOf(event), state.events)
        assertEquals(EventFreshness.UNCONFIRMED, state.freshness(event, 400))
    }

    @Test fun sameVersionPayloadMutationRejectsWholeSnapshot() {
        val state = ReceiverSnapshotState()
        state.apply(snapshot(), context, 1, position.scopeKey, 100)
        assertEquals(SnapshotApplication.REJECTED, state.apply(snapshot(listOf(event.copy(meters = 999.0))), context, 1, position.scopeKey, 200))
        assertEquals(listOf(event), state.events)
    }

    @Test fun freshnessUsesMonotonicElapsedAndCannotRegressOnPolling() {
        val state = ReceiverSnapshotState()
        state.apply(snapshot(), context, 1, position.scopeKey, 100)
        assertEquals(EventFreshness.STALE, state.freshness(event, 180_100))
        state.apply(snapshot(time = now - 50_000), context, 1, position.scopeKey, 180_100)
        assertEquals(EventFreshness.STALE, state.freshness(event, 180_101))
        assertEquals(SnapshotApplication.RESET, state.apply(snapshot(epoch = "epoch-two"), context, 1, position.scopeKey, 180_200))
        assertEquals(EventFreshness.FRESH, state.freshness(event, 180_201))
    }

    @Test fun lowSpeedHoldOnlyAffectsStoppedVoiceAndUnknownSpeedDoesNotHold() {
        val policy = WarningVoicePolicy()
        val slow = position.copy(speedMps = 0.0)
        policy.update(slow, now, 100)
        policy.update(slow.copy(observedAt = now + 3_000), now + 3_000, 3_100)
        assertTrue(policy.held(event, 400.0, 3_100))
        assertFalse(policy.held(event, 100.0, 3_100))
        assertFalse(policy.held(event.copy(eventType = EventType.ACCIDENT), 400.0, 3_100))
        policy.update(slow.copy(speedMps = null), now + 3_000, 3_100)
        assertFalse(policy.held(event, 400.0, 3_100))
        assertEquals(WarningDecision.WARN, WarningPolicy.evaluate(event, "B", position.copy(meters = 100.0, speedMps = null), now))
    }
}
