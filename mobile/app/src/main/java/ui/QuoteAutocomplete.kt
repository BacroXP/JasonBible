package ui

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import data.DEFAULT_ACCENT_ARGB
import data.findReferenceTokens
import model.Book
import model.Verse


// ---------------------------------------------------------------------------
// Quote autocomplete ("citations")
//
// While the caret sits at the end of a line, the editor watches what the
// user is typing. If the trailing text looks like the START of a Bible
// verse ("For god so loved…"), the full verse is suggested in a bar below
// the editor; pressing Tab inserts it as a BLUE colored quote with its
// reference chip behind it:
//
//     "For God so loved the world, … everlasting life."[#3B82F6] $John&3&16
//
// After the first insert the NEXT verse is suggested; Tab again appends it
// inside the quote and corrects the reference to a range:
//
//     "…life. For God sent not his Son…"[#3B82F6] $John&3&16-17
//
// All matching / insertion logic lives here as pure functions so it is
// directly unit-testable; NotesScreen wires it to the editor state.
// ---------------------------------------------------------------------------

/** The blue used for inserted citation quotes — the app's default accent
 *  blue, kept in `#RRGGBB` string form for the note markup. */
internal val CITE_BLUE_HEX: String = "#%06X".format(DEFAULT_ACCENT_ARGB and 0xFFFFFF)

/**
 * Minimum normalized length of a typed prefix before a verse is suggested
 * (6 chars ≈ two short words), so a stray "For" never lights up the bar.
 */
internal const val MIN_CITE_PREFIX_LEN = 6

/** Characters that end a sentence — a fresh quote prefix starts after one. */
private val SENTENCE_ENDERS = setOf('.', '!', '?', ';', ':', '\u2026')


/** A resolved verse of the active translation, with cleaned (markup-free)
 *  text — the payload shown in the suggestion bar and inserted on Tab. */
internal data class CiteVerse(
    val book: Book,
    val chapter: Int,
    val verse: Int,
    val text: String
)


/** A citation the editor is currently suggesting. */
internal sealed interface CiteSuggestion {
    val book: Book
    val chapter: Int
    val verse: Int
    val text: String
}


/**
 * Fresh suggestion: the user typed [text] at [prefixStart]..[prefixEnd] and
 * the verse begins with it — Tab replaces that typed prefix with the full
 * colored quote + reference.
 */
internal data class FreshCiteSuggestion(
    override val book: Book,
    override val chapter: Int,
    override val verse: Int,
    override val text: String,
    val prefixStart: Int,
    val prefixEnd: Int
) : CiteSuggestion


/**
 * Chained suggestion: the caret line already is a colored quote ending in a
 * reference chip (`"verse"[#hex] $Book&C&V[-E]`); the verse FOLLOWING the
 * cited range is suggested. Tab appends it inside the quote and corrects
 * the reference to an extended range. [lineStart]..[lineEnd] bounds the
 * colored-quote line in the source.
 */
internal data class ChainCiteSuggestion(
    override val book: Book,
    override val chapter: Int,
    override val verse: Int,
    override val text: String,
    val lineStart: Int,
    val lineEnd: Int
) : CiteSuggestion


/** The verse's display text: Strong's / parsed markup stripped. */
internal fun cleanVerseText(verse: Verse): String = stripWordStudyMarkup(verse.text).trim()


/**
 * Lowercases, drops every non-letter/digit and collapses whitespace, so the
 * typed phrase and the verse text compare leniently: "For god so loved..."
 * and "For God so loved the world, that…" both normalize to
 * "for god so loved the world that". This is what makes the autocomplete
 * tolerate punctuation the user hasn't typed yet.
 */
internal fun normalizeForMatch(text: String): String =
    text.lowercase()
        .map { if (it.isLetterOrDigit()) it else ' ' }
        .joinToString("")
        .split(' ')
        .filter { it.isNotEmpty() }
        .joinToString(" ")


/**
 * The trailing text the caret could be completing, or null when the editor
 * isn't in a state that suggests a fresh citation: the caret must sit at
 * the END of its line, the line (after stripping block markers) must be
 * non-empty, must not itself be a reference / colored-quote line (those are
 * handled by [computeChainSuggestion] / the $Book autocomplete), and the
 * prefix after the last sentence boundary must be long enough (>= two
 * words, [MIN_CITE_PREFIX_LEN] chars).
 */
