package data

import kotlinx.coroutines.runBlocking
import model.Book
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue


/**
 * Tests the interlinear Greek verse lookup against the REAL bundled
 * trparsed module (the parsed Greek New Testament) and the English KJV,
 * verifying that the interlinear covers books 40–66 with no numbering
 * holes. These parse actual resource files (~6.5 MB each), but
 * [BibleRepository]'s per-module cache means the whole suite pays each
 * parse once.
 */
class GreekInterlinearTest {

    companion object {
        // Loaded once per JVM (BibleRepository's module cache makes the
        // parse a one-time cost shared by every test).
        private val greekBooks: List<Book> by lazy {
            runBlocking { BibleRepository.loadModule(BibleRepository.INTERLINEAR_MODULE_ID) }
        }
        private val kjvBooks: List<Book> by lazy {
            runBlocking { BibleRepository.loadModule("kjv") }
        }
    }

    @Test
    fun greekModuleCoversExactlyTheNewTestamentBooks() {
        val books = greekBooks
        assertEquals(27, books.size)
        assertEquals((40..66).toList(), books.map { it.book }.sorted())
    }

    @Test
    fun greekModuleHasNoStructuralGaps() {
        // Every book's chapters are 1..N and every chapter's verses are
        // 1..M, so ANY valid reference into the module resolves — the
        // interlinear lookup can never miss because of numbering holes.
        for (book in greekBooks) {
            val chapters = book.chapters.map { it.chapter }
            assertEquals((1..chapters.size).toList(), chapters, "book ${book.book} chapters")
            for (chapter in book.chapters) {
                val verses = chapter.verses.map { it.verse }
                assertEquals(
                    (1..verses.size).toList(),
                    verses,
                    "book ${book.book} chapter ${chapter.chapter} verses"
                )
            }
        }
    }

    @Test
    fun interlinearCoversTheWholeEnglishNewTestament() {
        // Walk the full English KJV New Testament through the production
        // lookup ([BibleRepository.greekVersesForChapter]) and collect
        // every verse that has no Greek counterpart.
        val missing = ArrayList<Triple<Int, Int, Int>>()
        for (book in kjvBooks) {
            if (book.book < 40) continue
            for (chapter in book.chapters) {
                val greek = BibleRepository.greekVersesForChapter(
                    greekBooks,
                    book.book,
                    chapter.chapter
                )
                for (verse in chapter.verses) {
                    if (!greek.containsKey(verse.verse)) {
                        missing.add(Triple(book.book, chapter.chapter, verse.verse))
                    }
                }
            }
        }
        // 7956 of 7957 KJV NT verses resolve. The single exception is the
        // classic text-critical verse-numbering difference: KJV 2 Cor
        // 13:14 (the closing benediction) is not a separate verse in the
        // Greek TR module. Fail loudly if the data drifts beyond that.
        assertTrue(
            missing.size == 1 && missing[0] == Triple(47, 13, 14),
            "expected only 2 Cor 13:14 to be missing, got $missing"
        )
    }

    @Test
    fun greekVersesForChapterHandlesOldTestamentAndUnknownReferences() {
        // OT books have no Greek (trparsed is NT-only)…
        assertTrue(BibleRepository.greekVersesForChapter(greekBooks, 1, 1).isEmpty())
        // …and unknown chapters / books / unloaded modules yield empty maps.
        assertTrue(BibleRepository.greekVersesForChapter(greekBooks, 43, 999).isEmpty())
        assertTrue(BibleRepository.greekVersesForChapter(greekBooks, 999, 1).isEmpty())
        assertTrue(BibleRepository.greekVersesForChapter(null, 43, 3).isEmpty())
    }

    @Test
    fun greekVerseLookupResolvesJohn316() {
        val chapter = BibleRepository.greekVersesForChapter(greekBooks, 43, 3)
        val john316 = chapter[16]
        assertNotNull(john316, "John 3:16 should have a Greek TR verse")
        assertTrue(john316!!.contains("G25"), "John 3:16 should carry Strong's markup: $john316")
    }

    @Test
    fun greekVersesForBookBuildsWholeBookLookup() {
        // The whole-book lookup (continuous reading) resolves the same
        // reference keyed by (chapter, verse)…
        val john = BibleRepository.greekVersesForBook(greekBooks, 43)
        val john316 = john[3 to 16]
        assertNotNull(john316)
        assertTrue(john316!!.contains("G25"))
        // …and is empty for Old-Testament books.
        assertTrue(BibleRepository.greekVersesForBook(greekBooks, 1).isEmpty())
        assertTrue(BibleRepository.greekVersesForBook(null, 43).isEmpty())
    }
}
