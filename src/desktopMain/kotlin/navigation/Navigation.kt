package navigation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import data.NotesRepository
import data.SettingsManager
import data.SoundEvent
import data.SoundManager
import ui.*


enum class Screen {
    HOME,
    BIBLE,
    NOTES,
    SPLIT,
    SETTINGS,
    STATISTICS,
    LEXICON,
    COLLECTIONS,
    COMPARE
}


@Composable
fun Navigation(
    quit: () -> Unit,
    /**
     * Lets Navigation install its app-level key handler on the WINDOW
     * (Main.kt's `Window(onPreviewKeyEvent = ...)`). Window-level is the
     * only place that sees keys with NO node focused — see Main.kt. The
     * handler is registered once; it reads the live `screen` / search
     * state at event time.
     */
    registerWindowKeyHandler: ((KeyEvent) -> Boolean) -> Unit = {}
) {
    var screen by remember {
        mutableStateOf(Screen.HOME)
    }
    var selectedBibleReference by remember {
        mutableStateOf<BibleReferenceSelection?>(null)
    }
    // The note currently open in the editor pane. Set either by
    // `openNoteByTitle` (a Bible-pane NoteChip click) or by NotesScreen's
    // `onSelectedNoteChange` callback (sidebar pick / create / delete),
    // so a reference-chip click in the editor can flip to SPLIT (Bible +
    // notes side by side) instead of replacing the editor with a
    // full-screen Bible.
    var selectedNoteFileName by remember {
        mutableStateOf<String?>(null)
    }
    // When a NoteChip in the Bible pane is clicked, remember which verse
    // it referenced so the notes editor (opened as a side-effect of the
    // same click) can animate-scroll to the first `$Book$C$V$` line
    // that mentions that verse. NotesScreen consumes this via
    // `pendingScrollReference` + `onScrollReferenceConsumed`; we also
    // clear it on goHome() so it doesn't outlive the navigation.
    // Only set for the Bible → notes direction: when the user clicks a
    // reference chip IN the editor, they are already standing on that
    // line, so openBible() needs no scroll target.
    var pendingNoteScrollTarget by remember {
        mutableStateOf<BibleReferenceSelection?>(null)
    }
    // External "open note at this line" target from the global Ctrl+F
    // search: the 0-based line within the note to scroll to. Forwarded to
    // NotesScreen, which arms its internal scroll pipeline and calls
    // [onScrollLineConsumed] (below) to clear it.
    var pendingScrollLine by remember {
        mutableStateOf<Int?>(null)
    }
    // Pre-filled filter for the Settings screen: the global search's
    // thresholds hint opens Settings with the search filter set to
    // "threshold" so it lands directly on the two sliders. Cleared on
    // every leave/re-enter of Settings so a later visit is unfiltered.
    var settingsSearchPrefill by remember {
        mutableStateOf<String?>(null)
    }
    // Strong's number the Word Study screen should open on (set by the
    // "Open in Word Study" links in the Bible pane's word-study panels;
    // consumed and cleared by LexiconScreen on first composition).
    var lexiconInitialNumber by remember {
        mutableStateOf<String?>(null)
    }
    // Verse the compare screen should open on (set by the Bible pane's
    // "⇄ Compare" action; read once by VerseCompareScreen).
    var compareReference by remember {
        mutableStateOf<BibleReferenceSelection?>(null)
    }

    // The global Ctrl+F search: one overlay on top of every screen (except
    // Settings, which filters its own rows). State lives at the Navigation
    // level so it survives screen switches.
    val appSearch = rememberAppSearchState()

    // App-level shortcuts (Ctrl+F toggles the global search, Esc closes it
    // while open) run at the WINDOW level, NOT on a modifier: a modifier
    // only fires while some node inside it is focused, so with nothing
    // focused (whole-chapter view, chapter list, main menu, editor before
    // a click) Ctrl+F would be dead. The window handler fires for every
    // key in the main window regardless of focus; the Settings screen is
    // the one exception — its own Ctrl+F row filter must win, so `enabled
    // = false` lets the key pass through into the composition.
    LaunchedEffect(Unit) {
        registerWindowKeyHandler { event ->
            appSearch.handleKeyEvent(
                event = event,
                enabled = screen != Screen.SETTINGS
            )
        }
    }

    // Safety net: ANY screen change WHILE THE SEARCH IS OPEN (buttons or
    // keyboard-driven navigation like the editor's Ctrl+W) closes it, so
    // the overlay can never outlive the screen it was opened on. Result
    // clicks already close it before navigating, so this is a no-op there.
    // The `open` guard matters: close() persists the session query, and
    // this effect also fires on the initial composition — without the
    // guard it would overwrite the restored query with "" at startup.
    LaunchedEffect(screen) {
        if (appSearch.open) {
            appSearch.close()
        }
    }

    /**
     * Open a Bible reference. The SPLIT decision is based on the ACTIVE
     * screen, not on which panes happen to hold stale state: when the
     * editor pane is the active context (a reference chip clicked in the
     * editor, or a Bible search result clicked from the editor) both panes
     * open side by side so the note stays visible; anywhere else the
     * Bible opens full-screen.
     */
    fun openBible(reference: BibleReferenceSelection) {
        selectedBibleReference = reference
        screen = if (screen == Screen.NOTES || screen == Screen.SPLIT) {
            Screen.SPLIT
        } else {
            Screen.BIBLE
        }
    }

    /**
     * Open a note (by title, optionally scrolled to a line or verse). The
     * SPLIT decision mirrors [openBible]: when the Bible pane is the
     * active context (a NoteChip in the Bible pane, or a note search
     * result clicked from the Bible / split view) both panes open side by
     * side so the verse stays visible; anywhere else the note opens
     * full-screen.
     */
    fun openNoteByTitle(
        title: String,
        reference: BibleReferenceSelection? = null,
        scrollLine: Int? = null
    ) {
        selectedNoteFileName = NotesRepository.findByTitle(title)?.fileName
        if (reference != null) {
            selectedBibleReference = reference
            pendingNoteScrollTarget = reference
        }
        pendingScrollLine = scrollLine
        screen = if (screen == Screen.BIBLE || screen == Screen.SPLIT || reference != null) {
            Screen.SPLIT
        } else {
            Screen.NOTES
        }
    }

    fun goHome() {
        screen = Screen.HOME
        selectedNoteFileName = null
        selectedBibleReference = null
        pendingNoteScrollTarget = null
        pendingScrollLine = null
        appSearch.close()
    }


    // The whole app sits inside one Box so the global Ctrl+F search can
    // float above every screen. The Ctrl+F / Esc shortcut handling lives
    // at the WINDOW level (registered above) because a modifier here would
    // only fire while some node inside the Box is focused — with nothing
    // focused the shortcut would go dead. While the overlay is open, Esc /
    // Ctrl+F close it; other keys pass through so typing in the query
    // field still reaches it.
    Box(modifier = Modifier.fillMaxSize()) {
        when (screen) {
                Screen.HOME ->
                    HomeScreen(
                    openBible = {
                        // A plain "Bible" open resumes the last-read position.
                        // Drop any stale daily-verse / note-chip reference left
                        // from a previous visit (BIBLE's back button doesn't
                        // clear it) so it can't redirect the pane.
                        selectedBibleReference = null
                        screen = Screen.BIBLE
                    },
                    openNotes = {
                        screen = Screen.NOTES
                    },
                    openStatistics = {
                        screen = Screen.STATISTICS
                    },
                    openLexicon = {
                        lexiconInitialNumber = null
                        screen = Screen.LEXICON
                    },
                    openCollections = {
                        screen = Screen.COLLECTIONS
                    },
                    openSettings = {
                        settingsSearchPrefill = null
                        screen = Screen.SETTINGS
                    },
                    openQuit = {
                        quit()
                    },
                    // The Daily Verse card's "Read in Bible" link jumps the
                    // Bible pane straight to the day's verse.
                    onOpenVerse = { reference ->
                        openBible(reference)
                    }
                )
    
    
            Screen.BIBLE ->
                BibleScreen(
                    back = {
                        screen = Screen.HOME
                    },
                    initialReference = selectedBibleReference,
                    showBackButton = true,
                    compact = false,
                    onOpenNoteTitle = { title, reference ->
                        openNoteByTitle(title, reference)
                    },
                    // Dialogs (separate windows) forward Ctrl+F here: dismiss
                    // themselves and open the global search overlay.
                    onOpenGlobalSearch = { appSearch.openSearch() },
                    onOpenLexicon = { number ->
                        lexiconInitialNumber = number
                        screen = Screen.LEXICON
                    },
                    onOpenCompare = { bookName, cn, vn ->
                        compareReference = BibleReferenceSelection(bookName, cn, vn)
                        screen = Screen.COMPARE
                    }
                )
    
    
            Screen.NOTES ->
                NotesScreen(
                    back = {
                        screen = Screen.HOME
                    },
                    selectedFileName = selectedNoteFileName,
                    showBackButton = true,
                    compact = false,
                    pendingScrollReference = pendingNoteScrollTarget,
                    onScrollReferenceConsumed = { pendingNoteScrollTarget = null },
                    pendingScrollLine = pendingScrollLine,
                    onScrollLineConsumed = { pendingScrollLine = null },
                    onOpenBibleReference = { book, chapter, verse ->
                        openBible(BibleReferenceSelection(book, chapter, verse))
                    },
                    onSelectedNoteChange = { selectedNoteFileName = it },
                    // Dialogs (separate windows) forward Ctrl+F here.
                    onOpenGlobalSearch = { appSearch.openSearch() }
                )
    
    
            Screen.SPLIT -> {
                // Cross-screen hover-highlight: when the user hovers a Bible
                // reference in the notes editor, the corresponding verse in the
                // adjacent bible column gets a soft background tint. State stays
                // scoped to the SPLIT branch so it doesn't pollute the standalone
                // BIBLE / NOTES screens.
                var hoveredBibleReference by remember {
                    mutableStateOf<BibleReferenceSelection?>(null)
                }
    
                // Outer Column stacks a slim Back-button row on top of a Row
                // holding the two equal-width panes. Both panes share
                // `Modifier.weight(1f)` so the bible column and the editor
                // column each get exactly half the available width, regardless
                // of window size. The single Back button at the SPLIT level
                // replaces the per-pane back buttons (showBackButton=false on
                // both BibleScreen and NotesScreen) so the user has exactly
                // one obvious "return to Home" affordance.
                //
                // IMPORTANT: the content Row must NOT use `Modifier.fillMaxSize()`,
                // because that would ask for the Column's full max height BEFORE
                // weight distribution and starve the (non-weighted) back-button
                // Row of vertical space, hiding it. `Modifier.fillMaxWidth().weight(1f)`
                // is the right pattern: fillMaxWidth takes the parent's full
                // width, weight(1f) gets the remaining vertical space after
                // the back-button Row has its intrinsic height.
                //
                // The back-button Row is wrapped in a Surface with tonal
                // elevation so it's clearly visible as a top App Bar; without
                // the elevated background the Button blends into the
                // BibleScreen pane and looks like a stray inline button.
                // TODO: Add an Esc keyboard shortcut to call goHome() in SPLIT
                // mode (equivalent to clicking the "← Back to Home" button).
                // The straightforward `Modifier.onPreviewKeyEvent { ... }`
                // approach fails to compile on Compose Desktop 1.9 — the
                // `androidx.compose.ui.input.key.{Key,KeyEvent,KeyEventType}`
                // symbols don't resolve cleanly even with fully-qualified
                // names (4 compile rounds, same error pattern). The proper
                // fallback is to use a `KeyEventChannel` + `FocusRequester` in
                // a `LaunchedEffect` — see followup #1.
                Column(modifier = Modifier.fillMaxSize()) {
                    // Slim top bar with the SPLIT-level back button. Uses a
                    // plain clickable Text rather than Material3's TextButton
                    // because TextButton enforces a 40.dp minimum height — even
                    // with zero vertical contentPadding the bar would still be
                    // 40.dp tall, defeating the point of making it compact. A
                    // plain Text gives us intrinsic font-height sizing, so the
                    // bar's vertical footprint is just font + ~8.dp padding.
                    // The HorizontalDivider underneath provides subtle visual
                    // separation from the panes without the bulk of a
                    // tonalElevation Surface.
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Start,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Padding lives on the Text (not the Row) so the hit
                        // area and visual padding aren't double-counted.
                        Text(
                            text = "← Back",
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier
                                .clickable {
                                    SoundManager.play(SoundEvent.Click)
                                    goHome()
                                }
                                .padding(horizontal = 16.dp, vertical = 6.dp)
                        )
                    }
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                    )
    
                    // SPLIT pane layout: bible pane on the left, draggable
                    // divider in the middle, notes pane on the right. The split
                    // ratio (bible pane's share of the usable width, clamped
                    // 0.2..0.8 so neither pane disappears) is seeded from
                    // SettingsManager on entry and persisted back on drag end so
                    // the user's preferred ratio survives app restarts.
                    //
                    // BoxWithConstraints captures the available width at
                    // composition time. The three child Boxes must be wrapped in
                    // an explicit Row because BoxWithConstraintsScope extends
                    // BoxScope — without the Row wrapper the panes would stack
                    // vertically instead of horizontally. detectDragGestures
                    // reports drag deltas in pixels; we convert to a ratio delta
                    // by dividing by the usable width in px (resolved via
                    // LocalDensity because BoxWithConstraintsScope doesn't
                    // itself implement Density).
                    BoxWithConstraints(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    ) {
                        // 4.dp divider = tightest visible-as-nothing grab
                        // target on Compose Desktop. Was 16.dp until the white
                        // dividing line was removed; the 16.dp hit area left an
                        // empty gap between the bible and editor Cards, which
                        // looked like a visible strip of background after the
                        // line was gone. 4.dp is wide enough to land a mouse
                        // drag reliably while the panes sit almost flush.
                        val dividerWidthDp = 4.dp
                        val usableWidthDp = maxWidth - dividerWidthDp
                        val usableWidthPx = with(LocalDensity.current) { usableWidthDp.toPx() }
    
                        // Re-seed from SettingsManager each time SPLIT is
                        // re-entered (Compose drops the SPLIT branch's saveable
                        // state when the user toggles to another screen, so a
                        // fresh rememberSaveable on re-entry reads the latest
                        // SettingsManager.splitRatio). rememberSaveable also
                        // survives window resizes / config changes within a
                        // single SPLIT visit.
                        var ratio by rememberSaveable { mutableStateOf(SettingsManager.splitRatio) }
    
                        val bibleWidthDp = usableWidthDp * ratio
                        val notesWidthDp = usableWidthDp * (1f - ratio)
    
                        Row(modifier = Modifier.fillMaxSize()) {
                            Box(
                                modifier = Modifier
                                    .width(bibleWidthDp)
                                    .fillMaxHeight()
                            ) {
                                BibleScreen(
                                    back = { goHome() },
                                    initialReference = selectedBibleReference,
                                    showBackButton = false,
                                    compact = true,
                                    hoveredBibleReference = hoveredBibleReference,
                                    onOpenNoteTitle = { title, reference ->
                                        openNoteByTitle(title, reference)
                                    },
                                    // Dialogs (separate windows) forward Ctrl+F here.
                                    onOpenGlobalSearch = { appSearch.openSearch() },
                                    onOpenLexicon = { number ->
                                        lexiconInitialNumber = number
                                        screen = Screen.LEXICON
                                    },
                                    onOpenCompare = { bookName, cn, vn ->
                                        compareReference =
                                            BibleReferenceSelection(bookName, cn, vn)
                                        screen = Screen.COMPARE
                                    }
                                )
                            }
                            // Draggable divider. The previous version had a
                            // 2.dp visible `Box(... background(onSurfaceVariant))`
                            // drawing a white-ish line in the splitscreen middle;
                            // the user asked to remove that line. The whole
                            // inner Box is dropped — no background, no line, no
                            // fillMaxHeight on a child — and only the surrounding
                            // 4.dp hit area remains, so the bible and editor
                            // Cards sit flush against each other. The hit area
                            // still owns `detectDragGestures`, so drag-to-resize
                            // keeps working. No Click sound on drag start — a
                            // drag isn't a click.
                            Box(
                                modifier = Modifier
                                    .width(dividerWidthDp)
                                    .fillMaxHeight()
                                    .pointerInput(Unit) {
                                        detectDragGestures(
                                            onDragEnd = {
                                                SettingsManager.splitRatio = ratio
                                            },
                                            onDragCancel = {
                                                SettingsManager.splitRatio = ratio
                                            }
                                        ) { change, _ ->
                                            if (usableWidthPx > 0f) {
                                                val newRatio = (ratio + change.position.x / usableWidthPx)
                                                    .coerceIn(0.2f, 0.8f)
                                                if (newRatio != ratio) {
                                                    ratio = newRatio
                                                }
                                            }
                                        }
                                    }
                            )
                            Box(
                                modifier = Modifier
                                    .width(notesWidthDp)
                                    .fillMaxHeight()
                            ) {
                                NotesScreen(
                                    back = { goHome() },
                                    selectedFileName = selectedNoteFileName,
                                    showBackButton = false,
                                    compact = true,
                                    pendingScrollReference = pendingNoteScrollTarget,
                                    onScrollReferenceConsumed = { pendingNoteScrollTarget = null },
                                    pendingScrollLine = pendingScrollLine,
                                    onScrollLineConsumed = { pendingScrollLine = null },
                                    onOpenBibleReference = { book, chapter, verse ->
                                        openBible(BibleReferenceSelection(book, chapter, verse))
                                    },
                                    onHoverBibleReference = { ref ->
                                        hoveredBibleReference = ref
                                    },
                                    onSelectedNoteChange = { selectedNoteFileName = it },
                                    // Dialogs (separate windows) forward Ctrl+F here.
                                    onOpenGlobalSearch = { appSearch.openSearch() }
                                )
                            }
                        }
                    }
                }
            }
    
    
                Screen.SETTINGS ->
                    SettingsScreen(
                        initialSearchQuery = settingsSearchPrefill.orEmpty(),
                        back = {
                            settingsSearchPrefill = null
                            screen = Screen.HOME
                        }
                    )


            Screen.STATISTICS ->
                StatisticsScreen(
                    back = {
                        screen = Screen.HOME
                    }
                )


            Screen.LEXICON ->
                LexiconScreen(
                    initialQuery = lexiconInitialNumber,
                    onInitialQueryConsumed = { lexiconInitialNumber = null },
                    onOpenVerse = { book, chapter, verse ->
                        openBible(BibleReferenceSelection(book, chapter, verse))
                    },
                    onBack = {
                        screen = Screen.HOME
                    }
                )


            Screen.COLLECTIONS ->
                CollectionsScreen(
                    back = {
                        screen = Screen.HOME
                    },
                    onOpenVerse = { bookName, chapter, verse ->
                        openBible(
                            BibleReferenceSelection(bookName, chapter, verse)
                        )
                    }
                )


            Screen.COMPARE ->
                VerseCompareScreen(
                    initialReference = compareReference,
                    back = {
                        screen = Screen.HOME
                    }
                )
            }
    
            // Global Ctrl+F search overlay — floats above every screen. Both
            // result actions close it before navigating, so returning to the
            // current screen never leaves a stale overlay behind.
            if (appSearch.open) {
                GlobalSearchOverlay(
                    state = appSearch,
                    onOpenReference = { reference ->
                        appSearch.close()
                        openBible(reference)
                    },
                    onOpenNoteHit = { hit ->
                        appSearch.close()
                        openNoteByTitle(
                            title = hit.note.title,
                            scrollLine = hit.lineIndex
                        )
                    },
                    onOpenSettings = {
                        appSearch.close()
                        settingsSearchPrefill = "threshold"
                        screen = Screen.SETTINGS
                    }
                )
            }

            // Mini player: pinned bottom-right, appears whenever the in-app
            // media player has something loaded and SURVIVES screen changes
            // (going back / opening another note keeps the clip playing —
            // the always-on-top player window keeps running and this card
            // provides pause / resume / close from any screen). Clicking
            // the card re-opens the player window.
            MiniPlayer(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp)
            )
        }
    }
    