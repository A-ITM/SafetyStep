package io.github.aitm.pleosapp.vehicle

import android.content.Context
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.constant.area.DoorValue
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout

object VehicleGatewayFactory {
    fun create(context: Context): VehicleGateway = PleosVehicleGateway(context.applicationContext)
}

private class PleosVehicleGateway(context: Context) : VehicleGateway {
    override val sourceLabel = "Pleos Vehicle SDK · 차량 상태 읽기"
    private val vehicle = Vehicle(context)
    private val area = DoorArea(DoorValue.ROW_1_LEFT)
    private var initialized = false

    override suspend fun readDriverDoor(): String = withTimeout(8_000) {
        if (!initialized) {
            vehicle.initialize()
            initialized = true
        }
        // Initialization alone does not prove that the Vehicle Service is ready.
        // Only capability and read callbacks can establish a successful result.
        val door = vehicle.getDoor()
        suspendCancellableCoroutine { continuation ->
            door.checkDoorPositionCapabilities(
                onComplete = { _, bounds ->
                    if (continuation.isActive) {
                        if (!bounds.containsKey(area)) {
                            continuation.resumeWithException(
                                IllegalStateException("이 환경에서 운전석 문 상태를 지원하지 않습니다."),
                            )
                        } else {
                            door.getCurrentDoorPosition(
                                area = area,
                                onComplete = { value ->
                                    if (continuation.isActive) {
                                        continuation.resume(
                                            value?.let { "운전석 문 원시 값: $it" }
                                                ?: "현재 문 상태 데이터가 없습니다.",
                                        )
                                    }
                                },
                                onFailed = { error ->
                                    if (continuation.isActive) continuation.resumeWithException(error)
                                },
                            )
                        }
                    }
                },
                onFailed = { error ->
                    if (continuation.isActive) continuation.resumeWithException(error)
                },
            )
        }
    }

    override fun close() {
        if (initialized) {
            vehicle.release()
            initialized = false
        }
    }
}
