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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import data.BibleRepository
import data.NotesRepository
import data.SettingsManager
import data.SoundEvent
import data.SoundManager
import kotlinx.coroutines.delay
import model.ParsedNote
import ui.components.MaxWidthScaffold
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt


// ---------------------------------------------------------------------------
// File-level constants & regexes
//
// The regexes here are referenced both inside `NoteVisualTransformation` and
// from the file-level `findReferenceAt`. They mirror the grammar in
// `NotesRepository` so the visual transform and the parser agree on what
// counts as a heading / bullet / quote / reference / colored-quote.
// ---------------------------------------------------------------------------

private const val RLM = "\u200F" // Right-to-Left Mark: toggles a line into RTL
private const val LRM = "\u200E" // Left-to-Right Mark: explicit LTR line marker

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
private val ZOOM_MIN = SettingsManager.MIN_FONT_SCALE
private val ZOOM_MAX = SettingsManager.MAX_FONT_SCALE

private val orderedListRegex = Regex("^\\d+\\.\\s+")
private val referenceLineRegex =
    Regex("^\\\$([^\\\$]+)(?:\\\$(\\d+)(?:\\\$(\\d+)(?:\\s+(.*))?)?)?\\s*$")
private val coloredQuoteRegex =
    Regex("^\"(.+?)\"(?:\\[#([0-9A-Fa-f]{3,8})])?\\s*(.*)\$")

// Matches a `[#hex]` colour marker anywhere in a line. Used by the
// toolbar's "no colour" dot to strip highlight colours while keeping the
// quote markers themselves intact.
private val colorMarkerRegex = Regex("\\[(?:#[0-9A-Fa-f]{3,8})]")

private val INLINE_BOLD = Regex("\\*\\*([^*]+)\\*\\*")
private val INLINE_ITALIC = Regex("(?<!\\*)\\*([^*]+)\\*(?!\\*)")
private val INLINE_UNDER = Regex("__([^_]+)__")

// Word-class used by Ctrl+Arrow navigation and double-click word selection.
// Splits the cursor positions into "stay inside the same class" runs so
// `next_word/prev_word` mirrors IntelliJ/Android Studio: punctuation each
// forms its own word, whitespace is skipped, and alphanumerics coalesce.
private enum class WordClass { WHITESPACE, ALPHANUM, OTHER }
private fun wordClass(c: Char): WordClass = when {
    c.isWhitespace() -> WordClass.WHITESPACE
    c.isLetterOrDigit() -> WordClass.ALPHANUM
    else -> WordClass.OTHER
}
private enum class WordDir { PREV, NEXT }
private fun nextWordBoundary(text: String, pos: Int, dir: WordDir): Int {
    val n = text.length
    val p = pos.coerceIn(0, n)
    if (dir == WordDir.NEXT) {
        if (p >= n) return n
        var i = p
        val startCls = wordClass(text[i])
        while (i < n && wordClass(text[i]) == startCls) i++
        while (i < n && text[i].isWhitespace()) i++
        // Always advance at least one char so the user sees motion even
        // when the cursor already parks at the end of an alphanumeric
        // run followed only by punctuation / EOL.
        return i.coerceAtLeast(p + 1)
    } else {
        if (p <= 0) return 0
        var i = p
        while (i > 0 && text[i - 1].isWhitespace()) i--
        if (i == 0) return 0
        val endCls = wordClass(text[i - 1])
        while (i > 0 && wordClass(text[i - 1]) == endCls) i--
        return i
    }
}
// Returns [start, end) of the alphanumeric word containing `pos` in
// `text`. If `pos` lands on whitespace or punctuation, the returned
// range is empty (start == end). Used by double-click word selection.
private fun wordBoundsAt(text: String, pos: Int): IntRange {
    val n = text.length
    val p = pos.coerceIn(0, n)
    var s = p
    while (s > 0 && wordClass(text[s - 1]) == WordClass.ALPHANUM) s--
    var e = p
    while (e < n && wordClass(text[e]) == WordClass.ALPHANUM) e++
    return s until e
}

// Text-stats payload shown in the editor footer ("words · chars · …").
// Reading time uses a 200-wpm heuristic with a 1-min minimum so a
// single-word note still reads as "~1 min".
private data class TextStats(
    val words: Int,
    val chars: Int,
    val charsNoSpaces: Int,
    val lines: Int,
    val readingMinutes: Int
)
private fun computeTextStats(text: String): TextStats {
    if (text.isEmpty()) return TextStats(0, 0, 0, 0, 0)
    val trimmed = text.trim()
    val words = if (trimmed.isEmpty()) 0 else trimmed.split(Regex("\\s+")).size
    val chars = text.length
    val charsNoSpaces = text.count { !it.isWhitespace() }
    val lines = text.count { it == '\n' } + 1
    val readingMinutes = if (words == 0) 0 else ((words + 199) / 200).coerceAtLeast(1)
    return TextStats(words, chars, charsNoSpaces, lines, readingMinutes)
}

// Find/Replace state owned by NotesScreen and rendered by EditorFindBar.
private data class FindState(
    val open: Boolean = false,
    val replaceShown: Boolean = false,
    val query: String = "",
    val caseSensitive: Boolean = false,
    val replaceText: String = "",
    val matchIndex: Int = -1
)

// Pure helper that returns all non-overlapping match ranges for `query`
// in `text`. Case-insensitive matches fold both strings via lowercase().
private fun findMatches(text: String, query: String, caseSensitive: Boolean): List<IntRange> {
    if (query.isEmpty() || text.isEmpty()) return emptyList()
    val hay = if (caseSensitive) text else text.lowercase()
    val needle = if (caseSensitive) query else query.lowercase()
    if (needle.isEmpty()) return emptyList()
    val out = mutableListOf<IntRange>()
    var i = 0
    val step = needle.length.coerceAtLeast(1)
    while (i <= hay.length - needle.length) {
        if (hay.regionMatches(i, needle, 0, needle.length)) {
            out.add(i until i + needle.length)
            i += step
        } else {
            i += 1
        }
    }
    return out
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
    onOpenBibleReference: (book: String, chapter: Int?, verse: Int?) -> Unit = { _, _, _ -> },
    onHoverBibleReference: (BibleReferenceSelection?) -> Unit = { _ -> }
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
        findState = if (findState.open) {
            FindState()
        } else {
            findState.copy(open = true, replaceShown = replaceMode, matchIndex = -1)
        }
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
                            onInsertDate = { insertDate() }
                        )

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
                            onTapReference = { ref ->
                                ref?.let {
                                    onOpenBibleReference(it.book, it.chapter, it.verse)
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
                                handleEditorShortcut(
                                    event = event,
                                    isFindOpen = findState.open,
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
                                    onCloseFind = {
                                        findState = findState.copy(
                                            open = false,
                                            replaceShown = false,
                                            matchIndex = -1
                                        )
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
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
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
                AlertDialog(
                    onDismissRequest = { deleteCandidate = null },
                    title = { Text("Delete note?") },
                    text = {
                        Text(
                            "\"${note.title.ifBlank { note.fileName }}\" will be permanently " +
                                "deleted from your notes. This cannot be undone."
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = { doDelete(note) }) {
                            Text("Delete", color = MaterialTheme.colorScheme.error)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = {
                            SoundManager.play(SoundEvent.Click)
                            deleteCandidate = null
                        }) {
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
                    }
                )
            }
        }
}


/**
 * Word-style "Insert reference" picker. The user types a book name into a
 * field with autocomplete (suggestions filtered from the bundled Bible),
 * optionally narrows to a chapter / verse, and confirms with Insert or
 * Enter. Inserts `$Book$C$V ` / `$Book$C ` / `$Book ` at the caret.
 */
@Composable
private fun ReferenceInsertDialog(
    initialKind: ReferenceKind,
    onDismiss: () -> Unit,
    onInsert: (String) -> Unit
) {
    var kind by remember { mutableStateOf(initialKind) }
    var bookQuery by remember { mutableStateOf("") }
    var chapterText by remember { mutableStateOf("1") }
    var verseText by remember { mutableStateOf("1") }
    var showSuggestions by remember { mutableStateOf(false) }

    val allBooks = BibleRepository.books
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

    val insertText = buildString {
        append('$')
        append(selectedBook?.name ?: query)
        if (kind != ReferenceKind.BOOK) {
            append('$')
            append(chapterNumber ?: 1)
        }
        if (kind == ReferenceKind.VERSE) {
            append('$')
            append(verseNumber ?: 1)
        }
        append(' ')
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Insert Bible reference") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
            }
        },
        confirmButton = {
            TextButton(
                enabled = canInsert,
                onClick = {
                    SoundManager.play(SoundEvent.Click)
                    onInsert(insertText)
                }
            ) {
                Text("Insert")
            }
        },
        dismissButton = {
            TextButton(onClick = {
                SoundManager.play(SoundEvent.Click)
                onDismiss()
            }) {
                Text("Cancel")
            }
        }
    )
}


@Composable
private fun EditorHeader(
    note: ParsedNote,
    saving: Boolean,
    onSave: () -> Unit,
    onExport: () -> Unit,
    onNew: (() -> Unit)?,
    onDelete: (() -> Unit)?,
    onBack: (() -> Unit)?
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = note.title.ifBlank { note.fileName },
                style = MaterialTheme.typography.titleLarge
            )
            Text(
                text = note.fileName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (onBack != null) {
                ToolbarTip(label = "Back to previous screen", shortcut = "Ctrl+W") {
                    Button(onClick = onBack) {
                        Icon(RibbonIcons.Back, contentDescription = null, modifier = Modifier.size(16.dp))
                        Text("Back", modifier = Modifier.padding(start = 6.dp))
                    }
                }
            }
            ToolbarTip(label = "Export note as PDF", shortcut = "Ctrl+P") {
                Button(onClick = onExport) {
                    Icon(RibbonIcons.Export, contentDescription = null, modifier = Modifier.size(16.dp))
                    Text("Export", modifier = Modifier.padding(start = 6.dp))
                }
            }
            ToolbarTip(label = "Save note", shortcut = "Ctrl+S") {
                Button(onClick = onSave) {
                    Icon(RibbonIcons.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                    Text(if (saving) "Saving..." else "Save", modifier = Modifier.padding(start = 6.dp))
                }
            }
            if (onNew != null) {
                ToolbarTip(label = "Create a new note") {
                    Button(onClick = onNew) {
                        Icon(RibbonIcons.New, contentDescription = null, modifier = Modifier.size(16.dp))
                        Text("New", modifier = Modifier.padding(start = 6.dp))
                    }
                }
            }
            if (onDelete != null) {
                ToolbarTip(label = "Delete note") {
                    Button(
                        onClick = onDelete,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError
                        )
                    ) {
                        Icon(RibbonIcons.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                        Text("Delete", modifier = Modifier.padding(start = 6.dp))
                    }
                }
            }
        }
    }
}


