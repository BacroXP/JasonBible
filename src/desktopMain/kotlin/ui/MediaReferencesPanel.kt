@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class
)

package ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import data.DEFAULT_ACCENT_ARGB
import data.MediaFileKind
import data.MediaPreviewFetcher
import data.MediaPreviewInfo
import data.MediaProfileInfo
import data.MediaReferenceToken
import data.MediaService
import data.domainOf
import data.fetchProfileInfo
import data.fetchYouTubeDurationSeconds
import data.findMediaReferenceTokens
import data.formatDurationSeconds
import data.isPlayable
import data.isProfile
import data.mediaKindFor
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds


// ---------------------------------------------------------------------------
// Media References panel
//
// A rich-card list of every media link (`@youtube:…`, `@spotify:…`,
// `@url:…`) found in the open note, rendered BELOW the editor (a
// BasicTextField cannot host images inline, so the cards live outside
// the text). Each card mirrors the service's look: a service header, a
// 16:9 thumbnail with a duration badge, then the title and channel
// underneath. When a thumbnail can't be fetched the card falls back to
// the "title - channel" line; when the oEmbed fetch fails entirely it
// shows the service + id (or domain + URL for generic links).
//
// Playback: clicking a PLAYABLE card (video / song services — not plain
// links) — or hovering its thumbnail for the ▶ overlay — starts in-app
// playback in the embedded player (MediaPlayerController); while
// playing, the card paints live progress — a bottom bar on 16:9 video
// thumbnails, a circular ring around square album art / icons, and
// colored-in sound waves for SoundCloud — with a pause/play toggle and a
// stop button. Plain links keep opening in the browser.
// ---------------------------------------------------------------------------

/** Service-brand accent used for the card header label and progress
 *  visuals (blue, matching the reference cards' link color). Shared with
 *  the embedded player. */
internal val MediaAccent = Color(DEFAULT_ACCENT_ARGB)

/** How long to wait for an embed's first real event before the watchdog
 *  rescues a card stuck on "playing" (see the watchdog effect above).
 *  Shared with the embedded player, which runs its own watchdog while
 *  the notes screen (and this panel) aren't composed. */
internal const val WATCHDOG_GRACE_MILLIS = 8000L


/**
 * Renders a "Media References" section under the editor listing every
 * unique media token in [text] as a card. Fetches oEmbed info +
 * thumbnails on a background coroutine, deduped by resolved URL, with
 * per-card loading / fallback states. Hidden entirely when the note
 * contains no media references.
 *
 * Hover-expand ("swoop up"): when the note HAS media, the section
 * collapses to a thin grip strip at the bottom of the editor and
 * expands upward while the cursor is over it — the same hover pattern
 * as the notes sidebar (with the matching Open/Close sound blip). It
 * stays expanded while something is playing so the card's live
 * progress and controls don't vanish when the cursor leaves.
 */
