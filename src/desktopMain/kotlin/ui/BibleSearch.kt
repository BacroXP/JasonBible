package ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import data.BibleRepository
import data.SoundEvent
import data.SoundManager
import data.findMatchIn
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds
import model.Book


// ---------------------------------------------------------------------------
// Full-text Bible search
//
// Ctrl+F in the Bible pane toggles a search bar; typing runs a debounced
// scan of every verse in the currently loaded translation (on a background
// thread, see BibleScreen) and lists the matches grouped by book. Matching
// is case-insensitive by default, with an "Aa" toggle in the bar for
// exact-case matches and a whole-word toggle so "day" doesn't match
// "today". A scope filter (All / This book / This chapter) narrows the
// scan. Queries shaped like a Strong's number (`G25`, `h1`) run a reverse
// concordance lookup instead of a substring scan: they match verses whose
// word-study tokens carry that number. Clicking a match jumps the pane to
// that verse. The pure matching function [searchBible] is kept separate
// from the composables so it can be reused / tested without a UI.
// ---------------------------------------------------------------------------

/** How much of the active Bible the search scans. */
internal enum class BibleSearchScope(
    /** Short label for the compact bar button ("All" / "Book" / "Chapter"). */
    val label: String,
    /** Full label for the dropdown menu. */
    val menuLabel: String
) {
    ALL("All", "Whole Bible"),
    BOOK("Book", "This book"),
    CHAPTER("Chapter", "This chapter")
}

/** One verse in the current translation matching the search query. */
internal data class BibleSearchMatch(
    val book: Book,
    val chapter: Int,
    val verse: Int,
    val text: String
)

/** Cap on how many matches are rendered; the full count is still shown. */
internal const val MAX_BIBLE_SEARCH_RESULTS = 300

// Queries that look like a Strong's number (G#### / H####, case-
// insensitive) trigger the reverse-concordance path instead of substring
// matching: they hit the word-study tokens, so `G25` finds every verse
// whose `word{G25}` / `G25` token carries that lemma (and TVM-code
// searches like `G5656` work too).
private val STRONGS_QUERY = Regex("^[GH]\\d+$", RegexOption.IGNORE_CASE)

// Whole-word matching itself lives in data.findMatchIn (shared with the
// note search) — see data.TextMatch.

/**
 * Scan of every verse in [books], preserving canonical book / chapter /
 * verse order. Case-insensitive by default; pass [matchCase] = true for
 * an exact-case match and [wholeWord] = true for whole-word matches only.
 * A Strong's-shaped query (e.g. `G25`) runs a reverse-concordance lookup
 * over the word-study tokens instead. Returns ALL matches — callers cap
 * the rendered list via [MAX_BIBLE_SEARCH_RESULTS] while keeping the full
 * count for the status line.
 */
internal fun searchBible(
    books: List<Book>,
    query: String,
    matchCase: Boolean = false,
    wholeWord: Boolean = false
): List<BibleSearchMatch> {
    val q = query.trim()
    if (q.isEmpty()) return emptyList()
    // Reverse Strong's lookup: the query is a G/H number, so match the
    // verse's word-study tokens rather than its raw text. On modules
    // without Strong's markup this yields no hits (correct — there is
    // nothing to reverse-look-up).
    val strongsTarget = STRONGS_QUERY.matchEntire(q)?.value?.uppercase()
    val out = ArrayList<BibleSearchMatch>()
    for (book in books) {
        for (chapter in book.chapters) {
            for (verse in chapter.verses) {
                val hits = if (strongsTarget != null) {
                    parseWordStudyTokens(verse.text).any { token ->
                        token.number.equals(strongsTarget, ignoreCase = true) ||
                            token.tvm?.equals(strongsTarget, ignoreCase = true) == true
                    }
                } else {
                    findMatchIn(verse.text, q, 0, matchCase, wholeWord) != -1
                }
                if (hits) {
                    out += BibleSearchMatch(book, chapter.chapter, verse.verse, verse.text)
                }
            }
        }
    }
    return out
}


