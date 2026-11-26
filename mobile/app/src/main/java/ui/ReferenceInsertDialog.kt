@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class
)

package ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.unit.dp
import data.BibleRepository
import data.SoundEvent
import data.SoundManager
import data.StrongsRepository
import model.Book
import model.Chapter
import model.Verse



/**
 * Word-style "Insert reference" picker. The user types a book name into a
 * field with autocomplete (suggestions filtered from the bundled Bible),
 * optionally narrows to a chapter / verse, and confirms with Insert or
 * Enter. Inserts `$Book&C&V ` / `$Book&C ` / `$Book ` at the caret (the
 * `&`-separated reference syntax the editor renders as a chip).
 */
@Composable
internal fun ReferenceInsertDialog(
    initialKind: ReferenceKind,
    onDismiss: () -> Unit,
    onInsert: (String) -> Unit,
    onOpenGlobalSearch: () -> Unit = {}
) {
    var kind by remember { mutableStateOf(initialKind) }
    var bookQuery by remember { mutableStateOf("") }
    var chapterText by remember { mutableStateOf("1") }
    var verseText by remember { mutableStateOf("1") }
    var showSuggestions by remember { mutableStateOf(false) }

    // Live-preview wiring, mirroring the reading views: the Greek TR
    // module is loaded lazily only when a verse/chapter preview is actually
    // on screen (the module cache makes this free after the pane has loaded
    // it), and the Strong's dictionary is parsed on the first word click.
    var greekBooks by remember { mutableStateOf<List<Book>?>(null) }
    var strongsLoaded by remember { mutableStateOf(StrongsRepository.isLoaded) }
    var activeStudyWord by remember { mutableStateOf<StudyWordToken?>(null) }

    // Load the current translation's books asynchronously so a cold, huge
    // module (~30 MB) doesn't freeze the dialog open — the book field
    // starts with the cached list (empty on a cold module) and fills in
    // once the parse finishes.
    var allBooks by remember {
        mutableStateOf(BibleRepository.cachedBooks().orEmpty())
    }
    var booksLoading by remember { mutableStateOf(allBooks.isEmpty()) }
    LaunchedEffect(Unit) {
        val loaded = BibleRepository.loadBooks()
        allBooks = loaded
        booksLoading = false
    }
    val query = bookQuery.trim()
    // Autocomplete: books whose name contains the typed query, in
    // canonical Bible order. Empty query → hide the list entirely.
    val suggestions = remember(query) {
        if (query.isEmpty()) emptyList()
        else allBooks.filter { it.name.contains(query, ignoreCase = true) }.take(8)
    }
    val selectedBook = allBooks.find { it.name.equals(query, ignoreCase = true) }
    val chapterNumber = chapterText.toIntOrNull()
    val verseNumber = verseText.toIntOrNull()
    val selectedChapter = selectedBook?.chapters?.find { it.chapter == chapterNumber }

    // Validation: book must resolve, chapter within range for CHAPTER /
    // VERSE kinds, verse within range for VERSE kind.
    val chapterValid = selectedBook != null &&
        chapterNumber != null &&
        chapterNumber in 1..selectedBook.chapters.size
    val verseValid = selectedChapter != null &&
        verseNumber != null &&
        verseNumber in 1..selectedChapter.verses.size
    val canInsert = when (kind) {
        ReferenceKind.BOOK -> selectedBook != null
        ReferenceKind.CHAPTER -> chapterValid
        ReferenceKind.VERSE -> chapterValid && verseValid
    }
    // Whether the live scripture preview is on screen (a BOOK reference
    // has no single text to preview).
    val previewShown = when (kind) {
        ReferenceKind.BOOK -> false
        ReferenceKind.CHAPTER -> chapterValid
        ReferenceKind.VERSE -> verseValid
    }

    // Load the Greek TR module once a preview is shown (VERSE / CHAPTER
    // reference resolved). Loading only then keeps a book-only picker
    // session free of the ~6.5 MB parse. Skipped when the active
    // translation IS the Greek module itself — mirroring the reading
    // pane, there is nothing to interline (and the preview would just
    // duplicate Greek under Greek).
    LaunchedEffect(previewShown) {
        if (previewShown &&
            greekBooks == null &&
            BibleRepository.currentModuleId() != BibleRepository.INTERLINEAR_MODULE_ID
        ) {
            greekBooks = BibleRepository.loadModule(BibleRepository.INTERLINEAR_MODULE_ID)
        }
    }

    // Parse the Strong's concordance on the first word-study click inside
    // the preview (same on-demand pattern as the reading pane).
    LaunchedEffect(activeStudyWord) {
        if (activeStudyWord != null && !strongsLoaded) {
            StrongsRepository.ensureLoaded()
            strongsLoaded = true
        }
    }

    val insertText = buildString {
        append('$')
        append(selectedBook?.name ?: query)
        if (kind != ReferenceKind.BOOK) {
            append('&')
            append(chapterNumber ?: 1)
        }
        if (kind == ReferenceKind.VERSE) {
            append('&')
            append(verseNumber ?: 1)
        }
        append(' ')
    }

    // Ctrl+F while this dialog (a separate window) has focus: dismiss it
    // and open the global search — the root handler can't see it. On the
    // content root it fires before the fields' own Enter handlers (which
    // only return true for Enter).
    val dialogKeyHandler = globalSearchDialogKeyHandler(onDismiss, onOpenGlobalSearch)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Insert Bible reference") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.onPreviewKeyEvent(dialogKeyHandler)
            ) {
                // Granularity selector (Word-style segmented choice).
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ReferenceKind.entries.forEach { k ->
                        val isSelected = k == kind
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = if (isSelected) {
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                            },
                            modifier = Modifier
                                .clickable {
                                    SoundManager.play(SoundEvent.Click)
                                    kind = k
                                }
                                .padding(horizontal = 10.dp, vertical = 5.dp)
                        ) {
                            Text(
                                text = k.label,
                                style = MaterialTheme.typography.labelMedium,
                                color = if (isSelected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        }
                    }
                }

                // Book name field with autocomplete suggestions. The
                // suggestions render inline (below the field) so they can't
                // be clipped by the dialog bounds — they expand the dialog
                // briefly instead of overlaying the chapter/verse fields.
                OutlinedTextField(
                    value = bookQuery,
                    onValueChange = {
                        bookQuery = it
                        showSuggestions = true
                    },
                    label = { Text("Book") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .onPreviewKeyEvent { event ->
                            // Enter picks the first suggestion when the
                            // query isn't an exact book name yet.
                            if (event.key == Key.Enter &&
                                selectedBook == null &&
                                suggestions.isNotEmpty()
                            ) {
                                bookQuery = suggestions.first().name
                                true
                            } else {
                                false
                            }
                        }
                )
                if (booksLoading) {
                    Text(
                        text = "Loading books…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (showSuggestions && suggestions.isNotEmpty()) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        tonalElevation = 3.dp,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column {
                            suggestions.forEach { book ->
                                Text(
                                    text = book.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            SoundManager.play(SoundEvent.Click)
                                            bookQuery = book.name
                                            showSuggestions = false
                                        }
                                        .padding(horizontal = 12.dp, vertical = 7.dp)
                                )
                            }
                        }
                    }
                }

                if (kind != ReferenceKind.BOOK) {
                    OutlinedTextField(
                        value = chapterText,
                        onValueChange = {
                            chapterText = it.filter { c -> c.isDigit() }.take(3)
                        },
                        label = {
                            Text(
                                "Chapter" + selectedBook?.let {
                                    " (1–${it.chapters.size})"
                                }.orEmpty()
                            )
                        },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .onPreviewKeyEvent { event ->
                                // Enter commits the reference once the
                                // chapter (and, for verse refs, the verse)
                                // fields are valid.
                                if (event.key == Key.Enter && canInsert) {
                                    onInsert(insertText)
                                    true
                                } else {
                                    false
                                }
                            }
                    )
                }

                if (kind == ReferenceKind.VERSE) {
                    OutlinedTextField(
                        value = verseText,
                        onValueChange = {
                            verseText = it.filter { c -> c.isDigit() }.take(3)
                        },
                        label = {
                            Text(
                                "Verse" + selectedChapter?.let {
                                    " (1–${it.verses.size})"
                                }.orEmpty()
                            )
                        },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .onPreviewKeyEvent { event ->
                                if (event.key == Key.Enter && canInsert) {
                                    onInsert(insertText)
                                    true
                                } else {
                                    false
                                }
                            }
                    )
                }

                Text(
                    text = "Inserts: $insertText",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (canInsert) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.error
                    }
                )

                // Live scripture preview of the resolved reference — the
                // same interlinear + word-study rendering as the reading
                // views, so the user verifies the actual text (and Greek TR
                // line, when available) before inserting.
                if (previewShown) {
                    ReferenceInsertPreview(
                        kind = kind,
                        bookName = selectedBook?.name.orEmpty(),
                        bookNumber = selectedBook?.book ?: 0,
                        chapterNumber = chapterNumber ?: 1,
                        verseNumber = verseNumber ?: 1,
                        chapter = selectedChapter,
                        greekBooks = greekBooks,
                        strongsLoaded = strongsLoaded,
                        activeStudyWord = activeStudyWord,
                        onToggleStudyWord = { token ->
                            SoundManager.play(SoundEvent.Click)
                            activeStudyWord = if (activeStudyWord == token) {
                                null
                            } else {
                                token
                            }
                        }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = canInsert,
                modifier = Modifier.onPreviewKeyEvent(dialogKeyHandler),
                onClick = {
                    SoundManager.play(SoundEvent.Click)
                    onInsert(insertText)
                }
            ) {
                Text("Insert")
            }
        },
        dismissButton = {
            TextButton(
                modifier = Modifier.onPreviewKeyEvent(dialogKeyHandler),
                onClick = {
                    SoundManager.play(SoundEvent.Click)
                    onDismiss()
                }
            ) {
                Text("Cancel")
            }
        }
    )
}


