package com.arc.nupcar

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs

internal data class NupcarTestProperty<T>(
    val label: String,
    val area: String,
    val read: suspend () -> T?,
    val write: suspend (T) -> Unit,
    val target: (T) -> T?,
    val same: (T, T) -> Boolean = { a, b -> a == b },
    val restoreValue: (T) -> T = { it },
    val format: (T) -> String = { it.toString() },
)

internal enum class NupcarSeatDirection {
    FORWARD, BACKWARD, STOP;
    fun opposite() = if (this == FORWARD) BACKWARD else FORWARD
}

/** Notifications are recorded separately; they never replace a fresh SDK read. */
internal data class NupcarSeatFeedback(
    val count: Int = 0,
    val position: Int? = null,
    val failure: String? = null,
    val recentPositions: List<Int?> = emptyList(),
)

internal data class NupcarTestSeat(
    val minimum: Int,
    val maximum: Int,
    val read: suspend () -> Int?,
    val feedback: () -> NupcarSeatFeedback = { NupcarSeatFeedback() },
    val move: suspend (NupcarSeatDirection) -> Unit,
)

private data class NupcarSeatReadback(val position: Int?, val feedback: NupcarSeatFeedback) {
    val disagrees: Boolean
        get() = position != null && feedback.position != null && position != feedback.position
}

internal interface NupcarTestGateway {
    val isEmulator: Boolean
    val environment: List<Pair<String, suspend () -> NupcarTestProperty<*>>>
    suspend fun seat(): NupcarTestSeat
}

internal data class NupcarTestTiming(
    val pulseMs: Long = 500L,
    val returnPulseMs: Long = 120L,
    val settleMs: Long = 150L,
    val verifyMs: Long = 2_500L,
    val cleanupMs: Long = 7_000L,
    val stopMs: Long = 2_000L,
)

