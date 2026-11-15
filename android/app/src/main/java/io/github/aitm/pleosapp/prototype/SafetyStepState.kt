package io.github.aitm.pleosapp.prototype

import io.github.aitm.pleosapp.vehicle.VehicleSnapshot

enum class ThemePreference(val label: String) { SYSTEM("시스템"), LIGHT("라이트"), DARK("다크") }
enum class CollisionContext(val label: String) { UNKNOWN("아직 몰라요"), REAR("후방 충돌"), FRONT("전방 충돌"), OTHER("그 외 사고"), BREAKDOWN("차량 고장") }
enum class ControlState { IDLE, CHECKING, CONFIRMED, HELD, UNCONFIRMED }
enum class SupportScreen { DRIVE, INTAKE, RESPONSE, MOBILITY, EVACUATION, SUPPORT, MONITORING, HELP }

/** Presentation progress is independent of the road hazard and occupant report. */
data class SupportJourney(
    val collision: CollisionContext = CollisionContext.UNKNOWN,
    val stationaryConfirmed: Boolean = false,
    val routeConfirmed: Boolean = false,
    val departureDeclared: Boolean = false,
    val callSimulation: Boolean = false,
    val trunk: ControlState = ControlState.IDLE,
    val trunkNote: String = "열기 요청 전",
    val trunkAttempts: Int = 0,
    val rearClearConfirmed: Boolean = false,
    val autoTrunkClaimed: Boolean = false,
)

object SupportPolicy {
    fun screen(incident: Incident?, journey: SupportJourney, cabinClear: Boolean = false): SupportScreen = when {
        incident == null || incident.hazard == HazardState.RESOLVED -> SupportScreen.DRIVE
        cabinClear -> SupportScreen.MONITORING
        journey.callSimulation || incident.help == HelpState.NEEDS_HELP -> SupportScreen.HELP
        incident.evacuated || (journey.departureDeclared && incident.help == HelpState.CAN_MOVE) -> SupportScreen.SUPPORT
        incident.response == ResponseState.RESPONDED && incident.help == HelpState.CAN_MOVE -> SupportScreen.EVACUATION
        incident.response == ResponseState.RESPONDED -> SupportScreen.MOBILITY
        incident.response == ResponseState.IDLE -> SupportScreen.INTAKE
        else -> SupportScreen.RESPONSE
    }

    fun shouldAutoOpenTrunk(incident: Incident?, journey: SupportJourney, facts: VehicleSnapshot, now: Long): Boolean =
        incident?.hazard == HazardState.ACTIVE && incident.help == HelpState.CAN_MOVE &&
            journey.collision == CollisionContext.FRONT && journey.rearClearConfirmed && !journey.autoTrunkClaimed &&
            trunkBlock(journey, facts, now, true) == null

    fun trunkBlock(journey: SupportJourney, facts: VehicleSnapshot, now: Long, finalConfirmation: Boolean): String? = when {
        journey.callSimulation -> "연락 지원 중에는 차량 제어를 보류해요."
        journey.departureDeclared -> "대피 안내를 마쳤어요. 차량으로 돌아가 확인하지 마세요."
        journey.collision == CollisionContext.REAR -> "후방 충돌이 보고되어 트렁크 열기를 보류해요."
        journey.collision == CollisionContext.UNKNOWN -> "후방 충격·손상 여부가 확인되지 않았어요."
        !journey.stationaryConfirmed || !BitboxPolicy.stationary(facts, now) -> "차량이 멈췄는지 먼저 확인해주세요."
        facts.gear.current(now) !in listOf("GEAR_PARK", "P") -> "최신 P 기어 상태를 확인할 수 없어요."
        facts.trunkOpen.current(now) == null -> "현재 트렁크 상태를 확인할 수 없어 열기를 보류해요."
        !finalConfirmation -> "트렁크 주변 공간과 열기 의사를 확인해주세요."
        else -> null
    }
}
