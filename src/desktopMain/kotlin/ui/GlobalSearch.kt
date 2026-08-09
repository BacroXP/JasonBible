package ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import data.BibleCatalog
import data.BibleRepository
import data.NotesRepository
import data.SettingsManager
import data.SoundEvent
import data.SoundManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds
import model.Book


// ---------------------------------------------------------------------------
// Global Ctrl+F search — notes + Bible verses + book names
//
// Ctrl+F opens ONE search bar on every screen (except Settings, which
// filters its own rows). The query is matched against every note file,
// every verse of the active translation, and the book names themselves:
// "John 3, 16" resolves straight to John 3:16, "John" finds the book John
// AND every other book that mentions him (Matthew, Mark, Luke, Acts, …),
// and clicking a book expands the filtered chapters that contain the
// search term. The pure matching / grouping logic lives in
// [parseReferenceQuery] / [searchGlobal] so it can be unit-tested without
// a UI; the state class and the overlay compose on top.
// ---------------------------------------------------------------------------

/** Cap on how many verse matches are grouped into the result set. */
internal const val MAX_GLOBAL_MATCHES = 1000

/** Cap on how many book rows are rendered (the full set is still counted). */
internal const val MAX_GLOBAL_BOOKS = 60

/** Cap on how many chapter rows are rendered per expanded book. */
internal const val MAX_CHAPTERS_PER_BOOK = 80

/** Cap on how many note hits are rendered (the repository caps at 100). */
internal const val MAX_NOTE_HITS = 25

/** Cap on how many "lone" chapter rows are rendered. */
internal const val MAX_LONE_CHAPTERS = 40

/** Cap on how many "lone" verse rows are rendered. */
internal const val MAX_LONE_VERSES = 40

/**
 * True when [event] is the global-search shortcut: Ctrl+F on a KeyDown.
 * Shared by every dialog / focusable popup (separate windows the
 * Navigation root handler can't reach) so the key binding can't drift.
 */
internal fun KeyEvent.isGlobalSearchShortcut(): Boolean =
    type == KeyEventType.KeyDown && isCtrlPressed && key == Key.F

/**
 * The key handler every dialog / focusable popup uses so Ctrl+F (the
 * global-search shortcut) dismisses the widget and opens the search.
 * Key events go to the FOCUSED window only, so while a dialog is open
 * the Navigation root handler never sees them — the widget forwards the
 * shortcut itself: it dismisses (the overlay renders in the main window,
 * BEHIND a separate dialog window) and opens the search. Returns true
 * only for the shortcut; all other keys pass through untouched.
 */
internal fun globalSearchDialogKeyHandler(
    onDismiss: () -> Unit,
    onOpenGlobalSearch: () -> Unit
): (KeyEvent) -> Boolean = { event ->
    if (event.isGlobalSearchShortcut()) {
        onDismiss()
        onOpenGlobalSearch()
        true
    } else {
        false
    }
}

/** One chapter of one book that contains the query. */
internal data class ChapterSearchGroup(
    val chapter: Int,
    val matchCount: Int
)

/** One Bible book that matched: by verse text, by name, or both. */
internal data class BookSearchGroup(
    val book: Book,
    /**
     * True when the query matched the book's NAME (e.g. "Genesis"), even
     * if no verse text mentions it. Such books always win promotion and
     * list ALL their chapters so the result stays navigable.
     */
    val nameMatched: Boolean,
    /** Chapters whose verses contain the query. */
    val chapters: List<ChapterSearchGroup>,
    val totalMatches: Int
)

/**
 * A chapter of a NON-promoted book that met the chapter threshold on its
 * own — shown at chapter level in the trailing "Chapters & verses" list.
 */
internal data class LoneChapter(
    val book: Book,
    val chapter: Int,
    val matchCount: Int
)

/**
 * A single verse of a chapter below the chapter threshold — shown at verse
 * level in the trailing "Chapters & verses" list.
 */
internal data class LoneVerse(
    val book: Book,
    val chapter: Int,
    val verse: Int,
    val text: String
)

internal data class GlobalSearchResults(
    /** Books promoted to book level (name-matched or at/over the book threshold). */
    val books: List<BookSearchGroup>,
    val notes: List<NotesRepository.NoteSearchHit>,
    /** Chapters that met the chapter threshold in non-promoted books. */
    val loneChapters: List<LoneChapter>,
    /** Verses in chapters below the chapter threshold. */
    val loneVerses: List<LoneVerse>
)

/** A query interpreted as a Bible reference ("John 3, 16" → John 3:16). */
internal data class ParsedReference(
    val book: Book,
    val chapter: Int?,
    val verse: Int?
)

/**
 * Tries to read [query] as a Bible reference: "John", "John 3",
 * "John 3, 16", "John 3:16", "1 Mose 3,16", "Johannes 3,16", "1. Mose 3.16"
 * … The book name may be multi-word and is resolved through [resolveBook]
 * (the active translation's own names plus the cross-language alias
 * index), so a reference typed in one language keeps resolving after the
 * Bible is switched. Trailing integers are consumed as chapter / verse;
 * the remaining words must resolve to a book. Returns null when the query
 * isn't a reference (e.g. plain words like "love").
 */