@Composable
private fun EditorToolbar(
    editorValue: TextFieldValue,
    canUndo: Boolean,
    canRedo: Boolean,
    autoContinueLists: Boolean,
    onEditorValueChange: (TextFieldValue) -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onToggleOrientation: () -> Unit,
    onToggleAutoContinue: () -> Unit,
    onCopy: () -> Unit,
    onCut: () -> Unit,
    onPaste: () -> Unit,
    onToggleFind: () -> Unit,
    onSelectAll: () -> Unit,
    onRemoveColor: () -> Unit,
    onClearFormatting: () -> Unit,
    /**
     * Open the Bible-reference picker dialog pre-set to the given
     * granularity. The dialog lets the user type a book name (with
     * autocomplete) and choose chapter / verse before inserting the
     * `$Book$C$V` / `$Book$C` / `$Book` line at the caret.
     */
    onOpenReferencePicker: (ReferenceKind) -> Unit,
    onInsertDate: () -> Unit
) {
    val dividerColor = MaterialTheme.colorScheme.outlineVariant
    // Cut / Copy act on the current selection and disable when the caret
    // is collapsed (no selected text to take), like Word's clipboard
    // buttons. Paste is always available.
    val canClip = !editorValue.selection.collapsed
    // Paragraph style at the caret, mirrored in the Styles dropdown.
    val currentStyle = currentBlockStyle(editorValue)
    // Which ribbon tab is expanded. Word remembers the last tab; this
    // keeps the user's choice for the lifetime of the editor screen.
    var selectedTab by remember { mutableStateOf(RibbonTab.HOME) }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        RibbonTabBar(
            selected = selectedTab,
            onSelect = { selectedTab = it }
        )
        HorizontalDivider(color = dividerColor.copy(alpha = 0.6f))

        when (selectedTab) {
            // -----------------------------------------------------------------
            // Home — History · Clipboard · Edit and Style · Highlight ·
            // Format (Word: Home > Clipboard + Font).
            // -----------------------------------------------------------------
            RibbonTab.HOME -> {
                ToolbarRow {
                    ToolbarGroup("History") {
                        ToolbarActionButton(icon = RibbonIcons.Undo, enabled = canUndo, tooltip = "Undo", shortcut = "Ctrl+Z", onClick = onUndo)
                        ToolbarActionButton(icon = RibbonIcons.Redo, enabled = canRedo, tooltip = "Redo", shortcut = "Ctrl+Shift+Z", onClick = onRedo)
                    }
                    ToolbarDivider(dividerColor)

                    ToolbarGroup("Clipboard") {
                        ToolbarActionButton(icon = RibbonIcons.Cut, enabled = canClip, tooltip = "Cut selection", shortcut = "Ctrl+X", onClick = onCut)
                        ToolbarActionButton(icon = RibbonIcons.Copy, enabled = canClip, tooltip = "Copy selection", shortcut = "Ctrl+C", onClick = onCopy)
                        ToolbarActionButton(icon = RibbonIcons.Paste, tooltip = "Paste from clipboard", shortcut = "Ctrl+V", onClick = onPaste)
                    }
                    ToolbarDivider(dividerColor)

                    ToolbarGroup("Edit") {
                        ToolbarActionButton(icon = RibbonIcons.Find, tooltip = "Find in note", shortcut = "Ctrl+F", onClick = onToggleFind)
                        ToolbarActionButton(icon = RibbonIcons.SelectAll, tooltip = "Select all text", shortcut = "Ctrl+A", onClick = onSelectAll)
                    }
                }
                HorizontalDivider(color = dividerColor)

                ToolbarRow {
                    ToolbarGroup("Style") {
                        InlineButton("B", bold = true, tooltip = "Bold", shortcut = "Ctrl+B") { onEditorValueChange(toggleWrap(editorValue, "**")) }
                        InlineButton("I", italic = true, tooltip = "Italic", shortcut = "Ctrl+I") { onEditorValueChange(toggleWrap(editorValue, "*")) }
                        InlineButton("U", underline = true, tooltip = "Underline", shortcut = "Ctrl+U") { onEditorValueChange(toggleWrap(editorValue, "__")) }
                    }
                    ToolbarDivider(dividerColor)

                    ToolbarGroup("Highlight") {
                        listOf(
                            ColorMark(Color(0xFFFFD54F), "#FFD54F", "Yellow"),
                            ColorMark(Color(0xFF64B5F6), "#64B5F6", "Blue"),
                            ColorMark(Color(0xFF81C784), "#81C784", "Green"),
                            ColorMark(Color(0xFFBA68C8), "#BA68C8", "Purple"),
                            ColorMark(Color(0xFFE57373), "#E57373", "Red")
                        ).forEach { mark ->
                            ColorDot(
                                color = mark.color,
                                tooltip = "Highlight ${mark.name}",
                                onClick = {
                                    onEditorValueChange(toggleColoredQuote(editorValue, mark.hex))
                                }
                            )
                        }
                        // Word's "No colour": strips [#hex] markers, keeps the quote.
                        NoColorDot(tooltip = "Remove highlight color", onClick = onRemoveColor)
                    }
                    ToolbarDivider(dividerColor)

                    ToolbarGroup("Format") {
                        ToolbarActionButton(
                            icon = RibbonIcons.ClearFormat,
                            tooltip = "Clear formatting",
                            onClick = onClearFormatting
                        )
                    }
                }
            }

            // -----------------------------------------------------------------
            // Insert — Bible references & date (Word: Insert > Text).
            // Each reference button opens the target picker pre-set to its
            // granularity (verse / chapter / book) so the user can select
            // the actual book, chapter and verse before inserting.
            // -----------------------------------------------------------------
            RibbonTab.INSERT -> {
                ToolbarRow {
                    ToolbarGroup("Insert") {
                        StyleButton(icon = RibbonIcons.Reference, accent = true, tooltip = "Insert Bible reference", shortcut = "Ctrl+K") {
                            onOpenReferencePicker(ReferenceKind.VERSE)
                        }
                        StyleButton(icon = RibbonIcons.Chapter, accent = true, tooltip = "Insert chapter reference") {
                            onOpenReferencePicker(ReferenceKind.CHAPTER)
                        }
                        StyleButton(icon = RibbonIcons.Book, accent = true, tooltip = "Insert book reference", shortcut = "Ctrl+Shift+K") {
                            onOpenReferencePicker(ReferenceKind.BOOK)
                        }
                        StyleButton(icon = RibbonIcons.Date, accent = true, tooltip = "Insert today's date") {
                            onInsertDate()
                        }
                    }
                }
            }

            // -----------------------------------------------------------------
            // Layout — paragraph styles, block styles & text direction
            // (Word: Layout > Paragraph).
            // -----------------------------------------------------------------
            RibbonTab.LAYOUT -> {
                ToolbarRow {
                    ToolbarGroup("Styles") {
                        StyleDropdown(
                            currentStyle = currentStyle,
                            onStyleSelected = { style ->
                                onEditorValueChange(applyBlockStyle(editorValue, style))
                            }
                        )
                    }
                    ToolbarDivider(dividerColor)

                    ToolbarGroup("Block") {
                        StyleButton(icon = RibbonIcons.Heading1, tooltip = "Heading 1") {
                            onEditorValueChange(prefixSelectedLines(editorValue, "# "))
                        }
                        StyleButton(icon = RibbonIcons.Heading2, tooltip = "Heading 2") {
                            onEditorValueChange(prefixSelectedLines(editorValue, "## "))
                        }
                        StyleButton(icon = RibbonIcons.Quote, tooltip = "Block quote") {
                            onEditorValueChange(prefixSelectedLines(editorValue, "> "))
                        }
                        ListButton(ordered = false, icon = RibbonIcons.BulletList, tooltip = "Bullet list") {
                            onEditorValueChange(prefixSelectedLines(editorValue, "- "))
                        }
                        ListButton(ordered = true, icon = RibbonIcons.NumberedList, tooltip = "Numbered list") {
                            onEditorValueChange(prefixSelectedLines(editorValue, "1. "))
                        }
                        ToolbarActionButton(
                            icon = RibbonIcons.AutoList,
                            label = if (autoContinueLists) "ON" else "OFF",
                            accent = autoContinueLists,
                            tooltip = "Auto-continue lists",
                            onClick = onToggleAutoContinue
                        )
                    }
                    ToolbarDivider(dividerColor)

                    ToolbarGroup("Direction") {
                        ToolbarActionButton(icon = RibbonIcons.Direction, accent = true, tooltip = "Toggle text direction", shortcut = "Ctrl+E", onClick = onToggleOrientation)
                    }
                }
            }
        }

        HorizontalDivider(color = dividerColor.copy(alpha = 0.6f))
    }
}


/**
 * One row of the 3-row editor ribbon. Each row is a horizontally
 * scrollable strip of labelled groups, so on very narrow windows (e.g.
 * the SPLIT pane) the ribbon clips gracefully instead of overflowing.
 */
@Composable
private fun ToolbarRow(
    content: @Composable RowScope.() -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
        content = content
    )
}


@Composable
private fun ToolbarGroup(
    label: String,
    content: @Composable RowScope.() -> Unit
) {
    // Word-ribbon layout: buttons on top, tiny group label underneath.
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}


@Composable
private fun ToolbarDivider(color: Color) {
    Box(
        modifier = Modifier
            .height(28.dp)
            .width(1.dp)
            .background(color.copy(alpha = 0.7f))
    )
}


/**
 * The ribbon tabs (Word-style). Only the selected tab's groups render
 * below the strip, so a single tab is visible at a time.
 */
private enum class RibbonTab(val label: String) {
    HOME("Home"),
    INSERT("Insert"),
    LAYOUT("Layout")
}


/**
 * Word-style tab strip above the ribbon rows. The selected tab renders
 * as an active pill; clicking another tab swaps the visible groups.
 */
@Composable
private fun RibbonTabBar(
    selected: RibbonTab,
    onSelect: (RibbonTab) -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        RibbonTab.entries.forEach { tab ->
            val isSelected = tab == selected
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = if (isSelected) {
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                } else {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                },
                modifier = Modifier.clickable {
                    data.SoundManager.play(data.SoundEvent.Click)
                    onSelect(tab)
                }
            ) {
                Text(
                    text = tab.label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 5.dp)
                )
            }
        }
    }
}


/**
 * Granularity of a Bible reference being inserted via the picker dialog.
 * VERSE produces `$Book$C$V`, CHAPTER produces `$Book$C`, BOOK produces
 * just `$Book`.
 */
private enum class ReferenceKind(val label: String) {
    VERSE("Verse"),
    CHAPTER("Chapter"),
    BOOK("Book")
}


/**
 * Word-style "Styles" dropdown. Shows the paragraph style at the caret
 * (Normal / H1 / H2 / Quote) and applies the chosen style's block
 * prefix to the current line — replacing any existing prefix rather
 * than stacking on top of it.
 */
@Composable
private fun StyleDropdown(
    currentStyle: NoteStyle,
    onStyleSelected: (NoteStyle) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    ToolbarTip(label = "Apply paragraph style to current line") {
        Box {
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier
                    .height(28.dp)
                    .clickable {
                        data.SoundManager.play(data.SoundEvent.Click)
                        expanded = true
                    }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        RibbonIcons.Styles,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = currentStyle.label,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "▾",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                NoteStyle.entries.forEach { style ->
                    DropdownMenuItem(
                        text = { Text(style.label) },
                        onClick = {
                            expanded = false
                            data.SoundManager.play(data.SoundEvent.Click)
                            if (style != currentStyle) onStyleSelected(style)
                        }
                    )
                }
            }
        }
    }
}


