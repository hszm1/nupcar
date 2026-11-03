package ai.pleos.playground.sample.vehicle.session

import android.content.Context

class ProfileStore(context: Context) {
    private val prefs = context.getSharedPreferences("rest_profiles", Context.MODE_PRIVATE)

    fun load(mode: NupcarMode): RestProfile {
        val base = RestProfile.default(mode)
        val key = mode.name
        return base.copy(
            durationMinutes = prefs.getInt("$key.minutes", base.durationMinutes),
            reclineRatio = prefs.getFloat("$key.recline", base.reclineRatio),
            ambientRatio = prefs.getFloat("$key.ambient", base.ambientRatio),
            temperatureC = prefs.getFloat("$key.temperature", base.temperatureC),
            sound = enumOrDefault(prefs.getString("$key.sound", null), base.sound),
            wakeSpeed = enumOrDefault(prefs.getString("$key.wake", null), base.wakeSpeed)
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
            .apply()
    }

    private inline fun <reified T : Enum<T>> enumOrDefault(name: String?, fallback: T): T =
        enumValues<T>().firstOrNull { it.name == name } ?: fallback
}
