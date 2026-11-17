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

    /** Departure requires a previously occupied driver seat, not an initially empty driver seat. */
    fun passengerRemains(seats: List<SeatObservation>, now: Long, observedAreas: Set<Int>): Boolean =
        DRIVER_AREA in observedAreas &&
            seats.filter { it.area == DRIVER_AREA }.singleOrNull()?.occupied?.current(now) == false &&
            seats.any { it.area != DRIVER_AREA && it.occupied.current(now) == true }

    private const val DRIVER_AREA = 1
}

/**
 * Latch quiet after an occupied baseline and three seconds of complete, advancing empty readings.
 * Once quiet, missing signals are uncertainty about a new observation, not evidence of re-entry.
 */
class CabinClearGate {
    private var occupiedBaseline = false
    private var expectedAreas = emptySet<Int>()
    private var emptySince: Long? = null
    private var lastEmptyReceivedAt: Long? = null
    private var evidenceTimes = emptyMap<Int, Long>()
    private var observationTimes = emptyMap<Int, Long>()
    private var quiet = false
    var quietConfirmedAt: Long? = null
        private set

    fun reset() {
        occupiedBaseline = false
        expectedAreas = emptySet()
        evidenceTimes = emptyMap()
        observationTimes = emptyMap()
        quietConfirmedAt = null
        clearEmptyRun()
        quiet = false
    }

    /** Restore the original confirmation timestamp only for the same support incident. */
    fun restoreQuiet(confirmedAt: Long? = null) {
        reset()
        quiet = true
        quietConfirmedAt = confirmedAt?.takeIf { it >= 0 }
    }

    /** Current evidence is complete only when it covers every area observed before confirmation. */
    fun reliableEmptyNow(seats: List<SeatObservation>, now: Long): Boolean {
        val areas = seats.map { it.area }.toSet()
        return expectedAreas.isNotEmpty() && areas == expectedAreas && seats.size == areas.size &&
            seats.all { seat -> seat.occupied.current(now) == false &&
                observationTimes[seat.area]?.let { observed -> seat.occupied.observedAt >= observed } != false }
    }

    fun update(seats: List<SeatObservation>, now: Long): Boolean {
        val current = seats.filter { it.occupied.current(now) != null }
        val positive = current.filter { it.occupied.value == true }
        val newPositive = positive.any { seat ->
            val previous = observationTimes[seat.area]
            val watermark = listOfNotNull(previous, quietConfirmedAt).maxOrNull()
            watermark == null || seat.occupied.observedAt > watermark
        }
        // Keep ordering evidence even before an occupied baseline or during partial/unknown frames.
        val latestTimes = current.groupBy { it.area }.mapValues { (_, observations) ->
            observations.maxOf { it.occupied.observedAt }
        }
        observationTimes = (observationTimes.keys + latestTimes.keys).associateWith { area ->
            maxOf(observationTimes[area] ?: Long.MIN_VALUE, latestTimes[area] ?: Long.MIN_VALUE)
        }
        if (newPositive) {
            quiet = false
            quietConfirmedAt = null
            occupiedBaseline = true
            expectedAreas = expectedAreas + seats.map { it.area }
            evidenceTimes = observationTimes.filterKeys { it in expectedAreas }
            clearEmptyRun()
            return false
        }
        if (quiet) return true

        val areas = expectedAreas + seats.map { it.area }
        if (areas != expectedAreas) clearEmptyRun()
        expectedAreas = areas
        // A replay of an older occupied response cannot overturn newer empty evidence or fill a gap.
        if (positive.isNotEmpty()) {
            if (lastEmptyReceivedAt?.let { now - it !in 0..MAX_EMPTY_GAP_MS } == true) clearEmptyRun()
            return false
        }
        if (!occupiedBaseline || !reliableEmptyNow(seats, now)) {
            clearEmptyRun()
            return false
        }

        val times = seats.associate { it.area to it.occupied.observedAt }
        // Polling one cached snapshot repeatedly does not fill a gap between real observations.
        if (lastEmptyReceivedAt?.let { now - it !in 0..MAX_EMPTY_GAP_MS } == true) clearEmptyRun()
        if (times.any { (area, time) -> evidenceTimes[area]?.let { time <= it } == true }) return false

        if (emptySince == null) emptySince = now
        lastEmptyReceivedAt = now
        evidenceTimes = times
        val start = requireNotNull(emptySince)
        quiet = now - start >= EMPTY_CONFIRMATION_MS && times.values.all { it - start >= EMPTY_CONFIRMATION_MS }
        if (quiet) quietConfirmedAt = now
        return quiet
    }

    private fun clearEmptyRun() { emptySince = null; lastEmptyReceivedAt = null }

    companion object {
        private const val EMPTY_CONFIRMATION_MS = 3_000L
        // Foreground polling runs every 500 ms; two seconds allows ordinary read/scheduling jitter.
        private const val MAX_EMPTY_GAP_MS = 2_000L
    }
}

interface SeatOccupancyReader {
    suspend fun read(area: Int): VehicleReading<Boolean>
    suspend fun readSpeed(): VehicleReading<Float> = VehicleReading(detail = "차량 속도 읽기 미지원")
    suspend fun readHazard(): VehicleReading<Boolean> = VehicleReading(detail = "비상등 읽기 미지원")
    suspend fun enableHazardLights(onCommand: () -> Unit): String = error("비상등 제어 미지원")
    fun close()
}
