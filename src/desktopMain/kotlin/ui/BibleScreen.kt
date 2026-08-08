@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class
)

package ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import data.BibleCatalog
import data.BibleRepository
import data.SettingsManager
import data.SoundEvent
import data.SoundManager
import data.StrongsRepository
import model.Book
import model.Verse
import ui.components.MaxWidthScaffold
import kotlinx.coroutines.launch



private enum class Testament {
    OLD,
    NEW
}

/** Interlinear display modes cycled by the chapter-header pill. */
private enum class InterlinearMode {
    OFF,
    LINE,
    ALIGNED
}
// Persist the per-(book,chapter) scroll-offset cache across navigation
// (and configuration changes) by stringifying each "$book:$chapter -> $offset"
// entry into a single ArrayList<String> that Bundle can carry.
private val ScrollPositionSaver: Saver<MutableMap<String, Int>, ArrayList<String>> =
    Saver(
        save = { map ->
            ArrayList(map.map { (k, v) -> "$k=$v" })
        },
        restore = { saved ->
            saved.associate { entry ->
                val sep = entry.indexOf('=')
                entry.substring(0, sep) to entry.substring(sep + 1).toInt()
            }.toMutableMap()
        }
    )
@Composable
fun BibleScreen(
    back: () -> Unit,
    initialReference: BibleReferenceSelection? = null,
    showBackButton: Boolean = true,
    compact: Boolean = false,
    hoveredBibleReference: BibleReferenceSelection? = null,
    onOpenNoteTitle: (String, BibleReferenceSelection?) -> Unit = { _, _ -> },
    /**
     * Ctrl+F opener for the pane's DIALOG windows: separate Compose
     * windows never reach the Navigation root key handler, so each dialog
     * forwards the global-search shortcut here — dismissing itself and
     * opening the overlay in the main window.
     */
    onOpenGlobalSearch: () -> Unit = {},
    /** Opens the central word-study / lexicon at a Strong's number.
     *  Wired by Navigation; defaults to a no-op. */
    onOpenLexicon: (String) -> Unit = {},
    /** Opens the verse-comparison screen at a verse (book name, chapter,
     *  verse). Wired by Navigation; defaults to a no-op. */
    onOpenCompare: (String, Int, Int) -> Unit = { _, _, _ -> }
) {
    val bookGridState = rememberLazyGridState()
    val chapterGridState = rememberLazyGridState()
    val verseScrollState = rememberScrollState()

    val scope = rememberCoroutineScope()

    val scrollOffsets: MutableMap<String, Int> = rememberSaveable(
        saver = ScrollPositionSaver
    ) { mutableMapOf() }

    var testament by remember { mutableStateOf(Testament.OLD) }
    var selectedBookNumber by remember { mutableStateOf<Int?>(null) }
    var selectedChapterNumber by remember { mutableStateOf<Int?>(null) }
    var selectedVerseNumber by remember { mutableStateOf<Int?>(null) }
    // "＋ Collection" dialog for the selected verse (opened via the header
    // pill; only reachable while a verse is selected).
    var addToCollectionOpen by remember { mutableStateOf(false) }
    // Copy-range dialog for the chapter view's "More ▾" menu; non-null
    // while the dialog is open.
    var copyRangeOpen by remember { mutableStateOf(false) }
    // Ctrl+G jump-to-verse dialog.
    var jumpDialogOpen by remember { mutableStateOf(false) }
    // Cross-reference panel toggle: when on, the selected verse's derived
    // cross references (parallels / OT quotations / thematic links) render
    // below the chapter jump strip.
    var crossRefsOpen by remember { mutableStateOf(false) }
    // Whole-book continuous reading: renders every chapter of the open
    // book as one scrolling passage (chapter boundaries become headings).
    // Exited whenever the pane navigates to a specific chapter / verse.
    var continuousMode by remember { mutableStateOf(false) }
    // Interlinear view: OFF / LINE (the matching Greek TR verse beneath
    // each NT verse) / ALIGNED (Greek tokens paired word-by-word with the
    // English via Strong's numbers). The Greek module is loaded lazily
    // the first time the pill leaves OFF.
    var interlinearMode by remember { mutableStateOf(InterlinearMode.OFF) }
    var greekBooks by remember { mutableStateOf<List<Book>?>(null) }

    // Back/forward navigation history for the reading pane. Each entry
    // is a (book number, chapter, verse) target — book numbers are
    // canonical across translations, so history survives translation
    // switches. The stack is pushed on user navigation (book / chapter /
    // verse clicks, search-result jumps, Ctrl+G); back/forward step the
    // index without pushing.
    val historyStack = remember { mutableStateListOf<NavPoint>() }
    var historyIndex by remember { mutableStateOf(-1) }

    // Scroll-to-verse plumbing for tag-driven navigation.
    //
    // `verseYOffsetsMap` records each rendered verse's Y position inside
    // the scrolling verses Column (populated by VerseRow via
    // Modifier.onGloballyPositioned). `verseYOffsetsVersion` is bumped
    // every time the map mutates so the scroll-to-verse LaunchedEffect
    // re-fires and re-checks whether the target verse is now laid out.
    //
    // `pendingScrollVerse` is set when the user clicks a Bible
    // reference from the notes editor (the LaunchedEffect(initialReference)
    // arms it). The scroll-to-verse LaunchedEffect waits for the verse's
    // Y offset to be reported, then animateScrollTo's and clears the
    // flag. Same-chapter navigation also works: the verses are already
    // laid out so the offset is in the map and the scroll fires
    // immediately.
    val verseYOffsetsMap = remember { mutableMapOf<Int, Int>() }
    var verseYOffsetsVersion by remember { mutableStateOf(0) }
    var pendingScrollVerse by remember { mutableStateOf<Int?>(null) }

    // Full-text search over the current translation (Ctrl+F): all search
    // state — query / toggles / scope / results, the debounced background
    // scan, focus, shortcut handling and session persistence — lives in
    // the shared [BibleSearchState], created below once the module books
    // are available. The scope's book/chapter targets are read live from
    // this pane's selection at scan time.

    // Word study: Strong's-enabled translations (e.g. the bundled "KJV
    // with Strongs") render clickable word tokens; this tracks which
    // token's definition panel is open. The dictionary is loaded lazily on
    // the first word click (~4.4 MB JSON).
    var activeStudyWord by remember { mutableStateOf<StudyWordToken?>(null) }
    var strongsLoaded by remember { mutableStateOf(StrongsRepository.isLoaded) }

    // Async module loading: large translations (up to ~30 MB) take a
    // moment to parse on first open, so we load off the UI thread and
    // show a spinner instead of freezing the pane. `loadedModuleId`
    // guards against briefly showing the previous translation's books
    // after a switch — the display only trusts `loadedBooks` when it
    // belongs to the current module.
    var loadedModuleId by remember { mutableStateOf<String?>(null) }
    var loadedBooks by remember { mutableStateOf<List<Book>?>(null) }
    val currentModuleId = BibleRepository.currentModuleId()

    androidx.compose.runtime.LaunchedEffect(currentModuleId) {
        loadedModuleId = currentModuleId
        loadedBooks = BibleRepository.cachedBooks()
        if (loadedBooks == null) {
            loadedBooks = BibleRepository.loadBooks()
        }
    }

    val books = if (loadedModuleId == currentModuleId) {
        loadedBooks.orEmpty()
    } else {
        emptyList()
    }
    val booksLoading = loadedModuleId != currentModuleId || loadedBooks == null

    // Word-aligned interlinear joins the Greek numbers against Strong's-
    // marked English text (`word{G####}` tokens), so it only makes sense
    // for translations that carry that markup. Probe the loaded module
    // once per translation: any book's first chapter containing brace
    // tokens marks it as alignment-capable (cheap — a handful of verse
    // regex scans, memoised per module list).
    val supportsWordAlignment = remember(books) {
        books.any { book ->
            book.chapters.firstOrNull()?.verses?.any {
                parseStrongsTokens(it.text).isNotEmpty()
            } == true
        }
    }
    val visibleBooks = books.filter { book ->
        when (testament) {
            Testament.OLD -> book.book <= 39
            Testament.NEW -> book.book > 39
        }
    }

    val selectedBook = selectedBookNumber?.let { number ->
        books.find { it.book == number }
    }
    val selectedChapter = selectedBook?.chapters?.find {
        it.chapter == selectedChapterNumber
    }

    // Shared full-text search state (query / toggles / scope / results /
    // scan / focus / shortcuts), identical to Home's so the two can't
    // drift. Scope targets come live from this pane's book/chapter
    // selection at scan time.
    val search = rememberBibleSearchState(
        books = books,
        scopeTargets = { selectedBookNumber to selectedChapterNumber }
    )

    // Parse the Strong's concordance the first time a word is clicked (the
    // parse runs off the UI thread); the open panel shows a progress note
    // until strongsLoaded flips and the definition is available.
    androidx.compose.runtime.LaunchedEffect(activeStudyWord) {
        if (activeStudyWord != null && !strongsLoaded) {
            StrongsRepository.ensureLoaded()
            strongsLoaded = true
        }
    }

    // Load the Greek TR module once the interlinear toggle leaves OFF
    // (the parse runs off the UI thread and is cached afterwards). When
    // the module is missing from the catalog, loadModule returns an empty
    // list and the interlinear lines simply don't render.
    androidx.compose.runtime.LaunchedEffect(interlinearMode) {
        if (interlinearMode != InterlinearMode.OFF && greekBooks == null) {
            greekBooks = BibleRepository.loadModule(BibleRepository.INTERLINEAR_MODULE_ID)
        }
    }

    // If the user switches to a translation without Strong's markup while
    // in word-aligned mode, drop back to the plain Greek line — there is
    // nothing to align against. Guarded by `books.isNotEmpty()` so the
    // switch's brief loading gap (empty books → supportsWordAlignment
    // reads false) can't demote the mode when the destination module
    // actually supports alignment; once the module loads, the effect
    // re-keys and demotes only if it genuinely lacks markup.
    androidx.compose.runtime.LaunchedEffect(currentModuleId, supportsWordAlignment) {
        if (interlinearMode == InterlinearMode.ALIGNED &&
            !supportsWordAlignment &&
            books.isNotEmpty()
        ) {
            interlinearMode = InterlinearMode.LINE
        }
    }

    /**
     * Jump the pane to a search match: select its book / chapter / verse,
     * queue a scroll-to-verse (pendingScrollVerse fires as soon as the
     * verse's Y offset is reported — immediately when it is already on
     * screen, otherwise after the new chapter lays out) and close the
     * search so the verse is actually visible.
     */
    /**
     * Push a navigation target onto the history stack, truncating any
     * forward tail (the user navigated back, then somewhere new).
     * Consecutive duplicates are collapsed so toggling a verse selection
     * doesn't spam the stack.
     */
    fun recordNavigation(bn: Int, cn: Int?, vn: Int?) {
        if (historyIndex < historyStack.lastIndex) {
            historyStack.removeRange(historyIndex + 1, historyStack.size)
        }
        val last = historyStack.lastOrNull()
        if (last != null &&
            last.bookNumber == bn && last.chapter == cn && last.verse == vn
        ) {
            return
        }
        historyStack.add(NavPoint(bn, cn, vn))
        historyIndex = historyStack.lastIndex
    }

    /**
     * Restore a history entry: select its book / chapter / verse without
     * pushing (Back / Forward step the index instead). Verse targets also
     * queue a scroll-to-verse so the restored verse is actually on screen.
     * Returns false (without changing the view) when the target book
     * doesn't exist in the currently loaded module — the caller then
     * leaves the history index untouched so it can't drift out of sync
     * with what's on screen.
     */
    fun applyHistoryPoint(point: NavPoint): Boolean {
        val book = books.find { it.book == point.bookNumber } ?: return false
        continuousMode = false
        testament = if (point.bookNumber <= 39) {
            Testament.OLD
        } else {
            Testament.NEW
        }
        selectedBookNumber = point.bookNumber
        selectedChapterNumber = point.chapter
        selectedVerseNumber = point.verse
        if (point.verse != null) pendingScrollVerse = point.verse
        return true
    }

    fun goHistoryBack() {
        if (historyIndex > 0) {
            val candidate = historyIndex - 1
            if (applyHistoryPoint(historyStack[candidate])) {
                historyIndex = candidate
            }
        }
    }

    fun goHistoryForward() {
        if (historyIndex < historyStack.lastIndex) {
            val candidate = historyIndex + 1
            if (applyHistoryPoint(historyStack[candidate])) {
                historyIndex = candidate
            }
        }
    }

    /**
     * Jump (from Ctrl+G / the jump dialog) to a book / chapter / verse,
     * recording the move in history like any other navigation.
     */
    fun jumpToVerse(bookNumber: Int, chapterNumber: Int, verseNumber: Int?) {
        continuousMode = false
        testament = if (bookNumber <= 39) {
            Testament.OLD
        } else {
            Testament.NEW
        }
        selectedBookNumber = bookNumber
        selectedChapterNumber = chapterNumber
        selectedVerseNumber = verseNumber
        recordNavigation(bookNumber, chapterNumber, verseNumber)
        if (verseNumber != null) pendingScrollVerse = verseNumber
    }

    fun openSearchResult(match: BibleSearchMatch) {
        SoundManager.play(SoundEvent.Click)
        continuousMode = false
        selectedBookNumber = match.book.book
        testament = if (match.book.book <= 39) {
            Testament.OLD
        } else {
            Testament.NEW
        }
        selectedChapterNumber = match.chapter
        selectedVerseNumber = match.verse
        pendingScrollVerse = match.verse
        recordNavigation(match.book.book, match.chapter, match.verse)
        search.close()
    }

    androidx.compose.runtime.LaunchedEffect(initialReference) {
        val incoming = initialReference
        // Warm the module cache first (the parse runs off the UI thread)
        // so the name resolution below never blocks on a cold cache.
        val loaded = BibleRepository.loadBooks()
        if (incoming != null) {
            val matchingBook = loaded.find {
                it.name.equals(incoming.book, ignoreCase = true)
            } ?: BibleRepository.getBook(incoming.book)
            // Unknown book names (e.g. a stale scaffold like `$Book`, or a
            // book-only line that doesn't resolve) are a no-op — don't
            // reset the pane to the book picker, just leave the current
            // view alone.
            if (matchingBook == null) return@LaunchedEffect
            selectedBookNumber = matchingBook.book
            testament = if (matchingBook.book <= 39) {
                Testament.OLD
            } else {
                Testament.NEW
            }
            continuousMode = false
            selectedChapterNumber = incoming.chapter
            selectedVerseNumber = incoming.verse
            recordNavigation(matchingBook.book, incoming.chapter, incoming.verse)
            // Only queue a scroll-to-verse if the requested verse actually
            // exists in the destination chapter. Without this guard, a
            // bogus reference like `$Genesis$1$999` would set
            // pendingScrollVerse to a verse that never appears in
            // verseYOffsetsMap, the scroll-to-verse LaunchedEffect would
            // never resolve, and future chapter changes would lose their
            // normal scroll-restore behaviour until the flag was cleared.
            val targetChapter = matchingBook?.chapters?.find { it.chapter == incoming.chapter }
            pendingScrollVerse = incoming.verse?.takeIf { v ->
                targetChapter?.verses?.any { it.verse == v } == true
            }
        } else {
            // Nothing requested explicitly — resume the user's last-read
            // book/chapter from SettingsManager if it still maps to a real book.
            val last = SettingsManager.getLastRead()
            if (last != null && loaded.any { it.book == last.bookNumber }) {
                continuousMode = false
                selectedBookNumber = last.bookNumber
                selectedChapterNumber = last.chapterNumber
                selectedVerseNumber = last.verseNumber
                testament = if (last.bookNumber <= 39) {
                    Testament.OLD
                } else {
                    Testament.NEW
                }
                recordNavigation(last.bookNumber, last.chapterNumber, last.verseNumber)
            }
        }
    }

    // Persist the active book/chapter and restore its scroll position
    // whenever the active chapter changes.
    androidx.compose.runtime.LaunchedEffect(selectedBookNumber, selectedChapterNumber) {
        // A word-study panel belongs to the verse that was on screen; drop
        // it when the chapter changes so a stale panel can't pop back.
        activeStudyWord = null
        val bn = selectedBookNumber
        val cn = selectedChapterNumber
        if (bn != null && cn != null) {
            SettingsManager.setLastRead(bn, cn, selectedVerseNumber)
            // Skip the saved-offset restore when a tag-driven scroll-to-verse
            // is pending; that scroll will run as soon as the verse's Y
            // position is reported by VerseRow.onGloballyPositioned. Without
            // this guard the saved offset from a previous visit would
            // override the tag navigation and the user would see the chapter
            // snap to its old scroll position instead of landing on the
            // requested verse.
            if (pendingScrollVerse == null) {
                val key = "$bn:$cn"
                val savedOffset = scrollOffsets[key]
                if (savedOffset != null) {
                    verseScrollState.scrollTo(
                        savedOffset.coerceAtMost(verseScrollState.maxValue)
                    )
                }
            }
        }
    }

    // Scroll-to-verse: when the user clicks a Bible reference from the
    // notes editor, pendingScrollVerse is set and the destination verse's
    // Y position gets reported via Modifier.onGloballyPositioned as the
    // new chapter lays out. Wait for both conditions, then
    // animateScrollTo. The 50ms delay at the start gives
    // verseScrollState.maxValue time to settle to the new chapter's full
    // content height — without it, animateScrollTo can land short of the
    // requested offset if maxValue hasn't caught up to the freshly
    // mounted verse list yet.
    androidx.compose.runtime.LaunchedEffect(pendingScrollVerse, verseYOffsetsVersion) {
        val target = pendingScrollVerse
        if (target != null) {
            val offset = verseYOffsetsMap[target]
            if (offset != null) {
                kotlinx.coroutines.delay(50)
                verseScrollState.animateScrollTo(offset)
                pendingScrollVerse = null
            }
        }
    }

    // Track the live verse-scroll position for the active chapter so re-
    // entering it via the jump strip resumes at the same place.
    androidx.compose.runtime.LaunchedEffect(verseScrollState.value) {
        val bn = selectedBookNumber
        val cn = selectedChapterNumber
        if (bn != null && cn != null) {
            scrollOffsets["$bn:$cn"] = verseScrollState.value
        }
    }

    // Keyboard shortcuts for the reading view. We attach onPreviewKeyEvent to
    // the outer Box so events bubble up from any focused descendant (a chapter
    // card, a verse row, a clickable link, etc.) — no explicit focusable needed.
    val chapterShortcutHandler:
        (androidx.compose.ui.input.key.KeyEvent) -> Boolean = handler@{ event ->
        if (event.type != KeyEventType.KeyDown) {
            return@handler false
        }

        // The shared [BibleSearchState] owns the search bar's keyboard:
        // Ctrl+F toggles it, Esc / Ctrl+F close it (with the 250 ms
        // auto-repeat guard), and while it is open the reading shortcuts
        // below are suspended so typing in the query field never flips
        // views.
        if (search.handleKeyEvent(event)) {
            return@handler true
        }

        val chapter = selectedChapter
        val book = selectedBook
        // `book` is non-null whenever `chapter` is: the chapter is derived
        // from `book?.chapters?.find { … }`, so the compiler can prove the
        // `book != null` part of these guards is always true and drops it.
        when {
            chapter != null &&
                ((event.isCtrlPressed && event.key == Key.DirectionLeft) ||
                    event.key == Key.PageUp) -> {
                if (chapter.chapter > 1) {
                    selectedChapterNumber = (chapter.chapter - 1).coerceAtLeast(1)
                    selectedVerseNumber = null
                }
                true
            }

            chapter != null &&
                ((event.isCtrlPressed && event.key == Key.DirectionRight) ||
                    event.key == Key.PageDown) -> {
                if (chapter.chapter < book.chapters.size) {
                    selectedChapterNumber =
                        (chapter.chapter + 1).coerceAtMost(book.chapters.size)
                    selectedVerseNumber = null
                }
                true
            }

            selectedChapterNumber != null && event.key == Key.Escape -> {
                selectedChapterNumber = null
                selectedVerseNumber = null
                true
            }

            // Ctrl+G: open the jump-to-verse dialog from any reading view.
            event.isCtrlPressed && event.key == Key.G -> {
                jumpDialogOpen = true
                true
            }

            else -> false
        }
    }

    MaxWidthScaffold(
        compact = compact,
        modifier = Modifier.onPreviewKeyEvent(chapterShortcutHandler),
        maxWidth = SettingsManager.bibleMaxWidth
    ) {
            // In SPLIT mode the bible pane is half the window width, so the
            // non-compact 24.dp inner padding (which looks generous in the
            // standalone full-width view) is excessive here — it shrinks the
            // usable content area by ~40.dp on each side relative to the
            // notes editor pane (which uses 16.dp outer + 0.dp inner
            // padding), making the two panes look visibly mismatched in
            // size. We drop the inner padding to 12.dp in compact mode so
            // both panes end up with a comparable 16.dp + 12.dp = 28.dp
            // content margin and the verses column gets more breathing room.
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier
                    .padding(if (compact) 12.dp else 24.dp)
                    .fillMaxSize()
            ) {
                // Header: title (standalone) + quick translation switcher.
                // The switcher is shown in both the full Bible screen and
                // the compact SPLIT pane, so switching Bibles never
                // requires leaving the reading view.
                if (showBackButton) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Bible",
                            style = MaterialTheme.typography.headlineMedium
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            HistoryButtons(
                                canBack = historyIndex > 0,
                                canForward = historyIndex < historyStack.lastIndex,
                                onBack = ::goHistoryBack,
                                onForward = ::goHistoryForward
                            )
                            TranslationSwitcher(
                                compact = false,
                                onOpenGlobalSearch = onOpenGlobalSearch
                            )
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            HistoryButtons(
                                canBack = historyIndex > 0,
                                canForward = historyIndex < historyStack.lastIndex,
                                onBack = ::goHistoryBack,
                                onForward = ::goHistoryForward
                            )
                            TranslationSwitcher(
                                compact = true,
                                onOpenGlobalSearch = onOpenGlobalSearch
                            )
                        }
                    }
                }

                if (search.open) {
                    BibleSearchBarFor(search)
                }

                if (booksLoading) {
                    // First open of a (possibly large) translation — show a
                    // spinner while the module is parsed off the UI thread.
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            CircularProgressIndicator()
                            Text(
                                text = "Loading translation…",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else if (search.open) {
                    // Full-text search results replace the reading view while
                    // the bar is open; the underlying book/chapter selection is
                    // untouched, so closing the search returns exactly where
                    // the user was.
                    BibleSearchResultsFor(
                        state = search,
                        onOpen = ::openSearchResult,
                        // Interlinear + word study, forwarded verbatim so a
                        // search-result verse matches the reading views: the
                        // LINE / word-aligned Greek line (when the toggle is
                        // on and the active translation isn't itself the
                        // Greek module) plus the shared word-study panel.
                        interlinearOn = interlinearMode != InterlinearMode.OFF &&
                            currentModuleId != BibleRepository.INTERLINEAR_MODULE_ID,
                        interlinearAligned = interlinearMode == InterlinearMode.ALIGNED,
                        greekBooks = greekBooks,
                        // The Bible pane owns the word-study state, so
                        // Strong's-marked result verses render their
                        // English side as clickable word-study tokens
                        // (Home's search results keep plain text — it has
                        // no word-study wiring).
                        wordStudyEnabled = true,
                        strongsLoaded = strongsLoaded,
                        activeStudyWord = activeStudyWord,
                        onToggleStudyWord = { token ->
                            SoundManager.play(SoundEvent.Click)
                            activeStudyWord = if (activeStudyWord == token) {
                                null
                            } else {
                                token
                            }
                        },
                        modifier = Modifier.weight(1f)
                    )
                } else {
                    // Three reading modes: book picker → chapter picker → verses.
                    // The chapters list, book header, and testament indicator are
                    // kept out of the reading view so the bible text gets the screen.
                    when {
                    selectedBook == null -> {
                        TabRow(selectedTabIndex = testament.ordinal) {
                            Tab(
                                selected = testament == Testament.OLD,
                                onClick = {
                                    testament = Testament.OLD
                                    selectedBookNumber = null
                                    selectedChapterNumber = null
                                    selectedVerseNumber = null
                                },
                                text = { Text("Old Testament") }
                            )
                            Tab(
                                selected = testament == Testament.NEW,
                                onClick = {
                                    testament = Testament.NEW
                                    selectedBookNumber = null
                                    selectedChapterNumber = null
                                    selectedVerseNumber = null
                                },
                                text = { Text("New Testament") }
                            )
                        }

                        Text(
                            text = "${visibleBooks.size} books",
                            style = MaterialTheme.typography.bodyMedium
                        )

                        BoxWithConstraints(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                        ) {
                            val columns = responsiveColumns(maxWidth, 160.dp)

                            LazyVerticalGrid(
                                columns = GridCells.Fixed(columns),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .fillMaxHeight(),
                                state = bookGridState
                            ) {
                                items(visibleBooks) { book ->
                                BookCard(
                                    book = book,
                                    starred = SettingsManager.isBookStarred(book.book),
                                    onOpen = {
                                        SoundManager.play(SoundEvent.Click)
                                        selectedBookNumber = book.book
                                        selectedChapterNumber = null
                                        selectedVerseNumber = null
                                        recordNavigation(book.book, null, null)
                                    },
                                    onToggleStar = {
                                        SoundManager.play(SoundEvent.Click)
                                        SettingsManager.toggleBookStar(book.book)
                                    }
                                )
                                }
                            }
                        }
                    }

                    // Whole-book continuous reading: one scrolling passage
                    // with chapter headings as separators. Clicking a verse
                    // drops back into the normal chapter view at that verse.
                    continuousMode -> {
                        val book = selectedBook
                        ContinuousReadingView(
                            book = book,
                            interlinearMode = interlinearMode,
                            greekBooks = greekBooks,
                            strongsLoaded = strongsLoaded,
                            activeStudyWord = activeStudyWord,
                            onOpenLexicon = onOpenLexicon,
                            onToggleStudyWord = { token ->
                                SoundManager.play(SoundEvent.Click)
                                activeStudyWord = if (activeStudyWord == token) {
                                    null
                                } else {
                                    token
                                }
                            },
                            onOpenVerse = { cn, vn ->
                                SoundManager.play(SoundEvent.Click)
                                continuousMode = false
                                selectedChapterNumber = cn
                                selectedVerseNumber = vn
                                pendingScrollVerse = vn
                                recordNavigation(book.book, cn, vn)
                            },
                            onExit = {
                                SoundManager.play(SoundEvent.Click)
                                continuousMode = false
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                        )
                    }

                    selectedChapterNumber == null -> {
                        Text(
                            text = if (testament == Testament.OLD) {
                                "Old Testament"
                            } else {
                                "New Testament"
                            },
                            style = MaterialTheme.typography.titleMedium
                        )

                        Row(
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = selectedBook.name,
                                    style = MaterialTheme.typography.titleLarge
                                )
                                Text(
                                    text = "${selectedBook.chapters.size} chapters",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Text(
                                    text = "Read whole book \u2192",
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.clickable {
                                        SoundManager.play(SoundEvent.Click)
                                        continuousMode = true
                                        selectedVerseNumber = null
                                    }
                                )
                                Text(
                                    text = "Back to books",
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.clickable {
                                        SoundManager.play(SoundEvent.Click)
                                        selectedBookNumber = null
                                        selectedChapterNumber = null
                                        selectedVerseNumber = null
                                    }
                                )
                            }
                        }

                        BoxWithConstraints(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                        ) {
                            val columns = responsiveColumns(maxWidth, 220.dp)

                            LazyVerticalGrid(
                                columns = GridCells.Fixed(columns),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .fillMaxHeight(),
                                state = chapterGridState
                            ) {
                                items(selectedBook.chapters) { chapter ->
                                ChapterCard(
                                    book = selectedBook,
                                    chapterNumber = chapter.chapter,
                                    starred = SettingsManager.isChapterStarred(
                                        selectedBook.book,
                                        chapter.chapter
                                    ),
                                    onOpen = {
                                        SoundManager.play(SoundEvent.Click)
                                        selectedChapterNumber = chapter.chapter
                                        selectedVerseNumber = null
                                        recordNavigation(selectedBook.book, chapter.chapter, null)
                                    },
                                    onToggleStar = {
                                        SoundManager.play(SoundEvent.Click)
                                        SettingsManager.toggleChapterStar(
                                            selectedBook.book,
                                            chapter.chapter
                                        )
                                    }
                                )
                                }
                            }
                        }
                    }

                    else -> {
                        // A chapter is selected — show only the bible text.
                        selectedChapter?.let { chapter ->
                            val goPrev: () -> Unit = {
                                val next = (chapter.chapter - 1).coerceAtLeast(1)
                                selectedChapterNumber = next
                                selectedVerseNumber = null
                                recordNavigation(selectedBook.book, next, null)
                            }
                            val goNext: () -> Unit = {
                                val next = (chapter.chapter + 1).coerceAtMost(
                                    selectedBook.chapters.size
                                )
                                selectedChapterNumber = next
                                selectedVerseNumber = null
                                recordNavigation(selectedBook.book, next, null)
                            }
                            val goScrub: (Int) -> Unit = { newChapter ->
                                val next = newChapter.coerceIn(
                                    1,
                                    selectedBook.chapters.size
                                )
                                selectedChapterNumber = next
                                selectedVerseNumber = null
                                recordNavigation(selectedBook.book, next, null)
                            }
                            val goBack: () -> Unit = {
                                selectedChapterNumber = null
                                selectedVerseNumber = null
                            }

                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f)
                            ) {
                                Column(
                                    verticalArrangement = Arrangement.spacedBy(12.dp),
                                    modifier = Modifier
                                        .padding(if (compact) 0.dp else 16.dp)
                                        .fillMaxSize()
                                ) {
                                    // Whole-chapter clipboard text ("Book C" then
                                    // numbered verses, Strong's markup stripped),
                                    // for the Copy chapter pill below.
                                    val chapterCopy = remember(
                                        selectedBook.name,
                                        chapter.chapter,
                                        chapter.verses
                                    ) {
                                        chapterCopyText(
                                            selectedBook.name,
                                            chapter.chapter,
                                            chapter.verses
                                        )
                                    }
                                    Row(
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        // The title shrinks first so the header
                                        // pills never overflow, even in the
                                        // compact SPLIT pane.
                                        Text(
                                            text = "${selectedBook.name} ${chapter.chapter}",
                                            style = MaterialTheme.typography.titleLarge,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f, fill = false)
                                        )
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                                        ) {
                                            // One-click whole-chapter copy (with
                                            // transient "✓ Copied" feedback).
                                            CopyPill(
                                                copyText = chapterCopy,
                                                label = "Copy chapter"
                                            )
                                            // Interlinear view toggle: cycles
                                            // OFF → Greek line → word-aligned.
                                            // Hidden while the active translation
                                            // IS the Greek module (nothing to
                                            // interline). While the Greek module
                                            // is still parsing on the first
                                            // toggle-on, the pill shows "…".
                                            if (currentModuleId !=
                                                BibleRepository.INTERLINEAR_MODULE_ID
                                            ) {
                                                InterlinearToggle(
                                                    mode = interlinearMode,
                                                    loading = interlinearMode !=
                                                        InterlinearMode.OFF &&
                                                        greekBooks == null,
                                                    alignedAvailable = supportsWordAlignment,
                                                    onCycle = {
                                                        interlinearMode =
                                                            when (interlinearMode) {
                                                                InterlinearMode.OFF ->
                                                                    InterlinearMode.LINE

                                                                // Word-aligned only exists for
                                                                // Strong's-tagged translations;
                                                                // otherwise cycle straight back off.
                                                                InterlinearMode.LINE ->
                                                                    if (supportsWordAlignment) {
                                                                        InterlinearMode.ALIGNED
                                                                    } else {
                                                                        InterlinearMode.OFF
                                                                    }

                                                                InterlinearMode.ALIGNED ->
                                                                    InterlinearMode.OFF
                                                            }
                                                    }
                                                )
                                            }
                                            // Cross-reference panel toggle: shows the
                                            // selected verse's derived cross references
                                            // (parallels / OT quotations / thematic).
                                            CrossRefsToggle(
                                                active = crossRefsOpen,
                                                onClick = {
                                                    SoundManager.play(SoundEvent.Click)
                                                    crossRefsOpen = !crossRefsOpen
                                                }
                                            )
                                            // Verse actions: compare across
                                            // translations / save to a
                                            // collection. Shown once a verse is
                                            // selected — like the cross-reference
                                            // toggle, both key off the selection.
                                            val selectedForActions = selectedVerseNumber
                                            if (selectedForActions != null) {
                                                VerseActionPill(
                                                    label = "⇄ Compare",
                                                    tooltip = "Compare this verse across translations",
                                                    onClick = {
                                                        onOpenCompare(
                                                            selectedBook.name,
                                                            chapter.chapter,
                                                            selectedForActions
                                                        )
                                                    }
                                                )
                                                VerseActionPill(
                                                    label = "＋ Collection",
                                                    tooltip = "Add this verse to a personal collection",
                                                    onClick = {
                                                        addToCollectionOpen = true
                                                    }
                                                )
                                            }
                                            // Range copy + PDF export live behind a
                                            // small "More ▾" menu so the header
                                            // stays tidy in the compact SPLIT pane.
                                            ChapterMoreMenu(
                                                chapterCopy = chapterCopy,
                                                bookName = selectedBook.name,
                                                chapterNumber = chapter.chapter,
                                                verses = chapter.verses,
                                                onOpenRangeDialog = { copyRangeOpen = true },
                                                onOpenGlobalSearch = onOpenGlobalSearch,
                                                onExportPdf = {
                                                    NotePdfExporter.exportChapterAsPdf(
                                                        bookName = selectedBook.name,
                                                        chapterNumber = chapter.chapter,
                                                        verses = chapter.verses,
                                                        translationName = BibleCatalog
                                                            .entryFor(SettingsManager.translation)
                                                            ?.displayName
                                                    )
                                                }
                                            )
                                            Text(
                                                text = "← Chapters",
                                                color = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.clickable {
                                                    SoundManager.play(SoundEvent.Click)
                                                    goBack()
                                                }
                                            )
                                        }
                                    }

                                    ChapterJumpStrip(
                                        book = selectedBook,
                                        chapterNumber = chapter.chapter,
                                        onPrev = goPrev,
                                        onNext = goNext,
                                        onScrub = goScrub,
                                        onScrollToTop = {
                                            scope.launch {
                                                verseScrollState.scrollTo(0)
                                            }
                                        }
                                    )

                                    // Cross-reference panel for the selected verse,
                                    // derived from shared Strong's lemmas (no curated
                                    // dataset is bundled, so nothing is invented).
                                    if (crossRefsOpen) {
                                        val selectedVerse = selectedVerseNumber
                                        if (selectedVerse != null) {
                                            CrossReferencesPanel(
                                                bookNumber = selectedBook.book,
                                                chapterNumber = chapter.chapter,
                                                verseNumber = selectedVerse,
                                                books = books,
                                                onOpenReference = { bookName, cn, vn ->
                                                    val targetBook = books.find {
                                                        it.name.equals(bookName, ignoreCase = true)
                                                    }
                                                    if (targetBook != null) {
                                                        jumpToVerse(targetBook.book, cn, vn)
                                                    }
                                                }
                                            )
                                        } else {
                                            Text(
                                                text = "Select a verse to see its cross references.",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }

                                    // "Add to collection" dialog for the
                                    // selected verse (opened via the header
                                    // pill). The verse is non-null whenever
                                    // the pill is visible.
                                    if (addToCollectionOpen && selectedVerseNumber != null) {
                                        AddToCollectionDialog(
                                            bookNumber = selectedBook.book,
                                            bookName = selectedBook.name,
                                            chapter = chapter.chapter,
                                            verse = selectedVerseNumber ?: 1,
                                            onDismiss = { addToCollectionOpen = false }
                                        )
                                    }

                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .weight(1f),
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        Column(
                                            modifier = Modifier
                                                .weight(1f)
                                                .fillMaxHeight()
                                                .verticalScroll(verseScrollState),
                                            verticalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            // Interlinear: map this chapter's
                                            // verse numbers to their Greek TR
                                            // text, built ONCE per chapter (not
                                            // per verse) so the three linear
                                            // scans over the Greek module run
                                            // once instead of on every row
                                            // composition. Empty for OT books
                                            // (trparsed is NT-only) and while
                                            // the module is still loading.
                                            val greekVerseByNumber: Map<Int, String> =
                                                remember(
                                                    greekBooks,
                                                    selectedBook.book,
                                                    chapter.chapter,
                                                    interlinearMode,
                                                    currentModuleId
                                                ) {
                                                    if (interlinearMode !=
                                                        InterlinearMode.OFF &&
                                                        currentModuleId !=
                                                        BibleRepository.INTERLINEAR_MODULE_ID
                                                    ) {
                                                        BibleRepository.greekVersesForChapter(
                                                            greekBooks,
                                                            selectedBook.book,
                                                            chapter.chapter
                                                        )
                                                    } else {
                                                        emptyMap()
                                                    }
                                                }
                                            // trparsed is New-Testament-only: an
                                            // interlinear-enabled OT chapter has no
                                            // Greek lines to render, so show a
                                            // graceful hint instead of silence.
                                            // Mirrors the whole-book reader.
                                            if (interlinearMode !=
                                                InterlinearMode.OFF &&
                                                currentModuleId !=
                                                BibleRepository.INTERLINEAR_MODULE_ID &&
                                                selectedBook.book <= 39
                                            ) {
                                                GreekNtOnlyHint()
                                            }
                                            chapter.verses.forEach { verse ->
                                                // Hover highlight adapts to the reference's granularity: a
                                                // verse ref highlights that single verse, a chapter ref
                                                // highlights every verse in the chapter, a book ref
                                                // highlights nothing (book-level hover has no verse
                                                // target to tint).
                                                val hovered = hoveredBibleReference
                                                // Compare by canonical book number so a note's
                                                // reference resolves across languages too (e.g.
                                                // German `$Lukas` hovering over English "Luke").
                                                val hoverHighlighted = hovered != null &&
                                                    BibleRepository.bookNumberFor(hovered.book) == selectedBook.book &&
                                                    when {
                                                        hovered.verse != null ->
                                                            hovered.chapter == chapter.chapter &&
                                                                hovered.verse == verse.verse

                                                        hovered.chapter != null ->
                                                            hovered.chapter == chapter.chapter

                                                        else -> false
                                                    }
                                                VerseRow(
                                                    bookName = selectedBook.name,
                                                    bookNumber = selectedBook.book,
                                                    chapterNumber = chapter.chapter,
                                                    verseNumber = verse.verse,
                                                    text = verse.text,
                                                    selected = selectedVerseNumber == verse.verse,
                                                    interlinearGreek = greekVerseByNumber[verse.verse],
                                                    onOpenLexicon = onOpenLexicon,
                                                    interlinearAligned =
                                                        interlinearMode == InterlinearMode.ALIGNED,
                                                    hoverHighlighted = hoverHighlighted,
                                                    onOpenNoteTitle = onOpenNoteTitle,
                                                    onClick = {
                                                        SoundManager.play(SoundEvent.Click)
                                                        val next = if (
                                                            selectedVerseNumber == verse.verse
                                                        ) {
                                                            null
                                                        } else {
                                                            verse.verse
                                                        }
                                                        selectedVerseNumber = next
                                                        recordNavigation(
                                                            selectedBook.book,
                                                            chapter.chapter,
                                                            next
                                                        )
                                                    },
                                                    onToggleMarker = { markerColor ->
                                                        SoundManager.play(SoundEvent.Click)
                                                        SettingsManager.setVerseMarkerColor(
                                                            selectedBook.book,
                                                            chapter.chapter,
                                                            verse.verse,
                                                            markerColor
                                                        )
                                                    },
                                                    onVersePositioned = { verseNum, y ->
                                                        // Mutate the raw map and bump a
                                                        // version counter so the parent
                                                        // LaunchedEffect re-fires without
                                                        // allocating a new stateful map on
                                                        // every callback.
                                                        verseYOffsetsMap[verseNum] = y
                                                        verseYOffsetsVersion++
                                                    },
                                                    strongsLoaded = strongsLoaded,
                                                    activeStudyWord = activeStudyWord,
                                                    onToggleStudyWord = { token ->
                                                        SoundManager.play(SoundEvent.Click)
                                                        activeStudyWord = if (
                                                            activeStudyWord == token
                                                        ) {
                                                            null
                                                        } else {
                                                            token
                                                        }
                                                    }
                                                )
                                            }
                                        }

                                        if (verseScrollState.maxValue > 0) {
                                            VerticalScrollbar(
                                                adapter = rememberScrollbarAdapter(verseScrollState),
                                                modifier = Modifier.fillMaxHeight()
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                    if (showBackButton) {
                        Text(
                            text = "Back",
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable {
                                SoundManager.play(SoundEvent.Click)
                                back()
                            }
                        )
                    }
                }
            }
        }

        // Copy-range dialog — opened from the chapter header's "More ▾"
        // menu. Rendered at the screen root so it overlays the pane.
        if (copyRangeOpen) {
            selectedChapter?.let { chapter ->
                CopyRangeDialog(
                    bookName = selectedBook.name,
                    chapterNumber = chapter.chapter,
                    verses = chapter.verses,
                    onDismiss = { copyRangeOpen = false },
                    onOpenGlobalSearch = onOpenGlobalSearch
                )
            }
        }

        // Ctrl+G jump-to-verse dialog. Passed the open book (if any) so
        // it pre-fills; the Go action jumps the pane to the target verse.
        if (jumpDialogOpen) {
            JumpToVerseDialog(
                books = books,
                initialBook = selectedBook?.name,
                onJump = { bn, cn, vn ->
                    jumpDialogOpen = false
                    jumpToVerse(bn, cn, vn)
                },
                onDismiss = { jumpDialogOpen = false },
                onOpenGlobalSearch = onOpenGlobalSearch
            )
        }
}


/**
 * Compact "⛓ References" pill in the chapter header, mirroring the
 * InterlinearToggle's visual language: a primary-container pill while the
 * cross-reference panel is open, a dimmed surface pill otherwise. A hover
 * tooltip explains what it shows.
 */
@Composable
private fun CrossRefsToggle(
    active: Boolean,
    onClick: () -> Unit
) {
    ToolbarTip(label = "Cross references: parallels, Old-Testament quotations and thematically related passages for the selected verse") {
        Surface(
            shape = PillShape,
            color = if (active) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f)
            },
            modifier = Modifier.clickable(onClick = onClick)
        ) {
            Text(
                text = if (active) "⛓·on" else "⛓",
                style = MaterialTheme.typography.labelSmall,
                color = if (active) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
            )
        }
    }
}


/**
 * One-shot action pill for the chapter header's verse actions (compare /
 * collection), mirroring the CrossRefsToggle visual language but firing
 * once instead of toggling.
 */
@Composable
private fun VerseActionPill(
    label: String,
    tooltip: String,
    onClick: () -> Unit
) {
    ToolbarTip(label = tooltip) {
        Surface(
            shape = PillShape,
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f),
            modifier = Modifier.clickable(onClick = onClick)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
            )
        }
    }
}


