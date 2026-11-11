package com.arc.nupcar

import kotlinx.coroutines.*
import org.junit.Test

private class Gateway : NupcarTestGateway {
    override var isEmulator = true
    override var environment: List<Pair<String, suspend () -> NupcarTestProperty<*>>> = emptyList()
    var position = 0
    var frozen = false
    var missingMoveAck = false
    var stopFails = false
    val commands = mutableListOf<NupcarSeatDirection>()
    var minimum = 0
    var maximum = 100
    var feedback: () -> NupcarSeatFeedback = { NupcarSeatFeedback() }
    override suspend fun seat() = NupcarTestSeat(minimum, maximum, { position }, feedback) { command ->
        commands += command
        if (command == NupcarSeatDirection.STOP) {
            check(!stopFails) { "STOP test failure" }
        } else {
            if (!frozen) position = (position + if (command == NupcarSeatDirection.FORWARD) 1 else -1).coerceIn(minimum, maximum)
            if (missingMoveAck) awaitCancellation()
        }
    }
}

private val timing = NupcarTestTiming(pulseMs=12, returnPulseMs=2, settleMs=1, verifyMs=20, cleanupMs=300, stopMs=30)
private fun factory(property: NupcarTestProperty<Int>): Pair<String, suspend () -> NupcarTestProperty<*>> = property.label to { property }


class NupcarVehicleTestEngineTest {
    @Test
    fun environmentRoundTrip() = runBlocking {
        val g=Gateway(); var value=70;val writes=mutableListOf<Int>();val logs=mutableListOf<String>()
        g.environment=listOf(factory(NupcarTestProperty("brightness","all",{value},{value=it;writes+=it},{it-20})))
        val e=NupcarVehicleTestEngine(g,logs::add,timing);e.testEnvironment()
        check(writes==listOf(50,70));check(value==70);check(!e.hasPendingCleanup)
        check(logs.any { it.contains("변경 확인: 50") });check(logs.any {it.contains("복원 확인: 70")})
    }

    @Test
    fun missingSnapshotPreventsWrite() = runBlocking {
        val g=Gateway();var writes=0;val logs=mutableListOf<String>()
        g.environment=listOf(factory(NupcarTestProperty<Int>("missing","driver",{null},{writes++},{it-1})))
        NupcarVehicleTestEngine(g,logs::add,timing).testEnvironment()
        check(writes==0);check(logs.any {it.contains("원래 값 없음")})
    }

    @Test
    fun unavailableTemperatureDoesNotBlockOtherTests() = runBlocking {
        val g=Gateway();var value=3
        val unavailable: Pair<String,suspend()->NupcarTestProperty<*>> = "temperature" to {error("PropertyNotAvailableException")}
        g.environment=listOf(unavailable,factory(NupcarTestProperty("fan","front",{value},{value=it},{it-1})))
        val logs=mutableListOf<String>();NupcarVehicleTestEngine(g,logs::add,timing).testEnvironment()
        check(value==3);check(logs.any{it.contains("건너뜀")});check(logs.any{it.contains("변경 확인: 2")})
    }

    @Test
    fun ackWithoutReadbackIsNotSuccess() = runBlocking {
        val g=Gateway();val logs=mutableListOf<String>()
        g.environment=listOf(factory(NupcarTestProperty("ignored","all",{70},{},{it-20})))
        NupcarVehicleTestEngine(g,logs::add,timing).testEnvironment()
        check(logs.any {it.contains("변경 미확인")});check(logs.none {it.contains("변경 확인:")})
        check(logs.any {it.contains("복원 확인: 70")})
    }

    @Test
    fun failedAckStillRestores() = runBlocking {
        val g=Gateway();var value=70;val logs=mutableListOf<String>()
        g.environment=listOf(factory(NupcarTestProperty("applied-before-error","all",{value},{value=it;if(it==50)error("late failure")},{it-20})))
        NupcarVehicleTestEngine(g,logs::add,timing).testEnvironment()
        check(value==70);check(logs.any{it.contains("응답 실패")});check(logs.any{it.contains("변경 확인: 50")})
    }

