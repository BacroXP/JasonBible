package data


/**
 * An inline Bible reference token found in running text — e.g. the
 * `$Lukas$3$16` inside "Read $Lukas$3$16 for comfort". [sourceStart] /
 * [sourceEnd] are char offsets into the scanned string (end exclusive).
 */
data class ReferenceToken(
    val book: String,
    val chapter: Int?,
    val verse: Int?,
    val sourceStart: Int,
    val sourceEnd: Int
)


// Characters that legally terminate an inline reference. The user-facing
// rule is "a reference ends when a space follows"; we also accept common
// punctuation so "Read $Lukas$3$16, please" still resolves — the
// punctuation itself is NOT part of the book name.
private val REFERENCE_TERMINATORS = setOf(
    ' ', '\t', '\n', '\r',
    '.', ',', ';', ':', '!', '?',
    ')', ']', '}', '"', '\'',
    '»', '«', '„', '“', '”', '-', '/'
)


/**
 * Find `$Book`, `$Book$C` and `$Book$C$V` tokens embedded anywhere in
 * [content] — not just at the start of a line, so references work inside
 * a sentence ("Read $Lukas$3$16 today"). A token ends at whitespace (the
 * documented rule) or common punctuation, which stays outside the token.
 *
 * Book names may be multiple words ("1 Mose") when directly followed by
 * a `$digits` continuation; a multi-word run without one is treated as
 * just its first word ("Lukas" in "Read $Lukas today"). A space directly
 * before the continuation ends the reference ("$Lukas $3" → `$Lukas`).
 * The book name must contain at least one letter, so "$5.99" (money) is
 * not mistaken for a reference. Unknown book names still parse —
 * resolving them is the caller's concern.
 */
fun findReferenceTokens(content: String): List<ReferenceToken> {
    val result = mutableListOf<ReferenceToken>()
    var i = 0
    while (i < content.length) {
        if (content[i] != '$') {
            i++
            continue
        }
        // A `$` must be followed directly by the book name.
        if (i + 1 >= content.length ||
            content[i + 1].isWhitespace() ||
            content[i + 1] == '$'
        ) {
            i++
            continue
        }

        val tokenStart = i
        val restStart = i + 1

        // Where does the book name end? At the next `$` (which would
        // start the optional $chapter/$verse continuation) or at the end
        // of the run.
        var bookEnd = restStart
        while (bookEnd < content.length && content[bookEnd] != '$') bookEnd++

        var book: String
        var chapter: Int? = null
        var verse: Int? = null
        var end: Int

        // A continuation counts only when the book name abuts `$digits`
        // with no whitespace in between — a space ends the reference
        // (user rule), so "$Lukas $3" is `$Lukas` + plain text.
        val hasContinuation = bookEnd < content.length &&
            bookEnd + 1 < content.length &&
            content[bookEnd + 1].isDigit() &&
            !content[bookEnd - 1].isWhitespace()

        if (hasContinuation) {
            // `$Book$C` or `$Book$C$V`. Book is verbatim (spaces inside
            // are fine: "1 Mose$3$16").
            book = content.substring(restStart, bookEnd)
            var k = bookEnd + 1
            val cStart = k
            while (k < content.length && content[k].isDigit()) k++
            chapter = content.substring(cStart, k).toIntOrNull()
            // Optional `$Verse`.
            if (k < content.length && content[k] == '$' &&
                k + 1 < content.length && content[k + 1].isDigit()
            ) {
                val vStart = k + 1
                k = vStart
                while (k < content.length && content[k].isDigit()) k++
                verse = content.substring(vStart, k).toIntOrNull()
            }
            end = k
        } else {
            // No digit continuation: the reference is just `$Book`, and
            // the book name is a single word — whitespace or punctuation
            // ends it (and is excluded from the name).
            var k = restStart
            while (k < content.length &&
                content[k] != '$' &&
                !content[k].isWhitespace() &&
                content[k] !in REFERENCE_TERMINATORS
            ) {
                k++
            }
            book = content.substring(restStart, k)
            if (book.isEmpty()) {
                i = k
                continue
            }
            end = k
        }

        // A book name must contain at least one letter: "$5" / "$5.99"
        // (money) and "$123" (numbers) can't be Bible books, while
        // multi-word names like "1 Mose" still pass.
        if (!book.any { it.isLetter() }) {
            i = end
            continue
        }

        // The token must terminate at whitespace / punctuation / EOL.
        if (end < content.length && content[end] !in REFERENCE_TERMINATORS) {
            i = end
            continue
        }

        result.add(ReferenceToken(book, chapter, verse, tokenStart, end))
        i = end
    }
    return result
}
