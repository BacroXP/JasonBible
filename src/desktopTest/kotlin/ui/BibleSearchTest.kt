package ui

import data.findMatchIn
import model.Book
import model.Chapter
import model.Verse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue


/**
 * Unit tests for the pure matching logic of the Bible full-text search:
 * [searchBible] (whole-word, case, Strong's reverse concordance, the
 * book/chapter slices the scope filter feeds it) and [findMatchIn].
 * Fixtures are tiny synthetic modules, so the tests never touch disk or
 * the Compose runtime.
 */
class BibleSearchTest {

    private fun v(n: Int, text: String) = Verse(n, text)

    private val genesis = Book(
        book = 1,
        name = "Genesis",
        chapters = listOf(
            Chapter(
                1,
                listOf(
                    v(1, "In the beginning God created the heaven and the earth."),
                    v(2, "And the earth was without form, and void."),
                    v(3, "And God said, Let there be light.")
                )
            ),
            Chapter(
                2,
                listOf(
                    v(1, "Thus the heavens and the earth were finished."),
                    v(2, "And on the seventh day God ended his work.")
                )
            ),
            Chapter(
                3,
                listOf(
                    v(16, "For God so loved the world, that he gave his only begotten Son."),
                    v(17, "For God sent not his Son into the world.")
                )
            )
        )
    )

    private val exodus = Book(
        book = 2,
        name = "Exodus",
        chapters = listOf(
            Chapter(
                1,
                listOf(
                    v(1, "Now these are the names of the children of Israel."),
                    v(2, "God said unto Moses, I AM THAT I AM.")
                )
            )
        )
    )

    // Verses in BOTH word-study markup styles: the KJV `word{G####}`
    // braces and the parsed Greek module's `word G#### G#### CODE`.
    private val strongsBook = Book(
        book = 3,
        name = "Strongs",
        chapters = listOf(
            Chapter(
                1,
                listOf(
                    v(1, "loved{G25}{(G5656)} the world"),
                    v(2, "God{G2316} is love"),
                    v(3, "\u03B7\u03B3\u03B1\u03C0\u03B7\u03C3\u03B5\u03BD G25 G5656 V-AAI-3S \u03BF \u03B8\u03B5\u03BF\u03C2"),
                    v(4, "unclean{H1693} by the law")
                )
            )
        )
    )


    // ------------------------------------------------------------------
    // Whole-word & case matching
    // ------------------------------------------------------------------

    @Test
    fun wholeWordMatchesRealWordsOnly() {
        val book = Book(
            9,
            "Test",
            listOf(
                Chapter(
                    1,
                    listOf(
                        v(1, "a day of rest"),
                        v(2, "today is the day"),
                        v(3, "daylight saving")
                    )
                )
            )
        )
        // Loose matching counts the substrings inside "today"/"daylight".
        assertEquals(3, searchBible(listOf(book), "day", wholeWord = false).size)
        // Whole-word matching only keeps the two standalone "day" hits.
        assertEquals(2, searchBible(listOf(book), "day", wholeWord = true).size)
    }

    @Test
    fun matchCaseIsHonoured() {
        // "GOD" is never in the fixture as written, so case-sensitive finds
        // nothing while the default case-insensitive scan matches "God".
        assertTrue(searchBible(listOf(genesis), "GOD", matchCase = false).isNotEmpty())
        assertTrue(searchBible(listOf(genesis), "GOD", matchCase = true).isEmpty())
    }

    @Test
    fun findMatchInRespectsFlags() {
        assertEquals(
            2,
            findMatchIn("a day of rest", "day", 0, matchCase = false, wholeWord = true)
        )
        assertEquals(
            -1,
            findMatchIn("today", "day", 0, matchCase = false, wholeWord = true)
        )
        // The first hit is inside "daylight"; the whole-word walk lands on
        // the second, standalone occurrence.
        assertEquals(
            9,
            findMatchIn("daylight day", "day", 0, matchCase = false, wholeWord = true)
        )
        // The `from` index skips the first hit.
        assertEquals(
            4,
            findMatchIn("day day", "day", 1, matchCase = false, wholeWord = false)
        )
        // Case-sensitive matching refuses a lowercase query on "God".
        assertEquals(
            -1,
            findMatchIn("God", "god", 0, matchCase = true, wholeWord = false)
        )
    }