/**
 * Quick translation switcher for the Bible pane header. One dropdown
 * lists every discovered module grouped by language (scrollable — there
 * are ~90 of them). Picking one persists it via SettingsManager (module
 * id + language), which triggers `BibleRepository.books()` to reload and
 * recompose the pane on the same book/chapter in the new translation.
 * Reading [SettingsManager.translation] here keeps the label in sync with
 * the Settings picker.
 */
@Composable
private fun TranslationSwitcher(
    compact: Boolean,
    onOpenGlobalSearch: () -> Unit = {}
) {
    var menuOpen by remember { mutableStateOf(false) }
    // Include the language so same-named translations across languages
    // (e.g. two "Luther" modules) are distinguishable at a glance.
    val currentEntry = BibleCatalog.entryFor(SettingsManager.translation)
    val currentName = currentEntry?.let { "${it.languageName} · ${it.displayName}" }
        ?: "Bible"

    Box {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable { menuOpen = true }
                .padding(horizontal = 10.dp, vertical = if (compact) 4.dp else 6.dp)
        ) {
            Text(
                text = currentName,
                style = if (compact) {
                    MaterialTheme.typography.labelMedium
                } else {
                    MaterialTheme.typography.bodyMedium
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = if (compact) 200.dp else 320.dp)
            )
            Text(
                text = "▾",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false }
        ) {
            // Ctrl+F while this dropdown (a focusable popup window) is
            // open: dismiss it and open the global search — the root
            // handler can't see the popup.
            val dropdownKeyHandler = globalSearchDialogKeyHandler(
                onDismiss = { menuOpen = false },
                onOpenGlobalSearch = onOpenGlobalSearch
            )
            // Grouped by language (non-clickable separator items);
            // scrollable so the full catalog fits on screen. The currently
            // active module is marked bold-primary so the user can see at
            // a glance which translation is loaded.
            // NOTE: `heightIn` must come BEFORE `verticalScroll` — with
            // the scroll modifier outermost, a DropdownMenu popup measures
            // its content with infinite max height while it re-lays-out
            // during dismissal (e.g. right as a translation switch
            // recomposes the pane), which makes the scrollable throw
            // "measured with an infinity maximum height constraints".
            // Bounding the max height first gives the scrollable a finite
            // constraint to work with.
            Column(
                modifier = Modifier
                    .onPreviewKeyEvent(dropdownKeyHandler)
                    .heightIn(max = 380.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                var lastLanguage: String? = null
                for (entry in BibleCatalog.entries) {
                    if (entry.languageName != lastLanguage) {
                        lastLanguage = entry.languageName
                        // Non-clickable language separator. `enabled = false`
                        // renders it dimmed — reads as a header, not an
                        // available action.
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = entry.languageName,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            },
                            enabled = false,
                            onClick = {}
                        )
                    }
                    val active = entry.moduleId == SettingsManager.translation
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = entry.displayName,
                                color = if (active) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                                fontWeight = if (active) {
                                    androidx.compose.ui.text.font.FontWeight.Bold
                                } else {
                                    androidx.compose.ui.text.font.FontWeight.Normal
                                }
                            )
                        },
                        onClick = {
                            menuOpen = false
                            SoundManager.play(SoundEvent.Click)
                            SettingsManager.translation = entry.moduleId
                            SettingsManager.language = entry.languageName
                        }
                    )
                }
            }
        }
    }
}


