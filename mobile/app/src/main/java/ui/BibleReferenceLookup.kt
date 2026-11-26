@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class
)

package ui

import data.MediaReferenceToken
import data.NoteLinkToken
import data.ReferenceToken
import data.findMediaReferenceTokens
import data.findNoteLinkTokens
import data.findReferenceTokens
import data.parseRange




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
internal fun findFirstReferenceOffset(
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
            val line = stripLeadingMarkers(source.substring(pos, lineEnd))
            val match = referenceLineRegex.matchEntire(line)
            if (match != null) {
                val lineBook = match.groupValues[1].trim()
                val lineChapter = match.groupValues[2].toIntOrNull()
                val lineVerse = match.groupValues[3].toIntOrNull()
                val lineRange = parseRange(lineChapter, lineVerse, match.groupValues[4])
                // Match the requested granularity: verse refs require the
                // chapter+verse pair (a range line matches any chapter /
                // verse it covers, possibly across chapters), chapter refs
                // require the chapter, book refs match on the book name
                // alone. For ranged lines the chapter gate accepts any
                // chapter inside the span — a `$Lukas&7&1-&8&1` chip must
                // match a requested verse in chapter 8 too — and
                // [rangeCovers] re-validates the exact (chapter, verse).
                val chapterOk = when {
                    chapter == null -> true
                    lineChapter == chapter -> true
                    lineRange != null ->
                        chapter >= lineChapter!! && chapter <= lineRange.endChapter
                    else -> false
                }
                val verseOk = when {
                    verse == null -> true
                    lineVerse == null -> false
                    lineRange != null -> rangeCovers(
                        lineChapter!!, lineVerse!!,
                        lineRange.endChapter, lineRange.endVerse,
                        chapter, verse
                    )
                    else -> lineVerse == verse
                }
                if (lineBook.equals(book, ignoreCase = true) && chapterOk && verseOk) {
                    return pos
                }
            }
        }
        if (nl == -1) break
        pos = nl + 1
    }

    // No whole-line reference matched — look for an inline token embedded
    // in a sentence (e.g. "Read $Lukas$3$16 today"), matching the same
    // granularity rules. Tokens come back in document order, so the first
    // hit is the earliest occurrence.
    findReferenceTokens(source).forEach { token ->
        // Same range-aware chapter gate as the whole-line path above:
        // a ranged token (e.g. `$Lukas&7&1-&8&1`) covers every chapter
        // in its span, not just its start chapter.
        val chapterOk = when {
            chapter == null -> true
            token.chapter == chapter -> true
            token.endVerse != null && token.chapter != null ->
                chapter >= token.chapter && chapter <= (token.endChapter ?: token.chapter)
            else -> false
        }
        val verseOk = when {
            verse == null -> true
            token.verse == null -> false
            token.endVerse != null -> rangeCovers(
                token.chapter ?: chapter ?: 0,
                token.verse!!,
                token.endChapter ?: token.chapter ?: chapter ?: 0,
                token.endVerse,
                chapter, verse
            )
            else -> token.verse == verse
        }
        if (token.book.trim().equals(book, ignoreCase = true) && chapterOk && verseOk) {
            return token.sourceStart
        }
    }
    return null
}


/**
 * True when the inclusive range [startChapter]:[startVerse] ..
 * [endChapter]:[endVerse] covers the given (chapter, verse) — including
 * cross-chapter ranges (e.g. a `$Lukas&7&1-&8&1` reference covers every
 * verse from Luk 7:1 through Luk 8:1).
 */
private fun rangeCovers(
    startChapter: Int,
    startVerse: Int,
    endChapter: Int,
    endVerse: Int,
    chapter: Int?,
    verse: Int?
): Boolean {
    if (chapter == null || verse == null) return false
    if (chapter < startChapter || chapter > endChapter) return false
    return if (startChapter == endChapter) {
        verse in startVerse..endVerse
    } else if (chapter == startChapter) {
        verse >= startVerse
    } else if (chapter == endChapter) {
        verse <= endVerse
    } else {
        true
    }
}