    // ------------------------------------------------------------------
    // Plain-text search
    // ------------------------------------------------------------------

    @Test
    fun plainQueryIsSubstringSearch() {
        val results = searchBible(listOf(genesis), "world")
        assertEquals(listOf(3 to 16, 3 to 17), results.map { it.chapter to it.verse })
    }

    @Test
    fun blankQueryReturnsNothing() {
        assertTrue(searchBible(listOf(genesis, exodus), "").isEmpty())
        assertTrue(searchBible(listOf(genesis, exodus), "   ").isEmpty())
    }


    // ------------------------------------------------------------------
    // Strong's reverse concordance
    // ------------------------------------------------------------------

    @Test
    fun strongsQueryFindsBraceMarkupAndParsedTokens() {
        // G25 is the lemma number of both `loved{G25}` and the parsed
        // Greek token `\u03B7\u03B3\u03B1\u03C0\u03B7\u03C3\u03B5\u03BD G25 ...`.
        val results = searchBible(listOf(strongsBook), "G25")
        assertEquals(listOf(1 to 1, 1 to 3), results.map { it.chapter to it.verse })
    }

    @Test
    fun strongsQueryIsCaseInsensitive() {
        assertEquals(2, searchBible(listOf(strongsBook), "g25").size)
        assertEquals(1, searchBible(listOf(strongsBook), "G2316").size)
    }

    @Test
    fun strongsQueryMatchesTvmCodes() {
        // G5656 is the TVM code on the parsed token (1:3). The KJV verse
        // carries it only in its parsing slot "(G5656)", which the reverse
        // lookup deliberately does not match — so only 1:3 is found.
        val results = searchBible(listOf(strongsBook), "G5656")
        assertEquals(listOf(1 to 3), results.map { it.chapter to it.verse })
    }

    @Test
    fun strongsQueryMatchesHebrewNumbers() {
        val results = searchBible(listOf(strongsBook), "h1693")
        assertEquals(listOf(1 to 4), results.map { it.chapter to it.verse })
    }

    @Test
    fun strongsQueryYieldsNothingWithoutMarkup() {
        // Plain modules have no word-study tokens to reverse-look-up.
        assertTrue(searchBible(listOf(genesis), "G25").isEmpty())
    }

    @Test
    fun strongsQueryWithUnknownNumberIsEmpty() {
        assertTrue(searchBible(listOf(strongsBook), "G9999").isEmpty())
    }

    @Test
    fun strongsQueryIgnoresWholeWordAndCaseFlags() {
        // The reverse-concordance path deliberately skips the whole-word /
        // match-case rules — a G-number either occurs in the verse's tokens
        // or it doesn't. Locking that in guards a future refactor.
        assertEquals(2, searchBible(listOf(strongsBook), "G25", wholeWord = true).size)
        assertEquals(2, searchBible(listOf(strongsBook), "g25", matchCase = true).size)
    }


    // ------------------------------------------------------------------
    // Scope narrowing — [sliceBooksForScope] narrows the books list the
    // search scans, so these assert the slicing contract itself and the
    // degradation paths (no book / chapter open).
    // ------------------------------------------------------------------

    @Test
    fun allScopePassesBooksThroughUnchanged() {
        val books = listOf(genesis, exodus)
        assertTrue(sliceBooksForScope(books, BibleSearchScope.ALL, 1, 1) === books)
        // Even with a selection present, ALL never narrows.
        assertEquals(
            books,
            sliceBooksForScope(books, BibleSearchScope.ALL, 1, 2)
        )
    }

