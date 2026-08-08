package ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import data.MediaFileKind
import data.MediaReferenceToken
import data.MediaService
import data.StreamResolver
import data.isProfile
import data.mediaKindFor
import data.openExternalUrl
import javafx.application.Platform
import javafx.concurrent.Worker
import javafx.event.EventHandler
import javafx.geometry.Pos
import javafx.scene.Scene
import javafx.scene.control.Label
import javafx.scene.layout.StackPane
import javafx.scene.media.Media
import javafx.scene.media.MediaPlayer
import javafx.scene.media.MediaView
import javafx.scene.paint.Color
import javafx.scene.text.Font
import javafx.scene.web.WebEngine
import javafx.scene.web.WebView
import javafx.stage.Stage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import netscape.javascript.JSObject


// ---------------------------------------------------------------------------
// In-app media playback
//
// The play button on a media card opens a small always-on-top player
// window. YouTube / Vimeo / SoundCloud / local files are played NATIVELY
// by JavaFX's MediaPlayer: the bundled yt-dlp binary resolves the media
// to a DIRECT stream URL (e.g. a googlevideo mp4), which JavaFX streams
// with GStreamer. That sidesteps the entire class of WebView-embed
// failures — most notably YouTube's 2025+ embed validation (\"Error 153:
// Video player configuration error\"), which rejects embeds hosted from a
// local document with no real origin. Spotify alone keeps the embedded
// WebView player, because its tracks/albums have no public direct stream
// (yt-dlp cannot resolve them); its embed has no event API either, so
// Spotify shows an indeterminate progress ring.
//
// Progress (position, play/pause/end) flows from the native player's
// listeners — or, for Spotify, not at all — into [MediaPlayerState]; the
// media cards and the mini player paint live progress from it.
//
// JavaFX is a separate toolkit that coexists with Compose/AWT: it is
// started once, lazily, on the first play (never on app startup), and
// torn down via [MediaPlayerController.dispose] on app exit — its thread
// is non-daemon, so without Platform.exit the JVM would hang. If JavaFX
// cannot start (e.g. missing GStreamer/GTK on a Linux box), play degrades
// gracefully to opening the media in the default browser.
// ---------------------------------------------------------------------------


/**
 * Snapshot-backed playback state shared between the JavaFX player window
 * (writer: the native player's listeners / JS bridge on the FX thread)
 * and the Compose media cards (reader: composition). Snapshot state is
 * thread-safe, so the two sides never need to synchronize.
 */
object MediaPlayerState {

    /** Resolved URL of the media currently loaded in the player, or null. */
    var currentUrl by mutableStateOf<String?>(null)

    /** oEmbed title of the loaded media (used as the player window title). */
    var currentTitle by mutableStateOf<String?>(null)

    /** Service of the loaded media — drives the mini player's glyph and
     *  label, which otherwise have no token to read it from. */
    var currentService by mutableStateOf<MediaService?>(null)

    /** True while the media is actually playing (false = paused/stopped). */
    var playing by mutableStateOf(false)

    /** Playback position as a 0..1 fraction of the total duration. */
    var fraction by mutableStateOf(0f)

    /**
     * Millis of the last REAL state/progress event from the player
     * (played / paused / finished / progress). Not snapshot state — no UI
     * reads it; it feeds the \"player never started\" watchdog in
     * MediaReferencesPanel. Reset to 0 whenever playback stops.
     */
    @Volatile
    var lastEventAt: Long = 0L

    fun reset() {
        currentUrl = null
        currentTitle = null
        currentService = null
        playing = false
        fraction = 0f
        lastEventAt = 0L
    }
}


/**
 * Java object exposed to the Spotify embed page as `window.JavaBridge`.
 * Spotify's embed posts no progress events, so this is effectively inert
 * today — it exists so the page's guard `window.JavaBridge` never throws
 * and any future event protocol can be wired in without touching the
 * native path.
 */
