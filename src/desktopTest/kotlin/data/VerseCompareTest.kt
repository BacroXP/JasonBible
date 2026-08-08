package data

import model.Book
import model.Chapter
import model.Verse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull


/**
 * Tests for [BibleRepository.verseTextFor] — the pure per-module verse
 * lookup that backs the verse-comparison screen. Uses hand-built [Book]
 * models (no file I/O) so the extraction logic is tested in isolation:
 * found verse, missing verse / chapter / book, and a null module.
 */
class VerseCompareTest {

    private val books = listOf(
        Book(
            book = 43,
            name = "John",
            chapters = listOf(
                Chapter(3, listOf(Verse(16, "For God so loved the world"))),
                Chapter(4, listOf(Verse(1, "Therefore the Lord knew")))
            )
        ),
        Book(
            book = 19,
            name = "Psalms",
            chapters = listOf(
                Chapter(23, listOf(Verse(1, "The LORD is my shepherd")))
            )
        )
    )

    @Test
    fun returnsTextForExistingVerse() {
        assertEquals(
            "For God so loved the world",
            BibleRepository.verseTextFor(books, 43, 3, 16)
        )
    }

    @Test
    fun returnsNullForMissingVerse() {
        assertNull(BibleRepository.verseTextFor(books, 43, 3, 17))
    }

    @Test
    fun returnsNullForMissingChapter() {
        assertNull(BibleRepository.verseTextFor(books, 43, 2, 16))
    }

    @Test
    fun returnsNullForMissingBook() {
        assertNull(BibleRepository.verseTextFor(books, 44, 3, 16))
    }

    @Test
    fun returnsNullForNullModule() {
        assertNull(BibleRepository.verseTextFor(null, 43, 3, 16))
    }
}
