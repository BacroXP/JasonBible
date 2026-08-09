@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class
)

package ui



// ---------------------------------------------------------------------------
// Shared markup grammar for the note editor
//
// The marker constants and regexes here are used by the visual
// transformation (NoteVisualTransformation), the editor text helpers
// (EditorTextOps) and the tap/hover reference lookup
// (BibleReferenceLookup). They mirror the grammar in `NotesRepository` so
// the visual transform and the parser agree on what counts as a heading /
// bullet / quote / reference / colored-quote.
// ---------------------------------------------------------------------------

internal const val RLM = "\u200F" // Right-to-Left Mark: toggles a line into RTL
internal const val LRM = "\u200E" // Left-to-Right Mark: explicit LTR line marker

// Alignment markers — invisible zero-width control chars prepended to a
// line, like RLM/LRM, hidden from the display by the visual
// transformation. Left alignment is the default (no marker). The marker
// sits FIRST in the line, before any direction marker, so a centered RTL
// line reads `\u200B\u200F…` (the alignment markers are stripped by
// [stripLeadingMarkers] before block classification everywhere, so a
// centered `\u200B# Title` still parses as a heading).
internal const val ALIGN_CENTER = "\u200B" // Zero Width Space
internal const val ALIGN_RIGHT = "\u2060" // Word Joiner

/**
 * The three paragraph alignments the editor can force. [LEFT] is the
 * default and carries no marker — it strips any center/right marker.
 * [CENTER] / [RIGHT] write their marker at the line start.
 */
internal enum class LineAlignment(val marker: String, val label: String) {
    LEFT("", "Left"),
    CENTER(ALIGN_CENTER, "Center"),
    RIGHT(ALIGN_RIGHT, "Right");
}

/** Alignment marker present at the line start (empty = left/default). */
internal fun alignmentMarkerOf(line: String): String = when {
    line.startsWith(ALIGN_CENTER) -> ALIGN_CENTER
    line.startsWith(ALIGN_RIGHT) -> ALIGN_RIGHT
    else -> ""
}

/**
 * The leading control markers of a line — one alignment marker plus one
 * direction marker (RLM/LRM), in canonical order (alignment first) — so
 * callers can re-apply them around a block prefix. Empty when the line
 * starts with ordinary content.
 */
internal fun leadingMarkers(line: String): String {
    val alignment = alignmentMarkerOf(line)
    val rest = if (alignment.isNotEmpty()) line.removePrefix(alignment) else line
    val direction = when {
        rest.startsWith(RLM) -> RLM
        rest.startsWith(LRM) -> LRM
        else -> ""
    }
    return alignment + direction
}

/** The line with any leading alignment + direction markers removed. */
internal fun stripLeadingMarkers(line: String): String =
    line.removePrefix(ALIGN_CENTER).removePrefix(ALIGN_RIGHT)
        .removePrefix(RLM).removePrefix(LRM)

internal val orderedListRegex = Regex("^\\d+\\.\\s+")
// Groups: 1 = book, 2 = chapter, 3 = verse, 4 = optional verse-range
// suffix (`+`, `+N`, `-V2`, `-&C2&V2`, … — resolved by data.parseRange),
// 5 = optional trailing label. Parts are separated by `&` (or `$` for
// notes written before the `&` syntax); a cross-chapter end is written
// `-&C2&V2` (e.g. `$Lukas&7&1-&8&1` = Luk 7:1 to 8:1).
internal val referenceLineRegex =
    Regex("^\\$([^$&]+)(?:[$&](\\d+)(?:[$&](\\d+)(\\+\\d*|-[$&]?\\d+(?:[$&]\\d+)?(?:\\+\\d*)?)?(?:\\s+(.*))?)?)?\\s*$")
internal val coloredQuoteRegex =
    Regex("^\"(.+?)\"(?:\\[#([0-9A-Fa-f]{3,8})])?\\s*(.*)$")

// Matches a `[#hex]` colour marker anywhere in a line. Used by the
// toolbar's "no colour" dot to strip highlight colours while keeping the
// quote markers themselves intact.
internal val colorMarkerRegex = Regex("\\[#[0-9A-Fa-f]{3,8}]")

internal val INLINE_BOLD = Regex("\\*\\*([^*]+)\\*\\*")
internal val INLINE_ITALIC = Regex("(?<!\\*)\\*([^*]+)\\*(?!\\*)")
internal val INLINE_UNDER = Regex("__([^_]+)__")

