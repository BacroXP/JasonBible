package ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.unit.dp
import data.MediaFileKind
import data.MediaService
import data.mediaKindFor
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
 * The bare Spotify embed widget, hosted on the [JFXPanel] bridged in via
 * [SwingPanel] — no header, title or progress chrome and NO card behind
 * it, because Spotify's widget is already a complete player with its own
 * artwork, labels, controls and background. Sized to Spotify's COMPACT
 * player (400x80): the widget is fluid and fills that canvas
 * edge-to-edge, so there is no black gutter around it (a taller canvas
 * leaves a dead band below the widget). A small close button sits in the
 * corner, as the widget has no way to dismiss itself.
 *
 * Why there is no Surface card here: the JFXPanel is a heavyweight AWT
 * component that overpaints Compose drawing, and on Linux/OpenGL Compose
 * has no interop blending (that only exists for Direct3D/Metal), so a
 * genuinely transparent scene would render BLACK behind the widget. The
 * closest achievable look is a borderless widget whose FX scene is filled
 * with the app's own window background color — pushed via
 * [MediaPlayerController.updateSurfaceColor] — so the widget's rounded
 * corners blend seamlessly into whatever is behind it, with no visible
 * canvas or card.
 */
@Composable
private fun SpotifyWidget(modifier: Modifier = Modifier) {
    // Push the app's window background color (colorScheme.surface — the
    // root window Surface's fill) into the JavaFX side so the (rounded)
    // corners of the transparent media canvas blend straight into the app
    // behind the widget instead of painting black.
    val surface = MaterialTheme.colorScheme.surface
    LaunchedEffect(surface) {
        MediaPlayerController.updateSurfaceColor(surface.toArgb().toLong() and 0xFFFFFFFFL)
    }
    Box(modifier = modifier.width(400.dp)) {
        val panel = remember { JFXPanel().also { MediaPlayerController.attachHost(it) } }
        SwingPanel(
            factory = { panel },
            update = { MediaPlayerController.attachHost(it) },
            // No AWT background: the FX scene fill paints the whole canvas
            // with the window background color, so nothing white ever
            // flashes behind the widget.
            background = Color.Transparent,
            modifier = Modifier
                .fillMaxWidth()
                .height(80.dp)
        )
        DisposableEffect(panel) {
            onDispose { MediaPlayerController.detachHost(panel) }
        }
        StopButton(
            onStop = { MediaPlayerController.stop() },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(6.dp)
        )
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
    val shape = RoundedCornerShape(14.dp)
    // Live loop-toggle state (shared with the media cards' overlays).
    val looping = MediaPlayerState.looping

    // Keep the FX scene fill in sync with this card's surface color so
    // the rounded video corners blend in (same reason as SpotifyWidget).
    val surface = MaterialTheme.colorScheme.surface
    LaunchedEffect(surface) {
        MediaPlayerController.updateSurfaceColor(surface.toArgb().toLong() and 0xFFFFFFFFL)
    }
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
            .width(480.dp)
            .clip(shape)
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            // ---- Header: service glyph, label, pause/play, close ----
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
                StopButton(
                    onStop = { MediaPlayerController.stop() },
                    modifier = Modifier.size(24.dp)
                )
            }

            // ---- The media surface itself ----
            Spacer(modifier = Modifier.height(8.dp))
            val panel = remember { JFXPanel().also { MediaPlayerController.attachHost(it) } }
            SwingPanel(
                factory = { panel },
                update = { MediaPlayerController.attachHost(it) },
                // Inset by the border width so the FX canvas doesn't
                // overpaint the card's border (rounding is on the FX side).
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
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            ) {
                Box(
                    // A minimum sliver keeps the bar visible the instant
                    // playback starts (fraction is 0 until the first event).
                    modifier = Modifier
                        .fillMaxWidth(fraction.coerceIn(0.02f, 1f))
                        .fillMaxHeight()
                        .background(MediaAccent)
                )
            }
        }
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
            .width(300.dp)
            .clip(shape)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // ---- Header: service glyph, label, pause/play, close ----
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

            // ---- Title ----
            Spacer(modifier = Modifier.height(8.dp))
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
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            ) {
                Box(
                    // A minimum sliver keeps the bar visible the instant
                    // playback starts (fraction is 0 until the first event).
                    modifier = Modifier
                        .fillMaxWidth(fraction.coerceIn(0.02f, 1f))
                        .fillMaxHeight()
                        .background(MediaAccent)
                )
            }
        }
    }
}
