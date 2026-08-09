package data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.LinkedHashMap
import java.util.concurrent.TimeUnit


/**
 * Resolves playable media tokens to DIRECT stream URLs (e.g. a YouTube
 * video's googlevideo.com mp4) by shelling out to the yt-dlp binary that
 * is bundled into the app at build time — the same approach XR3Player and
 * most desktop media players use. The direct stream is then played by
 * JavaFX's native MediaPlayer instead of an embedded WebView player,
 * which sidesteps YouTube's 2025+ embed validation ("Error 153: Video
 * player configuration error"): YouTube now rejects embeds whose page has
 * no real origin, exactly the player window's situation. Results are
 * cached per resolved URL for the session (failed resolutions cached as
 * null too, so a dead link isn't re-resolved on every play).
 */
object StreamResolver {

    /** Cap on the cache, mirroring the other media caches. */
    private const val MAX_ENTRIES = 64

    /** A resolver binary must be at least this big to be trusted — the
     *  real standalone builds are multi-megabyte, so a zero/partial
     *  download indicates a failed fetch and is re-extracted. */
    private const val MIN_BINARY_BYTES = 1_000_000L

    /**
     * yt-dlp format selection: combined mp4 first (JavaFX plays
     * H.264/AAC natively via GStreamer), then m4a audio, then whatever
     * is best. A SINGLE combined format is required — separate
     * video+audio streams would need ffmpeg to merge, which is not
     * bundled.
     */
    private val FORMAT_SELECTION = "best[ext=mp4]/best[ext=m4a]/best"

    /** Overall cap on a single resolution (yt-dlp needs to fetch the
     *  page, decipher signatures and pick a format). */
    private const val RESOLVE_TIMEOUT_SECONDS = 30L

    private val cache = object : LinkedHashMap<String, String?>(32, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, String?>
        ): Boolean = size > MAX_ENTRIES
    }

    @Synchronized
    private fun isCached(url: String): Boolean = cache.containsKey(url)

    @Synchronized
    private fun cached(url: String): String? = cache[url]

    @Synchronized
    private fun put(url: String, stream: String?) {
        cache[url] = stream
    }

    /**
     * Direct playable stream URL for a media token, or null when the
     * token isn't streamable (generic links / profiles), yt-dlp is
     * unavailable, or the resolution failed. Runs off the main thread;
     * results are cached per resolved URL.
     */
    suspend fun resolveStreamUrl(token: MediaReferenceToken): String? {
        val url = token.resolveUrl() ?: return null
        if (isCached(url)) return cached(url)
        val stream = withContext(Dispatchers.IO) { runYtDlp(url) }
        put(url, stream)
        return stream
    }

    /**
     * Locate the bundled yt-dlp for THIS OS, extracting it from the app
     * jar into the user data directory on first use (a ProcessBuilder
     * cannot exec a binary that lives inside a jar). Returns null when
     * the bundle is missing or failed to extract.
     */
    internal fun locateBinary(): String? {
        val os = System.getProperty("os.name").lowercase()
        val resourceName = when {
            os.contains("win") -> "/bin/yt-dlp.exe"
            os.contains("mac") -> "/bin/yt-dlp_macos"
            // The standalone Linux build ships as `yt-dlp_linux`.
            else -> "/bin/yt-dlp_linux"
        }
        val targetName = if (os.contains("win")) "yt-dlp.exe" else "yt-dlp"
        val target = Path.of(
            System.getProperty("user.home"),
            ".bibleapp",
            "bin"
        ).resolve(targetName)

        if (Files.isRegularFile(target) && Files.size(target) >= MIN_BINARY_BYTES) {
            return target.toString()
        }
        return runCatching {
            Files.createDirectories(target.parent)
            val input = StreamResolver::class.java.getResourceAsStream(resourceName)
                ?: return null
            input.use { Files.copy(it, target, StandardCopyOption.REPLACE_EXISTING) }
            target.toFile().setExecutable(true, true)
            if (Files.size(target) >= MIN_BINARY_BYTES) target.toString() else null
        }.getOrNull()
    }

    /**
     * Run `yt-dlp --get-url` for [url] and return the first clean stream
     * URL line (the combined format selection yields exactly one). Any
     * failure — binary missing, timeout, yt-dlp error — returns null so
     * the caller can fall back to the browser.
     */
    private fun runYtDlp(url: String): String? {
        val binary = locateBinary() ?: return null
        return runCatching {
            val process = ProcessBuilder(
                binary,
                "--no-warnings",
                "--no-playlist",
                "--get-url",
                "-f", FORMAT_SELECTION,
                url
            ).redirectErrorStream(true).start()

            val output = StringBuilder()
            val reader = Thread {
                try {
                    process.inputStream.bufferedReader().use { output.append(it.readText()) }
                } catch (_: Exception) {
                    // Reading is best-effort; waitFor below is the real gate.
                }
            }
            reader.isDaemon = true
            reader.start()

            val finished = process.waitFor(RESOLVE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                return null
            }
            reader.join(2000)

            output.lineSequence()
                .map { it.trim() }
                .firstOrNull {
                    it.isNotEmpty() &&
                        !it.startsWith("[") &&
                        !it.startsWith("WARNING") &&
                        !it.startsWith("ERROR")
                }
        }.getOrNull()
    }
}