@Composable
internal fun MediaReferencesPanel(
    text: String,
    onOpenUrl: (String) -> Unit
) {
    // Unique media tokens in the current note text, deduped by resolved
    // URL (two `@youtube:ID` mentions collapse to one card).
    val tokens = remember(text) {
        findMediaReferenceTokens(text).distinctBy { it.resolveUrl() }
    }
    if (tokens.isEmpty()) return

    // Fetch cache, keyed by resolved URL. Written from a background
    // coroutine; reads below are snapshot-state so cards update as each
    // fetch completes (spinner → thumbnail / fallback).
    val previews = remember { mutableStateMapOf<String, MediaPreviewInfo?>() }
    val thumbnails = remember { mutableStateMapOf<String, ImageBitmap?>() }
    val durations = remember { mutableStateMapOf<String, Long?>() }
    val loading = remember { mutableStateMapOf<String, Boolean>() }
    // Channel / profile cards (YouTube channels, Spotify users / artists)
    // fetch their own info — name + avatar — instead of an oEmbed.
    val profiles = remember { mutableStateMapOf<String, MediaProfileInfo?>() }
    val avatars = remember { mutableStateMapOf<String, ImageBitmap?>() }
    val profileLoading = remember { mutableStateMapOf<String, Boolean>() }

    // Fetch every unique URL exactly once. Keyed on the TOKEN LIST (which
    // only changes when the note text changes) rather than on a derived
    // "pending" list: writing the loading flags below recomposes the
    // panel, and keying on derived state would cancel this effect (and
    // its in-flight network fetches) mid-flight, leaving cards stuck on
    // "Loading preview…". The previews/loading guards inside the body
    // do the dedupe instead, and `finally` clears the flag even when a
    // text edit cancels the effect, so the next run retries cleanly.
    LaunchedEffect(tokens) {
        val seen = mutableSetOf<String>()
        tokens.forEach { token ->
            val url = token.resolveUrl() ?: return@forEach
            if (!seen.add(url)) return@forEach
            if (token.isProfile) {
                if (profiles.containsKey(url) || (profileLoading[url] ?: false)) return@forEach
                profileLoading[url] = true
                try {
                    val profile = fetchProfileInfo(token)
                    profiles[url] = profile
                    profile?.avatarUrl?.let { avatars[url] = fetchImageBitmap(it) }
                } finally {
                    profileLoading[url] = false
                }
            } else {
                if (previews.containsKey(url) || (loading[url] ?: false)) return@forEach
                loading[url] = true
                try {
                    val info = MediaPreviewFetcher.fetch(token)
                    previews[url] = info
                    info?.thumbnailUrl?.let { thumbnails[url] = fetchImageBitmap(it) }
                    durations[url] = fetchYouTubeDurationSeconds(token)
                } finally {
                    loading[url] = false
                }
            }
        }
    }

    // NOTE: leaving the notes screen / opening another note does NOT stop
    // playback — the embedded player (bottom-right of the main window)
    // keeps playing and takes over the pause/close controls from any
    // screen.

    // Watchdog: if a playable embed never reports a real state event
    // (blocked / region-restricted / offline — the optimistic "playing"
    // flag is set before the embed starts), stop claiming playback after
    // a grace period so the card doesn't show a dead "now playing" ring
    // forever. A genuinely playing embed posts progress continuously, so
    // the trip only happens when NOTHING arrived for the whole grace
    // period. Spotify is exempt — its embed has no event API, so its
    // indeterminate ring is the intended state.
    LaunchedEffect(MediaPlayerState.currentUrl, MediaPlayerState.playing) {
        val url = MediaPlayerState.currentUrl ?: return@LaunchedEffect
        val token = tokens.firstOrNull { it.resolveUrl() == url } ?: return@LaunchedEffect
        if (!MediaPlayerState.playing || token.service == MediaService.SPOTIFY) {
            return@LaunchedEffect
        }
        delay(WATCHDOG_GRACE_MILLIS.milliseconds)
        val stale = System.currentTimeMillis() - MediaPlayerState.lastEventAt > WATCHDOG_GRACE_MILLIS
        if (stale && MediaPlayerState.playing && MediaPlayerState.currentUrl == url) {
            MediaPlayerState.playing = false
        }
    }

    // Hover-driven expand ("swoop up"): the section collapses to a thin
    // grip strip at the bottom of the editor and expands upward while the
    // cursor is over it — the same pattern as the notes sidebar. A single
    // InteractionSource on the outer Box keeps hover continuous while the
    // cursor moves between the always-present strip and the animating
    // panel (their bounds change during the expand/shrink animation).
    val mediaHoverSource = remember { MutableInteractionSource() }
    val isMediaHovered by mediaHoverSource.collectIsHoveredAsState()
    // Pinned open while something is playing — the card's live progress
    // and pause/stop controls must not vanish the moment the cursor
    // leaves the panel.
    val showMedia = isMediaHovered || MediaPlayerState.currentUrl != null

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .hoverable(mediaHoverSource)
            .soundHoverOn(mediaHoverSource)
    ) {
        if (!showMedia) {
            // Collapsed grip strip: the small "area" the cursor enters to
            // swoop the media section up. A subtle rounded pill mirrors
            // the notes sidebar's trigger line.
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(16.dp)
            ) {
                Box(
                    modifier = Modifier
                        .width(36.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f))
                )
            }
        }

        AnimatedVisibility(
            visible = showMedia,
            enter = fadeIn() + expandVertically(expandFrom = Alignment.Bottom),
            exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Bottom)
        ) {
            // One Open/Close blip per genuine visibility transition
            // (debounced inside PlayOpenCloseSound, so rapid hover
            // wavering does not cause repeated sonic bursts).
            PlayOpenCloseSound(visible = showMedia)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Media References",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                // Cards in a 2-column grid (fits the editor pane in both
                // standalone and split layouts); a lone trailing card gets
                // a spacer to keep the rhythm. Bounded height with
                // internal scroll so a long list can't crush the editor
                // above it.
                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 340.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    tokens.chunked(2).forEach { rowTokens ->
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            rowTokens.forEach { token ->
                                val url = token.resolveUrl().orEmpty()
                                when {
                                    token.service == MediaService.FILE ->
                                        // Embedded local files get a media-only
                                        // card (image shown, video/audio
                                        // playable) with no title / artist
                                        // metadata.
                                        FileCard(
                                            token = token,
                                            modifier = Modifier.weight(1f)
                                        )

                                    token.isProfile ->
                                        // YouTube channel / Spotify user or
                                        // artist: avatar + name beside it,
                                        // click opens the profile page in the
                                        // browser.
                                        ProfileCard(
                                            token = token,
                                            info = profiles[url],
                                            avatar = avatars[url],
                                            loading = profileLoading[url] ?: false,
                                            onClick = { if (url.isNotBlank()) onOpenUrl(url) },
                                            modifier = Modifier.weight(1f)
                                        )

                                    else -> MediaCard(
                                        token = token,
                                        info = previews[url],
                                        thumbnail = thumbnails[url],
                                        duration = durations[url],
                                        loading = loading[url] ?: false,
                                        onClick = { if (url.isNotBlank()) onOpenUrl(url) },
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                            if (rowTokens.size == 1) {
                                Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }
    }
}


/**
 * One rich media card: service header, thumbnail (with duration badge)
 * and title + channel underneath — falling back to a "title - channel"
 * text line when no thumbnail is available, and to a plain
 * service/id/domain line when the oEmbed fetch fails outright. Clicking
 * a PLAYABLE card (or its hover ▶) starts in-app playback; while
 * playing, live progress is painted over the media area.
 */
@Composable
private fun MediaCard(
    token: MediaReferenceToken,
    info: MediaPreviewInfo?,
    thumbnail: ImageBitmap?,
    duration: Long?,
    loading: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(12.dp)
    val playable = token.service.isPlayable
    val resolvedUrl = token.resolveUrl()
    val isNowPlaying = resolvedUrl != null && MediaPlayerState.currentUrl == resolvedUrl
    val playing = isNowPlaying && MediaPlayerState.playing
    val fraction = if (isNowPlaying) MediaPlayerState.fraction else 0f
    val isVideo = token.service == MediaService.YOUTUBE || token.service == MediaService.VIMEO
    // Shared hover source for the card's media area (thumbnail or icon).
    val hoverSource = remember { MutableInteractionSource() }
    val hovered by hoverSource.collectIsHoveredAsState()

    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        shadowElevation = 1.dp,
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
        ),
        modifier = modifier
            .clip(shape)
            .clickable {
                // Playable services (YouTube / Spotify / Vimeo /
                // SoundCloud) PLAY in-app on click — the same action as
                // the hover ▶, so the whole card is one big play button.
                // A card already playing toggles pause/resume; only plain
                // links (and profiles, handled by ProfileCard) open the
                // browser via onClick.
                if (token.service.isPlayable) {
                    if (isNowPlaying) {
                        MediaPlayerController.togglePlayPause()
                    } else {
                        MediaPlayerController.play(token, info?.title)
                    }
                } else {
                    onClick()
                }
            }
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            // ---- Header: service glyph + label ----
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(24.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f))
                ) {
                    Icon(
                        imageVector = token.service.icon,
                        contentDescription = null,
                        modifier = Modifier.size(15.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = token.service.label,
                    style = MaterialTheme.typography.labelLarge,
                    color = MediaAccent,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 6.dp)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            val title = info?.title
            val channel = info?.author

            when {
                // ---- Thumbnail available: image + duration badge, then
                // title and channel below ----
                thumbnail != null -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(if (isVideo) 16f / 9f else 1f)
                            .clip(RoundedCornerShape(8.dp))
                            .hoverable(hoverSource)
                    ) {
                        Image(
                            bitmap = thumbnail,
                            contentDescription = title,
                            contentScale = ContentScale.Crop,
                            // Video services serve 16:9 thumbnails; audio
                            // services (Spotify / SoundCloud) serve square
                            // album art, which a 16:9 crop would butcher.
                            modifier = Modifier
                                .fillMaxSize()
                                .then(if (isNowPlaying) Modifier.alpha(0.45f) else Modifier)
                        )
                        if (duration != null && duration > 0 && !isNowPlaying) {
                            Text(
                                text = formatDurationSeconds(duration),
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White,
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(6.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(Color(0xCC000000))
                                    .padding(horizontal = 5.dp, vertical = 2.dp)
                            )
                        }

                        if (isNowPlaying) {
                            // Live playback visuals.
                            if (isVideo) {
                                VideoProgressBar(
                                    fraction = fraction,
                                    modifier = Modifier.align(Alignment.BottomCenter)
                                )
                            } else {
                                ProgressRing(
                                    fraction = fraction,
                                    animate = token.service == MediaService.SPOTIFY,
                                    modifier = Modifier
                                        .align(Alignment.Center)
                                        .size(54.dp)
                                )
                                if (token.service == MediaService.SOUNDCLOUD) {
                                    SoundWaveFill(
                                        fraction = fraction,
                                        seed = resolvedUrl.hashCode(),
                                        modifier = Modifier.align(Alignment.BottomCenter)
                                    )
                                }
                            }
                            PlayPauseButton(
                                playing = playing,
                                onToggle = { MediaPlayerController.togglePlayPause() },
                                modifier = Modifier
                                    .align(Alignment.Center)
                                    .size(40.dp)
                            )
                            StopButton(
                                onStop = { MediaPlayerController.stop() },
                                modifier = Modifier.align(Alignment.TopEnd)
                            )
                        } else if (playable && hovered) {
                            PlayPauseButton(
                                playing = false,
                                onToggle = { MediaPlayerController.play(token, title) },
                                modifier = Modifier
                                    .align(Alignment.Center)
                                    .size(42.dp)
                            )
                        }
                    }
                    if (title != null) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                    if (channel != null) {
                        Text(
                            text = channel,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                }

                // ---- Preview fetch still in flight ----
                loading -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(top = 4.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp
                        )
                        Text(
                            text = "Loading preview\u2026",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // ---- No thumbnail, playable service: icon + text, so the
                // hover ▶ has an "icon" to sit on (mirrors the reference
                // cards' square-art look) ----
                playable -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 4.dp)
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(46.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f))
                                .hoverable(hoverSource)
                        ) {
                            Icon(
                                imageVector = token.service.icon,
                                contentDescription = null,
                                modifier = Modifier
                                    .size(26.dp)
                                    .then(if (isNowPlaying) Modifier.alpha(0.4f) else Modifier),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (isNowPlaying) {
                                ProgressRing(
                                    fraction = fraction,
                                    animate = token.service == MediaService.SPOTIFY,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(2.dp)
                                )
                                PlayPauseButton(
                                    playing = playing,
                                    onToggle = { MediaPlayerController.togglePlayPause() },
                                    modifier = Modifier
                                        .align(Alignment.Center)
                                        .size(22.dp)
                                )
                            } else if (hovered) {
                                PlayPauseButton(
                                    playing = false,
                                    onToggle = { MediaPlayerController.play(token, title) },
                                    modifier = Modifier
                                        .align(Alignment.Center)
                                        .size(26.dp)
                                )
                            }
                        }
                        Column(
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                            modifier = Modifier
                                .padding(start = 10.dp)
                                .weight(1f)
                        ) {
                            // oEmbed gave us a title: show it (plus the
                            // channel when present) …
                            if (!title.isNullOrBlank()) {
                                Text(
                                    text = title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (!channel.isNullOrBlank()) {
                                    Text(
                                        text = channel,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            } else {
                                // … otherwise the service + id.
                                Text(
                                    text = "${token.service.label} - ${token.content}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }

                // ---- oEmbed failed: generic links show domain + URL ----
                else -> {
                    val url = token.resolveUrl().orEmpty()
                    if (token.service == MediaService.LINK) {
                        Text(
                            text = domainOf(url),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                        Text(
                            text = url,
                            style = MaterialTheme.typography.bodySmall,
                            color = MediaAccent,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    } else {
                        Text(
                            text = "${token.service.label} - ${token.content}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }
        }
    }
}


// ---------------------------------------------------------------------------
// Channel / profile cards
// ---------------------------------------------------------------------------

/**
 * Small "✓ …" chip for a scraped verification badge ("✓ Verified" /
 * "✓ Official Artist"), shown beside profile names. Shared by the media
 * panel's profile cards and the preview popup so both agree.
 */
@Composable
internal fun VerifiedBadge(text: String, modifier: Modifier = Modifier) {
    Surface(
        shape = PillShape,
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
        modifier = modifier
    ) {
        Text(
            text = "\u2713 $text",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}


/**
 * One card for a YouTube channel or Spotify user / artist: the account's
 * avatar with its name beside it, scraped from the page's OpenGraph meta
 * tags, plus the follower / subscriber count and the verification badge
 * when the page exposes them. Loading shows a spinner in the avatar *  slot; a failed scrape falls back to the service icon + id. Clicking
 *  opens the profile page in the browser (profiles have no in-app
 *  player).
 */
@Composable
private fun ProfileCard(
    token: MediaReferenceToken,
    info: MediaProfileInfo?,
    avatar: ImageBitmap?,
    loading: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(12.dp)
    val name = info?.name
    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        shadowElevation = 1.dp,
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
        ),
        modifier = modifier
            .clip(shape)
            .clickable { onClick() }
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(10.dp)
        ) {
            // ---- Avatar (or spinner / icon fallback) ----
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f))
            ) {
                when {
                    avatar != null -> Image(
                        bitmap = avatar,
                        contentDescription = name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )

                    loading -> CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp
                    )

                    else -> Icon(
                        imageVector = token.service.icon,
                        contentDescription = null,
                        modifier = Modifier.size(30.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            // ---- Name beside the avatar ----
            Column(
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier
                    .padding(start = 10.dp)
                    .weight(1f)
            ) {
                if (name.isNullOrBlank()) {
                    // Scrape failed / still fetching: show the raw id.
                    Text(
                        text = "${token.service.label} - ${token.content}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                } else {
                    // Name with the scraped verification badge beside it
                    // ("✓ Verified" / "✓ Official Artist"). The name takes
                    // the remaining width and ellipsizes, so a long name
                    // can't push the badge off the card.
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = name,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        info?.badge?.let { badge ->
                            VerifiedBadge(
                                text = badge,
                                modifier = Modifier.padding(start = 6.dp)
                            )
                        }
                    }
                }
                // Follower / subscriber count when the page exposed one
                // ("1.23M subscribers", "2.4M monthly listeners", …) —
                // more informative than the generic type label, so it
                // replaces it. Falls back to the type label.
                val followerCount = info?.followerCount?.takeIf { it.isNotBlank() }
                val subtitle = when {
                    followerCount != null -> followerCount
                    token.service == MediaService.YOUTUBE -> "YouTube channel"
                    token.service == MediaService.SPOTIFY -> "Spotify profile"
                    else -> token.service.label
                }
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MediaAccent,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}


// ---------------------------------------------------------------------------
// Embedded local files (@file: tokens)
// ---------------------------------------------------------------------------

/**
 * One card for an embedded local media file: the MEDIA ITSELF, with no
 * title / artist metadata — an image is displayed inline, a video shows
 * a dark 16:9 frame with a play button, and an audio file shows its
 * waveform with a play button. Playback runs in the embedded player
 * window (local files load natively there); while playing, the card
 * paints the same live progress visuals as the service cards.
 */
@Composable
private fun FileCard(
    token: MediaReferenceToken,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(12.dp)
    val resolvedUrl = token.resolveUrl()
    val isNowPlaying = resolvedUrl != null && MediaPlayerState.currentUrl == resolvedUrl
    val playing = isNowPlaying && MediaPlayerState.playing
    val fraction = if (isNowPlaying) MediaPlayerState.fraction else 0f

    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        shadowElevation = 1.dp,
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
        ),
        modifier = modifier.clip(shape)
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            when (mediaKindFor(token.content)) {
                MediaFileKind.IMAGE -> ImageFileCard(token = token)

                MediaFileKind.VIDEO -> VideoFileCard(
                    token = token,
                    isNowPlaying = isNowPlaying,
                    playing = playing,
                    fraction = fraction
                )

                MediaFileKind.AUDIO -> AudioFileCard(
                    token = token,
                    isNowPlaying = isNowPlaying,
                    playing = playing,
                    fraction = fraction
                )

                null -> {
                    // File deleted from disk — the token drops out of the
                    // list on the next text edit; show a dim placeholder
                    // until then.
                    Text(
                        text = token.content,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}


/** The image file itself, loaded from disk and fitted into the card. */
@Composable
private fun ImageFileCard(token: MediaReferenceToken) {
    val path = token.localPath()
    var bitmap by remember(path) { mutableStateOf<ImageBitmap?>(null) }
    var failed by remember(path) { mutableStateOf(false) }
    LaunchedEffect(path) {
        val loaded = path?.let { fetchImageBitmapFromFile(it) }
        bitmap = loaded
        failed = loaded == null
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
        contentAlignment = Alignment.Center
    ) {
        val bmp = bitmap
        when {
            bmp != null -> Image(
                bitmap = bmp,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 300.dp)
            )

            failed -> Text(
                text = "Couldn't display image",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 40.dp)
            )

            else -> Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp
                )
            }
        }
    }
}


/**
 * A local video: a dark 16:9 frame with a play button (the frame can't
 * show a thumbnail without decoding the file, so the glyph stands in).
 * Clicking plays in the player window; while playing, the frame shows
 * the live progress bar plus pause/stop controls.
 */
@Composable
private fun VideoFileCard(
    token: MediaReferenceToken,
    isNowPlaying: Boolean,
    playing: Boolean,
    fraction: Float
) {
    val hoverSource = remember { MutableInteractionSource() }
    val hovered by hoverSource.collectIsHoveredAsState()
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.Black.copy(alpha = 0.72f))
            .hoverable(hoverSource)
            .clickable {
                if (isNowPlaying) {
                    MediaPlayerController.togglePlayPause()
                } else {
                    MediaPlayerController.play(token)
                }
            }
    ) {
        Icon(
            imageVector = RibbonIcons.MediaVimeo,
            contentDescription = null,
            modifier = Modifier
                .size(44.dp)
                .align(Alignment.Center),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (isNowPlaying) {
            VideoProgressBar(
                fraction = fraction,
                modifier = Modifier.align(Alignment.BottomCenter)
            )
            PlayPauseButton(
                playing = playing,
                onToggle = { MediaPlayerController.togglePlayPause() },
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(40.dp)
            )
            StopButton(
                onStop = { MediaPlayerController.stop() },
                modifier = Modifier.align(Alignment.TopEnd)
            )
        } else if (hovered) {
            PlayPauseButton(
                playing = false,
                onToggle = { MediaPlayerController.play(token) },
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(42.dp)
            )
        }
    }
}


/**
 * A local audio file: a play/pause button beside its waveform — the
 * entire "display" (no title / artist). The waveform's bars fill with
 * the playback position while playing.
 */
@Composable
private fun AudioFileCard(
    token: MediaReferenceToken,
    isNowPlaying: Boolean,
    playing: Boolean,
    fraction: Float
) {
    val seed = token.content.hashCode()
    val bars = remember(seed) { waveformHeights(seed, 24) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .height(60.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        PlayPauseButton(
            playing = playing,
            onToggle = {
                if (isNowPlaying) {
                    MediaPlayerController.togglePlayPause()
                } else {
                    MediaPlayerController.play(token)
                }
            },
            modifier = Modifier.size(34.dp)
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            modifier = Modifier.weight(1f)
        ) {
            bars.forEachIndexed { index, height ->
                val filled = playing &&
                    (index + 1).toFloat() / bars.size <= fraction.coerceIn(0f, 1f)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(height)
                        .clip(RoundedCornerShape(2.dp))
                        .background(
                            if (filled) MediaAccent
                            else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
                        )
                )
            }
        }
        if (isNowPlaying) {
            StopButton(
                onStop = { MediaPlayerController.stop() },
                modifier = Modifier.size(22.dp)
            )
        }
    }
}


// ---------------------------------------------------------------------------
// Playback overlay primitives
// ---------------------------------------------------------------------------

/**
 * Bottom progress bar over 16:9 video thumbnails while playing: a dim
 * track with an accent fill whose width tracks [fraction].
 */
@Composable
private fun VideoProgressBar(fraction: Float, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(4.dp)
            .background(Color.Black.copy(alpha = 0.55f))
    ) {
        Box(
            // A minimum sliver keeps the bar visible the instant playback
            // starts (fraction is 0 until the first progress event).
            modifier = Modifier
                .fillMaxWidth(fraction.coerceIn(0.02f, 1f))
                .fillMaxHeight()
                .background(MediaAccent)
        )
    }
}


/**
 * Circular progress ring ("rounded bar going around the icon") used for
 * square album art and fallback icons. When [animate] is true (Spotify —
 * its embed reports no progress) the sweep spins indefinitely instead of
 * tracking [fraction].
 */
@Composable
private fun ProgressRing(fraction: Float, animate: Boolean, modifier: Modifier = Modifier) {
    val sweep = if (animate) {
        val transition = rememberInfiniteTransition(label = "spotify-spin")
        val s by transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing)),
            label = "ring-sweep"
        )
        s
    } else {
        360f * fraction.coerceIn(0f, 1f)
    }
    Canvas(modifier) {
        val strokeWidth = 4.dp.toPx()
        drawArc(
            color = Color.White.copy(alpha = 0.28f),
            startAngle = 0f,
            sweepAngle = 360f,
            useCenter = false,
            style = Stroke(strokeWidth, cap = StrokeCap.Round)
        )
        drawArc(
            color = MediaAccent,
            startAngle = -90f,
            sweepAngle = sweep,
            useCenter = false,
            style = Stroke(strokeWidth, cap = StrokeCap.Round)
        )
    }
}


/**
 * SoundCloud-style waveform fill ("coloring in the sound waves"): a row
 * of deterministic pseudo-random bars, accent-colored up to [fraction]
 * and dimmed beyond it.
 */
@Composable
private fun SoundWaveFill(fraction: Float, seed: Int, modifier: Modifier = Modifier) {
    val bars = remember(seed) { waveformHeights(seed, 18) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        modifier = modifier
            .fillMaxWidth()
            .height(26.dp)
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        bars.forEachIndexed { index, height ->
            val filled = (index + 1).toFloat() / bars.size <= fraction.coerceIn(0f, 1f)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(height)
                    .clip(RoundedCornerShape(2.dp))
                    .background(
                        if (filled) MediaAccent else Color.White.copy(alpha = 0.2f)
                    )
            )
        }
    }
}


/**
 * Round translucent button with a ▶ / ⏸ glyph. Used for the hover "play" *  overlay and as the persistent pause/play control while playing.
 */
@Composable
internal fun PlayPauseButton(
    playing: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.62f))
            .clickable(onClick = onToggle)
    ) {
        Canvas(Modifier.fillMaxSize().padding(9.dp)) {
            val w = size.width
            val h = size.height
            if (playing) {
                val barWidth = w * 0.16f
                val radius = CornerRadius(barWidth / 2f)
                drawRoundRect(
                    color = Color.White,
                    topLeft = Offset(w * 0.30f, h * 0.20f),
                    size = Size(barWidth, h * 0.60f),
                    cornerRadius = radius
                )
                drawRoundRect(
                    color = Color.White,
                    topLeft = Offset(w * 0.54f, h * 0.20f),
                    size = Size(barWidth, h * 0.60f),
                    cornerRadius = radius
                )
            } else {
                val path = Path().apply {
                    moveTo(w * 0.34f, h * 0.22f)
                    lineTo(w * 0.76f, h * 0.50f)
                    lineTo(w * 0.34f, h * 0.78f)
                    close()
                }
                drawPath(path, Color.White)
            }
        }
    }
}


/** Small round ✕-style stop control in the corner of a playing card and
 *  the embedded player. */
@Composable
internal fun StopButton(onStop: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(22.dp)
            .padding(4.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.62f))
            .clickable(onClick = onStop)
    ) {
        Canvas(Modifier.fillMaxSize().padding(5.dp)) {
            val w = size.width
            drawRoundRect(
                color = Color.White,
                topLeft = Offset(w * 0.22f, w * 0.22f),
                size = Size(w * 0.56f, w * 0.56f),
                cornerRadius = CornerRadius(2.dp.toPx())
            )
        }
    }
}


/**
 * Deterministic pseudo-random waveform bar heights (0.2..1.0) for the
 * SoundCloud progress fill. Seeded by the track's URL hash so a given
 * track always draws the same wave, and stable across recompositions.
 */
internal fun waveformHeights(seed: Int, count: Int): List<Float> {
    var s = seed
    return List(count) {
        s = s * 1103515245 + 12345
        val r = ((s ushr 16) and 0x7FFF) / 32767f
        0.2f + 0.8f * r
    }
}
