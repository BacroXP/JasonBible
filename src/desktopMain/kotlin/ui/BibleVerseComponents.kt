@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class
)

package ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.draganddrop.dragAndDropSource
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draganddrop.DragAndDropTransferAction
import androidx.compose.ui.draganddrop.DragAndDropTransferData
import androidx.compose.ui.draganddrop.DragAndDropTransferable
import data.BibleCatalog
import data.NotesRepository
import data.SettingsManager
import data.SoundEvent
import data.SoundManager
import model.Book
import model.Verse
import java.awt.datatransfer.StringSelection
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds



private enum class MarkerColor(
    val hex: String, val color: Color
) {
    Yellow("#FFF59D", Color(0xFFFFF176)),
    Green("#A5D6A7", Color(0xFF81C784)),
    Blue("#90CAF9", Color(0xFF64B5F6)),
    Purple("#CE93D8", Color(0xFFBA68C8)),
    Red("#EF9A9A", Color(0xFFE57373))
}
@Composable
internal fun BookCard(
    book: Book,
    starred: Boolean,
    onOpen: () -> Unit,
    onToggleStar: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .dragAndDropSource {
                DragAndDropTransferData(
                    transferable = DragAndDropTransferable(
                        StringSelection(bookTag(book.name))
                    ),
                    supportedActions = listOf(DragAndDropTransferAction.Copy)
                )
            }
            .clickable { onOpen() }
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(14.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = book.name,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )

                StarGlyph(
                    starred = starred,
                    onClick = onToggleStar
                )
            }

            Text(
                text = "${book.chapters.size} chapters",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}


@Composable
internal fun ChapterCard(
    book: Book,
    chapterNumber: Int,
    starred: Boolean,
    onOpen: () -> Unit,
    onToggleStar: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 100.dp)
            .dragAndDropSource {
                DragAndDropTransferData(
                    transferable = DragAndDropTransferable(
                        StringSelection(chapterTag(book.name, chapterNumber))
                    ),
                    supportedActions = listOf(DragAndDropTransferAction.Copy)
                )
            }
            .clickable { onOpen() }
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(14.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Ch. $chapterNumber",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )

                StarGlyph(
                    starred = starred,
                    onClick = onToggleStar
                )
            }

            Text(
                text = book.name,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}


@Composable
internal fun ChapterJumpStrip(
    book: Book,
    chapterNumber: Int,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onScrub: (Int) -> Unit,
    onScrollToTop: () -> Unit
) {
    val totalChapters = book.chapters.size
    val hasNext = chapterNumber < totalChapters
    val hasPrev = chapterNumber > 1

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(
            modifier = Modifier
                .size(width = 72.dp, height = 44.dp)
                .clickable(enabled = hasPrev) { onPrev() },
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "← Prev",
                style = MaterialTheme.typography.titleSmall,
                color = if (hasPrev) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
                }
            )
        }

        // The chapter label doubles as a "Top" affordance — clicking it
        // scrolls the verses list back to the top of the active chapter.
        // Persistence happens automatically via the value-keyed LaunchedEffect
        // in BibleScreen (scrollOffsets["$book:$chapter"] updates on the
        // resulting value change).
        Text(
            text = "↑ Ch. $chapterNumber / $totalChapters",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .padding(horizontal = 4.dp)
                .clickable { onScrollToTop() }
        )

        Slider(
            value = chapterNumber.toFloat(),
            onValueChange = { v ->
                onScrub(v.toInt().coerceIn(1, totalChapters.coerceAtLeast(1)))
            },
            valueRange = 1f..totalChapters.toFloat().coerceAtLeast(1f),
            steps = (totalChapters - 2).coerceAtLeast(0),
            enabled = totalChapters > 1,
            modifier = Modifier.weight(1f)
        )

        Box(
            modifier = Modifier
                .size(width = 72.dp, height = 44.dp)
                .clickable(enabled = hasNext) { onNext() },
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "Next →",
                style = MaterialTheme.typography.titleSmall,
                color = if (hasNext) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
                }
            )
        }
    }
}