/**
 * "More ▾" menu in the chapter view header: copy a verse range (opens
 * the [CopyRangeDialog]) and export the chapter as a PDF. The whole-
 * chapter copy has its own one-click [CopyPill] next to this menu.
 */
@Composable
private fun ChapterMoreMenu(
    chapterCopy: String,
    bookName: String,
    chapterNumber: Int,
    verses: List<Verse>,
    onOpenRangeDialog: () -> Unit,
    onExportPdf: () -> Unit,
    onOpenGlobalSearch: () -> Unit = {}
) {
    val clipboard = LocalClipboardManager.current
    var expanded by remember { mutableStateOf(false) }
    Box {
        Surface(
            shape = PillShape,
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f),
            modifier = Modifier.clickable {
                SoundManager.play(SoundEvent.Click)
                expanded = true
            }
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
            ) {
                Text(
                    text = "More",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Icon(
                    imageVector = RibbonIcons.ChevronDown,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            // Ctrl+F while this dropdown (a focusable popup window) is
            // open: dismiss it and open the global search — the root
            // handler can't see the popup.
            val dropdownKeyHandler = globalSearchDialogKeyHandler(
                onDismiss = { expanded = false },
                onOpenGlobalSearch = onOpenGlobalSearch
            )
            Column(modifier = Modifier.onPreviewKeyEvent(dropdownKeyHandler)) {
                DropdownMenuItem(
                    text = { Text("Copy whole chapter") },
                    onClick = {
                        expanded = false
                        SoundManager.play(SoundEvent.Click)
                        clipboard.setText(AnnotatedString(chapterCopy))
                    }
                )
                DropdownMenuItem(
                    text = { Text("Copy verse range\u2026") },
                    onClick = {
                        expanded = false
                        SoundManager.play(SoundEvent.Click)
                        onOpenRangeDialog()
                    }
                )
                DropdownMenuItem(
                    text = { Text("Export chapter as PDF\u2026") },
                    onClick = {
                        expanded = false
                        SoundManager.play(SoundEvent.Click)
                        onExportPdf()
                    }
                )
            }
        }
    }
}


/**
 * Muted one-line note shown when the interlinear view is on for an
 * Old-Testament book: the bundled Greek TR module (trparsed) covers the
 * New Testament only, so no Greek line renders for OT verses. Deliberately
 * tiny and dimmed so it reads as a hint, not an error — the reading view
 * itself is unchanged.
 */
@Composable
private fun GreekNtOnlyHint(modifier: Modifier = Modifier) {
    Text(
        text = "Greek TR (Textus Receptus) is available for the New Testament " +
            "only \u2014 no interlinear for Old Testament books.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
    )
}


/**
 * Whole-book continuous reading: every chapter of [book] rendered as one
 * scrolling passage, with chapter headings as separators. Each verse is a
 * row (verse number + text — Strong's-marked verses render clickable
 * word-study tokens) whose click drops back into the normal chapter view
 * at that verse; the "← Chapters" link exits without jumping. When the
 * interlinear mode is on it renders beneath each verse too, via the same
 * shared [VerseInterlinear] as the chapter view, so both modes stay
 * consistent across the whole book.
 */
@Composable
private fun ContinuousReadingView(
    book: Book,
    // Interlinear support — passed through so the whole-book view matches
    // the chapter view exactly: the active mode (OFF / LINE / ALIGNED),
    // the lazily loaded Greek TR module, and the word-study wiring.
    interlinearMode: InterlinearMode,
    greekBooks: List<Book>?,
    strongsLoaded: Boolean,
    activeStudyWord: StudyWordToken?,
    onToggleStudyWord: (StudyWordToken) -> Unit,
    /** Opens the central lexicon at a Strong's number (wired by the host
     *  screen; defaults to no-op). */
    onOpenLexicon: (String) -> Unit = {},
    onOpenVerse: (chapterNumber: Int, verseNumber: Int) -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()
    Card(
        modifier = modifier
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(16.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "${book.name} \u2014 whole book",
                    style = MaterialTheme.typography.titleLarge
                )
                Text(
                    text = "\u2190 Chapters",
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable(onClick = onExit)
                )
            }
            Text(
                text = "Click any verse to open it in the chapter view.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            )

            book.chapters.forEach { chapter ->
                Text(
                    text = "Chapter ${chapter.chapter}",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 8.dp)
                )
                val showInterlinear = interlinearMode != InterlinearMode.OFF
                // trparsed is New-Testament-only: instead of silently
                // rendering nothing under OT verses, explain why there is
                // no Greek line for this book.
                if (showInterlinear && book.book <= 39) {
                    GreekNtOnlyHint(
                        modifier = Modifier.padding(bottom = 2.dp)
                    )
                }
                val aligned = interlinearMode == InterlinearMode.ALIGNED
                // Greek TR text per (chapter, verse) of this book, built
                // once per book while the interlinear view is on. trparsed
                // is New-Testament-only, so Old-Testament books simply
                // yield an empty map (no interlinear blocks, as in the
                // chapter view).
                val greekByRef: Map<Pair<Int, Int>, String> = remember(
                    book,
                    greekBooks,
                    showInterlinear
                ) {
                    if (showInterlinear) {
                        BibleRepository.greekVersesForBook(greekBooks, book.book)
                    } else {
                        emptyMap()
                    }
                }

                chapter.verses.forEach { verse ->
                    // Strong's-marked verses render clickable tokens (word
                    // study) exactly like the chapter view's VerseRow.
                    val tokens = remember(verse.text) {
                        parseWordStudyTokens(verse.text)
                    }
                    val activeForVerse = activeStudyWord?.takeIf {
                        it.bookNumber == book.book &&
                            it.chapter == chapter.chapter &&
                            it.verse == verse.verse
                    }
                    val toggleWord: (StrongsToken) -> Unit = { token ->
                        onToggleStudyWord(
                            StudyWordToken(
                                bookNumber = book.book,
                                chapter = chapter.chapter,
                                verse = verse.verse,
                                word = token.word,
                                number = token.number,
                                parsing = token.parsing
                            )
                        )
                    }
                    val greekText = if (showInterlinear) {
                        greekByRef[chapter.chapter to verse.verse]
                    } else {
                        null
                    }

                    Column(
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.Top,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onOpenVerse(chapter.chapter, verse.verse)
                                }
                                .padding(vertical = 2.dp)
                        ) {
                            Text(
                                text = verse.verse.toString(),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 3.dp)
                            )
                            if (tokens.isEmpty()) {
                                Text(
                                    text = stripWordStudyMarkup(verse.text),
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f)
                                )
                            } else {
                                StrongsVerseText(
                                    text = verse.text,
                                    tokens = tokens,
                                    activeNumber = activeForVerse?.number,
                                    onToggle = toggleWord,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                        // Interlinear block, shared with the chapter view
                        // (same LINE / word-aligned rendering). It sits
                        // OUTSIDE the clickable Row — deliberately unlike
                        // the chapter view, where the Greek line's
                        // background toggles verse selection — so the
                        // whole-book reader never navigates by accident;
                        // the numbers themselves still feed word study.
                        if (greekText != null) {
                            // Indent under the verse text (number column +
                            // 8 dp spacing).
                            Box(modifier = Modifier.padding(start = 24.dp)) {
                                VerseInterlinear(
                                    englishText = verse.text,
                                    greekText = greekText,
                                    aligned = aligned,
                                    activeNumber = activeForVerse?.number,
                                    onToggleStudyWord = toggleWord
                                )
                            }
                        }
                        // Open word-study MINI panel, outside the clickable
                        // Row so closing it never navigates. The whole-book
                        // reader shows the compact inline card (not the full
                        // panel the chapter view uses) so a studied word
                        // doesn't push the continuous passage around.
                        if (activeForVerse != null) {
                            WordStudyMiniPanel(
                                token = activeForVerse,
                                loaded = strongsLoaded,
                                onClose = { onToggleStudyWord(activeForVerse) },
                                onOpenLexicon = { onOpenLexicon(it) }
                            )
                        }
                    }
                }
            }
        }
        if (scrollState.maxValue > 0) {
            VerticalScrollbar(
                adapter = rememberScrollbarAdapter(scrollState),
                modifier = Modifier
                    .align(Alignment.End)
                    .fillMaxHeight()
            )
        }
    }
}


