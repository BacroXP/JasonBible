package ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import data.BibleRepository
import data.SettingsManager
import data.SoundEvent
import data.SoundManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds
import model.Book


/**
 * Owns the full-text Bible search state shared by Home and the Bible pane
 * so the two screens can't drift: the query / Aa / whole-word / scope /
 * result set, the debounced background scan, prev-next match stepping,
 * focus management, the Ctrl+F / Esc shortcuts and session persistence
 * (SettingsManager). Created by [rememberBibleSearchState], which also
 * runs the scan and focus effects.
 *
 * [scopeTargets] supplies the current book/chapter selection, read LIVE
 * at scan time so a scoped search honours navigation that happens while
 * the bar is open; screens without an open book/chapter (Home) pass
 * null/null, which degrades BOOK / CHAPTER scopes to the whole Bible.
 */
internal class BibleSearchState(
    private val scopeTargets: () -> Pair<Int?, Int?>
) {
    var open by mutableStateOf(false)
        private set
    // The query and the Aa / whole-word toggles seed from (and write back
    // to) SettingsManager, so reopening the search bar (or restarting the
    // app) resumes the last session exactly.
    var query by mutableStateOf(SettingsManager.bibleSearchQuery)
        private set
    var matchCase by mutableStateOf(SettingsManager.bibleSearchMatchCase)
        private set
    var wholeWord by mutableStateOf(SettingsManager.bibleSearchWholeWord)
        private set
    // Session-only scope — always defaults back to All so the user is
    // never surprised by a stale scope.
    var scope by mutableStateOf(BibleSearchScope.ALL)
        private set
    // Index of the active (highlighted) match within the rendered
    // results, driven by the bar's prev/next stepping; -1 = none selected.
    var activeIndex by mutableStateOf(-1)
        private set
    var results by mutableStateOf<List<BibleSearchMatch>>(emptyList())
        private set
    var total by mutableStateOf(0)
        private set
    var searching by mutableStateOf(false)
        private set
    val focusRequester = FocusRequester()
    // Guards the Ctrl+F toggle against OS key auto-repeat (which arrives
    // as repeated KeyDown events) so holding the combo doesn't flicker
    // the bar open and closed.
    private var lastToggleAt by mutableStateOf(0L)

    fun updateQuery(value: String) {
        query = value
    }

    fun updateScope(value: BibleSearchScope) {
        scope = value
    }

    fun toggleMatchCase() {
        matchCase = !matchCase
        SettingsManager.bibleSearchMatchCase = matchCase
    }

    fun toggleWholeWord() {
        wholeWord = !wholeWord
        SettingsManager.bibleSearchWholeWord = wholeWord
    }

    /** Open the bar with a click sound, matching the toggle shortcut. */
    fun openSearch() {
        SoundManager.play(SoundEvent.Click)
        open = true
    }

    /**
     * Close the search bar and persist the session query so reopening it
     * (or restarting the app) resumes where the user left off. Persisting
     * only on close keeps per-keystroke disk writes out of the hot path.
     */
    fun close() {
        SettingsManager.bibleSearchQuery = query
        SettingsManager.addBibleSearchRecent(query)
        open = false
    }

    /** Step to the previous rendered match (wraps around the list). */
    fun prev() {
        val count = results.size
        if (count > 0) {
            val base = if (activeIndex < 0) count - 1 else activeIndex
            activeIndex = (base - 1 + count) % count
        }
    }

    /** Step to the next rendered match (wraps around the list). */
    fun next() {
        val count = results.size
        if (count > 0) {
            activeIndex = if (activeIndex < 0) 0 else (activeIndex + 1) % count
        }
    }

    /**
     * Ctrl+F toggles the search bar; while it is open, Esc closes it and
     * Ctrl+F closes it too (honouring the 250 ms auto-repeat guard, so
     * holding the combo can't flicker the bar). Returns true when the
     * event was consumed — callers then skip their own shortcuts, so
     * typing in the query field never flips views.
     */
    fun handleKeyEvent(event: KeyEvent): Boolean {
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
        if (event.isCtrlPressed && event.key == Key.F) {
            val now = System.currentTimeMillis()
            if (now - lastToggleAt > 250) {
                lastToggleAt = now
                openSearch()
            }
            return true
        }
        return false
    }

    /**
     * Debounced full-text scan of [books], re-run by the shared
     * LaunchedEffect whenever the query, either matching toggle, the
     * scope, the loaded module or the open state changes. The scan runs
     * on [Dispatchers.Default] so a large translation (~31k verses) never
     * stalls composition; the LaunchedEffect re-keying guarantees a stale
     * scan's result can't land after a newer query started (the previous
     * coroutine is cancelled on re-key, so the assignment after
     * withContext never runs for an outdated query). The scope's
     * book/chapter targets are read LIVE at scan time via [scopeTargets]
     * (like the query), so navigating while the bar is open still honours
     * the current selection.
     */
    suspend fun scan(books: List<Book>) {
        // Any change that re-runs the search (new query, toggle, scope,
        // new module) invalidates the previously active match.
        activeIndex = -1
        if (!open) {
            results = emptyList()
            total = 0
            searching = false
            return
        }
        if (query.trim().isEmpty()) {
            results = emptyList()
            total = 0
            searching = false
            return
        }
        // The module may still be parsing on the first Ctrl+F of a cold
        // translation — show the searching state instead of a wrong
        // "no matches" (the effect re-runs once the books arrive). If
        // there is NO module at all, don't spin forever: fall through to
        // an empty result set like the rest of the app's no-Bible state.
        if (books.isEmpty()) {
            results = emptyList()
            total = 0
            searching = BibleRepository.currentModuleId() != null
            return
        }
        searching = true
        // Debounce then scan. [debouncedSearch] waits a cancellable 200 ms
        // before running the scan lambda — the LaunchedEffect re-keying on
        // every keystroke cancels the pending wait, so a burst of typing
        // collapses into one scan of the final query.
        val found = withContext(Dispatchers.Default) {
            debouncedSearch(query, debounceMillis = 200) { live ->
                val (bn, cn) = scopeTargets()
                val scanBooks = sliceBooksForScope(
                    books = books,
                    scope = scope,
                    selectedBookNumber = bn,
                    selectedChapterNumber = cn
                )
                searchBible(scanBooks, live, matchCase, wholeWord)
            }
        }
        results = found.take(MAX_BIBLE_SEARCH_RESULTS)
        total = found.size
        searching = false
    }
}


