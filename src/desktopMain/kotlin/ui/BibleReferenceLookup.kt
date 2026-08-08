@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class
)

package ui

import data.MediaReferenceToken
import data.ReferenceToken
import data.findMediaReferenceTokens
import data.findReferenceTokens




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

    // No whole-line reference matched — look for an inline token embedded
    // in a sentence (e.g. "Read $Lukas$3$16 today"), matching the same
    // granularity rules. Tokens come back in document order, so the first
    // hit is the earliest occurrence.
    findReferenceTokens(source).forEach { token ->
        val chapterOk = chapter == null || token.chapter == chapter
        val verseOk = verse == null || token.verse == verse
        if (token.book.trim().equals(book, ignoreCase = true) && chapterOk && verseOk) {
            return token.sourceStart
        }
    }
    return null
}


// ---------------------------------------------------------------------------
// Tap-to-navigate: Bible references
// ---------------------------------------------------------------------------
internal data class ReferenceMatch(
    val book: String,
    val chapter: Int?,
    val verse: Int?,
    val label: String? = null
) {
    /**
     * Human-readable form of this reference, matching the chip text the
     * editor renders for a `$Book$C$V` line — e.g. `John 3:16`,
     * `John 3` (chapter-only) or `John` (book-only). Used when copying
     * a reference from the editor's right-click menu.
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
        }
    }
}


internal data class ReferenceLookup(
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
    val mediaTokens: List<MediaReferenceToken> = emptyList()
)

internal fun buildReferenceLookup(text: String): ReferenceLookup {
    if (text.isEmpty()) return ReferenceLookup(IntArray(0), emptyMap())
    val starts = IntArray(text.count { it == '\n' } + 1)
    val byLine = mutableMapOf<Int, ReferenceMatch>()
    val tokens = mutableListOf<ReferenceToken>()
    val mediaTokens = mutableListOf<MediaReferenceToken>()
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
        val wholeLineMatch = referenceLineRegex.matchEntire(stripped)
        if (wholeLineMatch != null) {
            byLine[scan] = ReferenceMatch(
                book = wholeLineMatch.groupValues[1].trim(),
                chapter = wholeLineMatch.groupValues[2].toIntOrNull(),
                verse = wholeLineMatch.groupValues[3].toIntOrNull(),
                label = wholeLineMatch.groupValues[4].trim().ifBlank { null }
            )
        } else if (coloredQuoteRegex.matchEntire(stripped) == null) {
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
        starts[idx++] = scan
        if (nl == -1) break
        scan = lineEnd + 1
    }
    return ReferenceLookup(starts.copyOf(idx), byLine, tokens, mediaTokens)
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
                    verse = token.verse
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
    return null
}


/**
 * Rightmost token whose start is <= [pos]; only returned when [pos] also
 * lies strictly before its end. Works for any token type exposing
 * [sourceStart] / [sourceEnd] (Bible [ReferenceToken] and media
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