    @Test
    fun failedRestoreRetainsJournal() = runBlocking {
        val g=Gateway();var value=70;var failRestore=true;var secondWrites=0
        val p=NupcarTestProperty("restore-failure","all",{value},{if(it==70 && failRestore)error("restore failed");value=it},{it-20})
        val p2=NupcarTestProperty("second","all",{3},{secondWrites++},{it-1})
        g.environment=listOf(factory(p),factory(p2));val e=NupcarVehicleTestEngine(g,{},timing)
        e.testEnvironment();check(e.hasPendingCleanup);check(value==50);check(secondWrites==0)
        check(runCatching { e.testEnvironment() }.isFailure)
        failRestore=false;check(e.recover());check(!e.hasPendingCleanup);check(value==70)
    }

    @Test
    fun cancellationRestoresOriginal() = runBlocking {
        val g=Gateway();var value=70;val changed=CompletableDeferred<Unit>()
        val p=NupcarTestProperty("cancelled","all",{if(value!=70)delay(1000);value},{value=it;if(it!=70)changed.complete(Unit)},{it-20})
        g.environment=listOf(factory(p));val e=NupcarVehicleTestEngine(g,{},timing)
        val job=launch {e.testEnvironment()};changed.await();job.cancelAndJoin()
        check(value==70);check(!e.hasPendingCleanup)
    }

    @Test
    fun rgbRestoreNormalizesAlpha() = runBlocking {
        val g=Gateway();var color=0x00FFFFFF;val logs=mutableListOf<String>()
        val p=NupcarTestProperty("RGB","driver",{color},{color=it},{0xFF95F3D1.toInt()},
            same={a,b->(a and 0xFFFFFF)==(b and 0xFFFFFF)},restoreValue={it or 0xFF000000.toInt()})
        g.environment=listOf(factory(p));val e=NupcarVehicleTestEngine(g,logs::add,timing);e.testEnvironment()
        check(color==0xFFFFFFFF.toInt());check(!e.hasPendingCleanup);check(logs.any{it.contains("복원 확인")})
    }

    @Test
    fun seatMovementRestoresOriginal() = runBlocking {
        val g=Gateway();val logs=mutableListOf<String>();val e=NupcarVehicleTestEngine(g,logs::add,timing)
        e.testSeat();check(g.position==0);check(g.commands.last()==NupcarSeatDirection.STOP)
        check(g.commands.indexOf(NupcarSeatDirection.STOP)<g.commands.indexOf(NupcarSeatDirection.BACKWARD))
        check(!e.hasPendingCleanup)
        check(logs.any{it.contains("위치 변경 확인")});check(logs.any{it.contains("원래 위치 복원 확인")})
    }

    @Test
    fun seatAckWithoutPositionChange() = runBlocking {
        val g=Gateway();g.frozen=true;val logs=mutableListOf<String>();val e=NupcarVehicleTestEngine(g,logs::add,timing)
        e.testSeat();check(g.position==0);check(!e.hasPendingCleanup)
        check(g.commands.contains(NupcarSeatDirection.BACKWARD));check(logs.any{it.contains("위치 변화 미확인")})
        check(logs.none{it.contains("위치 변경 확인")})
    }

    @Test
    fun seatStopIndependentOfMovementAck() = runBlocking {
        val g=Gateway();g.missingMoveAck=true;val e=NupcarVehicleTestEngine(g,{},timing)
        e.testSeat();check(g.position==0);check(!e.hasPendingCleanup)
        check(g.commands.contains(NupcarSeatDirection.STOP))
    }

    @Test
    fun stopFailureBlocksFurtherMotion() = runBlocking {
        val g=Gateway();g.stopFails=true;val e=NupcarVehicleTestEngine(g,{},timing)
        check(runCatching{e.testSeat()}.isFailure);check(e.hasPendingCleanup)
        check(g.commands.count{it!=NupcarSeatDirection.STOP}==1)
        g.stopFails=false;check(e.recover());check(g.position==0)
    }

    @Test
    fun cancelledSeatPulseStopsAndRestores() = runBlocking {
        val g=Gateway();val e=NupcarVehicleTestEngine(g,{},timing.copy(pulseMs=1000))
        val job=launch {e.testSeat()}
        while(g.commands.isEmpty())yield()
        job.cancelAndJoin();check(g.commands.contains(NupcarSeatDirection.STOP))
        check(g.position==0);check(!e.hasPendingCleanup)
    }

