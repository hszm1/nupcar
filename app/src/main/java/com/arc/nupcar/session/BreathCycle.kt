package com.arc.nupcar.session

import kotlin.math.PI
import kotlin.math.cos

/**
 * One guided breath: in for half the period while the sound swells, out for the other
 * half while it fades. The breathing sound and the on-screen guide both read this, so the
 * words on screen always match what the user hears.
 */
object BreathCycle {
    const val PERIOD_MILLIS = 10_000L

    /** 0 with empty lungs at the start of each breath, 1 with full lungs halfway through. */
    fun level(elapsedMillis: Long): Float {
        val t = elapsedMillis.mod(PERIOD_MILLIS).toDouble() / PERIOD_MILLIS
        return ((1.0 - cos(2.0 * PI * t)) / 2.0).toFloat()
    }

    fun isInhaling(elapsedMillis: Long): Boolean = elapsedMillis.mod(PERIOD_MILLIS) < PERIOD_MILLIS / 2
}
