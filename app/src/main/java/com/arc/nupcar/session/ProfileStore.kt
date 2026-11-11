package com.arc.nupcar.session

import android.content.Context

class ProfileStore(context: Context) {
    private val prefs = context.getSharedPreferences("rest_profiles", Context.MODE_PRIVATE)

    fun load(mode: NupcarMode): RestProfile {
        val base = RestProfile.default(mode)
        val key = mode.name
        val storedSound = prefs.getString("$key.sound", null)
        // The breathing guide used to be a sound. A profile saved that way keeps the guide
        // and falls back to the mode's default sound.
        val guideFromOldSound = storedSound == LEGACY_BREATHING_SOUND
        return base.copy(
            durationMinutes = prefs.getInt("$key.minutes", base.durationMinutes)
                .coerceIn(MIN_REST_MINUTES, MAX_REST_MINUTES),
            reclineRatio = prefs.getFloat("$key.recline", base.reclineRatio),
            ambientRatio = prefs.getFloat("$key.ambient", base.ambientRatio),
            temperatureC = prefs.getFloat("$key.temperature", base.temperatureC),
            sound = enumOrDefault(storedSound, base.sound),
            wakeSpeed = enumOrDefault(prefs.getString("$key.wake", null), base.wakeSpeed),
            breathingGuide = prefs.getBoolean("$key.breathing", guideFromOldSound || base.breathingGuide)
        )
    }

    fun save(profile: RestProfile) {
        val key = profile.mode.name
        prefs.edit()
            .putInt("$key.minutes", profile.durationMinutes)
            .putFloat("$key.recline", profile.reclineRatio)
            .putFloat("$key.ambient", profile.ambientRatio)
            .putFloat("$key.temperature", profile.temperatureC)
            .putString("$key.sound", profile.sound.name)
            .putString("$key.wake", profile.wakeSpeed.name)
            .putBoolean("$key.breathing", profile.breathingGuide)
            .apply()
    }

    private companion object {
        const val LEGACY_BREATHING_SOUND = "BREATHING"
    }

    private inline fun <reified T : Enum<T>> enumOrDefault(name: String?, fallback: T): T =
        enumValues<T>().firstOrNull { it.name == name } ?: fallback
}