internal fun parseReferenceQuery(
    query: String,
    resolveBook: (String) -> Book?
): ParsedReference? {
    // German-style "3, 16" and "3:16" separators all become plain spaces.
    val normalized = query.trim()
        .replace(',', ' ')
        .replace(':', ' ')
        .replace(';', ' ')
        .replace('.', ' ')
        .trim()
    if (normalized.isEmpty()) return null
    // Strip trailing punctuation per word ("John 3, 16!" → 16) so a query
    // that ends a sentence still parses as a reference.
    val words = normalized.split(Regex("\\s+"))
        .filter { it.isNotEmpty() }
        .map { it.trim { c -> !c.isLetterOrDigit() } }
        .filter { it.isNotEmpty() }
    if (words.isEmpty()) return null

    // Whole query as the book name first ("John", "1 Mose",
    // "Song of Solomon") — covers book-only references.
    resolveBook(words.joinToString(" "))?.let {
        return ParsedReference(it, null, null)
    }

    // Otherwise strip trailing integers as chapter / verse: "1 Mose 3 16"
    // → book words "1 Mose", ints [3, 16]. "3 Johannes 5" → book "3 Johannes".
    var split = words.size
    while (split > 0 && words[split - 1].toIntOrNull() != null) split--
    if (split == 0 || split == words.size) return null
    val bookWords = words.subList(0, split)
    val ints = words.subList(split, words.size).mapNotNull { it.toIntOrNull() }
    val book = resolveBook(bookWords.joinToString(" ")) ?: return null
    return ParsedReference(book, ints.getOrNull(0), ints.getOrNull(1))
}

/**
 * Scans the active translation's verses (via the shared [searchBible],
 * which also handles Strong's-shaped queries like `G25`) and every note
 * file, then sorts the Bible hits by granularity:
 *
 *  - BOOK level: a book whose NAME contains the query, or whose verses
 *    contain it at least [bookThreshold] times, is promoted to the
 *    "books" section. Expanded, it lists the chapters at/over
 *    [chapterThreshold] (all matching chapters when none would qualify,
 *    so the expansion never empties).
 *  - CHAPTER level: in books that did NOT get promoted, a chapter whose
 *    verses contain the query at least [chapterThreshold] times appears
 *    as a "lone chapter" in the trailing section.
 *  - VERSE level: everything below the chapter threshold appears as
 *    individual "lone verses".
 *
 * [wholeWord] restricts verse and note matching to whole words ("day"
 * doesn't match "today"), like the Bible search's abc toggle; book-NAME
 * matching stays a loose substring so partial names keep resolving.
 * Notes are listed separately. All lists are capped for rendering — the
 * counts reflect the capped scan, so they read as "matches shown so far"
 * for very common words.
 */
internal fun searchGlobal(
    books: List<Book>,
    query: String,
    matchCase: Boolean,
    bookThreshold: Int,
    chapterThreshold: Int,
    wholeWord: Boolean = false
): GlobalSearchResults {
    val q = query.trim()
    if (q.isEmpty()) {
        return GlobalSearchResults(emptyList(), emptyList(), emptyList(), emptyList())
    }

    val matches = searchBible(books, q, matchCase, wholeWord)
        .take(MAX_GLOBAL_MATCHES)

    // Group matches by book → chapter, preserving canonical order. Keyed
    // by book NUMBER, not the Book object — hashing a Book walks every
    // chapter and verse text, which would add up across the cap of
    // MAX_GLOBAL_MATCHES matches per scan.
    val byBookNumber = LinkedHashMap<Int, LinkedHashMap<Int, MutableList<BibleSearchMatch>>>()
    for (match in matches) {
        byBookNumber.getOrPut(match.book.book) { LinkedHashMap() }
            .getOrPut(match.chapter) { mutableListOf() }
            .add(match)
    }
    val bookByNumber = books.associateBy { it.book }
    val qLower = q.lowercase()

    // Books whose NAME contains the query are promoted even when no verse
    // text matched — the book itself is the hit. Collected here so they
    // also appear when the scan found nothing in them.
    val promoted = mutableListOf<BookSearchGroup>()
    val loneChapters = mutableListOf<LoneChapter>()
    val loneVerses = mutableListOf<LoneVerse>()

    for ((bookNumber, chapters) in byBookNumber) {
        val book = bookByNumber[bookNumber] ?: continue
        val nameMatched = book.name.lowercase().contains(qLower)
        val total = chapters.values.sumOf { it.size }
        if (nameMatched || total >= bookThreshold) {
            // Book level: only chapters at/over the chapter threshold are
            // listed when expanded — sub-threshold chapters are hidden (the
            // book itself is the result). If that would leave the expansion
            // EMPTY (e.g. a promoted book whose matches are spread thin),
            // fall back to ALL matching chapters so the book still opens
            // somewhere useful.
            val allMatching = chapters.entries
                .sortedBy { it.key }
                .map { (chapter, list) -> ChapterSearchGroup(chapter, list.size) }
            val qualifying = allMatching.filter { it.matchCount >= chapterThreshold }
            promoted += BookSearchGroup(
                book = book,
                nameMatched = nameMatched,
                chapters = qualifying.ifEmpty { allMatching },
                totalMatches = total
            )
        } else {
            // Drill down: chapters at/over the chapter threshold become
            // lone chapters; the rest become individual verses.
            for ((chapter, list) in chapters.entries.sortedBy { it.key }) {
                if (list.size >= chapterThreshold) {
                    loneChapters += LoneChapter(book, chapter, list.size)
                } else {
                    for (match in list) {
                        loneVerses += LoneVerse(book, chapter, match.verse, match.text)
                    }
                }
            }
        }
    }

    // Name-matched books with no verse hits at all (e.g. "Genesis", whose
    // text never says "Genesis"): promote them with the whole book as the
    // chapter list so the result stays navigable.
    for (book in books) {
        if (book.name.lowercase().contains(qLower) && book.book !in byBookNumber) {
            promoted += BookSearchGroup(
                book = book,
                nameMatched = true,
                chapters = book.chapters.map { ChapterSearchGroup(it.chapter, 0) },
                totalMatches = 0
            )
        }
    }

    promoted.sortBy { it.book.book }

    val notes = NotesRepository.searchNotes(q, matchCase, wholeWord)
    return GlobalSearchResults(promoted, notes, loneChapters, loneVerses)
}

