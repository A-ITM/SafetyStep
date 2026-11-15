package io.github.aitm.pleosapp.vehicle

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.IBinder
import ai.pleos.playground.vehicle.IVehicleService
import ai.pleos.playground.vehicle.callback.IGetPropertyResult
import ai.pleos.playground.vehicle.callback.ISetPropertyResult
import ai.pleos.playground.vehicle.callback.ICapabilityConfigResult
import android.content.pm.PackageManager
import android.util.Log
import org.json.JSONObject
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object OccupancyReaderFactory {
    fun create(context: Context): SeatOccupancyReader = EmulatorOccupancyReader(context)
}

/** Vehicle 2.3.2/AIDL 1.1.1 emulator compatibility adapter; never used on a physical vehicle.
 * Reads the same permission-checked Vehicle Service as the SDK, before its lossy Boolean conversion.
 * No console HTTP values, reflection, permission bypass, or fabricated fallback observations.
 */
private class EmulatorOccupancyReader(private val context: Context) : SeatOccupancyReader {
    private var ready = CompletableDeferred<IVehicleService>()
    private var bound = false
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) { ready.complete(IVehicleService.Stub.asInterface(binder)) }
        override fun onServiceDisconnected(name: ComponentName) { ready = CompletableDeferred() }
        override fun onBindingDied(name: ComponentName) { ready = CompletableDeferred() }
        override fun onNullBinding(name: ComponentName) { ready.completeExceptionally(IllegalStateException("Vehicle Service 연결 실패")) }
    }
    override suspend fun read(area: Int): VehicleReading<Boolean> {
        val value = raw("SEAT_OCCUPANCY", area)
        return VehicleReading(CabinPolicy.decode(value.value?.toIntOrNull()), value.observedAt, value.detail)
    }
    override suspend fun readSpeed(): VehicleReading<Float> {
        val value = raw("PERF_VEHICLE_SPEED", 0)
        return VehicleReading(value.value?.toFloatOrNull()?.takeIf { it.isFinite() }, value.observedAt, value.detail)
    }
    override suspend fun readHazard(): VehicleReading<Boolean> {
        // This emulator exposes the physical blink phase as STATE (0/1 every 500ms).
        // SWITCH=ON alone is not proof: observe an actual ON phase within a bounded window.
        val enabled = raw("HAZARD_LIGHTS_SWITCH", 0)
        if (enabled.value == "0") return VehicleReading(false, enabled.observedAt, enabled.detail)
        if (enabled.value != "1") return VehicleReading(detail = "비상등 스위치 상태 미확인")
        repeat(5) {
            val actual = raw("HAZARD_LIGHTS_STATE", 0)
            if (actual.value == "1") return VehicleReading(true, actual.observedAt, "Pleos Vehicle Service · SWITCH=1 + STATE=1 점멸 관측")
            if (actual.value == null) return VehicleReading(detail = actual.detail)
            delay(150)
        }
        return VehicleReading(detail = "비상등 ON 스위치 확인 · 실제 점멸 미확인")
    }
    override suspend fun enableHazardLights(onCommand: () -> Unit): String {
        // Explicit, emulator-only test configuration. Never fall back from a denied SDK call.
        val mode = File(context.filesDir, "hazard-control-mode.txt").takeIf { it.exists() }?.readText()?.trim()
        return if (mode == "EMULATOR_CONSOLE") consoleHazards(onCommand) else sdkHazards(onCommand)
    }
    private suspend fun consoleHazards(onCommand: () -> Unit): String = withTimeout(8_000) {
        check(Build.HARDWARE in listOf("ranchu", "goldfish")) { "에뮬레이터 제어 어댑터 전용" }
        if (readHazard().current(System.currentTimeMillis()) == true) return@withTimeout "EMULATOR_CONSOLE · 이미 켜짐 확인 · 명령 없음"
        onCommand()
        withContext(Dispatchers.IO) {
            val connection = URL("http://127.0.0.1:8088/vehicle/lights?feature=hazard&value=true").openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "PUT"; connection.connectTimeout = 2_000; connection.readTimeout = 2_000
                check(connection.responseCode in 200..299) { "에뮬레이터 어댑터 응답 ${connection.responseCode}" }
                connection.inputStream.use { it.readBytes() }
            } finally { connection.disconnect() }
        }
        repeat(6) {
            if (readHazard().current(System.currentTimeMillis()) == true) {
                Log.i("SafetyStepHazard", "EMULATOR_CONSOLE request -> VEHICLE_SERVICE READBACK=1")
                return@withTimeout "EMULATOR_CONSOLE · 앱 자동 요청 → Vehicle Service 실제 켜짐 확인"
            }
            delay(300)
        }
        error("에뮬레이터 요청 후 비상등 켜짐 미확인")
    }
    private suspend fun sdkHazards(onCommand: () -> Unit): String = withTimeout(8_000) {
        check(Build.HARDWARE in listOf("ranchu", "goldfish")) { "비상등 제어는 디버그 에뮬레이터 전용" }
        check(context.checkSelfPermission("pleos.car.permission.CONTROL_CAR_LIGHTS") == PackageManager.PERMISSION_GRANTED) { "비상등 제어 권한 없음" }
        if (readHazard().current(System.currentTimeMillis()) == true) return@withTimeout "이미 켜짐 확인 · 명령 없음"
        val service = ready.await()
        val property = "HAZARD_LIGHTS_SWITCH"
        val json = suspendCancellableCoroutine<String> { c ->
            service.getCapabilityConfigByKey(property, object : ICapabilityConfigResult.Stub() {
                override fun onResult(value: String?) { if(c.isActive) c.resume(value ?: "{}") }
                override fun onFailed(message: String?) { if(c.isActive) c.resumeWithException(IllegalStateException(message)) }
            })
        }
        Log.i("SafetyStepHazard", "capability=$json")
        val config = JSONObject(json)
        check(config.optInt("propertyId") == 0x11400e13 && config.optInt("access") in listOf(2, 3)) { "비상등 쓰기 capability 미지원" }
        val area = config.optJSONObject("supportedAreas")?.optJSONObject("0")
        val values = area?.optJSONArray("supportedValues")
        check(values != null && (0 until values.length()).any { values.optString(it) == "1" }) { "비상등 ON 값 미지원" }
        // Same service, type encoding and permission checks as Vehicle 2.3.2's Light setter.
        // Integer payload type=4; area type=0; global area=0. Always ON, never toggle.
        suspendCancellableCoroutine<Unit> { c ->
            onCommand()
            Log.i("SafetyStepHazard", "SET HAZARD_LIGHTS_SWITCH area=0 type=4 value=1")
            service.setPropertyValue(property, 0, 0, 4, "1", object : ISetPropertyResult.Stub() {
                override fun onResult(key: String?, returnedArea: Int, value: String?) {
                    if(c.isActive) {
                        if(key == property && returnedArea == 0) c.resume(Unit)
                        else c.resumeWithException(IllegalStateException("비상등 제어 응답 불일치"))
                    }
                }
                override fun onFailed(key: String?, code: Int, message: String?) {
                    if(c.isActive) c.resumeWithException(IllegalStateException("$code $message"))
                }
            })
        }
        repeat(6) {
            if (readHazard().current(System.currentTimeMillis()) == true) {
                Log.i("SafetyStepHazard", "READBACK HAZARD_LIGHTS_STATE=1")
                return@withTimeout "Pleos Vehicle Service · ON 요청 후 실제 켜짐 확인"
            }
            delay(300)
        }
        error("요청 후 비상등 켜짐을 확인하지 못함")
    }
    private suspend fun raw(property: String, area: Int): VehicleReading<String> = try {
        check(Build.HARDWARE in listOf("ranchu", "goldfish")) { "착좌 호환 경로는 에뮬레이터 전용" }
        if (!bound) {
            bound = context.bindService(Intent("ai.pleos.playground.vehicle.VEHICLE_START").setComponent(
                ComponentName("ai.pleos.playground.service.vehicle", "ai.pleos.playground.service.vehicle.VehicleService")), connection, Context.BIND_AUTO_CREATE)
            check(bound) { "Vehicle Service 연결 실패" }
        }
        val raw = withTimeout(3_000) {
            val service = ready.await()
            suspendCancellableCoroutine<String?> { continuation ->
                // Area type 3 is SEAT, 0 is GLOBAL; matches the installed SDK and capability.
                service.getPropertyValue(property, if (property == "SEAT_OCCUPANCY") 3 else 0, area, object : IGetPropertyResult.Stub() {
                    override fun onResult(key: String?, returnedArea: Int, value: String?) {
                        if (continuation.isActive) {
                            if (key != property || returnedArea != area) continuation.resumeWithException(IllegalStateException("차량 신호 응답 영역 불일치"))
                            else continuation.resume(value)
                        }
                    }
                    override fun onFailed(key: String?, code: Int, message: String?) {
                        if (continuation.isActive) continuation.resumeWithException(IllegalStateException("$code $message"))
                    }
                })
            }
        }
        VehicleReading(raw, System.currentTimeMillis(), "Pleos Vehicle Service · $property · raw=$raw")
    } catch (error: kotlinx.coroutines.TimeoutCancellationException) {
        VehicleReading(detail = "차량 신호 조회 시간 초과")
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (error: Exception) { VehicleReading(detail = "차량 신호 조회 실패: ${error.message}") }
    override fun close() { if (bound) runCatching { context.unbindService(connection) }; bound = false }
}
