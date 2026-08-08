package ui

import data.NotesRepository
import data.SettingsManager
import model.Book
import model.Chapter
import model.Verse
import testutil.TestEnv
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue


/**
 * Unit tests for the global Ctrl+F search's pure logic: [parseReferenceQuery]
 * (free-text Bible references like "John 3, 16" / "1 Mose 3:16", including
 * multi-word and cross-language book names) and [searchGlobal] (promoting
 * books by the book threshold, drilling sub-threshold books down to lone
 * chapters / verses by the chapter threshold, and name-matched books that
 * never mention the query in their text). Fixtures are tiny synthetic
 * modules; `user.home` is redirected via [TestEnv] so note searching never
 * touches real files.
 */
class GlobalSearchTest {

    companion object {
        init {
            TestEnv.homeDir
            // The repository seeds bundled sample notes on first access
            // unless this flag is set; tests write their own files.
            SettingsManager.notesInitialized = true
        }
    }

    private fun v(n: Int, text: String) = Verse(n, text)

    private val genesis = Book(
        book = 1,
        name = "Genesis",
        chapters = listOf(
            Chapter(
                1,
                listOf(
                    v(1, "In the beginning God created the heaven and the earth.")
                )
            )
        )
    )

    // Matthew's text mentions John (the Baptist) in chapters 1 and 3:
    // chapter 1 twice, chapter 3 once (3 matches total).
    private val matthew = Book(
        book = 40,
        name = "Matthew",
        chapters = listOf(
            Chapter(
                1,
                listOf(
                    v(1, "The book of the generation of John."),
                    v(2, "John the Baptist appeared.")
                )
            ),
            Chapter(
                3,
                listOf(
                    v(1, "In those days came John the Baptist.")
                )
            )
        )
    )

    // John's own text never says "John", so only its NAME matches.
    private val john = Book(
        book = 43,
        name = "John",
        chapters = listOf(
            Chapter(
                3,
                listOf(
                    v(16, "For God so loved the world, that he gave his only begotten Son.")
                )
            ),
            Chapter(
                4,
                listOf(
                    v(5, "Then cometh he to a city of Samaria, which is called Sychar.")
                )
            )
        )
    )

    private val books = listOf(genesis, matthew, john)

    private fun resolve(name: String): Book? =
        books.firstOrNull { it.name.equals(name, ignoreCase = true) }


    // ------------------------------------------------------------------
    // parseReferenceQuery
    // ------------------------------------------------------------------

    @Test
    fun parsesBookChapterVerseWithGermanComma() {
        val ref = parseReferenceQuery("John 3, 16", ::resolve)
        assertNotNull(ref)
        assertEquals("John", ref.book.name)
        assertEquals(3, ref.chapter)
        assertEquals(16, ref.verse)
    }

    @Test
    fun parsesColonSeparatedQueries() {
        val ref = parseReferenceQuery("John 3:16", ::resolve)
        assertNotNull(ref)
        assertEquals(3, ref.chapter)
        assertEquals(16, ref.verse)
    }

    @Test
    fun parsesChapterOnlyQueries() {
        val ref = parseReferenceQuery("John 3", ::resolve)
        assertNotNull(ref)
        assertEquals("John", ref.book.name)
        assertEquals(3, ref.chapter)
        assertNull(ref.verse)
    }

    @Test
    fun parsesBookOnlyQueries() {
        val ref = parseReferenceQuery("John", ::resolve)
        assertNotNull(ref)
        assertEquals("John", ref.book.name)
        assertNull(ref.chapter)
        assertNull(ref.verse)
    }

    @Test
    fun parsesMultiWordBookNames() {
        val genesisGerman = Book(
            book = 1,
            name = "1 Mose",
            chapters = emptyList()
        )
        val ref = parseReferenceQuery("1 Mose 3, 16") { name ->
            if (name.equals("1 Mose", ignoreCase = true)) genesisGerman else null
        }
        assertNotNull(ref)
        assertEquals("1 Mose", ref.book.name)
        assertEquals(3, ref.chapter)
        assertEquals(16, ref.verse)
    }