// ---------------------------------------------------------------------------
// Tap-to-navigate: Bible references
// ---------------------------------------------------------------------------
internal data class ReferenceMatch(
    val book: String,
    val chapter: Int?,
    val verse: Int?,
    /**
     * Chapter of the inclusive range end (see [data.ReferenceToken
     * .endChapter]) — non-null together with [endVerse] when the
     * reference carries a `+` / `-V2` / `-&C2&V2` suffix. Same-chapter
     * ranges keep this equal to [chapter]; cross-chapter ranges (e.g.
     * `$Lukas&7&1-&8&1`) store the larger chapter here. The chip renders
     * as `Book C:V-E` or `Book C:V-C2:E` and Shift+click offers a picker
     * over the range.
     */
    val endChapter: Int? = null,
    /**
     * Inclusive last verse of the extended range. Non-null whenever
     * [endChapter] is.
     */
    val endVerse: Int? = null,
    val label: String? = null
) {
    /**
     * Human-readable form of this reference, matching the chip text the
     * editor renders for a `$Book&C&V` line — e.g. `John 3:16`,
     * `John 3:16-22` (extended same-chapter range), `John 3:16-4:1`
     * (cross-chapter range), `John 3` (chapter-only) or `John`
     * (book-only). Used when copying a reference from the editor's
     * right-click menu.
     */
    fun displayText(): String = buildString {
        append(book)
        if (chapter != null) {
            append(' ')
            append(chapter)
        }
        if (verse != null) {
            append(':')
            append(verse)
            if (endVerse != null) {
                append('-')
                if (endChapter != null && endChapter != chapter) {
                    append(endChapter)
                    append(':')
                }
                append(endVerse)
            }
        }
    }
}


internal class ReferenceLookup(
    val lineStarts: IntArray,
    val byLine: Map<Int, ReferenceMatch>,
    /**
     * Inline `$Book$C$V` tokens embedded in running text (source-absolute
     * offsets) — references inside sentences rather than whole lines.
     * Scanned once for the whole document via [findReferenceTokens].
     */
    val tokens: List<ReferenceToken> = emptyList(),
    /**
     * Inline `@service:content` media tokens (YouTube, Spotify, …) with
     * source-absolute offsets, scanned once via [findMediaReferenceTokens].
     */
    val mediaTokens: List<MediaReferenceToken> = emptyList(),
    /**
     * Inline `[[Title]]` note-to-note links with source-absolute offsets,
     * scanned once via [findNoteLinkTokens]. Clicking one opens the
     * linked note in the editor.
     */
    val noteLinks: List<NoteLinkToken> = emptyList()
)

internal fun buildReferenceLookup(text: String): ReferenceLookup {
    if (text.isEmpty()) return ReferenceLookup(IntArray(0), emptyMap())
    val starts = IntArray(text.count { it == '\n' } + 1)
    val byLine = mutableMapOf<Int, ReferenceMatch>()
    val tokens = mutableListOf<ReferenceToken>()
    val mediaTokens = mutableListOf<MediaReferenceToken>()
    val noteLinks = mutableListOf<NoteLinkToken>()
    var idx = 0
    var scan = 0
    while (scan <= text.length) {
        val nl = text.indexOf('\n', scan)
        val lineEnd = if (nl == -1) text.length else nl
        val raw = text.substring(scan, lineEnd)
        // Hidden alignment + direction markers are editor-only: strip
        // them so a centered / right-aligned reference line still
        // matches (`\u200B$Lukas$3$16` → `$Lukas$3$16`).
        val stripped = stripLeadingMarkers(raw)
        val wholeLineMatch = referenceLineRegex.matchEntire(stripped)
        if (wholeLineMatch != null) {
            val lineChapter = wholeLineMatch.groupValues[2].toIntOrNull()
            val lineVerse = wholeLineMatch.groupValues[3].toIntOrNull()
            val lineRange = parseRange(lineChapter, lineVerse, wholeLineMatch.groupValues[4])
            byLine[scan] = ReferenceMatch(
                book = wholeLineMatch.groupValues[1].trim(),
                chapter = lineChapter,
                verse = lineVerse,
                endChapter = lineRange?.endChapter,
                endVerse = lineRange?.endVerse,
                label = wholeLineMatch.groupValues[5].trim().ifBlank { null }
            )
        } else {
            // `[[Title]]` note-to-note links are scanned on EVERY line
            // (whole-line refs are handled by byLine above; note links
            // are a different token kind that can also sit in
            // colored-quote trailing text — where the transformation
            // renders them as chips — so they must stay hit-testable
            // there too).
            findNoteLinkTokens(raw).forEach { token ->
                noteLinks.add(
                    token.copy(
                        sourceStart = token.sourceStart + scan,
                        sourceEnd = token.sourceEnd + scan
                    )
                )
            }
            if (coloredQuoteRegex.matchEntire(stripped) == null) {
                // Inline `$Book$C$V` tokens in ordinary lines. Colored-quote
                // lines render their text verbatim (the transformation never
                // chips tokens there), so excluding them keeps click-through
                // consistent with what is actually drawn; whole-line refs are
                // covered by byLine above.
                findReferenceTokens(raw).forEach { token ->
                    tokens.add(
                        token.copy(
                            sourceStart = token.sourceStart + scan,
                            sourceEnd = token.sourceEnd + scan
                        )
                    )
                }
                // Same for `@service:content` media tokens — including
                // whole-line media refs, which never match the Bible
                // referenceLineRegex and so land in this branch.
                findMediaReferenceTokens(raw).forEach { token ->
                    mediaTokens.add(
                        token.copy(
                            sourceStart = token.sourceStart + scan,
                            sourceEnd = token.sourceEnd + scan
                        )
                    )
                }
            }
        }
        starts[idx++] = scan
        if (nl == -1) break
        scan = lineEnd + 1
    }
    return ReferenceLookup(starts.copyOf(idx), byLine, tokens, mediaTokens, noteLinks)
}


