@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import data.BibleRepository
import data.CrossReferences
import data.HebrewLexicon
import data.NotesRepository
import data.SettingsManager
import data.SoundEvent
import data.SoundManager
import data.StrongsRepository
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import model.Book


/**
 * Reference card shown under a clicked verse in the (single) chapter reader:
 *
 *  - Mentions    — the notes that reference this verse, one chip per note.
 *  - Similar texts — passages derived from shared Strong's lemmas / wording
 *                  (parallels, OT quotations, thematic links). Derived, not
 *                  curated: nothing is invented when the verse has no
 *                  Strong's tagging.
 *  - Hebrew      — the verse from the bundled Hebrew module word by word.
 *                  Each word is a button; clicking it opens that word's
 *                  possible translations from its concordance entry. Words
 *                  without a match stay visible but dimmed.
 *
 * The panel is intentionally inline (right under the verse) so the reader
 * never leaves the chapter to look something up.
 */
@Composable
internal fun VerseDetailPanel(
    bookNumber: Int,
    bookName: String,
    chapterNumber: Int,
    verseNumber: Int,
    verseText: String,
    books: List<Book>,
    /** Parsed Hebrew module, or null while it is still loading. */
    hebrewBooks: List<Book>?,
    onOpenNoteTitle: (String, BibleReferenceSelection?) -> Unit,
    onOpenReference: (bookNumber: Int, chapter: Int, verse: Int) -> Unit,
    onClose: () -> Unit
) {
    val selection = BibleReferenceSelection(
        book = bookName,
        chapter = chapterNumber,
        verse = verseNumber
    )
    val copyText = remember(bookName, chapterNumber, verseNumber, verseText) {
        verseCopyText(
            bookName,
            chapterNumber,
            verseNumber,
            stripWordStudyMarkup(verseText)
        )
    }

    // Marker color for this verse: stored in the verse-marker settings and
    // also read by VerseRow, which tints the verse card with it.
    val markerColor = SettingsManager.getVerseMarkerColor(
        bookNumber,
        chapterNumber,
        verseNumber
    )
    var colorPickerOpen by remember(bookNumber, chapterNumber, verseNumber) {
        mutableStateOf(false)
    }

    val mentions = remember(bookName, chapterNumber, verseNumber) {
        NotesRepository.referencesForVerse(bookName, chapterNumber, verseNumber)
    }

    // Similar texts are derived from the whole-Bible Strong's index, which
    // is parsed on first use — run it off the UI thread and say "Finding…"
    // until it lands.
    var similar by remember(bookNumber, chapterNumber, verseNumber) {
        mutableStateOf<List<CrossReferences.Reference>?>(null)
    }
    LaunchedEffect(bookNumber, chapterNumber, verseNumber) {
        similar = withContext(Dispatchers.Default) {
            CrossReferences.ensureLoaded()
            CrossReferences.referencesFor(bookNumber, chapterNumber, verseNumber, limit = 6)
        }
    }

    // Hebrew side: the verse text from the bundled Hebrew module plus one
    // gloss list per word, resolved from a single pass over the concordance.
    val hebrewText = remember(hebrewBooks, bookNumber, chapterNumber, verseNumber) {
        BibleRepository.verseTextFor(hebrewBooks, bookNumber, chapterNumber, verseNumber)
    }
    var definitions by remember {
        mutableStateOf(StrongsRepository.allDefinitions())
    }
    LaunchedEffect(hebrewText) {
        if (hebrewText != null && definitions.isEmpty()) {
            StrongsRepository.ensureLoaded()
            definitions = StrongsRepository.allDefinitions()
        }
    }
    val hebrewWords = remember(hebrewText) {
        hebrewText?.let(HebrewLexicon::words).orEmpty()
    }
    val hebrewOptions = remember(hebrewWords, definitions) {
        HebrewLexicon.translationOptionsForVerse(hebrewWords, definitions)
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(14.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    BibleEyebrow("VERSE")
                    Text(
                        text = "$bookName $chapterNumber:$verseNumber",
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                CopyPill(copyText = copyText)
                Text(
                    text = "\u2715",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clickable {
                            SoundManager.play(SoundEvent.Click)
                            onClose()
                        }
                        .padding(horizontal = 4.dp)
                )
            }

            BibleEyebrow("MARKER")
            // Preset hues plus a rainbow dot for any custom color. Picking a
            // swatch marks the verse immediately; "Clear" removes the mark.
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                ColorPickerSwatches.forEach { swatch ->
                    val hex = colorToHexInternal(swatch)
                    val selected = markerColor?.equals(hex, ignoreCase = true) == true
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .border(
                                width = if (selected) 2.dp else 1.dp,
                                color = if (selected) {
                                    MaterialTheme.colorScheme.onSurface
                                } else {
                                    MaterialTheme.colorScheme.outlineVariant
                                },
                                shape = CircleShape
                            )
                            .background(swatch, CircleShape)
                            .clickable {
                                SoundManager.play(SoundEvent.Click)
                                SettingsManager.setVerseMarkerColor(
                                    bookNumber,
                                    chapterNumber,
                                    verseNumber,
                                    hex
                                )
                            }
                    )
                }
                RainbowDot(modifier = Modifier.size(24.dp)) {
                    colorPickerOpen = true
                }
                if (markerColor != null) {
                    Surface(
                        shape = PillShape,
                        color = Color.Transparent,
                        border = BorderStroke(
                            width = 1.dp,
                            color = MaterialTheme.colorScheme.outlineVariant
                        ),
                        modifier = Modifier.clickable {
                            SoundManager.play(SoundEvent.Click)
                            SettingsManager.setVerseMarkerColor(
                                bookNumber,
                                chapterNumber,
                                verseNumber,
                                null
                            )
                        }
                    ) {
                        Text(
                            text = "Clear",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(
                                horizontal = 10.dp,
                                vertical = 4.dp
                            )
                        )
                    }
                }
            }

            if (colorPickerOpen) {
                ColorPickerDialog(
                    initialHex = markerColor,
                    onDismiss = { colorPickerOpen = false },
                    onPick = { hex ->
                        colorPickerOpen = false
                        SettingsManager.setVerseMarkerColor(
                            bookNumber,
                            chapterNumber,
                            verseNumber,
                            hex
                        )
                    }
                )
            }

            BibleEyebrow("MENTIONS")
            if (mentions.isEmpty()) {
                PanelHint("No note mentions this verse yet.")
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    mentions.forEach { ref ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            NoteChip(
                                text = ref.noteTitle,
                                onClick = { onOpenNoteTitle(ref.noteTitle, selection) }
                            )
                            ref.label?.takeIf { it.isNotBlank() }?.let { label ->
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }
            }

            PanelDivider()

            BibleEyebrow("SIMILAR TEXTS")
            val references = similar
            when {
                references == null -> PanelHint("Finding similar passages\u2026")

                references.isEmpty() -> PanelHint(
                    "No similar passages found (this verse may not be " +
                        "Strong's-tagged in the index)."
                )

                else -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    references.forEach { ref ->
                        SimilarTextRow(
                            label = "${bookNameFor(books, ref.book)} " +
                                "${ref.chapter}:${ref.verse}",
                            kind = ref.kind.label,
                            snippet = verseSnippet(
                                books,
                                ref.book,
                                ref.chapter,
                                ref.verse
                            ),
                            onClick = {
                                onOpenReference(ref.book, ref.chapter, ref.verse)
                            }
                        )
                    }
                }
            }

            PanelDivider()

            BibleEyebrow("HEBREW")
            when {
                hebrewBooks == null -> PanelHint("Loading Hebrew text\u2026")

                hebrewText == null -> PanelHint(
                    "No Hebrew text for this verse in the bundled Hebrew module."
                )

                else -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = hebrewText,
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.End,
                        modifier = Modifier.fillMaxWidth()
                    )
                    PanelHint("Click a word to see how it can be translated.")
                    // One word open at a time; resets when the verse changes
                    // because the card is rebuilt per clicked verse.
                    var openWord by remember(bookNumber, chapterNumber, verseNumber) {
                        mutableStateOf<String?>(null)
                    }
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        hebrewWords.distinct().forEach { word ->
                            HebrewWordButton(
                                word = word,
                                translated = hebrewOptions[word].orEmpty().isNotEmpty(),
                                open = openWord == word,
                                onClick = {
                                    openWord = if (openWord == word) null else word
                                }
                            )
                        }
                    }
                    openWord?.let { word ->
                        HebrewWordTranslations(
                            word = word,
                            options = hebrewOptions[word].orEmpty()
                        )
                    }
                }
            }
        }
    }
}


