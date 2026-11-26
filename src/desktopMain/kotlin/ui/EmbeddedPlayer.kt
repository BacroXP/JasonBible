package ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import data.MediaFileKind
import data.MediaService
import data.mediaKindFor
import data.openExternalUrl
import javafx.embed.swing.JFXPanel
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds


/**
 * The embedded media player, pinned to the bottom-right of the main
 * window (rendered by Navigation above every screen). Playback happens
 * INSIDE the app — no separate OS window: visual media (YouTube / Vimeo /
 * local video) renders the JavaFX MediaView, and Spotify renders its
 * official embed WebView, both hosted on a [JFXPanel] bridged into
 * Compose via [SwingPanel]. Audio-only media (SoundCloud / local audio)
 * shows a compact card instead — the audio still plays through the
 * controller's player without needing a surface.
 *
 * Appears whenever the in-app player has media loaded — playing OR paused
 * — and survives navigation: playback is no longer killed when you go
 * back or open another note, so this panel takes over the pause / close
 * controls from anywhere.
 */
@Composable
internal fun EmbeddedPlayer(modifier: Modifier = Modifier) {
    // Reading snapshot state in composition subscribes to updates, so the
    // panel appears / disappears and repaints its progress live.
    val url = MediaPlayerState.currentUrl ?: return
    val service = MediaPlayerState.currentService
    val title = MediaPlayerState.currentTitle
    val playing = MediaPlayerState.playing
    val fraction = MediaPlayerState.fraction

    // A clip that ran to completion keeps `currentUrl` set but is no
    // longer playing — hide the panel so it doesn't linger on a dead
    // card (replay from the media panel's card instead).
    if (!playing && fraction >= 0.999f) return

    // Watchdog: `playing` is set optimistically before playback actually
    // starts. If nothing real ever arrives (blocked / region-restricted /
    // offline embed) and the media panel's own watchdog isn't composed
    // (we navigated away), stop claiming playback after a grace period so
    // the panel can't show a dead "playing" state forever. Spotify is
    // exempt — its embed has no event API, so its indeterminate state is
    // the intended one.
    LaunchedEffect(url, playing) {
        if (!playing) return@LaunchedEffect
        // Spotify's embed has no event API at all, so its indeterminate
        // "playing" state is the intended one — never trip it.
        if (service == MediaService.SPOTIFY) return@LaunchedEffect
        delay(WATCHDOG_GRACE_MILLIS.milliseconds)
        val stale = System.currentTimeMillis() - MediaPlayerState.lastEventAt >
            WATCHDOG_GRACE_MILLIS
        if (stale && MediaPlayerState.playing && MediaPlayerState.currentUrl == url) {
            MediaPlayerState.playing = false
        }
    }

    // Visual services render the JavaFX surface (a MediaView for video,
    // a WebView for Spotify); audio-only services show a compact card.
    val isSpotify = service == MediaService.SPOTIFY
    val visual = service == MediaService.YOUTUBE || service == MediaService.VIMEO ||
        isSpotify ||
        (service == MediaService.FILE && mediaKindFor(url) == MediaFileKind.VIDEO)

    when {
        isSpotify ->
            // Spotify's official embed IS a complete widget (artwork,
            // title, its own play controls) — render it bare instead of
            // wrapping it in our own player chrome. Only a small close
            // button is added, since the widget has no dismiss affordance.
            SpotifyWidget(
                modifier = modifier
            )

        visual -> VisualPlayerPanel(
            service = service,
            title = title,
            playing = playing,
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


/**
 * The Spotify embed widget, wrapped in the SAME card chrome as the video
 * and compact audio players (see [PlayerCard]): rounded surface, service
 * glyph + label in the header and a close button. Spotify's official
 * COMPACT embed (400x80) is already a complete player with its own
 * artwork, labels and controls, so it is rendered bare inside the card —
 * no title or progress chrome of ours.
 *
 * The widget's own canvas is filled with the app's surface color (pushed
 * via [MediaPlayerController.updateSurfaceColor]) so it blends into the
 * card; Linux/OpenGL Compose has no heavyweight interop blending, so a
 * transparent scene would render BLACK. The card frame sits OUTSIDE the
 * JFXPanel's bounds (inside the card padding), so it paints normally —
 * the overpainting JFXPanel only covers the widget area itself.
 */
@Composable
private fun SpotifyWidget(modifier: Modifier = Modifier) {
    // Push the app's window background color (colorScheme.surface — the
    // root window Surface's fill, and this card's fill) into the JavaFX
    // side so the widget's corners blend into the card instead of
    // painting black.
    val surface = MaterialTheme.colorScheme.surface
    LaunchedEffect(surface) {
        MediaPlayerController.updateSurfaceColor(surface.toArgb().toLong() and 0xFFFFFFFFL)
    }
    val panel = remember { JFXPanel().also { MediaPlayerController.attachHost(it) } }
    // 424dp card = 400dp widget + the card's 12dp padding on both sides,
    // so the embed keeps the size it is designed for.
    PlayerCard(
        service = MediaService.SPOTIFY,
        width = 424.dp,
        modifier = modifier,
        controls = {
            StopButton(
                onStop = { MediaPlayerController.stop() },
                modifier = Modifier.size(24.dp)
            )
        }
    ) {
        SwingPanel(
            factory = { panel },
            update = { MediaPlayerController.attachHost(it) },
            // No AWT background: the FX scene fill paints the whole canvas
            // with the app's surface color, so nothing white ever flashes
            // behind the widget.
            background = Color.Transparent,
            modifier = Modifier
                .fillMaxWidth()
                .height(80.dp)
        )
    }
    DisposableEffect(panel) {
        onDispose { MediaPlayerController.detachHost(panel) }
    }
}


/**
 * The shared player card: a rounded surface with the app's standard card
 * chrome (surface fill, outline border, service glyph in a rounded tile,
 * accent label) and a [controls] slot on the right of the header. Every
 * embedded-player variant — Spotify, video, audio-only — renders through
 * this one container so the popup always matches the rest of the app.
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
            // ---- Header: service glyph, label, variant controls ----
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


/**
 * The visual player: a card whose body is the actual media surface — the
 * JavaFX MediaView for video services — bridged into Compose via
 * [SwingPanel] hosting a [JFXPanel]. The [MediaPlayerController] mounts
 * its scene onto the panel (and vice versa: a play started before this
 * panel composes is pushed in via attachHost).
 */
@Composable
private fun VisualPlayerPanel(
    service: MediaService?,
    title: String?,
    playing: Boolean,
    fraction: Float,
    modifier: Modifier = Modifier
) {
    // Live loop-toggle state (shared with the media cards' overlays).
    val looping = MediaPlayerState.looping

    // Keep the FX scene fill in sync with this card's surface color so
    // the rounded video corners blend in (same reason as SpotifyWidget).
    val surface = MaterialTheme.colorScheme.surface
    LaunchedEffect(surface) {
        MediaPlayerController.updateSurfaceColor(surface.toArgb().toLong() and 0xFFFFFFFFL)
    }
    val panel = remember { JFXPanel().also { MediaPlayerController.attachHost(it) } }
    PlayerCard(
        service = service,
        width = 480.dp,
        modifier = modifier,
        controls = {
            PlayPauseButton(
                playing = playing,
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
            // Open the media on its own service page (the YouTube watch
            // page, etc.) in the default browser.
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
        // ---- The media surface itself ----
        SwingPanel(
            factory = { panel },
            update = { MediaPlayerController.attachHost(it) },
            // Inset by the border width so the FX canvas doesn't overpaint
            // the card's border (rounding is on the FX side).
            modifier = Modifier
                .fillMaxWidth()
                .height(270.dp)
                .padding(1.dp)
        )
        DisposableEffect(panel) {
            onDispose { MediaPlayerController.detachHost(panel) }
        }

        // ---- Title ----
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

        // ---- Thin live progress bar ----
        Spacer(modifier = Modifier.height(6.dp))
        ProgressBar(fraction = fraction)
    }
}


/**
 * A compact "now playing" card for audio-only services (SoundCloud, local
 * audio files): service glyph, title, pause/play and close, with a thin
 * live progress bar. No media surface is needed — the audio plays through
 * the controller's player regardless.
 */
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
        // ---- Title ----
        Text(
            text = title ?: "Now playing",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        // ---- Thin live progress bar ----
        Spacer(modifier = Modifier.height(6.dp))
        ProgressBar(fraction = fraction)
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
            // A minimum sliver keeps the bar visible the instant playback
            // starts (fraction is 0 until the first event).
            modifier = Modifier
                .fillMaxWidth(fraction.coerceIn(0.02f, 1f))
                .fillMaxHeight()
                .background(MediaAccent)
        )
    }
}
