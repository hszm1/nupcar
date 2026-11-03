package ai.pleos.playground.sample.vehicle.session

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class WakeStage(val title: String) {
    SOUND("기상 사운드"),
    LIGHT("조명 전환"),
    SEAT("시트 올리는 중"),
    RESTORE("차량 환경 복원")
}

sealed interface RestState {
    data object Idle : RestState
    data class Preparing(val profile: RestProfile) : RestState
    data class Resting(val profile: RestProfile, val remainingMillis: Long) : RestState
    data class Waking(val profile: RestProfile, val stage: WakeStage) : RestState
    data class RestoringSeat(val message: String) : RestState
    data class Finished(val warnings: List<String>) : RestState

    /** Ended early. The seat stays where it stopped until the user chooses to restore it. */
    data class Interrupted(
        val reason: String,
        val canRestoreSeat: Boolean,
        val warnings: List<String>
    ) : RestState

    data class Rejected(val reason: String) : RestState
}

data class RestTimings(
    val wakeSoundMillis: Long = 15_000,
    val lightRampMillis: Long = 15_000,
    val lightRampSteps: Int = 10,
    val parkedPollMillis: Long = 1_000,
    val tickMillis: Long = 1_000,
    val unknownParkLimit: Int = 3,
    val seat: SeatMoveTimings = SeatMoveTimings()
)

private class NotParkedException : Exception()