internal fun quotePrefixAt(text: String, caret: Int): CitePrefix? {
    if (caret < 0 || caret > text.length) return null
    // The caret must be at the end of its line — a suggestion while the
    // user is still typing in the middle of a sentence would be noise.
    if (caret < text.length && text[caret] != '\n') return null
    val lineStart = text.lastIndexOf('\n', caret - 1) + 1
    val lineUpTo = text.substring(lineStart, caret)
    val stripped = stripBlockPrefixForCite(lineUpTo)
    if (stripped.isEmpty()) return null
    // Reference lines (`$Book…`) have their own autocomplete; colored
    // quotes without a reference have nothing to chain from. Both are left
    // alone here.
    if (stripped.startsWith('$') || stripped.startsWith('"')) return null

    // The quote prefix is the text after the LAST sentence boundary, so
    // "Important. For god so loved" still suggests John 3:16. Trailing
    // sentence punctuation / whitespace is trimmed BEFORE the boundary
    // scan, so finishing the phrase with a period or an ellipsis ("For
    // god so loved...") keeps the suggestion alive instead of leaving an
    // empty prefix after the final dot.
    val trimmedLine = stripped.trimEnd { it in SENTENCE_ENDERS || it.isWhitespace() }
    var boundary = -1
    for (i in trimmedLine.indices) {
        if (trimmedLine[i] in SENTENCE_ENDERS) boundary = i
    }
    var prefixStartRel = boundary + 1
    while (prefixStartRel < trimmedLine.length && trimmedLine[prefixStartRel].isWhitespace()) {
        prefixStartRel++
    }
    val prefix = trimmedLine.substring(prefixStartRel)
    val normalized = normalizeForMatch(prefix)
    if (normalized.length < MIN_CITE_PREFIX_LEN) return null
    if (!normalized.contains(' ')) return null

    val contentStart = lineStart + (lineUpTo.length - stripped.length)
    return CitePrefix(
        start = contentStart + prefixStartRel,
        end = caret,
        text = normalized
    )
}


/** A typed prefix to match: its source range plus the normalized text. */
internal data class CitePrefix(val start: Int, val end: Int, val text: String)


/**
 * Removes the leading alignment / direction markers and block prefixes
 * (headings, quote chains incl. `> .` / `> #` items, bullets, numbered
 * lists) from a source line, leaving the plain text the user typed.
 */
private fun stripBlockPrefixForCite(line: String): String {
    var s = stripLeadingMarkers(line)
    if (s.startsWith("## ")) s = s.removePrefix("## ")
    else if (s.startsWith("# ")) s = s.removePrefix("# ")
    val quoteDepth = s.takeWhile { it == '>' }.length
    if (quoteDepth > 0) {
        s = s.drop(quoteDepth).trimStart(' ', '.', '#')
        if (s.startsWith(" ")) s = s.drop(1)
    }
    if (s.startsWith("- ")) s = s.removePrefix("- ")
    orderedListRegex.matchAt(s, 0)?.let { s = s.drop(it.value.length) }
    return s
}


/**
 * First verse of [books] (canonical order) whose CLEANED text starts with
 * the normalized [prefix], or null when nothing matches. Runs a full scan
 * (the prefix check is an early-exit startsWith, so matching verses are
 * found fast; callers debounce on a background thread anyway).
 */
internal fun findFreshCite(books: List<Book>, prefix: String): CiteVerse? {
    val target = normalizeForMatch(prefix)
    if (target.length < MIN_CITE_PREFIX_LEN || !target.contains(' ')) return null
    for (book in books) {
        for (chapter in book.chapters) {
            for (verse in chapter.verses) {
                val clean = cleanVerseText(verse)
                if (normalizeForMatch(clean).startsWith(target)) {
                    return CiteVerse(book, chapter.chapter, verse.verse, clean)
                }
            }
        }
    }
    return null
}


/**
 * Chain detection: when the caret line is a colored quote whose trailing
 * part ends in a resolved verse reference (`"text"[#hex] $Book&C&V` or with
 * a range), and the quoted text genuinely ends with the last cited verse's
 * cleaned text, the NEXT verse after the cited range is suggested. Returns
 * null for every other line.
 */
internal fun computeChainSuggestion(
    books: List<Book>,
    text: String,
    caret: Int
): ChainCiteSuggestion? {
    if (caret < 0 || caret > text.length) return null
    if (caret < text.length && text[caret] != '\n') return null
    val lineStart = text.lastIndexOf('\n', caret - 1) + 1
    val line = text.substring(lineStart, caret)
    val quote = coloredQuoteRegex.matchEntire(line) ?: return null
    val inner = quote.groupValues[1]
    val trailing = quote.groupValues[3]
    // The trailing part must end with a verse reference token.
    val token = findReferenceTokens(trailing).firstOrNull { token ->
        token.chapter != null && token.verse != null &&
            token.sourceEnd == trailing.trimEnd().length
    } ?: return null
    // NOTE: the book resolves against the ACTIVE translation's names, so a
    // citation written in another language (e.g. `$Lukas&7&1` in a German
    // note while an English module is active) won't chain. Feature-
    // generated citations always use the active book name, so chaining
    // works for everything the autocomplete itself inserts.
    val book = books.firstOrNull { it.name.equals(token.book.trim(), ignoreCase = true) }
        ?: return null
    val lastChapter = token.endChapter ?: token.chapter!!
    val lastVerse = token.endVerse ?: token.verse!!
    // Only chain when the quoted text actually contains the cited verse —
    // appending to a quote that doesn't match would corrupt it.
    val lastText = findVerse(books, book, lastChapter, lastVerse)?.let { cleanVerseText(it) }
        ?: return null
    if (!inner.trimEnd().endsWith(lastText)) return null
    val next = nextVerse(books, book, lastChapter, lastVerse) ?: return null
    return ChainCiteSuggestion(
        book = next.first,
        chapter = next.second,
        verse = next.third,
        text = cleanVerseText(findVerse(books, next.first, next.second, next.third)!!),
        lineStart = lineStart,
        lineEnd = caret
    )
}


