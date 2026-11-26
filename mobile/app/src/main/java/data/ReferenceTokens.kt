package data


/**
 * An inline Bible reference token found in running text — e.g. the
 * `$Lukas&7&1` inside "Read $Lukas&7&1 for comfort". [sourceStart] /
 * [sourceEnd] are char offsets into the scanned string (end exclusive).
 *
 * Parts are separated by `&` (or `$` for notes written before the `&`
 * syntax) — `$Book&C&V`. [chapter] / [verse] are the START of the
 * reference; [endChapter] / [endVerse] describe an optional extended
 * range (see [parseRange]) and are both non-null exactly when the token
 * carries a range suffix.
 */
data class ReferenceToken(
    val book: String,
    val chapter: Int?,
    val verse: Int?,
    /**
     * Chapter of the inclusive range end, non-null when the token
     * carries a verse-range suffix (`+`, `+N`, `-V2`, `-C2&V2`, …).
     * Equal to [chapter] for same-chapter ranges, larger for
     * cross-chapter ranges (e.g. `$Lukas&7&1-&8&1`).
     */
    val endChapter: Int? = null,
    /**
     * Inclusive last verse of the range. Non-null whenever
     * [endChapter] is; the chip renders as `Book C:V-E` (same chapter)
     * or `Book C:V-C2:E` (cross-chapter). The suffix is part of the
     * token ([sourceEnd] includes it).
     */
    val endVerse: Int? = null,
    val sourceStart: Int,
    val sourceEnd: Int
)


/**
 * An inclusive verse range, resolved from a raw range suffix by
 * [parseRange]. [endChapter] / [endVerse] are the last chapter and verse
 * of the range; the start is the reference's own (chapter, verse).
 */
internal data class ReferenceRange(
    val endChapter: Int,
    val endVerse: Int
)


/**
 * Resolves a raw verse-range suffix — as captured by
 * `referenceLineRegex` group 4, e.g. `+`, `+5`, `-3`, `-8+10`,
 * `-&8`, `-&8&1`, `-&8+10` — to the inclusive end of the range that
 * starts at ([startChapter], [startVerse]). Returns null when the suffix
 * is empty, malformed, or degenerate (a range must move forward: same
 * chapter requires a strictly later verse).
 *
 * Grammar (end-based / "to-verse" semantics):
 *   `+`      → one following verse
 *   `+N`     → N following verses
 *   `-V2`    → to verse V2 of the same chapter
 *   `-V2+N`  → to verse V2, plus N more verses
 *   `-&C2`   → to chapter C2, verse 1
 *   `-&C2&V2` → to chapter C2, verse V2
 *   `-&C2+N` → to chapter C2 verse 1, plus N more
 *   `-&C2&V2+N` → to chapter C2 verse V2, plus N more
 * (`$` is accepted in place of `&` for the cross-chapter part.)
 */
internal fun parseRange(
    startChapter: Int?,
    startVerse: Int?,
    raw: String
): ReferenceRange? {
    val sc = startChapter ?: return null
    val sv = startVerse ?: return null
    if (raw.isEmpty()) return null

    if (raw.startsWith("+")) {
        // `+` alone = one following verse, `+N` = N following.
        val n = raw.drop(1).toIntOrNull() ?: 1
        if (n <= 0) return null
        // Long arithmetic so an absurd `+N` can't silently wrap the end
        // verse around into a nonsense range.
        val end = sv.toLong() + n
        if (end > Int.MAX_VALUE) return null
        return ReferenceRange(sc, end.toInt())
    }

    if (raw.startsWith("-")) {
        var body = raw.drop(1)
        var ec = sc
        var ev: Int
        if (body.isNotEmpty() && (body[0] == '&' || body[0] == '$')) {
            // Cross-chapter end: `-&C2[&V2][+N]`. Missing end verse
            // means verse 1 of the target chapter.
            body = body.drop(1)
            val c2 = body.takeWhile { it.isDigit() }
            if (c2.isEmpty()) return null
            ec = c2.toIntOrNull() ?: return null
            body = body.drop(c2.length)
            if (body.isNotEmpty() && (body[0] == '&' || body[0] == '$')) {
                body = body.drop(1)
                val v2 = body.takeWhile { it.isDigit() }
                if (v2.isEmpty()) return null
                ev = v2.toIntOrNull() ?: return null
                body = body.drop(v2.length)
            } else {
                ev = 1
            }
        } else {
            // Same-chapter end: `-V2[+N]`.
            val v2 = body.takeWhile { it.isDigit() }
            if (v2.isEmpty()) return null
            ev = v2.toIntOrNull() ?: return null
            body = body.drop(v2.length)
        }
        if (body.isNotEmpty()) {
            // Optional `+N` extending the end verse.
            if (body[0] != '+') return null
            val n = body.drop(1).toIntOrNull() ?: 1
            if (n <= 0) return null
            val extended = ev.toLong() + n
            if (extended > Int.MAX_VALUE) return null
            ev = extended.toInt()
        }
        // A range must actually move forward — a same-chapter range that
        // ends at or before the start verse (e.g. `-7` from verse 16) is
        // degenerate and treated as no range.
        if (ec < sc) return null
        if (ec == sc && ev <= sv) return null
        return ReferenceRange(ec, ev)
    }

    return null
}