    @Test
    fun invalidSeatPositionPreventsMotion() = runBlocking {
        val g=Gateway();g.position=150;val e=NupcarVehicleTestEngine(g,{},timing)
        check(runCatching{e.testSeat()}.isFailure);check(g.commands.isEmpty())
    }

    @Test
    fun physicalDeviceGate() = runBlocking {
        val g=Gateway();g.isEmulator=false;val e=NupcarVehicleTestEngine(g,{},timing)
        check(runCatching{e.testSeat()}.isFailure);check(runCatching{e.testEnvironment()}.isFailure)
        check(g.commands.isEmpty())
    }

    @Test
    fun delayedPositionAfterStopIsObservedBeforeOppositeDirection() = runBlocking {
        var actual = 0
        var beforeMove = 0
        var moving = false
        var staleReplies = 0
        val commands = mutableListOf<NupcarSeatDirection>()
        val g = object : NupcarTestGateway {
            override val isEmulator = true
            override val environment = emptyList<Pair<String, suspend () -> NupcarTestProperty<*>>>()
            override suspend fun seat() = NupcarTestSeat(0, 100, {
                if (staleReplies > 0) { staleReplies--; beforeMove } else actual
            }) { command ->
                commands += command
                if (command == NupcarSeatDirection.STOP) {
                    if (moving) staleReplies = 3
                    moving = false
                } else {
                    beforeMove = actual
                    actual += if (command == NupcarSeatDirection.FORWARD) 1 else -1
                    moving = true
                }
            }
        }
        val logs = mutableListOf<String>()
        val e = NupcarVehicleTestEngine(g, logs::add, timing.copy(verifyMs = 100))
        e.testSeat()
        check(actual == 0)
        check(commands.filter { it != NupcarSeatDirection.STOP } ==
            listOf(NupcarSeatDirection.FORWARD, NupcarSeatDirection.BACKWARD))
        check(logs.any { it.contains("위치 변경 확인: 0 → 1") })
        check(logs.any { it.contains("원래 위치 복원 확인: 0") })
        check(!e.hasPendingCleanup)
    }

    @Test
    fun conflictingCallbackPreventsBlindReverseAndFalseRestore() = runBlocking {
        val g = Gateway()
        g.frozen = true
        g.feedback = {
            if (g.commands.any { it == NupcarSeatDirection.FORWARD })
                NupcarSeatFeedback(count = 1, position = 5, recentPositions = listOf(5))
            else NupcarSeatFeedback()
        }
        val logs = mutableListOf<String>()
        val e = NupcarVehicleTestEngine(g, logs::add, timing)
        check(runCatching { e.testSeat() }.isFailure)
        check(e.hasPendingCleanup)
        check(g.commands.count { it != NupcarSeatDirection.STOP } == 1)
        check(g.commands.last() == NupcarSeatDirection.STOP)
        check(logs.none { it.contains("원래 위치 복원 확인") })
        check(logs.any { it.contains("알림=5") })
    }

    @Test
    fun unchangedPositionDoesNotClaimMovementOrRestoration() = runBlocking {
        val g = Gateway()
        g.frozen = true
        val logs = mutableListOf<String>()
        NupcarVehicleTestEngine(g, logs::add, timing).testSeat()
        check(logs.none { it.contains("원래 위치 복원 확인") || it.contains("위치 변경 확인") })
        check(logs.any { it.contains("실제 이동·복원 성공은 미확인") })
    }

    @Test
    fun matchingNotificationsAndFreshReadsCanRestore() = runBlocking {
        val g = Gateway()
        g.feedback = { NupcarSeatFeedback(count = g.commands.size, position = g.position) }
        val logs = mutableListOf<String>()
        val e = NupcarVehicleTestEngine(g, logs::add, timing)
        e.testSeat()
        check(g.position == 0)
        check(!e.hasPendingCleanup)
        check(logs.any { it.contains("위치 변경 확인") })
        check(logs.any { it.contains("원래 위치 복원 확인") })
    }

    @Test
    fun initialReadAndNotificationConflictPreventsMovement() = runBlocking {
        val g = Gateway()
        g.feedback = { NupcarSeatFeedback(count = 1, position = 10) }
        val e = NupcarVehicleTestEngine(g, {}, timing)
        check(runCatching { e.testSeat() }.isFailure)
        check(g.commands.isEmpty())
    }

}
