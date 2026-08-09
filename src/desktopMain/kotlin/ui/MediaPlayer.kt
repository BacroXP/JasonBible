@file:Suppress("DEPRECATION", "MarkedForRemoval")
// JavaFX WebEngine.executeScript returns `netscape.javascript.JSObject`
// for JS objects, and that JDK module is deprecated for removal — but
// JavaFX still uses it for the JS bridge and offers no replacement, so
// the suppression (kept tightly scoped to this file) is required.

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
import javafx.embed.swing.JFXPanel
import javafx.geometry.Pos
import javafx.scene.Parent
import javafx.scene.Scene
import javafx.scene.layout.StackPane
import javafx.scene.media.Media
import javafx.scene.media.MediaPlayer
import javafx.scene.media.MediaView
import javafx.scene.paint.Color
import javafx.scene.web.WebEngine
import javafx.scene.web.WebView
import javafx.util.Duration
import netscape.javascript.JSObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch


// ---------------------------------------------------------------------------
// In-app media playback
//
// The play button on a media card starts playback INSIDE the main app
// window: the Compose side (ui.EmbeddedPlayer) hosts a JFXPanel via
// Compose's SwingPanel bridge, and this controller mounts the JavaFX
// content onto it. YouTube / Vimeo / SoundCloud / local files are played
// NATIVELY by JavaFX's MediaPlayer: the bundled yt-dlp binary resolves
// the media to a DIRECT stream URL (e.g. a googlevideo mp4), which JavaFX
// streams with GStreamer. That sidesteps the entire class of WebView-embed
// failures — most notably YouTube's 2025+ embed validation ("Error 153:
// Video player configuration error"), which rejects embeds hosted from a
// local document with no real origin. Spotify alone keeps a WebView
// player, because its tracks/albums have no public direct stream (yt-dlp
// cannot resolve them); its embed has no event API either, so Spotify
// shows an indeterminate progress state.
//
// Progress (position, play/pause/end) flows from the native player's
// listeners — or, for Spotify, not at all — into [MediaPlayerState]; the
// media cards and the embedded player paint live progress from it.
//
// JavaFX is a separate toolkit that coexists with Compose/AWT: it is
// started once, lazily, on the first play (never on app startup), and
// torn down via [MediaPlayerController.dispose] on app exit — its thread
// is non-daemon, so without Platform.exit the JVM would hang. If JavaFX
// cannot start (e.g. missing GStreamer/GTK on a Linux box), play degrades
// gracefully to opening the media in the default browser.
// ---------------------------------------------------------------------------


/**
 * Snapshot-backed playback state shared between the JavaFX player (writer:
 * the native player's listeners / JS bridge on the FX thread) and the
 * Compose media cards / embedded player (reader: composition). Snapshot
 * state is thread-safe, so the two sides never need to synchronize.
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
     * reads it; it feeds the "player never started" watchdog in
     * MediaReferencesPanel. Reset to 0 whenever playback stops.
     */
    @Volatile
    var lastEventAt: Long = 0L

    /**
     * ARGB color of the app surface behind the embedded player, pushed by
     * the Compose side from MaterialTheme.colorScheme.surface. The JavaFX
     * scene uses it as its fill so the load-time media canvas (before the
     * video / widget has painted) blends into the window instead of
     * flashing black or white.
     */
    var surfaceArgb by mutableStateOf(0xFF1E1E1EL)

    /**
     * True while the native player should loop the current media forever
     * (JavaFX cycleCount = INDEFINITE). A user preference toggled from
     * the player controls — deliberately NOT reset by [reset], so it
     * survives stopping one clip and playing the next.
     */
    var looping by mutableStateOf(false)

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
    // Called from the Spotify embed page via `window.JavaBridge.progress`
    // — invisible to static analysis, hence the suppression.
    @Suppress("unused")
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
 * Owns the JavaFX toolkit and the embedded player content: the native
 * [MediaPlayer] (YouTube / Vimeo / SoundCloud / local files) and the
 * Spotify embed [WebView]. The content is mounted onto a [JFXPanel] host
 * that the Compose side creates (ui.EmbeddedPlayer via SwingPanel), so
 * playback renders inside the main window rather than a separate OS
 * window. All JavaFX calls are confined to the FX thread via
 * [Platform.runLater]; the public API is safe to call from any Compose
 * callback.
 */
