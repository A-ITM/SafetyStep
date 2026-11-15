package io.github.aitm.pleosapp.vehicle

interface VehicleGateway : AutoCloseable {
    val sourceLabel: String
    fun watchDrivingRestrictions(onRestricted: (Boolean) -> Unit)
    suspend fun readDriverDoor(): String
    suspend fun readGear(): String
    suspend fun readTrunk(): String
    suspend fun readSnapshot(): VehicleSnapshot
    suspend fun watchAirbag(onValue: (Boolean?) -> Unit, onError: (String) -> Unit)
    suspend fun enableHazardLights(onCommand: () -> Unit): String
    suspend fun openTrunk(): String
}