/**
 * "Copy verse range" dialog for the chapter view: From / To verse fields
 * (digit-only, validated against the chapter) and a Copy button that
 * writes `Book C:From–To` plus the numbered verses to the clipboard.
 */
@Composable
private fun CopyRangeDialog(
    bookName: String,
    chapterNumber: Int,
    verses: List<Verse>,
    onDismiss: () -> Unit,
    onOpenGlobalSearch: () -> Unit = {}
) {
    val clipboard = LocalClipboardManager.current
    var fromText by remember { mutableStateOf("1") }
    var toText by remember { mutableStateOf(verses.size.toString()) }
    val from = fromText.toIntOrNull()
    val to = toText.toIntOrNull()
    val valid = from != null && to != null &&
        from in 1..verses.size && to in 1..verses.size && from <= to

    // Ctrl+F while this dialog (a separate window) has focus: dismiss it
    // and open the global search — the root handler can't see it.
    val dialogKeyHandler = globalSearchDialogKeyHandler(onDismiss, onOpenGlobalSearch)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Copy verse range") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.onPreviewKeyEvent(dialogKeyHandler)
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = fromText,
                        onValueChange = {
                            fromText = it.filter { c -> c.isDigit() }.take(3)
                        },
                        label = { Text("From verse (1\u2013${verses.size})") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = toText,
                        onValueChange = {
                            toText = it.filter { c -> c.isDigit() }.take(3)
                        },
                        label = { Text("To verse (1\u2013${verses.size})") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
                Text(
                    text = "Copies \u201C$bookName $chapterNumber" +
                        "${from ?: ""}\u2013${to ?: ""}\u201D followed by the " +
                        "numbered verses.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                modifier = Modifier.onPreviewKeyEvent(dialogKeyHandler),
                onClick = {
                    SoundManager.play(SoundEvent.Click)
                    clipboard.setText(
                        AnnotatedString(
                            rangeCopyText(
                                bookName = bookName,
                                chapter = chapterNumber,
                                from = from!!,
                                to = to!!,
                                verses = verses
                            )
                        )
                    )
                    onDismiss()
                }
            ) { Text("Copy") }
        },
        dismissButton = {
            TextButton(
                modifier = Modifier.onPreviewKeyEvent(dialogKeyHandler),
                onClick = {
                    SoundManager.play(SoundEvent.Click)
                    onDismiss()
                }
            ) { Text("Cancel") }
        }
    )
}