@Composable
private fun InlineButton(
    label: String,
    bold: Boolean = false,
    italic: Boolean = false,
    underline: Boolean = false,
    tooltip: String? = null,
    shortcut: String? = null,
    onClick: () -> Unit
) {
    ToolbarTip(label = tooltip ?: label, shortcut = shortcut) {
        Surface(
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        modifier = Modifier
            .height(28.dp)
            .clickable {
                data.SoundManager.play(data.SoundEvent.Click)
                onClick()
            }
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                style = TextStyle(
                    fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
                    fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal,
                    textDecoration = if (underline) TextDecoration.Underline else null
                ),
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
    }
}


@Composable
private fun StyleButton(
    label: String? = null,
    icon: ImageVector? = null,
    sizeSp: Int? = null,
    accent: Boolean = false,
    tooltip: String? = null,
    shortcut: String? = null,
    onClick: () -> Unit
) {
    ToolbarTip(label = tooltip ?: label ?: "Button", shortcut = shortcut) {
        Surface(
            shape = RoundedCornerShape(6.dp),
            color = if (accent) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier
                .height(28.dp)
                .clickable {
                    data.SoundManager.play(data.SoundEvent.Click)
                    onClick()
                }
        ) {
            Box(
                modifier = Modifier.padding(horizontal = if (icon != null) 8.dp else 10.dp),
                contentAlignment = Alignment.Center
            ) {
                if (icon != null) {
                    Icon(
                        icon,
                        contentDescription = tooltip,
                        tint = if (accent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(16.dp)
                    )
                } else {
                    Text(
                        text = label ?: "",
                        style = TextStyle(fontSize = (sizeSp ?: 13).sp),
                        color = if (accent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ToolbarTip(
    label: String,
    shortcut: String? = null,
    content: @Composable () -> Unit
) {
    // Hover tooltip for ribbon buttons. This Material3 version dropped
    // PlainTooltip, so the tooltip surface is built here on top of
    // TooltipBox (inverseSurface = the classic "dark bubble" look).
    // The optional `shortcut` renders as a small kbd-style chip under
    // the label, e.g. "Bold" + "Ctrl+B".
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(),
        state = rememberTooltipState(),
        tooltip = {
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = MaterialTheme.colorScheme.inverseSurface,
                shadowElevation = 4.dp
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.inverseOnSurface
                    )
                    if (shortcut != null) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.16f)
                        ) {
                            Text(
                                text = shortcut,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.9f),
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
            }
        }
    ) {
        content()
    }
}


@Composable
private fun ToolbarActionButton(
    label: String? = null,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    accent: Boolean = false,
    tooltip: String? = null,
    shortcut: String? = null,
    onClick: () -> Unit
) {
    val baseColor = if (accent) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
    val textColor = when {
        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
        accent -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurface
    }
    ToolbarTip(label = tooltip ?: label ?: "Button", shortcut = shortcut) {
        Surface(
            shape = RoundedCornerShape(6.dp),
            color = if (enabled) baseColor else baseColor.copy(alpha = 0.3f),
            modifier = Modifier
                .height(28.dp)
                .then(
                    if (enabled) Modifier.clickable {
                        data.SoundManager.play(data.SoundEvent.Click)
                        onClick()
                    } else Modifier
                )
        ) {
            Box(
                modifier = Modifier.padding(horizontal = if (icon != null && label == null) 8.dp else 10.dp),
                contentAlignment = Alignment.Center
            ) {
                when {
                    icon != null && label != null -> Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            icon,
                            contentDescription = tooltip,
                            tint = textColor,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(label, style = MaterialTheme.typography.labelMedium, color = textColor)
                    }

                    icon != null -> Icon(
                        icon,
                        contentDescription = tooltip,
                        tint = textColor,
                        modifier = Modifier.size(16.dp)
                    )

                    else -> Text(
                        text = label ?: "",
                        style = MaterialTheme.typography.labelLarge,
                        color = textColor
                    )
                }
            }
        }
    }
}


@Composable
private fun ListButton(
    ordered: Boolean,
    icon: ImageVector? = null,
    tooltip: String? = null,
    shortcut: String? = null,
    onClick: () -> Unit
) {
    val label = if (ordered) "1. List" else "• List"
    ToolbarTip(label = tooltip ?: label, shortcut = shortcut) {
        Surface(
            shape = RoundedCornerShape(6.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier
                .height(28.dp)
                .clickable {
                    data.SoundManager.play(data.SoundEvent.Click)
                    onClick()
                }
        ) {
            Box(
                modifier = Modifier.padding(horizontal = if (icon != null) 8.dp else 10.dp),
                contentAlignment = Alignment.Center
            ) {
                if (icon != null) {
                    Icon(
                        icon,
                        contentDescription = tooltip,
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(16.dp)
                    )
                } else {
                    Text(text = label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }
}


@Composable
private fun ColorDot(
    color: Color,
    tooltip: String? = null,
    shortcut: String? = null,
    onClick: () -> Unit
) {
    ToolbarTip(label = tooltip ?: "Highlight", shortcut = shortcut) {
        Box(
        modifier = Modifier
            .size(20.dp)
            .background(color.copy(alpha = 0.85f), CircleShape)
            .clickable {
                data.SoundManager.play(data.SoundEvent.Click)
                onClick()
            }
    )
    }
}


@Composable
private fun NoColorDot(
    tooltip: String? = null,
    shortcut: String? = null,
    onClick: () -> Unit
) {
    // Hollow circle with a diagonal slash — Word's "No colour" swatch:
    // strips [#hex] highlight markers from the selection / cursor line.
    val lineColor = MaterialTheme.colorScheme.onSurfaceVariant
    ToolbarTip(label = tooltip ?: "No colour", shortcut = shortcut) {
        Box(
        modifier = Modifier
            .size(20.dp)
            .border(1.dp, lineColor.copy(alpha = 0.8f), CircleShape)
            .clickable {
                data.SoundManager.play(data.SoundEvent.Click)
                onClick()
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawLine(
                color = lineColor,
                start = Offset(size.width * 0.22f, size.height * 0.78f),
                end = Offset(size.width * 0.78f, size.height * 0.22f),
                strokeWidth = 2.dp.toPx()
            )
        }
    }
    }
}


@Composable
private fun EditorSurface(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    visualTransformation: VisualTransformation,
    /**
     * Word-style view zoom (A− / A+). Scales the editor's rendered text
     * without touching the saved .note content. 1.0 = 100%.
     */
    fontScale: Float = 1f,
    /**
     * VerticalScroll state, hoisted from the parent so a parent-level
     * LaunchedEffect (see `pendingScrollReference` plumbing on
     * `NotesScreen`) can drive `animateScrollTo` to land on a
     * particular source offset. The editor still owns the
     * verticalScroll modifier internally — we just pass a state object
     * in instead of creating a private one here.
     */
    scrollState: ScrollState,
    /**
     * Forwards each `BasicTextField.onTextLayout` layout result upward
     * so the parent can map a source-text offset to a Y coordinate
     * (`TextLayoutResult.getLineForOffset(...)` + `getLineTop(...)`).
     * Called on every layout pass: text edits, scroll, viewport resize.
     */
    onLayoutResult: (TextLayoutResult) -> Unit = {},
    dropTarget: DragAndDropTarget,
    onTapReference: (ReferenceMatch?) -> Unit,
    onHoverBibleReference: (BibleReferenceSelection?) -> Unit,
    /**
     * Double-click word selection. `start` and `end` are source-text
     * byte offsets into `value.text`; the parent wires this through
     * `applyEditorChange` so the new TextRange goes through the
     * UndoManager / list-continuation recordChange pipeline.
     */
    onWordSelect: (start: Int, end: Int) -> Unit = { _, _ -> },
    onShortcut: (KeyEvent) -> Boolean,
    modifier: Modifier = Modifier
) {
    // Build a memoised reference index once per text edit (O(N)) instead of
    // re-running the regex over the entire note on every pointer-move frame.
    // Lookup is O(log lines) per move via binary search over `lineStarts`.
    val referenceLookup = remember(value.text) { buildReferenceLookup(value.text) }
    val cursorColor = MaterialTheme.colorScheme.primary
    val backgroundColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
    var layoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
    // Track the most recent cursor position in Box-local coordinates —
    // the hover lookup only re-runs on PointerEventType.Move, but the
    // user can scroll the editor with the mouse wheel or keyboard
    // without any pointer motion. When the text scrolls, BasicTextField
    // fires onTextLayout with a fresh TextLayoutResult; re-running the
    // lookup against that result with `lastHoverPos` keeps the
    // bible-reference hitbox anchored to the tag under the cursor
    // instead of pinned at its pre-scroll offset.
    var lastHoverPos by remember { mutableStateOf<Offset?>(null) }

    // Shared Bible-reference hover lookup: map a Box-local coordinate to
    // the active BibleReferenceSelection (or null) and signal the parent.
    // Called from both the PointerEventType.Move arm (cursor moves) and
    // the BasicTextField.onTextLayout callback (text edit / scroll / size
    // change) so the hit-box follows text scrolling past a parked cursor
    // without depending on the cursor actually moving.
    val evaluateHover: (Offset) -> Unit = hover@{ pos ->
        val layout = layoutResult
        if (layout == null) return@hover
        val displayed = layout.getOffsetForPosition(pos)
        val mapping = (visualTransformation as? NoteVisualTransformation)?.offsetMapping
        val source = mapping?.transformedToOriginal(displayed) ?: displayed
        val ref = findReferenceInLookup(
            referenceLookup,
            source.coerceIn(0, value.text.length)
        )
        onHoverBibleReference(
            ref?.let { BibleReferenceSelection(it.book, it.chapter, it.verse) }
        )
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = backgroundColor,
        modifier = modifier
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .pointerInput(visualTransformation, value.text) {
                    detectTapGestures(
                        onTap = { tapPos ->
                            val layout = layoutResult
                            if (layout != null) {
                                val displayedOffset = layout.getOffsetForPosition(tapPos)
                                val mapping = (visualTransformation as? NoteVisualTransformation)?.offsetMapping
                                val sourceOffset = mapping?.transformedToOriginal(displayedOffset)
                                    ?: displayedOffset
                                onTapReference(
                                    findReferenceInLookup(
                                        referenceLookup,
                                        sourceOffset.coerceIn(0, value.text.length)
                                    )
                                )
                            }
                        },
                        onDoubleTap = { tapPos ->
                            // Double-click selects the alphanumeric word
                            // under the cursor. `wordBoundsAt` returns an
                            // empty range when the user double-clicks on
                            // whitespace / punctuation, in which case we
                            // skip so the editor's existing
                            // BasicTextField cursor behavior wins.
                            val layout = layoutResult
                            if (layout != null) {
                                val displayedOffset = layout.getOffsetForPosition(tapPos)
                                val mapping = (visualTransformation as? NoteVisualTransformation)?.offsetMapping
                                val sourceOffset = mapping?.transformedToOriginal(displayedOffset)
                                    ?: displayedOffset
                                val range = wordBoundsAt(
                                    value.text,
                                    sourceOffset.coerceAtLeast(0)
                                )
                                if (range.first != range.last) {
                                    onWordSelect(range.first, range.last + 1)
                                }
                            }
                        }
                    )
                }
                .pointerInput(value.text, visualTransformation) {
                    awaitEachGesture {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Main)
                            when (event.type) {
                                PointerEventType.Move -> {
                                    val change = event.changes.firstOrNull()
                                    if (change != null) {
                                        lastHoverPos = change.position
                                        evaluateHover(change.position)
                                    }
                                }
                                PointerEventType.Exit -> {
                                    lastHoverPos = null
                                    onHoverBibleReference(null)
                                }
                                else -> Unit
                            }
                        }
                    }
                }
                .onPreviewKeyEvent { event -> onShortcut(event) }
                .dragAndDropTarget(
                    shouldStartDragAndDrop = { event -> event.dragData() is DragData.Text },
                    target = dropTarget
                )
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                // The scroll modifier is what makes the editor actually
                // scrollable — BasicTextField does not scroll on its own.
                // It also gives `onTextLayout` a viewport-change trigger
                // (the displayed text portion shifts), which is what
                // powers the onTextLayout-driven hover re-evaluation in
                // the outer pointerInput below. `scrollState` is hoisted
                // from the parent so cross-pane navigation can
                // animate-scroll the editor to a specific verse line.
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState),
                visualTransformation = visualTransformation,
                textStyle = run {
                    val base = MaterialTheme.typography.bodyLarge
                    base.copy(
                        fontSize = (base.fontSize.value * fontScale).sp,
                        lineHeight = (base.lineHeight.value * fontScale).sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontFamily = FontFamily.Default
                    )
                },
                cursorBrush = SolidColor(cursorColor),
                onTextLayout = { result ->
                    // Layout pass (text edit / scroll / viewport resize).
                    // Cache the new TextLayoutResult and, when the cursor
                    // is parked over the editor surface, re-run the same
                    // Bible-reference lookup against this fresh layout so
                    // the hitbox follows the visible tag during scroll.
                    // Also forward the result upward so the parent
                    // LaunchedEffect can compute Y positions for
                    // `animateScrollTo` when a NoteChip click jumps the
                    // editor to a matching verse line.
                    layoutResult = result
                    onLayoutResult(result)
                    lastHoverPos?.let { pos -> evaluateHover(pos) }
                },
                decorationBox = { inner ->
                    if (value.text.isEmpty()) {
                        val base = MaterialTheme.typography.bodyLarge
                        Text(
                            text = "Write freely. Use the toolbar to add headings, quotes, lists, colors and Bible references.",
                            style = MaterialTheme.typography.bodyLarge.copy(
                                fontSize = (base.fontSize.value * fontScale).sp,
                                lineHeight = (base.lineHeight.value * fontScale).sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                            )
                        )
                    }
                    inner()
                }
            )
        }
    }
}


@Composable
private fun EditorFooter(
    value: TextFieldValue,
    canUndo: Boolean,
    canRedo: Boolean,
    fontScale: Float,
    onFontScaleChange: (Float) -> Unit,
    onFontScaleCommit: () -> Unit,
    onEditorValueChange: (TextFieldValue) -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onToggleOrientation: () -> Unit
) {
    val onSurface = MaterialTheme.colorScheme.onSurfaceVariant
    val stats = remember(value.text) { computeTextStats(value.text) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = if (stats.words == 0) {
                "${stats.chars} chars  \u00B7  ${stats.lines} lines"
            } else {
                "${stats.words} \u00B7 ${stats.chars} chars" +
                    "\u00B7 ${stats.charsNoSpaces} no-space" +
                    "\u00B7 ${stats.lines} ln" +
                    "\u00B7 ~${stats.readingMinutes} min"
            },
            style = MaterialTheme.typography.bodySmall,
            color = onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.then(if (canUndo) Modifier.clickable { onUndo() } else Modifier)
            ) {
                Icon(
                    RibbonIcons.Undo,
                    contentDescription = null,
                    tint = if (canUndo) MaterialTheme.colorScheme.primary else onSurface.copy(alpha = 0.4f),
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    text = "Undo",
                    color = if (canUndo) MaterialTheme.colorScheme.primary else onSurface.copy(alpha = 0.4f),
                    style = MaterialTheme.typography.labelLarge
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.then(if (canRedo) Modifier.clickable { onRedo() } else Modifier)
            ) {
                Icon(
                    RibbonIcons.Redo,
                    contentDescription = null,
                    tint = if (canRedo) MaterialTheme.colorScheme.primary else onSurface.copy(alpha = 0.4f),
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    text = "Redo",
                    color = if (canRedo) MaterialTheme.colorScheme.primary else onSurface.copy(alpha = 0.4f),
                    style = MaterialTheme.typography.labelLarge
                )
            }
            Text(
                text = "LTR\u21C4RTL",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.clickable { onToggleOrientation() }
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable { onEditorValueChange(TextFieldValue("")) }
            ) {
                Icon(
                    RibbonIcons.Delete,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    text = "Clear",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelLarge
                )
            }

            // Word-style status-bar zoom: − slider +, percentage label.
            // Live-preview on drag, persisted once on release. The cluster
            // uses tight 4dp spacing and the slider shrinks first (48..90dp)
            // so the footer never clips the zoom on narrow SPLIT panes.
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "\u2212",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.clickable {
                        onFontScaleChange(fontScale - 0.1f)
                        onFontScaleCommit()
                    }
                )
                Slider(
                    value = fontScale,
                    onValueChange = onFontScaleChange,
                    onValueChangeFinished = onFontScaleCommit,
                    valueRange = ZOOM_MIN..ZOOM_MAX,
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .widthIn(min = 48.dp, max = 90.dp)
                        .height(24.dp)
                )
                Text(
                    text = "+",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.clickable {
                        onFontScaleChange(fontScale + 0.1f)
                        onFontScaleCommit()
                    }
                )
                Text(
                    text = "${(fontScale * 100).roundToInt()}%",
                    style = MaterialTheme.typography.labelMedium,
                    color = onSurface,
                    textAlign = TextAlign.End,
                    modifier = Modifier.width(38.dp)
                )
            }
        }
    }
}



// -----------------------------------------------------------------------
// Find/Replace overlay
//
// Slim banner that renders above the editor when `state.open` is true.
// Key plumbing:
//
//   * `findMatches(text, query, caseSensitive)` is a pure helper and
//     runs once per text/query/casing change via `remember(...)`.
//   * Auto-scrolling rides the editor's hoisted scroll plumbing:
//     `activeRange` changes (Next/Prev/typing) flow through a
//     `LaunchedEffect` that maps source -> display via the visual
//     mapping, then to a Y via the editor's TextLayoutResult.
//   * Ctrl+F / Ctrl+H / Enter / Shift+Enter / Esc are routed through
//     `handleEditorShortcut` from the editor's onPreviewKeyEvent so the
//     user has a single keyboard surface; the bar itself only hosts
//     mouse/touch click handlers.
//
// The bar is intentionally small: it lives between EditorToolbar and
// EditorSurface, takes ~56.dp tall when Just-Find mode or ~92.dp in
// Find+Replace mode. Pinned to the editor column, so it scrolls with
// neither pane nor the find bar; the editor keeps its scroll offset.
// -----------------------------------------------------------------------
@Composable
private fun EditorFindBar(
    state: FindState,
    text: String,
    visualTransformation: VisualTransformation,
    editorScrollState: ScrollState,
    editorLayoutResult: TextLayoutResult?,
    onStateChange: (FindState) -> Unit,
    /**
     * Replace the active match (matches[matchIndex]) with `replacement`.
     * Receives a (start, endExclusive) `IntRange` plus the replacement
     * string; the parent notes-scope handler allocs the spliced text and
     * moves the caret to the just-after position.
     */
    onReplaceCurrent: (range: IntRange, replacement: String) -> Unit,
    onReplaceAll: (matches: List<IntRange>, replacement: String) -> Unit,
    modifier: Modifier = Modifier
) {
    val matches = remember(text, state.query, state.caseSensitive) {
        findMatches(text, state.query, state.caseSensitive)
    }
    val matchCount = matches.size
    val safeIndex = when {
        matchCount == 0 -> -1
        state.matchIndex < 0 -> -1
        else -> state.matchIndex.coerceAtMost(matchCount - 1)
    }
    val activeRange = if (safeIndex in matches.indices) matches[safeIndex] else null

    // Auto-scroll to the active match when it changes (Next/Prev/typing).
    // Mirrors the same plumbing that the cross-screen pendingScrollReference
    // effect uses: source -> display via mapping, display -> line Y via
    // layout. Without this the user would see "1/12" in the count badge
    // but have no idea WHERE match #1 actually lives.
    LaunchedEffect(activeRange, editorLayoutResult, text) {
        val range = activeRange ?: return@LaunchedEffect
        val layout = editorLayoutResult ?: return@LaunchedEffect
        val mapping = (visualTransformation as? NoteVisualTransformation)?.offsetMapping
            ?: return@LaunchedEffect
        val dispStart = mapping.originalToTransformed(range.first)
            .coerceIn(0, (layout.layoutInput.text.length - 1).coerceAtLeast(0))
        val lineIdx = layout.getLineForOffset(dispStart)
            .coerceIn(0, (layout.lineCount - 1).coerceAtLeast(0))
        val lineY = layout.getLineTop(lineIdx)
        editorScrollState.animateScrollTo((lineY - 24f).coerceAtLeast(0f).toInt())
    }

    // Hoist the click/key handlers into `remember(...)` blocks keyed
    // ONLY on the captured state and matchCount. We deliberately omit
    // `onStateChange` from the keys: the parent NotesScreen builds it
    // as a plain lambda (`{ findState = it }`) without wrapping in
    // remember, so its identity changes on every editor keystroke when
    // Find is open. Including it as a key would defeat this whole
    // optimisation by forcing re-allocation on every keystroke.
    // It's safe to drop because `onStateChange = { findState = it }`
    // writes through a `MutableState<FindState>` delegate that is
    // itself `remember`'d in the parent — so a "stale" lambda captured
    // earlier still calls the right backing state at click time.
    val goPrev = remember(matchCount, state) {
        {
            if (matchCount > 0) {
                val base = if (state.matchIndex < 0) matchCount - 1 else state.matchIndex
                val next = (base - 1 + matchCount) % matchCount
                onStateChange(state.copy(matchIndex = next))
            }
        }
    }
    val goNext = remember(matchCount, state) {
        {
            if (matchCount > 0) {
                val next = if (state.matchIndex < 0) 0 else (state.matchIndex + 1) % matchCount
                onStateChange(state.copy(matchIndex = next))
            }
        }
    }
    val close = remember(state) {
        {
            onStateChange(state.copy(open = false, replaceShown = false, matchIndex = -1))
        }
    }

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.padding(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                BasicTextField(
                    value = state.query,
                    onValueChange = { q ->
                        onStateChange(state.copy(query = q, matchIndex = -1))
                    },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    singleLine = true,
                    modifier = Modifier
                        .weight(1f)
                        .onPreviewKeyEvent { event ->
                            // Esc closes the bar even when the query
                            // field has focus (the editor Box's
                            // Modifier.onPreviewKeyEvent lives on a
                            // sibling, NOT an ancestor, so its
                            // handleEditorShortcut Esc short-circuit
                            // doesn't fire from inside this TextField).
                            // Without this, the user has to either click
                            // the × button or move focus back to the
                            // editor before pressing Esc.
                            when (event.key) {
                                Key.Escape -> {
                                    close()
                                    true
                                }
                                Key.Enter -> {
                                    if (event.isShiftPressed) goPrev() else goNext()
                                    true
                                }
                                else -> false
                            }
                        },
                    decorationBox = { inner ->
                        if (state.query.isEmpty()) {
                            Text(
                                "Find\u2026",
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                )
                            )
                        }
                        inner()
                    }
                )
                Text(
                    text = when {
                        state.query.isEmpty() -> ""
                        matchCount == 0 -> "no match"
                        state.matchIndex < 0 -> matchCount.toString()
                        else -> "${safeIndex + 1}/$matchCount"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                ToolbarActionButton(icon = RibbonIcons.PrevMatch, enabled = matchCount > 0, tooltip = "Previous match", onClick = goPrev)
                ToolbarActionButton(icon = RibbonIcons.NextMatch, enabled = matchCount > 0, tooltip = "Next match", onClick = goNext)
                ToolbarActionButton(
                    label = if (state.caseSensitive) "Aa\u00B7on" else "Aa",
                    accent = state.caseSensitive,
                    tooltip = "Match case",
                    onClick = {
                        onStateChange(
                            state.copy(caseSensitive = !state.caseSensitive, matchIndex = -1)
                        )
                    }
                )
                ToolbarActionButton(icon = RibbonIcons.Close, tooltip = "Close find", onClick = close)
            }
            if (state.replaceShown) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    BasicTextField(
                        value = state.replaceText,
                        onValueChange = { r -> onStateChange(state.copy(replaceText = r)) },
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.onSurface
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        singleLine = true,
                        modifier = Modifier
                            .weight(1f)
                            // Esc from the replace field also dismisses
                            // the bar — see the matching comment on the
                            // query field for the rationale.
                            .onPreviewKeyEvent { event ->
                                if (event.key == Key.Escape) {
                                    close()
                                    true
                                } else false
                            },
                        decorationBox = { inner ->
                            if (state.replaceText.isEmpty()) {
                                Text(
                                    "Replace with\u2026",
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                    )
                                )
                            }
                            inner()
                        }
                    )
                    ToolbarActionButton(
                        icon = RibbonIcons.Replace,
                        label = "Replace",
                        enabled = activeRange != null,
                        tooltip = "Replace current match",
                        onClick = {
                            activeRange?.let { onReplaceCurrent(it, state.replaceText) }
                        }
                    )
                    ToolbarActionButton(
                        icon = RibbonIcons.ReplaceAll,
                        label = "All",
                        enabled = matchCount > 0,
                        tooltip = "Replace all matches",
                        onClick = { onReplaceAll(matches, state.replaceText) }
                    )
                }
            }
        }
    }
}