object MediaPlayerController {

    @Volatile
    private var fxStarted = false

    /** Scope for the stream resolution (network + yt-dlp) that precedes
     *  native playback. Canceled on app exit. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var currentToken: MediaReferenceToken? = null

    // Embedded host: the JFXPanel created by the Compose-side SwingPanel
    // (ui.EmbeddedPlayer). All playback renders INSIDE the main window.
    @Volatile
    private var hostPanel: JFXPanel? = null

    /** The JavaFX scene root currently built for the host (a MediaView
     *  for native media, a WebView for Spotify). Kept so a host panel
     *  that composes AFTER play() still receives the content (attachHost
     *  pushes it). FX-thread confined. */
    private var currentRoot: Parent? = null

    // Native (JavaFX MediaPlayer) content.
    private var nativePlayer: MediaPlayer? = null
    private var mediaView: MediaView? = null

    // Embed (WebView) content — Spotify only.
    private var webView: WebView? = null
    private var webEngine: WebEngine? = null

    /** Design size of the content last mounted ([mount]) — used as the
     *  fallback for a host panel that attaches before it is laid out, so
     *  e.g. the Spotify widget keeps its compact aspect instead of
     *  flashing the video design size. FX-thread confined. */
    private var fallbackSize: Pair<Double, Double> = 480.0 to 270.0