/** Test transactions: take a real snapshot, write, read back, then restore. */
internal class NupcarVehicleTestEngine(
    private val gateway: NupcarTestGateway,
    private val log: (String) -> Unit,
    private val timing: NupcarTestTiming = NupcarTestTiming(),
) {
    private var pendingCleanup: (suspend () -> Boolean)? = null
    val hasPendingCleanup: Boolean get() = pendingCleanup != null

    suspend fun testEnvironment() {
        checkStart()
        log("공조·앰비언트 변경 시험 시작. 각 항목을 원래 값으로 복원합니다.")
        for ((label, factory) in gateway.environment) {
            val property = try {
                factory()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("[$label] 건너뜀: ${e.message}")
                continue
            }
            roundTrip(property)
            if (hasPendingCleanup) {
                log("복원 미확인 항목이 있어 다음 시험을 중단했습니다. 복원 재시도를 눌러 주세요.")
                return
            }
        }
        log("공조·앰비언트 시험 종료. 각 항목의 ‘변경 확인’과 ‘복원 확인’을 확인하세요.")
    }

    private suspend fun <T> roundTrip(property: NupcarTestProperty<T>) {
        val original = try {
            property.read()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("[${property.label}] 원래 값 조회 실패: ${e.message}; 변경하지 않습니다.")
            return
        }
        if (original == null) {
            log("[${property.label}] 원래 값 없음; 변경하지 않습니다.")
            return
        }
        val target = property.target(original)
        if (target == null || property.same(original, target)) {
            log("[${property.label}] 지원 범위에서 시험할 다른 값을 정하지 못해 건너뜁니다.")
            return
        }
        log("[${property.label}] 영역=${property.area}")
        log("  원래=${property.format(original)} → 시험=${property.format(target)}")
        // Journal BEFORE issuing a setter: even a failed/late ACK may have changed the value.
        pendingCleanup = {
            val restore = property.restoreValue(original)
            try {
                property.write(restore)
                log("[${property.label}] 복원 요청 접수: ${property.format(restore)}")
            } catch (e: Exception) {
                log("[${property.label}] 복원 요청 응답 실패: ${e.message}")
            }
            verify(property, original, "복원")
        }
        try {
            try {
                property.write(target)
                log("[${property.label}] 변경 요청 접수. 실제 값을 재조회합니다.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("[${property.label}] 변경 요청 응답 실패: ${e.message}; 현재값 확인 후 복원합니다.")
            }
            verify(property, target, "변경")
            delay(timing.settleMs)
        } finally {
            recover()
        }
    }

    private suspend fun <T> verify(property: NupcarTestProperty<T>, expected: T, stage: String): Boolean {
        var last: T? = null
        val matched = try {
            withTimeoutOrNull(timing.verifyMs) {
                while (true) {
                    val current = property.read()
                    last = current
                    if (current != null && property.same(current, expected)) return@withTimeoutOrNull true
                    delay(timing.settleMs)
                }
                @Suppress("UNREACHABLE_CODE") false
            } == true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("[${property.label}] $stage 재조회 실패: ${e.message}")
            false
        }
        log("[${property.label}] $stage ${if (matched) "확인" else "미확인"}: " +
            (last?.let(property.format) ?: "현재값 없음"))
        return matched
    }

    suspend fun testSeat() {
        checkStart()
        val seat = gateway.seat()
        val original = requireNotNull(seat.read()) { "시트 현재 위치가 없어 이동하지 않습니다." }
        check(original in seat.minimum..seat.maximum) { "현재 위치가 지원 범위 밖이어서 이동하지 않습니다." }
        log("[시트] 피드백 점검 v3 / 같은 SDK 연결의 위치 알림과 재조회를 비교합니다.")
        log("[시트] 원래 등받이 위치=$original; 범위=${seat.minimum}..${seat.maximum} (SDK 단위)")
        logSeatReadback("시작", NupcarSeatReadback(original, seat.feedback()))
        check(!NupcarSeatReadback(original, seat.feedback()).disagrees) {
            "시작 시 조회값과 마지막 알림이 달라 이동하지 않습니다."
        }
        var lastDirection = if (original == seat.maximum) NupcarSeatDirection.BACKWARD else NupcarSeatDirection.FORWARD
        var positiveDirection: NupcarSeatDirection? = null
        var changed = false
        pendingCleanup = {
            if (!stop(seat)) false else {
                // Even an original-looking first reply may be stale after a command.
                val snapshot = awaitSeatReadback(seat, original, "복원 전 확인")
                val now = snapshot.position
                if (snapshot.disagrees) {
                    log("[시트] 조회값과 위치 알림 불일치. 추가 이동으로 복원하지 않습니다.")
                    false
                } else if (now == original) {
                    log(if (changed) "[시트] 원래 위치 복원 확인: $original"
                        else "[시트] 조회값은 시작값과 동일: $original. 실제 이동·복원 성공은 미확인입니다.")
                    true
                } else if (now == null) {
                    log("[시트] 복원할 현재 위치를 읽지 못했습니다.")
                    false
                } else {
                    // Infer direction only from an observed position change in this test.
                    val positive = positiveDirection ?: if (now > original) lastDirection else lastDirection.opposite()
                    returnSeat(seat, original, now, positive)
                }
            }
        }
        try {
            var observed = original
            for (attempt in 0..1) {
                if (attempt == 1) lastDirection = lastDirection.opposite()
                log("[시트] ${lastDirection.name} ${timing.pulseMs}ms → STOP")
                check(pulse(seat, lastDirection, timing.pulseMs)) { "STOP 응답 미확인. 이동 시험을 중단합니다." }
                val snapshot = awaitSeatReadback(seat, original, "정지 후 확인")
                check(!snapshot.disagrees) { "조회값과 위치 알림이 달라 추가 방향 이동을 중단합니다." }
                observed = requireNotNull(snapshot.position) { "이동 후 시트 위치를 읽지 못했습니다." }
                log("[시트] 정지 후 재조회: $observed")
                if (observed != original) {
                    changed = true
                    positiveDirection = if (observed > original) lastDirection else lastDirection.opposite()
                    break
                }
            }
            log(if (observed == original) "[시트] 이동 명령 후 위치 변화 미확인. 실제 이동 성공으로 처리하지 않습니다."
                else "[시트] 위치 변경 확인: $original → $observed")
        } finally {
            recover()
        }
    }

    /**
     * Watch for delayed feedback while the actuator is already stopped. A changed
     * read must be repeated and agree with any received position notification.
     * An unchanged read is watched for the complete verification window.
     */
    private suspend fun awaitSeatReadback(
        seat: NupcarTestSeat, before: Int, stage: String,
    ): NupcarSeatReadback {
        var last = NupcarSeatReadback(null, seat.feedback())
        var previous: Int? = null
        var stable = 0
        var lastPrinted: NupcarSeatReadback? = null
        withTimeoutOrNull(timing.verifyMs) {
            while (true) {
                val value = seat.read()
                check(value == null || value in seat.minimum..seat.maximum) {
                    "시트 조회값이 지원 범위 밖입니다: $value"
                }
                last = NupcarSeatReadback(value, seat.feedback())
                if (last != lastPrinted) {
                    logSeatReadback(stage, last)
                    lastPrinted = last
                }
                stable = if (value != null && value == previous) stable + 1 else 1
                previous = value
                if (value != null && value != before && stable >= 2 && !last.disagrees) {
                    return@withTimeoutOrNull
                }
                delay(timing.settleMs)
            }
        }
        return last
    }

    private fun logSeatReadback(stage: String, snapshot: NupcarSeatReadback) {
        val feedback = snapshot.feedback
        log("[시트] $stage: 재조회=${snapshot.position ?: "없음"}; " +
            "알림=${feedback.position ?: "미수신"}; 수신=${feedback.count}회" +
            if (snapshot.disagrees) "; 두 값 불일치" else "")
        feedback.failure?.let { log("[시트] 위치 알림 오류: $it") }
        if (feedback.recentPositions.isNotEmpty()) {
            log("[시트] 최근 실제 위치 알림(최대 16개): ${feedback.recentPositions.joinToString()}")
        }
    }

    /** STOP is sent after the pulse deadline, independently of the movement ACK. */
    private suspend fun pulse(seat: NupcarTestSeat, direction: NupcarSeatDirection, duration: Long): Boolean = supervisorScope {
        val movement = async(start = CoroutineStart.UNDISPATCHED) { seat.move(direction) }
        var stopped = false
        try {
            delay(duration)
            if (movement.isCompleted) {
                try { movement.await() } catch (e: Exception) { log("[시트] 이동 응답 실패: ${e.message}") }
            } else log("[시트] 이동 응답 대기 중에도 정지 시점을 유지합니다.")
        } finally {
            withContext(NonCancellable) {
                stopped = stop(seat)
                movement.cancel()
                movement.join()
            }
        }
        stopped
    }

    private suspend fun stop(seat: NupcarTestSeat): Boolean = try {
        val ok = withTimeoutOrNull(timing.stopMs) { seat.move(NupcarSeatDirection.STOP); true } == true
        log("[시트] STOP ${if (ok) "요청 접수" else "응답 미확인"}")
        ok
    } catch (e: Exception) {
        log("[시트] STOP 요청 실패: ${e.message}")
        false
    }

    private suspend fun returnSeat(
        seat: NupcarTestSeat, original: Int, initial: Int, positive: NupcarSeatDirection,
    ): Boolean {
        var position = initial
        try {
            repeat(24) {
                if (position == original) {
                    log("[시트] 원래 위치 복원 확인: $position")
                    return true
                }
                val before = position
                val direction = if (original > position) positive else positive.opposite()
                val duration = minOf(timing.returnPulseMs, maxOf(20L, abs(original - position) * 20L))
                if (!pulse(seat, direction, duration)) return false
                val snapshot = awaitSeatReadback(seat, before, "복원 중 확인")
                if (snapshot.disagrees) {
                    log("[시트] 복원 중 조회값과 위치 알림 불일치. 추가 이동을 중단합니다.")
                    return false
                }
                position = snapshot.position ?: return false
                log("[시트] 복원 재조회: $position / 원래=$original")
                if (position !in seat.minimum..seat.maximum) return false
                if (position == before || (position - original).toLong() * (before - original) < 0L) {
                    log("[시트] 정지 또는 목표 통과. 정확한 복원 미확인으로 남깁니다.")
                    return false
                }
            }
            if (position == original) {
                log("[시트] 원래 위치 복원 확인: $position")
                return true
            }
            return false
        } finally {
            withContext(NonCancellable) { stop(seat) }
        }
    }

    suspend fun recover(): Boolean = withContext(NonCancellable) {
        val cleanup = pendingCleanup ?: return@withContext true
        val restored = try {
            withTimeoutOrNull(timing.cleanupMs) { cleanup() } == true
        } catch (e: Exception) {
            log("복원 실패: ${e.message}")
            false
        }
        if (restored) pendingCleanup = null
        else log("복원 미확인. 원래 값을 보관했습니다. ‘복원 재시도’를 사용할 수 있습니다.")
        restored
    }

    private fun checkStart() {
        check(gateway.isEmulator) { "이 변경 시험은 Pleos 에뮬레이터에서만 실행됩니다." }
        check(!hasPendingCleanup) { "먼저 이전 시험의 ‘복원 재시도’를 실행해 주세요." }
    }
}