/**
 * Creates the shared [BibleSearchState] for a screen and runs its two
 * effects: the debounced scan (re-keyed on the query / toggles / scope /
 * open state / loaded module) and the auto-focus on open. [scopeTargets]
 * supplies the current book/chapter for the scope slicing, read live at
 * scan time; screens without an open book/chapter (Home) use the default
 * null/null, which degrades BOOK / CHAPTER scopes to the whole Bible.
 */
@Composable
internal fun rememberBibleSearchState(
    books: List<Book>,
    scopeTargets: () -> Pair<Int?, Int?> = { null to null }
): BibleSearchState {
    val state = remember { BibleSearchState(scopeTargets) }

    LaunchedEffect(
        state.open,
        state.query,
        state.matchCase,
        state.wholeWord,
        state.scope,
        books
    ) {
        state.scan(books)
    }

    // Grab focus for the search field as soon as the bar appears. The
    // field is composed in the same frame the bar is shown; the short
    // delay lets it attach to the composition before requesting focus.
    LaunchedEffect(state.open) {
        if (state.open) {
            delay(50.milliseconds)
            state.focusRequester.requestFocus()
        }
    }

    return state
}


/**
 * The search bar wired to [state]: query input, live match count, prev /
 * next stepping, the Aa / whole-word toggles (persisted via the state),
 * the scope picker and close all bind straight to the shared state, so
 * callers can never mis-wire them. [showScope] hides the scope dropdown
 * where there is no open book/chapter (Home).
 */
@Composable
internal fun BibleSearchBarFor(
    state: BibleSearchState,
    showScope: Boolean = true
) {
    BibleSearchBar(
        query = state.query,
        onQueryChange = state::updateQuery,
        matchCount = state.results.size,
        totalMatches = state.total,
        searching = state.searching,
        matchCase = state.matchCase,
        onToggleMatchCase = state::toggleMatchCase,
        wholeWord = state.wholeWord,
        onToggleWholeWord = state::toggleWholeWord,
        scope = state.scope,
        onScopeChange = state::updateScope,
        showScope = showScope,
        recentQueries = SettingsManager.bibleSearchRecents,
        activeIndex = state.activeIndex,
        onPrev = state::prev,
        onNext = state::next,
        onClose = state::close,
        focusRequester = state.focusRequester
    )
}


/**
 * The search result list wired to [state]: query, result set, live count,
 * searching state, match toggles and the active-match index all come
 * straight from the shared state. Interlinear + word study stay
 * caller-owned: the Bible pane threads its state through (so result
 * verses match the reading views), Home passes nothing.
 */
@Composable
internal fun BibleSearchResultsFor(
    state: BibleSearchState,
    onOpen: (BibleSearchMatch) -> Unit,
    modifier: Modifier = Modifier,
    interlinearOn: Boolean = false,
    interlinearAligned: Boolean = false,
    greekBooks: List<Book>? = null,
    wordStudyEnabled: Boolean = false,
    strongsLoaded: Boolean = false,
    activeStudyWord: StudyWordToken? = null,
    onToggleStudyWord: (StudyWordToken) -> Unit = {},
    onOpenLexicon: (String) -> Unit = {}
) {
    BibleSearchResults(
        query = state.query,
        results = state.results,
        total = state.total,
        searching = state.searching,
        matchCase = state.matchCase,
        wholeWord = state.wholeWord,
        activeIndex = state.activeIndex,
        onOpen = onOpen,
        interlinearOn = interlinearOn,
        interlinearAligned = interlinearAligned,
        greekBooks = greekBooks,
        wordStudyEnabled = wordStudyEnabled,
        strongsLoaded = strongsLoaded,
        activeStudyWord = activeStudyWord,
        onToggleStudyWord = onToggleStudyWord,
        onOpenLexicon = onOpenLexicon,
        modifier = modifier
    )
}