/**
 * Debounce wrapper for the Bible search bar: waits [debounceMillis]
 * (virtual time in tests) then returns [scan]'s result for the trimmed
 * [query]. A blank query short-circuits immediately without waiting. The
 * debounce itself is what makes rapid typing cheap — the caller's
 * LaunchedEffect re-keys on every keystroke, cancelling this wait, so
 * only the final query ever reaches the scan. Kept pure (no Compose
 * state) so the debounce semantics are directly testable with
 * virtual time.
 */
internal suspend fun debouncedSearch(
    query: String,
    debounceMillis: Long,
    scan: suspend (String) -> List<BibleSearchMatch>
): List<BibleSearchMatch> {
    val trimmed = query.trim()
    if (trimmed.isEmpty()) return emptyList()
    delay(debounceMillis.milliseconds)
    return scan(trimmed)
}


/**
 * Narrow [books] to the part the search scope should scan. The scope's
 * target is read live from the pane's current selection: BOOK keeps only
 * the open book, CHAPTER keeps the open book rebuilt with a single open
 * chapter. When the scope's target isn't available (no book / chapter
 * selected, or the chapter isn't in the book), the slice degrades to the
 * full [books] rather than producing an empty result set.
 *
 * Kept pure (no UI, no state) so the scope logic itself is directly
 * unit-testable — [searchBible] then just scans whatever slice it is
 * given.
 */
internal fun sliceBooksForScope(
    books: List<Book>,
    scope: BibleSearchScope,
    selectedBookNumber: Int?,
    selectedChapterNumber: Int?
): List<Book> = when (scope) {
    BibleSearchScope.ALL -> books
    BibleSearchScope.BOOK -> {
        val bn = selectedBookNumber
        if (bn == null) books else books.filter { it.book == bn }
    }
    BibleSearchScope.CHAPTER -> {
        val bn = selectedBookNumber
        val cn = selectedChapterNumber
        if (bn == null || cn == null) {
            books
        } else {
            books.filter { it.book == bn }.map { book ->
                val chapter = book.chapters.find { it.chapter == cn }
                if (chapter == null) {
                    book
                } else {
                    Book(book.book, book.name, listOf(chapter))
                }
            }
        }
    }
}


/**
 * Slim search bar shown at the top of the Bible pane while search is open:
 * a query field (auto-focused by the caller's [FocusRequester]), a live
 * match count, prev/next match stepping (buttons plus Enter / Shift+Enter),
 * a case-sensitive "Aa" toggle and a close button. Keyboard handling
 * (Ctrl+F / Esc) lives in BibleScreen's pane-level shortcut handler, so
 * this bar only hosts the mouse/touch interactions plus Enter navigation
 * inside the field.
 */
