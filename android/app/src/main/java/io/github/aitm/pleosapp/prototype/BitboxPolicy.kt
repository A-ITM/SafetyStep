package io.github.aitm.pleosapp.prototype

import io.github.aitm.pleosapp.vehicle.VehicleSnapshot
import kotlin.math.abs

/** Read-only progress. No checklist item is completed by a tap or a command acknowledgement. */
object BitboxPolicy {
    fun stationary(vehicle: VehicleSnapshot, now: Long): Boolean =
        vehicle.gear.current(now) in listOf("P", "GEAR_PARK") &&
            vehicle.speedMps.current(now)?.let { abs(it) <= .05f } == true

    fun somebodyLeft(vehicle: VehicleSnapshot, seen: Set<Int>, now: Long): Boolean =
        vehicle.seats.any { it.area in seen && it.occupied.current(now) == false }
}
