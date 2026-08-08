package ui

import data.BibleCatalog
import data.SettingsManager
import model.Chapter
import model.Verse
import testutil.TestEnv
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue


/**
 * Unit tests for the chapter range-copy formatter [rangeCopyText] and the
 * word-study markup stripping it relies on ([stripWordStudyMarkup]).
 * The suffix tests touch [SettingsManager], so the class redirects
 * `user.home` via [TestEnv] before the singleton initialises.
 */
class CopyTextTest {

    private val chapter = Chapter(
        chapter = 3,
        verses = listOf(
            Verse(15, "For God so loved the world."),
            Verse(16, "For God so loved the world, that he gave his only begotten Son."),
            Verse(17, "That whosoever believeth in him should not perish."),
            Verse(18, "But have everlasting life."),
            Verse(19, "And this is the condemnation.")
        )
    )

    companion object {
        init {
            // Redirect user.home to a throwaway dir BEFORE SettingsManager
            // initialises its storage path.
            TestEnv.homeDir
        }
    }


    @Test
    fun rangeIsNumberedAndFiltered() {
        val text = rangeCopyText("John", 3, 16, 18, chapter.verses)
        assertEquals(
            "John 3:16\u201318\n" +
                "16. For God so loved the world, that he gave his only begotten Son.\n" +
                "17. That whosoever believeth in him should not perish.\n" +
                "18. But have everlasting life.",
            text
        )
    }

    @Test
    fun bookNameIsTrimmed() {
        val text = rangeCopyText("  John  ", 3, 16, 16, chapter.verses)
        assertTrue(text.startsWith("John 3:16\u201316\n16. "))
    }

    @Test
    fun strongsMarkupIsStrippedFromCopiedVerses() {
        // Both word-study markup styles are stripped to plain prose.
        val marked = Chapter(
            1,
            listOf(
                Verse(1, "loved{G25}{(G5656)} the world"),
                Verse(2, "God{G2316} is love"),
                Verse(3, "\u03B7\u03B3\u03B1\u03C0\u03B7\u03C3\u03B5\u03BD G25 G5656 V-AAI-3S")
            )
        )
        val text = rangeCopyText("Strongs", 1, 1, 3, marked.verses)
        assertEquals(
            "Strongs 1:1\u20133\n" +
                "1. loved the world\n" +
                "2. God is love\n" +
                "3. \u03B7\u03B3\u03B1\u03C0\u03B7\u03C3\u03B5\u03BD",
            text
        )
    }

    @Test
    fun stripWordStudyMarkupCleansBothMarkupStyles() {
        assertEquals("loved", stripWordStudyMarkup("loved{G25}{(G5656)}"))
        assertEquals(
            "\u03B7\u03B3\u03B1\u03C0\u03B7\u03C3\u03B5\u03BD",
            stripWordStudyMarkup("\u03B7\u03B3\u03B1\u03C0\u03B7\u03C3\u03B5\u03BD G25 G5656 V-AAI-3S")
        )
        assertEquals("plain text", stripWordStudyMarkup("plain text"))
    }

    @Test
    fun emptyRangeStillHasHeader() {
        // A range with no matching verses keeps the reference header and
        // simply carries an empty body.
        val text = rangeCopyText("John", 3, 20, 25, chapter.verses)
        assertEquals("John 3:20\u201325\n", text)
    }

    @Test
    fun noSuffixWhenSettingIsOff() {
        SettingsManager.copyWithTranslationName = false
        val text = rangeCopyText("John", 3, 16, 16, chapter.verses)
        assertTrue(text.startsWith("John 3:16\u201316\n"))
    }

    @Test
    fun translationNameSuffixIsAppendedWhenEnabled() {
        SettingsManager.copyWithTranslationName = true
        try {
            // Point at a real bundled module so the catalog resolves a
            // display name. Assert it resolves — otherwise this test would
            // silently pass with an empty suffix instead of failing.
            SettingsManager.translation = "luther_1912"
            val entry = BibleCatalog.entryFor("luther_1912")
            assertTrue(entry != null, "bundled luther_1912 module must resolve")
            val expected = " (${entry!!.displayName})"
            val text = rangeCopyText("John", 3, 16, 16, chapter.verses)
            assertTrue(
                text.startsWith("John 3:16\u201316$expected\n"),
                "expected suffix '$expected' in: $text"
            )
        } finally {
            SettingsManager.copyWithTranslationName = false
        }
    }

    @Test
    fun invertedRangeHasEmptyBody() {
        // from > to yields an empty numbered body but keeps the header.
        val text = rangeCopyText("John", 3, 18, 16, chapter.verses)
        assertEquals("John 3:18\u201316\n", text)
    }
}
