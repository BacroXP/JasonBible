@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class
)

package ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import data.BibleCatalog
import data.BibleRepository
import data.SettingsManager
import data.SoundEvent
import data.SoundManager
import data.StrongsRepository
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import model.Book
import model.Verse
import ui.components.MaxWidthScaffold


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
    val verseScrollState = rememberScrollState()

    val scope = rememberCoroutineScope()

    val scrollOffsets: MutableMap<String, Int> = rememberSaveable(
        saver = ScrollPositionSaver
    ) { mutableMapOf() }

    var testament by remember { mutableStateOf(Testament.OLD) }
    // Drill-down level 0: until a testament is picked the pane shows "The
    // Bible" with the two numbered testaments instead of the book grid.
    var testamentChosen by remember { mutableStateOf(false) }
    var selectedBookNumber by remember { mutableStateOf<Int?>(null) }
    var selectedChapterNumber by remember { mutableStateOf<Int?>(null) }
    var selectedVerseNumber by remember { mutableStateOf<Int?>(null) }
    // Verse reference card: clicking a verse opens its mentions / similar
    // texts / Hebrew analysis right under it. Only one chapter is ever on
    // screen, so the card belongs to the chapter view alone.
    var selectedVerseDetail by remember { mutableStateOf<Int?>(null) }
    // Parsed Hebrew module for that card, loaded on the first verse click.
    var hebrewBooks by remember { mutableStateOf<List<Book>?>(null) }
    // Reference the pane was last navigated to from a note chip / search
    // result. Kept highlighted so an arriving verse RANGE stays visible as
    // a range (the transient hover highlight only lives while the pointer
    // sits on the editor chip).
    var activeReference by remember { mutableStateOf<BibleReferenceSelection?>(null) }
    // Pending right-click export: the verses and range preselected in the
    // export dialog, or null while it is closed.
    var exportSeed by remember { mutableStateOf<VerseExportSeed?>(null) }
    // Ctrl+G jump-to-verse dialog.
    var jumpDialogOpen by remember { mutableStateOf(false) }
    // Interlinear view: OFF / LINE (the matching Greek TR verse beneath
    // each NT verse) / ALIGNED (Greek tokens paired word-by-word with the
    // English via Strong's numbers). The Greek module is loaded lazily
    // the first time the pill leaves OFF.
    var interlinearMode by remember { mutableStateOf(InterlinearMode.OFF) }
    var greekBooks by remember { mutableStateOf<List<Book>?>(null) }


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
    val verseYOffsetsMap = remember { mutableMapOf<String, Int>() }
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

    // Root index page: one scrolling column holding both testament sections.
    // The top bar and the numbered rail jump between them, exactly like the
    // Settings screen's section list.
    val rootScroll = rememberScrollState()
    val rootSectionOffsets = remember { mutableStateMapOf<String, Int>() }
    val testamentSections = remember {
        listOf(
            BibleNavSection("ot", "01", "Old Testament"),
            BibleNavSection("nt", "02", "New Testament")
        )
    }
    val rootActiveSectionId = testamentSections
        .lastOrNull { section ->
            (rootSectionOffsets[section.id] ?: Int.MAX_VALUE) <= rootScroll.value + 48
        }
        ?.id
        ?: testamentSections.first().id

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

    // The Hebrew section of the verse card needs the bundled Hebrew module;
    // parse it (off the UI thread, cached afterwards) on the first click.
    androidx.compose.runtime.LaunchedEffect(selectedVerseDetail) {
        if (selectedVerseDetail != null && hebrewBooks == null) {
            hebrewBooks = BibleRepository.loadModule(BibleRepository.HEBREW_MODULE_ID)
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
     * Jump (from Ctrl+G / the jump dialog) to a book / chapter / verse,
     * recording the move in history like any other navigation.
     */
    fun jumpToVerse(bookNumber: Int, chapterNumber: Int, verseNumber: Int?) {
        // A manual jump replaces the reference the pane arrived on.
        activeReference = null
        testament = if (bookNumber <= 39) {
            Testament.OLD
        } else {
            Testament.NEW
        }
        selectedBookNumber = bookNumber
        selectedChapterNumber = chapterNumber
        selectedVerseNumber = verseNumber
        if (verseNumber != null) pendingScrollVerse = verseNumber
    }

    fun openSearchResult(match: BibleSearchMatch) {
        SoundManager.play(SoundEvent.Click)
        activeReference = null
        selectedBookNumber = match.book.book
        testament = if (match.book.book <= 39) {
            Testament.OLD
        } else {
            Testament.NEW
        }
        selectedChapterNumber = match.chapter
        selectedVerseNumber = match.verse
        pendingScrollVerse = match.verse
        search.close()
    }

    /**
     * Open a book straight into a single chapter: the one the reader last
     * left in that book, otherwise its first chapter. The whole-book scroll
     * is gone, so a book click never lands on a "read straight through"
     * listing anymore.
     */
    fun openBook(book: Book) {
        SoundManager.play(SoundEvent.Click)
        activeReference = null
        testament = if (book.book <= 39) {
            Testament.OLD
        } else {
            Testament.NEW
        }
        selectedBookNumber = book.book
        selectedVerseDetail = null
        val last = SettingsManager.getLastRead()
            ?.takeIf { saved -> saved.bookNumber == book.book }
        val chapter = last?.chapterNumber?.coerceIn(
            1,
            book.chapters.size.coerceAtLeast(1)
        ) ?: book.chapters.firstOrNull()?.chapter
        selectedChapterNumber = chapter
        selectedVerseNumber = last?.verseNumber
        // Resume at the exact verse, but only when it really exists in the
        // chapter we are opening (a stale setting must not leave the pane
        // waiting for a verse that never renders).
        pendingScrollVerse = last?.verseNumber?.takeIf { verse ->
            chapter != null &&
                book.chapters.find { it.chapter == chapter }
                    ?.verses?.any { it.verse == verse } == true
        }
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
            selectedChapterNumber = incoming.chapter
            selectedVerseNumber = incoming.verse
            // Keep the whole incoming reference (including its range) so
            // every verse it covers is highlighted on arrival, not just
            // the first one.
            activeReference = incoming
            // Only queue a scroll-to-verse if the requested verse actually
            // exists in the destination chapter. Without this guard, a
            // bogus reference like `$Genesis$1$999` would set
            // pendingScrollVerse to a verse that never appears in
            // verseYOffsetsMap, the scroll-to-verse LaunchedEffect would
            // never resolve, and future chapter changes would lose their
            // normal scroll-restore behaviour until the flag was cleared.
            val targetChapter = matchingBook.chapters?.find { it.chapter == incoming.chapter }
            pendingScrollVerse = incoming.verse?.takeIf { v ->
                targetChapter?.verses?.any { it.verse == v } == true
            }
        } else {
            // Nothing requested explicitly — resume the user's last-read
            // book/chapter from SettingsManager if it still maps to a real
            // book, and fall back to the start of the Bible on a fresh
            // install so "Bible" always lands on a chapter, never the index.
            val last = SettingsManager.getLastRead()
            val lastBook = last?.let { saved ->
                loaded.find { it.book == saved.bookNumber }
            }
            if (last != null && lastBook != null) {
                // Clamp: the same book number can have a different chapter
                // count in another translation, and a missing chapter would
                // render an empty chapter screen.
                val chapter = last.chapterNumber.coerceIn(
                    1,
                    lastBook.chapters.size.coerceAtLeast(1)
                )
                selectedBookNumber = last.bookNumber
                selectedChapterNumber = chapter
                selectedVerseNumber = last.verseNumber
                testament = if (last.bookNumber <= 39) {
                    Testament.OLD
                } else {
                    Testament.NEW
                }
                // Land on the verse that was being read rather than the top
                // of the chapter. Only arm the scroll when that verse really
                // exists in the restored chapter, so a stale setting can't
                // leave the pane waiting for a verse that never renders.
                pendingScrollVerse = last.verseNumber?.takeIf { verse ->
                    lastBook.chapters
                        .find { it.chapter == chapter }
                        ?.verses
                        ?.any { it.verse == verse } == true
                }
            } else {
                loaded.firstOrNull()?.let { firstBook ->
                    selectedBookNumber = firstBook.book
                    selectedChapterNumber = firstBook.chapters.firstOrNull()?.chapter
                    selectedVerseNumber = null
                    testament = Testament.OLD
                }
            }
        }
    }

    // Persist the active book/chapter and restore its scroll position
    // whenever the active chapter changes.
    androidx.compose.runtime.LaunchedEffect(selectedBookNumber, selectedChapterNumber) {
        // A word-study panel (and the verse reference card) belongs to the
        // verse that was on screen; drop both when the chapter changes so a
        // stale panel can't pop back.
        activeStudyWord = null
        selectedVerseDetail = null
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
        val bn = selectedBookNumber
        val cn = selectedChapterNumber
        if (target != null && bn != null && cn != null) {
            val offset = verseYOffsetsMap["$bn:$cn:$target"]
            if (offset != null) {
                delay(50.milliseconds)
                verseScrollState.animateScrollTo(offset)
                pendingScrollVerse = null
            }
        }
    }

    // Track the live verse-scroll position for the active chapter so re-
    // entering it via the jump strip resumes at the same place, and keep
    // the verse at the top of the viewport as the one the pane resumes on.
    androidx.compose.runtime.LaunchedEffect(verseScrollState.value, verseYOffsetsVersion) {
        val bn = selectedBookNumber
        val cn = selectedChapterNumber
        if (bn != null && cn != null) {
            val top = verseScrollState.value
            scrollOffsets["$bn:$cn"] = top
            val prefix = "$bn:$cn:"
            verseYOffsetsMap.entries
                .filter { entry ->
                    entry.key.startsWith(prefix) && entry.value <= top + 8
                }
                .maxByOrNull { entry -> entry.value }
                ?.let { entry ->
                    selectedVerseNumber = entry.key
                        .substring(prefix.length)
                        .toIntOrNull()
                }
        }
    }

    // Persist the exact verse the reader left on when the pane leaves the
    // screen (Back to the menu, split view teardown): the chapter-level
    // save above only records the chapter, so without this the next open
    // would land on the top of the chapter instead of the last verse.
    val liveReadingPosition = androidx.compose.runtime.rememberUpdatedState(
        Triple(selectedBookNumber, selectedChapterNumber, selectedVerseNumber)
    )
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose {
            val position = liveReadingPosition.value
            val book = position.first
            val chapter = position.second
            if (book != null && chapter != null) {
                SettingsManager.setLastRead(book, chapter, position.third)
            }
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
                // Esc leaves the chapter for the book index; remember the
                // verse the reader was on so the next open resumes there.
                if (chapter != null && book != null) {
                    SettingsManager.setLastRead(
                        book.book,
                        chapter.chapter,
                        selectedVerseNumber
                    )
                }
                selectedBookNumber = null
                selectedChapterNumber = null
                selectedVerseNumber = null
                selectedVerseDetail = null
                testamentChosen = true
                true
            }

            // Esc climbs the drill-down: book → book list → testaments.
            selectedBookNumber != null && event.key == Key.Escape -> {
                selectedBookNumber = null
                selectedChapterNumber = null
                selectedVerseNumber = null
                testamentChosen = true
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
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (showBackButton) {
                        Text(
                            text = "\u2190 Menu",
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.clickable {
                                SoundManager.play(SoundEvent.Click)
                                back()
                            }
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    TranslationSwitcher(
                        compact = compact,
                        onOpenGlobalSearch = onOpenGlobalSearch
                    )
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
                    // Two levels: the testament / book index, then the one
                    // chapter the reader opened. Only a single chapter is
                    // ever displayed.
                    when {
                    selectedBook == null -> {
                        val oldBooks = books.filter { it.book <= 39 }
                        val newBooks = books.filter { it.book > 39 }
                        BoxWithConstraints(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                        ) {
                            val showRail = maxWidth >= 820.dp
                            val barAlpha by animateFloatAsState(
                                targetValue = if (rootScroll.value > 64) 1f else 0f,
                                animationSpec = tween(durationMillis = 170),
                                label = "bible-bar-alpha"
                            )
                            val jumpToSection: (String) -> Unit = { id ->
                                rootSectionOffsets[id]?.let { target ->
                                    scope.launch { rootScroll.animateScrollTo(target) }
                                }
                            }

                            Column(modifier = Modifier.fillMaxSize()) {
                                // The bar slot is always reserved so the page
                                // never shifts when the bar fades in or out.
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(BIBLE_BAR_HEIGHT)
                                ) {
                                    if (barAlpha > 0.01f) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .graphicsLayer { alpha = barAlpha }
                                        ) {
                                            BibleSectionBar(
                                                title = "The Bible",
                                                sections = testamentSections,
                                                activeId = rootActiveSectionId,
                                                onJump = jumpToSection
                                            )
                                        }
                                    }
                                }

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .weight(1f)
                                ) {
                                    if (showRail) {
                                        BibleSectionRail(
                                            sections = testamentSections,
                                            activeId = rootActiveSectionId,
                                            onJump = jumpToSection
                                        )
                                    }
                                    Column(
                                        verticalArrangement = Arrangement.spacedBy(6.dp),
                                        modifier = Modifier
                                            .weight(1f)
                                            .verticalScroll(rootScroll)
                                            .padding(horizontal = if (showRail) 6.dp else 0.dp)
                                    ) {
                                        BibleHero(
                                            eyebrow = "SCRIPTURE",
                                            title = "The Bible",
                                            subtitle = "Choose a book to read \u2014 " +
                                                "${books.size} books in this translation."
                                        )
                                        BookSection(
                                            section = testamentSections[0],
                                            books = oldBooks,
                                            onOpen = { book -> openBook(book) },
                                            onToggleStar = { book ->
                                                SoundManager.play(SoundEvent.Click)
                                                SettingsManager.toggleBookStar(book.book)
                                            },
                                            onPlaced = { y -> rootSectionOffsets["ot"] = y }
                                        )
                                        BookSection(
                                            section = testamentSections[1],
                                            books = newBooks,
                                            onOpen = { book -> openBook(book) },
                                            onToggleStar = { book ->
                                                SoundManager.play(SoundEvent.Click)
                                                SettingsManager.toggleBookStar(book.book)
                                            },
                                            onPlaced = { y -> rootSectionOffsets["nt"] = y }
                                        )
                                        Spacer(Modifier.height(24.dp))
                                    }
                                }
                            }
                        }
                    }

                    else -> {
                        // One chapter at a time: only the selected chapter is
                        // displayed. A book without a chapter (defensive —
                        // every navigation sets one) falls back to its first.
                        (selectedChapter ?: selectedBook.chapters.firstOrNull())
                            ?.let { chapter ->
                            val goPrev: () -> Unit = {
                                val next = (chapter.chapter - 1).coerceAtLeast(1)
                                selectedChapterNumber = next
                                selectedVerseNumber = null
                            }
                            val goNext: () -> Unit = {
                                val next = (chapter.chapter + 1).coerceAtMost(
                                    selectedBook.chapters.size
                                )
                                selectedChapterNumber = next
                                selectedVerseNumber = null
                            }
                            val goScrub: (Int) -> Unit = { newChapter ->
                                val next = newChapter.coerceIn(
                                    1,
                                    selectedBook.chapters.size
                                )
                                selectedChapterNumber = next
                                selectedVerseNumber = null
                            }
                            val goBack: () -> Unit = {
                                // Back out to the book index, keeping the exact
                                // verse as the resume point for the next open.
                                SettingsManager.setLastRead(
                                    selectedBook.book,
                                    chapter.chapter,
                                    selectedVerseNumber
                                )
                                selectedBookNumber = null
                                selectedChapterNumber = null
                                selectedVerseNumber = null
                                selectedVerseDetail = null
                                testamentChosen = true
                            }

                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surface
                                ),
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
                                    // Compact (phone) puts the read/back
                                    // controls on the eyebrow line so the
                                    // chapter title keeps its full width; the
                                    // desktop keeps them beside the title.
                                    val chapterIsRead = SettingsManager.isChapterRead(
                                        selectedBook.book,
                                        chapter.chapter
                                    )
                                    val toggleChapterRead: () -> Unit = {
                                        SettingsManager.setChapterRead(
                                            selectedBook.book,
                                            chapter.chapter,
                                            !chapterIsRead
                                        )
                                    }
                                    val backToBooks: () -> Unit = {
                                        SoundManager.play(SoundEvent.Click)
                                        goBack()
                                    }
                                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                                    // Narrow panes (phones) move the read/back
                                    // controls up to the eyebrow line so the
                                    // chapter title keeps its full width.
                                    val narrowHeader = maxWidth < 520.dp
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Column(
                                            verticalArrangement = Arrangement.spacedBy(3.dp),
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Box(Modifier.weight(1f)) {
                                                    BibleEyebrow(selectedBook.name.uppercase())
                                                }
                                                if (narrowHeader) {
                                                    ChapterReadButton(
                                                        read = chapterIsRead,
                                                        onToggle = toggleChapterRead
                                                    )
                                                    ChromeIconButton(
                                                        icon = RibbonIcons.Back,
                                                        description = "Back to the books",
                                                        onClick = backToBooks
                                                    )
                                                }
                                            }
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                SectionNumber("%02d".format(chapter.chapter))
                                                Text(
                                                    text = "Chapter ${chapter.chapter}",
                                                    style = MaterialTheme.typography.headlineSmall,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                    modifier = Modifier.weight(1f, fill = false)
                                                )
                                            }
                                        }
                                        if (!narrowHeader) {
                                            ChapterReadButton(
                                                read = chapterIsRead,
                                                onToggle = toggleChapterRead
                                            )
                                            ChromeIconButton(
                                                icon = RibbonIcons.Back,
                                                description = "Back to the books",
                                                onClick = backToBooks
                                            )
                                        }
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
                                            // Chapter-wide word hover: hovering a
                                            // Hebrew / Greek word anywhere in the
                                            // chapter highlights every occurrence
                                            // of it. Counts are computed once per
                                            // chapter, not per verse.
                                            var hoveredWordNumber by remember(
                                                selectedBook.book,
                                                chapter.chapter,
                                                currentModuleId
                                            ) {
                                                mutableStateOf<String?>(null)
                                            }
                                            val chapterWordCounts = remember(
                                                selectedBook.book,
                                                chapter.chapter,
                                                chapter.verses,
                                                currentModuleId
                                            ) {
                                                countWordStudyNumbers(
                                                    chapter.verses.map { it.text }
                                                )
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
                                                // [BibleReferenceSelection.highlights] covers the
                                                // whole ranged span, so hovering `John 3:16-18`
                                                // tints all three verses (and a chapter-level
                                                // reference tints the chapter).
                                                val hoverHighlighted = hovered != null &&
                                                    BibleRepository.bookNumberFor(hovered.book) == selectedBook.book &&
                                                    hovered.highlights(chapter.chapter, verse.verse)
                                                // The reference the pane was navigated to
                                                // stays tinted until the reader opens
                                                // another book, so the arrival target is
                                                // never just a fleeting scroll.
                                                val referenceHighlighted = activeReference
                                                    ?.takeIf {
                                                        BibleRepository.bookNumberFor(it.book) ==
                                                            selectedBook.book
                                                    }
                                                    ?.highlights(chapter.chapter, verse.verse) == true
                                                VerseRow(
                                                    bookName = selectedBook.name,
                                                    bookNumber = selectedBook.book,
                                                    chapterNumber = chapter.chapter,
                                                    verseNumber = verse.verse,
                                                    text = verse.text,
                                                    // Clicking the verse opens its
                                                    // reference card (mentions,
                                                    // similar texts, Hebrew);
                                                    // clicking it again closes it.
                                                    onClick = {
                                                        SoundManager.play(SoundEvent.Click)
                                                        selectedVerseDetail =
                                                            if (selectedVerseDetail == verse.verse) {
                                                                null
                                                            } else {
                                                                verse.verse
                                                            }
                                                    },
                                                    interlinearGreek = greekVerseByNumber[verse.verse],
                                                    onOpenLexicon = onOpenLexicon,
                                                    interlinearAligned =
                                                        interlinearMode == InterlinearMode.ALIGNED,
                                                    hoverHighlighted =
                                                        hoverHighlighted || referenceHighlighted,
                                                    onExportVerse = { startVerse ->
                                                        val cards = chapter.verses.map { v ->
                                                            CardVerse(
                                                                chapter = chapter.chapter,
                                                                verse = v.verse,
                                                                text = v.text
                                                            )
                                                        }
                                                        val index = cards.indexOfFirst {
                                                            it.verse == startVerse
                                                        }.coerceAtLeast(0)
                                                        exportSeed = VerseExportSeed(
                                                            bookName = selectedBook.name,
                                                            chapter = chapter.chapter,
                                                            verses = cards,
                                                            startIndex = index,
                                                            endIndex = index
                                                        )
                                                    },
                                                    onExportRange = { startVerse ->
                                                        val cards = chapter.verses.map { v ->
                                                            CardVerse(
                                                                chapter = chapter.chapter,
                                                                verse = v.verse,
                                                                text = v.text
                                                            )
                                                        }
                                                        val index = cards.indexOfFirst {
                                                            it.verse == startVerse
                                                        }.coerceAtLeast(0)
                                                        exportSeed = VerseExportSeed(
                                                            bookName = selectedBook.name,
                                                            chapter = chapter.chapter,
                                                            verses = cards,
                                                            startIndex = index,
                                                            endIndex = cards.lastIndex
                                                        )
                                                    },
                                                    wordCountsInChapter = chapterWordCounts,
                                                    hoveredWordNumber = hoveredWordNumber,
                                                    onHoverWord = { hoveredWordNumber = it },
                                                    onOpenNoteTitle = onOpenNoteTitle,
                                                    onVersePositioned = { verseNum, y ->
                                                        // Mutate the raw map and bump a
                                                        // version counter so the parent
                                                        // LaunchedEffect re-fires without
                                                        // allocating a new stateful map on
                                                        // every callback. Keyed by chapter
                                                        // so a stale offset from the
                                                        // previous chapter can never be
                                                        // mistaken for this one's.
                                                        verseYOffsetsMap[
                                                            "${selectedBook.book}:${chapter.chapter}:$verseNum"
                                                        ] = y
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
                                                if (selectedVerseDetail == verse.verse) {
                                                    VerseDetailPanel(
                                                        bookNumber = selectedBook.book,
                                                        bookName = selectedBook.name,
                                                        chapterNumber = chapter.chapter,
                                                        verseNumber = verse.verse,
                                                        verseText = verse.text,
                                                        books = books,
                                                        hebrewBooks = hebrewBooks,
                                                        onOpenNoteTitle = onOpenNoteTitle,
                                                        onOpenReference = { bn, ch, v ->
                                                            selectedVerseDetail = null
                                                            jumpToVerse(bn, ch, v)
                                                        },
                                                        onClose = {
                                                            SoundManager.play(SoundEvent.Click)
                                                            selectedVerseDetail = null
                                                        }
                                                    )
                                                }
                                            }
                                        }

                                    }
                                }
                            }
                        }
                    }
                }

                }
            }
        }

        // Ctrl+G jump-to-verse dialog. Passed the open book (if any) so
        // it pre-fills; the Go action jumps the pane to the target verse.
        // Right-click export dialog (verse or range as postcard / deck).
        exportSeed?.let { seed ->
            VerseExportDialog(
                seed = seed,
                onDismiss = { exportSeed = null }
            )
        }

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
            verse in 1..book.chapters[chapter - 1].verses.size))
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
 * One testament section of the Bible index: its numbered heading followed by
 * every book as one full-width numbered row. Reports its Y offset so the bar
 * / rail can jump here.
 */
@Composable
private fun BookSection(
    section: BibleNavSection,
    books: List<Book>,
    onOpen: (Book) -> Unit,
    onToggleStar: (Book) -> Unit,
    onPlaced: (Int) -> Unit
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .onGloballyPositioned { coords ->
                onPlaced(coords.positionInParent().y.toInt())
            }
    ) {
        BibleSectionHeader(
            number = section.number,
            title = section.title,
            subtitle = "${books.size} books"
        )
        Column(
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            books.forEachIndexed { index, book ->
                BookCard(
                    book = book,
                    index = index + 1,
                    starred = SettingsManager.isBookStarred(book.book),
                    readChapters = SettingsManager.readChapterCount(book.book),
                    onOpen = { onOpen(book) },
                    onToggleStar = { onToggleStar(book) }
                )
            }
        }
    }
}