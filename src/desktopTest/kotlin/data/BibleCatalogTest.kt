package data

import testutil.TestEnv
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull


/**
 * Tests for [BibleCatalog.nameToBookNumber] — the cross-language
 * book-name → canonical-number alias index built from every bundled
 * `Extras/books_*.json` list. These assertions exercise the real bundled
 * data (full names in multiple languages, short names, the matching
 * alias fields and the German abbreviations), so a corrupted or trimmed
 * resource file is caught by the suite.
 */
class BibleCatalogTest {

    companion object {
        init {
            TestEnv.homeDir
        }
    }

    @Test
    fun fullNamesResolveAcrossLanguages() {
        val index = BibleCatalog.nameToBookNumber
        // English
        assertEquals(43, index["john"])
        assertEquals(42, index["luke"])
        assertEquals(1, index["genesis"])
        // German (the app's default) — same canonical numbers
        assertEquals(43, index["johannes"])
        assertEquals(42, index["lukas"])
        assertEquals(1, index["1 mose"])
    }

    @Test
    fun englishAbbreviationsResolve() {
        val index = BibleCatalog.nameToBookNumber
        assertEquals(1, index["gen"])
        assertEquals(43, index["jn"])
        assertEquals(43, index["jhn"])
        assertEquals(43, index["joh"]) // added for the $Joh reference
    }

    @Test
    fun germanAbbreviationsResolve() {
        val index = BibleCatalog.nameToBookNumber
        assertEquals(43, index["joh"])
        assertEquals(19, index["ps"])
        assertEquals(40, index["mt"])
        assertEquals(42, index["lk"])
        assertEquals(44, index["apg"])
        assertEquals(45, index["r\u00F6m"]) // Röm
        assertEquals(46, index["1kor"])
        assertEquals(66, index["offb"])
    }

    @Test
    fun lookupIsCaseInsensitive() {
        // The index stores lower-cased keys, so mixed-case references
        // resolve through the case-insensitive entry point.
        assertEquals(43, BibleRepository.bookNumberFor("JOHN"))
        assertEquals(43, BibleRepository.bookNumberFor("Joh"))
        assertEquals(42, BibleRepository.bookNumberFor("Lukas"))
        assertEquals(45, BibleRepository.bookNumberFor("R\u00D6M")) // uppercase umlaut RÖM
    }

    @Test
    fun unknownNamesResolveToNull() {
        val index = BibleCatalog.nameToBookNumber
        assertNull(index["notabook"])
        assertNull(index["genesis extra"])
        assertNull(index["\u00DCberraschung"])
    }

    @Test
    fun canonicalCoverageIncludesEveryBook() {
        // Every canonical book number 1..66 must be reachable through at
        // least one alias — a missing list entry would leave a hole.
        val covered = BibleCatalog.nameToBookNumber.values.toSet()
        assertEquals((1..66).toSet(), covered)
    }

    @Test
    fun abbreviationResolvesAgainstActiveGermanBible() {
        // End-to-end: with a German module active, `$Joh` must resolve to
        // the actual book (Johannes, book 43) via the alias index.
        SettingsManager.translation = "luther_1912"
        val book = BibleRepository.getBook("Joh")
        assertEquals(43, book?.book)
        assertEquals(43, BibleRepository.bookNumberFor("Joh"))
    }
}