@Composable
internal fun VerseRow(
    bookNumber: Int,
    bookName: String,
    chapterNumber: Int,
    verseNumber: Int,
    text: String,
    selected: Boolean,
    hoverHighlighted: Boolean = false,
    onOpenNoteTitle: (String, BibleReferenceSelection?) -> Unit,
    onClick: () -> Unit,
    onToggleMarker: (String?) -> Unit,
    onVersePositioned: (Int, Int) -> Unit = { _, _ -> },
    // Word study: when the active translation carries Strong's markup the
    // verse renders clickable word tokens; these carry which token's panel
    // is open and how to toggle it.
    strongsLoaded: Boolean = false,
    activeStudyWord: StudyWordToken? = null,
    onToggleStudyWord: (StudyWordToken) -> Unit = {},
    /** Opens the central lexicon at a Strong's number (wired by the host
     *  screen; defaults to no-op). */
    onOpenLexicon: (String) -> Unit = {},
    // Interlinear: the matching Greek TR verse (trparsed) to render
    // beneath the English text, or null when the interlinear view is off
    // or the Greek module has no verse for this reference.
    interlinearGreek: String? = null,
    // Word-aligned interlinear: when true (and the active translation
    // carries Strong's markup), the Greek tokens are paired with the
    // English word sharing their G-number instead of showing a plain
    // Greek line. Verses without tokens on either side fall back to the
    // plain line.
    interlinearAligned: Boolean = false
) {
    var hovered by remember(bookNumber, chapterNumber, verseNumber) {
        mutableStateOf(false)
    }

    val noteTitles = NotesRepository.titlesForVerse(
        bookName,
        chapterNumber,
        verseNumber
    )
    val markerPreview = SettingsManager.getVerseMarkerColor(
        bookNumber,
        chapterNumber,
        verseNumber
    )

    // Clipboard text for the copy action: "Book C:V — text". Strong's
    // markup (if any) is stripped so the copy reads as clean prose.
    val copyText = remember(bookName, chapterNumber, verseNumber, text) {
        verseCopyText(bookName, chapterNumber, verseNumber, stripWordStudyMarkup(text))
    }

    // The word currently being studied, if it lives in this verse.
    val activeForThisVerse = activeStudyWord?.takeIf {
        it.bookNumber == bookNumber &&
            it.chapter == chapterNumber &&
            it.verse == verseNumber
    }

    // Shared word-study toggle for the English text, the Greek line and
    // the word-aligned mode: reports which token's panel should open.
    val toggleStudyWord: (StrongsToken) -> Unit = { token ->
        onToggleStudyWord(
            StudyWordToken(
                bookNumber = bookNumber,
                chapter = chapterNumber,
                verse = verseNumber,
                word = token.word,
                number = token.number,
                parsing = token.parsing
            )
        )
    }

    val highlightColor = when {
        markerPreview != null && (selected || hoverHighlighted) -> colorFromHexInternal(markerPreview).copy(alpha = 0.32f)
        markerPreview != null -> colorFromHexInternal(markerPreview).copy(alpha = 0.18f)
        selected -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.8f)
        hoverHighlighted -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f)
        hovered -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f)
        else -> Color.Transparent
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        // Report this verse's Y position inside the scrolling Column so the
        // parent BibleScreen can scroll-to-verse when a tag-driven navigation
        // lands on this verse. The callback is a no-op for non-tracking
        // callers (e.g. when VerseRow is rendered outside the reading view).
        //
        // Compose 1.9 removed `LayoutCoordinates.positionInParent()`; the
        // replacement is `parentLayoutCoordinates.localPositionOf(this,
        // Offset.Zero)` which gives the same offset (position relative to
        // the immediate parent, here the scrolling verses Column).
        modifier = Modifier.onGloballyPositioned { coords ->
            val localY = coords.parentLayoutCoordinates
                ?.localPositionOf(coords, Offset.Zero)
                ?.y
                ?.toInt()
                ?: 0
            onVersePositioned(verseNumber, localY)
        }
    ) {
        Card(
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = highlightColor),
            modifier = Modifier
                .fillMaxWidth()
                .dragAndDropSource {
                    DragAndDropTransferData(
                        transferable = DragAndDropTransferable(
                            StringSelection(
                                verseTag(
                                    bookName = bookName,
                                    chapterNumber = chapterNumber,
                                    verseNumber = verseNumber
                                )
                            )
                        ),
                        supportedActions = listOf(DragAndDropTransferAction.Copy)
                    )
                }
                .onPointerEvent(PointerEventType.Enter) { hovered = true }
                .onPointerEvent(PointerEventType.Exit) { hovered = false }
                .clickable { onClick() }
        ) {
            Box {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.padding(14.dp)
                ) {
                    Card(
                        shape = RoundedCornerShape(10.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = Color(0xFFFFC107).copy(alpha = 0.18f)
                        )
                    ) {
                        Text(
                            text = verseNumber.toString(),
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.labelLarge,
                            color = Color(0xFFFFB300)
                        )
                    }

                    Column(
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        // Strong's-enabled translations ("KJV with Strongs"
                        // with `word{G####}` markup, or the parsed Greek
                        // module with `word G#### CODE` tokens) carry a
                        // clickable token per word; render those verses with
                        // the word-study text. Verses without markup keep the
                        // plain rendering.
                        val strongsTokens = remember(text) { parseWordStudyTokens(text) }
                        if (strongsTokens.isEmpty()) {
                            Text(
                                text = text,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        } else {
                            StrongsVerseText(
                                text = text,
                                tokens = strongsTokens,
                                activeNumber = activeForThisVerse?.number,
                                onToggle = toggleStudyWord
                            )
                        }

                        // Interlinear: the Greek TR verse beneath the English
                        // text, rendered by the shared [VerseInterlinear] so
                        // the chapter view and the whole-book continuous
                        // reading view always show the same LINE / word-
                        // aligned output. Clickable Strong's numbers feed the
                        // same word-study panel as the English side.
                        interlinearGreek?.let { greekText ->
                            VerseInterlinear(
                                englishText = text,
                                greekText = greekText,
                                aligned = interlinearAligned,
                                activeNumber = activeForThisVerse?.number,
                                onToggleStudyWord = toggleStudyWord
                            )
                        }

                        if (noteTitles.isNotEmpty()) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                noteTitles.forEach { title ->
                                    NoteChip(
                                        text = title,
                                        onClick = {
                                            onOpenNoteTitle(
                                                title,
                                                BibleReferenceSelection(
                                                    book = bookName,
                                                    chapter = chapterNumber,
                                                    verse = verseNumber
                                                )
                                            )
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                // Quick copy affordance, overlaid top-right on hover so the
                // verse text never re-flows when it appears.
                if (hovered) {
                    CopyPill(
                        copyText = copyText,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp)
                    )
                }
            }
        }

        if (selected) {
            VerseMarkerPanel(
                bookName = bookName,
                bookNumber = bookNumber,
                chapterNumber = chapterNumber,
                verseNumber = verseNumber,
                currentColorHex = markerPreview,
                copyText = copyText,
                onOpenNoteTitle = onOpenNoteTitle,
                onPickColor = onToggleMarker
            )
        }

        if (activeForThisVerse != null) {
            WordStudyPanel(
                token = activeForThisVerse,
                loaded = strongsLoaded,
                onClose = { onToggleStudyWord(activeForThisVerse) },
                onOpenLexicon = onOpenLexicon
            )
        }
    }
}


@Composable
private fun VerseMarkerPanel(
    bookName: String,
    bookNumber: Int,
    chapterNumber: Int,
    verseNumber: Int,
    currentColorHex: String?,
    copyText: String,
    onOpenNoteTitle: (String, BibleReferenceSelection?) -> Unit,
    onPickColor: (String?) -> Unit
) {
    val references = NotesRepository.referencesForVerse(bookName, chapterNumber, verseNumber)

    // Custom-color picker: opened from the rainbow dot, seeded with the
    // verse's current marker color (or a fresh yellow). The hex field
    // beneath the dots applies a typed color immediately.
    var pickerOpen by remember { mutableStateOf(false) }
    var hexText by remember(bookNumber, chapterNumber, verseNumber, currentColorHex) {
        mutableStateOf(currentColorHex?.uppercase() ?: "#FFD54F")
    }

    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(14.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Referenced notes",
                    style = MaterialTheme.typography.titleMedium
                )
                CopyPill(copyText = copyText)
            }

            if (references.isEmpty()) {
                Text(
                    text = "No notes reference this verse.",
                    style = MaterialTheme.typography.bodySmall
                )
            } else {
                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    references.forEach { ref ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            NoteChip(
                                text = ref.noteTitle,
                                onClick = {
                                    onOpenNoteTitle(
                                        ref.noteTitle,
                                        BibleReferenceSelection(
                                            book = bookName,
                                            chapter = chapterNumber,
                                            verse = verseNumber
                                        )
                                    )
                                }
                            )
                            ref.label?.let {
                                Text(text = it)
                            }
                        }
                    }
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                MarkerColor.entries.forEach { marker ->
                    ColorDot(
                        color = marker.color,
                        onClick = {
                            SoundManager.play(SoundEvent.Click)
                            onPickColor(marker.hex)
                        }
                    )
                }

                ColorDot(
                    color = MaterialTheme.colorScheme.outline,
                    label = "×",
                    onClick = {
                        SoundManager.play(SoundEvent.Click)
                        onPickColor(null)
                    }
                )

                // Custom color: rainbow dot opens the picker dialog;
                // when the verse uses a non-preset color, the dot shows it.
                val custom = currentColorHex?.takeIf { hex ->
                    MarkerColor.entries.none { it.hex.equals(hex, ignoreCase = true) }
                }
                Box(contentAlignment = Alignment.Center) {
                    if (custom != null) {
                        ColorDot(
                            color = colorFromHexInternal(custom),
                            onClick = { pickerOpen = true }
                        )
                    } else {
                        RainbowDot(
                            modifier = Modifier.size(28.dp),
                            onClick = { pickerOpen = true }
                        )
                    }
                }
            }

            // Hex color field: type a #RRGGBB value to apply it directly
            // (Enter commits; the field border turns red while invalid).
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                HexColorField(
                    value = hexText,
                    onValueChange = { raw ->
                        hexText = sanitizeHexInput(raw)
                        if (isValidMarkerHex(hexText)) {
                            SoundManager.play(SoundEvent.Click)
                            onPickColor(hexText)
                        }
                    },
                    onCommit = {
                        if (isValidMarkerHex(hexText)) {
                            SoundManager.play(SoundEvent.Click)
                            onPickColor(hexText)
                        }
                    },
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "Custom",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    if (pickerOpen) {
        ColorPickerDialog(
            initialHex = currentColorHex,
            onDismiss = { pickerOpen = false },
            onPick = { hex ->
                SoundManager.play(SoundEvent.Click)
                onPickColor(hex)
                pickerOpen = false
            }
        )
    }
}


@Composable
private fun ColorDot(
    color: Color,
    label: String? = null,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(28.dp)
            .hoverable(remember { MutableInteractionSource() })
            .background(color.copy(alpha = 0.8f), CircleShape)
            .clickable {
                SoundManager.play(SoundEvent.Click)
                onClick()
            },
        contentAlignment = Alignment.Center
    ) {
        if (label != null) {
            Text(
                text = label,
                color = Color.White,
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}


@Composable
private fun NoteChip(
    text: String,
    onClick: () -> Unit
) {
    val hoverSource = remember { MutableInteractionSource() }
    val isHovered by hoverSource.collectIsHoveredAsState()
    LaunchedEffect(isHovered) {
        if (isHovered) {
            delay(60.milliseconds)
            SoundManager.play(SoundEvent.Hover)
        }
    }
    Card(
        shape = PillShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        modifier = Modifier
            .hoverable(hoverSource)
            .clickable {
                SoundManager.play(SoundEvent.Click)
                onClick()
            }
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelSmall
        )
    }
}


@Composable
private fun StarGlyph(
    starred: Boolean,
    onClick: () -> Unit
) {
    val hoverSource = remember { MutableInteractionSource() }
    val isHovered by hoverSource.collectIsHoveredAsState()
    LaunchedEffect(isHovered) {
        if (isHovered) {
            delay(60.milliseconds)
            SoundManager.play(SoundEvent.Hover)
        }
    }
    Text(
        text = if (starred) "★" else "☆",
        color = Color(0xFFFFC107),
        style = MaterialTheme.typography.titleLarge,
        modifier = Modifier
            .hoverable(hoverSource)
            .clickable {
                SoundManager.play(SoundEvent.Click)
                onClick()
            }
    )
}


/**
 * Formats a verse for clipboard output: `Book C:V — text`. When the
 * "include translation name" setting is on, the active translation's
 * display name is appended, e.g. `John 3:16 — … (Luther Bible 1912)`.
 */
private fun verseCopyText(bookName: String, chapter: Int, verse: Int, text: String): String =
    "${bookName.trim()} $chapter:$verse — ${text.trim()}${copyTranslationSuffix()}"


/**
 * Formats a whole chapter for clipboard output: `Book C` followed by each
 * verse on its own line as `N. verse text`. Strong's markup (if any) is
 * stripped per verse so the copy reads as clean prose, matching the
 * per-verse copy action.
 */
internal fun chapterCopyText(
    bookName: String,
    chapter: Int,
    verses: List<Verse>
): String {
    val body = verses.joinToString("\n") { verse ->
        "${verse.verse}. ${stripWordStudyMarkup(verse.text).trim()}"
    }
    return "${bookName.trim()} $chapter${copyTranslationSuffix()}\n$body"
}


/**
 * Formats a verse range for clipboard output: `Book C:From–To` followed
 * by each verse in the range on its own line as `N. verse text` (same
 * per-verse loop as [chapterCopyText]). [verses] is the chapter's full
 * verse list; the range is filtered by verse number.
 */
internal fun rangeCopyText(
    bookName: String,
    chapter: Int,
    from: Int,
    to: Int,
    verses: List<Verse>
): String {
    val body = verses
        .filter { it.verse in from..to }
        .joinToString("\n") { verse ->
            "${verse.verse}. ${stripWordStudyMarkup(verse.text).trim()}"
        }
    return "${bookName.trim()} $chapter:$from\u2013$to${copyTranslationSuffix()}\n$body"
}


/**
 * The translation-name suffix appended to copied verses / chapters /
 * ranges when the "include translation name" setting is on — e.g.
 * ` (Luther Bible 1912)`. Returns an empty string when the setting is
 * off or the active module has no display name.
 */
private fun copyTranslationSuffix(): String {
    if (!SettingsManager.copyWithTranslationName) return ""
    val name = BibleCatalog.entryFor(SettingsManager.translation)?.displayName
        ?: return ""
    return " ($name)"
}


/**
 * Compact copy pill with transient "✓ Copied" feedback. Writes the
 * pre-formatted [copyText] to the system clipboard on click. Defaults to
 * the per-verse "Copy" label; chapter/other callers pass their own
 * label via [label].
 */
@Composable
internal fun CopyPill(
    copyText: String,
    modifier: Modifier = Modifier,
    label: String = "Copy"
) {
    val clipboard = LocalClipboard.current
    val clipboardScope = rememberCoroutineScope()
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1500.milliseconds)
            copied = false
        }
    }
    val hoverSource = remember { MutableInteractionSource() }
    val isHovered by hoverSource.collectIsHoveredAsState()
    LaunchedEffect(isHovered) {
        if (isHovered) {
            delay(60.milliseconds)
            SoundManager.play(SoundEvent.Hover)
        }
    }
    Text(
        text = if (copied) "✓ Copied" else label,
        style = MaterialTheme.typography.labelSmall,
        color = if (copied) {
            MaterialTheme.colorScheme.onSecondaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        modifier = modifier
            .hoverable(hoverSource)
            .clickable {
                SoundManager.play(SoundEvent.Click)
                clipboardScope.launch {
                    clipboard.setClipEntry(plainTextClipEntry(copyText))
                }
                copied = true
            }
            .background(
                color = if (copied) {
                    MaterialTheme.colorScheme.secondaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f)
                },
                shape = PillShape
            )
            .padding(horizontal = 10.dp, vertical = 5.dp)
    )
}


