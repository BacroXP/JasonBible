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
import data.MediaPreviewFetcher
import data.MediaPreviewInfo
import data.MediaReferenceToken
import data.SoundEvent
import data.SoundManager
import data.readCapped
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL


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
    var failed by remember(token) { mutableStateOf(false) }
    var thumbnail by remember(token) { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(token) {
        val fetched = MediaPreviewFetcher.fetch(token)
        if (fetched == null) failed = true else info = fetched
    }
    LaunchedEffect(info) {
        thumbnail = info?.thumbnailUrl?.let { fetchImageBitmap(it) }
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
                Text(token.service.emoji, style = MaterialTheme.typography.titleMedium)
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

                info == null -> {
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
                    // Local snapshot: `info` is a delegated property, so
                    // smart-casting `info!!.title` across calls is not
                    // allowed — capture once and reuse.
                    val current = info
                    thumbnail?.let { bmp ->
                        Image(
                            bitmap = bmp,
                            contentDescription = current?.title,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(140.dp)
                                .clip(RoundedCornerShape(8.dp))
                        )
                    }
                    if (!current?.title.isNullOrBlank()) {
                        Text(
                            text = current!!.title,
                            style = MaterialTheme.typography.titleSmall.copy(
                                fontWeight = FontWeight.SemiBold
                            ),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (!current?.author.isNullOrBlank()) {
                        Text(
                            text = current!!.author,
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


/** Load a thumbnail into an [ImageBitmap] off the UI thread, or null on
 *  any failure (offline, unsupported format, timeout). The response is
 *  capped at 2 MB so a rogue URL can't balloon memory. */
internal suspend fun fetchImageBitmap(url: String): ImageBitmap? =
    withContext(Dispatchers.IO) {
        val conn = runCatching { URL(url).openConnection() }.getOrNull()
            ?: return@withContext null
        conn.connectTimeout = 4000
        conn.readTimeout = 4000
        // Some thumbnail CDNs reject the default Java user-agent.
        (conn as? HttpURLConnection)?.setRequestProperty("User-Agent", "BibleApp/1.0")
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

private const val MAX_THUMBNAIL_BYTES = 2 * 1024 * 1024