    /**
     * Open the embedded player and start [token] playing. Falls back to
     * the default browser when JavaFX is unavailable, the stream can't be
     * resolved, or the token isn't playable in-app (generic links,
     * profiles). [title] (oEmbed title) labels the player.
     */
    fun play(token: MediaReferenceToken, title: String? = null) {
        if (token.isProfile) {
            // Channel / profile pages have nothing to play — open in the
            // browser instead of a dead player panel.
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
            // keep its official embed in the embedded WebView.
            MediaService.SPOTIFY -> {
                val embedUrl = token.playerUrl()
                if (embedUrl == null) {
                    fallbackToBrowser(token)
                    return
                }
                Platform.runLater {
                    runCatching { ensureEmbedContent(embedUrl) }
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
                    runCatching { ensureNativeContent(fileUrl, isVideo) }
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
                        runCatching { ensureNativeContent(stream, isVideo) }
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

    /** Toggle infinite looping for the native player. Applies to the
     *  current media immediately and is remembered for the next clip
     *  ([MediaPlayerState.looping] survives [stop]). Enabling loop on a
     *  clip that already finished rewinds and restarts it, so the toggle
     *  always does something visible. */
    fun setLooping(loop: Boolean) {
        MediaPlayerState.looping = loop
        Platform.runLater {
            val player = nativePlayer ?: return@runLater
            runCatching {
                val cycle = if (loop) MediaPlayer.INDEFINITE else 1
                if (player.cycleCount != cycle) player.cycleCount = cycle
                // A finished one-shot player sits STOPPED at the end — a
                // changed cycleCount alone doesn't rewind it, so restart
                // it to actually begin looping.
                if (loop && player.status == MediaPlayer.Status.STOPPED) {
                    player.seek(Duration.ZERO)
                    player.play()
                }
            }
        }
    }

    /** Stop playback, clear the embedded player and the card state. */
    fun stop() {
        currentToken = null
        MediaPlayerState.reset()
        Platform.runLater {
            runCatching {
                nativePlayer?.stop()
                nativePlayer?.dispose()
                nativePlayer = null
                mediaView?.mediaPlayer = null
                mediaView = null
                webEngine?.load(null)
                webView = null
                // Blank the host so a stale frame doesn't linger while the
                // Compose side tears the panel down.
                currentRoot = null
                hostPanel?.scene = Scene(StackPane().apply {
                    style = "-fx-background-color: #000;"
                })
            }
        }
    }

    /**
     * Register the [JFXPanel] host created by the Compose-side SwingPanel
     * (ui.EmbeddedPlayer). If content was already built (play ran before
     * the panel composed), it is pushed onto the panel now.
     */
    fun attachHost(panel: JFXPanel) {
        hostPanel = panel
        Platform.runLater {
            val root = currentRoot ?: return@runLater
            // Idempotent: SwingPanel's `update` fires on every
            // recomposition (e.g. native progress ticks), and re-parenting
            // the root into a fresh Scene each time would be wasteful — and
            // for the Spotify WebView could detach/reload the page.
            if (panel.scene?.root === root) return@runLater
            runCatching {
                val (w, h) = sceneSize(panel, fallbackSize.first, fallbackSize.second)
                panel.scene = newScene(root, w, h)
            }
        }
    }

    /** The host panel is leaving composition — drop the reference. */
    fun detachHost(panel: JFXPanel) {
        if (hostPanel === panel) hostPanel = null
    }

    /**
     * Called by the Compose side when the theme surface color changes:
     * records it for the next scene and updates the live scene fill so
     * the transparent media corners keep blending into the card behind.
     * The Spotify embed page is recolored too (its background is the
     * surface color — see [spotifyEmbedHtml]); Linux WebView cannot do
     * page transparency, so the color must be explicit. The WebView's
     * own backing fill ([WebView.pageFill]) is synced as well — it
     * defaults to white and is painted behind the page on Linux.
     */
    fun updateSurfaceColor(argb: Long) {
        MediaPlayerState.surfaceArgb = argb
        Platform.runLater {
            hostPanel?.scene?.fill = fillColor(argb)
            // The WebView's backing fill is white by default and is
            // painted around the page content on Linux — keep it in sync
            // so the widget's corners blend into the window.
            webView?.pageFill = fillColor(argb)
            val engine = webEngine
            if (engine != null && engine.loadWorker.state == Worker.State.SUCCEEDED) {
                runCatching { recolorPage(engine, surfaceColorCss(argb)) }
            }
        }
    }

    /** Re-paint the Spotify embed page's canvas with the app surface
     *  color: the page's html/body background. The WebView paints WHITE
     *  wherever the page's own background is missing (Linux), so the
     *  color must always be explicit. FX thread only. */
    private fun recolorPage(engine: WebEngine, css: String) {
        engine.executeScript(
            "document.body.style.background='$css'; " +
                "document.documentElement.style.background='$css';"
        )
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
            webEngine?.load(null)
            webView = null
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
     * Build the native JavaFX content ([MediaPlayer] + [MediaView]) for
     * [streamUrl] and mount it on the embedded host. All state →
     * [MediaPlayerState] updates come from the player's listeners, which
     * also feed the "never started" watchdog.
     */
    private fun ensureNativeContent(streamUrl: String, isVideo: Boolean) {
        // Release the previous native playback / embed content.
        nativePlayer?.stop()
        nativePlayer?.dispose()
        nativePlayer = null
        mediaView?.mediaPlayer = null
        mediaView = null
        webEngine?.load(null)
        webView = null

        val player = MediaPlayer(Media(streamUrl))
        nativePlayer = player
        // Loop forever when the toggle is on: INDEFINITE makes the player
        // restart the clip automatically at the end of every cycle.
        player.cycleCount = if (MediaPlayerState.looping) MediaPlayer.INDEFINITE else 1

        // True once this player has actually started playing — drives the
        // error fallback below (fall back only when playback NEVER
        // started, not on a mid-stream blip).
        var startedPlaying = false

        // All listeners guard on `nativePlayer === player`: a player that
        // has been disposed by a newer play() call must not clobber the new
        // player's state with its final STOPPED status or last progress tick.
        player.statusProperty().addListener { _, _, status ->
            if (nativePlayer !== player) return@addListener
            when (status) {
                MediaPlayer.Status.PLAYING -> {
                    startedPlaying = true
                    MediaPlayerState.playing = true
                    touch()
                }

                MediaPlayer.Status.PAUSED -> {
                    MediaPlayerState.playing = false
                    touch()
                }

                MediaPlayer.Status.STOPPED -> {
                    // A looped player (cycleCount INDEFINITE) auto-restarts
                    // the next cycle — if WebKitGTK reports a transitional
                    // STOPPED at the boundary, don't let it kill the
                    // playing state (and hide the player panel) mid-loop.
                    if (!MediaPlayerState.looping) {
                        MediaPlayerState.playing = false
                    }
                    touch()
                }

                MediaPlayer.Status.HALTED -> MediaPlayerState.playing = false
                else -> {}
            }
        }
        player.currentTimeProperty().addListener { _, _, current ->
            if (nativePlayer !== player) return@addListener
            val total = player.totalDuration
            if (total != null && !total.isUnknown() && total.toSeconds() > 0) {
                MediaPlayerState.fraction =
                    (current.toSeconds() / total.toSeconds()).toFloat().coerceIn(0f, 1f)
            }
            touch()
        }
        player.setOnEndOfMedia {
            if (nativePlayer !== player) return@setOnEndOfMedia
            // Looping: the player auto-restarts the next cycle — keep
            // claiming playback and don't paint the finished fraction.
            if (MediaPlayerState.looping) {
                touch()
                return@setOnEndOfMedia
            }
            MediaPlayerState.playing = false
            MediaPlayerState.fraction = 1f
            touch()
        }
        player.setOnError {
            System.err.println("[MEDIA-ERROR] ${player.error?.message}")
            MediaPlayerState.playing = false
            // A stale error from a player that has since been replaced by
            // a newer play() call — the error belongs to the old stream,
            // so ignore it (do not feed the watchdog for the active one).
            if (nativePlayer !== player) return@setOnError
            // Playback failed AFTER starting (e.g. a transient network
            // stall): keep the stop-and-hold behavior — don't yank the
            // user to the browser mid-stream.
            if (startedPlaying) {
                touch()
                return@setOnError
            }
            // Playback never started (missing codec / region lock / dead
            // stream). Fall back to the default browser instead of leaving
            // a dead black panel — the browser can usually play what
            // JavaFX's bundled codecs cannot. Idempotent: fallbackToBrowser
            // nulls currentToken, so a second error event does nothing.
            val token = currentToken ?: return@setOnError
            nativePlayer?.stop()
            nativePlayer?.dispose()
            nativePlayer = null
            mediaView?.mediaPlayer = null
            mediaView = null
            currentRoot = null
            fallbackToBrowser(token)
        }

        val root: Parent = if (isVideo) {
            val view = MediaView(player)
            mediaView = view
            // `preserveRatio` maps to a private field in javafx-media's
            // Kotlin view, so the explicit setter form is required.
            view.isPreserveRatio = true
            view.fitWidth = 520.0
            view.fitHeight = 340.0
            // MediaView is a leaf Node, not a Parent — it can't be the
            // scene root directly. The video fills the surface edge to
            // edge with SQUARE corners (no rounded clip): a rounded clip
            // would cut into the video's corners and reveal the pane's
            // background — the "canvas" — around them.
            StackPane(view).apply {
                alignment = Pos.CENTER
                style = "-fx-background-color: #000;"
            }
        } else {
            // Audio has no picture — a dark pane keeps the host clean;
            // the app shows a compact card with the title and controls.
            StackPane().apply { style = "-fx-background-color: #1a1a1a;" }
        }
        mount(root)
        player.play()
    }

    /** Build the Spotify embed [WebView] content and mount it on the host. */
    private fun ensureEmbedContent(embedUrl: String) {
        // Release the previous native playback / embed content.
        nativePlayer?.stop()
        nativePlayer?.dispose()
        nativePlayer = null
        mediaView?.mediaPlayer = null
        mediaView = null
        webEngine?.load(null)
        webView = null

        val web = WebView()
        webView = web
        val engine = web.engine
        webEngine = engine
        // Linux WebView CANNOT render a transparent page: where the embed's
        // page / iframe has transparent margins, WebKit paints an opaque
        // WHITE background instead of letting the scene fill show through.
        // So the page gets an explicit background in the app's surface
        // color.
        //
        // The critical piece is the WebView's BACKING FILL: `pageFill`
        // (JavaFX 18+; the `-fx-page-fill` / `setPageFill` API) defaults
        // to WHITE, and on Linux WebKitGTK paints it behind the page
        // content wherever the page's own background does not cover the
        // viewport — e.g. the frames while the document parses. Node-level
        // `-fx-background-color` does NOT change this fill on Linux; only
        // `pageFill` does. Filling it with the app's surface color makes
        // the canvas behind the widget match the window, so no white can
        // ever bleed through. (The widget itself fills the canvas edge to
        // edge — no rounding — so the fill only shows while loading.)
        web.pageFill = fillColor(MediaPlayerState.surfaceArgb)
        web.setStyle("-fx-background-color: ${surfaceColorCss(MediaPlayerState.surfaceArgb)};")

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
                    // Re-apply the CURRENT surface color now that the
                    // document exists. The page may have been built with
                    // the default color when the Compose side pushed the
                    // theme surface AFTER this WebView was created (the
                    // two run on different threads) — re-painting here
                    // guarantees the rounded corners match the window
                    // regardless of the ordering.
                    recolorPage(engine, surfaceColorCss(MediaPlayerState.surfaceArgb))
                }
            }
        }

        // 400x80 fallback size: the widget keeps its compact aspect even
        // if the host panel isn't composed yet (the video design size
        // would stretch it until JFXPanel resizes). The widget fills the
        // whole canvas edge to edge with SQUARE corners — any rounding
        // here would cut into the widget's corners and reveal the
        // surface-colored canvas behind them.
        mount(
            StackPane(web).apply {
                style = "-fx-background-color: ${surfaceColorCss(MediaPlayerState.surfaceArgb)};"
            },
            fallbackWidth = 400.0,
            fallbackHeight = 80.0
        )
        engine.loadContent(
            spotifyEmbedHtml(embedUrl, surfaceColorCss(MediaPlayerState.surfaceArgb)),
            "text/html"
        )
    }

    /** Set [root] as the host panel's scene content. No-op without a host
     *  — attachHost pushes it once the panel composes. FX thread only. */
    private fun mount(
        root: Parent,
        fallbackWidth: Double = 480.0,
        fallbackHeight: Double = 270.0
    ) {
        currentRoot = root
        fallbackSize = fallbackWidth to fallbackHeight
        val panel = hostPanel ?: return
        runCatching {
            val (w, h) = sceneSize(panel, fallbackWidth, fallbackHeight)
            panel.scene = newScene(root, w, h)
        }
    }

    /** A scene for the host panel, filled with the app's surface color so
     *  transparent media corners blend into the rounded card. FX thread
     *  only. */
    private fun newScene(root: Parent, w: Double, h: Double): Scene =
        Scene(root, w, h, fillColor(MediaPlayerState.surfaceArgb))

    /** Surface color as a JavaFX paint (black on any parse failure). */
    private fun fillColor(argb: Long): Color = runCatching {
        Color.web(String.format("#%08X", argb))
    }.getOrDefault(Color.BLACK)

    /** Surface color as an opaque CSS hex (`#rrggbb`) for the embed page. */
    private fun surfaceColorCss(argb: Long): String =
        String.format("#%06X", argb and 0xFFFFFF)

    /** Scene size for the host panel. Falls back to the design size before
     *  the panel has been laid out (width/height are 0 at creation) — a
     *  0×0 scene would clip the media; JFXPanel resizes the scene to the
     *  panel once it is laid out. FX thread only. */
    private fun sceneSize(
        panel: JFXPanel,
        fallbackWidth: Double = 480.0,
        fallbackHeight: Double = 270.0
    ): Pair<Double, Double> =
        if (panel.width > 0 && panel.height > 0) {
            panel.width.toDouble() to panel.height.toDouble()
        } else {
            fallbackWidth to fallbackHeight
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
 * plain iframe — playback state for Spotify stays an indeterminate state
 * in the UI, and pause/resume happen in the embed's own controls.
 *
 * [backgroundColor] is the app's surface color. It is REQUIRED (not
 * transparent) because Linux WebView cannot render page transparency —
 * transparent page areas are painted opaque white. Giving the page the
 * app's surface color makes the load-time canvas blend into the window
 * instead of flashing white.
 *
 * The iframe fills the page edge to edge with SQUARE corners. There is
 * deliberately NO border-radius: rounding the canvas would cut into the
 * widget's corners and reveal the page background — the visible "canvas"
 * patch — behind them.
 */
private fun spotifyEmbedHtml(embedUrl: String, backgroundColor: String): String = """
    <!DOCTYPE html>
    <html>
    <head>
    <meta charset="utf-8">
    <style>
      html, body { margin: 0; padding: 0; height: 100%; background: $backgroundColor; overflow: hidden; }
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