@Composable
private fun NoteFileCard(
    note: ParsedNote,
    selected: Boolean,
    onClick: () -> Unit,
    onDelete: (() -> Unit)? = null
) {
    val hoverSource = remember { MutableInteractionSource() }
    val isHovered by hoverSource.collectIsHoveredAsState()
    androidx.compose.runtime.LaunchedEffect(isHovered) {
        if (isHovered) {
            kotlinx.coroutines.delay(60)
            data.SoundManager.play(data.SoundEvent.Hover)
        }
    }

    Card(
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .hoverable(hoverSource)
            .clickable {
                data.SoundManager.play(data.SoundEvent.Click)
                onClick()
            }
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = note.title.ifBlank { note.fileName },
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    modifier = Modifier.weight(1f)
                )
                // Quick delete affordance, revealed on hover. Routes into
                // the same confirmation dialog as the EditorHeader button.
                if (isHovered && onDelete != null) {
                    Text(
                        text = "\u00D7",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier
                            .padding(start = 6.dp)
                            .clickable {
                                data.SoundManager.play(data.SoundEvent.Click)
                                onDelete()
                            }
                    )
                }
            }
            Text(
                text = note.fileName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
    }
}


// ---------------------------------------------------------------------------
// Live WYSIWYG-style transformation. Underlying source markdown is preserved
// verbatim; only the display is transformed.
//
// Hidden markers use two strategies:
//   1) Line-prefix `hiddenLen` stripping for `#`, `##`, `>`, `>>`, `>>>`
//      `-`, `\d+\.`, `RLM`, `LRM`. A real OffsetMapping tracks the
//      delta so cursor positions stay correct.
//   2) `SpanStyle(color = Color.Transparent)` is reserved for `$`
//      markers in `$Book$Chapter$Verse$` reference lines. The colored
//      quote wrapper `"…"[#hex]` is no longer transparent-styled —
//      Pass 3 strips it out of the displayed text entirely and emits
//      four MappingSpans per line (opener `"` → zero-width, inner
//      text identity, closer+bracket gap → zero-width, trailing text
//      identity) so the colored quote hugs the trailing text with no
//      horizontal gap. Save format is unchanged: the parser reads
//      the full source markdown so the round-trip is lossless.
//
// Save format is also unchanged: `NotesRepository.parseNoteFile`
// parses `NotesScreen.kt`'s output identically to the original
// markdown. The shift to no-space "text"+"trailing" in the parser is
// matching-side only — sources that begin with explicit `<space>`
// after the `[#hex]` still capture them via the regex's `\s*(.*)` and
// the new join drops the leading space to match the stripped display.
// ---------------------------------------------------------------------------

private data class NotePalette(
    val onSurface: Color,
    val onSurfaceVariant: Color,
    val primary: Color,
    val tertiary: Color,
    val faded: Color,
    val transparent: Color = Color.Transparent
)


@Composable
private fun rememberNoteVisualTransformation(fontScale: Float = 1f): VisualTransformation {
    val palette = NotePalette(
        onSurface = MaterialTheme.colorScheme.onSurface,
        onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant,
        primary = MaterialTheme.colorScheme.primary,
        tertiary = MaterialTheme.colorScheme.tertiary,
        faded = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
        transparent = Color.Transparent
    )
    // Keyed on fontScale: zoom (A− / A+) must rebuild the transformation
    // so heading/quote spans scale in lockstep with the base body text.
    return remember(fontScale) { NoteVisualTransformation(palette, fontScale) }
}


