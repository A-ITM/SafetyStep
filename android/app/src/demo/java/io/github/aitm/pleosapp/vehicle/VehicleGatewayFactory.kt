package io.github.aitm.pleosapp.vehicle

import android.content.Context

object VehicleGatewayFactory {
    fun create(@Suppress("UNUSED_PARAMETER") context: Context): VehicleGateway = DemoVehicleGateway()
}

private class DemoVehicleGateway : VehicleGateway {
    override fun watchDrivingRestrictions(onRestricted: (Boolean) -> Unit) { onRestricted(false) }
    private var trunkOpened = false
    override val sourceLabel = "샘플 데이터 · 실제 차량 연결 없음"
    override suspend fun readDriverDoor() = "운전석 문: 닫힘 (샘플)"
    override suspend fun readGear() = "P (샘플)"
    override suspend fun readTrunk() = "${if (trunkOpened) "열림" else "닫힘"} (샘플)"
    override suspend fun watchAirbag(onValue: (Boolean?) -> Unit, onError: (String) -> Unit) {
        onError("Demo: 실제 에어백 구독 없음 · 사고 주입 버튼 사용")
    }
    override suspend fun readSnapshot(): VehicleSnapshot {
        val now = System.currentTimeMillis()
        fun <T> sample(value: T) = VehicleReading(value, now, "샘플 상태", true)
        return VehicleSnapshot(sample("P"), sample(false), sample(trunkOpened), sample(true), sample(true), sample(false), true, speedMps = sample(0f), hazardLights = sample(true))
    }
    override suspend fun enableHazardLights(onCommand: () -> Unit) = "샘플 비상등 이미 켜짐 · 실제 차량 제어 없음"
    override suspend fun openTrunk(): String {
        trunkOpened = true
        return "모의 트렁크 열림 · 실제 차량 제어 없음"
    }
    override fun close() = Unit
}
