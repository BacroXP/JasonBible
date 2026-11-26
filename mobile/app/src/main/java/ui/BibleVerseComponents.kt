@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class
)

package ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import data.BibleCatalog
import data.NotesRepository
import data.SettingsManager
import data.SoundEvent
import data.SoundManager
import model.Book
import model.Verse
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.milliseconds



/**
 * One book in a testament's list: a single full-width row with the gold `01`
 * gutter number, the serif book name and its chapter count, so the books
 * read as one numbered column instead of a tile grid. Drag it into a note
 * to insert a `$Book` reference.
 */
@Composable
internal fun BookCard(
    book: Book,
    index: Int,
    starred: Boolean,
    readChapters: Int = 0,
    onOpen: () -> Unit,
    onToggleStar: () -> Unit
) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (hovered) {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        } else {
            Color.Transparent
        },
        border = BorderStroke(
            width = 1.dp,
            color = if (hovered) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
            } else {
                Color.Transparent
            }
        ),
        modifier = Modifier
            .fillMaxWidth()
            .hoverable(hover)
            .clickable { onOpen() }
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp)
        ) {
            SectionNumber("%02d".format(index))
            Text(
                text = book.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 14.dp)
            )
            // Once a chapter is marked read the chapter count becomes the
            // read progress ("✓ 3 / 50 read") in gold; untouched books keep
            // the muted chapter count, so read progress stands out in the
            // index at a glance.
            val total = book.chapters.size
            val read = readChapters.coerceIn(0, total)
            Text(
                text = if (read > 0) {
                    "\u2713 $read / $total read"
                } else {
                    "$total chapters"
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (read > 0) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
            Box(modifier = Modifier.padding(start = 12.dp)) {
                StarGlyph(
                    starred = starred,
                    onClick = onToggleStar
                )
            }
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
    hoverHighlighted: Boolean = false,
    /** Opens this verse's reference card (mentions / similar texts /
     *  Hebrew) in the chapter reader. */
    onClick: () -> Unit = {},
    /** Right-click: export this single verse as a card / slide. */
    onExportVerse: (Int) -> Unit = {},
    /** Right-click: export a range that starts at this verse. */
    onExportRange: (Int) -> Unit = {},
    onOpenNoteTitle: (String, BibleReferenceSelection?) -> Unit,
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
    interlinearAligned: Boolean = false,
    // Chapter-wide word hover: a Strong's number hovered on ANY verse of
    // the chapter tints every occurrence of that word in the chapter; the
    // per-number occurrence counts come from the chapter.
    wordCountsInChapter: Map<String, Int> = emptyMap(),
    hoveredWordNumber: String? = null,
    onHoverWord: (String?) -> Unit = {}
) {
    var hovered by remember(bookNumber, chapterNumber, verseNumber) {
        mutableStateOf(false)
    }
    // Right-click context menu: anchor offset in the verse card, non-null
    // while the export / copy menu is open.
    var contextOffset by remember(bookNumber, chapterNumber, verseNumber) {
        mutableStateOf<DpOffset?>(null)
    }
    val density = LocalDensity.current
    val clipboard = rememberAppClipboard()
    val clipboardScope = rememberCoroutineScope()

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
        markerPreview != null && hoverHighlighted -> colorFromHexInternal(markerPreview).copy(alpha = 0.32f)
        markerPreview != null -> colorFromHexInternal(markerPreview).copy(alpha = 0.18f)
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
        modifier = Modifier
            // Touch equivalent of the desktop right-click menu: press and
            // hold a verse to open export / copy actions at the finger.
            .pointerInput(bookNumber, chapterNumber, verseNumber) {
                // Not detectTapGestures: the verse text consumes its own
                // presses (links / word tokens), so the detector has to
                // accept an already-consumed down.
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val position = down.position
                    val released = withTimeoutOrNull(
                        viewConfiguration.longPressTimeoutMillis
                    ) {
                        var finished = false
                        while (!finished) {
                            val event = awaitPointerEvent(PointerEventPass.Main)
                            val change = event.changes.firstOrNull { it.id == down.id }
                            when {
                                change == null || change.changedToUpIgnoreConsumed() -> {
                                    finished = true
                                }
                                // A drag is a scroll / a swipe, not a hold.
                                (change.position - position).getDistance() >
                                    viewConfiguration.touchSlop -> {
                                    return@withTimeoutOrNull false
                                }
                            }
                        }
                        true
                    }
                    if (released == null) {
                        SoundManager.play(SoundEvent.Click)
                        contextOffset = DpOffset(position.x.toDp(), position.y.toDp())
                    }
                }
            }
            .onGloballyPositioned { coords ->
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
                .clickable(onClick = onClick)
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
                                onToggle = toggleStudyWord,
                                hoverNumber = hoveredWordNumber,
                                hoverCount = hoveredWordNumber
                                    ?.let { wordCountsInChapter[it] } ?: 0,
                                onHoverWord = onHoverWord
                            )
                        }

                        // Interlinear: the Greek TR verse beneath the English
                        // text, rendered by the shared [VerseInterlinear] so
                        // every verse row shows the same LINE / word-aligned
                        // output. Clickable Strong's numbers feed the same
                        // word-study panel as the English side.
                        interlinearGreek?.let { greekText ->
                            VerseInterlinear(
                                englishText = text,
                                greekText = greekText,
                                aligned = interlinearAligned,
                                activeNumber = activeForThisVerse?.number,
                                onToggleStudyWord = toggleStudyWord,
                                hoverNumber = hoveredWordNumber,
                                hoverCount = hoveredWordNumber
                                    ?.let { wordCountsInChapter[it] } ?: 0,
                                onHoverWord = onHoverWord
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

        // Right-click menu: export this verse (or a range starting here)
        // as a stylish postcard / PowerPoint, or copy the text and
        // reference. Anchored at the press point.
        contextOffset?.let { offset ->
            DropdownMenu(
                expanded = true,
                offset = offset,
                onDismissRequest = { contextOffset = null }
            ) {
                DropdownMenuItem(
                    text = { Text("Export this verse…") },
                    onClick = {
                        SoundManager.play(SoundEvent.Click)
                        contextOffset = null
                        onExportVerse(verseNumber)
                    }
                )
                DropdownMenuItem(
                    text = { Text("Export a range from here…") },
                    onClick = {
                        SoundManager.play(SoundEvent.Click)
                        contextOffset = null
                        onExportRange(verseNumber)
                    }
                )
                DropdownMenuItem(
                    text = { Text("Copy verse") },
                    onClick = {
                        SoundManager.play(SoundEvent.Click)
                        clipboardScope.launch {
                            clipboard.setText(copyText)
                        }
                        contextOffset = null
                    }
                )
                DropdownMenuItem(
                    text = { Text("Copy reference") },
                    onClick = {
                        SoundManager.play(SoundEvent.Click)
                        clipboardScope.launch {
                            clipboard.setText("$bookName $chapterNumber:$verseNumber")
                        }
                        contextOffset = null
                    }
                )
            }
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
internal fun NoteChip(
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
internal fun verseCopyText(bookName: String, chapter: Int, verse: Int, text: String): String =
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
    val clipboard = rememberAppClipboard()
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
                    clipboard.setText(copyText)
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
    onToggleStudyWord: (StrongsToken) -> Unit,
    hoverNumber: String? = null,
    hoverCount: Int = 0,
    onHoverWord: (String?) -> Unit = {}
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
                    onToggle = onToggleStudyWord,
                    hoverNumber = hoverNumber,
                    hoverCount = hoverCount,
                    onHoverWord = onHoverWord
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
 * the chapter verse rows so LINE and word-aligned output always look and
 * behave identically.
 */
@Composable
internal fun VerseInterlinear(
    englishText: String,
    greekText: String,
    aligned: Boolean,
    activeNumber: String?,
    onToggleStudyWord: (StrongsToken) -> Unit,
    hoverNumber: String? = null,
    hoverCount: Int = 0,
    onHoverWord: (String?) -> Unit = {}
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
            onToggleStudyWord = onToggleStudyWord,
            hoverNumber = hoverNumber,
            hoverCount = hoverCount,
            onHoverWord = onHoverWord
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

