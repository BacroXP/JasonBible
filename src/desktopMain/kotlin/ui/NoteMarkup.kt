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

internal val orderedListRegex = Regex("^\\d+\\.\\s+")
internal val referenceLineRegex =
    Regex("^\\\$([^\\\$]+)(?:\\\$(\\d+)(?:\\\$(\\d+)(?:\\s+(.*))?)?)?\\s*$")
internal val coloredQuoteRegex =
    Regex("^\"(.+?)\"(?:\\[#([0-9A-Fa-f]{3,8})])?\\s*(.*)\$")

// Matches a `[#hex]` colour marker anywhere in a line. Used by the
// toolbar's "no colour" dot to strip highlight colours while keeping the
// quote markers themselves intact.
internal val colorMarkerRegex = Regex("\\[(?:#[0-9A-Fa-f]{3,8})]")

internal val INLINE_BOLD = Regex("\\*\\*([^*]+)\\*\\*")
internal val INLINE_ITALIC = Regex("(?<!\\*)\\*([^*]+)\\*(?!\\*)")
internal val INLINE_UNDER = Regex("__([^_]+)__")

