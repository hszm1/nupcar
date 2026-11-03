package ai.pleos.playground.sample.vehicle.session

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.abs

class SeatMoveException(message: String) : Exception(message)

data class SeatMoveTimings(
    val pollMillis: Long = 100,
    val stallMillis: Long = 3_000,
    val maxMoveMillis: Long = 120_000
)

/**
 * Drives the backrest to a position by watching it move, because the SDK only starts
 * and stops motion. The seat is always told to stop when this exits for any reason.
 */
class SeatMover(
    private val vehicle: VehicleGateway,
    private val now: () -> Long,
    private val timings: SeatMoveTimings = SeatMoveTimings()
) {
    /** Set once a move shows that the SDK direction is the reverse of what we assumed. */
    private var directionInverted = false

    suspend fun moveTo(
        target: Int,
        speed: WakeSpeed = WakeSpeed.NORMAL,
        guard: suspend () -> Unit
    ) {
        try {
            run(target, speed, guard)
        } finally {
            withContext(NonCancellable) { stopWithRetry() }
        }
    }

    private suspend fun run(target: Int, speed: WakeSpeed, guard: suspend () -> Unit) {
        guard()
        val start = readPosition()
        val tolerance = 1
        if (abs(start - target) <= tolerance) return

        val deadline = now() + timings.maxMoveMillis
        val wanted = if (target < start) SeatDirection.RECLINE else SeatDirection.UPRIGHT
        var closest = abs(start - target)
        var lastProgressAt = now()
        var triedReverse = false
        var moving = false
        var moveStartedAt = now()

        while (true) {
            guard()
            if (now() > deadline) throw SeatMoveException("시트 이동 시간이 초과되었습니다")

            if (!moving) {
                vehicle.moveBackrest(command(wanted))
                moving = true
                moveStartedAt = now()
                lastProgressAt = now()
            }
            delay(timings.pollMillis)

            val position = readPosition()
            val distance = abs(position - target)
            val crossed = (start - target) * (position - target) < 0
            if (distance <= tolerance || crossed) return

            when {
                distance < closest -> {
                    closest = distance
                    lastProgressAt = now()
                }
                distance > closest + tolerance -> {
                    vehicle.moveBackrest(SeatDirection.STOP)
                    if (triedReverse) throw SeatMoveException("시트가 예상과 다른 방향으로 움직였습니다")
                    directionInverted = !directionInverted
                    triedReverse = true
                    moving = false
                    closest = distance
                    continue
                }
            }

            if (now() - lastProgressAt > timings.stallMillis) {
                vehicle.moveBackrest(SeatDirection.STOP)
                moving = false
                if (triedReverse) throw SeatMoveException("시트가 움직이지 않습니다")
                directionInverted = !directionInverted
                triedReverse = true
                continue
            }

            if (now() - moveStartedAt >= speed.moveMillis) {
                vehicle.moveBackrest(SeatDirection.STOP)
                moving = false
                delay(speed.pauseMillis)
            }
        }
    }

    private fun command(wanted: SeatDirection): SeatDirection = when {
        !directionInverted -> wanted
        wanted == SeatDirection.RECLINE -> SeatDirection.UPRIGHT
        wanted == SeatDirection.UPRIGHT -> SeatDirection.RECLINE
        else -> wanted
    }

    private suspend fun readPosition(): Int =
        vehicle.backrestPosition() ?: throw SeatMoveException("시트 위치를 읽을 수 없습니다")

    private suspend fun stopWithRetry() {
        repeat(STOP_ATTEMPTS) { attempt ->
            val stopped = runCatching { vehicle.moveBackrest(SeatDirection.STOP) }.isSuccess
            if (stopped) return
            if (attempt < STOP_ATTEMPTS - 1) delay(STOP_RETRY_MILLIS)
        }
    }

    private companion object {
        const val STOP_ATTEMPTS = 3
        const val STOP_RETRY_MILLIS = 150L
    }
}