// Characters that legally terminate an inline reference. The user-facing
// rule is "a reference ends when a space follows"; we also accept common
// punctuation so "Read $Lukas&7&1, please" still resolves — the
// punctuation itself is NOT part of the book name. `&` and `-` are
// included so a dangling separator / dash (e.g. a malformed range that
// must stay plain text) cleanly ends the token.
private val REFERENCE_TERMINATORS = setOf(
    ' ', '\t', '\n', '\r',
    '.', ',', ';', ':', '!', '?',
    ')', ']', '}', '"', '\'',
    '»', '«', '„', '“', '”', '-', '/', '&'
)


/**
 * Scans a verse-range suffix starting at [k] and returns the raw suffix
 * text when it matches the range grammar, or null when [k] does not start
 * a well-formed range. This mirrors the `referenceLineRegex` range group
 * so the inline scanner and the whole-line regex agree on what counts as
 * a range; semantic validation (forward direction, digit overflow) is
 * done afterwards by [parseRange].
 */
private fun scanRangeText(content: String, k: Int): String? {
    var j = k
    when (content[j]) {
        '+' -> {
            j++
            while (j < content.length && content[j].isDigit()) j++
            return content.substring(k, j)
        }
        '-' -> {
            j++
            if (j < content.length && (content[j] == '&' || content[j] == '$')) {
                // Cross-chapter end: `-&C2[&V2][+N]`.
                j++
                val c2Start = j
                while (j < content.length && content[j].isDigit()) j++
                if (j == c2Start) return null
                if (j < content.length && (content[j] == '&' || content[j] == '$')) {
                    j++
                    val v2Start = j
                    while (j < content.length && content[j].isDigit()) j++
                    if (j == v2Start) return null
                }
            } else {
                // Same-chapter end: `-V2[+N]`.
                val v2Start = j
                while (j < content.length && content[j].isDigit()) j++
                if (j == v2Start) return null
            }
            if (j < content.length && content[j] == '+') {
                j++
                while (j < content.length && content[j].isDigit()) j++
            }
            return content.substring(k, j)
        }
        else -> return null
    }
}