@Composable
internal fun BibleSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    matchCount: Int,
    totalMatches: Int,
    searching: Boolean,
    matchCase: Boolean,
    onToggleMatchCase: () -> Unit,
    wholeWord: Boolean,
    onToggleWholeWord: () -> Unit,
    scope: BibleSearchScope,
    onScopeChange: (BibleSearchScope) -> Unit,
    /**
     * Whether the scope dropdown (All / This book / This chapter) is
     * shown. Hidden on Home, where there is no open book/chapter and
     * BOOK / CHAPTER would silently degrade to scanning the whole Bible.
     */
    showScope: Boolean = true,
    /**
     * Recently used queries (most recent first), shown in a dropdown on
     * the search icon; clicking one re-runs that search via
     * [onQueryChange]. Populated from SettingsManager.
     */
    recentQueries: List<String>,
    activeIndex: Int,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onClose: () -> Unit,
    focusRequester: FocusRequester
) {
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
            // The search icon doubles as the recent-queries menu: click it
            // to re-run one of the last searches (each entry re-fills the
            // field, which re-keys the debounced scan in BibleScreen).
            RecentQueriesMenu(
                recentQueries = recentQueries,
                onSelect = onQueryChange
            )
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = MaterialTheme.colorScheme.onSurface
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                singleLine = true,
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focusRequester)
                    .onPreviewKeyEvent { event ->
                        // Enter / Shift+Enter step through the matches, like
                        // the editor's find bar. Guarded to KeyDown so the
                        // repeated / released keys can't advance twice.
                        if (event.type != KeyEventType.KeyDown) {
                            false
                        } else if (event.key == Key.Enter) {
                            if (event.isShiftPressed) onPrev() else onNext()
                            true
                        } else {
                            false
                        }
                    },
                decorationBox = { inner ->
                    if (query.isEmpty()) {
                        Text(
                            "Search the whole Bible…",
                            style = MaterialTheme.typography.bodyMedium.copy(
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                    .copy(alpha = 0.5f)
                            )
                        )
                    }
                    inner()
                }
            )
            if (query.isNotBlank()) {
                Text(
                    text = when {
                        searching -> "…"
                        totalMatches == 0 -> "No matches"
                        // An active match shows its position, e.g. "5/300+"
                        // when the result list is capped.
                        activeIndex >= 0 -> "${activeIndex + 1}/$matchCount" +
                            if (totalMatches > matchCount) "+" else ""

                        totalMatches > matchCount -> "$totalMatches+"
                        else -> totalMatches.toString()
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            // Prev/next match stepping — the same affordance as the
            // editor's find bar (the click sound plays via
            // ToolbarActionButton).
            ToolbarActionButton(
                icon = RibbonIcons.PrevMatch,
                enabled = matchCount > 0,
                tooltip = "Previous match",
                onClick = onPrev
            )
            ToolbarActionButton(
                icon = RibbonIcons.NextMatch,
                enabled = matchCount > 0,
                tooltip = "Next match",
                onClick = onNext
            )
            // Case-sensitive match toggle — same "Aa" affordance as the
            // editor's find bar (label flips to "Aa·on" with the accent
            // highlight while active). Toggling re-runs the debounced
            // search via BibleScreen's re-keyed LaunchedEffect.
            ToolbarActionButton(
                label = if (matchCase) "Aa\u00B7on" else "Aa",
                accent = matchCase,
                tooltip = "Match case",
                onClick = onToggleMatchCase
            )
            // Whole-word toggle: "day" matches "a day of" but not
            // "today". Same accent-while-active treatment as Aa.
            ToolbarActionButton(
                label = if (wholeWord) "abc\u00B7on" else "abc",
                accent = wholeWord,
                tooltip = "Whole word only",
                onClick = onToggleWholeWord
            )
            // Scope filter: scan the whole Bible, just the open book, or
            // just the open chapter. A compact dropdown keeps the bar
            // from sprawling. Hidden on Home (no open book/chapter).
            if (showScope) {
                SearchScopeDropdown(
                    scope = scope,
                    onScopeChange = onScopeChange
                )
            }
            Text(
                text = "✕",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .hoverable(remember { MutableInteractionSource() })
                    .clickable {
                        SoundManager.play(SoundEvent.Click)
                        onClose()
                    }
                    .padding(horizontal = 4.dp)
            )
        }
    }
}


/**
 * Scrollable result list replacing the reading view while search is open.
 * Matches are grouped by book (in canonical book order); each row shows the
 * reference plus the verse text with every occurrence of the query
 * highlighted. The active match ([activeIndex], driven by the bar's prev /
 * next stepping) is highlighted and auto-scrolled into view. Clicking a row
 * opens that verse.
 *
 * Interlinear + word study are threaded through so result verses match the
 * reading views: when [interlinearOn] each match renders the same LINE /
 * word-aligned Greek TR line as [VerseRow] (via the shared
 * [VerseInterlinear]), and clicking a Strong's number in either language
 * opens the shared [WordStudyPanel].
 */
