@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package navigation

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import data.SoundEvent
import data.SoundManager

/** Page indexes of the phone shell. */
private const val PHONE_PAGE_EDITOR = 0
private const val PHONE_PAGE_BIBLE = 1

/** How far (px) a drag on the top bar counts as a page swipe. */
private const val PHONE_SWIPE_THRESHOLD = 60f

/** How much horizontal trackpad scroll counts as one page swipe. */
private const val PHONE_SCROLL_THRESHOLD = 3f


/**
 * The phone-first shell: the notes editor on the left, the Bible on the
 * right. Pages sit side by side and slide horizontally, so both stay
 * composed while switching (the editor never loses unsaved text).
 *
 * Switching works three ways:
 *  - **Swipe**: drag the top bar left / right.
 *  - **Trackpad**: a horizontal two-finger scroll.
 *  - **Tabs**: Files · Editor · Bible in the top bar.
 *
 * Swiping RIGHT on the editor (there is no page further left) opens the
 * notes file drawer, which is exactly what "going right on the editor"
 * should do on a phone.
 */
@Composable
internal fun PhoneShell(
    page: Int,
    onPageChange: (Int) -> Unit,
    onHome: () -> Unit,
    filesOpen: Boolean,
    onFilesOpenChange: (Boolean) -> Unit,
    notes: @Composable () -> Unit,
    bible: @Composable () -> Unit
) {
    fun goPrevious() {
        if (page == PHONE_PAGE_BIBLE) {
            onPageChange(PHONE_PAGE_EDITOR)
        } else {
            // Already on the leftmost page: open the files drawer instead.
            onFilesOpenChange(true)
        }
    }

    fun goNext() {
        if (page == PHONE_PAGE_EDITOR) onPageChange(PHONE_PAGE_BIBLE)
    }

    fun openFiles() {
        onPageChange(PHONE_PAGE_EDITOR)
        onFilesOpenChange(true)
    }

    // Accumulated horizontal trackpad / wheel delta. Committing on a small
    // threshold means a short two-finger flick is enough, like a phone.
    var scrollAccum by remember { mutableStateOf(0f) }

    Column(modifier = Modifier.fillMaxSize()) {
        Surface(tonalElevation = 3.dp) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .pointerInput(page) {
                        var dragTotal = 0f
                        detectHorizontalDragGestures(
                            onDragStart = { dragTotal = 0f },
                            onDragEnd = {
                                when {
                                    dragTotal > PHONE_SWIPE_THRESHOLD -> goPrevious()
                                    dragTotal < -PHONE_SWIPE_THRESHOLD -> goNext()
                                }
                                dragTotal = 0f
                            },
                            onDragCancel = { dragTotal = 0f }
                        ) { _, delta -> dragTotal += delta }
                    }
                    .padding(horizontal = 10.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                PhoneBarButton(text = "\u2630", onClick = onHome)
                PhoneBarButton(text = "Files", onClick = ::openFiles)
                PhoneBarTab(
                    text = "Editor",
                    selected = page == PHONE_PAGE_EDITOR && !filesOpen,
                    onClick = {
                        onFilesOpenChange(false)
                        onPageChange(PHONE_PAGE_EDITOR)
                    }
                )
                PhoneBarTab(
                    text = "Bible",
                    selected = page == PHONE_PAGE_BIBLE,
                    onClick = {
                        onFilesOpenChange(false)
                        onPageChange(PHONE_PAGE_BIBLE)
                    }
                )
            }
        }

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .onPointerEvent(PointerEventType.Scroll) { event ->
                    val dx = event.changes.firstOrNull()?.scrollDelta?.x ?: 0f
                    if (dx == 0f) return@onPointerEvent
                    scrollAccum += dx
                    when {
                        scrollAccum > PHONE_SCROLL_THRESHOLD -> {
                            scrollAccum = 0f
                            goPrevious()
                        }
                        scrollAccum < -PHONE_SCROLL_THRESHOLD -> {
                            scrollAccum = 0f
                            goNext()
                        }
                    }
                }
        ) {
            val pageWidth = maxWidth
            val pageWidthPx = with(LocalDensity.current) { pageWidth.toPx() }
            val offsetPx by animateFloatAsState(
                targetValue = if (page == PHONE_PAGE_EDITOR) 0f else -pageWidthPx,
                animationSpec = tween(durationMillis = 240),
                label = "phone-page"
            )
            Row(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(pageWidth * 2f)
                    .graphicsLayer { translationX = offsetPx }
            ) {
                Box(modifier = Modifier.width(pageWidth).fillMaxHeight()) {
                    notes()
                }
                Box(modifier = Modifier.width(pageWidth).fillMaxHeight()) {
                    bible()
                }
            }
        }
    }
}


/** A plain top-bar action (Home / Files). */
@Composable
private fun PhoneBarButton(text: String, onClick: () -> Unit) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .clickable {
                SoundManager.play(SoundEvent.Click)
                onClick()
            }
            .padding(horizontal = 6.dp, vertical = 2.dp)
    )
}


/** A top-bar tab; the current page is highlighted. */
@Composable
private fun PhoneBarTab(text: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = if (selected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        modifier = Modifier
            .clickable {
                SoundManager.play(SoundEvent.Click)
                onClick()
            }
            .padding(horizontal = 6.dp, vertical = 2.dp)
    )
}