    @Test
    fun leadingNumberBelongsToTheBookName() {
        // "3 Johannes 5" — the leading "3" is part of the book name, the
        // trailing "5" is the chapter.
        val thirdJohn = Book(64, "3 Johannes", emptyList())
        val ref = parseReferenceQuery("3 Johannes 5") { name ->
            if (name.equals("3 Johannes", ignoreCase = true)) thirdJohn else null
        }
        assertNotNull(ref)
        assertEquals("3 Johannes", ref.book.name)
        assertEquals(5, ref.chapter)
        assertNull(ref.verse)
    }

    @Test
    fun dottedNumericReferencesParse() {
        // "1. Mose 3.16" — the German-style period separators normalize to
        // spaces, leaving the book name "1 Mose".
        val genesisGerman = Book(1, "1 Mose", emptyList())
        val ref = parseReferenceQuery("1. Mose 3.16") { name ->
            if (name.equals("1 Mose", ignoreCase = true)) genesisGerman else null
        }
        assertNotNull(ref)
        assertEquals("1 Mose", ref.book.name)
        assertEquals(3, ref.chapter)
        assertEquals(16, ref.verse)
    }

    @Test
    fun trailingPunctuationDoesNotBreakTheParse() {
        // "John 3, 16!" / "John 3:16?" — sentence-ending punctuation is
        // stripped per word before the trailing-integer scan.
        val bang = parseReferenceQuery("John 3, 16!", ::resolve)
        assertNotNull(bang)
        assertEquals(3, bang.chapter)
        assertEquals(16, bang.verse)

        val question = parseReferenceQuery("John 3:16?", ::resolve)
        assertNotNull(question)
        assertEquals(16, question.verse)
    }

    @Test
    fun nonReferenceQueriesReturnNull() {
        assertNull(parseReferenceQuery("love", ::resolve))
        assertNull(parseReferenceQuery("", ::resolve))
        assertNull(parseReferenceQuery("   ", ::resolve))
        assertNull(parseReferenceQuery("3", ::resolve))
        // Trailing junk keeps the whole thing from resolving as a book.
        assertNull(parseReferenceQuery("John xyz", ::resolve))
    }


    // ------------------------------------------------------------------
    // searchGlobal — threshold promotion + drill-down
    // ------------------------------------------------------------------

    @Test
    fun bookPromotionDependsOnTheBookThreshold() {
        // Matthew has 3 "John" matches. Below the book threshold it is NOT
        // promoted; its matches drill down to lone chapters / verses.
        val drilled = searchGlobal(
            books, "John", matchCase = false,
            bookThreshold = 5, chapterThreshold = 2
        )
        assertTrue(drilled.books.none { it.book.name == "Matthew" })
        assertEquals(1, drilled.loneChapters.size)
        assertEquals(1, drilled.loneChapters[0].chapter)
        assertEquals(2, drilled.loneChapters[0].matchCount)
        assertEquals(1, drilled.loneVerses.size)
        assertEquals("Matthew 3:1", drilled.loneVerses[0].let {
            "${it.book.name} ${it.chapter}:${it.verse}"
        })

        // At/over the book threshold the whole book is promoted; the
        // chapter threshold then filters the chapters shown when expanded.
        val promoted = searchGlobal(
            books, "John", matchCase = false,
            bookThreshold = 3, chapterThreshold = 2
        )
        val matthew = promoted.books.first { it.book.name == "Matthew" }
        // Only chapter 1 (2 matches ≥ 2) qualifies; chapter 3 (1 match) is
        // hidden — the book is the result.
        assertEquals(listOf(1), matthew.chapters.map { it.chapter })
        assertEquals(3, matthew.totalMatches)
        assertTrue(promoted.loneChapters.isEmpty())
        assertTrue(promoted.loneVerses.isEmpty())
    }

    @Test
    fun expandedChaptersFallBackToAllWhenNoneQualify() {
        // Matthew is promoted (3 matches ≥ 3), but a chapter threshold above
        // every chapter's count would empty the expansion — it falls back
        // to ALL matching chapters so the book still opens somewhere.
        val promoted = searchGlobal(
            books, "John", matchCase = false,
            bookThreshold = 3, chapterThreshold = 4
        )
        val matthew = promoted.books.first { it.book.name == "Matthew" }
        assertEquals(listOf(1, 3), matthew.chapters.map { it.chapter })
        assertEquals(2, matthew.chapters.first { it.chapter == 1 }.matchCount)
    }

