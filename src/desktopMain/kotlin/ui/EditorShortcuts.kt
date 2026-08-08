@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class
)

package ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key



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
internal fun handleEditorShortcut(
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
    /**
     * Open the global notes search (Ctrl+Shift+F) — a full-text scan
     * across every note file, with click-to-open results.
     */
    onToggleNotesSearch: () -> Unit,
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
     * Align the cursor line (or selection) left — strips any
     * center/right alignment marker.
     */
    onAlignLeft: () -> Unit,
    /**
     * Align the cursor line (or selection) center; a re-press returns
     * it to left.
     */
    onAlignCenter: () -> Unit,
    /**
     * Align the cursor line (or selection) right; a re-press returns
     * it to left.
     */
    onAlignRight: () -> Unit,
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
            // Ctrl+Shift+F = global notes search; plain Ctrl+F = the
            // in-note find bar. Shift distinguishes the two on the same
            // physical key.
            if (event.isShiftPressed) {
                onToggleNotesSearch()
            } else {
                onToggleFind(false)
            }
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
        // Real paragraph alignment — left / center / right. Stored as
        // invisible leading markers and rendered via ParagraphStyle
        // textAlign; Ctrl+E toggles center off when already centered.
        // Text direction (LTR/RTL) stays available on the Layout tab's
        // direction button.
        Key.L -> {
            onAlignLeft()
            true
        }
        Key.E -> {
            onAlignCenter()
            true
        }
        Key.R -> {
            onAlignRight()
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