/**
 * A tap/hover hit inside the editor — either a Bible reference or a
 * media reference. Keeps the two kinds apart so the editor can render
 * the right actions for each (navigate to Bible vs. open the media
 * preview / browser).
 */
internal sealed interface ReferenceHit {
    data class Bible(val match: ReferenceMatch) : ReferenceHit
    data class Media(val token: MediaReferenceToken) : ReferenceHit
    /** A `[[Title]]` note-to-note link — opens the linked note. */
    data class Note(val title: String) : ReferenceHit
}

internal fun findReferenceInLookup(lookup: ReferenceLookup, sourcePos: Int): ReferenceHit? {
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
    val lineMatch = lookup.byLine[lookup.lineStarts[lineIdx]]
    if (lineMatch != null) return ReferenceHit.Bible(lineMatch)

    // No whole-line reference on this line — check inline tokens inside
    // its running text (e.g. "Read $Lukas$3$16 today"). Only a source
    // position strictly inside a token counts, so clicks on the plain
    // text around a chip resolve to nothing.
    if (lookup.tokens.isNotEmpty()) {
        val token = findTokenContaining(
            lookup.tokens,
            clamped,
            { it.sourceStart },
            { it.sourceEnd }
        )
        if (token != null) {
            return ReferenceHit.Bible(
                ReferenceMatch(
                    book = token.book.trim(),
                    chapter = token.chapter,
                    verse = token.verse,
                    endChapter = token.endChapter,
                    endVerse = token.endVerse
                )
            )
        }
    }
    // Media references (`@youtube:ID`, …) — same containment rule.
    if (lookup.mediaTokens.isNotEmpty()) {
        val token = findTokenContaining(
            lookup.mediaTokens,
            clamped,
            { it.sourceStart },
            { it.sourceEnd }
        )
        if (token != null) {
            return ReferenceHit.Media(token)
        }
    }
    // Note-to-note links (`[[Title]]`) — same containment rule. Checked
    // last so a `$Book` reference or media token wins over a link that
    // happens to wrap around it.
    if (lookup.noteLinks.isNotEmpty()) {
        val token = findTokenContaining(
            lookup.noteLinks,
            clamped,
            { it.sourceStart },
            { it.sourceEnd }
        )
        if (token != null) {
            return ReferenceHit.Note(token.title)
        }
    }
    return null
}


/**
 * Rightmost token whose start is <= [pos]; only returned when [pos] also
 * lies strictly before its end. Works for any token type exposing
 * `sourceStart` / `sourceEnd` (Bible [ReferenceToken] and media
 * [MediaReferenceToken]) — the lists are pre-sorted by start.
 */
private fun <T> findTokenContaining(
    tokens: List<T>,
    pos: Int,
    startOf: (T) -> Int,
    endOf: (T) -> Int
): T? {
    var lo = 0
    var hi = tokens.size - 1
    var candidate: T? = null
    while (lo <= hi) {
        val mid = (lo + hi) ushr 1
        val token = tokens[mid]
        if (startOf(token) <= pos) {
            candidate = token
            lo = mid + 1
        } else {
            hi = mid - 1
        }
    }
    return candidate?.takeIf { pos < endOf(it) }
}


