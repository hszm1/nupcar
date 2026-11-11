package com.arc.nupcar

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.capability.AccessType
import ai.pleos.playground.vehicle.api.model.capability.AreaBounds
import ai.pleos.playground.vehicle.api.model.capability.CapabilityConfig
import ai.pleos.playground.vehicle.api.model.capability.HvacTemperatureConfigArray
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.control.MoveControl
import ai.pleos.playground.vehicle.constant.unit.TemperatureUnit
import ai.pleos.playground.vehicle.listener.SeatBackrestAnglePositionListener
import android.content.Context
import android.os.Build
import android.util.Log
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

private typealias ControlIntBounds = Pair<CapabilityConfig, Map<SeatArea, AreaBounds<Int>>>
private data class NupcarSdkReply<T>(val value: T)

private class NupcarSdkTestGateway(context: Context) : NupcarTestGateway {
    private val vehicle = Vehicle(context.applicationContext)
    private var initialized = false
    private var releaseSeatFeedback: (() -> Unit)? = null
    override val isEmulator: Boolean = Build.HARDWARE.lowercase(Locale.ROOT).let {
        it == "ranchu" || it == "goldfish"
    } || Build.PRODUCT.lowercase(Locale.ROOT).let {
        it.contains("sdk") || it.contains("emulator")
    } || Build.FINGERPRINT.startsWith("generic")

    fun initialize() {
        if (!initialized) { vehicle.initialize(); initialized = true }
    }

    override val environment: List<Pair<String, suspend () -> NupcarTestProperty<*>>> = listOf(
        "공조 설정 온도" to ::temperature,
        "공조 풍량" to ::fan,
        "앰비언트 밝기" to ::brightness,
        "앰비언트 색상" to ::color,
    )

    private suspend fun fan(): NupcarTestProperty<Int> {
        val hvac = vehicle.getHVAC()
        val (config, bounds) = sdk<ControlIntBounds> { ok, fail ->
            hvac.checkHvacFanSpeedCapabilities({ c, b -> ok(c to b) }, fail)
        }
        val (area, range) = writableArea(config, bounds)
        return NupcarTestProperty(
            label = "공조 풍량", area = areaText(area),
            read = { sdk<Int?> { ok, fail -> hvac.getHvacFanSpeed(area, ok, fail) } },
            write = { value -> sdk<Unit> { ok, fail -> hvac.setHvacFanSpeed(area, value, { _, _ -> ok(Unit) }, fail) } },
            target = { alternate(it, 1, range) },
        )
    }

    private suspend fun brightness(): NupcarTestProperty<Int> {
        val light = vehicle.getLight()
        val (config, bounds) = sdk<ControlIntBounds> { ok, fail ->
            light.checkAmbientLightBrightnessCapabilities({ c, b -> ok(c to b) }, fail)
        }
        val (area, range) = writableArea(config, bounds)
        return NupcarTestProperty(
            label = "앰비언트 밝기", area = areaText(area),
            read = { sdk<Int?> { ok, fail -> light.getAmbientLightBrightness(area, ok, fail) } },
            write = { value -> sdk<Unit> { ok, fail -> light.setAmbientLightBrightness(area, value, { _, _ -> ok(Unit) }, fail) } },
            target = { alternate(it, 20, range) },
        )
    }

    private suspend fun color(): NupcarTestProperty<Int> {
        val light = vehicle.getLight()
        val (config, areas) = sdk<Pair<CapabilityConfig, List<SeatArea>>> { ok, fail ->
            light.checkAmbientLightBrightnessColorCapabilities({ c, a -> ok(c to a) }, fail)
        }
        check(config.accessType == AccessType.READ_WRITE) { "조회·변경 모두 지원되지 않습니다: ${config.accessType}" }
        val area = driverArea(areas)
        val mint = 0xFF95F3D1.toInt()
        return NupcarTestProperty(
            label = "앰비언트 색상(RGB 기준)", area = areaText(area),
            read = { sdk<Int?> { ok, fail -> light.getAmbientLightBrightnessColor(area, ok, fail) } },
            write = { value -> sdk<Unit> { ok, fail -> light.setAmbientLightBrightnessColor(area, value, { _, _ -> ok(Unit) }, fail) } },
            target = { if ((it and 0xFFFFFF) == (mint and 0xFFFFFF)) 0xFFFFC070.toInt() else mint },
            same = { a, b -> (a and 0xFFFFFF) == (b and 0xFFFFFF) },
            restoreValue = { it or 0xFF000000.toInt() },
            format = { "#" + Integer.toHexString(it).uppercase(Locale.ROOT).padStart(8, '0') },
        )
    }