    @Test
    fun chapterPromotionDependsOnTheChapterThreshold() {
        // A low chapter threshold keeps the chapter as a lone chapter…
        val chapters = searchGlobal(
            books, "John", matchCase = false,
            bookThreshold = 5, chapterThreshold = 2
        )
        assertEquals(listOf(1), chapters.loneChapters.map { it.chapter })
        assertEquals(listOf(3), chapters.loneVerses.map { it.chapter })

        // …a high chapter threshold breaks it into individual verses.
        val verses = searchGlobal(
            books, "John", matchCase = false,
            bookThreshold = 5, chapterThreshold = 3
        )
        assertTrue(verses.loneChapters.isEmpty())
        assertEquals(3, verses.loneVerses.size)
        assertEquals(
            listOf("Matthew 1:1", "Matthew 1:2", "Matthew 3:1"),
            verses.loneVerses.map { "${it.book.name} ${it.chapter}:${it.verse}" }
        )

        // …and the chapter threshold can never drop below a single match.
        val single = searchGlobal(
            books, "John", matchCase = false,
            bookThreshold = 5, chapterThreshold = 1
        )
        assertEquals(listOf(1, 3), single.loneChapters.map { it.chapter })
        assertTrue(single.loneVerses.isEmpty())
    }

    @Test
    fun nameMatchedBooksAlwaysGetPromoted() {
        // John's text never says "John" — the name match promotes it even
        // with a huge book threshold, while text-only Matthew (3 matches)
        // stays below the threshold and drills down to lone verses.
        val results = searchGlobal(
            books, "John", matchCase = false,
            bookThreshold = 100, chapterThreshold = 3
        )
        val johnGroup = results.books.first { it.book.name == "John" }
        assertTrue(johnGroup.nameMatched)
        assertEquals(0, johnGroup.totalMatches)
        // Name-only books list the whole book so the result stays usable.
        assertEquals(listOf(3, 4), johnGroup.chapters.map { it.chapter })
        assertTrue(results.books.none { it.book.name == "Matthew" })
        assertEquals(3, results.loneVerses.size)
    }

    @Test
    fun nameMatchedBooksAreIncludedWithoutTextMatches() {
        // Genesis's text never contains "Genesis".
        val results = searchGlobal(
            books, "Genesis", matchCase = false, wholeWord = false,
            bookThreshold = 5, chapterThreshold = 3
        )
        val group = results.books.single()
        assertTrue(group.nameMatched)
        assertEquals(0, group.totalMatches)
        assertEquals(listOf(1), group.chapters.map { it.chapter })
        assertEquals(0, group.chapters.single().matchCount)
    }

    @Test
    fun resultsFollowCanonicalBookOrder() {
        // "God" hits Genesis 1:1 and John 3:16 — with a threshold of 1 both
        // books are promoted and listed in canonical book-number order
        // (1 before 43), not query-alphabetical.
        val results = searchGlobal(
            books, "God", matchCase = false, wholeWord = false,
            bookThreshold = 1, chapterThreshold = 1
        )
        assertEquals(listOf("Genesis", "John"), results.books.map { it.book.name })
    }

    @Test
    fun matchCaseIsHonoured() {
        // "GOD" matches Genesis 1:1 case-insensitively…
        assertTrue(
            searchGlobal(
                books, "GOD", matchCase = false, wholeWord = false,
                bookThreshold = 1, chapterThreshold = 1
            ).books.isNotEmpty()
        )
        // …but a case-sensitive scan finds nothing.
        assertTrue(
            searchGlobal(
                books, "GOD", matchCase = true, wholeWord = false,
                bookThreshold = 1, chapterThreshold = 1
            ).books.isEmpty()
        )
    }