/**
 * Resolves a typed book name against the active translation: first its own
 * names, then the cross-language alias index (same semantics as
 * [BibleRepository.getBook], but against an already-loaded module list).
 */
internal fun resolveBookName(books: List<Book>, name: String): Book? {
    val trimmed = name.trim()
    books.firstOrNull { it.name.equals(trimmed, ignoreCase = true) }?.let { return it }
    val number = BibleCatalog.nameToBookNumber[trimmed.lowercase()] ?: return null
    return books.firstOrNull { it.book == number }
}

/**
 * The Bible reference Ctrl+Enter should open — the top Bible result in
 * render order. Priority: the exact-reference card (when the query parses
 * as a reference), then the first promoted book (opening at its first
 * qualifying chapter, or the whole book for name-only matches), then the
 * first lone chapter, then the first lone verse. Returns null when no
 * Bible result exists (e.g. only notes match), in which case Ctrl+Enter
 * is a no-op.
 *
 * Runs a FRESH scan with the live query rather than reading the rendered
 * results, so it is never stale — even in the 200 ms debounce window
 * right after the user finishes typing.
 */
internal fun topBibleResultReference(
    books: List<Book>,
    query: String,
    matchCase: Boolean,
    bookThreshold: Int,
    chapterThreshold: Int,
    wholeWord: Boolean = false
): BibleReferenceSelection? {
    // The exact reference is the first thing rendered, so it wins.
    parseReferenceQuery(query) { name -> resolveBookName(books, name) }?.let { parsed ->
        return BibleReferenceSelection(
            book = parsed.book.name,
            chapter = parsed.chapter,
            verse = parsed.verse
        )
    }

    val results = searchGlobal(books, query, matchCase, bookThreshold, chapterThreshold, wholeWord)
    results.books.firstOrNull()?.let { group ->
        // Land on the book's first qualifying chapter (the whole book's
        // first chapter for name-only matches), or the book itself when
        // it has no chapters.
        return BibleReferenceSelection(
            book = group.book.name,
            chapter = group.chapters.firstOrNull()?.chapter,
            verse = null
        )
    }
    results.loneChapters.firstOrNull()?.let { lone ->
        return BibleReferenceSelection(lone.book.name, lone.chapter, null)
    }
    results.loneVerses.firstOrNull()?.let { lone ->
        return BibleReferenceSelection(lone.book.name, lone.chapter, lone.verse)
    }
    return null
}

/** Text of one verse, or null when the reference doesn't exist. */
private fun findVerseText(
    books: List<Book>,
    bookNumber: Int,
    chapter: Int,
    verse: Int
): String? {
    val book = books.firstOrNull { it.book == bookNumber } ?: return null
    return book.chapters.firstOrNull { it.chapter == chapter }
        ?.verses
        ?.firstOrNull { it.verse == verse }
        ?.text
}

/** Combined hit count for the bar's status line. */
private fun hitCountText(results: GlobalSearchResults, hasExactReference: Boolean): String {
    val total = results.books.sumOf { it.totalMatches } +
        results.loneChapters.sumOf { it.matchCount } +
        results.loneVerses.size +
        results.notes.size +
        if (hasExactReference) 1 else 0
    return if (total == 0) "No matches"
    else "$total " + if (total == 1) "match" else "matches"
}