/** One history-stack entry: a (canonical book number, chapter, verse). */
private data class NavPoint(
    val bookNumber: Int,
    val chapter: Int?,
    val verse: Int?
)


/**
 * Back / forward affordance for the reading-pane history stack. Renders
 * as small primary chevrons, dimmed when the stack can't move that way.
 */
@Composable
private fun HistoryButtons(
    canBack: Boolean,
    canForward: Boolean,
    onBack: () -> Unit,
    onForward: () -> Unit
) {
    val dimmed = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
    val active = MaterialTheme.colorScheme.primary
    Text(
        text = "\u2190",
        style = MaterialTheme.typography.titleMedium,
        color = if (canBack) active else dimmed,
        modifier = Modifier
            .then(
                if (canBack) Modifier.clickable {
                    SoundManager.play(SoundEvent.Click)
                    onBack()
                } else Modifier
            )
            .padding(horizontal = 4.dp, vertical = 2.dp)
    )
    Text(
        text = "\u2192",
        style = MaterialTheme.typography.titleMedium,
        color = if (canForward) active else dimmed,
        modifier = Modifier
            .then(
                if (canForward) Modifier.clickable {
                    SoundManager.play(SoundEvent.Click)
                    onForward()
                } else Modifier
            )
            .padding(horizontal = 4.dp, vertical = 2.dp)
    )
}


