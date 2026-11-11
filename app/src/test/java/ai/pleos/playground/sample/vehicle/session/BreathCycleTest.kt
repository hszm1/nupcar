package ai.pleos.playground.sample.vehicle.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BreathCycleTest {
    @Test
    fun eachBreathStartsEmptyAndPeaksHalfway() {
        assertEquals(0f, BreathCycle.level(0), 0.001f)
        assertEquals(1f, BreathCycle.level(5_000), 0.001f)
        assertEquals(0f, BreathCycle.level(10_000), 0.001f)
    }

    @Test
    fun theWordFollowsTheSoundRisingThenFalling() {
        // Sound rises while inhaling and falls while exhaling.
        assertTrue(BreathCycle.isInhaling(0))
        assertTrue(BreathCycle.level(2_000) > BreathCycle.level(1_000))
        assertTrue(BreathCycle.isInhaling(4_999))
        assertFalse(BreathCycle.isInhaling(5_000))
        assertTrue(BreathCycle.level(7_000) < BreathCycle.level(6_000))
        assertFalse(BreathCycle.isInhaling(9_999))
        assertTrue(BreathCycle.isInhaling(10_000))
    }
}
