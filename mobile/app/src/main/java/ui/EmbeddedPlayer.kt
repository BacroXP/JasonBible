package ui

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.VideoView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import data.MediaFileKind
import data.MediaService
import data.mediaKindFor
import data.openExternalUrl
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds


/**
 * The embedded media player, pinned above the app content. On Android the
 * visual variants render the services' official embeds in a [WebView]
 * (YouTube / Vimeo / Spotify / SoundCloud keep their own players), and
 * local files play through a [VideoView] (video) or
 * [android.media.MediaPlayer] (audio, compact card).
 *
 * The card chrome is identical to the desktop build: the same
 * [PlayerCard] surface, glyph tile, accent label and controls, so the
 * player matches the rest of the app.
 */
@Composable
internal fun EmbeddedPlayer(modifier: Modifier = Modifier) {
    val url = MediaPlayerState.currentUrl ?: return
    val service = MediaPlayerState.currentService
    val title = MediaPlayerState.currentTitle
    val playing = MediaPlayerState.playing
    val fraction = MediaPlayerState.fraction

    // A clip that ran to completion keeps `currentUrl` set but is no
    // longer playing — hide the panel so it doesn't linger on a dead card.
    if (!playing && fraction >= 0.999f) return

    // Watchdog: `playing` is set optimistically. If nothing real ever
    // arrives (blocked / offline embed) stop claiming playback after a
    // grace period. Spotify's embed has no event API — exempt.
    LaunchedEffect(url, playing) {
        if (!playing) return@LaunchedEffect
        if (service == MediaService.SPOTIFY) return@LaunchedEffect
        delay(WATCHDOG_GRACE_MILLIS.milliseconds)
        val stale = System.currentTimeMillis() - MediaPlayerState.lastEventAt >
            WATCHDOG_GRACE_MILLIS
        if (stale && MediaPlayerState.playing && MediaPlayerState.currentUrl == url) {
            MediaPlayerState.playing = false
        }
    }

    val isSpotify = service == MediaService.SPOTIFY
    val isEmbed = MediaPlayerState.embedUrl != null
    val localVideo = MediaPlayerState.localIsVideo &&
        service == MediaService.FILE &&
        mediaKindFor(url) == MediaFileKind.VIDEO

    when {
        isSpotify -> SpotifyWidget(modifier)

        localVideo -> LocalVideoCard(
            path = MediaPlayerState.localPath.orEmpty(),
            title = title,
            fraction = fraction,
            modifier = modifier
        )

        // Any streaming service: its official embed, which is a complete
        // player (artwork, title, controls) — same as the desktop's
        // Spotify WebView path, applied to every stream service.
        isEmbed -> EmbedCard(
            service = service,
            embedUrl = MediaPlayerState.embedUrl.orEmpty(),
            title = title,
            fraction = fraction,
            modifier = modifier
        )

        else -> CompactAudioCard(
            service = service,
            title = title,
            playing = playing,
            fraction = fraction,
            modifier = modifier
        )
    }
}


/** The Spotify embed widget (400x80 compact embed) in the shared chrome. */
@Composable
private fun SpotifyWidget(modifier: Modifier = Modifier) {
    val embed = MediaPlayerState.embedUrl.orEmpty()
    PlayerCard(
        service = MediaService.SPOTIFY,
        // 424dp card = 400dp widget + the card's 12dp padding on both sides.
        width = 424.dp,
        modifier = modifier,
        controls = {
            StopButton(
                onStop = { MediaPlayerController.stop() },
                modifier = Modifier.size(24.dp)
            )
        }
    ) {
        EmbedWebView(
            embedUrl = embed,
            modifier = Modifier
                .fillMaxWidth()
                .height(80.dp)
        )
    }
}


/**
 * The generic embed card: a WebView showing the service's own player.
 * Height follows the service (video embeds need 16:9, audio widgets need
 * only a strip).
 */