private fun bookTag(bookName: String): String {
    return "$" + bookName
}


private fun chapterTag(bookName: String, chapterNumber: Int): String {
    return "$" + bookName + "$" + chapterNumber
}


private fun verseTag(
    bookName: String,
    chapterNumber: Int,
    verseNumber: Int
): String {
    return "$" + bookName + "$" + chapterNumber + "$" + verseNumber
}


internal fun responsiveColumns(
    maxWidth: Dp,
    minCardWidth: Dp
): Int {
    return (maxWidth / minCardWidth)
        .toInt()
        .coerceIn(1, 6)
}


/**
 * The interlinear line shown beneath an English verse when the
 * interlinear toggle is on: the matching Greek TR verse in a softly
 * tinted card, with a small caption and clickable Strong's numbers (the
 * same [StrongsVerseText] rendering the English word-study uses).
 */
@Composable
private fun InterlinearGreekLine(
    greekText: String,
    activeNumber: String?,
    onToggleStudyWord: (StrongsToken) -> Unit
) {
    val tokens = remember(greekText) { parseWordStudyTokens(greekText) }
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            Text(
                text = "Greek (TR) \u2014 click a number for word study",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (tokens.isEmpty()) {
                // A verse without parseable tokens (e.g. only variant
                // markers) still shows its text, italicised for contrast.
                Text(
                    text = greekText,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontStyle = FontStyle.Italic
                    )
                )
            } else {
                StrongsVerseText(
                    text = greekText,
                    tokens = tokens,
                    activeNumber = activeNumber,
                    onToggle = onToggleStudyWord
                )
            }
        }
    }
}


