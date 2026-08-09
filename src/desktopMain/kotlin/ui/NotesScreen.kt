@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class
)

package ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.DragData
import androidx.compose.ui.draganddrop.dragData
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import data.BibleRepository
import data.MediaFileKind
import data.MediaReferenceToken
import data.MediaSearchKind
import data.MediaSearchResult
import data.MediaService
import data.MediaTitleCache
import data.NotesRepository
import data.SettingsManager
import data.SoundEvent
import data.SoundManager
import data.fetchMediaTitle
import data.findMediaReferenceTokens
import data.isPlayable
import data.isProfile
import data.mediaKindFor
import data.openExternalUrl
import data.searchYouTube
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds
import kotlin.math.roundToInt
import model.Book
import model.ParsedNote
import ui.components.MaxWidthScaffold
import java.awt.FileDialog
import java.awt.Frame
import java.net.URI
import java.nio.file.Path
import java.time.LocalDate
import java.time.format.DateTimeFormatter



// ---------------------------------------------------------------------------
// NotesScreen-scoped constants
// ---------------------------------------------------------------------------

// Word-style "Insert Date & Time": German short date format to match the
// app's default Deutsch language (05.08.2026).
private val DATE_FORMATTER = DateTimeFormatter.ofPattern("dd.MM.yyyy")

// How often the editor polls the notes directory for external changes
// (files added / removed / edited outside the app). The poll compares
// cheap name|mtime|size fingerprints, so notes are only re-parsed when
// something actually changed.
private const val NOTES_POLL_INTERVAL_MS = 1000L

// Editor zoom range — sourced from SettingsManager so the footer slider,
// setFontScale and the persisted clamp all share one definition.
internal val ZOOM_MIN = SettingsManager.MIN_FONT_SCALE
internal val ZOOM_MAX = SettingsManager.MAX_FONT_SCALE


// ---------------------------------------------------------------------------
// Verse-range picker (Shift+click on an extended reference chip)
// ---------------------------------------------------------------------------

/**
 * State for the Shift+click verse picker: the extended reference chip
 * that was tapped plus every (chapter, verse) pair of its range
 * (clamped to each chapter's actual verse count when known), anchored
 * at the tap point.
 */
private data class VersePickerState(
    val match: ReferenceMatch,
    val entries: List<Pair<Int, Int>>,
    val anchor: Offset
)

/**
 * Builds the verse list for an extended reference (`$Book&C&V+`,
 * `-V2`, `-&C2&V2`, …): every (chapter, verse) from the start through
 * the resolved range end, clamped to each chapter's real verse count
 * when the active module knows it (a range that runs past a chapter's
 * end is cut off at its last existing verse). Cross-chapter ranges walk
 * the intervening chapters. Returns null when there is nothing to pick
 * from.
 */
