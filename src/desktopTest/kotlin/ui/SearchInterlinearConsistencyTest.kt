package ui

import data.BibleRepository
import kotlinx.coroutines.runBlocking
import model.Book
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue


/**
 * Consistency between the full-text search's Strong's reverse concordance
 * ([searchBible] with a `G####` query) and the interlinear Greek verse
 * lookup ([BibleRepository.greekVersesForChapter]) over the REAL bundled
 * modules — the English \"KJV with Strongs\" and the parsed Greek TR
 * (trparsed). In the search-results view the interlinear line under each
 * NT match is resolved through `greekVersesForChapter(...)[verse]`, so the
 * two subsystems must agree on which verses a given Strong's number
 * covers: a number the search finds in the English text must be present in
 * the Greek text the interlinear would show, and vice versa. These parse
 * actual resource files (~6.5 MB each), but [BibleRepository]'s per-module
 * cache means the whole suite pays each parse once (shared with
 * [GreekInterlinearTest]).
 */
class SearchInterlinearConsistencyTest {

    companion object {
        private val greekBooks: List<Book> by lazy {
            runBlocking { BibleRepository.loadModule(BibleRepository.INTERLINEAR_MODULE_ID) }
        }
        // The Strong's-marked KJV (kjv_strongs.json — module id is the
        // file name without extension). The plain "kjv" module carries no
        // `word{G####}` tokens, so it could never feed the reverse
        // concordance.
        private val kjvBooks: List<Book> by lazy {
            runBlocking { BibleRepository.loadModule("kjv_strongs") }
        }
        // trparsed is New-Testament-only, so the comparison is scoped to
        // books 40–66 (matching the reading view's Testament.OLD cutoff).
        private val kjvNt: List<Book> by lazy {
            kjvBooks.filter { it.book in 40..66 }
        }
    }

    // The Strong's numbers (lemma + TVM) a verse's word-study tokens carry
    // — the same rule [searchBible] uses to reverse-look-up a query.
    private fun numbersIn(text: String): Set<String> =
        parseWordStudyTokens(text).flatMap { listOfNotNull(it.number, it.tvm) }.toSet()

    // Every (book, chapter, verse) whose Greek TR text the production
    // interlinear lookup resolves AND whose tokens carry [number] — the
    // interlinear-coverage side of the comparison.
    private fun coveredVerses(number: String): Set<Triple<Int, Int, Int>> = buildSet {
        for (book in greekBooks) {
            for (chapter in book.chapters) {
                val greek = BibleRepository.greekVersesForChapter(
                    greekBooks,
                    book.book,
                    chapter.chapter
                )
                for ((verseNumber, text) in greek) {
                    if (number in numbersIn(text)) {
                        add(Triple(book.book, chapter.chapter, verseNumber))
                    }
                }
            }
        }
    }

    @Test
    fun strongsSearchReturnsExactlyTheInterlinearCoverage() {
        // G25 — the lemma of "loved" in John 3:16 — is a number on which
        // the English KJV and the Greek TR agree exactly (no verse-number
        // offsets, no translation divergences), so the reverse-concordance
        // search over the whole English NT must return EXACTLY the verses
        // whose Greek counterpart carries G25. This is the core contract:
        // a number the search finds in English and a number the interlinear
        // can show must cover the same verse set.
        val query = "G25"
        val searched = searchBible(kjvNt, query)
            .map { Triple(it.book.book, it.chapter, it.verse) }
            .toSet()
        val covered = coveredVerses(query)
        assertEquals(covered, searched, "search hit set must equal the G25 interlinear coverage")
        // Sanity: the comparison isn't vacuously empty — G25 covers 109 NT
        // verses, including John 3:16.
        assertTrue(searched.contains(Triple(43, 3, 16)), "John 3:16 must carry G25")
    }

    @Test
    fun everyStrongsSearchHitResolvesToAGreekVerse() {
        // UI-critical invariant: in the search-results view every Strong's
        // match renders its interlinear line via
        // `greekVersesForChapter(...)[verse]`, so a search hit must never
        // silently lack a Greek counterpart. Sweep every distinct Strong's
        // number of every KJV NT chapter through the same chapter-scoped
        // search the pane runs, and collect the hits the interlinear lookup
        // cannot resolve. (Each search is scoped to its own chapter — the
        // production `sliceBooksForScope(CHAPTER)` path — keeping the sweep
        // linear in the data instead of per-number over the whole NT.)
        val unresolved = mutableSetOf<Triple<Int, Int, Int>>()
        for (book in kjvNt) {
            for (chapter in book.chapters) {
                val greek = BibleRepository.greekVersesForChapter(
                    greekBooks,
                    book.book,
                    chapter.chapter
                )
                val chapterNumbers = chapter.verses.flatMap { numbersIn(it.text) }.toSet()
                for (number in chapterNumbers) {
                    val slice = sliceBooksForScope(
                        listOf(book),
                        BibleSearchScope.CHAPTER,
                        book.book,
                        chapter.chapter
                    )
                    val hits = searchBible(slice, number).map {
                        Triple(it.book.book, it.chapter, it.verse)
                    }
                    for (hit in hits) {
                        if (!greek.containsKey(hit.third)) unresolved.add(hit)
                    }
                }
            }
        }
        // The single documented exception is the classic text-critical
        // verse-numbering difference: KJV 2 Cor 13:14 (the closing
        // benediction) is not a separate verse in the Greek TR module.
        // Fail loudly if the data drifts beyond that.
        assertEquals(
            setOf(Triple(47, 13, 14)),
            unresolved,
            "expected only 2 Cor 13:14 to be unresolvable, got $unresolved"
        )
    }
}
