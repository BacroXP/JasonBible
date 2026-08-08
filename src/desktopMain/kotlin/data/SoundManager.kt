package data

import java.io.File
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.Clip
import javax.sound.sampled.FloatControl


/**
 * Plays short procedural sound effects with a per-event [Clip] pool so
 * rapid clicks don't starve the audio mixer. All entry points are
 * best-effort — any failure (headless CI, no audio device, mute toggle,
 * missing mixer control, volume 0) becomes a silent no-op rather than
 * crashing the UI.
 *
 * Lifecycle:
 *   - [Main] calls [init] exactly once at startup. The pool then reads
 *     each WAV via the default mixer (`AudioSystem.getClip()`).
 *   - [shutdown] closes every Clip gracefully; the JVM also tears down
 *     the audio system on exit, but we register a shutdown hook for
 *     deterministic cleanup during long-running developer sessions.
 *
 * Volume handling:
 *   - Honours [SettingsManager.soundEffectsEnabled] (mute toggle).
 *   - Maps [SettingsManager.soundVolume] (0..100) to MASTER_GAIN
 *     logarithmically: 1% → -30 dB, 100% → 0 dB. Clips whose mixer
 *     doesn't expose MASTER_GAIN simply skip the volume step.
 */
object SoundManager {

    private const val POOL_SIZE = 4

    private var initialised = false
    private val pool: MutableMap<SoundEvent, ArrayDeque<Clip>> = mutableMapOf()


    /**
     * Generates the WAV stubs (if absent) and pre-loads [POOL_SIZE] clips
     * per [SoundEvent]. Idempotent. Print a single line to stdout if the
     * audio system is unavailable so a Linux user with PulseAudio/PipeWire
     * issues sees a hint rather than silent failures.
     */
    fun init() {
        if (initialised) return
        runCatching {
            SoundLibrary.ensureGenerated()
            SoundEvent.entries.forEach { event ->
                val file = File(SoundLibrary.pathFor(event).toString())
                if (!file.exists()) return@forEach
                val q = ArrayDeque<Clip>(POOL_SIZE)
                repeat(POOL_SIZE) {
                    runCatching {
                        val stream = AudioSystem.getAudioInputStream(file)
                        val clip = AudioSystem.getClip()
                        clip.open(stream)
                        q.add(clip)
                    }
                }
                if (q.isNotEmpty()) pool[event] = q
            }
            Runtime.getRuntime().addShutdownHook(
                Thread({ shutdown() }, "SoundManager-shutdown")
            )
        }.onFailure { cause ->
            // Headless, no sound card, or JVM without javax.sound.
            // Carry on — sound is non-essential.
            System.err.println("[SoundManager] init failed: ${cause.message}")
        }
        initialised = true
    }


    /**
     * Plays the given sound effect at the user-configured volume. Returns
     * immediately; the audio runs on the JVM mixer thread. No-op when
     * not initialised, when the global mute is on, when master volume is
     * 0, or when the clip pool for the event is empty.
     */
    fun play(event: SoundEvent) {
        if (!initialised) return
        if (!SettingsManager.soundEffectsEnabled) return
        val volume = SettingsManager.soundVolume.coerceIn(0, 100)
        if (volume == 0) return
        val list = pool[event] ?: return
        val clip = pickFree(list) ?: list.first()
        runCatching {
            clip.stop()
            clip.framePosition = 0
            applyVolume(clip, volume)
            clip.start()
        }
    }


    /**
     * Returns the first Clip in [list] that isn't currently playing,
     * or `null` if every slot is hot. The caller falls back to the head
     * of the queue when that happens — instant restart will cut the
     * previous play off, which is preferable to dropping the sound.
     */
    private fun pickFree(list: ArrayDeque<Clip>): Clip? {
        for (clip in list) {
            if (!clip.isRunning) return clip
        }
        return null
    }


    private fun applyVolume(clip: Clip, percent: Int) {
        if (!clip.isControlSupported(FloatControl.Type.MASTER_GAIN)) return
        val ctrl = clip.getControl(FloatControl.Type.MASTER_GAIN) as FloatControl
        // 0 disabled upstream; here we only see 1..100. Map 1% → -30 dB,
        // 100% → 0 dB on a log scale so the slider feels natural.
        val scaled = (percent - 1).coerceAtLeast(0) / 99.0
        val db = if (scaled <= 0.0) -30.0 else -30.0 + 30.0 * Math.log10(scaled.coerceAtLeast(1e-4))
        if (db < ctrl.minimum || db > ctrl.maximum) return
        ctrl.value = db.toFloat()
    }


    fun shutdown() {
        pool.values.forEach { q ->
            q.forEach { runCatching { it.stop(); it.close() } }
        }
        pool.clear()
        initialised = false
    }


    @Suppress("unused")
    fun debugPoolSize(event: SoundEvent): Int = pool[event]?.size ?: 0

}