    @Test
    fun wholeWordFiltersSubstringMatches() {
        // "day" occurs inside "Today" (verse 1) and standalone (verse 2).
        val book = Book(
            book = 40,
            name = "Matthew",
            chapters = listOf(
                Chapter(1, listOf(v(1, "Today is bright."), v(2, "A day of rest.")))
            )
        )
        // Substring scan finds both verses.
        val loose = searchGlobal(
            listOf(book), "day", matchCase = false,
            wholeWord = false, bookThreshold = 1, chapterThreshold = 1
        )
        assertEquals(2, loose.books.single().totalMatches)

        // Whole-word scan only finds the standalone "day".
        val exact = searchGlobal(
            listOf(book), "day", matchCase = false,
            wholeWord = true, bookThreshold = 1, chapterThreshold = 1
        )
        val group = exact.books.single()
        assertEquals(1, group.totalMatches)
        // The standalone "day" is verse 2 of chapter 1.
        assertEquals(listOf(1), group.chapters.map { it.chapter })
        assertEquals(1, group.chapters.single().matchCount)
    }

    @Test
    fun wholeWordAppliesToNotes() {
        NotesRepository.saveNoteInPlace(
            "global-search-ww.note",
            "# Beta\nI like pineapple and apple.\n"
        )
        try {
            // Substring "apple" matches (inside "pineapple" + standalone);
            // whole-word "apple" only the standalone occurrence.
            assertEquals(
                1,
                searchGlobal(
                    books, "apple", matchCase = false, wholeWord = false,
                    bookThreshold = 5, chapterThreshold = 3
                ).notes.size
            )
            assertEquals(
                1,
                searchGlobal(
                    books, "apple", matchCase = false, wholeWord = true,
                    bookThreshold = 5, chapterThreshold = 3
                ).notes.size
            )
            // "pine" is a substring of "pineapple": whole-word finds it
            // only without the whole-word restriction.
            assertTrue(
                searchGlobal(
                    books, "pine", matchCase = false, wholeWord = true,
                    bookThreshold = 5, chapterThreshold = 3
                ).notes.isEmpty()
            )
            assertEquals(
                1,
                searchGlobal(
                    books, "pine", matchCase = false, wholeWord = false,
                    bookThreshold = 5, chapterThreshold = 3
                ).notes.size
            )
        } finally {
            NotesRepository.deleteNote("global-search-ww.note")
        }
    }

    // ------------------------------------------------------------------
    // globalSearchRecents — recent-queries dropdown data
    // ------------------------------------------------------------------

    @Test
    fun globalSearchRecentsDedupeAndCap() {
        try {
            // Blank queries never enter the list.
            SettingsManager.addGlobalSearchRecent("   ")
            assertTrue(SettingsManager.globalSearchRecents.isEmpty())

            // Newest first, capped at 10.
            for (i in 1..12) {
                SettingsManager.addGlobalSearchRecent("query $i")
            }
            assertEquals(10, SettingsManager.globalSearchRecents.size)
            assertEquals("query 12", SettingsManager.globalSearchRecents.first())
            assertEquals("query 3", SettingsManager.globalSearchRecents.last())

            // Re-adding an existing query (new spelling) moves it to the
            // front without growing the list.
            SettingsManager.addGlobalSearchRecent("QUERY 12")
            assertEquals("QUERY 12", SettingsManager.globalSearchRecents.first())
            assertEquals(10, SettingsManager.globalSearchRecents.size)
        } finally {
            SettingsManager.globalSearchRecents = emptyList()
        }
    }


    // ------------------------------------------------------------------
    // topBibleResultReference — Ctrl+Enter quick-jump
    // ------------------------------------------------------------------

    @Test
    fun ctrlEnterPicksTheExactReference() {
        // A query that parses as a reference wins over every other result.
        val ref = topBibleResultReference(
            books, "John 3, 16", matchCase = false,
            bookThreshold = 1, chapterThreshold = 1, wholeWord = false
        )
        assertNotNull(ref)
        assertEquals("John", ref.book)
        assertEquals(3, ref.chapter)
        assertEquals(16, ref.verse)
    }