/**
 * Ctrl+G "Jump to verse" dialog: type a book name (with autocomplete),
 * optionally a chapter and verse, and Go jumps the pane there (recorded
 * in history like any other navigation). Verse is optional — leave it
 * blank to jump to the whole chapter.
 */
@Composable
private fun JumpToVerseDialog(
    books: List<Book>,
    initialBook: String?,
    onJump: (bookNumber: Int, chapter: Int, verse: Int?) -> Unit,
    onDismiss: () -> Unit,
    onOpenGlobalSearch: () -> Unit = {}
) {
    var bookQuery by remember { mutableStateOf(initialBook.orEmpty()) }
    var chapterText by remember { mutableStateOf("") }
    var verseText by remember { mutableStateOf("") }
    var showSuggestions by remember { mutableStateOf(false) }
    val query = bookQuery.trim()
    val suggestions = remember(query) {
        if (query.isEmpty()) emptyList()
        else books.filter { it.name.contains(query, ignoreCase = true) }.take(8)
    }
    val book = books.find { it.name.equals(query, ignoreCase = true) }
    val chapter = chapterText.toIntOrNull()
    val verse = verseText.toIntOrNull()
    val chapterValid = book != null && chapter != null &&
        chapter in 1..book.chapters.size
    val verseValid = chapterValid &&
        (verseText.isBlank() || (verse != null &&
            verse in 1..book!!.chapters[chapter!! - 1].verses.size))
    val canGo = chapterValid &&
        (verseText.isBlank() || verseValid)

    fun doJump() {
        if (book != null && chapter != null) {
            onJump(book.book, chapter, if (verseText.isBlank()) null else verse)
        }
    }

    // Ctrl+F while this dialog (a separate window) has focus: dismiss it
    // and open the global search — the root handler can't see it. Attached
    // to the content root, so it fires before the fields' own Enter
    // handlers (which only return true for Enter).
    val dialogKeyHandler = globalSearchDialogKeyHandler(onDismiss, onOpenGlobalSearch)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Jump to verse") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.onPreviewKeyEvent(dialogKeyHandler)
            ) {
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
                            if (event.key == Key.Enter && book == null &&
                                suggestions.isNotEmpty()
                            ) {
                                bookQuery = suggestions.first().name
                                true
                            } else {
                                false
                            }
                        }
                )
                if (showSuggestions && suggestions.isNotEmpty()) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        tonalElevation = 3.dp,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column {
                            suggestions.forEach { candidate ->
                                Text(
                                    text = candidate.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            SoundManager.play(SoundEvent.Click)
                                            bookQuery = candidate.name
                                            showSuggestions = false
                                        }
                                        .padding(horizontal = 12.dp, vertical = 7.dp)
                                )
                            }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = chapterText,
                        onValueChange = {
                            chapterText = it.filter { c -> c.isDigit() }.take(3)
                        },
                        label = {
                            Text(
                                "Chapter" + book?.let {
                                    " (1\u2013${it.chapters.size})"
                                }.orEmpty()
                            )
                        },
                        singleLine = true,
                        modifier = Modifier
                            .weight(1f)
                            .onPreviewKeyEvent { event ->
                                if (event.key == Key.Enter && canGo) {
                                    doJump()
                                    true
                                } else {
                                    false
                                }
                            }
                    )
                    OutlinedTextField(
                        value = verseText,
                        onValueChange = {
                            verseText = it.filter { c -> c.isDigit() }.take(3)
                        },
                        label = {
                            Text(
                                "Verse (optional)" + (book?.let { b ->
                                    chapter?.let { c ->
                                        b.chapters.getOrNull(c - 1)?.let {
                                            " (1\u2013${it.verses.size})"
                                        }.orEmpty()
                                    }.orEmpty()
                                }.orEmpty())
                            )
                        },
                        singleLine = true,
                        modifier = Modifier
                            .weight(1f)
                            .onPreviewKeyEvent { event ->
                                if (event.key == Key.Enter && canGo) {
                                    doJump()
                                    true
                                } else {
                                    false
                                }
                            }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = canGo,
                modifier = Modifier.onPreviewKeyEvent(dialogKeyHandler),
                onClick = {
                    SoundManager.play(SoundEvent.Click)
                    doJump()
                }
            ) { Text("Go") }
        },
        dismissButton = {
            TextButton(
                modifier = Modifier.onPreviewKeyEvent(dialogKeyHandler),
                onClick = {
                    SoundManager.play(SoundEvent.Click)
                    onDismiss()
                }
            ) { Text("Cancel") }
        }
    )
}