// ---------------------------------------------------------------------------
// State + overlay
// ---------------------------------------------------------------------------

/**
 * Owns the global Ctrl+F search overlay: open state, the query, the Aa
 * match-case toggle, the results and the set of expanded book rows. The
 * debounced scan and the overlay UI live in [GlobalSearchOverlay]; the
 * keyboard toggling (Ctrl+F / Esc) is routed by the caller (Navigation)
 * through [handleKeyEvent] so it can skip screens where Ctrl+F means
 * something else (Settings filters its own rows).
 */
internal class AppSearchState {
    var open by mutableStateOf(false)
        private set
    // The query and Aa / abc toggles seed from (and write back to)
    // SettingsManager, so reopening the search (or restarting the app)
    // resumes the last session — exactly like the Bible search.
    var query by mutableStateOf(SettingsManager.globalSearchQuery)
        private set
    var matchCase by mutableStateOf(SettingsManager.globalSearchMatchCase)
        private set
    var wholeWord by mutableStateOf(SettingsManager.globalSearchWholeWord)
        private set
    var results by mutableStateOf(
        GlobalSearchResults(emptyList(), emptyList(), emptyList(), emptyList())
    )
        private set
    var searching by mutableStateOf(false)
        private set
    var expandedBooks by mutableStateOf<Set<Int>>(emptySet())
        private set
    val focusRequester = FocusRequester()
    // Guards the Ctrl+F toggle against OS key auto-repeat (which arrives
    // as repeated KeyDown events) so holding the combo doesn't flicker
    // the bar open and closed.
    private var lastToggleAt by mutableStateOf(0L)

    fun openSearch() {
        SoundManager.play(SoundEvent.Click)
        open = true
    }

    /**
     * Close the search, persist the session query (so reopening it or
     * restarting the app resumes where the user left off) and record the
     * query in the recent-queries dropdown. The query and Aa / abc
     * toggles stay in the field for the next open (matching the Bible
     * search); results / expanded state reset so the next scan starts
     * fresh.
     */
    fun close() {
        SettingsManager.globalSearchQuery = query
        SettingsManager.addGlobalSearchRecent(query)
        open = false
        results = GlobalSearchResults(emptyList(), emptyList(), emptyList(), emptyList())
        searching = false
        expandedBooks = emptySet()
    }

    fun updateQuery(value: String) {
        query = value
        // A new query invalidates the expanded-book layout.
        expandedBooks = emptySet()
    }

    fun toggleMatchCase() {
        matchCase = !matchCase
        SettingsManager.globalSearchMatchCase = matchCase
    }

    fun toggleWholeWord() {
        wholeWord = !wholeWord
        SettingsManager.globalSearchWholeWord = wholeWord
    }

    fun toggleBookExpanded(bookNumber: Int) {
        expandedBooks = if (bookNumber in expandedBooks) {
            expandedBooks - bookNumber
        } else {
            expandedBooks + bookNumber
        }
    }

    /**
     * Debounced scan of the active translation + every note file, re-run by
     * the overlay's LaunchedEffect whenever the query / Aa toggle / loaded
     * module / open state changes. The scan runs on [Dispatchers.Default]
     * so a big translation never stalls composition; the effect re-keying
     * cancels a stale scan before its result can land after a newer query
     * started. A not-yet-loaded module (empty [books]) still searches the
     * notes and is re-run once the books arrive.
     */
    suspend fun scan(books: List<Book>) {
        if (!open || query.trim().isEmpty()) {
            results = GlobalSearchResults(emptyList(), emptyList(), emptyList(), emptyList())
            searching = false
            return
        }
        searching = true
        delay(200.milliseconds)
        val live = query.trim()
        if (live.isEmpty()) {
            results = GlobalSearchResults(emptyList(), emptyList(), emptyList(), emptyList())
            searching = false
            return
        }
        // Thresholds read on the UI thread (snapshot state), so the next
        // scan honours a threshold changed in Settings.
        val bookThreshold = SettingsManager.searchBookThreshold
        val chapterThreshold = SettingsManager.searchChapterThreshold
        results = withContext(Dispatchers.Default) {
            searchGlobal(books, live, matchCase, bookThreshold, chapterThreshold, wholeWord)
        }
        searching = false
    }

    /**
     * Ctrl+F toggles the overlay; while it is open, Esc / Ctrl+F close it
     * (honouring the 250 ms auto-repeat guard). [enabled] lets the caller
     * disable the OPEN shortcut on screens where Ctrl+F means something
     * else (Settings filters its own rows); closing is never disabled.
     * Returns true when the event was consumed.
     */
    fun handleKeyEvent(event: KeyEvent, enabled: Boolean = true): Boolean {
        if (event.type != KeyEventType.KeyDown) {
            return false
        }
        if (open) {
            if (event.key == Key.Escape) {
                close()
                return true
            }
            if (event.isCtrlPressed && event.key == Key.F) {
                val now = System.currentTimeMillis()
                if (now - lastToggleAt > 250) {
                    lastToggleAt = now
                    close()
                }
                return true
            }
            return false
        }
        if (enabled && event.isCtrlPressed && event.key == Key.F) {
            val now = System.currentTimeMillis()
            if (now - lastToggleAt > 250) {
                lastToggleAt = now
                openSearch()
            }
            return true
        }
        return false
    }
}