@Composable
internal fun BibleSearchResults(
    query: String,
    results: List<BibleSearchMatch>,
    total: Int,
    searching: Boolean,
    matchCase: Boolean,
    wholeWord: Boolean,
    activeIndex: Int,
    onOpen: (BibleSearchMatch) -> Unit,
    // Interlinear state, forwarded verbatim from the reading pane so the
    // results match the views: the mode is ON for LINE / ALIGNED, and the
    // Greek module (loaded lazily by the pane) supplies the TR text.
    interlinearOn: Boolean = false,
    interlinearAligned: Boolean = false,
    greekBooks: List<Book>? = null,
    // Word study, same wiring as VerseRow: whether the caller owns the
    // word-study state (the reading pane does; Home does not), the token
    // whose panel is open (if it belongs to this match) and the toggle
    // callback. When the caller has no word-study wiring, Strong's-marked
    // verses keep the plain query-highlighted text instead of rendering
    // clickable-looking numbers that would do nothing.
    wordStudyEnabled: Boolean = false,
    strongsLoaded: Boolean = false,
    activeStudyWord: StudyWordToken? = null,
    onToggleStudyWord: (StudyWordToken) -> Unit = {},
    onOpenLexicon: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()
    // Content-Y of each rendered row (indexed like [results]) so stepping
    // to an off-screen match can scroll it into view. Read only by the
    // LaunchedEffect below — the map never triggers recomposition, so the
    // position callbacks stay cheap.
    val rowYs = remember { mutableMapOf<Int, Int>() }

    // Scroll the active match into view when it changes. The rows compose
    // eagerly inside the scrolling Column, so the offset is recorded by the
    // time the effect runs; the short delay lets the new frame lay out
    // first (same pattern as BibleScreen's scroll-to-verse). A ~24 dp top
    // margin keeps the book-group header (and a bit of context) above the
    // active row visible — same cue as the editor's find bar.
    LaunchedEffect(activeIndex) {
        if (activeIndex >= 0) {
            delay(50.milliseconds)
            rowYs[activeIndex]?.let { y ->
                scrollState.animateScrollTo((y - 24).coerceAtLeast(0))
            }
        }
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        // Header line: the match-count caption (when the list is capped)
        // plus a "Greek (TR)" pill making the interlinear mode discoverable
        // right in the results — the Greek line under NT matches is the
        // Textus Receptus, same as the reading views. The pill is shown
        // only when the mode is on AND at least one result is from a
        // New-Testament book: trparsed is NT-only, so an OT-only result
        // set renders no Greek lines and needs no caption.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            if (total > results.size) {
                Text(
                    text = "Showing ${results.size} of $total matches",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
            } else {
                // No count caption (uncapped list) — an empty weight slot
                // keeps the pill pinned to the right edge in both layouts.
                Spacer(modifier = Modifier.weight(1f))
            }
            if (interlinearOn && results.any { it.book.book > 39 }) {
                // Non-interactive caption pill, matching the reading view's
                // InterlinearToggle visual language (primary container)
                // without the ΑΩ glyph — the label spells the module out.
                Surface(
                    shape = PillShape,
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                ) {
                    Text(
                        text = "Greek (TR)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            when {
                searching && results.isEmpty() -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Searching…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                results.isEmpty() -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (query.isBlank()) {
                                "Type to search across the whole Bible."
                            } else {
                                "No matches for \"${query.trim()}\"."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                else -> {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(scrollState)
                    ) {
                        // groupBy keeps first-encounter order, so the
                        // books stay in canonical order.
                        var rowIndex = 0
                        results.groupBy { it.book }.forEach { (book, matches) ->
                            Text(
                                text = "${book.name}  ·  ${matches.size} " +
                                    if (matches.size == 1) "match" else "matches",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 6.dp)
                            )
                            matches.forEach { match ->
                                BibleSearchMatchRow(
                                    match = match,
                                    query = query,
                                    matchCase = matchCase,
                                    wholeWord = wholeWord,
                                    active = rowIndex == activeIndex,
                                    interlinearOn = interlinearOn,
                                    interlinearAligned = interlinearAligned,
                                    greekBooks = greekBooks,
                                    wordStudyEnabled = wordStudyEnabled,
                                    strongsLoaded = strongsLoaded,
                                    activeStudyWord = activeStudyWord,
                                    onToggleStudyWord = onToggleStudyWord,
                                    onOpenLexicon = onOpenLexicon,
                                    onGloballyPositioned = { y -> rowYs[rowIndex] = y },
                                    onClick = { onOpen(match) }
                                )
                                rowIndex++
                            }
                        }
                    }
                    VerticalScrollbar(
                        adapter = rememberScrollbarAdapter(scrollState),
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .fillMaxHeight()
                    )
                }
            }
        }
    }
}