private class NoteVisualTransformation(
    private val palette: NotePalette,
    private val fontScale: Float = 1f
) : VisualTransformation {

    var offsetMapping: OffsetMapping? = null
        private set

    enum class BlockKind {
        HEADING1, HEADING2, BULLET, NUMBERED, QUOTE, QUOTE_BULLET, QUOTE_NUMBERED,
        COLORED_QUOTE, REFERENCE, PARAGRAPH, RTL_LINE, LTR_LINE
    }

    data class LineAnalysis(
        val kind: BlockKind,
        val hiddenLen: Int,
        val quoteDepth: Int = 0,
        /**
         * Hex string for COLORED_QUOTE lines (e.g. `"#FFD54F"`),
         * empty string for every other line kind (default). Making
         * the field non-nullable means consumers in
         * `applyColoredQuoteStyles` and elsewhere can pass it
         * straight to `colorFromHex(hex: String)` without an
         * `?: ""` dance; an empty hex falls through
         * `colorFromHex` to `null` and lands on the primary-color
         * fallback via `?: palette.primary`.
         */
        val quoteColorHex: String = "",
        /**
         * For COLORED_QUOTE lines — byte-length of the inner text
         * captured by `coloredQuoteRegex` group 1 (the part between the
         * two literal `"` quotes). Drives the colored-span width in
         * `applyColoredQuoteStyles` and the span decomposition in Pass 3
         * where the closing `"…"[#hex]` markup is stripped from the
         * displayed text. Stays at 0 for all other line kinds.
         */
        val innerTextLength: Int = 0,
        val isReferenceLine: Boolean = false,
        val direction: TextDirection = TextDirection.Ltr
    )

    override fun filter(text: AnnotatedString): TransformedText {
        val source = text.text

        // ----- Pass 1: classify every line independently -----
        val analyses = mutableListOf<LineAnalysis>()
        val lineEndsByIndex = mutableListOf<Int>()
        var scanPos = 0
        while (scanPos <= source.length) {
            val nlPos = source.indexOf('\n', scanPos)
            val lineEnd = if (nlPos == -1) source.length else nlPos
            val raw = source.substring(scanPos, lineEnd)
            analyses.add(analyzeLine(raw))
            lineEndsByIndex.add(lineEnd)
            if (nlPos == -1) break
            scanPos = nlPos + 1
        }

        // ----- Pass 2: assign auto-numbers to QUOTE_NUMBERED lines -----
        // Quote-prefixed lines (PLAIN QUOTE, QUOTE_BULLET, QUOTE_NUMBERED)
        // continue the sequence; anything else (paragraph, heading,
        // reference, RLM/LTR marker, etc.) resets the counter to 0.
        val assignedNumbers = IntArray(analyses.size)
        var counter = 0
        for (i in analyses.indices) {
            val kind = analyses[i].kind
            when {
                kind == BlockKind.QUOTE_NUMBERED -> {
                    counter += 1
                    assignedNumbers[i] = counter
                }
                kind == BlockKind.QUOTE || kind == BlockKind.QUOTE_BULLET -> {
                    // Chain alive; do not reset, do not increment.
                }
                else -> counter = 0
            }
        }

        // ----- Pass 3: build the displayed output & OffsetMapping -----
        val out = AnnotatedString.Builder()
        val ranges = mutableListOf<MappingSpan>()

        for (i in analyses.indices) {
            val analysis = analyses[i]
            val lineEnd = lineEndsByIndex[i]
            val lineStart = if (i == 0) 0 else lineEndsByIndex[i - 1] + 1

            // Emit a newline span between lines (skip before the first).
            if (i > 0) {
                val nlOutStart = out.length
                out.append("\n")
                ranges.add(
                    MappingSpan(
                        originalStart = lineStart - 1,
                        originalEnd = lineStart,
                        transformedStart = nlOutStart,
                        transformedEnd = nlOutStart + 1,
                        delta = 0,
                        prependedLen = 0
                    )
                )
            }

            // Synthesised display prefix that does not exist in source.
            // `mapping` records `prependedLen` so the OffsetMapping can
            // route clicks inside it to a sensible source position.
            // COLORED_QUOTE never synthesises a prefix — the visible
            // text is just the inner-stripped content of the line, so
            // the `prepended + visibleText` style call below receives
            // an empty prefix for that case.
            val prepended = when (analysis.kind) {
                BlockKind.QUOTE_NUMBERED -> "${assignedNumbers[i]}. "
                BlockKind.QUOTE_BULLET -> "\u2022 "
                else -> ""
            }
            val prependedLen = prepended.length

            val visibleStartInOut: Int
            val visibleText: String

            if (analysis.kind == BlockKind.COLORED_QUOTE) {
                // Colored quotes have a decorative wrapper of the form
                // `"inner"[#hex]trailing` in source. The wrapper has to
                // be fully stripped from rendering so the trailing text
                // hugs the colored text without a horizontal gap;
                // Compose's font-metric width applies to transparent
                // chars too, so Color.Transparent isn't enough.
                //
                // The strip is bimodal: 1 char of opener at the start,
                // 1 + bracketLen chars of closer+bracket in the MIDDLE
                // of the source line. The standard `delta = hiddenLen`
                // model can only hide a contiguous prefix or suffix,
                // so emit FOUR MappingSpans per COLORED_QUOTE line
                // instead — opener (zero-width), inner text (identity),
                // gap (zero-width), trailing (identity, optional).
                // The NoteOffsetMapping binary search handles this
                // naturally; both endpoints of the gap converge on
                // `transformedStart = visibleStartInOut + innerTextLength`
                // so cursor positions round-trip cleanly between source
                // and display coordinates.
                val rawLine = source.substring(lineStart, lineEnd)
                val cqMatch = coloredQuoteRegex.matchEntire(rawLine)
                val cqInnerText = cqMatch?.groupValues?.getOrNull(1).orEmpty()
                val cqHex = cqMatch?.groupValues?.getOrNull(2).orEmpty()
                val cqTrailing = cqMatch?.groupValues?.getOrNull(3).orEmpty()
                val bracketLen = if (cqHex.isNotEmpty()) "[#$cqHex]".length else 0

                visibleStartInOut = out.length
                out.append(cqInnerText)
                out.append(cqTrailing)
                visibleText = cqInnerText + cqTrailing

                val openerSrcEnd = lineStart + 1
                val innerSrcEnd = openerSrcEnd + cqInnerText.length
                val gapSrcEnd = innerSrcEnd + 1 + bracketLen
                val trailSrcEnd = gapSrcEnd + cqTrailing.length

                // Span 1: opening `"` → zero display chars
                ranges.add(
                    MappingSpan(
                        originalStart = lineStart,
                        originalEnd = openerSrcEnd,
                        transformedStart = visibleStartInOut,
                        transformedEnd = visibleStartInOut,
                        delta = 1
                    )
                )
                // Span 2: inner text
                ranges.add(
                    MappingSpan(
                        originalStart = openerSrcEnd,
                        originalEnd = innerSrcEnd,
                        transformedStart = visibleStartInOut,
                        transformedEnd = visibleStartInOut + cqInnerText.length,
                        delta = 0
                    )
                )
                // Span 3: closing `"` + `[#hex]` → zero display chars
                ranges.add(
                    MappingSpan(
                        originalStart = innerSrcEnd,
                        originalEnd = gapSrcEnd,
                        transformedStart = visibleStartInOut + cqInnerText.length,
                        transformedEnd = visibleStartInOut + cqInnerText.length,
                        delta = 1 + bracketLen
                    )
                )
                // Span 4: trailing text (identity, only if non-empty)
                if (cqTrailing.isNotEmpty()) {
                    ranges.add(
                        MappingSpan(
                            originalStart = gapSrcEnd,
                            originalEnd = trailSrcEnd,
                            transformedStart = visibleStartInOut + cqInnerText.length,
                            transformedEnd = visibleStartInOut + cqInnerText.length + cqTrailing.length,
                            delta = 0
                        )
                    )
                }
            } else {
                // Standard flow: optional synthesised list-marker prefix
                // (`1. ` / `• `) PLUS prefix-stripped visibleText. The
                // single MappingSpan covers the whole line; the
                // standard `delta = hiddenLen` formula in
                // NoteOffsetMapping handles the prefix-hide.
                val visibleStartInSource = lineStart + analysis.hiddenLen
                visibleText = if (visibleStartInSource < lineEnd) {
                    source.substring(visibleStartInSource, lineEnd)
                } else ""

                visibleStartInOut = out.length
                out.append(prepended)
                out.append(visibleText)

                ranges.add(
                    MappingSpan(
                        originalStart = lineStart,
                        originalEnd = lineEnd,
                        transformedStart = visibleStartInOut,
                        transformedEnd = visibleStartInOut + prependedLen + visibleText.length,
                        delta = analysis.hiddenLen,
                        prependedLen = prependedLen
                    )
                )
            }

            // Style applied over the line's full visible span (prefix + content).
            applyLineStyles(out, visibleStartInOut, prepended + visibleText, analysis)
        }

        val mapping = NoteOffsetMapping(
            spans = ranges,
            originalLength = source.length,
            transformedLength = out.length
        )
        offsetMapping = mapping
        return TransformedText(out.toAnnotatedString(), mapping)
    }

    /**
     * Classify a single line. When the line starts with an orientation
     * marker (RLM / LRM), strip it and re-classify the remainder so that
     * `‏$Book$C$V$` keeps its REFERENCE classification with RTL direction
     * applied, instead of being treated as a plain RTL line.
     */
    private fun analyzeLine(raw: String): LineAnalysis {
        if (raw.isEmpty()) return LineAnalysis(BlockKind.PARAGRAPH, hiddenLen = 0)

        if (raw.startsWith(RLM)) {
            val inner = analyzeLine(raw.removePrefix(RLM))
            return inner.copy(
                hiddenLen = RLM.length + inner.hiddenLen,
                direction = TextDirection.Rtl
            )
        }
        if (raw.startsWith(LRM)) {
            val inner = analyzeLine(raw.removePrefix(LRM))
            return inner.copy(
                hiddenLen = LRM.length + inner.hiddenLen,
                direction = TextDirection.Ltr
            )
        }

        if (raw.startsWith("## ")) return LineAnalysis(BlockKind.HEADING2, hiddenLen = 3)
        if (raw.startsWith("# ")) return LineAnalysis(BlockKind.HEADING1, hiddenLen = 2)
        if (raw.startsWith("- ")) return LineAnalysis(BlockKind.BULLET, hiddenLen = 2)

        orderedListRegex.matchAt(raw, 0)?.let { match ->
            return LineAnalysis(BlockKind.NUMBERED, hiddenLen = match.value.length)
        }

        if (raw.startsWith(">")) {
            val depth = raw.takeWhile { it == '>' }.length.coerceAtLeast(1)
            // After the `>` chain we may have `.` or `#` for a list item,
            // or just regular content for a plain quote.
            val afterChain = if (depth < raw.length) raw[depth] else ' '
            when (afterChain) {
                '.' -> {
                    var hidden = depth + 1
                    if (hidden < raw.length && raw[hidden] == ' ') hidden += 1
                    return LineAnalysis(
                        kind = BlockKind.QUOTE_BULLET,
                        hiddenLen = hidden,
                        quoteDepth = depth
                    )
                }
                '#' -> {
                    var hidden = depth + 1
                    if (hidden < raw.length && raw[hidden] == ' ') hidden += 1
                    return LineAnalysis(
                        kind = BlockKind.QUOTE_NUMBERED,
                        hiddenLen = hidden,
                        quoteDepth = depth
                    )
                }
                else -> {
                    var hidden = depth
                    if (hidden < raw.length && raw[hidden] == ' ') hidden += 1
                    return LineAnalysis(
                        kind = BlockKind.QUOTE,
                        hiddenLen = hidden,
                        quoteDepth = depth
                    )
                }
            }
        }

        referenceLineRegex.matchEntire(raw)?.let {
            return LineAnalysis(BlockKind.REFERENCE, hiddenLen = 0, isReferenceLine = true)
        }

        coloredQuoteRegex.matchEntire(raw)?.let { match ->
            val innerText = match.groupValues[1]
            val hex = match.groupValues[2]
            return LineAnalysis(
                kind = BlockKind.COLORED_QUOTE,
                hiddenLen = 0,
                quoteColorHex = hex,
                innerTextLength = innerText.length
            )
        }

        return LineAnalysis(BlockKind.PARAGRAPH, hiddenLen = 0)
    }

    private fun applyLineStyles(
        builder: AnnotatedString.Builder,
        visibleStart: Int,
        visibleText: String,
        analysis: LineAnalysis
    ) {
        if (visibleText.isEmpty()) return
        val visibleEnd = visibleStart + visibleText.length

        paragraphFor(analysis)?.let { builder.addStyle(it, visibleStart, visibleEnd) }
        spanFor(analysis)?.let { builder.addStyle(it, visibleStart, visibleEnd) }

        when (analysis.kind) {
            BlockKind.COLORED_QUOTE -> applyColoredQuoteStyles(builder, visibleStart, visibleText, analysis)
            BlockKind.REFERENCE -> applyReferenceStyles(builder, visibleText, visibleStart)
            else -> { /* nothing */ }
        }

        applyInlineEmphasis(builder, visibleText, visibleStart)
    }

    /**
     * Apply the hex colour span to the inner text of a colored quote
     * line. The visibleText passed in is already the stripped form (the
     * `"…"[#hex]` wrapper has been removed by the COLORED_QUOTE branch
     * in `filter`), so the inner text occupies the entire `visibleText`
     * up to `analysis.innerTextLength` bytes; any trailing text after
     * that is plain. No `Color.Transparent` spans are emitted here —
     * they're unnecessary because the decorative chars aren't in the
     * displayed text anymore.
     */
    private fun applyColoredQuoteStyles(
        builder: AnnotatedString.Builder,
        visibleStart: Int,
        visibleText: String,
        analysis: LineAnalysis
    ) {
        val innerLen = analysis.innerTextLength
        if (innerLen == 0 || visibleText.length < innerLen) return

        // `quoteColorHex` is non-nullable on LineAnalysis (defaults to ""
        // for non-COLORED_QUOTE lines), so this passes straight through
        // to `colorFromHex` without a coalesce dance. An empty hex
        // returns null from `colorFromHex` and falls through to the
        // primary-color fallback via the `?:` below.
        val color = colorFromHex(analysis.quoteColorHex) ?: palette.primary
        builder.addStyle(
            SpanStyle(color = color),
            visibleStart,
            visibleStart + innerLen
        )
    }

    /**
     * Marker-style hiding: every `$` in `visibleText` becomes invisible
     * (Color.Transparent) but stays in the source text so the offset
     * mapping for that line is identity and click-to-navigate works on
     * any byte of the reference line.
     */
    private fun applyReferenceStyles(
        builder: AnnotatedString.Builder,
        visibleText: String,
        visibleStart: Int
    ) {
        var i = 0
        while (i < visibleText.length) {
            if (visibleText[i] == '$') {
                builder.addStyle(
                    SpanStyle(color = palette.transparent),
                    visibleStart + i, visibleStart + i + 1
                )
            }
            i += 1
        }
    }

    private fun spanFor(analysis: LineAnalysis): SpanStyle? = when (analysis.kind) {
        BlockKind.HEADING1 -> SpanStyle(fontWeight = FontWeight.Bold, fontSize = (28 * fontScale).sp, color = palette.onSurface)
        BlockKind.HEADING2 -> SpanStyle(fontWeight = FontWeight.Bold, fontSize = (22 * fontScale).sp, color = palette.onSurface)
        BlockKind.QUOTE -> SpanStyle(
            fontStyle = FontStyle.Italic,
            fontSize = (16 * fontScale).sp,
            color = palette.onSurface.copy(alpha = 0.92f)
        )
        BlockKind.QUOTE_BULLET -> SpanStyle(
            color = palette.onSurface.copy(alpha = 0.92f),
            fontSize = (16 * fontScale).sp
        )
        BlockKind.QUOTE_NUMBERED -> SpanStyle(
            color = palette.primary,
            fontWeight = FontWeight.SemiBold,
            fontSize = (16 * fontScale).sp
        )
        BlockKind.COLORED_QUOTE -> SpanStyle(fontStyle = FontStyle.Italic, fontSize = (16 * fontScale).sp, color = palette.onSurface)
        // Reference-line tag style. The user's tag-press bug was that the
        // `$Book$1$1` rendering blended into surrounding prose — tertiary
        // text in SemiBold is barely distinguishable from a normal
        // paragraph. This pumps the visual affordance up: primary accent
        // color (instead of muted tertiary), full Bold (instead of
        // SemiBold), and underline decoration (the universal "this is a
        // hyperlink" cue). We deliberately do NOT add a chip background
        // tint here — `NoteVisualPalette` doesn't expose
        // `primaryContainer`, and adding `MaterialTheme.colorScheme` reads
        // here would force the visual transform to be @Composable-aware.
        // The companion applyReferenceStyles() also paints the `$`
        // markers transparent so only the readable book/chapter/verse
        // text carries the bold + accent + underline cue. Mode-agnostic
        // and works in both light and dark schemes.
        BlockKind.REFERENCE -> SpanStyle(
            fontSize = 15.sp,
            color = palette.primary,
            fontWeight = FontWeight.Bold,
            textDecoration = TextDecoration.Underline
        )
        BlockKind.BULLET, BlockKind.NUMBERED, BlockKind.RTL_LINE, BlockKind.LTR_LINE ->
            SpanStyle(fontSize = 15.sp, color = palette.onSurface)
        BlockKind.PARAGRAPH -> SpanStyle(fontSize = 16.sp, color = palette.onSurface)
    }

    private fun paragraphFor(analysis: LineAnalysis): ParagraphStyle? {
        val quoteDepthDp = analysis.quoteDepth * 24
        // Quote-prefixed list items start with the synthesised glyph
        // (e.g. `1. ` or `• `) directly, so we leave the first-line
        // indent at zero on those lines; the quote-depth indent still
        // applies on wrap.
        val firstIndentDp = when (analysis.kind) {
            BlockKind.QUOTE -> quoteDepthDp
            BlockKind.QUOTE_BULLET, BlockKind.QUOTE_NUMBERED -> 0
            BlockKind.BULLET, BlockKind.NUMBERED -> 20
            else -> quoteDepthDp
        }
        val restIndentDp = when (analysis.kind) {
            BlockKind.QUOTE -> quoteDepthDp
            BlockKind.QUOTE_BULLET, BlockKind.QUOTE_NUMBERED -> quoteDepthDp
            BlockKind.BULLET, BlockKind.NUMBERED -> 20
            else -> quoteDepthDp
        }
        val dir = analysis.direction
        return when (analysis.kind) {
            BlockKind.HEADING1 -> ParagraphStyle(
                lineHeight = 34.sp,
                textIndent = TextIndent(firstLine = firstIndentDp.sp, restLine = restIndentDp.sp),
                textDirection = dir
            )
            BlockKind.HEADING2 -> ParagraphStyle(
                lineHeight = 30.sp,
                textIndent = TextIndent(firstLine = firstIndentDp.sp, restLine = restIndentDp.sp),
                textDirection = dir
            )
            BlockKind.QUOTE -> ParagraphStyle(
                lineHeight = 22.sp,
                textIndent = TextIndent(firstLine = firstIndentDp.sp, restLine = restIndentDp.sp),
                textDirection = dir
            )
            BlockKind.QUOTE_BULLET, BlockKind.QUOTE_NUMBERED -> ParagraphStyle(
                lineHeight = 22.sp,
                textIndent = TextIndent(firstLine = firstIndentDp.sp, restLine = restIndentDp.sp),
                textDirection = dir
            )
            BlockKind.COLORED_QUOTE -> ParagraphStyle(
                lineHeight = 22.sp,
                textIndent = TextIndent(firstLine = 24.sp, restLine = 24.sp),
                textDirection = dir
            )
            BlockKind.REFERENCE -> ParagraphStyle(
                lineHeight = 22.sp,
                textIndent = TextIndent(firstLine = 0.sp, restLine = 0.sp),
                textDirection = dir
            )
            BlockKind.BULLET, BlockKind.NUMBERED -> ParagraphStyle(
                lineHeight = 22.sp,
                textIndent = TextIndent(firstLine = firstIndentDp.sp, restLine = restIndentDp.sp),
                textDirection = dir
            )
            BlockKind.RTL_LINE, BlockKind.LTR_LINE, BlockKind.PARAGRAPH -> ParagraphStyle(
                lineHeight = 24.sp,
                textIndent = TextIndent(firstLine = firstIndentDp.sp, restLine = restIndentDp.sp),
                textDirection = dir
            )
        }
    }

    private fun applyInlineEmphasis(
        builder: AnnotatedString.Builder,
        visible: String,
        offset: Int
    ) {
        INLINE_BOLD.findAll(visible).forEach { match ->
            val s = match.range.first + 2
            val e = match.range.last - 1
            if (s < e) builder.addStyle(SpanStyle(fontWeight = FontWeight.Bold), offset + s, offset + e)
        }
        INLINE_ITALIC.findAll(visible).forEach { match ->
            val s = match.range.first + 1
            val e = match.range.last - 1
            if (s < e) builder.addStyle(SpanStyle(fontStyle = FontStyle.Italic), offset + s, offset + e)
        }
        INLINE_UNDER.findAll(visible).forEach { match ->
            val s = match.range.first + 2
            val e = match.range.last - 1
            if (s < e) builder.addStyle(
                SpanStyle(textDecoration = TextDecoration.Underline),
                offset + s, offset + e
            )
        }
    }
}


