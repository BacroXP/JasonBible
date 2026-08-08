package ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay


/**
 * A compact "now playing" card pinned to the bottom-right corner of the
 * main window (rendered by Navigation above every screen). It appears
 * whenever the in-app player has media loaded — playing OR paused — and
 * survives navigation: playback is no longer killed when you go back or
 * open another note, so the mini player takes over the pause / close
 * controls from anywhere. Clicking the card re-opens the always-on-top
 * player window (without restarting the media).
 */
@Composable
internal fun MiniPlayer(modifier: Modifier = Modifier) {
    // Reading snapshot state in composition subscribes to updates, so the
    // card appears / disappears and repaints its progress live.
    val url = MediaPlayerState.currentUrl ?: return
    val service = MediaPlayerState.currentService
    val title = MediaPlayerState.currentTitle
    val playing = MediaPlayerState.playing
    val fraction = MediaPlayerState.fraction

    // A clip that ran to completion keeps `currentUrl` set but is no
    // longer playing — hide the mini player so it doesn't linger on a
    // dead card (replay from the media panel's card instead).
    if (!playing && fraction >= 0.999f) return

    // Watchdog: `playing` is set optimistically before the embed actually
    // starts. If nothing real ever arrives (blocked / region-restricted /
    // offline embed) and the media panel's own watchdog isn't composed
    // (we navigated away), stop claiming playback after a grace period so
    // the mini player can't show a dead "playing" state forever.
    LaunchedEffect(url, playing) {
        if (!playing) return@LaunchedEffect
        delay(WATCHDOG_GRACE_MILLIS)
        val stale = System.currentTimeMillis() - MediaPlayerState.lastEventAt >
            WATCHDOG_GRACE_MILLIS
        if (stale && MediaPlayerState.playing && MediaPlayerState.currentUrl == url) {
            MediaPlayerState.playing = false
        }
    }

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
            .clickable { MediaPlayerController.showPlayerWindow() }
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
