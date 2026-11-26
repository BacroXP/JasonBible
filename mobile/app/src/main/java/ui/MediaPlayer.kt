package ui

import android.media.MediaPlayer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import data.MediaFileKind
import data.MediaReferenceToken
import data.MediaService
import data.isProfile
import data.mediaKindFor
import data.openExternalUrl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File


// ---------------------------------------------------------------------------
// Playback state shared by the media cards and the embedded player.
//
// The desktop build drove this from JavaFX (native MediaPlayer + WebView on
// a JFXPanel). On Android the same state is written by two backends:
//   - android.media.MediaPlayer for local audio files,
//   - the WebView embeds (YouTube / Vimeo / Spotify / SoundCloud) for
//     streaming media, which keep the services' own official players.
// Every public field below keeps its desktop name so the shared UI reads it
// unchanged.
// ---------------------------------------------------------------------------

object MediaPlayerState {

    /** Resolved URL of the media currently loaded in the player, or null. */
    var currentUrl by mutableStateOf<String?>(null)

    /** oEmbed title of the loaded media (used as the player window title). */
    var currentTitle by mutableStateOf<String?>(null)

    /** Service of the loaded media — drives the mini player's glyph/label. */
    var currentService by mutableStateOf<MediaService?>(null)

    /** True while the media is actually playing (false = paused/stopped). */
    var playing by mutableStateOf(false)

    /** Playback position as a 0..1 fraction of the total duration. */
    var fraction by mutableStateOf(0f)

    /** Millis of the last real progress event (feeds the start watchdog). */
    @Volatile
    var lastEventAt: Long = 0L

    /** ARGB color of the app surface behind the player (embed background). */
    var surfaceArgb by mutableStateOf(0xFF1E1E1EL)

    /** True while the current media should loop. Survives stop(). */
    var looping by mutableStateOf(false)

    /** Embed URL rendered by the WebView card (null for local files). */
    var embedUrl by mutableStateOf<String?>(null)

    /** Local file path played by MediaPlayer / VideoView (null for embeds). */
    var localPath by mutableStateOf<String?>(null)

    /** True when [localPath] is a video (rendered by a VideoView). */
    var localIsVideo by mutableStateOf(false)

    fun reset() {
        currentUrl = null
        currentTitle = null
        currentService = null
        playing = false
        fraction = 0f
        lastEventAt = 0L
        embedUrl = null
        localPath = null
        localIsVideo = false
    }
}


/**
 * Plays media inside the app. All methods are safe to call from Compose
 * callbacks; the actual work happens on the main/IO threads.
 */
object MediaPlayerController {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var player: MediaPlayer? = null
    private var progressJob: Job? = null

    /**
     * Injected by ui.EmbeddedPlayer while a WebView card is composed. The
     * controller only sends "play"/"pause" commands through it.
     */
    var embedControl: ((String) -> Unit)? = null

    /** Injected by ui.EmbeddedPlayer for local video (VideoView). */
    var videoControl: ((String) -> Unit)? = null


    /**
     * Start [token]. Streaming services use their official embeds; local
     * files play through MediaPlayer (audio) or VideoView (video).
     * Profiles / generic links open in the browser, like on the desktop.
     */
    fun play(token: MediaReferenceToken, title: String? = null) {
        if (token.isProfile) {
            fallbackToBrowser(token)
            return
        }
        stop()
        val url = token.resolveUrl()
        MediaPlayerState.currentUrl = url
        MediaPlayerState.currentTitle = title
        MediaPlayerState.currentService = token.service
        MediaPlayerState.playing = true
        MediaPlayerState.fraction = 0f
        MediaPlayerState.lastEventAt = System.currentTimeMillis()

        when (token.service) {
            MediaService.FILE -> {
                val path = token.playerUrl()?.removePrefix("file://")
                if (path == null) {
                    fallbackToBrowser(token)
                    return
                }
                MediaPlayerState.localPath = path
                MediaPlayerState.localIsVideo = mediaKindFor(path) == MediaFileKind.VIDEO
                if (MediaPlayerState.localIsVideo) {
                    // The card's VideoView starts playback when it composes.
                    videoControl?.invoke("play")
                } else {
                    startAudio(path)
                }
            }

            else -> {
                // Every streaming service keeps its own official embed,
                // exactly like the desktop's WebView path for Spotify.
                val embed = token.playerUrl() ?: url
                if (embed == null) {
                    fallbackToBrowser(token)
                    return
                }
                MediaPlayerState.embedUrl = embed
            }
        }
    }


