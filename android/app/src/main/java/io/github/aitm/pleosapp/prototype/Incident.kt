package io.github.aitm.pleosapp.prototype

enum class ResponseState { IDLE, WAITING, RETRYING, RESPONDED, UNCONFIRMED, UNAVAILABLE }
enum class Responder { UNKNOWN, DRIVER, PASSENGER }
enum class HelpState { UNKNOWN, CAN_MOVE, NEEDS_HELP }
enum class HazardState { ACTIVE, RETRACTED, RESOLVED }
enum class EventType { ACCIDENT, STOPPED_VEHICLE }
enum class RoadKind { MAINLINE, SHOULDER, FACILITY, UNKNOWN }

data class Incident(
    val id: String,
    val observedAt: Long,
    val source: String,
    val response: ResponseState = ResponseState.IDLE,
    val responder: Responder = Responder.UNKNOWN,
    val help: HelpState = HelpState.UNKNOWN,
    val evacuated: Boolean = false,
    val hazard: HazardState = HazardState.ACTIVE,
    val version: Int = 1,
    val eventType: EventType = EventType.STOPPED_VEHICLE,
) {
    fun beginResponse() = if (response == ResponseState.IDLE) copy(response = ResponseState.WAITING) else this

    fun timeout() = when (response) {
        ResponseState.WAITING -> copy(response = ResponseState.RETRYING)
        ResponseState.RETRYING -> copy(response = ResponseState.UNCONFIRMED)
        else -> this
    }

    fun respond(who: Responder, needs: HelpState) = copy(
        response = ResponseState.RESPONDED, responder = who, help = needs,
    )

    fun voiceFailed() = if (response in listOf(ResponseState.WAITING, ResponseState.RETRYING)) {
        copy(response = ResponseState.UNAVAILABLE)
    } else this

    // Occupant updates never clear the road hazard or bump its shared version.
    fun reportEvacuated() = copy(evacuated = true)
    fun resolve() = if (hazard == HazardState.ACTIVE) {
        copy(hazard = HazardState.RESOLVED, version = version + 1)
    } else this
}

/** Test route coordinates are distances along a directed road, NOT latitude/longitude. */
data class RoadPosition(
    val road: String, val direction: String, val meters: Double,
    val speedMps: Double?, val observedAt: Long,
    val roadKind: RoadKind = RoadKind.UNKNOWN,
    val source: String = "TEST",
) {
    fun valid(now: Long): Boolean = source == "TEST" &&
        road in setOf("test-highway", "parallel-highway") && direction in setOf("east", "west") &&
        meters.isFinite() && meters in 0.0..10_000.0 &&
        (speedMps == null || (speedMps.isFinite() && speedMps >= 0)) &&
        observedAt > 0 && now - observedAt in -5_000..WarningPolicy.POSITION_TTL_MS
    val scopeKey: String get() = "v1:$road:$direction"
}

data class Hazard(
    val id: String, val owner: String, val version: Int, val observedAt: Long,
    val expiresAt: Long, val road: String, val direction: String,
    val meters: Double, val status: HazardState, val source: String,
    val eventType: EventType = EventType.STOPPED_VEHICLE,
    val simulation: Boolean = true,
)

enum class WarningDecision { WARN, OWN_EVENT, RESOLVED, STALE, DIFFERENT_ROAD, DIFFERENT_DIRECTION, PASSED, TOO_FAR, INVALID_POSITION }

object WarningPolicy {
    // Prototype experiment values, not validated road-safety thresholds.
    const val POSITION_TTL_MS = 15_000L
    fun evaluate(hazard: Hazard, vehicle: String, position: RoadPosition, now: Long): WarningDecision {
        if (hazard.owner == vehicle) return WarningDecision.OWN_EVENT
        if (hazard.status != HazardState.ACTIVE) return WarningDecision.RESOLVED
        if (now >= hazard.expiresAt || hazard.observedAt > now + 5_000) return WarningDecision.STALE
        if (!position.meters.isFinite() || position.speedMps?.let { !it.isFinite() || it < 0 } == true ||
            now - position.observedAt !in -5_000..POSITION_TTL_MS) return WarningDecision.INVALID_POSITION
        if (position.road != hazard.road) return WarningDecision.DIFFERENT_ROAD
        if (position.direction != hazard.direction) return WarningDecision.DIFFERENT_DIRECTION
        val distance = hazard.meters - position.meters
        if (distance <= 0) return WarningDecision.PASSED
        val leadDistance = position.speedMps?.let { (it * 20).coerceIn(200.0, 1_000.0) } ?: 1_000.0
        return if (distance <= leadDistance) WarningDecision.WARN else WarningDecision.TOO_FAR
    }
}

/** Requires an observed false baseline. An old true value at app startup is not a new crash. */
class DeploymentEdge(initial: Boolean? = null) {
    var last: Boolean? = initial
        private set
    fun update(value: Boolean?): Boolean {
        if (value == null) return false
        val rising = last == false && value
        last = value
        return rising
    }
}
