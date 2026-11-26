package ui

/**
 * A Bible navigation target. [chapter] and [verse] are nullable so a
 * reference can point at three levels of granularity:
 *  - whole book    (chapter = null, verse = null)
 *  - whole chapter (chapter set,  verse = null)
 *  - single verse  (chapter set,  verse set)
 *
 * [endChapter] / [endVerse] describe an optional inclusive end of a verse
 * range (`John 3:16-18`, `Luke 7:1-8:1`). They are non-null together and
 * never precede the start. Carrying the end here means one reference can
 * highlight / export every verse it covers instead of only its first.
 */
data class BibleReferenceSelection(
    val book: String,
    val chapter: Int? = null,
    val verse: Int? = null,
    val endChapter: Int? = null,
    val endVerse: Int? = null
) {
    /** True when [verse] carries a resolved extended range. */
    val hasRange: Boolean
        get() = verse != null && endVerse != null

    /**
     * True when this reference covers (or, for a chapter-level reference,
     * points at) [verseChapter]:[verseNumber] — ranges are inclusive and
     * may span chapters.
     */
    fun highlights(verseChapter: Int, verseNumber: Int): Boolean {
        val startChapter = chapter ?: return false
        val startVerse = verse
        val lastChapter = endChapter ?: startChapter
        if (startVerse == null) {
            // Chapter / book-level reference: every verse of the chapter
            // (or every chapter in a chapter span) is covered.
            return verseChapter in startChapter..lastChapter
        }
        val lastVerse = endVerse ?: startVerse
        if (verseChapter < startChapter || verseChapter > lastChapter) return false
        return when {
            startChapter == lastChapter -> verseNumber in startVerse..lastVerse
            verseChapter == startChapter -> verseNumber >= startVerse
            verseChapter == lastChapter -> verseNumber <= lastVerse
            else -> true
        }
    }

    /**
     * Human-readable form: `John 3:16`, `John 3:16-18` (same-chapter range),
     * `Luke 7:1-8:1` (cross-chapter range), `John 3` or `John`.
     */
    fun displayText(): String = buildString {
        append(book)
        chapter?.let { c ->
            append(' ')
            append(c)
            verse?.let { v ->
                append(':')
                append(v)
                endVerse?.let { end ->
                    append('-')
                    val endC = endChapter
                    if (endC != null && endC != c) {
                        append(endC)
                        append(':')
                    }
                    append(end)
                }
            }
        }
    }
}