@Composable
private fun BibleSearchMatchRow(
    match: BibleSearchMatch,
    query: String,
    matchCase: Boolean,
    wholeWord: Boolean,
    active: Boolean,
    // Interlinear + word study, same wiring as VerseRow so a search-result
    // verse renders exactly like the reading views.
    interlinearOn: Boolean,
    interlinearAligned: Boolean,
    greekBooks: List<Book>?,
    wordStudyEnabled: Boolean,
    strongsLoaded: Boolean,
    activeStudyWord: StudyWordToken?,
    onToggleStudyWord: (StudyWordToken) -> Unit,
    onOpenLexicon: (String) -> Unit = {},
    onGloballyPositioned: (Int) -> Unit,
    onClick: () -> Unit
) {
    // The word currently being studied, if it lives in this match's verse.
    val activeForThisVerse = activeStudyWord?.takeIf {
        it.bookNumber == match.book.book &&
            it.chapter == match.chapter &&
            it.verse == match.verse
    }
    // Word-study tokens of this match's verse, when it carries Strong's
    // markup. Their presence switches the English side to clickable
    // StrongsVerseText (same as the reading views' VerseRow) so word
    // study works on the English text too; verses without markup keep
    // the plain query-highlighted rendering.
    val tokens = remember(match.text) { parseWordStudyTokens(match.text) }

    // Shared word-study toggle for the English text and the Greek line:
    // reports which token's panel should open (tagged with this match's
    // reference, like VerseRow's wrapper).
    val toggleStudyWord: (StrongsToken) -> Unit = { token ->
        onToggleStudyWord(
            StudyWordToken(
                bookNumber = match.book.book,
                chapter = match.chapter,
                verse = match.verse,
                word = token.word,
                number = token.number,
                parsing = token.parsing
            )
        )
    }

    val hoverSource = remember { MutableInteractionSource() }
    val isHovered by hoverSource.collectIsHoveredAsState()
    LaunchedEffect(isHovered) {
        if (isHovered) {
            delay(60.milliseconds)
            SoundManager.play(SoundEvent.Hover)
        }
    }
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            // Report the row's Y inside the scrolling results Column so the
            // parent can scroll the active match into view (same
            // parentLayoutCoordinates.localPositionOf pattern as VerseRow).
            .onGloballyPositioned { coords ->
                val localY = coords.parentLayoutCoordinates
                    ?.localPositionOf(coords, Offset.Zero)
                    ?.y
                    ?.toInt()
                    ?: 0
                onGloballyPositioned(localY)
            }
    ) {
        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(
                containerColor = when {
                    active -> MaterialTheme.colorScheme.primaryContainer
                    isHovered -> MaterialTheme.colorScheme.surfaceVariant
                    else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                }
            ),
            // The active match gets a primary outline so it stays visible
            // even on the highlighted container.
            border = if (active) {
                BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
            } else {
                null
            },
            modifier = Modifier
                .fillMaxWidth()
                .hoverable(hoverSource)
                .clickable {
                    SoundManager.play(SoundEvent.Click)
                    onClick()
                }
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.padding(12.dp)
            ) {
                Text(
                    text = "${match.book.name} ${match.chapter}:${match.verse}",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (active) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.primary
                    }
                )
                // English side of the match. When the verse carries
                // Strong's markup AND the caller has word-study wiring
                // (wordStudyEnabled) it renders as clickable word-study
                // tokens (a StrongsVerseText link consumes the click, so
                // tapping a G/H number opens the panel while taps on the
                // surrounding text still open the verse); otherwise the
                // plain text with the query highlighted is shown.
                if (tokens.isEmpty() || !wordStudyEnabled) {
                    Text(
                        text = highlightQuery(match.text, query, matchCase, wholeWord),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (active) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            Color.Unspecified
                        },
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                } else {
                    StrongsVerseText(
                        text = match.text,
                        tokens = tokens,
                        activeNumber = activeForThisVerse?.number,
                        onToggle = toggleStudyWord,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                // Interlinear: the same Greek TR line / word-aligned block
                // as the reading views (shared [VerseInterlinear]), looked
                // up per match from the Greek module. Only the match's own
                // verse is resolved, so OT matches (trparsed is NT-only)
                // and verses missing from the module simply render without
                // a line — same as the chapter view.
                if (interlinearOn) {
                    // Keyed on the primitive reference (not `match`, whose
                    // `book` is the whole Book — structural equality would
                    // compare every verse on each recomposition) plus the
                    // lazily-loaded module, so rows repaint when it arrives.
                    val greekForMatch = remember(
                        match.book.book,
                        match.chapter,
                        match.verse,
                        greekBooks,
                        interlinearOn
                    ) {
                        greekBooks?.let { greek ->
                            BibleRepository.greekVersesForChapter(
                                greek,
                                match.book.book,
                                match.chapter
                            )[match.verse]
                        }
                    }
                    greekForMatch?.let { greekText ->
                        VerseInterlinear(
                            englishText = match.text,
                            greekText = greekText,
                            aligned = interlinearAligned,
                            activeNumber = activeForThisVerse?.number,
                            onToggleStudyWord = toggleStudyWord
                        )
                    }
                }
            }
        }

        // Open word-study panel BELOW the card (like VerseRow), so closing
        // it never triggers the row's open-verse click.
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