private data class MappingSpan(
    val originalStart: Int,
    val originalEnd: Int,
    val transformedStart: Int,
    val transformedEnd: Int,
    val delta: Int,
    val prependedLen: Int = 0
)


/**
 * Bidirectional offset mapping built from a list of `MappingSpan`s. The
 * spans cover the original text contiguously (one per visible line plus
 * one per newline) and carry `delta = originalEnd - originalStart -
 * (transformedEnd - transformedStart)` so the within-span formulas know
 * how many source characters were hidden on a line.
 */
private class NoteOffsetMapping(
    private val spans: List<MappingSpan>,
    private val originalLength: Int,
    private val transformedLength: Int
) : OffsetMapping {

    override fun originalToTransformed(offset: Int): Int {
        if (spans.isEmpty()) return offset.coerceIn(0, transformedLength)
        val clamped = offset.coerceIn(0, originalLength)
        val span = spans[findSpanIndexForOriginal(clamped)]
        if (clamped >= span.originalEnd) {
            val carry = clamped - span.originalEnd
            return (span.transformedEnd + carry).coerceAtMost(transformedLength)
        }
        // Stripped prefix region: clicks inside the hidden `>#` (etc.)
        // snap to the start of the synthesised display prefix (call it
        // "before the 1."), so the user's natural intent — to put the
        // caret nearest the visible content — wins.
        if (clamped < span.originalStart + span.delta) {
            return span.transformedStart.coerceIn(0, transformedLength)
        }
        val within = (clamped - span.originalStart) - span.delta
        return (span.transformedStart + span.prependedLen + within).coerceIn(0, transformedLength)
    }

    override fun transformedToOriginal(offset: Int): Int {
        if (spans.isEmpty()) return offset.coerceIn(0, originalLength)
        val clamped = offset.coerceIn(0, transformedLength)
        val span = spans[findSpanIndexForTransformed(clamped)]
        if (clamped >= span.transformedEnd) {
            val carry = clamped - span.transformedEnd
            return (span.originalEnd + carry).coerceAtMost(originalLength)
        }
        // Synthesised display prefix (`1. `, `• `): any click inside it
        // maps to just-after the stripped markdown prefix in source —
        // the user can't actually edit into the synthesised area, so
        // we collapse it to the same point as if they clicked on the
        // first character of the visible content.
        if (clamped < span.transformedStart + span.prependedLen) {
            return (span.originalStart + span.delta).coerceIn(0, originalLength)
        }
        val within = (clamped - span.transformedStart) - span.prependedLen
        return (span.originalStart + span.delta + within).coerceIn(0, originalLength)
    }

    private fun findSpanIndexForOriginal(offset: Int): Int {
        var lo = 0
        var hi = spans.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val span = spans[mid]
            when {
                offset < span.originalStart -> hi = mid - 1
                offset > span.originalEnd -> lo = mid + 1
                else -> return mid
            }
        }
        return (lo - 1).coerceAtLeast(0)
    }

    private fun findSpanIndexForTransformed(offset: Int): Int {
        var lo = 0
        var hi = spans.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val span = spans[mid]
            when {
                offset < span.transformedStart -> hi = mid - 1
                offset > span.transformedEnd -> lo = mid + 1
                else -> return mid
            }
        }
        return (lo - 1).coerceAtLeast(0)
    }
}


private data class ColorMark(val color: Color, val hex: String, val name: String)


private fun colorFromHex(hex: String): Color? {
    val cleaned = hex.removePrefix("#")
    val value = runCatching { cleaned.toLong(16) }.getOrNull() ?: return null
    return when (cleaned.length) {
        6 -> Color(
            red = ((value shr 16) and 0xFF).toFloat() / 255f,
            green = ((value shr 8) and 0xFF).toFloat() / 255f,
            blue = (value and 0xFF).toFloat() / 255f
        )
        8 -> Color(
            alpha = ((value shr 24) and 0xFF).toFloat() / 255f,
            red = ((value shr 16) and 0xFF).toFloat() / 255f,
            green = ((value shr 8) and 0xFF).toFloat() / 255f,
            blue = (value and 0xFF).toFloat() / 255f
        )
        else -> null
    }
}


/**
 * Walk the raw source text line-by-line and return the source offset of
 * the first line matching `$Book$Chapter$Verse$` for the given (book,
 * chapter, verse). The match is parsed by [referenceLineRegex] (the
 * editor surface uses the same regex for tap detection, so the two
 * systems agree on what counts as a reference line). Returns null when
 * the note does not mention the verse at all.
 *
 * Used by NotesScreen's `pendingScrollReference` LaunchedEffect to
 * land the editor at the line a NoteChip click "took us to".
 */
private fun findFirstReferenceOffset(
    source: String,
    book: String,
    chapter: Int?,
    verse: Int?
): Int? {
    var pos = 0
    while (pos <= source.length) {
        val nl = source.indexOf('\n', pos)
        val lineEnd = if (nl == -1) source.length else nl
        if (lineEnd > pos) {
            val line = source.substring(pos, lineEnd)
            val match = referenceLineRegex.matchEntire(line)
            if (match != null) {
                val lineBook = match.groupValues[1].trim()
                val lineChapter = match.groupValues[2].toIntOrNull()
                val lineVerse = match.groupValues[3].toIntOrNull()
                // Match the requested granularity: verse refs require the
                // chapter+verse pair, chapter refs require the chapter,
                // book refs match on the book name alone.
                val chapterOk = chapter == null || lineChapter == chapter
                val verseOk = verse == null || lineVerse == verse
                if (lineBook.equals(book, ignoreCase = true) && chapterOk && verseOk) {
                    return pos
                }
            }
        }
        if (nl == -1) break
        pos = nl + 1
    }
    return null
}


// ---------------------------------------------------------------------------
// Editor mutations (operate on raw markdown text)
// ---------------------------------------------------------------------------

private fun insertAtSelection(current: TextFieldValue, insertion: String): TextFieldValue {
    val start = minOf(current.selection.start, current.selection.end).coerceIn(0, current.text.length)
    val end = maxOf(current.selection.start, current.selection.end).coerceIn(0, current.text.length)
    val newText = buildString {
        append(current.text.substring(0, start))
        append(insertion)
        append(current.text.substring(end))
    }
    val cursor = start + insertion.length
    return current.copy(text = newText, selection = TextRange(cursor, cursor))
}


private fun toggleWrap(current: TextFieldValue, marker: String): TextFieldValue {
    val start = minOf(current.selection.start, current.selection.end).coerceIn(0, current.text.length)
    val end = maxOf(current.selection.start, current.selection.end).coerceIn(0, current.text.length)
    if (start == end) return insertAtSelection(current, "$marker$marker")

    val selected = current.text.substring(start, end)
    val wrapped = if (selected.startsWith(marker) && selected.endsWith(marker)) {
        selected.removePrefix(marker).removeSuffix(marker)
    } else {
        "$marker$selected$marker"
    }
    val newText = buildString {
        append(current.text.substring(0, start))
        append(wrapped)
        append(current.text.substring(end))
    }
    val cursor = start + wrapped.length
    return current.copy(text = newText, selection = TextRange(cursor, cursor))
}


private fun prefixSelectedLines(current: TextFieldValue, prefix: String): TextFieldValue {
    val start = minOf(current.selection.start, current.selection.end).coerceIn(0, current.text.length)
    val end = maxOf(current.selection.start, current.selection.end).coerceIn(0, current.text.length)

    if (start == end) {
        val lineStart = current.text.lastIndexOf('\n', start - 1).let { if (it < 0) 0 else it + 1 }
        val newText = buildString {
            append(current.text.substring(0, lineStart))
            append(prefix)
            append(current.text.substring(lineStart))
        }
        val cursor = start + prefix.length
        return current.copy(text = newText, selection = TextRange(cursor, cursor))
    }

    val block = current.text.substring(start, end)
    val prefixed = block.lines().joinToString("\n") { line ->
        if (line.isBlank()) line else prefix + line
    }
    val newText = buildString {
        append(current.text.substring(0, start))
        append(prefixed)
        append(current.text.substring(end))
    }
    val cursor = start + prefixed.length
    return current.copy(text = newText, selection = TextRange(start, cursor))
}


/**
 * Paragraph styles selectable from the Word-style "Styles" dropdown.
 * Each style maps to the block prefix written into the source text.
 */
private enum class NoteStyle(val label: String, val prefix: String) {
    NORMAL("Normal", ""),
    H1("H1", "# "),
    H2("H2", "## "),
    QUOTE("Quote", "> ")
}