    @Test
    fun bookScopeKeepsOnlyTheOpenBook() {
        val books = listOf(genesis, exodus)
        val slice = sliceBooksForScope(books, BibleSearchScope.BOOK, 1, null)
        assertEquals(listOf(genesis), slice)
        // The chapter selection is irrelevant to the BOOK scope.
        assertEquals(
            listOf(exodus),
            sliceBooksForScope(books, BibleSearchScope.BOOK, 2, 1)
        )
        // A book number that isn't in the list narrows to nothing.
        assertTrue(sliceBooksForScope(books, BibleSearchScope.BOOK, 99, null).isEmpty())
    }

    @Test
    fun bookScopeWithoutSelectionDegradesToWholeBible() {
        val books = listOf(genesis, exodus)
        assertEquals(
            books,
            sliceBooksForScope(books, BibleSearchScope.BOOK, null, null)
        )
    }

    @Test
    fun chapterScopeRebuildsOpenBookWithSingleChapter() {
        val slice = sliceBooksForScope(
            listOf(genesis, exodus),
            BibleSearchScope.CHAPTER,
            1,
            2
        )
        assertEquals(1, slice.size)
        assertEquals(genesis.book, slice[0].book)
        assertEquals(listOf(2), slice[0].chapters.map { it.chapter })
        // A book number that isn't in the list narrows to nothing (same
        // as the BOOK scope).
        assertTrue(
            sliceBooksForScope(
                listOf(genesis, exodus),
                BibleSearchScope.CHAPTER,
                99,
                1
            ).isEmpty()
        )
    }

    @Test
    fun chapterScopeWithoutSelectionDegradesToWholeBible() {
        val books = listOf(genesis, exodus)
        // Neither book nor chapter open → no narrowing at all.
        assertEquals(
            books,
            sliceBooksForScope(books, BibleSearchScope.CHAPTER, null, null)
        )
        // Book open but no chapter → still no narrowing.
        assertEquals(
            books,
            sliceBooksForScope(books, BibleSearchScope.CHAPTER, 1, null)
        )
    }

    @Test
    fun chapterScopeWithMissingChapterKeepsTheBook() {
        // A chapter number the book doesn't contain degrades to the whole
        // book (never an empty slice) — the scope filter must not turn a
        // stale selection into a "no matches" wall.
        val slice = sliceBooksForScope(
            listOf(genesis, exodus),
            BibleSearchScope.CHAPTER,
            1,
            99
        )
        assertEquals(listOf(genesis), slice)
        assertEquals(3, slice[0].chapters.size)
    }

    @Test
    fun searchRunsOverTheSlicedBooks() {
        // End-to-end: slice first, then scan — the integration the pane
        // performs before every search. Genesis 1:1, 1:3, 2:2, 3:16, 3:17
        // + Exodus 1:2 on the full list; BOOK scope feeds just Genesis
        // (5 hits); CHAPTER scope only Genesis 1 (2 hits).
        val all = searchBible(listOf(genesis, exodus), "God")
        assertEquals(6, all.size)

        val bookSlice = sliceBooksForScope(
            listOf(genesis, exodus),
            BibleSearchScope.BOOK,
            1,
            null
        )
        val bookScope = searchBible(bookSlice, "God")
        assertEquals(5, bookScope.size)
        assertEquals(setOf(1), bookScope.map { it.book.book }.toSet())

        val chapterSlice = sliceBooksForScope(
            listOf(genesis, exodus),
            BibleSearchScope.CHAPTER,
            1,
            1
        )
        val chapterScope = searchBible(chapterSlice, "God")
        assertEquals(2, chapterScope.size) // Genesis 1:1 + 1:3
        assertTrue(chapterScope.all { it.chapter == 1 })
    }

    @Test
    fun resultsFollowCanonicalOrder() {
        val results = searchBible(listOf(genesis, exodus), "God")
        assertEquals(
            listOf(
                "Genesis 1:1",
                "Genesis 1:3",
                "Genesis 2:2",
                "Genesis 3:16",
                "Genesis 3:17",
                "Exodus 1:2"
            ),
            results.map { "${it.book.name} ${it.chapter}:${it.verse}" }
        )
    }
}