/**
 * Builds an AnnotatedString with every occurrence of [query] in [text]
 * styled as bold on a soft yellow background — the same visual language as
 * the verse marker colors. Matching is case-insensitive unless [matchCase]
 * is set and honouring the whole-word flag, mirroring the search's own
 * matching ([findMatchIn]). Shared with the global Ctrl+F search
 * ([GlobalSearchOverlay]) so previews highlight identically.
 */
internal fun highlightQuery(
    text: String,
    query: String,
    matchCase: Boolean,
    wholeWord: Boolean
): AnnotatedString {
    val q = query.trim()
    if (q.isEmpty()) return AnnotatedString(text)
    return buildAnnotatedString {
        var from = 0
        while (from < text.length) {
            val index = findMatchIn(text, q, from, matchCase, wholeWord)
            if (index == -1) {
                append(text.substring(from))
                break
            }
            append(text.substring(from, index))
            withStyle(
                SpanStyle(
                    fontWeight = FontWeight.Bold,
                    background = Color(0xFFFFF176).copy(alpha = 0.55f)
                )
            ) {
                append(text.substring(index, index + q.length))
            }
            from = index + q.length
        }
    }
}


/**
 * Recent-queries dropdown anchored on the search icon. Shows the last
 * few searches (most recent first, capped upstream at 10); clicking one
 * re-fills the query field. A disabled "No recent queries" entry is
 * shown when the list is empty so the menu never appears broken.
 * Shared by the Bible search bar and the global Ctrl+F search overlay.
 */
@Composable
internal fun RecentQueriesMenu(
    recentQueries: List<String>,
    onSelect: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        // ToolbarTip (the ribbon's hover-tooltip helper) makes the icon's
        // new menu role discoverable.
        ToolbarTip(label = "Recent queries") {
            Icon(
                imageVector = RibbonIcons.Find,
                contentDescription = "Recent queries",
                modifier = Modifier
                    .size(20.dp)
                    .hoverable(remember { MutableInteractionSource() })
                    .clickable {
                        SoundManager.play(SoundEvent.Click)
                        expanded = true
                    }
                    .padding(horizontal = 2.dp)
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            if (recentQueries.isEmpty()) {
                DropdownMenuItem(
                    text = {
                        Text(
                            text = "No recent queries",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    enabled = false,
                    onClick = {}
                )
            } else {
                recentQueries.forEach { query ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = query,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.widthIn(max = 240.dp)
                            )
                        },
                        onClick = {
                            expanded = false
                            SoundManager.play(SoundEvent.Click)
                            onSelect(query)
                        }
                    )
                }
            }
        }
    }
}


/**
 * Compact scope picker for the search bar: a label button ("All" /
 * "This book" / "This chapter") that opens a dropdown of the three
 * [BibleSearchScope]s. The currently active scope is marked bold-primary.
 */
@Composable
private fun SearchScopeDropdown(
    scope: BibleSearchScope,
    onScopeChange: (BibleSearchScope) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Surface(
            shape = RoundedCornerShape(6.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier
                .height(28.dp)
                .clickable {
                    SoundManager.play(SoundEvent.Click)
                    expanded = true
                }
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.padding(horizontal = 8.dp)
            ) {
                Text(
                    text = scope.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface
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
            BibleSearchScope.entries.forEach { option ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = option.menuLabel,
                            fontWeight = if (option == scope) {
                                FontWeight.Bold
                            } else {
                                FontWeight.Normal
                            },
                            color = if (option == scope) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            }
                        )
                    },
                    onClick = {
                        expanded = false
                        SoundManager.play(SoundEvent.Click)
                        onScopeChange(option)
                    }
                )
            }
        }
    }
}