/** One derived "similar text": target reference, its kind and a snippet. */
@Composable
private fun SimilarTextRow(
    label: String,
    kind: String,
    snippet: String?,
    onClick: () -> Unit
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable {
                SoundManager.play(SoundEvent.Click)
                onClick()
            }
            .padding(vertical = 4.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "\u2197",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Text(
            text = kind,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        snippet?.let { text ->
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}


@Composable
private fun PanelHint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}


@Composable
private fun PanelDivider() {
    HorizontalDivider(
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
    )
}


/**
 * One Hebrew word of the verse as a clickable pill. Words with no
 * concordance entry stay dimmed but remain clickable, so the panel can say
 * why nothing is shown for them.
 */
@Composable
private fun HebrewWordButton(
    word: String,
    translated: Boolean,
    open: Boolean,
    onClick: () -> Unit
) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    LaunchedEffect(hovered) {
        if (hovered) {
            delay(60.milliseconds)
            SoundManager.play(SoundEvent.Hover)
        }
    }
    Surface(
        shape = PillShape,
        color = when {
            open -> activeChromeFill()
            hovered -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
            else -> Color.Transparent
        },
        border = BorderStroke(
            width = 1.dp,
            color = if (open) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
            } else {
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            }
        ),
        modifier = Modifier
            .hoverable(hover)
            .clickable {
                SoundManager.play(SoundEvent.Click)
                onClick()
            }
    ) {
        Text(
            text = word,
            style = MaterialTheme.typography.bodyMedium,
            color = if (translated || open) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
            },
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}


/** The possible translations of the Hebrew word currently open. */
@Composable
private fun HebrewWordTranslations(word: String, options: List<String>) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = word,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.SemiBold
                    ),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = when (options.size) {
                        0 -> "no concordance entry"
                        1 -> "1 translation"
                        else -> "${options.size} translations"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            if (options.isEmpty()) {
                Text(
                    text = "This word has no concordance entry, so no " +
                        "translation options are available.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(
                    text = "Can be translated as",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    options.forEach { option ->
                        Surface(
                            shape = PillShape,
                            color = activeChromeFill()
                        ) {
                            Text(
                                text = option,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(
                                    horizontal = 10.dp,
                                    vertical = 4.dp
                                )
                            )
                        }
                    }
                }
            }
        }
    }
}


private fun bookNameFor(books: List<Book>, number: Int): String =
    books.find { it.book == number }?.name ?: "Book $number"


private fun verseSnippet(
    books: List<Book>,
    book: Int,
    chapter: Int,
    verse: Int
): String? = BibleRepository.verseTextFor(books, book, chapter, verse)
    ?.let { stripWordStudyMarkup(it).trim() }
    ?.takeIf { it.isNotEmpty() }