/**
 * Interlinear toggle pill in the chapter header: "ΑΩ" (accented when
 * active) toggles the Greek TR verse beneath each NT verse. A hover
 * tooltip explains the mode.
 */
@Composable
private fun InterlinearToggle(
    mode: InterlinearMode,
    // True while the Greek module is still parsing on the first toggle-on;
    // the pill shows "…" so the toggle doesn't look like it did nothing.
    loading: Boolean,
    // Whether the active translation carries Strong's markup, so the
    // word-aligned mode is actually possible (the pill then never shows
    // the aligned state when it can't be rendered).
    alignedAvailable: Boolean,
    onCycle: () -> Unit
) {
    val active = mode != InterlinearMode.OFF
    ToolbarTip(
        label = when {
            loading -> "Loading the Greek TR module\u2026"
            mode == InterlinearMode.ALIGNED -> "Word-aligned Greek \u2014 pairs each Greek token with the English word sharing its Strong's number"
            mode == InterlinearMode.LINE && alignedAvailable -> "Interlinear \u2014 click again for word-aligned"
            mode == InterlinearMode.LINE -> "Interlinear (word-aligned needs a Strong's-tagged translation, e.g. KJV with Strongs)"
            else -> "Interlinear: show the Greek TR beneath each verse"
        }
    ) {
        Surface(
            shape = PillShape,
            color = if (active) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f)
            },
            modifier = Modifier.clickable {
                SoundManager.play(SoundEvent.Click)
                onCycle()
            }
        ) {
            Text(
                text = when {
                    loading -> "\u0391\u03A9\u2026"
                    mode == InterlinearMode.ALIGNED -> "\u0391\u03A9\u2261\u00B7on"
                    mode == InterlinearMode.LINE -> "\u0391\u03A9\u00B7on"
                    else -> "\u0391\u03A9"
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (active) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
            )
        }
    }
}



