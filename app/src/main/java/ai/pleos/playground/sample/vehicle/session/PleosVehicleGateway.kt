package ai.pleos.playground.sample.vehicle.session

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.capability.AccessType
import ai.pleos.playground.vehicle.api.model.capability.AreaBounds
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.control.LightSwitch
import ai.pleos.playground.vehicle.constant.control.MoveControl
import ai.pleos.playground.vehicle.constant.control.VehicleGear as PleosGear
import ai.pleos.playground.vehicle.constant.unit.TemperatureUnit
import android.annotation.SuppressLint
import android.car.Car
import android.car.VehicleGear
import android.car.VehiclePropertyIds
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Connects a rest session to the Pleos Vehicle SDK.
 *
 * Vehicles may report grouped seat areas, so each feature looks up the area that contains
 * the driver's seat from its own capability response instead of assuming one.
 */
@SuppressLint("MissingPermission")
class PleosVehicleGateway(context: Context) : VehicleGateway {
    private val appContext = context.applicationContext
    private val vehicle = Vehicle(appContext).also { it.initialize() }
    private val seat = vehicle.getSeat()
    private val hvac = vehicle.getHVAC()
    private val light = vehicle.getLight()
    private val car: Car? = runCatching { Car.createCar(appContext) }.getOrNull()
    private val properties: CarPropertyManager? = runCatching {
        car?.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager
    }.getOrNull()

    private var moveArea: SeatArea? = null
    private var positionArea: SeatArea? = null
    private var climatePowerArea: SeatArea? = null
    private var temperatureArea: SeatArea? = null
    private var temperatureRange: AreaBounds<Float>? = null
    private var ambientArea: SeatArea? = null
    private var ambientSwitchArea: SeatArea? = null

    override suspend fun isParked(): Boolean? = androidGearIsPark() ?: pleosGearIsPark()

    private fun androidGearIsPark(): Boolean? = runCatching {
        properties?.getIntProperty(VehiclePropertyIds.GEAR_SELECTION, GLOBAL_AREA)
            ?.let { it == VehicleGear.GEAR_PARK }
    }.getOrNull()

    /** Fallback while the Android property's availability on Pleos Connect is unconfirmed. */
    @Suppress("DEPRECATION")
    private suspend fun pleosGearIsPark(): Boolean? = runCatching {
        call<PleosGear?> { done, failed ->
            vehicle.getGear().getCurrentGearSelectionState(done, failed)
        }?.let { it == PleosGear.GEAR_PARK }
    }.getOrNull()

    override suspend fun backrestBounds(): IntRange? {
        val area = call<SeatArea?> { done, failed ->
            seat.checkMoveSeatBackrestAngleCapabilities(
                onComplete = { config, areas -> done(driverArea(areas).takeIf { config.accessType.canWrite() }) },
                onFailed = failed
            )
        } ?: return null
        moveArea = area
        return call { done, failed ->
            seat.checkSeatBackrestAnglePositionCapabilities(
                onComplete = { config, bounds ->
                    val found = driverArea(bounds.keys).takeIf { config.accessType.canRead() }
                    positionArea = found
                    done(found?.let { key -> bounds.getValue(key).let { it.minValue..it.maxValue } })
                },
                onFailed = failed
            )
        }
    }

    override suspend fun backrestPosition(): Int? {
        val area = positionArea ?: error("시트 위치 영역을 확인하지 못했습니다")
        return call { done, failed -> seat.getCurrentSeatBackrestAnglePosition(area, done, failed) }
    }

