package io.github.aitm.pleosapp.vehicle

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.car.Car
import android.car.drivingstate.CarUxRestrictionsManager
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.constant.area.DoorValue
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.api.model.capability.AccessType
import ai.pleos.playground.vehicle.listener.SeatAirbagDeployedListener
import io.github.aitm.pleosapp.BuildConfig
import kotlinx.coroutines.delay
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.TimeoutCancellationException

object VehicleGatewayFactory {
    fun create(context: Context): VehicleGateway = PleosVehicleGateway(context.applicationContext)
}

private class PleosVehicleGateway(private val context: Context) : VehicleGateway {
    override val sourceLabel = "Pleos Vehicle SDK · 차량 상태 읽기"
    private val vehicle = Vehicle(context)
    private val occupancyReader = OccupancyReaderFactory.create(context)
    private val area = DoorArea(DoorValue.ROW_1_LEFT)
    private var car: Car? = null
    private var ux: CarUxRestrictionsManager? = null
    override fun watchDrivingRestrictions(onRestricted: (Boolean) -> Unit) {
        onRestricted(true)
        runCatching {
            car = Car.createCar(context, Handler(Looper.getMainLooper()), 0L) { service, ready ->
                if (!ready) { ux = null; onRestricted(true) }
                else runCatching {
                    val manager = service.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as CarUxRestrictionsManager
                    ux = manager
                    manager.registerListener { value -> onRestricted(value.isRequiresDistractionOptimization || value.activeRestrictions != 0) }
                    val value = manager.currentCarUxRestrictions
                    onRestricted(value.isRequiresDistractionOptimization || value.activeRestrictions != 0)
                }.onFailure { onRestricted(true) }
            }
        }.onFailure { onRestricted(true) }
    }
    private var initialized = false
    private val driverSeat = SeatArea(SeatValue.ROW_1_LEFT)
    private val rear = DoorArea(DoorValue.REAR)
    private var airbagListener: SeatAirbagDeployedListener? = null

    private fun ensureInitialized() {
        if (!initialized) {
            vehicle.initialize()
            initialized = true
        }
    }

    private suspend fun <T> read(block: (onSuccess: (T) -> Unit, onFailure: (Exception) -> Unit) -> Unit): T =
        withTimeout(8_000) {
            ensureInitialized()
            suspendCancellableCoroutine { continuation ->
                block(
                    { if (continuation.isActive) continuation.resume(it) },
                    { if (continuation.isActive) continuation.resumeWithException(it) },
                )
            }
        }

    override suspend fun readGear(): String = read<VehicleGear?> { ok, fail ->
        vehicle.getGear().getCurrentGearSelectionState(ok, fail)
    }?.name ?: "기어 미확인"

    override suspend fun readTrunk(): String = "트렁크 원시 값: ${trunkPosition()}"

    private suspend fun trunkPosition(): Int? = read { ok, fail ->
        vehicle.getDoor().getCurrentDoorPosition(rear, ok, fail)
    }

    private suspend fun <T> observe(block: suspend () -> T?): VehicleReading<T> = try {
        val value = block()
        VehicleReading(value, System.currentTimeMillis(), if (value == null) "SDK 값 미확인" else "Pleos SDK 조회")
    } catch (_: TimeoutCancellationException) { VehicleReading(detail = "SDK 조회 시간 초과") }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (error: Exception) { VehicleReading(detail = "SDK 미지원/오류: ${error.message?.take(160)}") }

    private suspend fun doorOpen(doorArea: DoorArea): Boolean? {
        val bounds = read<Pair<Int, Int>> { ok, fail ->
            vehicle.getDoor().checkDoorPositionCapabilities(onComplete = { config, areas ->
                val b = areas[doorArea]
                if (b != null && config.accessType in listOf(AccessType.READ, AccessType.READ_WRITE)) ok(b.minValue to b.maxValue)
                else fail(IllegalStateException("문 상태 읽기 미지원"))
            }, onFailed = fail)
        }
        val value = read<Int?> { ok, fail -> vehicle.getDoor().getCurrentDoorPosition(doorArea, ok, fail) }
        return when (value) { bounds.first -> false; bounds.second -> true; else -> null }
    }

    private suspend fun seatSignal(occupancy: Boolean): Boolean? {
        val supported = read<Boolean> { ok, fail ->
            val complete: (ai.pleos.playground.vehicle.api.model.capability.CapabilityConfig, List<SeatArea>) -> Unit = { config, areas ->
                ok(driverSeat in areas && config.accessType in listOf(AccessType.READ, AccessType.READ_WRITE))
            }
            if (occupancy) vehicle.getSeat().checkSeatOccupancyCapabilities(complete, fail)
            else vehicle.getSeat().checkSeatBeltBuckledCapabilities(complete, fail)
        }
        check(supported) { "운전석 ${if (occupancy) "착좌" else "벨트"} 읽기 미지원" }
        return read { ok, fail ->
            if (occupancy) vehicle.getSeat().getCurrentSeatOccupancy(driverSeat, ok, fail)
            else vehicle.getSeat().getCurrentSeatBeltBuckledState(driverSeat, ok, fail)
        }
    }

