@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class
)

package ui

import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.DragData
import androidx.compose.ui.draganddrop.dragData
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import data.SoundEvent
import data.SoundManager
import data.openExternalUrl
import kotlinx.coroutines.launch


// Right-click context-menu state: the reference under the cursor plus the
// anchor offset (Box-local px, converted to Dp) where the menu should pop
// up. Held while the menu is open, cleared on dismiss / text edits.
private data class ReferenceMenuState(
    val hit: ReferenceHit,
    val offset: DpOffset
)


@Composable
internal fun EditorSurface(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    visualTransformation: VisualTransformation,
    /**
     * Word-style view zoom (A− / A+). Scales the editor's rendered text
     * without touching the saved .note content. 1.0 = 100%.
     */
    fontScale: Float = 1f,
    /**
     * VerticalScroll state, hoisted from the parent so a parent-level
     * LaunchedEffect (see `pendingScrollReference` plumbing on
     * `NotesScreen`) can drive `animateScrollTo` to land on a
     * particular source offset. The editor still owns the
     * verticalScroll modifier internally — we just pass a state object
     * in instead of creating a private one here.
     */
    scrollState: ScrollState,
    /**
     * Forwards each `BasicTextField.onTextLayout` layout result upward
     * so the parent can map a source-text offset to a Y coordinate
     * (`TextLayoutResult.getLineForOffset(...)` + `getLineTop(...)`).
     * Called on every layout pass: text edits, scroll, viewport resize.
     */
    onLayoutResult: (TextLayoutResult) -> Unit = {},
    dropTarget: DragAndDropTarget,
    /**
     * Called when a reference chip is tapped. `hit` is null for taps on
     * plain text; for media references `anchor` is the tap point in
     * WINDOW px so the caller can anchor the in-app preview popup at the
     * chip (Bible taps pass Offset.Zero — the caller navigates instead).
     * `shiftPressed` is true when the Shift key was held during the tap
     * (an extended range chip then shows its verse picker instead of
     * jumping straight to the start verse).
     */
    onTapReference: (ReferenceHit?, anchor: Offset, shiftPressed: Boolean) -> Unit,
    onHoverBibleReference: (BibleReferenceSelection?) -> Unit,
    /**
     * Double-click word selection. `start` and `end` are source-text
     * byte offsets into `value.text`; the parent wires this through
     * `applyEditorChange` so the new TextRange goes through the
     * UndoManager / list-continuation recordChange pipeline.
     */
    onWordSelect: (start: Int, end: Int) -> Unit = { _, _ -> },
    onShortcut: (KeyEvent) -> Boolean,
    modifier: Modifier = Modifier
) {
    // Build a memoised reference index once per text edit (O(N)) instead of
    // re-running the regex over the entire note on every pointer-move frame.
    // Lookup is O(log lines) per move via binary search over `lineStarts`.
    val referenceLookup = remember(value.text) { buildReferenceLookup(value.text) }
    val cursorColor = MaterialTheme.colorScheme.primary
    val backgroundColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
    var layoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
    // Track the most recent cursor position in Box-local coordinates —
    // the hover lookup only re-runs on PointerEventType.Move, but the
    // user can scroll the editor with the mouse wheel or keyboard
    // without any pointer motion. When the text scrolls, BasicTextField
    // fires onTextLayout with a fresh TextLayoutResult; re-running the
    // lookup against that result with `lastHoverPos` keeps the
    // bible-reference hitbox anchored to the tag under the cursor
    // instead of pinned at its pre-scroll offset.
    var lastHoverPos by remember { mutableStateOf<Offset?>(null) }    // The reference currently under the pointer (or null). Drives the
    // hand-cursor affordance so hovered `$Book$C$V` chips AND media
    // (`@youtube:…`) chips read as clickable links; kept in sync from
    // `evaluateHover` below.
    var hoveredReference by remember { mutableStateOf<ReferenceHit?>(null) }
    // The editor's top-left corner in window coordinates, refreshed on
    // every layout pass. Used to convert Box-local tap positions into
    // window anchors for the media preview popup.
    var editorWindowPos by remember { mutableStateOf(Offset.Zero) }
    // Right-click context menu: the reference under the cursor plus the
    // menu's anchor position (Box-local px converted to Dp). Non-null
    // while the menu is open; opened from a secondary-button press in
    // the pointer handler below.
    var referenceMenu by remember { mutableStateOf<ReferenceMenuState?>(null) }
    // Editing the text invalidates any open menu's reference — close it.
    LaunchedEffect(value.text) { referenceMenu = null }
    val clipboard = LocalClipboard.current
    // Clipboard writes are async (suspend) in the new Compose API.
    val clipboardScope = rememberCoroutineScope()
    // Hoisted out of the pointer-input scope below (LocalDensity.current
    // is a @Composable call, illegal inside the suspend lambda) and used
    // to convert the px press position to the DpOffset the DropdownMenu
    // anchors at.
    val density = LocalDensity.current
    // Live keyboard state (Shift detection for the verse-range picker).
    // Same hoisting rationale: LocalWindowInfo.current is a @Composable
    // call, so it must be captured before the pointerInput lambdas.
    val windowInfo = LocalWindowInfo.current

    // Shared reference hit-test: map a Box-local pointer position to the
    // ReferenceHit under it (or null). Used by tap, double-tap, the
    // right-click context menu and the hover lookup below — every path
    // must agree on where a reference chip is.
    fun resolveReferenceAt(pos: Offset): ReferenceHit? {
        val layout = layoutResult ?: return null
        val displayed = layout.getOffsetForPosition(pos)
        val mapping = (visualTransformation as? NoteVisualTransformation)?.offsetMapping
        val source = mapping?.transformedToOriginal(displayed) ?: displayed
        return findReferenceInLookup(referenceLookup, source.coerceIn(0, value.text.length))
    }
    // Shared hover lookup: map a Box-local coordinate to the hovered
    // chip. Bible chips additionally signal the parent (the Bible pane
    // highlights the hovered verse); media chips only switch the cursor.
    // Called from both the PointerEventType.Move arm (cursor moves) and
    // the BasicTextField.onTextLayout callback (text edit / scroll / size
    // change) so the hit-box follows text scrolling past a parked cursor
    // without depending on the cursor actually moving.
    val evaluateHover: (Offset) -> Unit = hover@{ pos ->
        val hit = resolveReferenceAt(pos)
        hoveredReference = hit
        onHoverBibleReference(
            (hit as? ReferenceHit.Bible)?.let {
                BibleReferenceSelection(it.match.book, it.match.chapter, it.match.verse)
            }
        )
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = backgroundColor,
        modifier = modifier
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .onGloballyPositioned { editorWindowPos = it.positionInWindow() }
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .then(
                    if (hoveredReference != null) {
                        // Point cursor at hovered reference chips — the
                        // universal "this is a link" cue.
                        Modifier.pointerHoverIcon(PointerIcon.Hand)
                    } else {
                        Modifier
                    }
                )
                .pointerInput(visualTransformation, value.text) {
                    detectTapGestures(
                        onTap = { tapPos ->
                            // Window anchor = editor origin + Box-local tap
                            // point, so the caller can pop a preview next
                            // to the tapped chip. `windowInfo` tracks the
                            // live keyboard state (incl. Shift) so a
                            // Shift+tap on an extended reference chip can
                            // open the verse-range picker instead of
                            // navigating straight away.
                            onTapReference(
                                resolveReferenceAt(tapPos),
                                editorWindowPos + tapPos,
                                windowInfo.keyboardModifiers.isShiftPressed
                            )
                        },
                        onDoubleTap = { tapPos ->
                            // Double-click selects the alphanumeric word
                            // under the cursor. `wordBoundsAt` returns an
                            // empty range when the user double-clicks on
                            // whitespace / punctuation, in which case we
                            // skip so the editor's existing
                            // BasicTextField cursor behavior wins.
                            val layout = layoutResult
                            if (layout != null) {
                                val displayedOffset = layout.getOffsetForPosition(tapPos)
                                val mapping = (visualTransformation as? NoteVisualTransformation)?.offsetMapping
                                val sourceOffset = mapping?.transformedToOriginal(displayedOffset)
                                    ?: displayedOffset
                                val range = wordBoundsAt(
                                    value.text,
                                    sourceOffset.coerceAtLeast(0)
                                )
                                if (range.first != range.last) {
                                    onWordSelect(range.first, range.last + 1)
                                }
                            }
                        }
                    )
                }
                .pointerInput(value.text, visualTransformation) {
                    awaitEachGesture {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Main)
                            when (event.type) {
                                PointerEventType.Move -> {
                                    val change = event.changes.firstOrNull()
                                    if (change != null) {
                                        lastHoverPos = change.position
                                        evaluateHover(change.position)
                                    }
                                }
                                PointerEventType.Press -> {
                                    // Right-click (secondary button) on a
                                    // Bible-reference chip opens a context
                                    // menu with "Open in Bible" / "Copy
                                    // reference". Consuming the press keeps
                                    // BasicTextField from moving the caret.
                                    val change = event.changes.firstOrNull()
                                    if (change != null && event.buttons.isSecondaryPressed) {
                                        val hit = resolveReferenceAt(change.position)
                                        if (hit != null) {
                                            change.consume()
                                            referenceMenu = ReferenceMenuState(
                                                hit = hit,
                                                offset = with(density) {
                                                    DpOffset(
                                                        change.position.x.toDp(),
                                                        change.position.y.toDp()
                                                    )
                                                }
                                            )
                                        } else {
                                            // Right-click on plain text:
                                            // close any open menu.
                                            referenceMenu = null
                                        }
                                    }
                                }
                                PointerEventType.Exit -> {
                                    lastHoverPos = null
                                    hoveredReference = null
                                    onHoverBibleReference(null)
                                }
                                else -> Unit
                            }
                        }
                    }
                }
                .onPreviewKeyEvent { event -> onShortcut(event) }
                .dragAndDropTarget(
                    // Accept both plain text drops AND local media files
                    // (images / videos / audio, which NotesScreen imports
                    // and embeds as `@file:` tokens).
                    shouldStartDragAndDrop = { event ->
                        val data = event.dragData()
                        data is DragData.Text || data is DragData.FilesList
                    },
                    target = dropTarget
                )
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                // The scroll modifier is what makes the editor actually
                // scrollable — BasicTextField does not scroll on its own.
                // It also gives `onTextLayout` a viewport-change trigger
                // (the displayed text portion shifts), which is what
                // powers the onTextLayout-driven hover re-evaluation in
                // the outer pointerInput below. `scrollState` is hoisted
                // from the parent so cross-pane navigation can
                // animate-scroll the editor to a specific verse line.
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .drawWithContent {
                        // Rounded chip pills behind reference ("Book C:V")
                        // and note-link ("[[Title]]") chips.
                        // `SpanStyle.background` can only paint a flat
                        // rectangle (no corner radius), so the chips are
                        // drawn here: the transformation records the
                        // displayed ranges of every chip, and we map them
                        // to pixel rects via the current TextLayoutResult.
                        // This modifier sits INSIDE verticalScroll, so the
                        // pills share the text's coordinate space and
                        // scroll along with it.
                        val vt = visualTransformation as? NoteVisualTransformation
                        val layout = layoutResult
                        if (vt != null && layout != null) {
                            val textLen = layout.layoutInput.text.length
                            val corner = CornerRadius(5.dp.toPx())
                            val padX = 2.dp.toPx()
                            fun drawPills(ranges: List<IntRange>, color: Color) {
                                for (range in ranges) {
                                    if (range.first >= textLen) continue
                                    val start = range.first.coerceAtMost(textLen - 1)
                                    val end = (range.last + 1).coerceAtMost(textLen)
                                    if (end <= start) continue
                                    // A chip may wrap across lines (long
                                    // book names in a narrow pane). Split
                                    // it into per-line segments so each
                                    // gets its own pill instead of one
                                    // giant rect spanning the inter-line
                                    // gap.
                                    var segStart = start
                                    while (segStart < end) {
                                        val line = layout.getLineForOffset(segStart)
                                        val lineEnd = minOf(end, layout.getLineEnd(line, visibleEnd = false))
                                        // Vertical bounds from line metrics
                                        // so a taller middle glyph
                                        // (ascender / descender) can't poke
                                        // out of the pill's top or bottom
                                        // edge.
                                        val top = layout.getLineTop(line)
                                        val bottom = layout.getLineBottom(line)
                                        val firstBox = layout.getBoundingBox(segStart)
                                        val lastBox = layout.getBoundingBox(lineEnd - 1)
                                        val left = minOf(firstBox.left, lastBox.left) - padX
                                        val right = maxOf(firstBox.right, lastBox.right) + padX
                                        if (right > left && bottom > top) {
                                            drawRoundRect(
                                                color = color,
                                                topLeft = Offset(left, top),
                                                size = Size(right - left, bottom - top),
                                                cornerRadius = corner
                                            )
                                        }
                                        segStart = lineEnd
                                    }
                                }
                            }
                            drawPills(vt.referenceChipRanges, vt.referenceChipColor)
                            drawPills(vt.noteLinkChipRanges, vt.noteLinkChipColor)
                        }
                        drawContent()
                    },
                visualTransformation = visualTransformation,
                textStyle = run {
                    val base = MaterialTheme.typography.bodyLarge
                    base.copy(
                        fontSize = (base.fontSize.value * fontScale).sp,
                        lineHeight = (base.lineHeight.value * fontScale).sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontFamily = FontFamily.Default
                    )
                },
                cursorBrush = SolidColor(cursorColor),
                onTextLayout = { result ->
                    // Layout pass (text edit / scroll / viewport resize).
                    // Cache the new TextLayoutResult and, when the cursor
                    // is parked over the editor surface, re-run the same
                    // Bible-reference lookup against this fresh layout so
                    // the hitbox follows the visible tag during scroll.
                    // Also forward the result upward so the parent
                    // LaunchedEffect can compute Y positions for
                    // `animateScrollTo` when a NoteChip click jumps the
                    // editor to a matching verse line.
                    layoutResult = result
                    onLayoutResult(result)
                    lastHoverPos?.let { pos -> evaluateHover(pos) }
                },
                decorationBox = { inner ->
                    if (value.text.isEmpty()) {
                        val base = MaterialTheme.typography.bodyLarge
                        Text(
                            text = "Write freely. Use the toolbar to add headings, quotes, lists, colors and Bible references.",
                            style = MaterialTheme.typography.bodyLarge.copy(
                                fontSize = (base.fontSize.value * fontScale).sp,
                                lineHeight = (base.lineHeight.value * fontScale).sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                            )
                        )
                    }
                    inner()
                }
            )

            // Right-click context menu for Bible-reference chips. Anchored
            // at the secondary-button press position; both actions close
            // the menu (the DropdownMenu's own outside-click / Esc handling
            // covers every other dismissal path).
            referenceMenu?.let { menu ->
                DropdownMenu(
                    expanded = true,
                    offset = menu.offset,
                    onDismissRequest = { referenceMenu = null }
                ) {
                    when (val hit = menu.hit) {
                        is ReferenceHit.Bible -> {
                            DropdownMenuItem(
                                text = { Text("Open in Bible") },
                                onClick = {
                                    SoundManager.play(SoundEvent.Click)
                                    referenceMenu = null
                                    onTapReference(hit, Offset.Zero, false)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Copy reference") },
                                onClick = {
                                    SoundManager.play(SoundEvent.Click)
                                    clipboardScope.launch {
                                        clipboard.setClipEntry(plainTextClipEntry(hit.match.displayText()))
                                    }
                                    referenceMenu = null
                                }
                            )
                        }
                        is ReferenceHit.Media -> {
                            DropdownMenuItem(
                                text = { Text("Open in browser") },
                                onClick = {
                                    SoundManager.play(SoundEvent.Click)
                                    openExternalUrl(hit.token.resolveUrl().orEmpty())
                                    referenceMenu = null
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Copy link") },
                                onClick = {
                                    SoundManager.play(SoundEvent.Click)
                                    clipboardScope.launch {
                                        clipboard.setClipEntry(plainTextClipEntry(hit.token.resolveUrl().orEmpty()))
                                    }
                                    referenceMenu = null
                                }
                            )
                        }
                        is ReferenceHit.Note -> {
                            DropdownMenuItem(
                                text = { Text("Open note") },
                                onClick = {
                                    SoundManager.play(SoundEvent.Click)
                                    referenceMenu = null
                                    onTapReference(hit, Offset.Zero, false)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Copy title") },
                                onClick = {
                                    SoundManager.play(SoundEvent.Click)
                                    clipboardScope.launch {
                                    clipboard.setClipEntry(plainTextClipEntry(hit.title))
                                }
                                    referenceMenu = null
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}