private class MediaPlayerBridge {
    fun progress(secs: Double, dur: Double, playing: Boolean, paused: Boolean, finished: Boolean) {
        if (dur > 0) {
            MediaPlayerState.fraction = (secs / dur).toFloat().coerceIn(0f, 1f)
        }
        when {
            finished -> {
                MediaPlayerState.fraction = 1f
                MediaPlayerState.playing = false
            }

            playing -> MediaPlayerState.playing = true
            paused -> MediaPlayerState.playing = false
        }
        if (secs > 0 || dur > 0 || playing || paused || finished) {
            MediaPlayerState.lastEventAt = System.currentTimeMillis()
        }
    }
}


/**
 * Owns the JavaFX toolkit and the two player windows: the native
 * [MediaPlayer] stage (YouTube / Vimeo / SoundCloud / local files) and
 * the Spotify embed [WebView] stage. All JavaFX calls are confined to the
 * FX thread via [Platform.runLater]; the public API is safe to call from
 * any Compose callback.
 */
object MediaPlayerController {

    @Volatile
    private var fxStarted = false

    /** Scope for the stream resolution (network + yt-dlp) that precedes
     *  native playback. Canceled on app exit. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var currentToken: MediaReferenceToken? = null

    // Native (JavaFX MediaPlayer) path.
    private var nativeStage: Stage? = null
    private var mediaView: MediaView? = null
    private var nativePlayer: MediaPlayer? = null

    // Embed (WebView) path — Spotify only.
    private var embedStage: Stage? = null
    private var webEngine: WebEngine? = null

    /**
     * Open (or reuse) the always-on-top player window and start [token]
     * playing. Falls back to the default browser when JavaFX is
     * unavailable, the stream can't be resolved, or the token isn't
     * playable in-app (generic links, profiles). [title] (oEmbed title)
     * becomes the window title.
     */
    fun play(token: MediaReferenceToken, title: String? = null) {
        if (token.isProfile) {
            // Channel / profile pages have nothing to play — open in the
            // browser instead of a dead player window.
            fallbackToBrowser(token)
            return
        }
        currentToken = token
        MediaPlayerState.currentUrl = token.resolveUrl()
        MediaPlayerState.currentTitle = title
        MediaPlayerState.currentService = token.service
        MediaPlayerState.playing = true
        MediaPlayerState.fraction = 0f

        if (runCatching { ensureFxStarted() }.isFailure) {
            fallbackToBrowser(token)
            return
        }

        when (token.service) {
            // Spotify: the only service without a public direct stream —
            // keep its official embed in the WebView window.
            MediaService.SPOTIFY -> {
                val embedUrl = token.playerUrl()
                if (embedUrl == null) {
                    fallbackToBrowser(token)
                    return
                }
                Platform.runLater {
                    runCatching { ensureEmbedStage(embedUrl) }
                        .onFailure { fallbackToBrowser(token) }
                }
            }

            // Local files: the file:// URI IS the stream.
            MediaService.FILE -> {
                val fileUrl = token.playerUrl()
                if (fileUrl == null) {
                    fallbackToBrowser(token)
                    return
                }
                val isVideo = mediaKindFor(fileUrl) == MediaFileKind.VIDEO
                Platform.runLater {
                    runCatching { ensureNativeStage(fileUrl, isVideo) }
                        .onFailure { fallbackToBrowser(token) }
                }
            }

            // Everything else with a stream: resolve it via yt-dlp (off
            // the FX thread — it can take seconds), then play natively.
            MediaService.YOUTUBE, MediaService.VIMEO, MediaService.SOUNDCLOUD -> {
                scope.launch {
                    val stream = StreamResolver.resolveStreamUrl(token)
                    if (stream == null) {
                        fallbackToBrowser(token)
                        return@launch
                    }
                    val isVideo = token.service == MediaService.YOUTUBE ||
                        token.service == MediaService.VIMEO
                    Platform.runLater {
                        runCatching { ensureNativeStage(stream, isVideo) }
                            .onFailure { fallbackToBrowser(token) }
                    }
                }
            }

            MediaService.LINK -> fallbackToBrowser(token)
        }
    }

    /** Pause / resume the current media. Spotify's embed has no command
     *  API, so its in-window player controls handle pause/resume; the
     *  toggle is a no-op there. */
    fun togglePlayPause() {
        val token = currentToken ?: return
        if (token.service == MediaService.SPOTIFY) return
        if (nativePlayer == null) return
        val resume = !MediaPlayerState.playing
        MediaPlayerState.playing = resume
        Platform.runLater {
            val player = nativePlayer ?: return@runLater
            runCatching { if (resume) player.play() else player.pause() }
        }
    }

