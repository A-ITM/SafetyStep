package io.github.aitm.pleosapp.vehicle

import android.content.Context

object VehicleGatewayFactory {
    fun create(@Suppress("UNUSED_PARAMETER") context: Context): VehicleGateway = DemoVehicleGateway()
}

private class DemoVehicleGateway : VehicleGateway {
    override val sourceLabel = "샘플 데이터 · 실제 차량 연결 없음"
    override suspend fun readDriverDoor() = "운전석 문: 닫힘 (샘플)"
    override fun close() = Unit
}