/** [Verse] at (book, chapter, verse) in [books], or null. */
internal fun findVerse(books: List<Book>, book: Book, chapter: Int, verse: Int): Verse? =
    book.chapters.firstOrNull { it.chapter == chapter }
        ?.verses?.firstOrNull { it.verse == verse }


/**
 * The verse immediately after (book, chapter, verse) in canonical order —
 * next verse of the chapter, else the next chapter's verse 1, else the
 * next book's verse 1. Null when there is no following verse.
 */
internal fun nextVerse(
    books: List<Book>,
    book: Book,
    chapter: Int,
    verse: Int
): Triple<Book, Int, Int>? {
    val chapterData = book.chapters.firstOrNull { it.chapter == chapter }
    if (chapterData != null) {
        chapterData.verses.firstOrNull { it.verse == verse + 1 }?.let {
            return Triple(book, chapter, it.verse)
        }
    }
    book.chapters.firstOrNull { it.chapter == chapter + 1 }?.let { nextChapter ->
        nextChapter.verses.firstOrNull()?.let {
            return Triple(book, nextChapter.chapter, it.verse)
        }
    }
    val index = books.indexOfFirst { it.book == book.book }
    if (index >= 0 && index + 1 < books.size) {
        val nextBook = books[index + 1]
        val firstChapter = nextBook.chapters.firstOrNull() ?: return null
        val firstVerse = firstChapter.verses.firstOrNull() ?: return null
        return Triple(nextBook, firstChapter.chapter, firstVerse.verse)
    }
    return null
}


/**
 * The insertion a FRESH suggestion produces: the full verse as a blue
 * colored quote with the reference chip behind it, plus a trailing space
 * so the reference token cleanly terminates (chips end at whitespace).
 */
internal fun buildCitationText(
    verseText: String,
    bookName: String,
    chapter: Int,
    verse: Int
): String = "\"$verseText\"[$CITE_BLUE_HEX] \$$bookName&$chapter&$verse "


/**
 * The `$Book&C&V` reference token with its end corrected to an extended
 * range: same chapter → `-V2` suffix, cross-chapter → `-&C2&V2`.
 */
internal fun buildRangeReference(
    book: String,
    startChapter: Int,
    startVerse: Int,
    endChapter: Int,
    endVerse: Int
): String = if (endChapter == startChapter) {
    "\$$book&$startChapter&$startVerse-$endVerse"
} else {
    "\$$book&$startChapter&$startVerse-&$endChapter&$endVerse"
}


/**
 * Apply a citation suggestion to the editor state.
 *
 * FRESH replaces the typed prefix with the colored quote + reference and
 * parks the caret at the end of the line (where the chain suggestion for
 * the next verse then appears).
 *
 * CHAIN appends the suggested verse to the existing colored quote's inner
 * text (joined by a space) and corrects the trailing reference to a range
 * covering the whole quote.
 */
internal fun applyCiteSuggestion(
    current: TextFieldValue,
    suggestion: CiteSuggestion
): TextFieldValue = when (suggestion) {
    is FreshCiteSuggestion -> {
        val replacement = buildCitationText(
            suggestion.text,
            suggestion.book.name,
            suggestion.chapter,
            suggestion.verse
        )
        val newText = current.text.substring(0, suggestion.prefixStart) +
            replacement +
            current.text.substring(suggestion.prefixEnd)
        current.copy(
            text = newText,
            selection = TextRange(suggestion.prefixStart + replacement.length)
        )
    }

    is ChainCiteSuggestion -> {
        val lineStart = suggestion.lineStart
        val lineEnd = suggestion.lineEnd.coerceAtMost(current.text.length)
        val line = current.text.substring(lineStart, lineEnd)
        val quote = coloredQuoteRegex.matchEntire(line) ?: return current
        val inner = quote.groupValues[1]
        val hex = quote.groupValues[2].ifBlank { CITE_BLUE_HEX }
        val trailing = quote.groupValues[3]
        val token = findReferenceTokens(trailing)
            .firstOrNull { it.chapter != null && it.verse != null }
            ?: return current
        val newInner = inner.trimEnd() + " " + suggestion.text
        val ref = buildRangeReference(
            book = token.book.trim(),
            startChapter = token.chapter!!,
            startVerse = token.verse!!,
            endChapter = suggestion.chapter,
            endVerse = suggestion.verse
        )
        // The regex captures the hex WITHOUT the `#` — re-add it so the
        // rebuilt line stays valid `"quote"[#hex]` markup.
        val newLine = "\"$newInner\"[#$hex] $ref "
        val newText = current.text.substring(0, lineStart) +
            newLine +
            current.text.substring(lineEnd)
        current.copy(
            text = newText,
            selection = TextRange(lineStart + newLine.length)
        )
    }
}