/**
 * Live scripture preview shown inside the insert-reference dialog once the
 * typed reference resolves to a real chapter (CHAPTER kind) or verse
 * (VERSE kind). Renders the actual text with the SAME interlinear and
 * word-study wiring as the reading views: Strong's-marked verses get
 * clickable tokens (via [StrongsVerseText]), the matching Greek TR line is
 * shown beneath when the bundled module has the verse, and clicking a G/H
 * number opens the compact inline [WordStudyMiniPanel]. CHAPTER kind
 * previews the first few verses plus a count note so the dialog stays
 * compact; BOOK kind never calls this (a whole book has no single text).
 */
@Composable
private fun ReferenceInsertPreview(
    kind: ReferenceKind,
    bookName: String,
    bookNumber: Int,
    chapterNumber: Int,
    verseNumber: Int,
    chapter: Chapter?,
    greekBooks: List<Book>?,
    strongsLoaded: Boolean,
    activeStudyWord: StudyWordToken?,
    onToggleStudyWord: (StudyWordToken) -> Unit
) {
    val chapter = chapter ?: return
    val verses = when (kind) {
        ReferenceKind.BOOK -> emptyList()
        // VERSE kind shows the single resolved verse.
        ReferenceKind.VERSE -> chapter.verses.filter { it.verse == verseNumber }.take(1)
        // CHAPTER kind previews the opening of the chapter (kept short so
        // the dialog stays compact) with a count note for the rest.
        ReferenceKind.CHAPTER -> chapter.verses.take(3)
    }
    if (verses.isEmpty()) return

    // The whole chapter's Greek map, looked up once (empty for OT books
    // and while the module is still loading — verses then preview without
    // a Greek line, matching the reading views). Also empty when the
    // active translation IS the Greek module: interlining Greek under
    // Greek would just duplicate the text.
    val greekByVerse = remember(greekBooks, bookNumber, chapterNumber) {
        if (BibleRepository.currentModuleId() == BibleRepository.INTERLINEAR_MODULE_ID) {
            emptyMap()
        } else {
            greekBooks?.let {
                BibleRepository.greekVersesForChapter(it, bookNumber, chapterNumber)
            }.orEmpty()
        }
    }

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
        ) {
            Text(
                text = if (kind == ReferenceKind.VERSE) {
                    "Preview \u00B7 $bookName $chapterNumber:$verseNumber"
                } else {
                    "Preview \u00B7 $bookName $chapterNumber"
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            verses.forEach { verse ->
                ReferencePreviewVerse(
                    bookNumber = bookNumber,
                    chapterNumber = chapterNumber,
                    verse = verse,
                    greekText = greekByVerse[verse.verse],
                    strongsLoaded = strongsLoaded,
                    activeStudyWord = activeStudyWord,
                    onToggleStudyWord = onToggleStudyWord
                )
            }
            if (kind == ReferenceKind.CHAPTER && chapter.verses.size > 3) {
                Text(
                    text = "\u2026 and ${chapter.verses.size - 3} more verses",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}


/**
 * One verse of the insert-reference preview: the verse number, the English
 * text (clickable Strong's tokens when it carries markup, matching the
 * reading views' [StrongsVerseText]), the Greek TR line beneath it when
 * the interlinear module has this verse, and the compact word-study panel
 * when a number has been clicked. Mirrors [VerseRow]'s wiring, scoped to
 * the small dialog surface.
 */
@Composable
private fun ReferencePreviewVerse(
    bookNumber: Int,
    chapterNumber: Int,
    verse: Verse,
    greekText: String?,
    strongsLoaded: Boolean,
    activeStudyWord: StudyWordToken?,
    onToggleStudyWord: (StudyWordToken) -> Unit
) {
    val tokens = remember(verse.text) { parseWordStudyTokens(verse.text) }
    val activeForThisVerse = activeStudyWord?.takeIf {
        it.bookNumber == bookNumber &&
            it.chapter == chapterNumber &&
            it.verse == verse.verse
    }
    // Same wrapper the reading views use: tag the token with this verse's
    // reference before reporting the toggle.
    val toggleWord: (StrongsToken) -> Unit = { token ->
        onToggleStudyWord(
            StudyWordToken(
                bookNumber = bookNumber,
                chapter = chapterNumber,
                verse = verse.verse,
                word = token.word,
                number = token.number,
                parsing = token.parsing
            )
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Top,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = verse.verse.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp)
            )
            if (tokens.isEmpty()) {
                Text(
                    text = verse.text,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f)
                )
            } else {
                StrongsVerseText(
                    text = verse.text,
                    tokens = tokens,
                    activeNumber = activeForThisVerse?.number,
                    onToggle = toggleWord,
                    modifier = Modifier.weight(1f)
                )
            }
        }
        // Greek TR line, indented under the verse text — LINE mode, like
        // the reading views; clickable numbers feed the same panel.
        greekText?.let {
            Box(modifier = Modifier.padding(start = 20.dp)) {
                VerseInterlinear(
                    englishText = verse.text,
                    greekText = it,
                    aligned = false,
                    activeNumber = activeForThisVerse?.number,
                    onToggleStudyWord = toggleWord
                )
            }
        }
        // Compact word-study panel, inline like the whole-book reader's.
        if (activeForThisVerse != null) {
            WordStudyMiniPanel(
                token = activeForThisVerse,
                loaded = strongsLoaded,
                onClose = { onToggleStudyWord(activeForThisVerse) }
            )
        }
    }
}