/**
 * Returns the paragraph style currently applied to the line holding the
 * cursor (or the start of a multi-line selection), so the Styles
 * dropdown can mirror what Word shows at the caret.
 */
private fun currentBlockStyle(current: TextFieldValue): NoteStyle {
    val caret = minOf(current.selection.start, current.selection.end).coerceIn(0, current.text.length)
    val lineStart = current.text.lastIndexOf('\n', caret - 1).let { if (it < 0) 0 else it + 1 }
    val lineEnd = current.text.indexOf('\n', caret).let { if (it < 0) current.text.length else it }
    val line = current.text.substring(lineStart, lineEnd)
        .removePrefix(RLM)
        .removePrefix(LRM)
    return when {
        line.startsWith("## ") -> NoteStyle.H2
        line.startsWith("# ") -> NoteStyle.H1
        line.startsWith(">") -> NoteStyle.QUOTE
        else -> NoteStyle.NORMAL
    }
}


/**
 * Removes every block-level prefix from a raw source line — headings,
 * quote chains (incl. `> .` / `> #` list items), bullets and numbered
 * list markers — leaving only the line's plain content. Used when
 * applying a new paragraph style so the result is idempotent
 * (re-applying the same style never stacks prefixes).
 */
private fun stripBlockPrefix(line: String): String {
    // Preserve an RTL/LTR direction marker (RLM/LRM) that may sit in
    // front of the block prefix, e.g. RLM + "# text" from toggling the
    // line orientation on a heading. The marker is re-appended after the
    // prefix is stripped so applying a style keeps the line's direction.
    val directionMarker = when {
        line.startsWith(RLM) -> RLM
        line.startsWith(LRM) -> LRM
        else -> ""
    }
    var result = if (directionMarker.isNotEmpty()) line.removePrefix(directionMarker) else line

    if (result.startsWith("## ")) result = result.removePrefix("## ")
    else if (result.startsWith("# ")) result = result.removePrefix("# ")

    val quoteDepth = result.takeWhile { it == '>' }.length
    if (quoteDepth > 0) {
        result = result.drop(quoteDepth).trimStart(' ', '.', '#')
        if (result.startsWith(" ")) result = result.drop(1)
    }

    if (result.startsWith("- ")) result = result.removePrefix("- ")

    orderedListRegex.matchAt(result, 0)?.let { result = result.drop(it.value.length) }

    return directionMarker + result
}


/**
 * Applies [style] to the current line (or every line of the selection)
 * by stripping any existing block prefix and writing the style's own
 * prefix. The caret follows the edit so focus stays on the same text.
 */
private fun applyBlockStyle(current: TextFieldValue, style: NoteStyle): TextFieldValue {
    val start = minOf(current.selection.start, current.selection.end).coerceIn(0, current.text.length)
    val end = maxOf(current.selection.start, current.selection.end).coerceIn(0, current.text.length)

    if (start == end) {
        val lineStart = current.text.lastIndexOf('\n', start - 1).let { if (it < 0) 0 else it + 1 }
        val lineEnd = current.text.indexOf('\n', start).let { if (it < 0) current.text.length else it }
        val rawLine = current.text.substring(lineStart, lineEnd)
        val stripped = stripBlockPrefix(rawLine)
        val newLine = style.prefix + stripped
        val newText = buildString {
            append(current.text.substring(0, lineStart))
            append(newLine)
            append(current.text.substring(lineEnd))
        }
        // Keep the caret anchored to the same character: shift it by
        // however many prefix characters were removed/added.
        val caretInLine = (start - lineStart).coerceIn(0, rawLine.length)
        val newCaret = lineStart + (caretInLine - (rawLine.length - stripped.length) + style.prefix.length)
            .coerceIn(0, newLine.length)
        return current.copy(text = newText, selection = TextRange(newCaret, newCaret))
    }

    val block = current.text.substring(start, end)
    val styled = block.lines().joinToString("\n") { line ->
        if (line.isBlank()) line else style.prefix + stripBlockPrefix(line)
    }
    val newText = buildString {
        append(current.text.substring(0, start))
        append(styled)
        append(current.text.substring(end))
    }
    val cursor = start + styled.length
    return current.copy(text = newText, selection = TextRange(start, cursor))
}


private fun toggleColoredQuote(current: TextFieldValue, colorHex: String): TextFieldValue {
    val start = minOf(current.selection.start, current.selection.end).coerceIn(0, current.text.length)
    val end = maxOf(current.selection.start, current.selection.end).coerceIn(0, current.text.length)
    if (start == end) return insertAtSelection(current, "\"text\"[$colorHex]")
    val selected = current.text.substring(start, end)
    val quoted = "\"$selected\"[$colorHex]"
    val newText = buildString {
        append(current.text.substring(0, start))
        append(quoted)
        append(current.text.substring(end))
    }
    val cursor = start + quoted.length
    return current.copy(text = newText, selection = TextRange(cursor, cursor))
}


/**
 * Toggles the orientation marker for the cursor line. Default is LTR (no
 * marker). Clicking on an LTR line prepends an RLM (RTL); clicking again
 * removes it (back to LTR). LRM markers are also stripped on click.
 */
private fun toggleLineOrientation(current: TextFieldValue): TextFieldValue {
    val start = minOf(current.selection.start, current.selection.end).coerceIn(0, current.text.length)
    val lineStart = current.text.lastIndexOf('\n', start - 1).let { if (it < 0) 0 else it + 1 }
    val lineEnd = current.text.indexOf('\n', lineStart).let { if (it < 0) current.text.length else it }
    val line = current.text.substring(lineStart, lineEnd)

    val newLine = when {
        line.startsWith(RLM) -> line.removePrefix(RLM)
        line.startsWith(LRM) -> line.removePrefix(LRM)
        else -> RLM + line
    }
    val newText = buildString {
        append(current.text.substring(0, lineStart))
        append(newLine)
        append(current.text.substring(lineEnd))
    }
    val cursorDelta = newLine.length - line.length
    val newCursor = (start + cursorDelta).coerceIn(lineStart, newText.length)
    return current.copy(text = newText, selection = TextRange(newCursor, newCursor))
}

/**
 * Forces a specific text direction on the cursor's line by stripping any
 * existing RLM / LRM marker and prepending RLM iff [wantRtl] is true.
 * Unlike [toggleLineOrientation] (which cycles LTR ↔ RTL), this always
 * sets the direction to exactly what the caller asked for — keyed
 * shortcuts Ctrl+L (force-LTR) / Ctrl+R (force-RTL) reuse this so a
 * re-press lands on the same state.
 */
private fun forceLineOrientation(current: TextFieldValue, wantRtl: Boolean): TextFieldValue {
    val start = minOf(current.selection.start, current.selection.end).coerceIn(0, current.text.length)
    val lineStart = current.text.lastIndexOf('\n', start - 1).let { if (it < 0) 0 else it + 1 }
    val lineEnd = current.text.indexOf('\n', lineStart).let { if (it < 0) current.text.length else it }
    val line = current.text.substring(lineStart, lineEnd)
    val stripped = line.removePrefix(RLM).removePrefix(LRM)
    val newLine = if (wantRtl) RLM + stripped else stripped
    val newText = buildString {
        append(current.text.substring(0, lineStart))
        append(newLine)
        append(current.text.substring(lineEnd))
    }
    val cursorDelta = newLine.length - line.length
    val newCursor = (start + cursorDelta).coerceIn(lineStart, newText.length)
    return current.copy(text = newText, selection = TextRange(newCursor, newCursor))
}


// ---------------------------------------------------------------------------
// Word-style "Clear Formatting"
//
// Strips inline markdown markers (**bold**, *italic*, __underline__, and
// `"text"[#hex]` coloured quotes unwrap back to plain text) plus line
// prefixes (H1/H2/quote/bullet/number) from the selection — or from the
// cursor's line when nothing is selected. Mirrors Word's "Clear All
// Formatting" eraser: content survives, styling does not.
// ---------------------------------------------------------------------------

private fun clearInlineFormatting(current: TextFieldValue): TextFieldValue {
    val start = minOf(current.selection.start, current.selection.end).coerceIn(0, current.text.length)
    val end = maxOf(current.selection.start, current.selection.end).coerceIn(0, current.text.length)

    val from: Int
    val to: Int
    if (start == end) {
        // No selection → operate on the cursor's line.
        from = current.text.lastIndexOf('\n', start - 1).let { if (it < 0) 0 else it + 1 }
        to = current.text.indexOf('\n', start).let { if (it < 0) current.text.length else it }
    } else {
        from = start
        to = end
    }

    val affected = current.text.substring(from, to)
    val cleaned = stripMarkdownFormatting(affected)
    if (cleaned == affected) return current

    val newText = buildString {
        append(current.text.substring(0, from))
        append(cleaned)
        append(current.text.substring(to))
    }
    // Park the selection inside the cleaned block so the user can keep
    // working; a collapsed cursor lands just after the cleaned text.
    val newEnd = from + cleaned.length
    val newSelection = if (start == end) TextRange(newEnd) else TextRange(from, newEnd)
    return current.copy(text = newText, selection = newSelection)
}


private fun stripMarkdownFormatting(text: String): String {
    val noInline = text
        .replace(INLINE_BOLD, "$1")
        .replace(INLINE_UNDER, "$1")
        .replace(INLINE_ITALIC, "$1")
    return noInline.lines().joinToString("\n") { line ->
        var l = line
        // `"quote"[#hex]` → `quote` (colour AND quote markers removed).
        l = l.replace(Regex("^\"(.*?)\"\\[(?:#[0-9A-Fa-f]{3,8})](.*)$")) { m ->
            (m.groupValues[1].trim() + " " + m.groupValues[2].trim()).trim()
        }
        // Line prefixes: H1/H2, quotes, bullets, numbered lists.
        l = l.replace(Regex("^#{1,2}\\s+"), "")
        l = l.replace(Regex("^>+\\s*"), "")
        l = l.replace(Regex("^[-*]\\s+"), "")
        l = l.replace(Regex("^\\d+\\.\\s+"), "")
        l.trimEnd()
    }
}


/**
 * Removes only the `[#hex]` colour markers from the selection (or cursor
 * line), keeping the quote markers — the toolbar's "no colour" dot. The
 * coloured quote falls back to a plain quote instead of being unwrapped.
 */
private fun removeColorMarkers(current: TextFieldValue): TextFieldValue {
    val start = minOf(current.selection.start, current.selection.end).coerceIn(0, current.text.length)
    val end = maxOf(current.selection.start, current.selection.end).coerceIn(0, current.text.length)

    val from: Int
    val to: Int
    if (start == end) {
        from = current.text.lastIndexOf('\n', start - 1).let { if (it < 0) 0 else it + 1 }
        to = current.text.indexOf('\n', start).let { if (it < 0) current.text.length else it }
    } else {
        from = start
        to = end
    }

    val affected = current.text.substring(from, to)
    val cleaned = affected.lines().joinToString("\n") { line ->
        line.replace(colorMarkerRegex, "")
    }
    if (cleaned == affected) return current

    val newText = buildString {
        append(current.text.substring(0, from))
        append(cleaned)
        append(current.text.substring(to))
    }
    val newEnd = from + cleaned.length
    val newSelection = if (start == end) TextRange(newEnd) else TextRange(from, newEnd)
    return current.copy(text = newText, selection = newSelection)
}


// ---------------------------------------------------------------------------
// Enter-key list continuation
//
// When `autoContinueLists` is on and the user presses Enter at the end of
// a list / enumeration line, we want a NEW list entry to appear. Detection
// runs in `applyEditorChange` by intercepting the TextFieldValue change:
// if exactly one `\n` was added at the previous cursor position AND the
// line that the cursor was on ended in a recognised list prefix, we
// rewrite `next` so the new line already has the matching continuation.
//
// Empty list items are demoted: pressing Enter on a final `- ` / `>. ` /
// `># ` strips the prefix so the user exits the list cleanly onto a
// blank line.
// ---------------------------------------------------------------------------

private enum class ContinuationKind { CONTINUE, EXIT }


private data class DetectedContinuation(
    val kind: ContinuationKind,
    val prefix: String
)


private fun continueListAtEnter(prev: TextFieldValue, next: TextFieldValue): TextFieldValue {
    val selStart = minOf(prev.selection.start, prev.selection.end)
    val selEnd = maxOf(prev.selection.start, prev.selection.end)
    if (selStart != selEnd) return next
    if (next.selection.start != next.selection.end) return next

    // Exactly one newline was inserted, AT selStart of prev.
    if (next.text.length - prev.text.length != 1) return next
    if (selStart !in 0..next.text.lastIndex || next.text[selStart] != '\n') return next

    // Compute the line the cursor was on (in prev).
    val lineStart = prev.text.lastIndexOf('\n', selStart - 1).let { if (it < 0) 0 else it + 1 }
    val lineEnd = prev.text.indexOf('\n', lineStart).let { if (it < 0) prev.text.length else it }
    if (selStart != lineEnd) return next
    val line = prev.text.substring(lineStart, lineEnd)

    val detected = detectListContinuation(line, prev.text, lineStart) ?: return next

    return when (detected.kind) {
        ContinuationKind.EXIT -> {
            // Strip the empty list prefix from prev so the new line stays blank.
            val newText = prev.text.substring(0, lineStart) + "\n"
            val newCursor = newText.length
            next.copy(text = newText, selection = TextRange(newCursor))
        }
        ContinuationKind.CONTINUE -> {
            // Insert continuation prefix immediately after the entered \n.
            val withContinuation =
                next.text.substring(0, selStart + 1) +
                    detected.prefix +
                    next.text.substring(selStart + 1)
            val newCursor = selStart + 1 + detected.prefix.length
            next.copy(text = withContinuation, selection = TextRange(newCursor))
        }
    }
}


