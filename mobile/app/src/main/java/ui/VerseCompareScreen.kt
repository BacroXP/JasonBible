package ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import data.BibleCatalog
import data.BibleRepository
import data.SettingsManager
import data.SoundEvent
import data.SoundManager
import model.Book
import model.Chapter
import ui.components.MaxWidthScaffold


/**
 * Verse comparison across translations: one verse shown side by side in
 * every selected bundled translation, each with its display name and the
 * verse text (\"—\" when the verse doesn't exist in that module). The
 * reference is pre-filled from the Bible pane's selected verse and can be
 * changed via the book / chapter / verse pickers. Translations are
 * selected per language group and loaded LAZILY in the background (a full
 * parse of all ~90 modules up front would freeze the first compare for
 * minutes); results are cached per reference, so revisiting a verse is
 * instant. Fully offline — no external data is fetched.
 */
@Composable
fun VerseCompareScreen(
    initialReference: BibleReferenceSelection?,
    back: () -> Unit
) {
    // Books of the active translation drive the reference pickers; the
    // canonical book numbers join across all other modules.
    var books by remember { mutableStateOf<List<Book>?>(null) }
    LaunchedEffect(Unit) {
        books = BibleRepository.loadBooks()
    }

    var bookNumber by remember { mutableStateOf(1) }
    var chapterNumber by remember { mutableStateOf(1) }
    var verseNumber by remember { mutableStateOf(1) }

    val loaded = books
    LaunchedEffect(loaded) {
        val list = loaded ?: return@LaunchedEffect
        val (bn, cn, vn) = resolveCompareReference(list, initialReference)
        bookNumber = bn
        chapterNumber = cn
        verseNumber = vn
    }

    // Picker-driven changes clamp the finer-grained values so the screen
    // can never compare a chapter/verse the new book or chapter doesn't
    // have (e.g. switching from Psalms 150 to John must not keep "150").
    // Same clamping semantics as [resolveCompareReference].
    val currentBook = loaded?.find { it.book == bookNumber }

    // Translations to compare, grouped by language. Default: the active
    // translation plus every translation of the same language.
    val entries = BibleCatalog.entries
    val activeModuleId = BibleRepository.currentModuleId()
    var selectedIds by remember(entries, activeModuleId) {
        mutableStateOf(defaultCompareSelection(entries, activeModuleId))
    }

    // refKey -> moduleId -> verse text (null = verse not in that module).
    // Cached per reference so toggling translations or revisiting a verse
    // never re-parses modules.
    var resultsCache by remember {
        mutableStateOf<Map<String, Map<String, String?>>>(emptyMap())
    }
    val refKey = "$bookNumber:$chapterNumber:$verseNumber"
    val currentResults = resultsCache[refKey].orEmpty()

    // Skip until the reference is resolved (the pickers aren't rendered
    // before `loaded` either, so the initial "1:1:1" pass would be wasted).
    LaunchedEffect(refKey, selectedIds, loaded) {
        if (loaded == null) return@LaunchedEffect
        val missing = selectedIds - currentResults.keys
        if (missing.isEmpty()) return@LaunchedEffect
        val acc = currentResults.toMutableMap()
        for (id in missing) {
            // loadModule parses on Dispatchers.Default internally and
            // caches the result globally, so repeated visits are cheap.
            val moduleBooks = BibleRepository.loadModule(id)
            acc[id] = BibleRepository.verseTextFor(
                moduleBooks, bookNumber, chapterNumber, verseNumber
            )
            resultsCache = resultsCache + (refKey to acc.toMap())
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Compact top bar mirroring the Statistics screen pattern.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "← Back",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier
                    .clickable {
                        SoundManager.play(SoundEvent.Click)
                        back()
                    }
                    .padding(horizontal = 16.dp, vertical = 6.dp)
            )
            Text(
                text = "⇄ Verse comparison",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(start = 4.dp)
            )
        }
        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
        )

        val list = loaded
        if (list == null) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Loading translations…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            MaxWidthScaffold(
                compact = false,
                maxWidth = SettingsManager.bibleMaxWidth
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(20.dp)
                ) {
                    // Reference pickers: book / chapter / verse of the
                    // active translation (canonical numbers join the rest).
                    ReferencePickerRow(
                        books = list,
                        bookNumber = bookNumber,
                        chapterNumber = chapterNumber,
                        verseNumber = verseNumber,
                        onBook = { newBookNumber ->
                            val target = list.find { it.book == newBookNumber }
                            bookNumber = newBookNumber
                            chapterNumber =
                                target?.chapters?.firstOrNull()?.chapter ?: 1
                            verseNumber = target?.chapters?.firstOrNull()
                                ?.verses?.firstOrNull()?.verse ?: 1
                        },
                        onChapter = { newChapterNumber ->
                            chapterNumber = newChapterNumber
                            verseNumber = currentBook?.chapters
                                ?.find { it.chapter == newChapterNumber }
                                ?.verses?.firstOrNull()?.verse ?: 1
                        },
                        onVerse = { verseNumber = it }
                    )

                    // Translation selection, grouped by language.
                    TranslationSelector(
                        entries = entries,
                        selectedIds = selectedIds,
                        activeModuleId = activeModuleId,
                        onToggle = { id, checked ->
                            selectedIds = if (checked) {
                                selectedIds + id
                            } else {
                                selectedIds - id
                            }
                        },
                        onToggleLanguage = { language, checked ->
                            val group = entries
                                .filter { it.languageName == language }
                                .map { it.moduleId }
                                .toSet()
                            selectedIds = if (checked) {
                                selectedIds + group
                            } else {
                                selectedIds - group
                            }
                        }
                    )

                    if (selectedIds.isEmpty()) {
                        Text(
                            text = "Select at least one translation to compare.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        // One card per selected translation. A missing
                        // result key means the module is still loading.
                        val ordered = entries
                            .filter { it.moduleId in selectedIds }
                            .sortedWith(
                                compareBy({ it.languageName }, { it.displayName.lowercase() })
                            )
                        ordered.forEach { entry ->
                            val loaded = entry.moduleId in currentResults
                            val text = currentResults[entry.moduleId]
                            TranslationCompareCard(
                                displayName = entry.displayName,
                                languageName = entry.languageName,
                                text = text,
                                loading = !loaded,
                                isActive = entry.moduleId == activeModuleId,
                                verseLabel = "$chapterNumber:$verseNumber"
                            )
                        }
                    }
                }
            }
        }
    }
}


