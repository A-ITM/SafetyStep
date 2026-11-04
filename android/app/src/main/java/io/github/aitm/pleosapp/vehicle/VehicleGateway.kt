package io.github.aitm.pleosapp.vehicle

interface VehicleGateway : AutoCloseable {
    val sourceLabel: String
    suspend fun readDriverDoor(): String
}