/**
 * Word-aligned interlinear: every English word of the verse gets a column
 * (Greek word on top, English gloss below), paired by Strong's number via
 * [alignGreekToEnglish]. Both words carry clickable G/H numbers feeding
 * the same word-study panel, and the word being studied is highlighted on
 * both sides. Columns without a counterpart on one side show a dimmed "·".
 */
@Composable
private fun AlignedInterlinear(
    englishTokens: List<StrongsToken>,
    greekTokens: List<StrongsToken>,
    activeNumber: String?,
    onToggleStudyWord: (StrongsToken) -> Unit
) {
    val pairs = remember(englishTokens, greekTokens) {
        alignGreekToEnglish(englishTokens, greekTokens)
    }
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            Text(
                text = "Greek (TR) \u00B7 word-aligned \u2014 click a number for word study",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            // Columns wrap like text: each is the Greek token above its
            // English gloss, so the whole verse stays readable inline.
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                pairs.forEach { pair ->
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                        modifier = Modifier.widthIn(max = 140.dp)
                    ) {
                        if (pair.greek != null) {
                            AlignedWordText(
                                token = pair.greek,
                                greek = true,
                                activeNumber = activeNumber,
                                onToggle = onToggleStudyWord
                            )
                        } else {
                            // English-only word (e.g. an article with no
                            // direct Greek counterpart): dimmed gap.
                            Text(
                                text = "\u00B7",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                    .copy(alpha = 0.4f)
                            )
                        }
                        if (pair.english != null) {
                            AlignedWordText(
                                token = pair.english,
                                greek = false,
                                activeNumber = activeNumber,
                                onToggle = onToggleStudyWord
                            )
                        } else {
                            // Untranslated Greek word (e.g. a particle):
                            // dimmed gap under it.
                            Text(
                                text = "\u00B7",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                    .copy(alpha = 0.4f)
                            )
                        }
                    }
                }
            }
        }
    }
}


