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
        when (sound) {
            RestSound.NONE -> Unit
            RestSound.WHITE_NOISE -> play(AudioAttributes.USAGE_MEDIA) { noise(smoothing = 0f, gain = 0.12f) }
            RestSound.SOFT_NOISE -> play(AudioAttributes.USAGE_MEDIA) { noise(smoothing = 0.97f, gain = 0.5f) }
            RestSound.BREATHING -> play(AudioAttributes.USAGE_MEDIA) { index ->
                val phase = sin(2.0 * PI * index / (SAMPLE_RATE * BREATH_SECONDS)).toFloat()
                noise(smoothing = 0.97f, gain = 0.5f) * (0.25f + 0.75f * (phase + 1f) / 2f)
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

    private fun noise(smoothing: Float, gain: Float): Float {
        val white = Random.nextFloat() * 2f - 1f
        lastSample = smoothing * lastSample + (1f - smoothing) * white
        return lastSample * gain * if (smoothing > 0f) 6f else 1f
    }

    private fun play(usage: Int, sample: (Int) -> Float) {
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
                var index = 0
                while (running) {
                    for (i in buffer.indices) {
                        buffer[i] = (sample(index).coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort()
                        index = (index + 1) % LOOP_SAMPLES
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
        const val BREATH_SECONDS = 10
        const val WAKE_NOTE_SECONDS = 1.6f
        const val WAKE_RISE_SECONDS = 10f
        const val STOP_WAIT_MILLIS = 500L
        val WAKE_NOTES = floatArrayOf(523.25f, 659.25f, 783.99f, 659.25f)
    }
}
