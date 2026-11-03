package ai.pleos.playground.sample.vehicle.session

enum class SeatDirection { RECLINE, UPRIGHT, STOP }

data class ClimateState(val powerOn: Boolean?, val temperatureC: Float?)

data class AmbientState(val switchOn: Boolean?, val brightness: Int?)

/** Vehicle access used by a rest session. Calls throw when the vehicle rejects them. */
interface VehicleGateway {
    /** Null means the state could not be read and must be treated as not parked. */
    suspend fun isParked(): Boolean?

    /** Null means this vehicle offers no controllable backrest. */
    suspend fun backrestBounds(): IntRange?
    suspend fun backrestPosition(): Int?
    suspend fun moveBackrest(direction: SeatDirection)

    /** Null means occupancy could not be read. */
    suspend fun isSeatBehindOccupied(): Boolean?

    suspend fun readClimate(): ClimateState?
    suspend fun setClimatePower(on: Boolean)
    suspend fun setTemperature(celsius: Float)

    suspend fun ambientBounds(): IntRange?
    suspend fun readAmbient(): AmbientState?
    suspend fun setAmbientSwitch(on: Boolean)
    suspend fun setAmbientBrightness(value: Int)
}

interface RestAudio {
    fun playRest(sound: RestSound)
    fun playWake()
    fun stop()
}