    /** Stop playback, hide the player window and clear the card state. */
    fun stop() {
        currentToken = null
        MediaPlayerState.reset()
        Platform.runLater {
            runCatching {
                nativePlayer?.stop()
                nativePlayer?.dispose()
                nativePlayer = null
                mediaView?.mediaPlayer = null
                nativeStage?.hide()
                embedStage?.hide()
            }
        }
    }

    /**
     * Bring the always-on-top player window back to the front WITHOUT
     * touching playback — the mini player's \"open player\" action, so a
     * click re-opens the window instead of restarting the media. No-op
     * when the window hasn't been created yet.
     */
    fun showPlayerWindow() {
        Platform.runLater {
            runCatching {
                when {
                    embedStage != null -> {
                        embedStage?.show()
                        embedStage?.toFront()
                    }

                    nativeStage != null -> {
                        nativeStage?.show()
                        nativeStage?.toFront()
                    }
                }
            }
        }
    }

    /**
     * Shut down the JavaFX toolkit. Called on app exit — the FX thread is
     * non-daemon, so without this the JVM would not terminate.
     */
    fun dispose() {
        scope.cancel()
        runCatching {
            nativePlayer?.stop()
            nativePlayer?.dispose()
            nativePlayer = null
            nativeStage?.hide()
            embedStage?.hide()
        }
        runCatching { Platform.exit() }
    }

    private fun ensureFxStarted() {
        if (!fxStarted) {
            synchronized(this) {
                if (!fxStarted) {
                    Platform.startup { }
                    fxStarted = true
                }
            }
        }
    }

    /**
     * Create the native player stage on first use; on reuse, tear down
     * the previous MediaPlayer and play the new stream in the same stage.
     * All state → [MediaPlayerState] updates come from the player's
     * listeners, which also feed the \"never started\" watchdog.
     */
    private fun ensureNativeStage(streamUrl: String, isVideo: Boolean) {
        val existing = nativeStage

        // Release the previous native playback before loading a new one.
        nativePlayer?.stop()
        nativePlayer?.dispose()
        mediaView?.mediaPlayer = null

        val player = MediaPlayer(Media(streamUrl))
        nativePlayer = player

        player.statusProperty().addListener { _, _, status ->
            // TEMP DEBUG (will be removed): prove native playback starts.
            System.err.println("[NATIVE] status=$status stream=${streamUrl.take(60)}")
            when (status) {
                MediaPlayer.Status.PLAYING -> {
                    MediaPlayerState.playing = true
                    touch()
                }

                MediaPlayer.Status.PAUSED, MediaPlayer.Status.STOPPED -> {
                    MediaPlayerState.playing = false
                    touch()
                }

                MediaPlayer.Status.HALTED -> MediaPlayerState.playing = false
                else -> {}
            }
        }
        player.currentTimeProperty().addListener { _, _, current ->
            val total = player.totalDuration
            if (total != null && !total.isUnknown() && total.toSeconds() > 0) {
                MediaPlayerState.fraction =
                    (current.toSeconds() / total.toSeconds()).toFloat().coerceIn(0f, 1f)
            }
            touch()
        }
        player.setOnEndOfMedia {
            MediaPlayerState.playing = false
            MediaPlayerState.fraction = 1f
            touch()
        }
        player.setOnError {
            System.err.println("[MEDIA-ERROR] ${player.error?.message}")
            MediaPlayerState.playing = false
            touch()
        }

        if (existing != null) {
            existing.title = MediaPlayerState.currentTitle ?: existing.title
            if (isVideo) mediaView?.mediaPlayer = player
            existing.show()
            existing.toFront()
            player.play()
            return
        }

        val s = Stage()
        nativeStage = s
        s.title = MediaPlayerState.currentTitle ?: currentToken?.service?.label ?: "Media player"
        s.isAlwaysOnTop = true
        s.centerOnScreen()

        if (isVideo) {
            val view = MediaView(player)
            mediaView = view
            view.fitWidth = 520.0
            view.fitHeight = 340.0
            // `preserveRatio` maps to a private field in javafx-media's
            // Kotlin view, so the explicit setter form is required.
            view.isPreserveRatio = true
            // MediaView is a leaf Node, not a Parent — it can't be the
            // scene root directly.
            s.width = 520.0
            s.height = 340.0
            s.minWidth = 320.0
            s.minHeight = 200.0
            s.scene = Scene(StackPane(view).apply { style = "-fx-background-color: #000;" })
        } else {
            // Audio has no picture — a small dark stage with the title.
            val label = Label(
                MediaPlayerState.currentTitle ?: currentToken?.service?.label ?: "Media player"
            )
            label.textFill = Color.WHITE
            label.font = Font(15.0)
            val root = StackPane(label)
            root.alignment = Pos.CENTER
            root.style = "-fx-background-color: #1a1a1a;"
            s.width = 400.0
            s.height = 90.0
            s.scene = Scene(root)
        }

        s.onCloseRequest = EventHandler { stop() }
        s.show()
        player.play()
    }