    /**
     * A late acknowledgement must not delay a stop, so a move only waits briefly. If the seat
     * then fails to move, the position checks in [SeatMover] detect it. STOP waits for the reply.
     */
    override suspend fun moveBackrest(direction: SeatDirection) {
        val area = moveArea ?: error("시트 이동 영역을 확인하지 못했습니다")
        val control = when (direction) {
            SeatDirection.RECLINE -> MoveControl.BACKWARD
            SeatDirection.UPRIGHT -> MoveControl.FORWARD
            SeatDirection.STOP -> MoveControl.STOP
        }
        val waitMillis = if (direction == SeatDirection.STOP) CALL_TIMEOUT_MILLIS else MOVE_ACK_WAIT_MILLIS
        val result = withTimeoutOrNull(waitMillis) {
            suspendCancellableCoroutine<Unit> { continuation ->
                seat.moveSeatBackrestAngle(
                    area,
                    control,
                    { _, _ -> if (continuation.isActive) continuation.resume(Unit) },
                    { error -> if (continuation.isActive) continuation.resumeWithException(error) }
                )
            }
        }
        if (result == null && direction == SeatDirection.STOP) error("시트 정지 응답을 받지 못했습니다")
    }

    /**
     * The SDK's Boolean is true only for raw value 1, which Android defines as VACANT, and the
     * emulator confirms it: Empty reads true and Occupied reads false. Anything but vacant,
     * including an unknown state, therefore counts as occupied. Null means no rear sensor.
     */
    override suspend fun isSeatBehindOccupied(): Boolean? {
        val area = call<SeatArea?> { done, failed ->
            seat.checkSeatOccupancyCapabilities(
                onComplete = { config, areas ->
                    done(areas.firstOrNull { SeatValue.ROW_2_LEFT in it.values }.takeIf { config.accessType.canRead() })
                },
                onFailed = failed
            )
        } ?: return null
        val vacant = call<Boolean?> { done, failed -> seat.getCurrentSeatOccupancy(area, done, failed) }
        return vacant != true
    }

    /** The vehicle reports no target temperature while climate is off, so each value is optional. */
    override suspend fun readClimate(): ClimateState {
        val power = orNull {
            val area = resolveClimatePowerArea()
            call<Boolean?> { done, failed -> hvac.getHvacPowerState(area, done, failed) }
        }
        val temperature = orNull {
            val area = resolveTemperatureArea()
            call<Float?> { done, failed -> hvac.getHvacTemperature(area, TemperatureUnit.CELSIUS, done, failed) }
        }
        return ClimateState(power, temperature)
    }

    override suspend fun setClimatePower(on: Boolean) {
        val area = resolveClimatePowerArea()
        call<Unit> { done, failed -> hvac.setHvacPower(area, on, { _, _ -> done(Unit) }, failed) }
    }

    /** Retries because the temperature stays unavailable briefly after climate is turned on. */
    override suspend fun setTemperature(celsius: Float) {
        val area = resolveTemperatureArea()
        val value = temperatureRange?.let { celsius.coerceIn(it.minValue, it.maxValue) } ?: celsius
        var lastFailure: Exception? = null
        repeat(TEMPERATURE_ATTEMPTS) { attempt ->
            try {
                call<Unit> { done, failed ->
                    hvac.setHvacTemperature(area, value, TemperatureUnit.CELSIUS, { _, _ -> done(Unit) }, failed)
                }
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastFailure = e
                if (attempt < TEMPERATURE_ATTEMPTS - 1) delay(TEMPERATURE_RETRY_MILLIS)
            }
        }
        throw lastFailure ?: IllegalStateException("온도를 설정하지 못했습니다")
    }

    override suspend fun ambientBounds(): IntRange? = call { done, failed ->
        light.checkAmbientLightBrightnessCapabilities(
            onComplete = { config, bounds ->
                val found = driverArea(bounds.keys).takeIf { config.accessType == AccessType.READ_WRITE }
                ambientArea = found
                done(found?.let { key -> bounds.getValue(key).let { it.minValue..it.maxValue } })
            },
            onFailed = failed
        )
    }