/** Creates the global search state and focuses the field when it opens. */
@Composable
internal fun rememberAppSearchState(): AppSearchState {
    val state = remember { AppSearchState() }

    LaunchedEffect(state.open) {
        if (state.open) {
            delay(50.milliseconds)
            state.focusRequester.requestFocus()
        }
    }

    return state
}


/**
 * The global search overlay: a full-window scrim with a centered card —
 * query bar on top, then the results (exact-reference card, Bible books
 * with expandable chapters, notes). Clicking the scrim closes it; the
 * Ctrl+F / Esc shortcuts are handled by the caller (Navigation).
 *
 * The Bible module is loaded lazily on first open (cached by
 * [BibleRepository], so reopening is instant); notes are searched from
 * disk on every scan, like the in-app notes search.
 */
@Composable
internal fun GlobalSearchOverlay(
    state: AppSearchState,
    onOpenReference: (BibleReferenceSelection) -> Unit,
    onOpenNoteHit: (NotesRepository.NoteSearchHit) -> Unit,
    onOpenSettings: () -> Unit
) {
    // Books of the active translation, loaded once the overlay opens.
    var books by remember { mutableStateOf<List<Book>?>(null) }
    LaunchedEffect(Unit) {
        books = BibleRepository.loadBooks()
    }

    // Coroutine scope for the Ctrl+Enter quick-jump (it runs a fresh
    // background scan of the live query before navigating).
    val scope = rememberCoroutineScope()
    // Guards the Ctrl+Enter quick-jump against OS key auto-repeat (which
    // arrives as repeated KeyDown events) so holding the combo launches at
    // most one scan, like handleKeyEvent's toggle guard.
    var lastQuickJumpAt by remember { mutableStateOf(0L) }

    // Debounced scan over the Bible + notes on a background thread. The
    // effect re-keys on the query / toggle / loaded module, so a stale
    // scan's result can never land after a newer query started (the
    // previous coroutine is cancelled on re-key). While the module is
    // still loading, notes are searched immediately and the effect re-runs
    // once the books arrive.
    LaunchedEffect(state.open, state.query, state.matchCase, state.wholeWord, books) {
        state.scan(books.orEmpty())
    }

    // Exact-reference card: only when the query resolves to a book /
    // chapter / verse AND the module is loaded (parsing needs its names).
    val loadedBooks = books.orEmpty()
    val parsedReference = remember(state.query, loadedBooks) {
        if (loadedBooks.isEmpty()) {
            null
        } else {
            parseReferenceQuery(state.query) { name -> resolveBookName(loadedBooks, name) }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .zIndex(10f)
    ) {
        // Scrim: dims the screen and closes the search on click. It is a
        // SIBLING rendered behind the card — clicks on the card never reach
        // it, because the card container below consumes all pointer events
        // within its footprint. Clicks anywhere else hit this box and close.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.45f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {
                    SoundManager.play(SoundEvent.Click)
                    state.close()
                }
        )
        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 56.dp)
                .widthIn(max = 640.dp)
                .fillMaxWidth()
                // Swallow every pointer event inside the card's footprint so
                // clicks on non-interactive content (section headers, result
                // padding, the verse text under the reference card) never
                // fall through to the scrim and close the search. The
                // interactive children (query field, clickable rows) are
                // hit-tested first, so they keep working — the same pattern
                // as Home's search overlay.
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            awaitPointerEvent().changes.forEach { it.consume() }
                        }
                    }
                }
        ) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(16.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = RibbonIcons.Find,
                            contentDescription = null,
                            modifier = Modifier.size(22.dp)
                        )
                        Text(
                            text = "Search",
                            style = MaterialTheme.typography.headlineSmall,
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }

                    // ---- query bar (same visual language as the Bible /
                    // notes search bars) ----
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            // The search icon doubles as the recent-queries
                            // menu (same affordance as the Bible search bar):
                            // click it to re-run one of the last searches —
                            // each entry re-fills the field, which re-keys
                            // the debounced scan.
                            RecentQueriesMenu(
                                recentQueries = SettingsManager.globalSearchRecents,
                                onSelect = state::updateQuery
                            )
                            BasicTextField(
                                value = state.query,
                                onValueChange = state::updateQuery,
                                textStyle = MaterialTheme.typography.bodyMedium.copy(
                                    color = MaterialTheme.colorScheme.onSurface
                                ),
                                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                singleLine = true,
                                modifier = Modifier
                                    .weight(1f)
                                    .focusRequester(state.focusRequester)
                                    // Ctrl+Enter jumps straight to the top
                                    // Bible result. The scan runs on the
                                    // background thread with the live query
                                    // (never stale), and navigation closes
                                    // the search first via onOpenReference.
                                    .onPreviewKeyEvent { event ->
                                        if (event.type == KeyEventType.KeyDown &&
                                            event.isCtrlPressed &&
                                            event.key == Key.Enter
                                        ) {
                                            val now = System.currentTimeMillis()
                                            if (now - lastQuickJumpAt > 250) {
                                                lastQuickJumpAt = now
                                                val q = state.query
                                                if (q.isNotBlank()) {
                                                    val matchCase = state.matchCase
                                                    val wholeWord = state.wholeWord
                                                    val bookThreshold =
                                                        SettingsManager.searchBookThreshold
                                                    val chapterThreshold =
                                                        SettingsManager.searchChapterThreshold
                                                    scope.launch {
                                                        val reference =
                                                            withContext(Dispatchers.Default) {
                                                                topBibleResultReference(
                                                                    books = loadedBooks,
                                                                    query = q,
                                                                    matchCase = matchCase,
                                                                    bookThreshold = bookThreshold,
                                                                    chapterThreshold = chapterThreshold,
                                                                    wholeWord = wholeWord
                                                                )
                                                            }
                                                        reference?.let { onOpenReference(it) }
                                                    }
                                                }
                                            }
                                            true
                                        } else {
                                            false
                                        }
                                    },
                                decorationBox = { inner ->
                                    if (state.query.isEmpty()) {
                                        Text(
                                            "Search notes, the Bible and book names…",
                                            style = MaterialTheme.typography.bodyMedium.copy(
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                                    .copy(alpha = 0.5f)
                                            )
                                        )
                                    }
                                    inner()
                                }
                            )
                            if (state.query.isNotBlank()) {
                                Text(
                                    text = if (state.searching) "…" else {
                                        // A resolved reference ("John 3, 16")
                                        // counts as one hit even when the
                                        // verse scan itself finds nothing.
                                        hitCountText(state.results, parsedReference != null)
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            ToolbarActionButton(
                                label = if (state.matchCase) "Aa\u00B7on" else "Aa",
                                accent = state.matchCase,
                                tooltip = "Match case",
                                onClick = state::toggleMatchCase
                            )
                            ToolbarActionButton(
                                label = if (state.wholeWord) "abc\u00B7on" else "abc",
                                accent = state.wholeWord,
                                tooltip = "Whole word only",
                                onClick = state::toggleWholeWord
                            )
                            Text(
                                text = "✕",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .hoverable(remember { MutableInteractionSource() })
                                    .clickable {
                                        SoundManager.play(SoundEvent.Click)
                                        state.close()
                                    }
                                    .padding(horizontal = 4.dp)
                            )
                        }
                    }

                    // ---- results ----
                    Column(
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                        modifier = Modifier
                            .heightIn(max = 460.dp)
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                    ) {
                        // Loading the module for the first time.
                        if (books == null && state.query.isNotBlank()) {
                            Text(
                                text = "Loading Bible module…",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 6.dp)
                            )
                        }

                        // ---- exact reference (e.g. "John 3, 16") ----
                        parsedReference?.let { parsed ->
                            val label = buildString {
                                append(parsed.book.name)
                                parsed.chapter?.let { append(" $it") }
                                parsed.verse?.let { append(":$it") }
                            }
                            val chapter = parsed.chapter
                            val verseText = if (chapter != null && parsed.verse != null) {
                                findVerseText(loadedBooks, parsed.book.book, chapter, parsed.verse)
                            } else {
                                null
                            }
                            ExactReferenceCard(
                                label = label,
                                hint = when {
                                    verseText != null -> null
                                    parsed.verse != null -> "Open verse"
                                    parsed.chapter != null -> "Open chapter"
                                    else -> "Open book"
                                },
                                onClick = {
                                    SoundManager.play(SoundEvent.Click)
                                    onOpenReference(
                                        BibleReferenceSelection(
                                            book = parsed.book.name,
                                            chapter = parsed.chapter,
                                            verse = parsed.verse
                                        )
                                    )
                                }
                            )
                            if (verseText != null) {
                                Text(
                                    text = highlightQuery(
                                        verseText,
                                        state.query,
                                        state.matchCase,
                                        state.wholeWord
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                )
                            }
                        }

                        // ---- Bible books (with expandable chapters) ----
                        if (state.results.books.isNotEmpty()) {
                            Text(
                                text = "Bible",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)
                            )
                            state.results.books.take(MAX_GLOBAL_BOOKS).forEach { group ->
                                BookResultRow(
                                    group = group,
                                    expanded = group.book.book in state.expandedBooks,
                                    onToggle = { state.toggleBookExpanded(group.book.book) },
                                    onOpenChapter = { book, chapter ->
                                        SoundManager.play(SoundEvent.Click)
                                        onOpenReference(
                                            BibleReferenceSelection(
                                                book = book.name,
                                                chapter = chapter,
                                                verse = null
                                            )
                                        )
                                    }
                                )
                            }
                            if (state.results.books.size > MAX_GLOBAL_BOOKS) {
                                Text(
                                    text = "…and ${state.results.books.size - MAX_GLOBAL_BOOKS} more books",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(start = 4.dp, top = 2.dp)
                                )
                            }
                        }

                        // ---- notes ----
                        if (state.results.notes.isNotEmpty()) {
                            Text(
                                text = "Notes",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)
                            )
                            state.results.notes.take(MAX_NOTE_HITS).forEach { hit ->
                                NoteHitRow(
                                    hit = hit,
                                    query = state.query,
                                    matchCase = state.matchCase,
                                    wholeWord = state.wholeWord,
                                    onClick = { onOpenNoteHit(hit) }
                                )
                            }
                            if (state.results.notes.size > MAX_NOTE_HITS) {
                                Text(
                                    text = "…and ${state.results.notes.size - MAX_NOTE_HITS} more in notes",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(start = 4.dp, top = 2.dp)
                                )
                            }
                        }

                        // ---- lone chapters & verses (books below the book
                        // threshold, drilled down to chapter/verse level) ----
                        val hasLone = state.results.loneChapters.isNotEmpty() ||
                            state.results.loneVerses.isNotEmpty()
                        if (hasLone) {
                            Text(
                                text = "Chapters & verses",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)
                            )
                            state.results.loneChapters.take(MAX_LONE_CHAPTERS).forEach { lone ->
                                LoneChapterRow(
                                    lone = lone,
                                    onClick = {
                                        SoundManager.play(SoundEvent.Click)
                                        onOpenReference(
                                            BibleReferenceSelection(
                                                book = lone.book.name,
                                                chapter = lone.chapter,
                                                verse = null
                                            )
                                        )
                                    }
                                )
                            }
                            if (state.results.loneChapters.size > MAX_LONE_CHAPTERS) {
                                Text(
                                    text = "…and ${state.results.loneChapters.size - MAX_LONE_CHAPTERS} more chapters",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(start = 4.dp, top = 2.dp)
                                )
                            }
                            state.results.loneVerses.take(MAX_LONE_VERSES).forEach { lone ->
                                LoneVerseRow(
                                    lone = lone,
                                    query = state.query,
                                    matchCase = state.matchCase,
                                    wholeWord = state.wholeWord,
                                    onClick = {
                                        SoundManager.play(SoundEvent.Click)
                                        onOpenReference(
                                            BibleReferenceSelection(
                                                book = lone.book.name,
                                                chapter = lone.chapter,
                                                verse = lone.verse
                                            )
                                        )
                                    }
                                )
                            }
                            if (state.results.loneVerses.size > MAX_LONE_VERSES) {
                                Text(
                                    text = "…and ${state.results.loneVerses.size - MAX_LONE_VERSES} more verses",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(start = 4.dp, top = 2.dp)
                                )
                            }
                        }

                        // ---- empty state ----
                        if (state.query.isNotBlank() && !state.searching &&
                            parsedReference == null &&
                            state.results.books.isEmpty() &&
                            state.results.notes.isEmpty() &&
                            !hasLone
                        ) {
                            Text(
                                text = "No matches for \"${state.query.trim()}\".",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        }

                        // ---- idle hint ----
                        if (state.query.isBlank()) {
                            Text(
                                text = "Type a verse reference like \"John 3, 16\", a book name " +
                                    "like \"John\", or any word to search notes and the Bible. " +
                                    "Ctrl+Enter opens the top Bible result.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 6.dp)
                            )
                        }
                    }

                    // ---- thresholds hint (pinned below the scrollable
                    // results): links to the Search sliders in Settings ----
                    if (state.query.isNotBlank()) {
                        Text(
                            text = "⚙ Result thresholds — adjust in Settings",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 6.dp, bottom = 2.dp)
                                .hoverable(remember { MutableInteractionSource() })
                                .clickable {
                                    SoundManager.play(SoundEvent.Click)
                                    onOpenSettings()
                                }
                        )
                    }
                }
            }
        }
    }
}