/** Runs one rest session: prepare, rest, wake in four stages, restore. */
class RestSessionController(
    vehicleProvider: () -> VehicleGateway,
    private val audio: RestAudio,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
    private val timings: RestTimings = RestTimings()
) {
    private val _state = MutableStateFlow<RestState>(RestState.Idle)
    val state: StateFlow<RestState> = _state.asStateFlow()

    /** Connects on the first session, so opening the app never touches the vehicle. */
    private val vehicle by lazy(vehicleProvider)
    private val seatMover by lazy { SeatMover(vehicle, now, timings.seat) }
    private var sessionJob: Job? = null
    private var wakeSignal = CompletableDeferred<Unit>()
    private var snapshot: Snapshot? = null
    private val warnings = mutableListOf<String>()

    private data class Snapshot(
        val backrest: Int,
        val climate: ClimateState?,
        val ambient: AmbientState?
    )

    val isActive: Boolean
        get() = when (_state.value) {
            is RestState.Preparing, is RestState.Resting,
            is RestState.Waking, is RestState.RestoringSeat -> true
            else -> false
        }

    fun start(profile: RestProfile) {
        if (isActive) return
        warnings.clear()
        snapshot = null
        wakeSignal = CompletableDeferred()
        _state.value = RestState.Preparing(profile)
        sessionJob = scope.launch {
            try {
                runSession(profile)
            } catch (e: CancellationException) {
                throw e
            } catch (e: LinkageError) {
                _state.value = RestState.Rejected("이 기기에서는 차량 기능을 사용할 수 없습니다.")
            } catch (e: NotParkedException) {
                interrupt("주차 상태가 아니어서 휴식을 중단했습니다. 시트는 멈춘 위치에 있으니 안전하게 정차한 뒤 확인해 주세요.")
            } catch (e: SeatMoveException) {
                interrupt(e.message ?: "시트를 움직일 수 없습니다")
            } catch (e: Exception) {
                interrupt("차량 제어에 실패했습니다: ${e.message ?: "알 수 없는 오류"}")
            }
        }
    }

    /** Skips the remaining rest time and starts the wake-up sequence. */
    fun wakeNow() {
        wakeSignal.complete(Unit)
    }

    /** Halts the seat at once and ends the session without moving anything further. */
    fun emergencyStop() {
        if (!isActive) return
        val running = sessionJob
        scope.launch {
            running?.cancelAndJoin()
            interrupt("사용자가 중단했습니다. 시트는 멈춘 위치에 있습니다.")
        }
    }

    /** Returns the seat to its saved position after an interruption, only while parked. */
    fun restoreSeat() {
        val saved = snapshot ?: return
        if (isActive) return
        _state.value = RestState.RestoringSeat("시트를 휴식 전 위치로 되돌리는 중입니다")
        sessionJob = scope.launch {
            try {
                seatMover.moveTo(saved.backrest, WakeSpeed.NORMAL, ::requireParked)
                snapshot = null
                _state.value = RestState.Finished(warnings.toList())
            } catch (e: CancellationException) {
                throw e
            } catch (e: NotParkedException) {
                _state.value = RestState.Interrupted("주차 상태에서만 시트를 복원할 수 있습니다.", true, warnings.toList())
            } catch (e: Exception) {
                _state.value = RestState.Interrupted(e.message ?: "시트를 복원하지 못했습니다", true, warnings.toList())
            }
        }
    }

    fun reset() {
        if (!isActive) _state.value = RestState.Idle
    }

    private suspend fun runSession(profile: RestProfile) {
        rejectionReason()?.let {
            _state.value = RestState.Rejected(it)
            return
        }
        val bounds = vehicle.backrestBounds()
        if (bounds == null) {
            _state.value = RestState.Rejected("이 차량에서는 등받이를 제어할 수 없습니다.")
            return
        }
        val backrest = vehicle.backrestPosition()
        if (backrest == null) {
            _state.value = RestState.Rejected("현재 시트 위치를 읽을 수 없어 시작하지 않았습니다.")
            return
        }
        val saved = Snapshot(backrest, optional("공조 상태 확인") { vehicle.readClimate() },
            optional("조명 상태 확인") { vehicle.readAmbient() })
        snapshot = saved

        coroutineScope {
            val watchdog = launch { watchParkedState() }
            seatMover.moveTo(reclineTarget(profile, bounds, backrest), WakeSpeed.NORMAL, ::requireParked)
            val ambientBounds = optional("조명 범위 확인") { vehicle.ambientBounds() }
            applyRestEnvironment(profile, ambientBounds)
            audio.playRest(profile.sound)

            rest(profile)
            wake(profile, saved, ambientBounds)
            watchdog.cancel()
        }

        snapshot = null
        _state.value = RestState.Finished(warnings.toList())
    }

    private suspend fun rejectionReason(): String? {
        if (vehicle.isParked() != true) {
            return "주차 상태(P)를 확인할 수 없어 시작하지 않았습니다. 기어를 P에 두고 다시 시도해 주세요."
        }
        return when (optional("뒷좌석 확인") { vehicle.isSeatBehindOccupied() }) {
            true -> "뒷좌석에 사람이 있거나 비어 있는지 확인할 수 없어 시트를 눕히지 않았습니다."
            null -> {
                warnings += "뒷좌석 탑승 여부를 확인하지 못했습니다. 시트 뒤 공간을 직접 확인해 주세요."
                null
            }
            false -> null
        }
    }

    /** Assumes lower positions are more reclined, as Android Automotive defines this property. */
    private fun reclineTarget(profile: RestProfile, bounds: IntRange, current: Int): Int {
        val span = bounds.last - bounds.first
        val allowed = profile.reclineRatio.coerceIn(0f, 1f) * MAX_RECLINE_SHARE
        val target = bounds.last - (span * allowed).roundToInt()
        return min(current, target).coerceIn(bounds.first, bounds.last)
    }

    private suspend fun applyRestEnvironment(profile: RestProfile, ambientBounds: IntRange?) {
        optional("공조 설정") {
            vehicle.setClimatePower(true)
            vehicle.setTemperature(profile.temperatureC)
        }
        if (ambientBounds != null) {
            optional("조명 설정") {
                vehicle.setAmbientSwitch(true)
                vehicle.setAmbientBrightness(scale(ambientBounds, profile.ambientRatio))
            }
        }
    }

    private suspend fun rest(profile: RestProfile) {
        val endsAt = now() + profile.durationMinutes * 60_000L
        while (true) {
            val remaining = endsAt - now()
            if (remaining <= 0) return
            _state.value = RestState.Resting(profile, remaining)
            val woken = withTimeoutOrNull(min(remaining, timings.tickMillis)) { wakeSignal.await() }
            if (woken != null) return
        }
    }

    private suspend fun wake(profile: RestProfile, saved: Snapshot, ambientBounds: IntRange?) {
        _state.value = RestState.Waking(profile, WakeStage.SOUND)
        audio.stop()
        if (profile.wakeSoundEnabled) audio.playWake()
        delay(timings.wakeSoundMillis)

        _state.value = RestState.Waking(profile, WakeStage.LIGHT)
        rampLight(saved, ambientBounds)

        _state.value = RestState.Waking(profile, WakeStage.SEAT)
        seatMover.moveTo(saved.backrest, profile.wakeSpeed, ::requireParked)

        _state.value = RestState.Waking(profile, WakeStage.RESTORE)
        audio.stop()
        restoreEnvironment(saved)
    }

    private suspend fun rampLight(saved: Snapshot, bounds: IntRange?) {
        if (bounds == null) {
            delay(timings.lightRampMillis)
            return
        }
        val from = optional("조명 상태 확인") { vehicle.readAmbient()?.brightness } ?: bounds.first
        val end = max(saved.ambient?.brightness ?: bounds.first, scale(bounds, WAKE_LIGHT_RATIO))
        val steps = timings.lightRampSteps.coerceAtLeast(1)
        repeat(steps) { step ->
            optional("조명 전환") {
                vehicle.setAmbientBrightness(from + (end - from) * (step + 1) / steps)
            }
            delay(timings.lightRampMillis / steps)
        }
    }

    private suspend fun restoreEnvironment(saved: Snapshot) {
        saved.climate?.let { climate ->
            optional("공조 복원") {
                climate.temperatureC?.let { vehicle.setTemperature(it) }
                climate.powerOn?.let { vehicle.setClimatePower(it) }
            }
        }
        saved.ambient?.let { ambient ->
            optional("조명 복원") {
                ambient.brightness?.let { vehicle.setAmbientBrightness(it) }
                ambient.switchOn?.let { vehicle.setAmbientSwitch(it) }
            }
        }
    }

    private suspend fun interrupt(reason: String) {
        withContext(NonCancellable) {
            audio.stop()
            snapshot?.let { restoreEnvironment(it) }
        }
        _state.value = RestState.Interrupted(reason, snapshot != null, warnings.toList())
    }

    /** Fails the session, which stops any seat motion, as soon as the vehicle may not be parked. */
    private suspend fun watchParkedState() {
        var unknownCount = 0
        while (true) {
            delay(timings.parkedPollMillis)
            val parked = runCatching { vehicle.isParked() }.getOrNull()
            unknownCount = if (parked == null) unknownCount + 1 else 0
            if (parked == false || unknownCount >= timings.unknownParkLimit) throw NotParkedException()
        }
    }

    private suspend fun requireParked() {
        if (runCatching { vehicle.isParked() }.getOrNull() != true) throw NotParkedException()
    }

    private suspend fun <T> optional(step: String, block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        warnings += "$step 실패: ${e.message ?: "지원되지 않는 기능일 수 있습니다"}"
        null
    }

    private fun scale(bounds: IntRange, ratio: Float): Int =
        bounds.first + ((bounds.last - bounds.first) * ratio.coerceIn(0f, 1f)).roundToInt()

    private companion object {
        /** Never asks for the full mechanical range, which could reach whatever is behind the seat. */
        const val MAX_RECLINE_SHARE = 0.7f
        const val WAKE_LIGHT_RATIO = 0.7f
    }
}
