package ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.graphics.decodeToImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupPositionProvider
import data.APP_USER_AGENT
import data.MediaPreviewFetcher
import data.MediaPreviewInfo
import data.MediaProfileInfo
import data.MediaReferenceToken
import data.SoundEvent
import data.SoundManager
import data.fetchProfileInfo
import data.isProfile
import data.readCapped
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.util.LinkedHashMap


/**
 * Open media-preview state: the tapped token plus the tap point in WINDOW
 * px, so the popup can anchor at the chip the user clicked.
 */
internal data class MediaPreviewState(
    val token: MediaReferenceToken,
    val anchorWindow: Offset
)


/**
 * Positions the preview popup at the tapped chip's window coordinates,
 * clamped so the card never runs off the window's bottom / right edge
 * (anchors near the border slide inward instead of clipping).
 */
internal class MediaPreviewPositionProvider(
    private val anchor: IntOffset
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize
    ): IntOffset {
        val maxX = (windowSize.width - popupContentSize.width).coerceAtLeast(0)
        val maxY = (windowSize.height - popupContentSize.height).coerceAtLeast(0)
        return IntOffset(
            anchor.x.coerceIn(0, maxX),
            anchor.y.coerceIn(0, maxY)
        )
    }
}


/**
 * The in-app media preview panel: a floating card anchored at the tapped
 * chip. It fetches a rich preview (title / thumbnail / author) from the
 * service's public oEmbed endpoint on a background thread, showing a
 * loading state while fetching and degrading gracefully to a plain
 * "no preview available" card offline or for links without an oEmbed
 * endpoint (e.g. generic URLs). "Open in browser" launches the resolved
 * URL in the default browser.
 */
