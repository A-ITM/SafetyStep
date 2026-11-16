package io.github.aitm.pleosapp.prototype

data class PollContext(val requestId: String, val position: RoadPosition, val generation: Long)
data class HazardSnapshot(
    val requestId: String, val epoch: String, val scopeKey: String, val scopeVersion: Int,
    val serverNow: Long, val complete: Boolean, val sourceHealth: String, val events: List<Hazard>,
)
enum class SnapshotApplication { APPLIED, RESET, OBSOLETE, REJECTED }

/** Snapshot replacement and version history are one transaction. Absence is never resolution. */
class ReceiverSnapshotState {
    var events: List<Hazard> = emptyList()
        private set
    var epoch: String? = null
        private set
    var confirmed: Boolean = false
        private set
    private var versions = mutableMapOf<String, Int>()
    private var records = mutableMapOf<String, Hazard>()
    private var serverNow = 0L
    private var receivedElapsed = 0L
    private var serverHighWater = 0L

    fun invalidate(clearDisplay: Boolean = false) {
        confirmed = false
        if (clearDisplay) events = emptyList()
    }

    fun apply(snapshot: HazardSnapshot, captured: PollContext, generation: Long, currentScope: String?, elapsed: Long): SnapshotApplication {
        if (captured.generation != generation || captured.position.scopeKey != currentScope) return SnapshotApplication.OBSOLETE
        // The transport validates all records first; also guard the envelope at the state boundary.
        if (snapshot.requestId != captured.requestId || snapshot.scopeKey != captured.position.scopeKey ||
            !snapshot.complete || snapshot.scopeVersion != 1 || snapshot.sourceHealth != "TEST_ONLY" || snapshot.epoch.isBlank()) {
            confirmed = false
            return SnapshotApplication.REJECTED
        }
        val reset = epoch != null && epoch != snapshot.epoch
        val baseline = if (epoch == snapshot.epoch) versions else emptyMap()
        val priorRecords = if (epoch == snapshot.epoch) records else emptyMap()
        if (snapshot.events.any { event ->
            val prior = priorRecords[event.id]
            event.version < (baseline[event.id] ?: 0) || (prior != null &&
                (event.copy(version = prior.version, status = prior.status) != prior ||
                    (event.version == prior.version && event.status != prior.status)))
        }) {
            confirmed = false
            return SnapshotApplication.REJECTED
        }
        val nextVersions = baseline.toMutableMap()
        val nextRecords = priorRecords.toMutableMap()
        snapshot.events.forEach { nextVersions[it.id] = it.version }
        snapshot.events.forEach { nextRecords[it.id] = it }
        // Never let a later poll or wall-clock adjustment make a stale record fresh again.
        val previousEstimate = if (epoch == snapshot.epoch) estimatedServerNow(elapsed) else 0L
        serverHighWater = maxOf(previousEstimate, snapshot.serverNow)
        serverNow = serverHighWater
        receivedElapsed = elapsed
        epoch = snapshot.epoch
        versions = nextVersions
        records = nextRecords
        events = snapshot.events.toList()
        confirmed = true
        return if (reset) SnapshotApplication.RESET else SnapshotApplication.APPLIED
    }

    fun estimatedServerNow(elapsed: Long): Long = maxOf(serverHighWater, serverNow + (elapsed - receivedElapsed).coerceAtLeast(0))
    fun freshness(event: Hazard, elapsed: Long): EventFreshness = when {
        !confirmed -> EventFreshness.UNCONFIRMED
        estimatedServerNow(elapsed) >= event.expiresAt -> EventFreshness.STALE
        else -> EventFreshness.FRESH
    }
}

/** A low-speed hold never consumes a warning ID. Unknown speed cannot qualify a hold. */
class WarningVoicePolicy {
    private var slowSince: Long? = null
    fun update(position: RoadPosition?, now: Long, elapsed: Long) {
        if (position?.valid(now) != true || position.speedMps == null || position.speedMps > 3.0) slowSince = null
        else if (slowSince == null) slowSince = elapsed
    }
    fun held(event: Hazard, distance: Double, elapsed: Long): Boolean =
        event.eventType == EventType.STOPPED_VEHICLE && distance > 100 &&
            slowSince?.let { elapsed - it >= 3_000 } == true
    fun reset() { slowSince = null }
}

data class RestartEvidence(val startedAt: Long, val observedAt: Long, val speedMps: Double) {
    val durationMs: Long get() = observedAt - startedAt
}
data class StopObservation(val firstObservedAt: Long?, val qualified: Boolean, val restarted: RestartEvidence? = null)

/** Only newly observed TEST samples advance these timers; polling never refreshes a fix. */
class StopObservationPolicy {
    private var stopAt: Long? = null
    private var stopElapsed: Long? = null
    private var movingAt: Long? = null
    private var movingElapsed: Long? = null
    private var minimumMovingSpeed = Double.POSITIVE_INFINITY
    private var lastObservedAt = Long.MIN_VALUE
    private var scope: String? = null

    fun reset() {
        stopAt = null; stopElapsed = null; movingAt = null; movingElapsed = null
        minimumMovingSpeed = Double.POSITIVE_INFINITY; lastObservedAt = Long.MIN_VALUE; scope = null
    }

    fun observe(position: RoadPosition?, now: Long, elapsed: Long): StopObservation {
        if (position?.valid(now) != true || position.speedMps == null) {
            stopAt = null; stopElapsed = null; movingAt = null; movingElapsed = null
            return StopObservation(null, false)
        }
        if (scope != position.scopeKey || position.observedAt <= lastObservedAt ||
            (lastObservedAt != Long.MIN_VALUE && position.observedAt - lastObservedAt > WarningPolicy.POSITION_TTL_MS)) {
            if (position.observedAt == lastObservedAt && scope == position.scopeKey) return StopObservation(stopAt, false)
            reset()
        }
        scope = position.scopeKey; lastObservedAt = position.observedAt
        val speed = position.speedMps
        if (speed <= .5 && position.road == "test-highway" && position.roadKind in setOf(RoadKind.MAINLINE, RoadKind.SHOULDER)) {
            movingAt = null; movingElapsed = null; minimumMovingSpeed = Double.POSITIVE_INFINITY
            if (stopAt == null) { stopAt = position.observedAt; stopElapsed = elapsed }
            return StopObservation(stopAt, position.observedAt - stopAt!! >= 5_000 && elapsed - stopElapsed!! >= 5_000)
        }
        stopAt = null; stopElapsed = null
        if (speed >= 2.0) {
            if (movingAt == null) { movingAt = position.observedAt; movingElapsed = elapsed; minimumMovingSpeed = speed }
            minimumMovingSpeed = minOf(minimumMovingSpeed, speed)
            val evidence = if (position.observedAt - movingAt!! >= 3_000 && elapsed - movingElapsed!! >= 3_000)
                RestartEvidence(movingAt!!, position.observedAt, minimumMovingSpeed) else null
            return StopObservation(null, false, evidence)
        }
        movingAt = null; movingElapsed = null; minimumMovingSpeed = Double.POSITIVE_INFINITY
        return StopObservation(null, false)
    }
}