/**
 * Find `$Book`, `$Book&C` and `$Book&C&V` tokens embedded anywhere in
 * [content] — not just at the start of a line, so references work inside
 * a sentence ("Read $Lukas&7&1 today"). A token ends at whitespace (the
 * documented rule) or common punctuation, which stays outside the token.
 * Parts may be separated by `&` or `$` (the `$` form is accepted so
 * notes written before the `&` syntax keep working).
 *
 * Book names may be multiple words ("1 Mose") when directly followed by
 * a `&digits` continuation; a multi-word run without one is treated as
 * just its first word ("Lukas" in "Read $Lukas today"). A space directly
 * before the continuation ends the reference ("$Lukas &7" → `$Lukas`).
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
            content[i + 1] == '$' ||
            content[i + 1] == '&'
        ) {
            i++
            continue
        }

        val tokenStart = i
        val restStart = i + 1

        // Where does the book name end? At the next separator (`&` or
        // `$`), which would start the optional chapter/verse
        // continuation, or at the end of the run.
        var bookEnd = restStart
        while (bookEnd < content.length &&
            content[bookEnd] != '$' &&
            content[bookEnd] != '&'
        ) {
            bookEnd++
        }

        var book: String
        var chapter: Int? = null
        var verse: Int? = null
        // Inclusive end of an optional `+` / `+N` / `-V2` / `-&C2&V2` …
        // range, resolved via parseRange after the raw suffix is scanned.
        var endChapter: Int? = null
        var endVerse: Int? = null
        var end: Int

        // A continuation counts only when the book name abuts
        // `&digits` with no whitespace in between — a space ends the
        // reference (user rule), so "$Lukas &7" is `$Lukas` + plain text.
        val hasContinuation = bookEnd < content.length &&
            bookEnd + 1 < content.length &&
            content[bookEnd + 1].isDigit() &&
            !content[bookEnd - 1].isWhitespace()

        if (hasContinuation) {
            // `$Book&C` or `$Book&C&V`. Book is verbatim (spaces inside
            // are fine: "1 Mose&3&16").
            book = content.substring(restStart, bookEnd)
            var k = bookEnd + 1
            val cStart = k
            while (k < content.length && content[k].isDigit()) k++
            chapter = content.substring(cStart, k).toIntOrNull()
            // Optional `&Verse`.
            if (k < content.length && (content[k] == '&' || content[k] == '$') &&
                k + 1 < content.length && content[k + 1].isDigit()
            ) {
                val vStart = k + 1
                k = vStart
                while (k < content.length && content[k].isDigit()) k++
                verse = content.substring(vStart, k).toIntOrNull()
            }
            // Optional verse-range suffix (`+`, `+N`, `-V2`, `-&C2&V2`,
            // …). The raw suffix is consumed into the token (sourceEnd
            // includes it) ONLY when it resolves to a valid forward
            // range — a degenerate one (e.g. `-7` from verse 16) stays
            // a terminator so the text is preserved as plain characters.
            if (verse != null && k < content.length &&
                (content[k] == '+' || content[k] == '-')
            ) {
                val rangeStart = k
                val rawRange = scanRangeText(content, k)
                val resolved = rawRange?.let { parseRange(chapter, verse, it) }
                if (resolved != null) {
                    endChapter = resolved.endChapter
                    endVerse = resolved.endVerse
                    k = rangeStart + rawRange.length
                }
            }
            end = k
        } else {
            // No digit continuation: the reference is just `$Book`, and
            // the book name is a single word — whitespace or punctuation
            // ends it (and is excluded from the name).
            var k = restStart
            while (k < content.length &&
                content[k] != '$' &&
                content[k] != '&' &&
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

        result.add(
            ReferenceToken(book, chapter, verse, endChapter, endVerse, tokenStart, end)
        )
        i = end
    }
    return result
}


/**
 * A note-to-note link token found in running text — e.g. the
 * `[[Prayer Notes]]` inside "See [[Prayer Notes]] for more". [title] is
 * the linked note's title (the target of the link, trimmed);
 * [sourceStart] / [sourceEnd] are char offsets into the scanned string
 * (end exclusive) and INCLUDE the `[[` / `]]` delimiters, so the editor
 * can hide the brackets and render the title as a clickable chip.
 */
data class NoteLinkToken(
    val title: String,
    val sourceStart: Int,
    val sourceEnd: Int
)


private val NOTE_LINK_TOKEN_REGEX = Regex("\\[\\[([^]]+)]]")


/**
 * Find `[[Title]]` note links embedded anywhere in [content] — in
 * paragraphs, lists or the trailing text of colored quotes. The
 * delimiters are part of every token so tap/hit-testing and the visual
 * transformation agree on the exact source range to hide.
 *
 * A link whose inner text itself contains another token's marker
 * (`$` Bible reference, `@` media) is skipped and stays plain text —
 * nesting two chip kinds would produce overlapping mapping spans in
 * the editor.
 */
fun findNoteLinkTokens(content: String): List<NoteLinkToken> =
    NOTE_LINK_TOKEN_REGEX.findAll(content)
        .filter { match ->
            val inner = match.groupValues[1]
            !inner.contains('$') && !inner.contains('@')
        }
        .map { NoteLinkToken(it.groupValues[1].trim(), it.range.first, it.range.last + 1) }
        .toList()