@Composable
internal fun MediaPreviewCard(
    token: MediaReferenceToken,
    onClose: () -> Unit,
    onOpen: () -> Unit,
    onCopy: () -> Unit,
    onOpenGlobalSearch: () -> Unit = {}
) {
    val url = token.resolveUrl().orEmpty()
    var info by remember(token) { mutableStateOf<MediaPreviewInfo?>(null) }
    var profile by remember(token) { mutableStateOf<MediaProfileInfo?>(null) }
    var failed by remember(token) { mutableStateOf(false) }
    var thumbnail by remember(token) { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(token) {
        if (token.isProfile) {
            // Channels / users have no oEmbed — scrape name + avatar the
            // same way the media panel does, so the popup agrees with it.
            val fetched = fetchProfileInfo(token)
            if (fetched == null) failed = true else profile = fetched
        } else {
            val fetched = MediaPreviewFetcher.fetch(token)
            if (fetched == null) failed = true else info = fetched
        }
    }
    LaunchedEffect(info, profile) {
        thumbnail = info?.thumbnailUrl?.let { fetchImageBitmap(it) }
            ?: profile?.avatarUrl?.let { fetchImageBitmap(it) }
    }

    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    // This popup is focusable (takes focus from the main window), so
    // Ctrl+F reaches here instead of the Navigation root handler: dismiss
    // the preview and open the global search.
    val dialogKeyHandler = globalSearchDialogKeyHandler(onClose, onOpenGlobalSearch)
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 10.dp,
        tonalElevation = 4.dp,
        modifier = Modifier
            .widthIn(min = 260.dp, max = 340.dp)
            .onPreviewKeyEvent(dialogKeyHandler)
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(14.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = token.service.icon,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = muted
                )
                Text(
                    text = token.service.label,
                    style = MaterialTheme.typography.labelLarge,
                    color = muted,
                    modifier = Modifier
                        .padding(start = 6.dp)
                        .weight(1f)
                )
                Text(
                    text = "\u2715",
                    style = MaterialTheme.typography.titleMedium,
                    color = muted,
                    modifier = Modifier
                        .clickable {
                            SoundManager.play(SoundEvent.Click)
                            onClose()
                        }
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }

            when {
                failed -> {
                    Text(
                        text = "No preview available for this link.",
                        style = MaterialTheme.typography.bodySmall,
                        color = muted
                    )
                    Text(
                        text = url,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Profile fetches populate `profile` (never `info`), so the
                // loading state ends only once BOTH are null — otherwise a
                // profile would sit on "Loading preview…" forever.
                info == null && profile == null -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                        Text(
                            text = "Loading preview\u2026",
                            style = MaterialTheme.typography.bodySmall,
                            color = muted
                        )
                    }
                }

                else -> {
                    // Local snapshots: `info` / `profile` are delegated
                    // properties, so reading them once avoids the
                    // smart-cast limitation on delegated vars.
                    val title = profile?.name ?: info?.title
                    val author = if (profile != null) null else info?.author
                    thumbnail?.let { bmp ->
                        Image(
                            bitmap = bmp,
                            contentDescription = title,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(140.dp)
                                .clip(RoundedCornerShape(8.dp))
                        )
                    }
                    if (!title.isNullOrBlank()) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleSmall.copy(
                                fontWeight = FontWeight.SemiBold
                            ),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    // Profiles: the scraped verification badge ("✓ Verified"
                    // / "✓ Official Artist") under the name, like the panel.
                    profile?.badge?.let { badge ->
                        VerifiedBadge(
                            text = badge,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                    // Profiles: the scraped follower / subscriber count
                    // ("1.23M subscribers", "114.6M monthly listeners")
                    // under the badge, matching the panel's profile card.
                    profile?.followerCount?.takeIf { it.isNotBlank() }?.let { count ->
                        Text(
                            text = count,
                            style = MaterialTheme.typography.bodySmall,
                            color = MediaAccent,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                    if (!author.isNullOrBlank()) {
                        Text(
                            text = author,
                            style = MaterialTheme.typography.bodySmall,
                            color = muted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    modifier = Modifier.onPreviewKeyEvent(dialogKeyHandler),
                    onClick = onOpen
                ) { Text("Open in browser") }
                TextButton(
                    modifier = Modifier.onPreviewKeyEvent(dialogKeyHandler),
                    onClick = onCopy
                ) { Text("Copy link") }
            }
        }
    }
}


// ---------------------------------------------------------------------------
// Network image cache
//
// Thumbnails / avatars are re-fetched whenever a note is re-opened, so
// decoded bitmaps are cached per URL for the app session (access-ordered
// LRU, capped so a session browsing many links can't grow memory without
// bound). Images change rarely — a session-stale thumbnail is a fair
// price for not re-downloading every card on every note switch. Failed
// fetches are cached as null too, so a dead URL isn't retried on every
// re-open. Mirrors the data layer's [data.ProfileInfoCache].
// ---------------------------------------------------------------------------
private const val MAX_CACHED_IMAGES = 48

private val imageCache =
    object : LinkedHashMap<String, ImageBitmap?>(32, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, ImageBitmap?>
        ): Boolean = size > MAX_CACHED_IMAGES
    }


/** Load a thumbnail into an [ImageBitmap] off the UI thread, or null on
 *  any failure (offline, unsupported format, timeout). The response is
 *  capped at 2 MB so a rogue URL can't balloon memory. Results are
 *  cached per URL (see [imageCache]) so re-opening a note serves the
 *  previously decoded bitmap instantly. */
internal suspend fun fetchImageBitmap(url: String): ImageBitmap? {
    // Guarded because the cache is a plain LinkedHashMap and this is a
    // public suspend API that could be called from any dispatcher (all
    // current callers resume on main, but the guard keeps that an
    // implementation detail rather than a requirement).
    if (imageCache.containsKey(url)) {
        return synchronized(imageCache) { imageCache[url] }
    }
    val fetched = withContext(Dispatchers.IO) {
        val conn = runCatching { URL(url).openConnection() }.getOrNull()
            ?: return@withContext null
        conn.connectTimeout = 4000
        conn.readTimeout = 4000
        // Some thumbnail CDNs reject the default Java user-agent.
        (conn as? HttpURLConnection)?.setRequestProperty("User-Agent", APP_USER_AGENT)
        val bytes: ByteArray? = try {
            conn.getInputStream().use { input ->
                readCapped(input, MAX_THUMBNAIL_BYTES)
            }
        } catch (_: Exception) {
            null
        }
        bytes?.let { raw ->
            runCatching { raw.decodeToImageBitmap() }.getOrNull()
        }
    }
    synchronized(imageCache) { imageCache[url] = fetched }
    return fetched
}

private const val MAX_THUMBNAIL_BYTES = 2 * 1024 * 1024

// Local images can legitimately be larger than remote thumbnails, but
// decoding is capped all the same so a huge / malformed file can't
// balloon memory.
private const val MAX_LOCAL_IMAGE_BYTES = 32 * 1024 * 1024


/** Load a local image file into an [ImageBitmap] off the UI thread, or
 *  null on any failure (unsupported format, truncated / unreadable file,
 *  oversized). Used for embedded `@file:` images in the media panel. */
internal suspend fun fetchImageBitmapFromFile(path: String): ImageBitmap? =
    withContext(Dispatchers.IO) {
        runCatching {
            Files.newInputStream(Path.of(path)).use { input ->
                readCapped(input, MAX_LOCAL_IMAGE_BYTES)
            }.decodeToImageBitmap()
        }.getOrNull()
    }