private val emptyQuoteBulletRegex = Regex("^>+\\.\\s*$")
private val emptyQuoteNumberedRegex = Regex("^>+#\\s*$")


private fun detectListContinuation(line: String, fullSource: String, lineStart: Int): DetectedContinuation? {
    val quoteDepth = if (line.startsWith(">")) line.takeWhile { it == '>' }.length else 0

    // Empty list items are demoted (Enter exits the list).
    if (line == "- ") return DetectedContinuation(ContinuationKind.EXIT, "- ")
    if (emptyQuoteBulletRegex.matches(line)) {
        return DetectedContinuation(ContinuationKind.EXIT, line)
    }
    if (emptyQuoteNumberedRegex.matches(line)) {
        return DetectedContinuation(ContinuationKind.EXIT, line)
    }

    // Non-empty plain bullet `- foo` -> continue with `- `
    if (line.startsWith("- ") && line.length > 2) {
        return DetectedContinuation(ContinuationKind.CONTINUE, "- ")
    }

    // Non-empty plain numbered `1. foo` -> walk backwards for the previous
    // numbered line and increment from there so the user's sequence is
    // preserved.
    orderedListRegex.matchAt(line, 0)?.let { match ->
        val currentNum = match.value.substringBefore('.').toIntOrNull() ?: 1
        val prevNum = lastPlainNumberedNumber(fullSource, lineStart)
        val nextNum = (maxOf(currentNum, prevNum ?: currentNum) + 1)
        return DetectedContinuation(ContinuationKind.CONTINUE, "$nextNum. ")
    }

    // Non-empty quote-bullet / quote-numbered `>. foo` / `># foo` (and
    // their multi-`>` variants). For `>#` we save the raw `#` because
    // the editor synthesises the displayed digit at render time.
    if (quoteDepth > 0 && line.length > quoteDepth) {
        val after = line[quoteDepth]
        val prefix = ">".repeat(quoteDepth)
        when (after) {
            '.' -> return DetectedContinuation(ContinuationKind.CONTINUE, "$prefix. ")
            '#' -> return DetectedContinuation(ContinuationKind.CONTINUE, "$prefix# ")
        }
    }

    return null
}


private fun lastPlainNumberedNumber(source: String, beforeStart: Int): Int? {
    var pos = beforeStart
    while (pos > 0) {
        val nlIdx = source.lastIndexOf('\n', pos - 1)
        val lineStart = if (nlIdx < 0) 0 else nlIdx + 1
        val lineEnd = if (nlIdx < 0) pos else nlIdx
        val line = source.substring(lineStart, lineEnd)
        orderedListRegex.matchAt(line, 0)?.let { match ->
            return match.value.substringBefore('.').toIntOrNull()
        }
        // Stop at blank line — numbered sequences don't cross blank lines.
        if (line.isBlank()) return null
        pos = lineStart
    }
    return null
}


// ---------------------------------------------------------------------------
// Undo / Redo
//
// `past` is the stack of states we can `undo` back to. The active editor
// value lives in the composable (not here); it's passed in `undo(current)`
// / `redo(current)` so we can push it onto the opposite stack and pop the
// correct previous/next state.
// ---------------------------------------------------------------------------

private class UndoManager(private val maxSize: Int = 200) {
    private val past = ArrayDeque<TextFieldValue>()
    private val future = ArrayDeque<TextFieldValue>()

    fun reset() {
        past.clear()
        future.clear()
    }

    fun recordChange(prev: TextFieldValue, next: TextFieldValue) {
        if (prev.text == next.text && prev.selection == next.selection) return
        if (past.isNotEmpty() &&
            past.last().text == prev.text &&
            past.last().selection == prev.selection
        ) return
        past.addLast(prev)
        while (past.size > maxSize) past.removeFirst()
        future.clear()
    }

    fun undo(current: TextFieldValue): TextFieldValue? {
        if (past.isEmpty()) return null
        future.addLast(current)
        return past.removeLast()
    }

    fun redo(current: TextFieldValue): TextFieldValue? {
        if (future.isEmpty()) return null
        val next = future.removeLast()
        past.addLast(current)
        return next
    }

    fun canUndo(): Boolean = past.isNotEmpty()
    fun canRedo(): Boolean = future.isNotEmpty()
}


// ---------------------------------------------------------------------------
// Tap-to-navigate: Bible references
// ---------------------------------------------------------------------------

private data class ReferenceMatch(
    val book: String,
    val chapter: Int?,
    val verse: Int?,
    val label: String? = null
)


private data class ReferenceLookup(
    val lineStarts: IntArray,
    val byLine: Map<Int, ReferenceMatch>
)

private fun buildReferenceLookup(text: String): ReferenceLookup {
    if (text.isEmpty()) return ReferenceLookup(IntArray(0), emptyMap())
    val starts = IntArray(text.count { it == '\n' } + 1)
    val byLine = mutableMapOf<Int, ReferenceMatch>()
    var idx = 0
    var scan = 0
    while (scan <= text.length) {
        val nl = text.indexOf('\n', scan)
        val lineEnd = if (nl == -1) text.length else nl
        val raw = text.substring(scan, lineEnd)
        val stripped = when {
            raw.startsWith(RLM) -> raw.removePrefix(RLM)
            raw.startsWith(LRM) -> raw.removePrefix(LRM)
            else -> raw
        }
        referenceLineRegex.matchEntire(stripped)?.let { match ->
            byLine[scan] = ReferenceMatch(
                book = match.groupValues[1].trim(),
                chapter = match.groupValues[2].toIntOrNull(),
                verse = match.groupValues[3].toIntOrNull(),
                label = match.groupValues[4].trim().ifBlank { null }
            )
        }
        starts[idx++] = scan
        if (nl == -1) break
        scan = lineEnd + 1
    }
    return ReferenceLookup(starts.copyOf(idx), byLine)
}

private fun findReferenceInLookup(lookup: ReferenceLookup, sourcePos: Int): ReferenceMatch? {
    if (lookup.lineStarts.isEmpty()) return null
    val clamped = sourcePos.coerceAtLeast(0)
    var lo = 0
    var hi = lookup.lineStarts.size - 1
    var lineIdx = -1
    while (lo <= hi) {
        val mid = (lo + hi) ushr 1
        if (lookup.lineStarts[mid] <= clamped) {
            lineIdx = mid
            lo = mid + 1
        } else {
            hi = mid - 1
        }
    }
    if (lineIdx == -1) return null
    return lookup.byLine[lookup.lineStarts[lineIdx]]
}


// ---------------------------------------------------------------------------
// Keyboard shortcuts
//
// `onPreviewKeyEvent` reports a separate event for each physical press
// and release of the same key. The Compose `KeyEvent.type` discriminator
// and the `nativeKeyEvent` underlying-AWT accessor are both opaque on
// this `jvm(\"desktop\")` Compose 1.9 target (compiler resolves neither
// property), so we cannot strictly distinguish Press from Release. We
// fall back to a time-windowed dedupe keyed by (key, modifiers):
//
//   * A single Ctrl+Z tap fires Press (~t=0) and Release (~t=50–150ms);
//     the window collapses the Release event so the user sees ONE undo.
//   * Held autorepeat fires Press events at OS autorepeat cadence
//     (~30 Hz / ~33ms); the window collapses consecutive Presses until
//     a gap exceeds the window, so undo fires ~5 Hz while held. This
//     matches the heuristic used by Android Studio / IntelliJ and is
//     tuned to feel deliberate rather than spammy for editor undo.
//   * Two distinct manual taps (>= 250ms apart) both fire.
// ---------------------------------------------------------------------------

private var lastHandledKeyCombo: String? = null
private var lastHandledShortcutNanos: Long = Long.MIN_VALUE

private fun handleEditorShortcut(
    event: KeyEvent,
    /**
     * Whether the Find/Replace bar is currently open. When true, a
     * non-modifier Esc is treated as "close Find" (short-circuit
     * before the Ctrl-only check so the user doesn't need modifier
     * keys to dismiss Find).
     */
    isFindOpen: Boolean = false,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    /**
     * Move the cursor (or selection head) to the next/previous word
     * boundary relative to the current selection. `extend = true`
     * keeps the anchor end of the selection pinned (so the user can
     * `Ctrl+Shift+→` to extend selection one word at a time).
     */
    onWordJump: (extend: Boolean, forward: Boolean) -> Unit,
    onSelectAll: () -> Unit,
    /**
     * Open Find (Ctrl+F) or Find+Replace (Ctrl+H). When Find is
     * already open, the same shortcut toggles it shut so the user
     * has a single key to dismiss the bar without reaching for Esc.
     */
    onToggleFind: (replaceMode: Boolean) -> Unit,
    onCloseFind: () -> Unit,
    /**
     * Persist the current note. Wired from the parent's existing
     * save-in-place path so the toolbar's "Save" button and the
     * Ctrl+S keystroke stay in lockstep.
     */
    onSave: () -> Unit,
    /**
     * Export the current note to PDF. Wired from the parent's
     * existing NotePdfExporter call.
     */
    onExport: () -> Unit,
    /**
     * Navigate back to the previous screen (HOME from BIBLE / NOTES,
     * HOME from SPLIT). Activated by Esc when the Find bar is NOT
     * open, and by Ctrl+W (matches browser "close tab" convention).
     */
    onBack: () -> Unit,
    /**
     * Inline-formatting wrap. Marker is the wrapping token — `**`,
     * `*`, `__` for bold / italic / underline. The parent's caller
     * is expected to treat the call as a toggle on the current
     * selection (matching the toolbar buttons).
     */
    onInlineWrap: (marker: String) -> Unit,
    /**
     * Cycle the cursor line's text-direction marker LTR → RTL → LTR.
     */
    onCycleOrientation: () -> Unit,
    /**
     * Force the cursor line to LTR (strip any RLM/LRM marker).
     */
    onForceLtr: () -> Unit,
    /**
     * Force the cursor line to RTL (prepend RLM after stripping any
     * existing marker — re-press lands on the same state).
     */
    onForceRtl: () -> Unit,
    /**
     * Open the verse-reference picker (Ctrl+K).
     */
    onInsertReference: () -> Unit,
    /**
     * Open the book-reference picker (Ctrl+Shift+K).
     */
    onInsertBook: () -> Unit
): Boolean {
    // Esc closes Find but does NOT navigate Back — an accidental Esc
    // mid-edit would lose unsaved changes with no save prompt. The
    // dedicated Back keybind is Ctrl+W (which we added below), so
    // the navigation action is still one keystroke away, just not
    // hidden behind a modifier-less Esc.
    if (isFindOpen && event.key == Key.Escape) {
        onCloseFind()
        return true
    }
    if (!event.isCtrlPressed && !event.isMetaPressed) return false

    val combo = "${event.key}|${event.isCtrlPressed}|${event.isShiftPressed}|${event.isMetaPressed}"
    val now = System.nanoTime()
    if (combo == lastHandledKeyCombo && now - lastHandledShortcutNanos < 200_000_000L) {
        // Same physical key/modifiers within the dedupe window. Skip
        // every action that has external side effects (Z/Y/S/P/W/K/B/I/U
        // and similar) so held autorepeat doesn't hammer the disk, the
        // undo stack, or the toggle state. ←/→ bypass so the natural OS
        // autorepeat cadence applies to word-jump navigation, matching
        // IntelliJ / VS Code.
        if (event.key != Key.DirectionLeft && event.key != Key.DirectionRight) {
            return false
        }
    }
    lastHandledKeyCombo = combo
    lastHandledShortcutNanos = now

    return when (event.key) {
        Key.Z -> {
            if (event.isShiftPressed) onRedo() else onUndo()
            true
        }
        Key.Y -> {
            onRedo()
            true
        }
        Key.A -> {
            onSelectAll()
            true
        }
        Key.DirectionLeft -> {
            // Kotlin disallows NAMED arguments on function-type values
            // (lambdas are positional-by-type). Forward positionally.
            onWordJump(event.isShiftPressed, false)
            true
        }
        Key.DirectionRight -> {
            onWordJump(event.isShiftPressed, true)
            true
        }
        Key.F -> {
            onToggleFind(false)
            true
        }
        Key.H -> {
            onToggleFind(true)
            true
        }
        // Inline formatting — wrap/unwrap the current selection.
        Key.B -> {
            onInlineWrap("**")
            true
        }
        Key.I -> {
            onInlineWrap("*")
            true
        }
        Key.U -> {
            onInlineWrap("__")
            true
        }
        // Persist + export.
        Key.S -> {
            onSave()
            true
        }
        Key.P -> {
            onExport()
            true
        }
        // Back / navigate away.
        Key.W -> {
            onBack()
            true
        }
        // Direction toggles. Markdown has no alignment so we map
        // "left/center/right" to the existing LTR/RLM direction
        // markers in the markdown source. Right-click on this if the
        // user wants real visual alignment.
        Key.L -> {
            onForceLtr()
            true
        }
        Key.E -> {
            onCycleOrientation()
            true
        }
        Key.R -> {
            onForceRtl()
            true
        }
        // Insert Reference / Book scaffolds. K with Shift is the
        // book-only variant (no chapter/verse digits).
        Key.K -> {
            if (event.isShiftPressed) onInsertBook() else onInsertReference()
            true
        }
        else -> false
    }
}