/** Highlighted card for a query that resolves to a book / chapter / verse. */
@Composable
private fun ExactReferenceCard(
    label: String,
    hint: String?,
    onClick: () -> Unit
) {
    val hover = remember { MutableInteractionSource() }
    val isHovered by hover.collectIsHoveredAsState()
    LaunchedEffect(isHovered) {
        if (isHovered) {
            delay(60.milliseconds)
            SoundManager.play(SoundEvent.Hover)
        }
    }
    Card(
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .hoverable(hover)
            .clickable {
                SoundManager.play(SoundEvent.Click)
                onClick()
            }
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f)
            )
            if (hint != null) {
                Text(
                    text = hint,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}


/**
 * One Bible book result. Clicking the row expands/collapses it to reveal
 * the filtered chapters that contain the search term (or the whole book
 * when only the name matched); clicking a chapter opens the Bible at it.
 */
@Composable
private fun BookResultRow(
    group: BookSearchGroup,
    expanded: Boolean,
    onToggle: () -> Unit,
    onOpenChapter: (Book, Int) -> Unit
) {
    val hover = remember { MutableInteractionSource() }
    val isHovered by hover.collectIsHoveredAsState()
    LaunchedEffect(isHovered) {
        if (isHovered) {
            delay(60.milliseconds)
            SoundManager.play(SoundEvent.Hover)
        }
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .hoverable(hover)
                .clickable {
                    SoundManager.play(SoundEvent.Click)
                    onToggle()
                }
                .padding(horizontal = 4.dp, vertical = 6.dp)
        ) {
            Icon(
                imageVector = if (expanded) RibbonIcons.ChevronDown else RibbonIcons.ChevronRight,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Icon(
                imageVector = RibbonIcons.Bible,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = group.book.name,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            if (group.totalMatches > 0) {
                Text(
                    text = group.totalMatches.toString() +
                        if (group.totalMatches == 1) " match" else " matches",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (expanded) {
            group.chapters.take(MAX_CHAPTERS_PER_BOOK).forEach { chapterGroup ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            SoundManager.play(SoundEvent.Click)
                            onOpenChapter(group.book, chapterGroup.chapter)
                        }
                        .padding(start = 30.dp, end = 4.dp, top = 3.dp, bottom = 3.dp)
                ) {
                    Text(
                        text = "${group.book.name} ${chapterGroup.chapter}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f)
                    )
                    if (chapterGroup.matchCount > 0) {
                        Text(
                            text = chapterGroup.matchCount.toString() +
                                if (chapterGroup.matchCount == 1) " match" else " matches",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            if (group.chapters.size > MAX_CHAPTERS_PER_BOOK) {
                Text(
                    text = "…and ${group.chapters.size - MAX_CHAPTERS_PER_BOOK} more chapters",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 30.dp, top = 2.dp)
                )
            }
        }
    }
}


/**
 * One "lone" chapter result: a chapter that met the chapter threshold in
 * a book that did NOT meet the book threshold. Clicking opens the Bible at
 * that chapter.
 */
@Composable
private fun LoneChapterRow(
    lone: LoneChapter,
    onClick: () -> Unit
) {
    val hover = remember { MutableInteractionSource() }
    val isHovered by hover.collectIsHoveredAsState()
    LaunchedEffect(isHovered) {
        if (isHovered) {
            delay(60.milliseconds)
            SoundManager.play(SoundEvent.Hover)
        }
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .hoverable(hover)
            .clickable {
                SoundManager.play(SoundEvent.Click)
                onClick()
            }
            .padding(horizontal = 4.dp, vertical = 5.dp)
    ) {
        Icon(
            imageVector = RibbonIcons.Bible,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Text(
            text = "${lone.book.name} ${lone.chapter}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = lone.matchCount.toString() +
                if (lone.matchCount == 1) " match" else " matches",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}


/**
 * One "lone" verse result: a verse of a chapter below the chapter
 * threshold. Shows the reference plus the verse text (query highlighted);
 * clicking opens the Bible at that verse.
 */
@Composable
private fun LoneVerseRow(
    lone: LoneVerse,
    query: String,
    matchCase: Boolean,
    wholeWord: Boolean,
    onClick: () -> Unit
) {
    val hover = remember { MutableInteractionSource() }
    val isHovered by hover.collectIsHoveredAsState()
    LaunchedEffect(isHovered) {
        if (isHovered) {
            delay(60.milliseconds)
            SoundManager.play(SoundEvent.Hover)
        }
    }
    Column(
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier
            .fillMaxWidth()
            .hoverable(hover)
            .clickable {
                SoundManager.play(SoundEvent.Click)
                onClick()
            }
            .padding(horizontal = 4.dp, vertical = 4.dp)
    ) {
        Text(
            text = "${lone.book.name} ${lone.chapter}:${lone.verse}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = highlightQuery(lone.text, query, matchCase, wholeWord),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}


/**
 * One note hit: the note title plus the matching line (query highlighted),
 * clickable to open the note at that line.
 */
@Composable
private fun NoteHitRow(
    hit: NotesRepository.NoteSearchHit,
    query: String,
    matchCase: Boolean,
    wholeWord: Boolean,
    onClick: () -> Unit
) {
    val hover = remember { MutableInteractionSource() }
    val isHovered by hover.collectIsHoveredAsState()
    LaunchedEffect(isHovered) {
        if (isHovered) {
            delay(60.milliseconds)
            SoundManager.play(SoundEvent.Hover)
        }
    }
    Column(
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier
            .fillMaxWidth()
            .hoverable(hover)
            .clickable {
                SoundManager.play(SoundEvent.Click)
                onClick()
            }
            .padding(horizontal = 4.dp, vertical = 5.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = RibbonIcons.Document,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Text(
                text = hit.note.title.ifBlank { hit.note.fileName },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 6.dp)
            )
        }
        Text(
            text = highlightQuery(hit.lineText, query, matchCase, wholeWord),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