    /** Create the Spotify embed window on first use; reload on reuse. */
    private fun ensureEmbedStage(embedUrl: String) {
        val existing = embedStage
        if (existing != null) {
            existing.title = MediaPlayerState.currentTitle ?: existing.title
            webEngine?.loadContent(spotifyEmbedHtml(embedUrl), "text/html")
            existing.show()
            existing.toFront()
            return
        }

        val s = Stage()
        embedStage = s
        s.title = MediaPlayerState.currentTitle ?: currentToken?.service?.label ?: "Media player"
        s.isAlwaysOnTop = true
        s.width = 520.0
        s.height = 340.0
        s.minWidth = 320.0
        s.minHeight = 200.0
        s.centerOnScreen()

        val web = WebView()
        val engine = web.engine
        webEngine = engine

        // Expose the Java bridge to the page — but ONLY once the page has
        // finished loading. Calling executeScript on the initial
        // (about:blank) document races the native WebKit bridge and can
        // hard-crash the JVM (SIGSEGV in JNIHandles::resolve_impl inside
        // twkExecuteScript) on the JavaFX Application Thread. The
        // loadWorker SUCCEEDED listener fires once the document is
        // actually parsed, which is the state in which executeScript is
        // guaranteed safe. Page scripts guard every use with
        // `window.JavaBridge`, so anything arriving before the bridge is
        // set is harmlessly dropped.
        val bridge = MediaPlayerBridge()
        engine.loadWorker.stateProperty().addListener { _, _, state ->
            if (state == Worker.State.SUCCEEDED) {
                runCatching {
                    (engine.executeScript("window") as JSObject).setMember("JavaBridge", bridge)
                }
            }
        }

        s.scene = Scene(web)
        s.onCloseRequest = EventHandler { stop() }
        s.show()
        engine.loadContent(spotifyEmbedHtml(embedUrl), "text/html")
    }

    private fun fallbackToBrowser(token: MediaReferenceToken) {
        currentToken = null
        MediaPlayerState.reset()
        token.resolveUrl()?.let { openExternalUrl(it) }
    }

    private fun touch() {
        MediaPlayerState.lastEventAt = System.currentTimeMillis()
    }
}


/**
 * The Spotify embed hosted in a full-bleed iframe. Spotify's embed has no
 * postMessage event protocol and no public command API, so the page is a
 * plain iframe — playback state for Spotify stays an indeterminate ring in
 * the UI, and pause/resume happen in the embed's own controls.
 */
private fun spotifyEmbedHtml(embedUrl: String): String = """
    <!DOCTYPE html>
    <html>
    <head>
    <meta charset="utf-8">
    <style>
      html, body { margin: 0; padding: 0; height: 100%; background: #000; overflow: hidden; }
      iframe { width: 100%; height: 100%; border: 0; display: block; }
    </style>
    </head>
    <body>
    <iframe id="player" src="$embedUrl"
            referrerpolicy="strict-origin-when-cross-origin"
            allow="autoplay; encrypted-media; picture-in-picture; fullscreen"
            allowFullscreen></iframe>
    </body>
    </html>
""".trimIndent()
