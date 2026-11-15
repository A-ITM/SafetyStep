package io.github.aitm.pleosapp.vehicle

/** A missing or stale observation must never become a false/normal value. */
data class VehicleReading<T>(
    val value: T? = null,
    val observedAt: Long = 0,
    val detail: String = "아직 확인하지 못했어요",
    val sample: Boolean = false,
) {
    fun current(now: Long): T? = value?.takeIf { now - observedAt in 0..FRESHNESS_MS }
    companion object { const val FRESHNESS_MS = 30_000L } // Prototype freshness window, not a safety threshold.
}

data class VehicleSnapshot(
    val gear: VehicleReading<String> = VehicleReading(),
    val driverDoorOpen: VehicleReading<Boolean> = VehicleReading(),
    val trunkOpen: VehicleReading<Boolean> = VehicleReading(),
    val driverOccupied: VehicleReading<Boolean> = VehicleReading(),
    val driverBelt: VehicleReading<Boolean> = VehicleReading(),
    val brakePressed: VehicleReading<Boolean> = VehicleReading(),
    val sample: Boolean = false,
    val seats: List<SeatObservation> = emptyList(),
    val speedMps: VehicleReading<Float> = VehicleReading(),
    val hazardLights: VehicleReading<Boolean> = VehicleReading(),
)
