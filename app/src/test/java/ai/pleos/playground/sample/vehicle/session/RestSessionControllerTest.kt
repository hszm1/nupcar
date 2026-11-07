package ai.pleos.playground.sample.vehicle.session

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RestSessionControllerTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val vehicle = SimulatedVehicleGateway(unitsPerSecond = 200f)
    private val audio = RecordingAudio()
    private val timings = RestTimings(
        wakeSoundMillis = 30,
        lightRampMillis = 30,
        lightRampSteps = 3,
        parkedPollMillis = 20,
        tickMillis = 20,
        visualSeatSpeed = 20f,
        visualTickMillis = 5,
        seat = SeatMoveTimings(pollMillis = 5, stallMillis = 150, maxMoveMillis = 3_000)
    )
    private val controller = RestSessionController({ vehicle }, audio, scope, timings = timings)
    private val profile = RestProfile.default(NupcarMode.NAP).copy(durationMinutes = 1)

    @After
    fun tearDown() = scope.cancel()

    private suspend fun awaitResting() = withTimeout(5_000) {
        controller.state.first { it is RestState.Resting }
    }

    private suspend inline fun <reified T : RestState> awaitState(): T = withTimeout(8_000) {
        controller.state.first { it is T } as T
    }

    @Test
    fun fullSessionRestoresSeatAndEnvironment() = runBlocking<Unit> {
        val before = vehicle.backrestPosition()
        val climateBefore = vehicle.climate
        val ambientBefore = vehicle.ambient

        controller.start(profile)
        awaitResting()
        assertTrue("seat should be reclined", vehicle.backrestPosition() < before - 20)
        assertEquals(profile.temperatureC, vehicle.climate.temperatureC)
        assertEquals(RestSound.WHITE_NOISE, audio.restSound)

        controller.wakeNow()
        val finished = awaitState<RestState.Finished>()

        assertTrue(kotlin.math.abs(vehicle.backrestPosition() - before) <= 2)
        assertEquals(climateBefore, vehicle.climate)
        assertEquals(ambientBefore, vehicle.ambient)
        assertEquals(SeatDirection.STOP, vehicle.currentDirection)
        assertTrue(audio.wakePlayed)
        assertTrue(audio.stopped)
        assertTrue(finished.warnings.isEmpty())
    }

    @Test
    fun seatPictureFollowsCommandsWhenTheVehicleNeverReportsMotion() = runBlocking<Unit> {
        // The Pleos Connect emulator accepts backrest commands but keeps reporting 0.
        vehicle.setPosition(0)
        vehicle.seatJammed = true

        controller.start(profile)
        awaitResting()
        val reclined = controller.seat.value
        assertTrue(reclined.simulated)
        assertEquals(profile.reclineRatio * 0.7f, reclined.recline, 0.01f)
        assertEquals(0, reclined.position)

        controller.wakeNow()
        awaitState<RestState.Finished>()
        assertEquals(0f, controller.seat.value.recline, 0.01f)
    }

    @Test
    fun seatPictureShowsTheRealSeatWhenItMoves() = runBlocking<Unit> {
        controller.start(profile)
        awaitResting()
        val view = controller.seat.value
        assertFalse(view.simulated)
        assertEquals((100 - vehicle.backrestPosition()) / 100f, view.recline, 0.03f)
    }

    @Test
    fun refusesWhenNotParked() = runBlocking<Unit> {
        vehicle.parked = false
        controller.start(profile)
        awaitState<RestState.Rejected>()
        assertTrue(vehicle.moveLog.isEmpty())
    }

    @Test
    fun refusesWhenParkStateIsUnknown() = runBlocking<Unit> {
        vehicle.parked = null
        controller.start(profile)
        awaitState<RestState.Rejected>()
        assertTrue(vehicle.moveLog.isEmpty())
    }

    @Test
    fun refusesWhenSomeoneSitsBehind() = runBlocking<Unit> {
        vehicle.seatBehindOccupied = true
        controller.start(profile)
        awaitState<RestState.Rejected>()
        assertTrue(vehicle.moveLog.isEmpty())
    }

    @Test
    fun leavingParkDuringRestStopsWithoutMovingSeat() = runBlocking<Unit> {
        controller.start(profile)
        awaitResting()
        val position = vehicle.backrestPosition()
        val moves = vehicle.moveLog.size

        vehicle.parked = false
        val interrupted = awaitState<RestState.Interrupted>()

        assertEquals(moves, vehicle.moveLog.size)
        assertEquals(position, vehicle.backrestPosition())
        assertTrue(interrupted.canRestoreSeat)
        assertTrue(audio.stopped)
    }

    @Test
    fun leavingParkWhileSeatMovesStopsSeat() = runBlocking<Unit> {
        val slow = SimulatedVehicleGateway(unitsPerSecond = 10f)
        val session = RestSessionController({ slow }, audio, scope, timings = timings)
        session.start(profile)
        withTimeout(2_000) { while (slow.currentDirection == SeatDirection.STOP) delay(2) }

        slow.parked = false
        withTimeout(2_000) { session.state.first { it is RestState.Interrupted } }

        assertEquals(SeatDirection.STOP, slow.currentDirection)
    }

    @Test
    fun emergencyStopHaltsSeatAndAllowsExplicitRestore() = runBlocking<Unit> {
        val slow = SimulatedVehicleGateway(unitsPerSecond = 40f)
        val session = RestSessionController({ slow }, audio, scope, timings = timings)
        session.start(profile)
        withTimeout(2_000) { while (slow.currentDirection == SeatDirection.STOP) delay(2) }

        session.emergencyStop()
        val interrupted = withTimeout(2_000) {
            session.state.first { it is RestState.Interrupted } as RestState.Interrupted
        }
        assertEquals(SeatDirection.STOP, slow.currentDirection)
        assertTrue(interrupted.canRestoreSeat)
        val stoppedAt = slow.backrestPosition()
        delay(30)
        assertEquals(stoppedAt, slow.backrestPosition())

        session.restoreSeat()
        withTimeout(5_000) { session.state.first { it is RestState.Finished } }
        assertTrue(kotlin.math.abs(slow.backrestPosition() - 90) <= 2)
    }

    @Test
    fun restoreRefusedWhileNotParked() = runBlocking<Unit> {
        controller.start(profile)
        awaitResting()
        vehicle.parked = false
        awaitState<RestState.Interrupted>()
        val moves = vehicle.moveLog.size

        controller.restoreSeat()
        delay(50)

        assertTrue(vehicle.moveLog.drop(moves).all { it == SeatDirection.STOP })
        assertTrue(controller.state.value is RestState.Interrupted)
    }

    @Test
    fun jammedSeatStopsAndReports() = runBlocking<Unit> {
        vehicle.seatJammed = true
        controller.start(profile)
        val interrupted = awaitState<RestState.Interrupted>()
        assertEquals(SeatDirection.STOP, vehicle.currentDirection)
        assertTrue(interrupted.reason.contains("시트"))
    }

    @Test
    fun correctsReversedSdkDirection() = runBlocking<Unit> {
        vehicle.invertDirection = true
        controller.start(profile)
        awaitResting()
        assertTrue(vehicle.backrestPosition() < 70)
        assertFalse(controller.state.value is RestState.Interrupted)
    }

    @Test
    fun missingClimateOnlyWarns() = runBlocking<Unit> {
        vehicle.climateFails = true
        controller.start(profile)
        awaitResting()
        controller.wakeNow()
        val finished = awaitState<RestState.Finished>()
        assertTrue(finished.warnings.any { it.contains("공조") })
    }

    @Test
    fun neverMovesPastAllowedReclineLimit() = runBlocking<Unit> {
        controller.start(profile.copy(reclineRatio = 1f))
        awaitResting()
        assertTrue(vehicle.backrestPosition() >= 28)
    }

    @Test
    fun climateThatWasOffIsTurnedBackOffWithoutWarnings() = runBlocking<Unit> {
        vehicle.temperatureNeedsPower = true
        controller.start(profile)
        awaitResting()
        assertEquals(true, vehicle.climate.powerOn)
        assertEquals(profile.temperatureC, vehicle.climate.temperatureC)

        controller.wakeNow()
        val finished = awaitState<RestState.Finished>()

        assertEquals(false, vehicle.climate.powerOn)
        assertTrue(finished.warnings.isEmpty())
    }

    @Test
    fun wakeSoundCanBeTurnedOff() = runBlocking<Unit> {
        controller.start(profile.copy(wakeSoundEnabled = false))
        awaitResting()
        controller.wakeNow()
        awaitState<RestState.Finished>()
        assertFalse(audio.wakePlayed)
    }

    @Test
    fun alreadyReclinedSeatIsNotRaisedOnStart() = runBlocking<Unit> {
        vehicle.setPosition(40)
        controller.start(profile.copy(reclineRatio = 0.1f))
        awaitResting()
        assertEquals(40, vehicle.backrestPosition())
    }

    private class RecordingAudio : RestAudio {
        var restSound: RestSound? = null
        var wakePlayed = false
        var stopped = false
        override fun playRest(sound: RestSound) {
            restSound = sound
        }
        override fun playWake() {
            wakePlayed = true
        }
        override fun stop() {
            stopped = true
        }
    }
}