    @Test
    fun ctrlEnterPicksTheFirstPromotedBook() {
        // "day" is not a reference; with a low book threshold the whole
        // book is promoted and the jump lands on its first qualifying
        // chapter (chapter 1 has 2 of the 3 matches).
        val dayBook = Book(
            book = 40,
            name = "Matthew",
            chapters = listOf(
                Chapter(1, listOf(v(1, "A day of rest."), v(2, "Another day came."))),
                Chapter(3, listOf(v(1, "The day after.")))
            )
        )
        val ref = topBibleResultReference(
            listOf(dayBook), "day", matchCase = false,
            bookThreshold = 3, chapterThreshold = 2, wholeWord = false
        )
        assertNotNull(ref)
        assertEquals("Matthew", ref.book)
        assertEquals(1, ref.chapter)
        assertNull(ref.verse)
    }

    @Test
    fun ctrlEnterPicksTheFirstLoneChapter() {
        // Below the book threshold, the first lone chapter (2 matches) is
        // the top Bible result.
        val dayBook = Book(
            book = 40,
            name = "Matthew",
            chapters = listOf(
                Chapter(1, listOf(v(1, "A day of rest."), v(2, "Another day came."))),
                Chapter(3, listOf(v(1, "The day after.")))
            )
        )
        val ref = topBibleResultReference(
            listOf(dayBook), "day", matchCase = false,
            bookThreshold = 5, chapterThreshold = 2, wholeWord = false
        )
        assertNotNull(ref)
        assertEquals("Matthew", ref.book)
        assertEquals(1, ref.chapter)
        assertNull(ref.verse)
    }

    @Test
    fun ctrlEnterPicksTheFirstLoneVerse() {
        // A chapter threshold above every chapter's count breaks the
        // matches into lone verses; Matthew 1:1 is the first.
        val dayBook = Book(
            book = 40,
            name = "Matthew",
            chapters = listOf(
                Chapter(1, listOf(v(1, "A day of rest."), v(2, "Another day came."))),
                Chapter(3, listOf(v(1, "The day after.")))
            )
        )
        val ref = topBibleResultReference(
            listOf(dayBook), "day", matchCase = false,
            bookThreshold = 5, chapterThreshold = 4, wholeWord = false
        )
        assertNotNull(ref)
        assertEquals("Matthew", ref.book)
        assertEquals(1, ref.chapter)
        assertEquals(1, ref.verse)
    }

    @Test
    fun ctrlEnterWithoutBibleHitsReturnsNull() {
        // A query matching only notes has no Bible result to jump to.
        NotesRepository.saveNoteInPlace(
            "global-search-ctrl-enter.note",
            "# Gamma\npeanut butter\n"
        )
        try {
            val ref = topBibleResultReference(
                books, "peanut", matchCase = false,
                bookThreshold = 5, chapterThreshold = 3, wholeWord = false
            )
            assertNull(ref)
        } finally {
            NotesRepository.deleteNote("global-search-ctrl-enter.note")
        }
    }

    @Test
    fun blankQueryReturnsNothing() {
        val results = searchGlobal(
            books, "   ", matchCase = false, wholeWord = false,
            bookThreshold = 5, chapterThreshold = 3
        )
        assertTrue(results.books.isEmpty())
        assertTrue(results.loneChapters.isEmpty())
        assertTrue(results.loneVerses.isEmpty())
        assertTrue(results.notes.isEmpty())
    }

    @Test
    fun notesAreSearched() {
        NotesRepository.saveNoteInPlace(
            "global-search-test.note",
            "# Alpha\nMatch me John here\n"
        )
        try {
            val results = searchGlobal(
                books, "John", matchCase = false, wholeWord = false,
                bookThreshold = 5, chapterThreshold = 3
            )
            val hit = results.notes.firstOrNull { it.note.title == "Alpha" }
            assertNotNull(hit, "the seeded note must appear in the note hits")
            assertTrue(hit.lineText.contains("John", ignoreCase = true))
        } finally {
            // Don't leave the note behind — other tests enumerate notes.
            NotesRepository.deleteNote("global-search-test.note")
        }
    }


    // ------------------------------------------------------------------
    // resolveBookName — cross-language alias fallback
    // ------------------------------------------------------------------

    @Test
    fun resolveBookNameFallsBackToCrossLanguageAlias() {
        // The bundled books_*.json lists map "Johannes" → book 43, so a
        // German reference still resolves against an English module.
        val book = resolveBookName(listOf(john), "Johannes")
        assertNotNull(book)
        assertEquals(43, book.book)
    }
}