/**
 * The interlinear block rendered beneath an English verse: the plain
 * Greek TR line in LINE mode, or word-aligned columns in ALIGNED mode —
 * falling back to the plain line when either side carries no Strong's
 * tokens to join (e.g. a translation without Strong's markup). Shared by
 * the chapter view (VerseRow) and the whole-book continuous reading view
 * so the two modes always look and behave identically.
 */
@Composable
internal fun VerseInterlinear(
    englishText: String,
    greekText: String,
    aligned: Boolean,
    activeNumber: String?,
    onToggleStudyWord: (StrongsToken) -> Unit
) {
    val englishTokens = remember(englishText) { parseStrongsTokens(englishText) }
    val greekTokens = remember(greekText) { parseParsedTokens(greekText) }
    if (aligned && englishTokens.isNotEmpty() && greekTokens.isNotEmpty()) {
        AlignedInterlinear(
            englishTokens = englishTokens,
            greekTokens = greekTokens,
            activeNumber = activeNumber,
            onToggleStudyWord = onToggleStudyWord
        )
    } else {
        InterlinearGreekLine(
            greekText = greekText,
            activeNumber = activeNumber,
            onToggleStudyWord = onToggleStudyWord
        )
    }
}


/**
 * One word of the aligned interlinear: the surface word followed by its
 * clickable Strong's number (superscript, like [StrongsVerseText]) and, on
 * the Greek side, the TVM code and morphological parsing. Clicking a number
 * feeds the word-study panel; the word being studied is highlighted.
 */