/**
 * Book / chapter / verse pickers for the active translation, using the
 * scrollable-dropdown pattern from the Settings screen's pickers.
 */
@Composable
private fun ReferencePickerRow(
    books: List<Book>,
    bookNumber: Int,
    chapterNumber: Int,
    verseNumber: Int,
    onBook: (Int) -> Unit,
    onChapter: (Int) -> Unit,
    onVerse: (Int) -> Unit
) {
    val book = books.find { it.book == bookNumber } ?: books.first()
    val chapters = book.chapters
    val chapter = chapters.find { it.chapter == chapterNumber }
        ?: chapters.firstOrNull()
    val maxChapter = chapters.maxOfOrNull { it.chapter } ?: 1
    val maxVerse = chapter?.verses?.maxOfOrNull { it.verse } ?: 1

    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        PickerField(
            label = "Book",
            value = book.name,
            options = books.map { it.name },
            onSelect = { name ->
                books.find { it.name == name }?.let { onBook(it.book) }
            },
            modifier = Modifier.weight(1.4f)
        )
        PickerField(
            label = "Chapter",
            value = chapterNumber.toString(),
            options = (1..maxChapter).map { it.toString() },
            onSelect = { onChapter(it.toInt()) },
            modifier = Modifier.weight(0.8f)
        )
        PickerField(
            label = "Verse",
            value = verseNumber.toString(),
            options = (1..maxVerse).map { it.toString() },
            onSelect = { onVerse(it.toInt()) },
            modifier = Modifier.weight(0.8f)
        )
    }
}


/** One labeled dropdown (Button + scrollable DropdownMenu). */
@Composable
private fun PickerField(
    label: String,
    value: String,
    options: List<String>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Box {
            Button(
                onClick = { expanded = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = value,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                // heightIn BEFORE verticalScroll — see SettingsScreen's
                // picker for why the order matters (infinite max height
                // during dismissal otherwise).
                Column(
                    modifier = Modifier
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    options.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option) },
                            onClick = {
                                expanded = false
                                SoundManager.play(SoundEvent.Click)
                                onSelect(option)
                            }
                        )
                    }
                }
            }
        }
    }
}


