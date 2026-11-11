package com.arc.nupcar.session

import kotlin.math.roundToInt

/** Stands in for a vehicle when developing without the Pleos Connect emulator, and in tests. */
class SimulatedVehicleGateway(
    private val now: () -> Long = System::currentTimeMillis,
    private val unitsPerSecond: Float = 8f
) : VehicleGateway {
    var parked: Boolean? = true
    var seatBehindOccupied: Boolean? = false
    var seatJammed = false
    var invertDirection = false
    var climateFails = false
    var temperatureNeedsPower = false
    val bounds = 0..100

    var climate = ClimateState(powerOn = false, temperatureC = 21f)
        private set
    var ambient = AmbientState(switchOn = false, brightness = 40)
        private set

    val moveLog = mutableListOf<SeatDirection>()
    private var position = 90.0f
    private var direction = SeatDirection.STOP
    private var movedAt = now()

    fun setPosition(value: Int) {
        position = value.toFloat()
    }

    val currentDirection: SeatDirection get() = direction

    override suspend fun isParked(): Boolean? = parked
    override suspend fun backrestBounds(): IntRange = bounds

    override suspend fun backrestPosition(): Int {
        advance()
        return position.roundToInt()
    }

    override suspend fun moveBackrest(direction: SeatDirection) {
        advance()
        moveLog += direction
        this.direction = direction
    }

    override suspend fun isSeatBehindOccupied(): Boolean? = seatBehindOccupied
    override suspend fun readClimate(): ClimateState =
        if (temperatureNeedsPower && climate.powerOn != true) climate.copy(temperatureC = null) else climate

    override suspend fun setClimatePower(on: Boolean) {
        if (climateFails) error("공조 제어 불가")
        climate = climate.copy(powerOn = on)
    }

    override suspend fun setTemperature(celsius: Float) {
        if (climateFails) error("공조 제어 불가")
        if (temperatureNeedsPower && climate.powerOn != true) error("공조가 꺼져 있습니다")
        climate = climate.copy(temperatureC = celsius)
    }

    override suspend fun ambientBounds(): IntRange = 0..100
    override suspend fun readAmbient(): AmbientState = ambient

    override suspend fun setAmbientSwitch(on: Boolean) {
        ambient = ambient.copy(switchOn = on)
    }

    override suspend fun setAmbientBrightness(value: Int) {
        ambient = ambient.copy(brightness = value)
    }

    private fun advance() {
        val current = now()
        val elapsedSeconds = (current - movedAt) / 1000f
        movedAt = current
        if (seatJammed) return
        val sign = when (direction) {
            SeatDirection.UPRIGHT -> 1
            SeatDirection.RECLINE -> -1
            SeatDirection.STOP -> 0
        } * if (invertDirection) -1 else 1
        position = (position + sign * unitsPerSecond * elapsedSeconds)
            .coerceIn(bounds.first.toFloat(), bounds.last.toFloat())
    }
}
