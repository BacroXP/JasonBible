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
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.DragData
import androidx.compose.ui.draganddrop.dragData
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalClipboardManager
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
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import data.BibleRepository
import data.NotesRepository
import data.SettingsManager
import data.SoundEvent
import data.SoundManager
import data.openExternalUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import model.Book
import model.ParsedNote
import ui.components.MaxWidthScaffold
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
    // In-app media preview popup: non-null while a media chip's preview
    // card is open — the tapped token plus the chip's window anchor.
    var mediaPreview by remember { mutableStateOf<MediaPreviewState?>(null) }
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
    val visualTransformation = rememberNoteVisualTransformation()

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
            delay(2500)
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
        delay(200)
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
    val clipboard = LocalClipboardManager.current

    // Word-style font-size zoom (A− / A+). A pure VIEW setting: it scales
    // the editor's rendered text but never touches the saved .note content.
    // Persisted via SettingsManager so the user's preferred size survives
    // app restarts (mirrors editorMaxWidth).
    var editorFontScale by remember { mutableStateOf(SettingsManager.editorFontScale) }

    LaunchedEffect(selectedFileName, notes) {
        if (selectedFileName != null && selectedFileNameState != selectedFileName) {
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
     * Save the active note in place and reset the UndoManager. Both the
     * EditorHeader's "Save" button and the Ctrl+S keystroke route here
     * so the two surfaces can never drift in their banner / state
     * semantics.
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
        val saved = NotesRepository.saveNoteInPlace(
            originalFileName = originalName,
            content = editorValue.text
        )
        editorValue = TextFieldValue(saved.content)
        undoManager.reset()
        undoManager.recordChange(editorValue, editorValue)
        saveBanner = "Saved in place: $originalName"
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
        clipboard.setText(AnnotatedString(editorValue.text.substring(min, max)))
    }

    fun cutSelection() {
        val sel = editorValue.selection
        if (sel.collapsed) return
        val min = minOf(sel.start, sel.end)
        val max = maxOf(sel.start, sel.end)
        clipboard.setText(AnnotatedString(editorValue.text.substring(min, max)))
        applyEditorChange(
            editorValue.copy(text = editorValue.text.removeRange(min, max), selection = TextRange(min))
        )
    }

    fun pasteClipboard() {
        val text = clipboard.getText()?.text
        if (!text.isNullOrEmpty()) {
            applyEditorChange(insertAtSelection(editorValue, text))
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
            delay(NOTES_POLL_INTERVAL_MS)
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

                                    LazyColumn(
                                        verticalArrangement = Arrangement.spacedBy(6.dp),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        items(notes, key = { it.fileName }) { note ->
                                            NoteFileCard(
                                                note = note,
                                                selected = note.fileName == selectedFileNameState,
                                                onClick = {
                                                    if (selectedFileNameState != note.fileName) {
                                                        selectedFileNameState = note.fileName
                                                    }
                                                },
                                                onDelete = { deleteCandidate = note }
                                            )
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
                            Text("New note")
                        }
                        if (showBackButton) {
                            Button(onClick = back, modifier = Modifier.fillMaxWidth()) { Text("Back") }
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
                            onToggleAutoContinue = { autoContinueLists = !autoContinueLists },
                            onCopy = { copySelection() },
                            onCut = { cutSelection() },
                            onPaste = { pasteClipboard() },
                            onToggleFind = { toggleFind() },
                            onSelectAll = { selectAllText() },
                            onRemoveColor = { removeColor() },
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
                            onTapReference = { hit, anchor ->
                                when (hit) {
                                    is ReferenceHit.Bible -> onOpenBibleReference(
                                        hit.match.book,
                                        hit.match.chapter,
                                        hit.match.verse
                                    )
                                    is ReferenceHit.Media -> mediaPreview =
                                        MediaPreviewState(hit.token, anchor)
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
                                    // Direction toggles for the user's
                                    // left/center/right mention (Ctrl+L /
                                    // E / R). See handleEditorShortcut
                                    // doc for the "we map to RLM
                                    // markers, not real alignment"
                                    // caveat.
                                    onCycleOrientation = {
                                        applyEditorChange(toggleLineOrientation(editorValue))
                                    },
                                    onForceLtr = {
                                        applyEditorChange(forceLineOrientation(editorValue, false))
                                    },
                                    onForceRtl = {
                                        applyEditorChange(forceLineOrientation(editorValue, true))
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
                                        shape = RoundedCornerShape(999.dp),
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
                            clipboard.setText(
                                AnnotatedString(preview.token.resolveUrl().orEmpty())
                            )
                            mediaPreview = null
                        },
                        // The popup is focusable (takes focus from the main
                        // window), so Ctrl+F forwards here.
                        onOpenGlobalSearch = onOpenGlobalSearch
                    )
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
            Text(
                text = "\uD83D\uDD0D",
                style = MaterialTheme.typography.bodyMedium
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
            delay(60)
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