    override suspend fun readSnapshot(): VehicleSnapshot = coroutineScope {
        ensureInitialized()
        val gear = async { observe { readGear().takeUnless { it == "기어 미확인" } } }
        val door = async { observe { doorOpen(area) } }
        val trunk = async { observe { doorOpen(rear) } }
        val seats = async {
            val areas = try { read<List<SeatArea>> { ok, fail ->
                vehicle.getSeat().checkSeatOccupancyCapabilities({ config, supported ->
                    if (config.accessType in listOf(AccessType.READ, AccessType.READ_WRITE)) ok(supported)
                    else fail(IllegalStateException("착좌 읽기 미지원"))
                }, fail)
            } } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { emptyList() }
            areas.flatMap { it.values.toList() }.distinct().map { value ->
                val label = when (value) {
                    SeatValue.ROW_1_LEFT -> "운전석"; SeatValue.ROW_1_RIGHT -> "동승석"
                    SeatValue.ROW_2_LEFT -> "뒷좌석 왼쪽"; SeatValue.ROW_2_RIGHT -> "뒷좌석 오른쪽"
                    SeatValue.ROW_2_CENTER -> "뒷좌석 가운데"; else -> value.name
                }
                async { SeatObservation(value.id, label, occupancyReader.read(value.id)) }
            }.map { it.await() }
        }
        val belt = async { observe { seatSignal(false) } }
        val brake = async { observe { read<Boolean?> { ok, fail -> vehicle.getBrake().getCurrentBrakePedalStatus(ok, fail) } } }
        val speed = async { occupancyReader.readSpeed() }
        val hazard = async { occupancyReader.readHazard() }
        val observations = seats.await()
        VehicleSnapshot(gear.await(), door.await(), trunk.await(),
            observations.firstOrNull { it.area == SeatValue.ROW_1_LEFT.id }?.occupied ?: VehicleReading(detail = "운전석 착좌 읽기 미지원"),
            belt.await(), brake.await(), seats = observations, speedMps = speed.await(), hazardLights = hazard.await())
    }

    override suspend fun watchAirbag(onValue: (Boolean?) -> Unit, onError: (String) -> Unit) {
        ensureInitialized()
        if (airbagListener != null) return
        val supported = read<Boolean> { ok, fail ->
            vehicle.getSafety().checkAirbagDeployedCapabilities(
                onComplete = { config, areas ->
                    ok(driverSeat in areas && config.accessType in listOf(AccessType.READ, AccessType.READ_WRITE))
                }, onFailed = fail,
            )
        }
        check(supported) { "에어백 전개 capability 미지원 · 경고등과 전개 신호는 다릅니다." }
        val initial = read<Boolean?> { ok, fail ->
            vehicle.getSafety().hasSeatAirbagBeenDeployed(driverSeat, ok, fail)
        }
        onValue(initial)
        val listener = object : SeatAirbagDeployedListener {
            override fun onSeatAirbagDeployUpdated(value: Boolean?) = onValue(value)
            override fun onFailed(error: Exception) = onError(error.message ?: error.javaClass.simpleName)
        }
        vehicle.getSafety().registerSeatAirbagDeployed(driverSeat, listener)
        airbagListener = listener
    }

    override suspend fun enableHazardLights(onCommand: () -> Unit): String = occupancyReader.enableHazardLights(onCommand)

    override suspend fun openTrunk(): String = withTimeout(8_000) {
        // This prototype must never actuate a physical vehicle.
        check(BuildConfig.DEBUG && Build.HARDWARE in listOf("ranchu", "goldfish")) {
            "차량 제어는 debug 에뮬레이터에서만 허용됩니다."
        }
        check(readGear() == VehicleGear.GEAR_PARK.name) { "트렁크 제어 중단: P 기어 확인 필요" }
        val bounds = read<Pair<Int, Int>> { ok, fail ->
            vehicle.getDoor().checkDoorPositionCapabilities(
                onComplete = { config, bounds ->
                    val bound = bounds[rear]
                    if (bound != null && bound.minValue < bound.maxValue && config.accessType == AccessType.READ_WRITE) {
                        ok(bound.minValue to bound.maxValue)
                    } else fail(IllegalStateException("트렁크 쓰기 capability 미지원"))
                }, onFailed = fail,
            )
        }
        val maximum = bounds.second
        val initial = trunkPosition()
        if (initial == maximum) return@withTimeout "SDK 트렁크 이미 열림 확인 · 중복 명령 없음"
        check(initial == bounds.first) { "트렁크 닫힘 미확인 또는 이동 중 · 제어 보류" }
        check(readGear() == VehicleGear.GEAR_PARK.name) { "제어 직전 기어 변경 · 중단" }
        read<Int?> { ok, fail ->
            vehicle.getDoor().setDoorPosition(rear, maximum, { _, value -> ok(value) }, fail)
        }
        repeat(5) {
            if (trunkPosition() == maximum) return@withTimeout "SDK 트렁크 열림 확인 · GET=$maximum"
            delay(250)
        }
        error("제어 요청 후 실제 트렁크 열림을 확인하지 못했습니다.")
    }

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
        occupancyReader.close()
        runCatching { ux?.unregisterListener(); car?.disconnect() }
        if (initialized) {
            airbagListener?.let { vehicle.getSafety().unregisterSeatAirbagDeployed(driverSeat, it) }
            airbagListener = null
            vehicle.release()
            initialized = false
        }
    }
}
