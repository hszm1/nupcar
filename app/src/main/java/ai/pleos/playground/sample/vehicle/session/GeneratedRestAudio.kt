package ai.pleos.playground.sample.vehicle.session

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/** Generates every sound in code, so the app ships no audio files that need licensing. */
class GeneratedRestAudio : RestAudio {
    @Volatile private var running = false
    private var worker: Thread? = null

    override fun playRest(sound: RestSound) {
        stop()
        resetFilters()
        when (sound) {
            RestSound.NONE -> Unit
            RestSound.WHITE_NOISE -> play(AudioAttributes.USAGE_MEDIA, REST_FADE_IN_SECONDS) { index ->
                val swell = sin(2.0 * PI * index / (SAMPLE_RATE * SWELL_SECONDS)).toFloat()
                pinkNoise() * (0.85f + 0.15f * swell)
            }
            RestSound.SOFT_NOISE -> play(AudioAttributes.USAGE_MEDIA, REST_FADE_IN_SECONDS) { noise(smoothing = 0.97f, gain = 0.5f) }
            RestSound.BREATHING -> play(AudioAttributes.USAGE_MEDIA, REST_FADE_IN_SECONDS) { index ->
                val level = BreathCycle.level(index * 1000L / SAMPLE_RATE)
                noise(smoothing = 0.97f, gain = 0.5f) * (0.25f + 0.75f * level)
            }
        }
    }

    override fun playWake() {
        stop()
        play(AudioAttributes.USAGE_ALARM) { index ->
            val seconds = index.toFloat() / SAMPLE_RATE
            val note = WAKE_NOTES[(seconds / WAKE_NOTE_SECONDS).toInt() % WAKE_NOTES.size]
            val noteTime = seconds % WAKE_NOTE_SECONDS
            val fade = (1f - noteTime / WAKE_NOTE_SECONDS)
            val rise = min(1f, seconds / WAKE_RISE_SECONDS)
            sin(2.0 * PI * note * noteTime).toFloat() * fade * rise * 0.4f
        }
    }

    override fun stop() {
        running = false
        worker?.join(STOP_WAIT_MILLIS)
        worker = null
    }

    private var lastSample = 0f
    private var pink0 = 0f
    private var pink1 = 0f
    private var pink2 = 0f
    private var pinkSoft = 0f

    private fun resetFilters() {
        lastSample = 0f
        pink0 = 0f
        pink1 = 0f
        pink2 = 0f
        pinkSoft = 0f
    }

    private fun noise(smoothing: Float, gain: Float): Float {
        val white = Random.nextFloat() * 2f - 1f
        lastSample = smoothing * lastSample + (1f - smoothing) * white
        return lastSample * gain * if (smoothing > 0f) 6f else 1f
    }

    /**
     * Pink noise: each octave carries equal energy, so it sounds like steady rain instead
     * of the hiss of plain white noise. Uses Paul Kellet's three-pole approximation, then a
     * light low-pass to take the remaining edge off the top.
     */
    private fun pinkNoise(): Float {
        val white = Random.nextFloat() * 2f - 1f
        pink0 = 0.99765f * pink0 + white * 0.0990460f
        pink1 = 0.96300f * pink1 + white * 0.2965164f
        pink2 = 0.57000f * pink2 + white * 1.0526913f
        val pink = pink0 + pink1 + pink2 + white * 0.1848f
        pinkSoft = 0.5f * pinkSoft + 0.5f * pink
        return pinkSoft * PINK_GAIN
    }

    private fun play(usage: Int, fadeInSeconds: Float = 0f, sample: (Int) -> Float) {
        running = true
        worker = thread(name = "nupcar-audio") {
            val bufferBytes = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
                .coerceAtLeast(BUFFER_SAMPLES * 2)
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(usage)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(CHANNEL)
                        .setEncoding(ENCODING)
                        .build()
                )
                .setBufferSizeInBytes(bufferBytes)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            try {
                track.play()
                val buffer = ShortArray(BUFFER_SAMPLES)
                val fadeSamples = (fadeInSeconds * SAMPLE_RATE).toLong()
                var index = 0
                var played = 0L
                while (running) {
                    for (i in buffer.indices) {
                        val fade = if (played < fadeSamples) played.toFloat() / fadeSamples else 1f
                        buffer[i] = ((sample(index) * fade).coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort()
                        index = (index + 1) % LOOP_SAMPLES
                        played++
                    }
                    track.write(buffer, 0, buffer.size)
                }
            } finally {
                runCatching { track.stop() }
                track.release()
            }
        }
    }

    private companion object {
        const val SAMPLE_RATE = 44_100
        const val CHANNEL = AudioFormat.CHANNEL_OUT_MONO
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        const val BUFFER_SAMPLES = 2_048
        const val LOOP_SAMPLES = SAMPLE_RATE * 600
        /** Divides the 600-second loop evenly, so the swell has no jump when the loop wraps. */
        const val SWELL_SECONDS = 15
        const val REST_FADE_IN_SECONDS = 3f
        /** About the loudness of the previous white noise, with peaks near half scale. */
        const val PINK_GAIN = 0.05f
        const val WAKE_NOTE_SECONDS = 1.6f
        const val WAKE_RISE_SECONDS = 10f
        const val STOP_WAIT_MILLIS = 500L
        val WAKE_NOTES = floatArrayOf(523.25f, 659.25f, 783.99f, 659.25f)
    }
}
