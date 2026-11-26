package ui

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import data.MediaSearchResult
import data.MediaService


// ---------------------------------------------------------------------------
// Media reference autofill
//
// While the caret sits on (or right after) an `@Phrase` — e.g. the `@Josia`
// in "Check out @Josia" — the editor searches the web for matching
// channels / videos and shows them in a suggestion bar below the editor.
// Tab or a click inserts the correct `@youtube:@JosiaQueen` /
// `@youtube:videoId` token in place of the typed phrase:
//
//     "Check out @Josia"  →  "Check out @youtube:@JosiaQueen "
//
// Only a PHRASE triggers the search: `@youtube:…` tokens and `@youtube`
// (a bare known service key) are left to the manual insert flow, so the
// autofill never fights the token syntax. All matching / insertion logic
// lives here as pure functions so it is directly unit-testable;
// NotesScreen wires it to the editor state (debounced search on a
// background thread).
// ---------------------------------------------------------------------------

/** Minimum length of the search phrase after `@` before a search starts. */
internal const val MIN_MEDIA_QUERY_LEN = 2

/** Characters allowed inside an `@Phrase` (the media-token regex's word
 *  charset plus spaces, so multi-word queries like "@Josia Queen" work). */
private fun isPhraseChar(c: Char): Boolean =
    c.isWhitespace() || c.isLetterOrDigit() || c == '_' || c == '-'


/** The `@Phrase` the caret is completing: its source range (including the
 *  leading `@`) plus the phrase to search for. */
internal data class MediaPrefix(
    val start: Int,
    val end: Int,
    val query: String
)


/**
 * The `@Phrase` the caret sits on, or null when the editor isn't in an
 * autofill state. Multi-word phrases are supported ("@Josia Queen" is
 * ONE query) and the phrase may sit inline in a sentence ("Watch @Josia
 * today"). Returns null when:
 *   - the caret is outside the text,
 *   - no `@` starts the phrase (and the `@` isn't preceded by whitespace /
 *     the start of the text — an email like "john@mail.com" must not
 *     trigger a search),
 *   - the phrase is shorter than [MIN_MEDIA_QUERY_LEN] (a lone "@J"),
 *   - the phrase contains a `:` (an in-progress `@service:…` token),
 *   - the phrase's first word IS a known service key ("@youtube" alone) —
 *     those go through the manual insert dialog / token flow instead.
 */
internal fun mediaSearchPrefixAt(text: String, caret: Int): MediaPrefix? {
    if (caret < 0 || caret > text.length) return null
    // Backwards scan from the caret over phrase characters to the `@`
    // that starts the phrase.
    var at = -1
    var i = caret
    while (i > 0) {
        val c = text[i - 1]
        if (c == '@') {
            at = i - 1
            break
        }
        if (!isPhraseChar(c)) break
        i--
    }
    if (at == -1) return null
    // The `@` must open a word (whitespace or start of text before it),
    // so email addresses and mid-word mentions never trigger a search.
    if (at > 0 && !text[at - 1].isWhitespace()) return null
    // Forward scan over word characters (mirroring the backward scan's
    // charset) — the caret may sit mid-phrase, and trailing punctuation
    // like a comma must stay OUT of the query and the replaced range.
    var end = caret
    while (end < text.length &&
        (text[end].isLetterOrDigit() || text[end] == '_' || text[end] == '-')
    ) end++
    val query = text.substring(at + 1, end)
    if (query.length < MIN_MEDIA_QUERY_LEN) return null
    if (query.contains(':')) return null
    val firstWord = query.substringBefore(' ')
    if (MediaService.forKey(firstWord) != null) return null
    return MediaPrefix(start = at, end = end, query = query.trim())
}


/** The `@service:content ` token text inserted for a picked suggestion. */
internal fun buildMediaToken(result: MediaSearchResult): String =
    "@${result.service.key}:${result.tokenContent} "


/**
 * Apply a picked suggestion to the editor state: replace the typed
 * `@Phrase` with the full `@service:content ` token and park the caret
 * right after it. When the phrase is directly followed by a space the
 * user typed (e.g. "@Josia "), that space is consumed too, so the
 * completed token doesn't leave a double gap.
 */
internal fun applyMediaSuggestion(
    current: TextFieldValue,
    prefix: MediaPrefix,
    result: MediaSearchResult
): TextFieldValue {
    val token = buildMediaToken(result)
    var replaceEnd = prefix.end
    if (replaceEnd < current.text.length && current.text[replaceEnd] == ' ') {
        replaceEnd++
    }
    val newText = current.text.substring(0, prefix.start) +
        token +
        current.text.substring(replaceEnd)
    return current.copy(
        text = newText,
        selection = TextRange(prefix.start + token.length)
    )
}