    private suspend fun temperature(): NupcarTestProperty<Float> {
        val hvac = vehicle.getHVAC()
        val (config, bounds, conversion) = sdk<Triple<CapabilityConfig, Map<SeatArea, AreaBounds<Float>>, HvacTemperatureConfigArray?>> { ok, fail ->
            hvac.checkHvacTemperatureCapabilities({ c, b, conversion -> ok(Triple(c, b, conversion)) }, fail)
        }
        val (area, range) = writableArea(config, bounds)
        val step = conversion?.incrementCelsius?.takeIf { it.isFinite() && it > 0f } ?: 0.5f
        return NupcarTestProperty(
            label = "공조 설정 온도", area = areaText(area),
            read = { sdk<Float?> { ok, fail -> hvac.getHvacTemperature(area, TemperatureUnit.CELSIUS, ok, fail) } },
            write = { value -> sdk<Unit> { ok, fail ->
                hvac.setHvacTemperature(area, value, TemperatureUnit.CELSIUS, { _, _ -> ok(Unit) }, fail)
            } },
            target = { value ->
                if (!value.isFinite() || value !in range.minValue..range.maxValue) null
                else if (value - step >= range.minValue) value - step
                else if (value + step <= range.maxValue) value + step else null
            },
            same = { a, b -> abs(a - b) < 0.01f },
            format = { "$it °C" },
        )
    }

    override suspend fun seat(): NupcarTestSeat {
        val seat = vehicle.getSeat()
        val (config, areas) = sdk<Pair<CapabilityConfig, List<SeatArea>>> { ok, fail ->
            seat.checkMoveSeatBackrestAngleCapabilities({ c, a -> ok(c to a) }, fail)
        }
        check(config.accessType == AccessType.WRITE || config.accessType == AccessType.READ_WRITE) { "시트 이동 쓰기 미지원" }
        val moveArea = driverArea(areas)
        val (positionConfig, bounds) = sdk<ControlIntBounds> { ok, fail ->
            seat.checkSeatBackrestAnglePositionCapabilities({ c, b -> ok(c to b) }, fail)
        }
        check(positionConfig.accessType == AccessType.READ || positionConfig.accessType == AccessType.READ_WRITE) { "시트 위치 조회 미지원" }
        val readArea = driverArea(bounds.keys.toList())
        check(moveArea.values.size == 1 && readArea.values.size == 1) { "시트 시험은 운전석 단독 영역만 사용합니다." }
        val range = bounds.getValue(readArea)
        check(range.minValue < range.maxValue) { "유효한 시트 위치 범위가 없습니다." }
        stopSeatFeedback()
        val feedback = AtomicReference(NupcarSeatFeedback())
        val listener = object : SeatBackrestAnglePositionListener {
            override fun onSeatBackrestAnglePositionUpdated(position: Int?) {
                feedback.updateAndGet { it.copy(count = it.count + 1, position = position,
                    recentPositions = (it.recentPositions + position).takeLast(16)) }
            }
            override fun onFailed(e: Exception) {
                feedback.updateAndGet { it.copy(failure = e.message ?: e.javaClass.simpleName) }
            }
        }
        releaseSeatFeedback = { seat.unregisterSeatBackrestAnglePosition(readArea, listener) }
        seat.registerSeatBackrestAnglePosition(readArea, listener)
        // Give the asynchronous registration time before any movement request.
        // Registration return alone is not treated as proof of notification delivery.
        delay(300L)
        return NupcarTestSeat(
            minimum = range.minValue, maximum = range.maxValue,
            read = { sdk<Int?> { ok, fail -> seat.getCurrentSeatBackrestAnglePosition(readArea, ok, fail) } },
            feedback = { feedback.get() },
            move = { direction ->
                val command = when (direction) {
                    NupcarSeatDirection.FORWARD -> MoveControl.FORWARD
                    NupcarSeatDirection.BACKWARD -> MoveControl.BACKWARD
                    NupcarSeatDirection.STOP -> MoveControl.STOP
                }
                sdk<Unit> { ok, fail -> seat.moveSeatBackrestAngle(moveArea, command, { _, _ -> ok(Unit) }, fail) }
            },
        )
    }

