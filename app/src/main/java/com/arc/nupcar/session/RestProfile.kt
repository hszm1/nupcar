package com.arc.nupcar.session

enum class NupcarMode(
    val title: String,
    val description: String,
    val defaultMinutes: Int,
) {
    NAP("낮잠", "잠깐 눈을 붙이고 쉬어가요.", 20),
    MEDITATION("명상", "잠시 호흡과 나에게 집중해요.", 10),
    ASMR("ASMR", "편안한 소리와 함께하는 휴식.", 30),
    FREE("자유 휴식", "원하는 환경을 조합해 저장해요.", 15),
}

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
    companion object {
        fun default(mode: NupcarMode): RestProfile = when (mode) {
            NupcarMode.NAP -> RestProfile(mode, mode.defaultMinutes, 0.8f, 0.05f, 23f, RestSound.WHITE_NOISE, WakeSpeed.SLOW)
            NupcarMode.MEDITATION -> RestProfile(
                mode, mode.defaultMinutes, 0.3f, 0.3f, 24f, RestSound.SOFT_NOISE, WakeSpeed.SLOW,
                breathingGuide = true
            )
            NupcarMode.ASMR -> RestProfile(mode, mode.defaultMinutes, 0.5f, 0.1f, 23f, RestSound.SOFT_NOISE, WakeSpeed.SLOW)
            NupcarMode.FREE -> RestProfile(mode, mode.defaultMinutes, 0.5f, 0.2f, 23f, RestSound.SOFT_NOISE, WakeSpeed.SLOW)
        }
    }
}
