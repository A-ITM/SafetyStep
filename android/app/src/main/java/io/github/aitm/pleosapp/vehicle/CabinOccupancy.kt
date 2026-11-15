package io.github.aitm.pleosapp.vehicle

data class SeatObservation(val area: Int, val label: String, val occupied: VehicleReading<Boolean>)
enum class CabinPresence { OCCUPIED, EMPTY, UNKNOWN }
object CabinPolicy {
    fun decode(raw: Int?): Boolean? = when (raw) { 1 -> false; 2 -> true; else -> null }
    fun presence(seats: List<SeatObservation>, now: Long): CabinPresence = when {
        seats.any { it.occupied.current(now) == true } -> CabinPresence.OCCUPIED
        seats.isEmpty() || seats.any { it.occupied.current(now) == null } -> CabinPresence.UNKNOWN
        else -> CabinPresence.EMPTY
    }
}

/** Require an occupied baseline and three seconds of fresh, all-seat empty readings. */
class CabinClearGate {
    private var occupiedBaseline = false
    private var expectedAreas = emptySet<Int>()
    private var emptySince: Long? = null
    fun reset() { occupiedBaseline = false; emptySince = null; expectedAreas = emptySet() }
    fun update(seats: List<SeatObservation>, now: Long): Boolean {
        when (CabinPolicy.presence(seats, now)) {
            CabinPresence.OCCUPIED -> { occupiedBaseline = true; expectedAreas = expectedAreas + seats.map { it.area }; emptySince = null }
            CabinPresence.UNKNOWN -> emptySince = null
            CabinPresence.EMPTY -> {
                if (seats.map { it.area }.toSet() != expectedAreas) emptySince = null
                else if (occupiedBaseline && emptySince == null) emptySince = now
            }
        }
        return emptySince?.let { now - it >= 3_000 } ?: false
    }
}

interface SeatOccupancyReader {
    suspend fun read(area: Int): VehicleReading<Boolean>
    suspend fun readSpeed(): VehicleReading<Float> = VehicleReading(detail = "차량 속도 읽기 미지원")
    suspend fun readHazard(): VehicleReading<Boolean> = VehicleReading(detail = "비상등 읽기 미지원")
    suspend fun enableHazardLights(onCommand: () -> Unit): String = error("비상등 제어 미지원")
    fun close()
}