    private fun driverArea(areas: List<SeatArea>): SeatArea =
        areas.firstOrNull { it.values.size == 1 && SeatValue.ROW_1_LEFT in it.values }
            ?: areas.firstOrNull { SeatValue.ROW_1_LEFT in it.values }
            ?: error("운전석 영역이 지원되지 않습니다.")

    private fun <T : Number> writableArea(
        config: CapabilityConfig, bounds: Map<SeatArea, AreaBounds<T>>,
    ): Pair<SeatArea, AreaBounds<T>> {
        check(config.accessType == AccessType.READ_WRITE) { "조회·변경 모두 지원되지 않습니다: ${config.accessType}" }
        val area = driverArea(bounds.keys.toList())
        val range = bounds.getValue(area)
        check(range.minValue.toDouble().isFinite() && range.maxValue.toDouble().isFinite() &&
            range.minValue.toDouble() <= range.maxValue.toDouble()) { "유효한 지원 범위가 없습니다." }
        return area to range
    }

    private fun alternate(old: Int, delta: Int, range: AreaBounds<Int>): Int? = when {
        old !in range.minValue..range.maxValue || range.minValue == range.maxValue -> null
        old > range.minValue -> maxOf(range.minValue, old - delta)
        else -> minOf(range.maxValue, old + delta)
    }

    private fun areaText(area: SeatArea) = area.values.joinToString(", ") { it.name }

    private suspend fun <T> sdk(start: ((T) -> Unit, (Exception) -> Unit) -> Unit): T {
        val reply = withTimeoutOrNull(2_500L) {
            val value = suspendCancellableCoroutine<T> { continuation ->
                val delivered = AtomicBoolean(false)
                val fail: (Exception) -> Unit = { error ->
                    if (delivered.compareAndSet(false, true) && continuation.isActive) continuation.resumeWithException(error)
                }
                try {
                    start({ value ->
                        if (delivered.compareAndSet(false, true) && continuation.isActive) continuation.resume(value)
                    }, fail)
                } catch (e: Exception) { fail(e) }
            }
            NupcarSdkReply(value)
        } ?: error("Vehicle SDK 응답 시간 초과")
        return reply.value
    }

    fun stopSeatFeedback() {
        val release = releaseSeatFeedback
        releaseSeatFeedback = null
        release?.let { runCatching(it) }
    }

    fun close() {
        stopSeatFeedback()
        if (initialized) runCatching { vehicle.release() }
    }
}

internal class NupcarVehicleControlTest(
    context: Context,
    private val onReport: (String, Boolean, Boolean) -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val gateway = NupcarSdkTestGateway(context)
    private val lines = mutableListOf<String>()
    private val engine = NupcarVehicleTestEngine(gateway, ::emit)
    private var job: Job? = null
    private var busy = false
    private var closing = false

    fun testEnvironment() = runTask("공조·앰비언트 변경·복원") { engine.testEnvironment() }
    fun testSeat() = runTask("시트 0.5초 이동·복원") { engine.testSeat() }
    fun retryRestore() = runTask("복원 재시도", clear = false) { engine.recover() }

    private fun runTask(title: String, clear: Boolean = true, action: suspend () -> Unit) {
        if (closing || busy) return
        if (clear && !engine.hasPendingCleanup) lines.clear()
        busy = true
        emit("=== $title ===")
        job = scope.launch {
            try {
                gateway.initialize()
                action()
            } catch (e: CancellationException) {
                emit("시험 중지. 정지·복원 절차가 종료됐습니다.")
                throw e
            } catch (e: Exception) {
                emit("시험 오류: ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                if (!engine.hasPendingCleanup) gateway.stopSeatFeedback()
                busy = false
                emit(if (engine.hasPendingCleanup) "복원 미확인 항목이 남아 있습니다."
                    else "시험 종료. 추가 복원 요청 대기 없음. 실제 성공 여부는 위의 변경·복원 결과를 확인하세요.")
            }
        }
    }

    fun cancel() {
        if (busy) { emit("중지 요청. 시트 STOP 및 변경한 값의 복원을 시도합니다."); job?.cancel() }
    }

    private fun emit(line: String) {
        Log.i("NupcarVehicleControl", line)
        lines += line
        if (!closing) onReport(lines.joinToString("\n"), busy, engine.hasPendingCleanup)
    }

    fun close() {
        if (closing) return
        closing = true
        val running = job
        running?.cancel()
        scope.launch {
            try {
                running?.join()
                if (engine.hasPendingCleanup) engine.recover()
            } finally {
                gateway.close()
                scope.cancel()
            }
        }
    }
}