    fun togglePlayPause() {
        val nowPlaying = !MediaPlayerState.playing
        when {
            MediaPlayerState.embedUrl != null -> {
                embedControl?.invoke(if (nowPlaying) "play" else "pause")
                MediaPlayerState.playing = nowPlaying
                MediaPlayerState.lastEventAt = System.currentTimeMillis()
            }

            MediaPlayerState.localIsVideo -> {
                videoControl?.invoke(if (nowPlaying) "play" else "pause")
                MediaPlayerState.playing = nowPlaying
                MediaPlayerState.lastEventAt = System.currentTimeMillis()
            }

            else -> player?.let { media ->
                runCatching {
                    val wasPlaying = media.isPlaying
                    if (wasPlaying) media.pause() else media.start()
                    MediaPlayerState.playing = !wasPlaying
                    MediaPlayerState.lastEventAt = System.currentTimeMillis()
                }
            }
        }
    }


    fun setLooping(loop: Boolean) {
        MediaPlayerState.looping = loop
        player?.let { runCatching { it.isLooping = loop } }
    }


    fun stop() {
        progressJob?.cancel()
        progressJob = null
        runCatching {
            player?.let { media ->
                if (media.isPlaying) media.stop()
                media.release()
            }
        }
        player = null
        embedControl = null
        videoControl = null
        MediaPlayerState.reset()
    }


    fun dispose() {
        stop()
    }


    /** Kept for source compatibility with the desktop surface-color push. */
    fun updateSurfaceColor(argb: Long) {
        MediaPlayerState.surfaceArgb = argb
    }


    // ------------------------------------------------------------------
    // Local audio
    // ------------------------------------------------------------------

    private fun startAudio(path: String) {
        runCatching {
            val media = MediaPlayer()
            media.setDataSource(File(path).absolutePath)
            media.isLooping = MediaPlayerState.looping
            media.setOnPreparedListener { prepared ->
                runCatching { prepared.start() }
                MediaPlayerState.playing = true
                MediaPlayerState.lastEventAt = System.currentTimeMillis()
                watchProgress(prepared)
            }
            media.setOnCompletionListener {
                MediaPlayerState.fraction = 1f
                MediaPlayerState.playing = false
                MediaPlayerState.lastEventAt = System.currentTimeMillis()
            }
            media.setOnErrorListener { _, _, _ ->
                MediaPlayerState.playing = false
                true
            }
            media.prepareAsync()
            player = media
        }.onFailure {
            MediaPlayerState.playing = false
            fallbackToBrowserFromPath(path)
        }
    }


    private fun watchProgress(media: MediaPlayer) {
        progressJob?.cancel()
        progressJob = scope.launch {
            while (true) {
                val duration = runCatching { media.duration }.getOrDefault(0)
                val position = runCatching { media.currentPosition }.getOrDefault(0)
                if (duration > 0 && position >= 0) {
                    MediaPlayerState.fraction =
                        (position.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
                    MediaPlayerState.lastEventAt = System.currentTimeMillis()
                }
                if (runCatching { media.isPlaying }.getOrDefault(false).not() &&
                    MediaPlayerState.playing.not()
                ) {
                    break
                }
                delay(500)
            }
        }
    }


    private fun fallbackToBrowser(token: MediaReferenceToken) {
        MediaPlayerState.reset()
        token.resolveUrl()?.let { openExternalUrl(it) }
    }


    private fun fallbackToBrowserFromPath(path: String) {
        MediaPlayerState.reset()
        openExternalUrl(path)
    }
}
