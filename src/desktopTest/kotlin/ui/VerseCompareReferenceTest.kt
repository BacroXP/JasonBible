package ui

import data.BibleCatalog
import model.Book
import model.Chapter
import model.Verse
import testutil.TestEnv
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue


/**
 * Tests for the pure helpers behind [VerseCompareScreen]:
 * [resolveCompareReference] (initial reference → book/chapter/verse with
 * clamping) and [defaultCompareSelection] (active translation + same
 * language). Both are pure functions, so they're exercised with hand-built
 * models and fake catalog entries — no resource scanning, no parsing.
 */
class VerseCompareReferenceTest {

    companion object {
        init {
            TestEnv.homeDir
        }
    }

    private val books = listOf(
        Book(
            book = 43,
            name = "John",
            chapters = listOf(
                Chapter(3, listOf(Verse(16, "a"), Verse(17, "b"))),
                Chapter(4, listOf(Verse(1, "c")))
            )
        ),
        Book(
            book = 19,
            name = "Psalms",
            chapters = listOf(
                Chapter(23, listOf(Verse(1, "d")))
            )
        )
    )

    private fun entry(moduleId: String, language: String, displayName: String = moduleId) =
        BibleCatalog.BibleEntry(
            moduleId = moduleId,
            displayName = displayName,
            languageName = language,
            languageFolder = "XX-$language",
            resourcePath = "bible/XX-$language/$moduleId.json"
        )

    @Test
    fun resolvesExactReference() {
        assertEquals(
            Triple(43, 3, 16),
            resolveCompareReference(books, BibleReferenceSelection("John", 3, 16))
        )
    }

    @Test
    fun resolvesByCanonicalNumberAcrossLanguages() {
        // "Johannes" (German) isn't in the active books' names, but the
        // alias index maps it to book 43 — matched via the number.
        assertEquals(
            Triple(43, 3, 16),
            resolveCompareReference(books, BibleReferenceSelection("Johannes", 3, 16))
        )
    }

    @Test
    fun clampsMissingVerseToFirstOfChapter() {
        assertEquals(
            Triple(43, 3, 16),
            resolveCompareReference(books, BibleReferenceSelection("John", 3, 99))
        )
    }

    @Test
    fun clampsMissingChapterAndVerseToFirstOfBook() {
        // Chapter 99 doesn't exist → falls back to the book's first
        // chapter; verse 5 doesn't exist there → first verse of it.
        assertEquals(
            Triple(43, 3, 16),
            resolveCompareReference(books, BibleReferenceSelection("John", 99, 5))
        )
    }

    @Test
    fun defaultsToFirstBookWhenNoReference() {
        assertEquals(
            Triple(43, 3, 16),
            resolveCompareReference(books, null)
        )
    }

    @Test
    fun chapterOnlyReferenceUsesFirstVerse() {
        assertEquals(
            Triple(43, 4, 1),
            resolveCompareReference(books, BibleReferenceSelection("John", 4, null))
        )
    }

    @Test
    fun defaultSelectionIncludesActiveTranslationAndItsLanguage() {
        val entries = listOf(
            entry("luther_1912", "German"),
            entry("schlachter", "German"),
            entry("kjv", "English"),
            entry("web", "English")
        )
        val selected = defaultCompareSelection(entries, "luther_1912")
        assertEquals(setOf("luther_1912", "schlachter"), selected)
    }

    @Test
    fun defaultSelectionFallsBackToFirstEntryWithoutActiveModule() {
        val entries = listOf(
            entry("luther_1912", "German"),
            entry("kjv", "English")
        )
        val selected = defaultCompareSelection(entries, null)
        assertEquals(setOf("luther_1912"), selected)
    }

    @Test
    fun unknownActiveModuleFallsBackToFirstEntry() {
        val entries = listOf(
            entry("luther_1912", "German"),
            entry("kjv", "English")
        )
        val selected = defaultCompareSelection(entries, "does-not-exist")
        assertEquals(setOf("luther_1912"), selected)
    }

    @Test
    fun translationTogglingKeepsIdempotentSets() {
        val entries = listOf(
            entry("luther_1912", "German"),
            entry("kjv", "English")
        )
        var selected = defaultCompareSelection(entries, "kjv")
        assertTrue("kjv" in selected && "luther_1912" !in selected)
        // Toggle the English group off and the German group on.
        selected = selected - setOf("kjv") + setOf("luther_1912")
        assertTrue("luther_1912" in selected && "kjv" !in selected)
    }
}
