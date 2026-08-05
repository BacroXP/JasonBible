package data

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin


/**
 * Categories of sound effects that the app plays.
 */
enum class SoundEvent {
    Click,
    Hover,
    Open,
    Close
}


/**
 * Procedurally generates short PCM-16 mono WAV files for each [SoundEvent]
 * and persists them under `~/.bibleapp/sounds/`. The app ships no audio
 * assets — every WAV is created on first run so the [SoundManager] has
 * something to play.
 *
 * Sound design:
 *   - Click       — 60 ms, 1500 Hz sine with fast exp decay (35)
 *   - Hover       — 25 ms, 850 Hz sine with very fast exp decay (80)
 *   - Open        — 180 ms, frequency sweep 400→1100 Hz, trapezoid envelope
 *   - Close       — 160 ms, frequency sweep 1100→400 Hz, trapezoid envelope
 *
 * Sample format is mono 22050 Hz, 16-bit PCM little-endian, packed into a
 * standard RIFF/WAVE container.
 */
object SoundLibrary {

    private const val SAMPLE_RATE = 22050

    val soundsDir: Path = Path.of(
        System.getProperty("user.home"),
        ".bibleapp",
        "sounds"
    )


    /**
     * Writes a stub WAV into [soundsDir] for every event that doesn't
     * already exist. Failures (disk full / missing permissions / no audio
     * system at all on headless systems) are intentionally swallowed —
     * [SoundManager.init] will simply have nothing to play.
     */
    fun ensureGenerated() {
        runCatching {
            Files.createDirectories(soundsDir)
            SoundEvent.entries.forEach { event ->
                val target = pathFor(event)
                if (!Files.exists(target)) {
                    runCatching { Files.write(target, generateWavBytes(event)) }
                }
            }
        }
    }


    fun pathFor(event: SoundEvent): Path =
        soundsDir.resolve("${event.name.lowercase()}.wav")


    /**
     * Accessible only to tests / debug surfaces — emits the raw WAV bytes
     * for [event] without touching disk.
     */
    fun generateWavBytes(event: SoundEvent): ByteArray {
        val samples = synthesize(event)
        val pcm = ShortArray(samples.size) { i ->
            (samples[i].coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort()
        }
        val dataBytes = pcm.size * 2
        val buf = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN)
        // RIFF chunk
        buf.put("RIFF".toByteArray())
        buf.putInt(36 + dataBytes)
        buf.put("WAVE".toByteArray())
        // fmt chunk
        buf.put("fmt ".toByteArray())
        buf.putInt(16)                 // PCM fmt chunk size
        buf.putShort(1)                // audio format = PCM
        buf.putShort(1)                // channels = mono
        buf.putInt(SAMPLE_RATE)
        buf.putInt(SAMPLE_RATE * 2)    // byte rate = sr * channels * bytes/sample
        buf.putShort(2)                // block align
        buf.putShort(16)               // bits per sample
        // data chunk
        buf.put("data".toByteArray())
        buf.putInt(dataBytes)
        for (s in pcm) buf.putShort(s)
        return buf.array()
    }


    private fun synthesize(event: SoundEvent): FloatArray {
        val duration = when (event) {
            SoundEvent.Click -> 0.060
            SoundEvent.Hover -> 0.025
            SoundEvent.Open -> 0.180
            SoundEvent.Close -> 0.160
        }
        val n = (duration * SAMPLE_RATE).toInt().coerceAtLeast(2)
        val out = FloatArray(n)
        for (i in 0 until n) {
            val t = i.toDouble() / SAMPLE_RATE
            val sample: Double = when (event) {
                SoundEvent.Click -> {
                    val env = exp(-t * 35.0)
                    sin(2 * PI * 1500.0 * t) * env
                }
                SoundEvent.Hover -> {
                    val env = exp(-t * 80.0)
                    sin(2 * PI * 850.0 * t) * env
                }
                SoundEvent.Open -> {
                    val env = trapezoidEnvelope(t, duration, 0.005, 0.040)
                    val freq = 400.0 + (1100.0 - 400.0) * (t / duration)
                    sin(2 * PI * freq * t) * env
                }
                SoundEvent.Close -> {
                    val env = trapezoidEnvelope(t, duration, 0.005, 0.040)
                    val freq = 1100.0 - (1100.0 - 400.0) * (t / duration)
                    sin(2 * PI * freq * t) * env
                }
            }
            out[i] = (sample * 0.5).toFloat()
        }
        return out
    }


    /** Linear ramp-up over [0, attack], sustain 1.0, linear ramp-down ending at [total]. */
    private fun trapezoidEnvelope(
        t: Double,
        total: Double,
        attack: Double,
        release: Double
    ): Double {
        val atk = attack.coerceAtMost(total / 2)
        val rel = release.coerceAtMost(total - atk)
        return when {
            t < 0 -> 0.0
            t < atk -> t / atk
            t > total - rel -> (total - t) / rel
            else -> 1.0
        }
    }
}
