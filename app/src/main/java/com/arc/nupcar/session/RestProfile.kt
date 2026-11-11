package com.arc.nupcar.session

enum class NupcarMode(
    val title: String,
    val description: String,
    val defaultMinutes: Int,
) {
    NAP("낮잠", "잠깐 눈을 붙이고 쉬어가요.", 20),
    MEDITATION("명상", "잠시 호흡과 나에게 집중해요.", 10),
    VIDEO("영상 시청", "편한 자세로 맞춘 뒤 다른 앱에서 영상을 즐겨요.", 60),
}

/** Rest length the time wheel allows, in minutes: 1 minute up to 23 hours 59 minutes. */
const val MIN_REST_MINUTES = 1
const val MAX_REST_MINUTES = 23 * 60 + 59

enum class RestSound(val title: String) {
    NONE("없음"),
    WHITE_NOISE("백색소음"),
    SOFT_NOISE("잔잔한 소리")
}

/**
 * The SDK moves the backrest by direction only and exposes no speed, so a slower
 * wake-up is made by alternating short moves with pauses.
 */
enum class WakeSpeed(val title: String, val moveMillis: Long, val pauseMillis: Long) {
    GENTLE("아주 천천히", 1_000, 2_000),
    SLOW("천천히", 2_000, 1_000),
    NORMAL("보통", Long.MAX_VALUE, 0)
}

data class RestProfile(
    val mode: NupcarMode,
    val durationMinutes: Int,
    /** 0 keeps the backrest upright, 1 is the deepest recline this app allows. */
    val reclineRatio: Float,
    /** 0 is the dimmest ambient level the vehicle supports, 1 the brightest. */
    val ambientRatio: Float,
    val temperatureC: Float,
    val sound: RestSound,
    val wakeSpeed: WakeSpeed,
    val wakeSoundEnabled: Boolean = true,
    /**
     * Shows a breathing circle to follow during the rest, whatever the sound. The chosen
     * sound then swells and fades with the same breath, so it can be followed eyes closed.
     */
    val breathingGuide: Boolean = false
) {
    /**
     * Video plays in another app, so Nupcar plays no rest sound and ends the session with a
     * notification instead of a wake-up sound that would talk over the video.
     */
    val quietWake: Boolean get() = mode == NupcarMode.VIDEO

    companion object {
        fun default(mode: NupcarMode): RestProfile = when (mode) {
            NupcarMode.NAP -> RestProfile(mode, mode.defaultMinutes, 0.8f, 0.05f, 23f, RestSound.WHITE_NOISE, WakeSpeed.SLOW)
            NupcarMode.MEDITATION -> RestProfile(
                mode, mode.defaultMinutes, 0.3f, 0.3f, 24f, RestSound.SOFT_NOISE, WakeSpeed.SLOW,
                breathingGuide = true
            )
            // A slight recline for watching a screen, and dim light that still avoids glare.
            NupcarMode.VIDEO -> RestProfile(
                mode, mode.defaultMinutes, 0.35f, 0.15f, 23f, RestSound.NONE, WakeSpeed.SLOW,
                wakeSoundEnabled = false
            )
        }
    }
}