@Composable
private fun AlignedWordText(
    token: StrongsToken,
    greek: Boolean,
    activeNumber: String?,
    onToggle: (StrongsToken) -> Unit
) {
    val primary = MaterialTheme.colorScheme.primary
    val primaryContainer = MaterialTheme.colorScheme.primaryContainer
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    // The Greek word is the study focus (larger); the English gloss is a
    // small caption beneath it.
    val style = if (greek) {
        MaterialTheme.typography.bodySmall
    } else {
        MaterialTheme.typography.labelSmall
    }
    val numberSize = style.fontSize * 0.75f

    // Same hover/press feedback as StrongsVerseText's token spans.
    val linkStyles = TextLinkStyles(
        style = SpanStyle(color = primary, fontWeight = FontWeight.SemiBold),
        hoveredStyle = SpanStyle(
            color = primary,
            background = primaryContainer.copy(alpha = 0.35f)
        ),
        pressedStyle = SpanStyle(
            color = primary,
            background = primaryContainer
        )
    )

    // The token IS the whole column, so the listener captures it directly
    // (no source-offset lookup needed); the TVM span reuses the same
    // `:tvm` tag suffix convention as StrongsVerseText.
    val listener = LinkInteractionListener { link ->
        val clickable = link as? LinkAnnotation.Clickable
            ?: return@LinkInteractionListener
        val tvm = token.tvm
        if (clickable.tag.endsWith(TVM_TAG_SUFFIX) && tvm != null) {
            onToggle(token.copy(number = tvm))
        } else {
            onToggle(token)
        }
    }

    val wordActive = token.number == activeNumber || token.tvm == activeNumber
    val numberActive = token.number == activeNumber

    Text(
        text = buildAnnotatedString {
            if (wordActive) {
                withStyle(
                    SpanStyle(background = primaryContainer.copy(alpha = 0.3f))
                ) {
                    append(token.word)
                }
            } else {
                append(token.word)
            }
            val numberStart = length
            withStyle(
                SpanStyle(
                    color = primary,
                    fontSize = numberSize,
                    baselineShift = BaselineShift.Superscript,
                    background = if (numberActive) {
                        primaryContainer.copy(alpha = 0.5f)
                    } else {
                        Color.Unspecified
                    }
                )
            ) {
                append(token.number)
            }
            addLink(
                LinkAnnotation.Clickable(
                    tag = token.start.toString(),
                    styles = linkStyles,
                    linkInteractionListener = listener
                ),
                numberStart,
                length
            )
            token.tvm?.let { tvm ->
                val tvmStart = length
                val tvmActive = tvm == activeNumber
                withStyle(
                    SpanStyle(
                        color = primary,
                        fontSize = numberSize,
                        baselineShift = BaselineShift.Superscript,
                        background = if (tvmActive) {
                            primaryContainer.copy(alpha = 0.5f)
                        } else {
                            Color.Unspecified
                        }
                    )
                ) {
                    append(tvm)
                }
                addLink(
                    LinkAnnotation.Clickable(
                        tag = "${token.start}$TVM_TAG_SUFFIX",
                        styles = linkStyles,
                        linkInteractionListener = listener
                    ),
                    tvmStart,
                    length
                )
            }
            // Only the Greek side shows its morphological parsing code;
            // the English side's "(G5656)" would just duplicate the Greek
            // TVM number already shown above it.
            if (greek && token.parsing != null) {
                withStyle(
                    SpanStyle(
                        color = muted,
                        fontSize = style.fontSize * 0.6f,
                        baselineShift = BaselineShift.Superscript
                    )
                ) {
                    append(token.parsing)
                }
            }
        },
        style = style,
        textAlign = TextAlign.Center
    )
}

