package data


// ---------------------------------------------------------------------------
// Generic text matching
//
// Whole-word boundary semantics shared by the Bible full-text search
// (ui.BibleSearch), the global search and the note search
// (NotesRepository): the characters immediately before and after a match
// must not be letters or digits, so "day" matches in "a day of" but not
// in "today" or "daylight".
// ---------------------------------------------------------------------------

/** True when the match at [index] (length [length]) in [text] is a whole
 *  word. */
private fun isWholeWordAt(text: String, index: Int, length: Int): Boolean {
    val before = if (index > 0) text[index - 1] else ' '
    val after = if (index + length < text.length) text[index + length] else ' '
    return !before.isLetterOrDigit() && !after.isLetterOrDigit()
}

/**
 * First match of [q] in [text] at or after [from], honouring the case
 * and whole-word flags. Returns -1 when none. Whole-word matching walks
 * past substring hits ("day" inside "today") until a real boundary hit
 * or the end of the text.
 */
internal fun findMatchIn(
    text: String,
    q: String,
    from: Int,
    matchCase: Boolean,
    wholeWord: Boolean
): Int {
    var index = text.indexOf(q, from, ignoreCase = !matchCase)
    if (!wholeWord) return index
    while (index != -1) {
        if (isWholeWordAt(text, index, q.length)) return index
        index = text.indexOf(q, index + 1, ignoreCase = !matchCase)
    }
    return -1
}