    override suspend fun readAmbient(): AmbientState {
        val switch = orNull {
            val area = resolveAmbientSwitchArea() ?: return@orNull null
            call<LightSwitch?> { done, failed -> light.getAmbientLightSwitch(area, done, failed) }
        }
        val brightness = orNull {
            val area = resolveAmbientArea() ?: return@orNull null
            call<Int?> { done, failed -> light.getAmbientLightBrightness(area, done, failed) }
        }
        return AmbientState(switch?.let { it == LightSwitch.ON }, brightness)
    }

    override suspend fun setAmbientSwitch(on: Boolean) {
        val area = resolveAmbientSwitchArea() ?: return
        val value = if (on) LightSwitch.ON else LightSwitch.OFF
        call<Unit> { done, failed -> light.setAmbientLightSwitch(area, value, { _, _ -> done(Unit) }, failed) }
    }

    override suspend fun setAmbientBrightness(value: Int) {
        val area = resolveAmbientArea() ?: return
        call<Unit> { done, failed -> light.setAmbientLightBrightness(area, value, { _, _ -> done(Unit) }, failed) }
    }

    private suspend fun resolveAmbientArea(): SeatArea? {
        if (ambientArea == null) ambientBounds()
        return ambientArea
    }

    private suspend fun resolveAmbientSwitchArea(): SeatArea? {
        ambientSwitchArea?.let { return it }
        return call<SeatArea?> { done, failed ->
            light.checkAmbientLightSwitchCapabilities(
                onComplete = { _, areas -> done(driverArea(areas.keys)) },
                onFailed = failed
            )
        }.also { ambientSwitchArea = it }
    }

    private suspend fun resolveClimatePowerArea(): SeatArea {
        climatePowerArea?.let { return it }
        val area = call<SeatArea?> { done, failed ->
            hvac.checkHvacPowerCapabilities(
                onComplete = { _, areas -> done(driverArea(areas)) },
                onFailed = failed
            )
        } ?: error("운전석 공조 전원이 지원되지 않습니다")
        climatePowerArea = area
        return area
    }

    private suspend fun resolveTemperatureArea(): SeatArea {
        temperatureArea?.let { return it }
        return call { done, failed ->
            hvac.checkHvacTemperatureCapabilities(
                onComplete = { _, bounds, _ ->
                    val area = driverArea(bounds.keys)
                    if (area == null) {
                        failed(IllegalStateException("운전석 공조 온도가 지원되지 않습니다"))
                    } else {
                        temperatureArea = area
                        temperatureRange = bounds[area]
                        done(area)
                    }
                },
                onFailed = failed
            )
        }
    }

    /** Lets one unavailable value leave the others usable, without hiding a cancelled session. */
    private suspend fun <T> orNull(block: suspend () -> T?): T? = try {
        block()
    } catch (e: TimeoutCancellationException) {
        null
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    private fun driverArea(areas: Collection<SeatArea>): SeatArea? =
        areas.firstOrNull { it.values.size == 1 && SeatValue.ROW_1_LEFT in it.values }
            ?: areas.firstOrNull { SeatValue.ROW_1_LEFT in it.values }

    private fun AccessType.canWrite() = this == AccessType.WRITE || this == AccessType.READ_WRITE
    private fun AccessType.canRead() = this == AccessType.READ || this == AccessType.READ_WRITE

    /** Turns an SDK callback pair into a suspending call that cannot hang a session. */
    private suspend fun <T> call(
        block: (done: (T) -> Unit, failed: (Exception) -> Unit) -> Unit
    ): T = withTimeout(CALL_TIMEOUT_MILLIS) {
        suspendCancellableCoroutine { continuation ->
            block(
                { value -> if (continuation.isActive) continuation.resume(value) },
                { error -> if (continuation.isActive) continuation.resumeWithException(error) }
            )
        }
    }

    private companion object {
        const val CALL_TIMEOUT_MILLIS = 2_500L
        const val MOVE_ACK_WAIT_MILLIS = 300L
        const val GLOBAL_AREA = 0
        const val TEMPERATURE_ATTEMPTS = 4
        const val TEMPERATURE_RETRY_MILLIS = 300L
    }
}
