package ui

/**
 * A Bible navigation target. [chapter] and [verse] are nullable so a
 * reference can point at three levels of granularity:
 *  - whole book    (chapter = null, verse = null)
 *  - whole chapter (chapter set,  verse = null)
 *  - single verse  (chapter set,  verse set)
 */
data class BibleReferenceSelection(
    val book: String,
    val chapter: Int? = null,
    val verse: Int? = null
)