private fun buildVersePicker(match: ReferenceMatch, anchor: Offset): VersePickerState? {
    val chapter = match.chapter ?: return null
    val verse = match.verse ?: return null
    val endChapter = match.endChapter ?: chapter
    val endVerse = match.endVerse ?: return null
    if (endChapter < chapter || (endChapter == chapter && endVerse < verse)) return null
    val book = runCatching { BibleRepository.getBook(match.book) }.getOrNull()
    val entries = mutableListOf<Pair<Int, Int>>()
    for (c in chapter..endChapter) {
        val chapterData = book?.chapters?.firstOrNull { it.chapter == c }
        val lastVerse = chapterData?.verses?.lastOrNull()?.verse
        val first = if (c == chapter) verse else 1
        val rawLast = if (c == endChapter) endVerse else (lastVerse ?: endVerse)
        val last = if (lastVerse != null) minOf(rawLast, lastVerse) else rawLast
        if (last >= first) {
            for (v in first..last) entries.add(c to v)
        }
    }
    if (entries.isEmpty()) return null
    return VersePickerState(match, entries, anchor)
}
@Composable
fun NotesScreen(
    back: () -> Unit,
    selectedFileName: String? = null,
    showBackButton: Boolean = true,
    compact: Boolean = false,
    /**
     * Optional cross-screen "land me on this verse" target. When non-null
     * and the editor text + layout result are both ready, the editor's
     * verticalScroll animates to the first source line matching
     * $Book$Chapter$Verse$ for [pendingScrollReference]. After the
     * scroll fires (or the lookup confirms no match exists), this prop
     * is dropped via [onScrollReferenceConsumed] so subsequent
     * recompositions with the same data do not re-scroll.
     *
     * Plumbed from `Navigation.openNoteByTitle(title, reference)` —
     * lets a NoteChip click in the Bible pane jump straight to the
     * line in the note that mentions the clicked verse.
     */
    pendingScrollReference: BibleReferenceSelection? = null,
    onScrollReferenceConsumed: () -> Unit = {},
    /**
     * External "land on this line" target (from the global Ctrl+F search):
     * when non-null and the note is open, the editor scrolls to this
     * 0-based line of the note's content. Armed by forwarding it into the
     * internal [pendingScrollLineOffset] pipeline (the same mechanism the
     * in-screen notes search uses), then cleared via [onScrollLineConsumed]
     * so the parent's state doesn't linger.
     */
    pendingScrollLine: Int? = null,
    onScrollLineConsumed: () -> Unit = {},
    onOpenBibleReference: (book: String, chapter: Int?, verse: Int?) -> Unit = { _, _, _ -> },
    onHoverBibleReference: (BibleReferenceSelection?) -> Unit = { _ -> },
    /**
     * Reports which note is currently open in the editor, whenever that
     * changes (sidebar pick, create, delete, or an externally-driven
     * [selectedFileName] sync). Lets `Navigation` know a note is open even
     * when the user reached it via the standalone NOTES screen — so a
     * reference-chip click can switch to the SPLIT view (Bible + editor
     * side by side) instead of replacing the editor with a full-screen
     * Bible.
     */
    onSelectedNoteChange: (String?) -> Unit = {},
    /**
     * Ctrl+F opener for this screen's DIALOG windows / focusable popup:
     * separate Compose windows never reach the Navigation root key
     * handler, so each widget forwards the global-search shortcut here —
     * dismissing itself and opening the overlay in the main window.
     */
    onOpenGlobalSearch: () -> Unit = {}
) {
    // Mutable so deletions refresh the sidebar immediately. Re-read from
    // disk each time via NotesRepository.listFiles() (the repository
    // keeps no in-memory cache, so the fresh list always matches disk).
    var notes by remember { mutableStateOf(NotesRepository.listFiles()) }

    // Folder list for the sidebar sections. Recomputed when the note
    // list changes — folder create / rename / delete / move all refresh
    // `notes`, so the sections always mirror disk.
    val folders = remember(notes) { NotesRepository.folders() }

    var selectedFileNameState by remember {
        mutableStateOf(selectedFileName ?: notes.firstOrNull()?.fileName)
    }

    // The note currently pending deletion confirmation. When non-null the
    // AlertDialog renders; both the EditorHeader "Delete" button and the
    // sidebar card's hover-delete funnel into this.
    var deleteCandidate by remember { mutableStateOf<ParsedNote?>(null) }

    // Which reference picker (verse / chapter / book) is open, or null
    // when closed. Opened from the Insert ribbon buttons and the Ctrl+K /
    // Ctrl+Shift+K shortcuts; inserts the selected reference at the caret.
    var referencePickerKind by remember { mutableStateOf<ReferenceKind?>(null) }
    // Media (YouTube / Spotify / Vimeo / SoundCloud / link) insert picker:
    // same pattern as the Bible picker, inserts `@service:content ` at the
    // caret (rendered as a clickable chip by the editor).
    var mediaPickerOpen by remember { mutableStateOf(false) }
    // Custom highlight-color picker (the toolbar's rainbow dot). Non-null
    // while the dialog is open; seeded with the color of the marker at the
    // selection / cursor line so re-picking the same text starts from its
    // current color.
    var colorPickerHex by remember { mutableStateOf<String?>(null) }
    var colorPickerOpen by remember { mutableStateOf(false) }
    // Folder management dialog (create / rename / move-note). Non-null
    // while the dialog is open.
    var folderDialog by remember { mutableStateOf<FolderDialogMode?>(null) }
    // In-app media preview popup: non-null while a media chip's preview
    // card is open — the tapped token plus the chip's window anchor.
    var mediaPreview by remember { mutableStateOf<MediaPreviewState?>(null) }
    // Verse-range picker: non-null while the Shift+click popup over an
    // extended reference chip (`$Book$C$V-7`) is open. Holds the
    // reference, the resolved verse list and the chip's window anchor.
    var versePicker by remember { mutableStateOf<VersePickerState?>(null) }
    var editorValue by remember { mutableStateOf(TextFieldValue("")) }
    var saving by remember { mutableStateOf(false) }

    // Auto-list continuation toggle: when ON, pressing Enter at the end of
    // a `- ` / `1. ` / `>. ` / `># ` line inserts the matching continuation
    // prefix on the new line so the user keeps typing list entries. When
    // OFF, Enter behaves like a normal newline insert.
    var autoContinueLists by remember { mutableStateOf(true) }

    // Transient "Saved" / "PDF saved to ..." banner, auto-cleared after a delay.
    var saveBanner by remember { mutableStateOf<String?>(null) }

    val selectedNote = selectedFileNameState?.let { NotesRepository.loadNote(it) }

    // Media chips render the media's TITLE (oEmbed / profile name / file
    // name) instead of the raw `@youtube:…` link once fetched. The lookup
    // reads the session title cache; the version counter is bumped when a
    // new title lands so the remembered transformation rebuilds and the
    // editor re-filters with the fresh chip text.
    var mediaTitleVersion by remember { mutableStateOf(0) }
    val mediaTitleLookup: (MediaReferenceToken) -> String? = remember {
        { token -> token.resolveUrl()?.let { MediaTitleCache.get(it) } }
    }
    val visualTransformation = rememberNoteVisualTransformation(
        mediaTitleLookup = mediaTitleLookup,
        mediaTitleVersion = mediaTitleVersion
    )

    // Fetch media titles for the media chips in the open note so they
    // display the media's title instead of the link. Re-runs whenever the
    // note or its text changes, but MediaTitleCache serves cached URLs
    // instantly (and failed fetches are cached as null), so typing past an
    // already-resolved token never re-fetches. The debounce keeps a typing
    // burst from firing a fetch per keystroke; the version bump rebuilds
    // the transformation so the editor re-filters with the new titles.
    LaunchedEffect(selectedNote?.fileName, editorValue.text) {
        if (editorValue.text.isEmpty()) return@LaunchedEffect
        delay(200.milliseconds)
        val tokens = findMediaReferenceTokens(editorValue.text)
        if (tokens.isEmpty()) return@LaunchedEffect
        var newTitles = false
        for (token in tokens) {
            val url = token.resolveUrl() ?: continue
            if (MediaTitleCache.isCached(url)) continue
            val title = fetchMediaTitle(token)
            if (title != null) newTitles = true
        }
        if (newTitles) mediaTitleVersion++
    }

    // Editor scroll state hoisted out of EditorSurface so a parent
    // LaunchedEffect can drive `animateScrollTo` when a NoteChip click
    // in the Bible pane wants to land the editor on the matching
    // $Book$C$V$ line. EditorSurface receives it as a parameter and
    // wires it to `Modifier.verticalScroll(scrollState)`.
    val editorScrollState = rememberScrollState()

    // Layout result hoisted for the same reason: the scroll-into-view
    // effect needs to map a source-text offset to a Y coordinate, and
    // that mapping uses `TextLayoutResult.getLineForOffset` +
    // `getLineTop(...)` from the editor's actual layout pass. Without
    // hoisting, the effect would only be reachable from inside
    // EditorSurface and could not be triggered from
    // `pendingScrollReference` arriving in the parent.
    var editorLayoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }

    LaunchedEffect(saveBanner) {
        if (saveBanner != null) {
            delay(2500.milliseconds)
            saveBanner = null
        }
    }

    val undoManager = remember { UndoManager() }
    var selectedKey by remember { mutableStateOf<String?>(null) }
    // Find/Replace overlay state owned at NotesScreen scope so both the
    // keyboard shortcut chain (in handleEditorShortcut delegates below)
    // and the EditorFindBar Composable can mutate it without rebuilding
    // lambda identities each recomposition.
    var findState by remember { mutableStateOf(FindState()) }

    // Global notes search (Ctrl+Shift+F): a full-text scan across every
    // note file, listed above the editor; clicking a hit opens that note
    // and scrolls the editor to the matching line.
    var notesSearchOpen by remember { mutableStateOf(false) }
    var notesSearchQuery by remember { mutableStateOf("") }
    var notesSearchMatchCase by remember { mutableStateOf(false) }
    var notesSearchResults by remember {
        mutableStateOf<List<NotesRepository.NoteSearchHit>>(emptyList())
    }
    // Source-text offset to scroll the editor to after a notes-search hit
    // (relative to the newly opened note's content). Consumed by the
    // scroll effect below; cleared when the search closes.
    var pendingScrollLineOffset by remember { mutableStateOf<Int?>(null) }

    // Debounced global notes search. Re-reads the notes from disk on each
    // scan (fresh files), so the 200 ms debounce keeps rapid typing from
    // hammering the filesystem; the scan itself runs on a background
    // thread so many / large note files never stall the query field.
    LaunchedEffect(notesSearchOpen, notesSearchQuery, notesSearchMatchCase) {
        if (!notesSearchOpen || notesSearchQuery.isBlank()) {
            notesSearchResults = emptyList()
            return@LaunchedEffect
        }
        val q = notesSearchQuery.trim()
        if (q.isEmpty()) {
            notesSearchResults = emptyList()
            return@LaunchedEffect
        }
        delay(200.milliseconds)
        // Re-read the query after the debounce so the effect re-keying
        // can't land a stale scan on a newer query.
        val live = notesSearchQuery.trim()
        if (live.isEmpty()) return@LaunchedEffect
        notesSearchResults = withContext(Dispatchers.Default) {
            NotesRepository.searchNotes(live, notesSearchMatchCase)
        }
    }

    // Forward an external scroll target (global Ctrl+F search result) into
    // the same pendingScrollLineOffset pipeline the in-screen notes search
    // uses. The scroll effect below waits for the target note's content and
    // layout to be ready (it re-fires as they arrive), so arming here is
    // enough — the parent's target is consumed immediately.
    LaunchedEffect(pendingScrollLine) {
        val target = pendingScrollLine ?: return@LaunchedEffect
        pendingScrollLineOffset = target
        onScrollLineConsumed()
    }

    // Scroll the editor to a notes-search hit once the target note's text
    // and layout are ready. Like the pendingScrollReference effect, the
    // wait-states (empty source / null layout / offset beyond the current
    // text) are TRANSIENT — the target is only cleared after a successful
    // scroll so a just-clicked result can't be dropped while its note is
    // still loading.
    LaunchedEffect(pendingScrollLineOffset, editorValue.text, editorLayoutResult) {
        val target = pendingScrollLineOffset ?: return@LaunchedEffect
        val layout = editorLayoutResult ?: return@LaunchedEffect
        val source = editorValue.text
        if (source.isEmpty()) return@LaunchedEffect
        val mapping = (visualTransformation as? NoteVisualTransformation)?.offsetMapping
            ?: return@LaunchedEffect
        // Offset belongs to the freshly-opened note's content; if the
        // editor still shows the previous note's text the offset may be
        // out of range — wait for the load to re-fire us.
        if (target >= source.length) return@LaunchedEffect
        val displayed = mapping.originalToTransformed(target)
            .coerceIn(0, (layout.layoutInput.text.length - 1).coerceAtLeast(0))
        val lineIndex = layout.getLineForOffset(displayed)
            .coerceIn(0, (layout.lineCount - 1).coerceAtLeast(0))
        val lineTop = layout.getLineTop(lineIndex)
        editorScrollState.animateScrollTo((lineTop - 24f).coerceAtLeast(0f).toInt())
        pendingScrollLineOffset = null
    }

    // Inline reference autocomplete: books of the active translation, for
    // suggesting $Book names as the user types (cached after the Bible
    // pane has parsed the module; loaded here otherwise).
    var suggestBooks by remember { mutableStateOf<List<Book>>(emptyList()) }
    LaunchedEffect(Unit) {
        suggestBooks = BibleRepository.cachedBooks().orEmpty()
            .takeIf { it.isNotEmpty() }
            ?: BibleRepository.loadBooks()
    }

    // Word-style Clipboard group (Cut / Copy / Paste). Reads & writes the
    // OS clipboard so text can travel between the editor and other apps.
    val clipboard = LocalClipboard.current
    // Clipboard writes are async (suspend) in the new Compose API; all
    // copy/paste actions launch on this scope.
    val clipboardScope = rememberCoroutineScope()

    // Word-style font-size zoom (A− / A+). A pure VIEW setting: it scales
    // the editor's rendered text but never touches the saved .note content.
    // Persisted via SettingsManager so the user's preferred size survives
    // app restarts (mirrors editorMaxWidth).
    var editorFontScale by remember { mutableStateOf(SettingsManager.editorFontScale) }

    LaunchedEffect(selectedFileName, notes) {
        // Only adopt the prop when the file it names actually exists on
        // disk. External opens (openNoteByTitle / global search) always
        // point at a live file, while a STALE prop — e.g. Navigation
        // hasn't mirrored a rename back yet — names a deleted file and
        // must never yank the selection off the freshly-renamed note.
        // This guards the save path regardless of callback timing.
        if (selectedFileName != null &&
            selectedFileNameState != selectedFileName &&
            NotesRepository.loadNote(selectedFileName) != null
        ) {
            selectedFileNameState = selectedFileName
        } else if (selectedFileNameState == null) {
            selectedFileNameState = notes.firstOrNull()?.fileName
        }
    }

    // Keep the parent (Navigation) in sync with the open note. Single
    // effect keyed on the state: every path that changes
    // selectedFileNameState (sidebar click, create, delete, prop sync)
    // funnels through here, so the parent always knows a note is open.
    LaunchedEffect(selectedFileNameState) {
        onSelectedNoteChange(selectedFileNameState)
    }

    LaunchedEffect(selectedNote?.fileName) {
        val targetKey = selectedNote?.fileName
        if (targetKey != selectedKey) {
            selectedKey = targetKey
            val initial = TextFieldValue(selectedNote?.content.orEmpty())
            editorValue = initial
            undoManager.reset()
            undoManager.recordChange(initial, initial)
        }
    }

    // Cross-screen "land me on this verse" effect.
    //
    // Triggers when a NoteChip in the Bible pane calls into Navigation,
    // which forwards the verse here as `pendingScrollReference`. We wait
    // for ALL THREE prereqs to be ready — non-null target, loaded editor
    // text (the file finished loading into editorValue), and a non-null
    // layout result (the editor has run at least one BasicTextField
    // layout pass). The layout pass is what gives us line Y values, so
    // firing before it's available would just call animateScrollTo(0).
    //
    // IMPORTANT — DO NOT consume `onScrollReferenceConsumed()` in the
    // "wait for state" early-returns (empty source, null layout, null
    // offsetMapping). All three of those states are TRANSIENT and
    // usually arrive within a single frame after the sibling
    // `LaunchedEffect(selectedNote?.fileName)` runs — the file-load
    // effect sets editorValue.text after our effect has already armed
    // itself with an empty source, and BasicTextField.onTextLayout
    // populates editorLayoutResult and the visual filter's
    // offsetMapping a tick later. Consuming on any of those wait paths
    // would clear the target before the keys can re-fire with the
    // resolved values, leaving the editor opened at scroll=0 instead
    // of at the verse line. The only two consume paths that are safe:
    //
    //   (a) `findFirstReferenceOffset` returned non-null AND we
    //       successfully scrolled to it.
    //   (b) `findFirstReferenceOffset` returned null AND the text is
    //       non-empty — meaning the note definitively does not mention
    //       this verse, so we can give up and free the parent's state.
    LaunchedEffect(pendingScrollReference, editorValue.text, editorLayoutResult) {
        val target = pendingScrollReference ?: return@LaunchedEffect
        val layout = editorLayoutResult
        val source = editorValue.text
        if (source.isEmpty() || layout == null) {
            // Transient wait-state — let the file-load /
            // onTextLayout flows re-fire us. Do NOT consume.
            return@LaunchedEffect
        }

        val sourceOffset = findFirstReferenceOffset(
            source = source,
            book = target.book,
            chapter = target.chapter,
            verse = target.verse
        )
        if (sourceOffset == null) {
            // The note does not mention this verse at all (chip data
            // may be stale relative to the note body, or the file was
            // edited after the chip was generated). Safe to consume —
            // there's nothing to scroll to and a fresh target will
            // re-key the effect from the parent side.
            onScrollReferenceConsumed()
            return@LaunchedEffect
        }

        val mapping = (visualTransformation as? NoteVisualTransformation)?.offsetMapping
        if (mapping == null) {
            // Transient — the visual filter hasn't run a layout pass
            // yet for the current text edit. Do NOT consume.
            return@LaunchedEffect
        }

        // Defensive bounds: Kotlin's `coerceIn(min, max)` throws if
        // `min > max`, and `layoutInput.text.length` or `lineCount`
        // could be 0 in malformed edge cases. Clamp the upper bound
        // to >= 0 before handing it to coerceIn.
        val displayedOffset = mapping.originalToTransformed(sourceOffset)
            .coerceIn(0, (layout.layoutInput.text.length - 1).coerceAtLeast(0))
        val lineIndex = layout.getLineForOffset(displayedOffset)
            .coerceIn(0, (layout.lineCount - 1).coerceAtLeast(0))
        val lineTop = layout.getLineTop(lineIndex)

        // Subtract a few px so the matched line doesn't paste against
        // the very top of the viewport (slightly more readable on
        // high-DPI displays where heading margins sit close to the
        // scroll surface edge). `getLineTop` is in px relative to the
        // text container, and `animateScrollTo` takes the same px
        // frame, so subtract directly.
        val targetScroll = (lineTop - 24f).coerceAtLeast(0f).toInt()
        editorScrollState.animateScrollTo(targetScroll)

        onScrollReferenceConsumed()
    }

    fun applyEditorChange(newValue: TextFieldValue) {
        val resolved = if (autoContinueLists) {
            continueListAtEnter(editorValue, newValue)
        } else {
            newValue
        }
        undoManager.recordChange(editorValue, resolved)
        editorValue = resolved
    }

    /**
     * Save the active note and reset the UndoManager. Both the
     * EditorHeader's "Save" button and the Ctrl+S keystroke route here
     * so the two surfaces can never drift in their banner / state
     * semantics.
     *
     * The note is RENAMED on disk when its `# ` title changed — the
     * file name always follows the title — and the editor, sidebar list
     * and Navigation all switch to the new path so the same note stays
     * selected (the old file is gone, so pointing anywhere else at it
     * would drop the user out of the note).
     *
     * The keyboard path can fire while the editor isn't mounted (e.g.
     * right after a screen transition before the new `selectedNote`
     * recomputes), so the function must guard against [selectedNote]
     * being null — returning without side-effects in that boot race.
     * The toolbar path (EditorHeader) is only rendered when
     * [selectedNote] is non-null, so it never hits this branch.
     */
    fun doSave() {
        SoundManager.play(SoundEvent.Click)
        val note = selectedNote
        if (note == null) return
        saving = true
        val originalName = note.fileName
        val saved = NotesRepository.saveNote(
            originalFileName = originalName,
            content = editorValue.text
        )
        val renamed = saved.fileName != originalName
        // Point the editor, the sidebar list and Navigation at the
        // (possibly renamed) file. Navigation must learn the new name
        // SYNCHRONOUSLY — its `selectedNoteFileName` state is re-read by
        // the prop-sync effect on the next recomposition, and a stale
        // prop would snap the selection back to the (now deleted) old
        // file. The synchronous callback keeps the recomposition frame
        // consistent (prop == state → the sync effect no-ops).
        selectedFileNameState = saved.fileName
        notes = NotesRepository.listFiles()
        onSelectedNoteChange(saved.fileName)
        editorValue = TextFieldValue(saved.content)
        undoManager.reset()
        undoManager.recordChange(editorValue, editorValue)
        saveBanner = if (renamed) {
            "Renamed to \"${saved.title.ifBlank { saved.fileName }}\" (was $originalName)"
        } else {
            "Saved: ${saved.fileName}"
        }
        saving = false
    }

    /**
     * Export the active note as a PDF and surface the destination
     * path on the save banner. Shared between EditorHeader's "Export"
     * button and Ctrl+P.
     *
     * Same null guard rationale as [doSave].
     */
    fun doExport() {
        SoundManager.play(SoundEvent.Click)
        val note = selectedNote ?: return
        val path = NotePdfExporter.exportAsPdf(note, editorValue.text)
        if (path != null) saveBanner = "PDF saved to $path"
    }

    /**
     * Navigate one level up (HOME from NOTES, or HOME from SPLIT in
     * non-compact layout). No-op when the Back button isn't visible
     * (SPLIT case) — the dedicated "← Back" affordance in the SPLIT
     * top bar is the only Back path there. Shared between
     * EditorHeader's "Back" / Ctrl+W / Esc-when-Find-closed.
     */
    fun doBack() {
        SoundManager.play(SoundEvent.Click)
        if (showBackButton) back()
    }

    fun undo() {
        undoManager.undo(editorValue)?.let { editorValue = it }
    }

    fun redo() {
        undoManager.redo(editorValue)?.let { editorValue = it }
    }

    /**
     * Word-inspired editor actions shared between the toolbar buttons.
     * Each routes through [applyEditorChange] so edits stay undoable.
     */
    fun copySelection() {
        val sel = editorValue.selection
        if (sel.collapsed) return
        val min = minOf(sel.start, sel.end)
        val max = maxOf(sel.start, sel.end)
        clipboardScope.launch {
            clipboard.setClipEntry(plainTextClipEntry(editorValue.text.substring(min, max)))
        }
    }

    fun cutSelection() {
        val sel = editorValue.selection
        if (sel.collapsed) return
        val min = minOf(sel.start, sel.end)
        val max = maxOf(sel.start, sel.end)
        clipboardScope.launch {
            clipboard.setClipEntry(plainTextClipEntry(editorValue.text.substring(min, max)))
        }
        applyEditorChange(
            editorValue.copy(text = editorValue.text.removeRange(min, max), selection = TextRange(min))
        )
    }

    fun pasteClipboard() {
        clipboardScope.launch {
            val text = clipboard.getClipEntry()?.readPlainText()
            if (!text.isNullOrEmpty()) {
                applyEditorChange(insertAtSelection(editorValue, text))
            }
        }
    }

    fun selectAllText() {
        applyEditorChange(
            editorValue.copy(selection = TextRange(0, editorValue.text.length))
        )
    }

    fun clearFormatting() {
        applyEditorChange(clearInlineFormatting(editorValue))
    }

    fun removeColor() {
        applyEditorChange(removeColorMarkers(editorValue))
    }

    /**
     * The `[#hex]` marker color of the current selection / cursor line
     * (or null when none): seeds the color picker dialog so re-coloring
     * the same text starts from its existing color.
     */
    fun currentMarkerHex(): String? {
        val text = editorValue.text
        if (text.isEmpty()) return null
        val sel = editorValue.selection
        val start = minOf(sel.start, sel.end).coerceIn(0, text.length)
        val end = maxOf(sel.start, sel.end).coerceIn(0, text.length)
        // A selection colors a range of text; a collapsed caret colors the
        // whole line it sits on — same scope the toolbar's color dots use.
        val scope = if (sel.collapsed) {
            val lineStart = text.lastIndexOf('\n', start - 1).let { if (it < 0) 0 else it + 1 }
            val lineEnd = text.indexOf('\n', start).let { if (it < 0) text.length else it }
            text.substring(lineStart, lineEnd)
        } else {
            text.substring(start, end)
        }
        // colorMarkerRegex matches the full `[#hex]` token; the picker
        // wants the bare color, so strip the brackets.
        return colorMarkerRegex.find(scope)?.value
            ?.removeSurrounding("[", "]")
    }

    fun openColorPicker() {
        colorPickerHex = currentMarkerHex()
        colorPickerOpen = true
    }

    fun insertDate() {
        applyEditorChange(insertAtSelection(editorValue, DATE_FORMATTER.format(LocalDate.now())))
    }

    fun toggleFind(replaceMode: Boolean = false) {
        // Shared by the toolbar's Find button and the Ctrl+F / Ctrl+H
        // shortcuts (handleEditorShortcut): pressing again while Find is
        // open dismisses it, so a single action serves both open & close.
        // Opening Find also dismisses the global notes search (the two
        // overlays never stack).
        notesSearchOpen = false
        pendingScrollLineOffset = null
        findState = if (findState.open) {
            FindState()
        } else {
            findState.copy(open = true, replaceShown = replaceMode, matchIndex = -1)
        }
    }

    /**
     * Toggle the global notes search (Ctrl+Shift+F). Dismisses the
     * in-note Find bar so the two overlays never stack.
     */
    fun toggleNotesSearch() {
        findState = FindState()
        notesSearchOpen = !notesSearchOpen
        if (!notesSearchOpen) pendingScrollLineOffset = null
    }

    // ------------------------------------------------------------------
    // Inline $Book autocomplete
    // ------------------------------------------------------------------

    // Source offset of the '$' that starts the book-name prefix the caret
    // currently sits at the end of, or -1 when the caret isn't completing
    // a reference (see referencePrefixAt). Only collapsed selections count.
    val refPrefixStart = remember(editorValue.text, editorValue.selection) {
        if (editorValue.selection.collapsed) {
            referencePrefixAt(editorValue.text, editorValue.selection.end)
        } else {
            -1
        }
    }
    // Matching books: strict name prefixes (so an exact full name dismisses
    // the bar), or the first few books when the user just typed a bare
    // `$` at a fresh position.
    val refSuggestions = remember(refPrefixStart, editorValue.text, suggestBooks) {
        if (refPrefixStart < 0) emptyList()
        else {
            val partial = editorValue.text.substring(
                refPrefixStart + 1,
                editorValue.selection.end
            )
            if (partial.isEmpty()) {
                suggestBooks.take(8)
            } else {
                suggestBooks
                    .filter {
                        it.name.length > partial.length &&
                            it.name.startsWith(partial, ignoreCase = true)
                    }
                    .take(8)
            }
        }
    }

    // ------------------------------------------------------------------
    // Quote autocomplete (citations)
    // ------------------------------------------------------------------

    // Chain suggestion: the caret line already is a colored quote ending
    // in a reference chip; the verse AFTER the cited range is suggested.
    // Cheap (a regex + a direct verse lookup), so it recomputes
    // synchronously on every edit instead of debouncing.
    val chainSuggestion = remember(editorValue.text, editorValue.selection, suggestBooks) {
        if (editorValue.selection.collapsed) {
            computeChainSuggestion(suggestBooks, editorValue.text, editorValue.selection.end)
        } else {
            null
        }
    }
    // Fresh prefix: the trailing text at the caret that could be the start
    // of a verse (or null when the editor isn't in a quote-typing state).
    val citePrefix = remember(editorValue.text, editorValue.selection) {
        if (editorValue.selection.collapsed) {
            quotePrefixAt(editorValue.text, editorValue.selection.end)
        } else {
            null
        }
    }
    // The `@Phrase` the caret is completing (e.g. the `@Josia` in
    // "Watch @Josia"), or null. Recomputed synchronously on every edit —
    // a cheap string scan. Also gates the FRESH citation suggestion: on
    // an `@Phrase` line ("@Josia Queen") the user's intent is a media
    // reference, so the blue verse bar must not light up alongside the
    // media suggestions.
    val mediaPrefix = remember(editorValue.text, editorValue.selection) {
        if (editorValue.selection.collapsed) {
            mediaSearchPrefixAt(editorValue.text, editorValue.selection.end)
        } else {
            null
        }
    }
    // Fresh match, debounced + scanned on a background thread (a full
    // translation is ~31k verses). The match is stored PAIRED with the
    // exact prefix it was scanned for, so a suggestion can never be built
    // with a mismatched range — the effect nulls it at the START of every
    // run and re-keys on every keystroke, cancelling the pending scan.
    var citeFresh by remember { mutableStateOf<Pair<CitePrefix, CiteVerse>?>(null) }
    LaunchedEffect(citePrefix, chainSuggestion, suggestBooks) {
        citeFresh = null
        if (chainSuggestion != null || citePrefix == null || suggestBooks.isEmpty()) {
            return@LaunchedEffect
        }
        delay(120.milliseconds)
        val prefix = citePrefix
        citeFresh = withContext(Dispatchers.Default) {
            findFreshCite(suggestBooks, prefix.text)?.let { prefix to it }
        }
    }
    // The active suggestion: a chain (next verse of an existing citation)
    // wins over a fresh typed-prefix match — but a fresh match only when
    // the caret isn't completing an `@Phrase` (media wins there).
    val citeSuggestion: CiteSuggestion? = chainSuggestion ?: if (mediaPrefix == null) {
        citeFresh?.let { (prefix, fresh) ->
            FreshCiteSuggestion(
                book = fresh.book,
                chapter = fresh.chapter,
                verse = fresh.verse,
                text = fresh.text,
                prefixStart = prefix.start,
                prefixEnd = prefix.end
            )
        }
    } else {
        null
    }

    /**
     * Replace the typed `$partial` token with `$FullBookName` (keeping
     * the '$') and park the caret right after the completed name.
     * Routed through [applyEditorChange] so the completion is undoable
     * and list auto-continuation logic still applies.
     */
    fun acceptReferenceSuggestion() {
        val book = refSuggestions.firstOrNull() ?: return
        val start = refPrefixStart
        if (start < 0) return
        val caret = editorValue.selection.end
        val newText = editorValue.text.substring(0, start) +
            "$" + book.name +
            editorValue.text.substring(caret)
        val newCaret = start + 1 + book.name.length
        applyEditorChange(TextFieldValue(newText, selection = TextRange(newCaret)))
    }

    /**
     * Insert the currently suggested citation — Tab turns the typed
     * prefix into a blue colored quote with its reference behind it, or
     * appends the next verse to an existing citation and corrects the
     * reference range. Routed through [applyEditorChange] so the insert
     * stays undoable.
     */
    fun acceptCiteSuggestion() {
        val suggestion = citeSuggestion ?: return
        applyEditorChange(applyCiteSuggestion(editorValue, suggestion))
    }

    // ------------------------------------------------------------------
    // Media reference autofill (`@Phrase` → web search for channels/videos)
    // ------------------------------------------------------------------

    // Search results, debounced + fetched off the UI thread (a results
    // page scrape can take a moment). Stored PAIRED with the exact prefix
    // they were searched for, so a suggestion can never be accepted with
    // a mismatched range — the effect nulls the pair at the START of
    // every run and re-keys on every keystroke, cancelling the pending
    // search. The bar simply isn't composed until results arrive (and
    // never while offline, when the search degrades to an empty list).
    var mediaSearchResult by remember {
        mutableStateOf<Pair<MediaPrefix, List<MediaSearchResult>>?>(null)
    }
    LaunchedEffect(mediaPrefix) {
        mediaSearchResult = null
        val prefix = mediaPrefix ?: return@LaunchedEffect
        delay(250.milliseconds)
        val results = withContext(Dispatchers.IO) {
            searchYouTube(prefix.query)
        }
        mediaSearchResult = prefix to results
    }
    // The active suggestions: only the pair matching the CURRENT prefix.
    val mediaSuggestions = mediaSearchResult
        ?.takeIf { it.first == mediaPrefix }
        ?.second
        .orEmpty()

    /**
     * Insert the picked media suggestion — replaces the typed `@Phrase`
     * with the full `@youtube:…` token. Routed through
     * [applyEditorChange] so the insert stays undoable.
     */
    fun acceptMediaSuggestion(result: MediaSearchResult) {
        val pair = mediaSearchResult ?: return
        applyEditorChange(applyMediaSuggestion(editorValue, pair.first, result))
    }

    /**
     * Live update of the editor zoom while the footer slider is being
     * dragged — touches only in-memory state for a smooth preview; the
     * value is persisted to disk on [commitFontScale] (slider release).
     */
    fun setFontScale(value: Float) {
        editorFontScale = value.coerceIn(ZOOM_MIN, ZOOM_MAX)
    }

    /**
     * Persist the current zoom after a slider drag ends (or the footer
     * − / + buttons step it). Keeps disk writes to one per gesture.
     */
    fun commitFontScale() {
        SettingsManager.editorFontScale = editorFontScale
    }

    /**
     * Import existing notes (.note / .txt / .md) via a native file
     * dialog as NEW notes — an import never overwrites an existing file
     * (the repository dedupes names), so it's always undoable by
     * deleting the created file. The last successfully imported note is
     * selected so the result is immediately visible; unsupported /
     * unreadable files are reported on the save banner.
     */
    fun doImportNotes() {
        SoundManager.play(SoundEvent.Click)
        val dialog = FileDialog(null as Frame?, "Import notes", FileDialog.LOAD)
        dialog.isMultipleMode = true
        dialog.file = "*.note"
        dialog.isVisible = true
        val files = dialog.files?.toList().orEmpty()
        if (files.isEmpty()) return
        val supported = setOf("note", "txt", "md")
        var imported = 0
        var lastImported: String? = null
        val skipped = mutableListOf<String>()
        files.forEach { file ->
            val ext = file.extension.lowercase()
            if (ext !in supported) {
                skipped.add("${file.name} (unsupported type)")
                return@forEach
            }
            val result = NotesRepository.importNote(Path.of(file.absolutePath))
            if (result.fileName != null) {
                imported++
                lastImported = result.fileName
            } else {
                skipped.add("${file.name} (${result.error ?: "failed"})")
            }
        }
        notes = NotesRepository.listFiles()
        lastImported?.let { selectedFileNameState = it }
        saveBanner = buildString {
            append("Imported $imported note${if (imported == 1) "" else "s"}")
            if (skipped.isNotEmpty()) append(" — ${skipped.size} skipped")
        }
    }

    /**
     * Delete an (empty) folder from the sidebar. Folders that still
     * contain notes refuse politely — the user must move or delete the
     * notes first, so a stray click can never destroy notes.
     */
    fun deleteFolderAction(name: String) {
        SoundManager.play(SoundEvent.Click)
        if (!NotesRepository.deleteFolder(name)) {
            saveBanner = "Folder \"$name\" is not empty — move or delete its notes first."
        }
        notes = NotesRepository.listFiles()
    }

    /**
     * Delete a note permanently — called only after the user confirmed
     * in the AlertDialog. Refreshes the sidebar list and, when the
     * deleted note was the one being edited, switches to the first
     * remaining note (or the empty state when none are left).
     */
    /**
     * Create a brand-new blank note, select it in the editor and show it
     * in the sidebar. The repository picks a unique file name, so
     * repeated taps never collide.
     */
    fun doCreateNote() {
        SoundManager.play(SoundEvent.Click)
        if (saving) return  // same guard as doDelete — don't race a save write
        val created = NotesRepository.createNote("Untitled")
        notes = NotesRepository.listFiles()
        selectedFileNameState = created.fileName
    }

    fun doDelete(note: ParsedNote) {
        SoundManager.play(SoundEvent.Click)
        // Never delete while a save write is in flight — the two could
        // race on the same file (e.g. the user smashes Ctrl+S + Delete).
        if (saving) return
        if (NotesRepository.deleteNote(note.fileName)) {
            notes = NotesRepository.listFiles()
            if (selectedFileNameState == note.fileName) {
                selectedFileNameState = notes.firstOrNull()?.fileName
            }
        } else {
            saveBanner = "Could not delete \"${note.title.ifBlank { note.fileName }}\" (file may be locked)."
        }
        deleteCandidate = null
    }

    /**
     * Re-read the notes list from disk. Called by the periodic poll below
     * (and after in-app create/delete) so the sidebar always mirrors the
     * actual `.note` files — deleting a file externally removes it from
     * the list, adding one shows it. If the currently open note vanished
     * from disk (deleted / renamed externally) the selection moves to the
     * first remaining note, or to the empty state when none are left.
     *
     * The OPEN note's in-memory content is deliberately NOT reloaded on
     * external edits — auto-overwriting what the user is typing would be
     * destructive. Only the list (titles from disk) is refreshed.
     */
    fun refreshNotesFromDisk() {
        val fresh = NotesRepository.listFiles()
        val freshNames = fresh.map { it.fileName }.toSet()
        notes = fresh
        val current = selectedFileNameState
        if (current != null && current !in freshNames) {
            selectedFileNameState = fresh.firstOrNull()?.fileName
        }
    }

    // Poll the notes directory so files added / removed / edited outside
    // the app (e.g. in a file manager) show up in the sidebar without a
    // restart. The poll compares cheap name|mtime|size fingerprints, so it
    // only re-parses notes when something actually changed; 1s is fast
    // enough to feel live while costing almost nothing on a small folder.
    // A WatchService would be the "proper" watcher but adds a background
    // thread and per-platform quirks — polling is robust everywhere here.
    //
    // The loop lives only while this screen is composed; on re-entering
    // NOTES / SPLIT the `notes` state is re-initialized from disk anyway,
    // so changes made while on another screen are picked up regardless.
    LaunchedEffect(Unit) {
        var lastSignatures = NotesRepository.fileSignatures()
        while (true) {
            delay(NOTES_POLL_INTERVAL_MS.milliseconds)
            val current = NotesRepository.fileSignatures()
            if (current != lastSignatures) {
                lastSignatures = current
                refreshNotesFromDisk()
            }
        }
    }

    val dropTarget = remember {
        object : DragAndDropTarget {
            override fun onDrop(event: DragAndDropEvent): Boolean {
                val data = event.dragData()
                // Local media files: import each into the notes media
                // folder and insert its `@file:…` token at the caret.
                // Unsupported files (documents, archives) are skipped
                // silently — text drops still work as before.
                val fileUris = (data as? DragData.FilesList)?.readFiles().orEmpty()
                if (fileUris.isNotEmpty()) {
                    val refs = fileUris.mapNotNull { uri ->
                        runCatching { Path.of(URI.create(uri)) }.getOrNull()
                            ?.let { NotesRepository.importMediaFile(it) }
                    }
                    if (refs.isEmpty()) return false
                    val tokens = refs.joinToString(" ") { "@file:$it " }
                    applyEditorChange(insertAtSelection(editorValue, tokens))
                    return true
                }
                val droppedText = (data as? DragData.Text)?.readText()?.trim().orEmpty()
                if (droppedText.isBlank()) return false
                applyEditorChange(insertAtSelection(editorValue, droppedText))
                return true
            }
        }
    }

    // Hover-driven notes list: a single InteractionSource sits on the outer
    // Box so hover stays continuous while the cursor moves between the
    // always-present 20dp trigger strip and the animating sidebar. Using
    // separate sources on the strip and the sidebar would flicker as their
    // bounds change during the expand/shrink animation.
    val notesHoverSource = remember { MutableInteractionSource() }
    val isNotesHovered by notesHoverSource.collectIsHoveredAsState()
    // In compact (SPLIT) mode the notes pane's "left edge" is screen-center,
    // so the cursor would cross the trigger on every back-and-forth between
    // the bible pane and the editor — forcing the sidebar open would feel
    // like the editor is fighting the cursor. Pin the sidebar to visible
    // there and disable the hover trigger altogether.
    val showNotesList = compact || isNotesHovered

    // Outer-window sizing mirrors BibleScreen so the editor and bible
    // widgets feel like siblings at the edge of the window. Standalone
    // (non-compact) views get a centered 980.dp-max card with 24.dp
    // window margin — matching the bible's standalone look. Compact /
    // SPLIT views get a full-bleed card with 16.dp window margin so
    // both panes sit at the same vertical offset when paired in SPLIT.
    //
    // The 980.dp width cap leaves room for the 240.dp notes-list
    // sidebar (non-compact only) plus a ~740.dp editor column on
    // max-width windows, which is still readable. The inner Row keeps
    // its existing 16.dp/10.dp padding so editor text sits a further
    // 10.dp inside the Card edge — total content-to-window-edge is
    // ~34.dp standalone / 32.dp SPLIT, tight enough for the user's
    // "little space" request without crowding the editor card border.
    MaxWidthScaffold(
        compact = compact,
        maxWidth = SettingsManager.editorMaxWidth
    ) {
            // In compact (SPLIT) mode the outer NotesScreen pane is half the
            // window, so a 240dp notes-list sidebar would devour roughly a
            // quarter of the available width and leave the editor squeezed.
            // We hide the sidebar entirely in compact mode and let the
            // editor Column own the full pane width.
            //
            // Inner Row padding: 12.dp in compact (was 16.dp) so the editor's
            // SPLIT total content margin (Box 16.dp + Row 12.dp = 28.dp)
            // matches the bible widget's SPLIT total (Box 16.dp + Column
            // 12.dp = 28.dp) — both panes sit at the same vertical offset.
            // In non-compact we use 24.dp so the editor's standalone total
            // (Box 24.dp + Row 24.dp = 48.dp) matches the bible widget's
            // standalone total (Box 24.dp + Column 24.dp = 48.dp) — both
            // panes feel like siblings at the outer Card border instead of
            // the editor feeling noticeably tighter than the bible verses.
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(if (compact) 12.dp else 24.dp),
                horizontalArrangement = if (compact) Arrangement.Start else Arrangement.spacedBy(8.dp)
            ) {
                if (!compact) {
                    Box(
                        modifier = Modifier.fillMaxHeight()
                            .hoverable(notesHoverSource)
                            .soundHoverOn(notesHoverSource)
                    ) {
                        Row(modifier = Modifier.fillMaxHeight()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .width(12.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                if (!isNotesHovered) {
                                    Box(
                                        modifier = Modifier
                                            .width(2.dp)
                                            .height(80.dp)
                                            .background(
                                                MaterialTheme.colorScheme.outlineVariant
                                                    .copy(alpha = 0.6f),
                                                RoundedCornerShape(2.dp)
                                            )
                                    )
                                }
                            }

                            AnimatedVisibility(
                                visible = showNotesList,
                                enter = fadeIn() +
                                    expandHorizontally(expandFrom = Alignment.Start),
                                exit = fadeOut() +
                                    shrinkHorizontally(shrinkTowards = Alignment.Start)
                            ) {
                                // Play one Open/Close blip per genuine visibility
                                // transition (debounced via
                                // PlayOpenCloseSound itself; rapid hover
                                // wavering does not cause repeated sonic bursts).
                                PlayOpenCloseSound(visible = showNotesList)
                                Column(
                                    modifier = Modifier
                                        .fillMaxHeight()
                                        .width(240.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Row(
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(
                                            text = "Notes",
                                            style = if (showBackButton) {
                                                MaterialTheme.typography.headlineSmall
                                            } else {
                                                MaterialTheme.typography.titleMedium
                                            }
                                        )
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            // Import .note / .txt / .md files
                                            Text(
                                                text = "⇪",
                                                style = MaterialTheme.typography.titleMedium,
                                                color = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier
                                                    .clickable {
                                                        SoundManager.play(SoundEvent.Click)
                                                        doImportNotes()
                                                    }
                                                    .padding(horizontal = 8.dp, vertical = 2.dp)
                                            )
                                            // New folder
                                            Text(
                                                text = "▤",
                                                style = MaterialTheme.typography.titleMedium,
                                                color = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier
                                                    .clickable {
                                                        SoundManager.play(SoundEvent.Click)
                                                        folderDialog = FolderDialogMode.Create
                                                    }
                                                    .padding(horizontal = 8.dp, vertical = 2.dp)
                                            )
                                            // New note
                                            Text(
                                                text = "+",
                                                style = MaterialTheme.typography.titleLarge,
                                                color = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier
                                                    .clickable {
                                                        SoundManager.play(SoundEvent.Click)
                                                        doCreateNote()
                                                    }
                                                    .padding(horizontal = 8.dp, vertical = 2.dp)
                                            )
                                        }
                                    }

                                    LazyColumn(
                                        verticalArrangement = Arrangement.spacedBy(6.dp),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        if (folders.isEmpty()) {
                                            items(notes, key = { it.fileName }) { note ->
                                                NoteFileCard(
                                                    note = note,
                                                    selected = note.fileName == selectedFileNameState,
                                                    onClick = {
                                                        if (selectedFileNameState != note.fileName) {
                                                            selectedFileNameState = note.fileName
                                                        }
                                                    },
                                                    onDelete = { deleteCandidate = note },
                                                    onMove = {
                                                        folderDialog = FolderDialogMode.Move(
                                                            note.fileName,
                                                            note.folder
                                                        )
                                                    }
                                                )
                                            }
                                        } else {
                                            // Notes are organised in
                                            // folders: render each folder
                                            // as a section (header + its
                                            // notes), then the root notes
                                            // in their own section.
                                            folders.forEach { folder ->
                                                val folderNotes = notes.filter { it.folder == folder }
                                                item(key = "folder:$folder") {
                                                    FolderHeaderRow(
                                                        name = folder,
                                                        count = folderNotes.size,
                                                        onRename = {
                                                            folderDialog = FolderDialogMode.Rename(folder)
                                                        },
                                                        onDelete = { deleteFolderAction(folder) }
                                                    )
                                                }
                                                items(folderNotes, key = { it.fileName }) { note ->
                                                    NoteFileCard(
                                                        note = note,
                                                        selected = note.fileName == selectedFileNameState,
                                                        onClick = {
                                                            if (selectedFileNameState != note.fileName) {
                                                                selectedFileNameState = note.fileName
                                                            }
                                                        },
                                                        onDelete = { deleteCandidate = note },
                                                        onMove = {
                                                            folderDialog = FolderDialogMode.Move(
                                                                note.fileName,
                                                                note.folder
                                                            )
                                                        }
                                                    )
                                                }
                                            }
                                            val rootNotes = notes.filter { it.folder.isEmpty() }
                                            if (rootNotes.isNotEmpty()) {
                                                item(key = "folder:__root__") {
                                                    FolderHeaderRow(
                                                        name = "Root",
                                                        count = rootNotes.size,
                                                        canManage = false,
                                                        onRename = {},
                                                        onDelete = {}
                                                    )
                                                }
                                                items(rootNotes, key = { it.fileName }) { note ->
                                                    NoteFileCard(
                                                        note = note,
                                                        selected = note.fileName == selectedFileNameState,
                                                        onClick = {
                                                            if (selectedFileNameState != note.fileName) {
                                                                selectedFileNameState = note.fileName
                                                            }
                                                        },
                                                        onDelete = { deleteCandidate = note },
                                                        onMove = {
                                                            folderDialog = FolderDialogMode.Move(
                                                                note.fileName,
                                                                note.folder
                                                            )
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

                Column(
                    modifier = Modifier
                        .fillMaxHeight()
                        .let { if (compact) it.fillMaxWidth() else it.weight(1f) },
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (selectedNote == null) {
                        Text(
                            text = "Select a note file to start editing.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        // New-note affordance reachable even when no note
                        // exists yet (e.g. right after deleting the last one).
                        Button(
                            onClick = { doCreateNote() },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                imageVector = RibbonIcons.New,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Text("New note", modifier = Modifier.padding(start = 8.dp))
                        }
                        Button(
                            onClick = { doImportNotes() },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                // Same download-into-tray glyph as the PDF
                                // export action — the direction fits both.
                                imageVector = RibbonIcons.Export,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Text("Import notes…", modifier = Modifier.padding(start = 8.dp))
                        }
                        if (showBackButton) {
                            Button(onClick = back, modifier = Modifier.fillMaxWidth()) {
                                Icon(
                                    imageVector = RibbonIcons.Back,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Text("Back", modifier = Modifier.padding(start = 8.dp))
                            }
                        }
                    } else {
                        saveBanner?.let { msg ->
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.primaryContainer,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = msg,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                                )
                            }
                        }
                        EditorHeader(
                            note = selectedNote,
                            saving = saving,
                            onSave = { doSave() },
                            onExport = { doExport() },
                            onNew = { doCreateNote() },
                            onDelete = { deleteCandidate = selectedNote },
                            onBack = if (showBackButton) {
                                { doBack() }
                            } else null
                        )

                        EditorToolbar(
                            editorValue = editorValue,
                            canUndo = undoManager.canUndo(),
                            canRedo = undoManager.canRedo(),
                            autoContinueLists = autoContinueLists,
                            onEditorValueChange = { applyEditorChange(it) },
                            onUndo = { undo() },
                            onRedo = { redo() },
                            onToggleOrientation = {
                                applyEditorChange(toggleLineOrientation(editorValue))
                            },
                            onAlignLeft = {
                                applyEditorChange(toggleLineAlignment(editorValue, LineAlignment.LEFT))
                            },
                            onAlignCenter = {
                                applyEditorChange(toggleLineAlignment(editorValue, LineAlignment.CENTER))
                            },
                            onAlignRight = {
                                applyEditorChange(toggleLineAlignment(editorValue, LineAlignment.RIGHT))
                            },
                            onToggleAutoContinue = { autoContinueLists = !autoContinueLists },
                            onCopy = { copySelection() },
                            onCut = { cutSelection() },
                            onPaste = { pasteClipboard() },
                            onToggleFind = { toggleFind() },
                            onSelectAll = { selectAllText() },
                            onRemoveColor = { removeColor() },
                            onOpenColorPicker = { openColorPicker() },
                            onClearFormatting = { clearFormatting() },
                            onOpenReferencePicker = { kind ->
                                referencePickerKind = kind
                            },
                            onOpenMediaPicker = {
                                mediaPickerOpen = true
                            },
                            onInsertDate = { insertDate() }
                        )

                        // Global notes search overlay (Ctrl+Shift+F): a
                        // query bar plus a scrollable hit list; clicking a
                        // hit opens that note and scrolls the editor to
                        // the matching line.
                        if (notesSearchOpen) {
                            NotesSearchBar(
                                query = notesSearchQuery,
                                onQueryChange = { notesSearchQuery = it },
                                matchCount = notesSearchResults.size,
                                matchCase = notesSearchMatchCase,
                                onToggleMatchCase = {
                                    notesSearchMatchCase = !notesSearchMatchCase
                                },
                                onClose = {
                                    notesSearchOpen = false
                                    pendingScrollLineOffset = null
                                }
                            )
                            if (notesSearchQuery.isNotBlank()) {
                                NotesSearchResults(
                                    results = notesSearchResults,
                                    query = notesSearchQuery,
                                    matchCase = notesSearchMatchCase,
                                    onOpenHit = { hit ->
                                        SoundManager.play(SoundEvent.Click)
                                        selectedFileNameState = hit.note.fileName
                                        pendingScrollLineOffset = lineStartOffset(
                                            hit.note.content,
                                            hit.lineIndex
                                        )
                                    }
                                )
                            }
                        }

                        if (findState.open) {
                            EditorFindBar(
                                state = findState,
                                text = editorValue.text,
                                visualTransformation = visualTransformation,
                                editorScrollState = editorScrollState,
                                editorLayoutResult = editorLayoutResult,
                                onStateChange = { findState = it },
                                onReplaceCurrent = { range, replacement ->
                                    val oldText = editorValue.text
                                    val splicePoint = range.first.coerceAtMost(oldText.length)
                                    val endPoint = (range.last + 1).coerceAtMost(oldText.length)
                                    val newText = oldText.substring(0, splicePoint) +
                                        replacement +
                                        oldText.substring(endPoint)
                                    // Move the caret to just after the
                                    // replacement so the user can keep
                                    // editing straight away.
                                    val newCaret = splicePoint + replacement.length
                                    applyEditorChange(
                                        editorValue.copy(
                                            text = newText,
                                            selection = TextRange(newCaret)
                                        )
                                    )
                                },
                                onReplaceAll = { matches, replacement ->
                                    if (matches.isEmpty()) return@EditorFindBar
                                    val oldText = editorValue.text
                                    // Walk substitutions in REVERSE
                                    // order so earlier offsets stay
                                    // valid as later positions shift.
                                    var newText = oldText
                                    matches.reversed().forEach { range ->
                                        val sp = range.first.coerceAtMost(newText.length)
                                        val ep = (range.last + 1).coerceAtMost(newText.length)
                                        newText = newText.substring(0, sp) +
                                            replacement +
                                            newText.substring(ep)
                                    }
                                    applyEditorChange(editorValue.copy(text = newText))
                                }
                            )
                        }
                        EditorSurface(
                            value = editorValue,
                            onValueChange = { applyEditorChange(it) },
                            visualTransformation = visualTransformation,
                            scrollState = editorScrollState,
                            onLayoutResult = { editorLayoutResult = it },
                            dropTarget = dropTarget,
                            onTapReference = { hit, anchor, shiftPressed ->
                                when (hit) {
                                    is ReferenceHit.Bible -> {
                                        val match = hit.match
                                        // Shift+click on an extended range
                                        // chip opens a picker over all of its
                                        // verses; a plain click (or a
                                        // single-verse chip) jumps straight
                                        // to the start verse.
                                        if (shiftPressed && match.endVerse != null &&
                                            match.verse != null
                                        ) {
                                            versePicker = buildVersePicker(match, anchor)
                                        } else {
                                            onOpenBibleReference(
                                                match.book,
                                                match.chapter,
                                                match.verse
                                            )
                                        }
                                    }
                                    is ReferenceHit.Media -> {
                                        val token = hit.token
                                        // Playable items (videos / songs /
                                        // local audio & video) start in-app
                                        // playback IMMEDIATELY — no popup
                                        // detour, the embedded player opens
                                        // bottom-right and plays. Profiles,
                                        // plain links and embedded images
                                        // keep the preview popup (which
                                        // offers "Open in browser").
                                        val playable = token.service.isPlayable &&
                                            !token.isProfile &&
                                            // Local files play only when they
                                            // are actual video / audio; images
                                            // and unknown extensions keep the
                                            // preview popup.
                                            (token.service != MediaService.FILE ||
                                                mediaKindFor(token.content) in
                                                setOf(
                                                    MediaFileKind.VIDEO,
                                                    MediaFileKind.AUDIO
                                                ))
                                        if (playable) {
                                            SoundManager.play(SoundEvent.Click)
                                            MediaPlayerController.play(
                                                token,
                                                token.resolveUrl()?.let {
                                                    MediaTitleCache.get(it)
                                                }
                                            )
                                        } else {
                                            mediaPreview = MediaPreviewState(token, anchor)
                                        }
                                    }
                                    is ReferenceHit.Note -> {
                                        // `[[Title]]` note link: switch the
                                        // editor to the linked note. The
                                        // title resolves via
                                        // NotesRepository.findByTitle; a
                                        // missing target shows a banner
                                        // instead of silently doing nothing.
                                        val target = NotesRepository.findByTitle(hit.title)
                                        if (target != null) {
                                            selectedFileNameState = target.fileName
                                        } else {
                                            saveBanner = "Note \"${hit.title}\" not found."
                                        }
                                    }
                                    null -> Unit
                                }
                            },
                            onHoverBibleReference = { ref ->
                                onHoverBibleReference(ref)
                            },
                            onWordSelect = { start, end ->
                                // Double-click word selection. Routed
                                // through applyEditorChange so the new
                                // TextRange takes the same path as
                                // typing / undo.
                                applyEditorChange(
                                    editorValue.copy(selection = TextRange(start, end))
                                )
                            },
                            onShortcut = { event ->
                                // While the inline reference-autocomplete
                                // chips are visible, Enter / Tab complete
                                // the suggested book name instead of
                                // inserting a newline (or moving focus).
                                if (event.type == KeyEventType.KeyDown &&
                                    (event.key == Key.Enter || event.key == Key.Tab) &&
                                    !event.isCtrlPressed && !event.isAltPressed &&
                                    refSuggestions.isNotEmpty()
                                ) {
                                    acceptReferenceSuggestion()
                                    true
                                } else if (event.type == KeyEventType.KeyDown &&
                                    event.key == Key.Tab &&
                                    !event.isCtrlPressed && !event.isAltPressed &&
                                    mediaSuggestions.isNotEmpty()
                                ) {
                                    // Tab inserts the first matching media
                                    // (channel/video) for the typed @Phrase.
                                    acceptMediaSuggestion(mediaSuggestions.first())
                                    true
                                } else if (event.type == KeyEventType.KeyDown &&
                                    event.key == Key.Tab &&
                                    !event.isCtrlPressed && !event.isAltPressed &&
                                    citeSuggestion != null
                                ) {
                                    // Tab completes the suggested citation
                                    // (insert or append a verse). Only a
                                    // bare Tab — Enter stays a newline here.
                                    acceptCiteSuggestion()
                                    true
                                } else {
                                    handleEditorShortcut(
                                        event = event,
                                        isFindOpen = findState.open || notesSearchOpen,
                                    onUndo = { undo() },
                                    onRedo = { redo() },
                                    onWordJump = { extend, forward ->
                                        val sel = editorValue.selection
                                        // Anchor stays at min(start,end)
                                        // when extending forward, or
                                        // max(start,end) when extending
                                        // backward; when the selection is
                                        // collapsed we treat its single
                                        // point as both ends.
                                        val anchorEnd = if (sel.collapsed) sel.start
                                            else if (forward) minOf(sel.start, sel.end)
                                            else maxOf(sel.start, sel.end)
                                        val headPos = if (forward) maxOf(sel.start, sel.end)
                                            else minOf(sel.start, sel.end)
                                        val newHead = nextWordBoundary(
                                            editorValue.text,
                                            headPos,
                                            if (forward) WordDir.NEXT else WordDir.PREV
                                        )
                                        val newSel = if (extend) {
                                            TextRange(anchorEnd, newHead)
                                        } else {
                                            TextRange(newHead, newHead)
                                        }
                                        applyEditorChange(editorValue.copy(selection = newSel))
                                    },
                                    onSelectAll = {
                                        applyEditorChange(
                                            editorValue.copy(
                                                selection = TextRange(0, editorValue.text.length)
                                            )
                                        )
                                    },
                                    onToggleFind = { replaceMode ->
                                        toggleFind(replaceMode)
                                    },
                                    onToggleNotesSearch = {
                                        toggleNotesSearch()
                                    },
                                    onCloseFind = {
                                        // Esc closes whichever overlay is
                                        // open (in-note find or the global
                                        // notes search).
                                        findState = findState.copy(
                                            open = false,
                                            replaceShown = false,
                                            matchIndex = -1
                                        )
                                        notesSearchOpen = false
                                        pendingScrollLineOffset = null
                                    },
                                    // Persist (Ctrl+S) / Export (Ctrl+P)
                                    // / Back (Ctrl+W) — all delegate to
                                    // shared `doSave` / `doExport` /
                                    // `doBack` local functions defined in
                                    // NotesScreen body above. Sharing
                                    // means the keybind and the toolbar
                                    // button banners / state semantics
                                    // cannot drift.
                                    onSave = { doSave() },
                                    onExport = { doExport() },
                                    onBack = { doBack() },
                                    // Inline wrap (Ctrl+B / I / U) —
                                    // matches the toolbar's B / I / U
                                    // buttons so a re-press unwraps.
                                    onInlineWrap = { marker ->
                                        applyEditorChange(toggleWrap(editorValue, marker))
                                    },
                                    // Real paragraph alignment (Ctrl+L /
                                    // E / R) — left / center / right,
                                    // stored as invisible leading markers.
                                    onAlignLeft = {
                                        applyEditorChange(toggleLineAlignment(editorValue, LineAlignment.LEFT))
                                    },
                                    onAlignCenter = {
                                        applyEditorChange(toggleLineAlignment(editorValue, LineAlignment.CENTER))
                                    },
                                    onAlignRight = {
                                        applyEditorChange(toggleLineAlignment(editorValue, LineAlignment.RIGHT))
                                    },
                                    // Insert pickers (Ctrl+K opens the
                                    // verse picker, Ctrl+Shift+K the book
                                    // picker). Mirror the toolbar's
                                    // Reference / Chapter / Book buttons.
                                    onInsertReference = {
                                        referencePickerKind = ReferenceKind.VERSE
                                    },
                                    onInsertBook = {
                                        referencePickerKind = ReferenceKind.BOOK
                                    }
                                    )
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                        )

                        // Inline $Book autocomplete chips: shown while the
                        // caret is completing a reference prefix (e.g.
                        // "$Joh"). Enter / Tab or a click completes the
                        // name; the chips disappear once the caret leaves
                        // the prefix or the name is no longer a prefix.
                        if (refSuggestions.isNotEmpty()) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState())
                            ) {
                                Text(
                                    text = "Reference:",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                refSuggestions.forEach { book ->
                                    Surface(
                                        shape = PillShape,
                                        color = MaterialTheme.colorScheme.primaryContainer,
                                        modifier = Modifier.clickable {
                                            SoundManager.play(SoundEvent.Click)
                                            acceptReferenceSuggestion()
                                        }
                                    ) {
                                        Text(
                                            text = book.name,
                                            style = MaterialTheme.typography.labelSmall,
                                            modifier = Modifier.padding(
                                                horizontal = 10.dp,
                                                vertical = 5.dp
                                            )
                                        )
                                    }
                                }
                            }
                        }

                        // Media autofill: while the caret completes an
                        // `@Phrase` (e.g. "@Josia"), matching YouTube
                        // channels / videos from a debounced web search
                        // appear here; Tab (or a click) inserts the
                        // correct @youtube:… token in place of the phrase.
                        if (mediaSuggestions.isNotEmpty()) {
                            MediaSuggestionBar(
                                suggestions = mediaSuggestions,
                                onClick = { result ->
                                    SoundManager.play(SoundEvent.Click)
                                    acceptMediaSuggestion(result)
                                }
                            )
                        }

                        // Quote/citation suggestion: while the caret types
                        // the start of a Bible verse, the full matched
                        // verse appears here as a blue bar; Tab (or a
                        // click) inserts it as a colored quote with its
                        // reference — and, once inserted, suggests the
                        // NEXT verse so Tab chains them into a range.
                        citeSuggestion?.let { suggestion ->
                            CitationSuggestionBar(
                                suggestion = suggestion,
                                chain = suggestion is ChainCiteSuggestion,
                                onClick = {
                                    SoundManager.play(SoundEvent.Click)
                                    acceptCiteSuggestion()
                                }
                            )
                        }

                        // Rich media cards: every `@youtube:…` / `@spotify:…` /
                        // `@url:…` link in the note renders below the editor
                        // (and below the transient $Book autocomplete row)
                        // with its thumbnail (or a "title - channel" fallback)
                        // and title + channel beneath. Clicking a card opens
                        // the link in the default browser.
                        MediaReferencesPanel(
                            text = editorValue.text,
                            onOpenUrl = { url ->
                                SoundManager.play(SoundEvent.Click)
                                openExternalUrl(url)
                            }
                        )

                        EditorFooter(
                            value = editorValue,
                            canUndo = undoManager.canUndo(),
                            canRedo = undoManager.canRedo(),
                            fontScale = editorFontScale,
                            onFontScaleChange = { setFontScale(it) },
                            onFontScaleCommit = { commitFontScale() },
                            onEditorValueChange = { applyEditorChange(it) },
                            onUndo = { undo() },
                            onRedo = { redo() },
                            onToggleOrientation = {
                                applyEditorChange(toggleLineOrientation(editorValue))
                            }
                        )
                    }
                }
            }

            // Delete confirmation. Rendered outside the "note open" else
            // branch so it also covers the sidebar-card delete path (which
            // is reachable even when no note is currently open).
            deleteCandidate?.let { note ->
                // Ctrl+F while this dialog (a separate window) has focus:
                // dismiss it and open the global search.
                val dialogKeyHandler = globalSearchDialogKeyHandler(
                    onDismiss = { deleteCandidate = null },
                    onOpenGlobalSearch = onOpenGlobalSearch
                )
                AlertDialog(
                    onDismissRequest = { deleteCandidate = null },
                    title = { Text("Delete note?") },
                    text = {
                        Text(
                            "\"${note.title.ifBlank { note.fileName }}\" will be permanently " +
                                "deleted from your notes. This cannot be undone.",
                            modifier = Modifier.onPreviewKeyEvent(dialogKeyHandler)
                        )
                    },
                    confirmButton = {
                        TextButton(
                            modifier = Modifier.onPreviewKeyEvent(dialogKeyHandler),
                            onClick = { doDelete(note) }
                        ) {
                            Text("Delete", color = MaterialTheme.colorScheme.error)
                        }
                    },
                    dismissButton = {
                        TextButton(
                            modifier = Modifier.onPreviewKeyEvent(dialogKeyHandler),
                            onClick = {
                                SoundManager.play(SoundEvent.Click)
                                deleteCandidate = null
                            }
                        ) {
                            Text("Cancel")
                        }
                    }
                )
            }

            // Bible-reference picker. Rendered outside the toolbar so the
            // dialog stays mounted while the picker's own state (typed book
            // query, selected chapter / verse) is composed.
            referencePickerKind?.let { kind ->
                ReferenceInsertDialog(
                    initialKind = kind,
                    onDismiss = { referencePickerKind = null },
                    onInsert = { text ->
                        referencePickerKind = null
                        applyEditorChange(insertAtSelection(editorValue, text))
                    },
                    onOpenGlobalSearch = onOpenGlobalSearch
                )
            }

            if (mediaPickerOpen) {
                MediaInsertDialog(
                    onDismiss = { mediaPickerOpen = false },
                    onInsert = { text ->
                        mediaPickerOpen = false
                        applyEditorChange(insertAtSelection(editorValue, text))
                    },
                    onOpenGlobalSearch = onOpenGlobalSearch
                )
            }

            // Custom highlight-color picker (toolbar rainbow dot).
            // Applies the chosen color to the selection / cursor line via
            // the same toggleColoredQuote the preset dots use.
            if (colorPickerOpen) {
                ColorPickerDialog(
                    initialHex = colorPickerHex,
                    onDismiss = { colorPickerOpen = false },
                    onPick = { hex ->
                        colorPickerOpen = false
                        applyEditorChange(toggleColoredQuote(editorValue, hex))
                    }
                )
            }

            // Folder management dialog (create / rename / move-note).
            folderDialog?.let { mode ->
                FolderDialog(
                    mode = mode,
                    onDismiss = { folderDialog = null },
                    onConfirm = { value ->
                        when (mode) {
                            is FolderDialogMode.Create -> {
                                if (NotesRepository.createFolder(value)) {
                                    saveBanner = "Folder \"$value\" created."
                                } else {
                                    saveBanner = "Could not create folder \"$value\"."
                                }
                            }
                            is FolderDialogMode.Rename -> {
                                // The open note may live inside the renamed
                                // folder — its on-disk path changes with the
                                // folder, so re-point the editor at the new
                                // path or the sidebar selection dangles.
                                val editedInFolder = selectedFileNameState
                                    ?.takeIf { it.startsWith("${mode.oldName}/") }
                                when {
                                    mode.oldName == value -> Unit // no-op rename

                                    NotesRepository.renameFolder(mode.oldName, value) -> {
                                        saveBanner = "Folder renamed to \"$value\"."
                                        if (editedInFolder != null) {
                                            // renameFolder moves the whole
                                            // directory, so the note keeps
                                            // its name relative to the folder.
                                            selectedFileNameState =
                                                "$value/${editedInFolder.substringAfter('/')}"
                                        }
                                    }

                                    else -> saveBanner = "Could not rename folder."
                                }
                            }
                            is FolderDialogMode.Move -> {
                                val moved = NotesRepository.moveNote(mode.fileName, value)
                                if (moved == null) {
                                    saveBanner = "Could not move note."
                                } else if (selectedFileNameState == mode.fileName) {
                                    // The note being edited moved — keep
                                    // the editor on it under its new path.
                                    selectedFileNameState = moved
                                }
                            }
                        }
                        notes = NotesRepository.listFiles()
                        folderDialog = null
                    }
                )
            }

            // In-app media preview popup, anchored at the tapped chip's
            // window position (clamped to stay on screen). Focusable, so
            // clicking anywhere else or pressing Esc dismisses it.
            mediaPreview?.let { preview ->
                Popup(
                    popupPositionProvider = remember(preview.anchorWindow) {
                        MediaPreviewPositionProvider(
                            IntOffset(
                                preview.anchorWindow.x.roundToInt(),
                                preview.anchorWindow.y.roundToInt()
                            )
                        )
                    },
                    onDismissRequest = { mediaPreview = null },
                    properties = PopupProperties(focusable = true)
                ) {
                    MediaPreviewCard(
                        token = preview.token,
                        onClose = { mediaPreview = null },
                        onOpen = {
                            SoundManager.play(SoundEvent.Click)
                            openExternalUrl(preview.token.resolveUrl().orEmpty())
                            mediaPreview = null
                        },
                        onCopy = {
                            SoundManager.play(SoundEvent.Click)
                            clipboardScope.launch {
                                clipboard.setClipEntry(plainTextClipEntry(preview.token.resolveUrl().orEmpty()))
                            }
                            mediaPreview = null
                        },
                        // The popup is focusable (takes focus from the main
                        // window), so Ctrl+F forwards here.
                        onOpenGlobalSearch = onOpenGlobalSearch
                    )
                }
            }

            // Verse-range picker (Shift+click on an extended reference
            // chip like `$John$3$16-7`): lists every verse of the range
            // so the user can pick which one to jump to. Anchored at the
            // tapped chip; focusable so Esc / outside click dismisses it.
            versePicker?.let { picker ->
                Popup(
                    popupPositionProvider = remember(picker.anchor) {
                        MediaPreviewPositionProvider(
                            IntOffset(
                                picker.anchor.x.roundToInt(),
                                picker.anchor.y.roundToInt()
                            )
                        )
                    },
                    onDismissRequest = { versePicker = null },
                    properties = PopupProperties(focusable = true)
                ) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        tonalElevation = 4.dp,
                        modifier = Modifier.widthIn(min = 180.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .padding(vertical = 6.dp)
                                .heightIn(max = 300.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            Text(
                                text = picker.match.displayText(),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                            )
                            picker.entries.forEach { (entryChapter, entryVerse) ->
                                val rowHover = remember { MutableInteractionSource() }
                                val rowHovered by rowHover.collectIsHoveredAsState()
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (rowHovered) {
                                        MaterialTheme.colorScheme.primaryContainer
                                            .copy(alpha = 0.5f)
                                    } else {
                                        MaterialTheme.colorScheme.surface
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 6.dp, vertical = 1.dp)
                                        .hoverable(rowHover)
                                        .clickable {
                                            SoundManager.play(SoundEvent.Click)
                                            versePicker = null
                                            onOpenBibleReference(
                                                picker.match.book,
                                                entryChapter,
                                                entryVerse
                                            )
                                        }
                                ) {
                                    Text(
                                        text = "${picker.match.book} " +
                                            "$entryChapter:$entryVerse",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.padding(
                                            horizontal = 10.dp,
                                            vertical = 6.dp
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
}


// ---------------------------------------------------------------------------
// Global notes search (Ctrl+Shift+F) + inline reference autocomplete
// ---------------------------------------------------------------------------

/**
 * Slim query bar for the global notes search: a field, the live hit
 * count, an "Aa" case toggle and a close button (mirrors the editor's
 * find bar affordances).
 */
@Composable
private fun NotesSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    matchCount: Int,
    matchCase: Boolean,
    onToggleMatchCase: () -> Unit,
    onClose: () -> Unit
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
            Icon(
                imageVector = RibbonIcons.Find,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = MaterialTheme.colorScheme.onSurface
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                singleLine = true,
                modifier = Modifier.weight(1f),
                decorationBox = { inner ->
                    if (query.isEmpty()) {
                        Text(
                            text = "Search all notes\u2026",
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
                    text = matchCount.toString(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            ToolbarActionButton(
                label = if (matchCase) "Aa\u00B7on" else "Aa",
                accent = matchCase,
                tooltip = "Match case",
                onClick = onToggleMatchCase
            )
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
    }
}


/**
 * Hit list for the global notes search: note title + trimmed matching
 * line (with the query highlighted), clickable to open the note and
 * scroll to the line. Bounded height so it never swallows the editor.
 */
@Composable
private fun NotesSearchResults(
    results: List<NotesRepository.NoteSearchHit>,
    query: String,
    matchCase: Boolean,
    onOpenHit: (NotesRepository.NoteSearchHit) -> Unit
) {
    if (results.isEmpty()) {
        Text(
            text = "No matches for \"${query.trim()}\".",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        return
    }
    val hoverSource = remember { MutableInteractionSource() }
    val isHovered by hoverSource.collectIsHoveredAsState()
    LaunchedEffect(isHovered) {
        if (isHovered) {
            delay(60.milliseconds)
            SoundManager.play(SoundEvent.Hover)
        }
    }
    Surface(
        shape = RoundedCornerShape(8.dp),
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier
                .padding(vertical = 6.dp)
                .heightIn(max = 220.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                text = "${results.size} " + if (results.size == 1) "match" else "matches",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp)
            )
            results.forEach { hit ->
                Column(
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .hoverable(hoverSource)
                        .clickable {
                            SoundManager.play(SoundEvent.Click)
                            onOpenHit(hit)
                        }
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                ) {
                    Text(
                        text = hit.note.title.ifBlank { hit.note.fileName },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = highlightNotesPreview(hit.lineText, query, matchCase),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}


/**
 * Character offset of the first character of [lineIndex] (0-based)
 * within [content], used to scroll the editor to a search hit. Counts
 * `\n` separators; clamps to the content length for out-of-range lines.
 */
private fun lineStartOffset(content: String, lineIndex: Int): Int {
    var offset = 0
    var seen = 0
    while (seen < lineIndex && offset < content.length) {
        if (content[offset] == '\n') seen++
        offset++
    }
    return offset.coerceAtMost(content.length)
}


/**
 * Highlights every occurrence of [query] in [text] (bold on soft yellow),
 * mirroring the Bible search's highlight treatment.
 */
private fun highlightNotesPreview(
    text: String,
    query: String,
    matchCase: Boolean
): AnnotatedString {
    val q = query.trim()
    if (q.isEmpty()) return AnnotatedString(text)
    return buildAnnotatedString {
        var from = 0
        while (from < text.length) {
            val index = text.indexOf(q, from, ignoreCase = !matchCase)
            if (index == -1) {
                append(text.substring(from))
                break
            }
            append(text.substring(from, index))
            withStyle(
                SpanStyle(
                    fontWeight = FontWeight.Bold,
                    background = androidx.compose.ui.graphics.Color(0xFFFFF176)
                        .copy(alpha = 0.55f)
                )
            ) {
                append(text.substring(index, index + q.length))
            }
            from = index + q.length
        }
    }
}


/**
 * Returns the source offset of the `$` that starts the book-name prefix
 * the caret sits at the end of, or -1 when the caret isn't completing a
 * reference. Rules:
 *
 *  - The caret must be at the very end of the token (no letters/digits
 *    right after it), so mid-word edits don't pop the suggestion bar.
 *  - Scanning backwards from the caret, book-name characters (letters,
 *    digits, spaces for multi-word names like "1 Mose", apostrophes,
 *    hyphens) are skipped until the starting `$` or a hard punctuation
 *    terminator. False positives from sentence text ("$Lukas today") are
 *    filtered by the caller's `startsWith` check, so the scan can afford
 *    to be generous.
 *  - A bare `$` (empty prefix) only suggests when it starts a fresh
 *    reference (preceded by whitespace / line punctuation), so "$5" or
 *    "$Lukas$" (a completed book + chapter separator) don't fire.
 */
internal fun referencePrefixAt(text: String, caret: Int): Int {
    if (caret <= 0 || caret > text.length) return -1
    if (caret < text.length && text[caret].isLetterOrDigit()) return -1
    var i = caret - 1
    while (i >= 0 && text[i] != '$') {
        val c = text[i]
        if (c == '\n' || c == '\t' || c == '\r') break
        // Hard punctuation ends the reference context (the '.' of "$5.99"
        // stops the scan before the '$', so money isn't a book prefix).
        if (c in REFERENCE_PREFIX_TERMINATORS) break
        i--
    }
    if (i < 0 || text[i] != '$') return -1
    // Empty prefix ("$" directly before the caret): only fresh starts.
    if (i == caret - 1) {
        val before = if (i > 0) text[i - 1] else ' '
        if (!before.isWhitespace() &&
            before !in REFERENCE_PREFIX_TERMINATORS
        ) {
            return -1
        }
    }
    return i
}

// Punctuation that terminates a reference-prefix scan (see
// [referencePrefixAt]). Deliberately excludes space, apostrophe and
// hyphen so multi-word names like "1 Mose" survive the backwards walk.
private val REFERENCE_PREFIX_TERMINATORS = setOf(
    '.', ',', ';', ':', '!', '?',
    '(', ')', '[', ']', '{', '}',
    '\u00BB', '\u00AB', '\u201E', '\u201C', '\u201D', '/'
)


/**
 * The media suggestion bar shown below the editor while the caret
 * completes an `@Phrase`: up to [MAX_MEDIA_SUGGESTIONS] matching
 * channels / videos from the debounced web search, each with a muted
 * kind icon, its title and a secondary line (channel name / subscriber
 * count) plus the `@service` tag it will insert. Tab (or a click)
 * inserts the token; the bar simply isn't composed until results arrive.
 */
@Composable
private fun MediaSuggestionBar(
    suggestions: List<MediaSearchResult>,
    onClick: (MediaSearchResult) -> Unit
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .padding(8.dp)
    ) {
        Text(
            text = "Media:",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        suggestions.forEach { result ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .clickable {
                        onClick(result)
                    }
                    .padding(horizontal = 8.dp, vertical = 5.dp)
            ) {
                Icon(
                    imageVector = if (result.kind == MediaSearchKind.CHANNEL) {
                        RibbonIcons.MediaChannel
                    } else {
                        RibbonIcons.MediaPlay
                    },
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = result.title,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (result.subtitle.isNotBlank()) {
                        Text(
                            text = result.subtitle,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Text(
                    text = "@" + result.service.key,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
        Text(
            text = "[Tab] insert",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}


/**
 * The blue suggestion bar shown below the editor while a citation is being
 * typed: the full matched verse (rendered as a blue colored quote) plus
 * its reference, with a Tab hint ("insert" for a fresh match, "append"
 * when chaining the next verse onto an existing citation). Clicking the
 * bar accepts the suggestion too.
 */
@Composable
private fun CitationSuggestionBar(
    suggestion: CiteSuggestion,
    chain: Boolean,
    onClick: () -> Unit
) {
    val blue = colorFromHexInternal(CITE_BLUE_HEX)
    val hoverSource = remember { MutableInteractionSource() }
    val isHovered by hoverSource.collectIsHoveredAsState()
    LaunchedEffect(isHovered) {
        if (isHovered) {
            delay(60.milliseconds)
            SoundManager.play(SoundEvent.Hover)
        }
    }
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (isHovered) {
            MaterialTheme.colorScheme.surfaceVariant
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
        },
        modifier = Modifier
            .fillMaxWidth()
            .hoverable(hoverSource)
            .clickable {
                SoundManager.play(SoundEvent.Click)
                onClick()
            }
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            // Blue accent bar — the "colored quote" cue.
            Box(
                modifier = Modifier
                    .size(width = 4.dp, height = 36.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(blue)
            )
            Column(
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = "${suggestion.book.name} ${suggestion.chapter}:${suggestion.verse}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "\u201E${suggestion.text}\u201C",
                    style = MaterialTheme.typography.bodySmall,
                    color = blue,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                text = if (chain) "[Tab] append" else "[Tab] insert",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}


// ---------------------------------------------------------------------------
// Folder management (sidebar sections + dialogs)
// ---------------------------------------------------------------------------

/** What the folder dialog is doing right now. */
private sealed interface FolderDialogMode {
    data object Create : FolderDialogMode
    data class Rename(val oldName: String) : FolderDialogMode
    data class Move(val fileName: String, val currentFolder: String) : FolderDialogMode
}


/**
 * One sidebar section header: the folder name with its note count and
 * (when [canManage]) a "…" menu offering Rename / Delete. Folders that
 * still contain notes refuse deletion at the action level, so the menu
 * never destroys notes.
 */
@Composable
private fun FolderHeaderRow(
    name: String,
    count: Int,
    canManage: Boolean = true,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 2.dp, top = 6.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
        ) {
            Icon(
                imageVector = RibbonIcons.Folder,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Text(
                text = "$name  ($count)",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                modifier = Modifier.padding(start = 4.dp)
            )
        }
        if (canManage) {
            Box {
                Icon(
                    imageVector = RibbonIcons.More,
                    contentDescription = "Folder actions",
                    modifier = Modifier
                        .size(20.dp)
                        .clickable {
                            SoundManager.play(SoundEvent.Click)
                            menuOpen = true
                        }
                        .padding(horizontal = 2.dp, vertical = 2.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Rename") },
                        onClick = {
                            SoundManager.play(SoundEvent.Click)
                            menuOpen = false
                            onRename()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Delete folder") },
                        onClick = {
                            SoundManager.play(SoundEvent.Click)
                            menuOpen = false
                            onDelete()
                        }
                    )
                }
            }
        }
    }
}


/** One selectable folder row inside the move dialog. */
@Composable
private fun FolderChoiceRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable {
                SoundManager.play(SoundEvent.Click)
                onClick()
            }
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        if (selected) {
            Text("\u2713", color = MaterialTheme.colorScheme.primary)
        }
    }
}


/**
 * The folder dialog: a name field for Create / Rename, or a folder
 * picker (Root + every folder except the note's current one) for Move.
 */
@Composable
private fun FolderDialog(
    mode: FolderDialogMode,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var name by remember(mode) {
        mutableStateOf(if (mode is FolderDialogMode.Rename) mode.oldName else "")
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                when (mode) {
                    is FolderDialogMode.Create -> "New folder"
                    is FolderDialogMode.Rename -> "Rename folder"
                    is FolderDialogMode.Move -> "Move note to folder"
                }
            )
        },
        text = {
            when (mode) {
                is FolderDialogMode.Create, is FolderDialogMode.Rename -> {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it },
                            label = { Text("Folder name") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        // Nested folders ("Unterordner") are simply paths:
                        // a hint makes the capability discoverable.
                        if (mode is FolderDialogMode.Create) {
                            Text(
                                text = "Use / for subfolders (e.g. Study/Deep).",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                is FolderDialogMode.Move -> {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        FolderChoiceRow(
                            label = "Root (no folder)",
                            selected = mode.currentFolder.isEmpty()
                        ) { onConfirm("") }
                        NotesRepository.folders()
                            .filter { it != mode.currentFolder }
                            .forEach { folder ->
                                FolderChoiceRow(
                                    label = folder,
                                    selected = mode.currentFolder == folder
                                ) { onConfirm(folder) }
                            }
                    }
                }
            }
        },
        confirmButton = {
            if (mode is FolderDialogMode.Create || mode is FolderDialogMode.Rename) {
                TextButton(
                    enabled = name.isNotBlank(),
                    onClick = { onConfirm(name.trim()) }
                ) {
                    Text("OK")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