@Composable
private fun EmbedCard(
    service: MediaService?,
    embedUrl: String,
    title: String?,
    fraction: Float,
    modifier: Modifier = Modifier
) {
    val looping = MediaPlayerState.looping
    val videoService = service == MediaService.YOUTUBE || service == MediaService.VIMEO
    PlayerCard(
        service = service,
        width = 480.dp,
        modifier = modifier,
        controls = {
            PlayPauseButton(
                playing = MediaPlayerState.playing,
                onToggle = { MediaPlayerController.togglePlayPause() },
                modifier = Modifier.size(30.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            LoopButton(
                active = looping,
                onToggle = { MediaPlayerController.setLooping(!looping) },
                modifier = Modifier.size(30.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            OpenExternalButton(
                onOpen = {
                    MediaPlayerState.currentUrl?.let { openExternalUrl(it) }
                },
                modifier = Modifier.size(30.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            StopButton(
                onStop = { MediaPlayerController.stop() },
                modifier = Modifier.size(24.dp)
            )
        }
    ) {
        EmbedWebView(
            embedUrl = embedUrl,
            modifier = Modifier
                .fillMaxWidth()
                .height(if (videoService) 270.dp else 160.dp)
                .padding(1.dp)
        )
        if (!title.isNullOrBlank()) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        ProgressBar(fraction = fraction)
    }
}


/**
 * A WebView hosting the service's official embed, filling its parent.
 * Playback controls are forwarded from the card through
 * [MediaPlayerController.embedControl]: for YouTube the IFrame player API
 * accepts postMessage play/pause commands; other widgets ignore them and
 * the user uses the embed's own controls.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun EmbedWebView(embedUrl: String, modifier: Modifier = Modifier) {
    val surface = MaterialTheme.colorScheme.surface
    var webView by remember { mutableStateOf<WebView?>(null) }
    AndroidView(
        modifier = modifier.clip(RoundedCornerShape(10.dp)),
        factory = { context ->
            WebView(context).apply {
                webView = this
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                setBackgroundColor(surface.toArgb())
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.mediaPlaybackRequiresUserGesture = false
                settings.loadsImagesAutomatically = true
                webViewClient = WebViewClient()
                loadDataWithBaseURL(
                    "https://www.youtube.com",
                    embedHtml(embedUrl),
                    "text/html",
                    "utf-8",
                    null
                )
            }
        },
        update = { view ->
            view.setBackgroundColor(surface.toArgb())
        }
    )
    DisposableEffect(embedUrl) {
        MediaPlayerController.embedControl = { command ->
            // Best-effort remote control of YouTube-style embeds: the
            // IFrame player API accepts postMessage play/pause commands.
            val func = if (command == "play") "playVideo" else "pauseVideo"
            val js = "document.querySelectorAll('iframe').forEach(f => " +
                "f.contentWindow.postMessage('{\"event\":\"command\",\"func\":\"$func\",\"args\":\"\"}','*'))"
            webView?.evaluateJavascript(js, null)
        }
        onDispose { MediaPlayerController.embedControl = null }
    }
}


/** A local video file played in place, with the shared card chrome. */
@Composable
private fun LocalVideoCard(
    path: String,
    title: String?,
    fraction: Float,
    modifier: Modifier = Modifier
) {
    PlayerCard(
        service = MediaService.FILE,
        width = 480.dp,
        modifier = modifier,
        controls = {
            PlayPauseButton(
                playing = MediaPlayerState.playing,
                onToggle = { MediaPlayerController.togglePlayPause() },
                modifier = Modifier.size(30.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            StopButton(
                onStop = { MediaPlayerController.stop() },
                modifier = Modifier.size(24.dp)
            )
        }
    ) {
        var videoView by remember { mutableStateOf<VideoView?>(null) }
        AndroidView(
            modifier = Modifier
                .fillMaxWidth()
                .height(270.dp)
                .clip(RoundedCornerShape(10.dp)),
            factory = { context ->
                VideoView(context).apply {
                    videoView = this
                    setVideoPath(path)
                    setOnPreparedListener { media ->
                        media.isLooping = MediaPlayerState.looping
                        start()
                    }
                }
            }
        )
        DisposableEffect(path) {
            MediaPlayerController.videoControl = { command ->
                videoView?.let { view ->
                    runCatching {
                        if (command == "play") view.start() else view.pause()
                    }
                }
            }
            onDispose {
                MediaPlayerController.videoControl = null
                runCatching { videoView?.stopPlayback() }
            }
        }
        if (!title.isNullOrBlank()) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        ProgressBar(fraction = fraction)
    }
}



/** A compact "now playing" card for audio-only media. */
@Composable
private fun CompactAudioCard(
    service: MediaService?,
    title: String?,
    playing: Boolean,
    fraction: Float,
    modifier: Modifier = Modifier
) {
    PlayerCard(
        service = service,
        width = 300.dp,
        modifier = modifier,
        controls = {
            PlayPauseButton(
                playing = playing,
                onToggle = { MediaPlayerController.togglePlayPause() },
                modifier = Modifier.size(30.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            StopButton(
                onStop = { MediaPlayerController.stop() },
                modifier = Modifier.size(24.dp)
            )
        }
    ) {
        Text(
            text = title ?: "Now playing",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.height(6.dp))
        ProgressBar(fraction = fraction)
    }
}


/**
 * The shared player card: rounded surface, outline border, service glyph
 * tile, accent label and a controls slot — the same container the desktop
 * build uses for every variant.
 */
@Composable
private fun PlayerCard(
    service: MediaService?,
    width: Dp,
    modifier: Modifier = Modifier,
    controls: @Composable RowScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(14.dp)
    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 6.dp,
        shadowElevation = 8.dp,
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
        ),
        modifier = modifier
            .width(width)
            .clip(shape)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(26.dp)
                        .clip(RoundedCornerShape(7.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f))
                ) {
                    Icon(
                        imageVector = service?.icon ?: RibbonIcons.MediaPlay,
                        contentDescription = null,
                        modifier = Modifier.size(15.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = service?.label ?: "Media",
                    style = MaterialTheme.typography.labelLarge,
                    color = MediaAccent,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 8.dp)
                )
                Spacer(modifier = Modifier.weight(1f))
                controls()
            }
            Spacer(modifier = Modifier.height(8.dp))
            content()
        }
    }
}


/** The thin live progress bar shared by the video and audio cards. */
@Composable
private fun ProgressBar(fraction: Float) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(3.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction.coerceIn(0.02f, 1f))
                .fillMaxHeight()
                .background(MediaAccent)
        )
    }
}


/** Minimal HTML page hosting [embedUrl], sized to its parent. */
private fun embedHtml(embedUrl: String): String {
    val separator = if (embedUrl.contains('?')) '&' else '?'
    val autoplay = if (embedUrl.contains("youtube") || embedUrl.contains("vimeo")) {
        "${separator}autoplay=1"
    } else {
        ""
    }
    return """
        <!DOCTYPE html>
        <html>
        <head>
          <meta name="viewport" content="width=device-width, initial-scale=1.0">
          <style>
            html, body { margin:0; padding:0; width:100%; height:100%;
                         background:transparent; overflow:hidden; }
            iframe, video { border:0; width:100%; height:100%; }
          </style>
        </head>
        <body>
          <iframe
            src="$embedUrl$autoplay"
            allow="autoplay; encrypted-media; fullscreen; picture-in-picture"
            allowfullscreen></iframe>
        </body>
        </html>
    """.trimIndent()
}