/** Per-language group with a \"select all\" checkbox and entry checkboxes. */
@Composable
private fun TranslationSelector(
    entries: List<BibleCatalog.BibleEntry>,
    selectedIds: Set<String>,
    activeModuleId: String?,
    onToggle: (String, Boolean) -> Unit,
    onToggleLanguage: (String, Boolean) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = "Translations",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        entries.groupBy { it.languageName }
            .toSortedMap()
            .forEach { (language, languageEntries) ->
                val groupIds = languageEntries.map { it.moduleId }.toSet()
                val allSelected = groupIds.all { it in selectedIds }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            SoundManager.play(SoundEvent.Click)
                            onToggleLanguage(language, !allSelected)
                        }
                ) {
                    Checkbox(
                        checked = allSelected,
                        onCheckedChange = {
                            onToggleLanguage(language, it)
                        }
                    )
                    Text(
                        text = language,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "  (${languageEntries.size})",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                languageEntries.sortedBy { it.displayName.lowercase() }.forEach { entry ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                SoundManager.play(SoundEvent.Click)
                                onToggle(entry.moduleId, entry.moduleId !in selectedIds)
                            }
                    ) {
                        Checkbox(
                            checked = entry.moduleId in selectedIds,
                            onCheckedChange = {
                                onToggle(entry.moduleId, it)
                            }
                        )
                        Text(
                            text = entry.displayName +
                                if (entry.moduleId == activeModuleId) "  (active)" else "",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (entry.moduleId == activeModuleId) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            }
                        )
                    }
                }
            }
    }
}


/** One translation's rendering of the compared verse. */
@Composable
private fun TranslationCompareCard(
    displayName: String,
    languageName: String,
    text: String?,
    loading: Boolean,
    isActive: Boolean,
    verseLabel: String
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(14.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = displayName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = languageName + if (isActive) " · active" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isActive) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
            )
            when {
                // Module still parsing in the background.
                loading -> Text(
                    text = "…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Parsed, but the verse doesn't exist in this module
                // (e.g. an NT verse in an OT-only module).
                text == null -> Text(
                    text = "— (not in this translation)",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                else -> Text(
                    text = "$verseLabel  ${stripWordStudyMarkup(text)}",
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        }
    }
}


/**
 * Default set of translations to compare: the active translation plus
 * every bundled translation of the same language. Falls back to the
 * single first entry when no module is active.
 */
internal fun defaultCompareSelection(
    entries: List<BibleCatalog.BibleEntry>,
    activeModuleId: String?
): Set<String> {
    val active = entries.find { it.moduleId == activeModuleId }
        ?: return entries.take(1).map { it.moduleId }.toSet()
    return entries
        .filter { it.languageName == active.languageName }
        .map { it.moduleId }
        .toSet()
}


/**
 * Resolve the reference shown by the compare screen: the initial
 * reference (a book name in any known language + optional chapter/verse)
 * against the active translation's books, clamping to the first
 * book/chapter/verse when anything is missing or out of range. Pure
 * function so the UI and its tests share one implementation.
 */
internal fun resolveCompareReference(
    books: List<Book>,
    initial: BibleReferenceSelection?
): Triple<Int, Int, Int> {
    val book = initial?.let { selection ->
        books.find { it.name.equals(selection.book, ignoreCase = true) }
            ?: books.find { BibleRepository.bookNumberFor(selection.book) == it.book }
    } ?: books.firstOrNull()
    val bookNumber = book?.book ?: 1
    val chapters = book?.chapters.orEmpty()
    val chapterNumber = initial?.chapter
        ?.takeIf { c -> chapters.any { it.chapter == c } }
        ?: chapters.firstOrNull()?.chapter ?: 1
    val chapter: Chapter? = chapters.find { it.chapter == chapterNumber }
    val verseNumber = initial?.verse
        ?.takeIf { v -> chapter?.verses?.any { it.verse == v } == true }
        ?: chapter?.verses?.firstOrNull()?.verse ?: 1
    return Triple(bookNumber, chapterNumber, verseNumber)
}
