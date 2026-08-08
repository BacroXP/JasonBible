package data

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue


/**
 * Tests for [WordLexicon] against the REAL bundled modules (the Greek
 * original `trparsed` and the English `kjv_strongs`), mirroring
 * GreekInterlinearTest's pattern: [BibleRepository]'s per-module cache
 * means the whole suite pays each parse once.
 */
class WordLexiconTest {

    companion object {
        init {
            // ensureLoaded builds the occurrence index once per JVM.
            runBlocking { WordLexicon.ensureLoaded() }
        }
    }

    @Test
    fun languageOfClassifiesByLetter() {
        assertEquals(WordLexicon.LanguageKind.HEBREW, WordLexicon.languageOf("H1"))
        assertEquals(WordLexicon.LanguageKind.HEBREW, WordLexicon.languageOf("H7225"))
        assertEquals(WordLexicon.LanguageKind.GREEK, WordLexicon.languageOf("G25"))
        assertEquals(WordLexicon.LanguageKind.GREEK, WordLexicon.languageOf("G3056"))
    }

    @Test
    fun hebrewOccurrencesComeFromTheEnglishTranslation() {
        val occurrences = WordLexicon.occurrences("H7225") // "beginning"
        assertTrue(occurrences.isNotEmpty())
        // Genesis 1:1 — "In the beginning{H7225} …"
        val first = occurrences.first { it.book == 1 }
        assertEquals(1, first.chapter)
        assertEquals(1, first.verse)
        assertEquals("beginning", first.word)
        assertEquals(WordLexicon.LanguageKind.HEBREW, first.language)
        assertEquals("KJV with Strongs (English)", first.sourceLabel)
    }

    @Test
    fun greekOccurrencesIncludeTheOriginalAndEnglish() {
        val occurrences = WordLexicon.occurrences("G25") // ἀγαπάω, "to love"
        assertTrue(occurrences.isNotEmpty())
        // Greek original: John 3:16 ηγαπησεν (the bundled trparsed module
        // ships UNACCENTED Greek — the accents live in the Strong's entry
        // root word, not the verse text).
        val greek = occurrences.first {
            it.language == WordLexicon.LanguageKind.GREEK &&
                it.book == 43 && it.chapter == 3 && it.verse == 16
        }
        assertEquals("\u03B7\u03B3\u03B1\u03C0\u03B7\u03C3\u03B5\u03BD", greek.word)
        assertEquals("V-AAI-3S", greek.parsing)
        assertEquals("TR Parsed (Greek)", greek.sourceLabel)
        // The English translation also carries G25 at the same passage.
        val english = occurrences.first {
            it.language == WordLexicon.LanguageKind.HEBREW || it.sourceLabel.contains("English")
        }
        assertTrue(english.sourceLabel.contains("English"))
    }

    @Test
    fun originalWordsCollectDistinctForms() {
        val forms = WordLexicon.originalWords("G25")
        // Unaccented surface form, as shipped by trparsed.
        assertTrue("\u03B7\u03B3\u03B1\u03C0\u03B7\u03C3\u03B5\u03BD" in forms)
        // The Greek forms sort before the English surface words.
        val firstGreek = forms.indexOfFirst { it.all { ch -> ch.code > 0x370 } }
        assertTrue(firstGreek in 0 until forms.size)
        assertTrue("loved" in forms)
    }

    @Test
    fun rootsAreFoundFromEntryCrossReferences() {
        // H430 elohim: "Plural of H433".
        assertEquals("H433", WordLexicon.rootOf("H430"))
        // H7225: "From the same as H7218".
        assertEquals("H7218", WordLexicon.rootOf("H7225"))
        // G25: "Compare G5368" (no From).
        assertEquals("G5368", WordLexicon.rootOf("G25"))
        // A word with no root reference in its entry.
        assertNotNull(WordLexicon.rootOf("G3056")) // "From G3004"
    }

    @Test
    fun relatedNumbersAreBidirectional() {
        val related = WordLexicon.relatedNumbers("H430")
        assertTrue("H433" in related, "H430 should relate to its root H433")
        // Numbers referencing H430 also appear (e.g. entries that say
        // "From H430").
        assertTrue(related.any { it != "H433" })
        assertEquals(related, related.distinct())
    }

    @Test
    fun searchFindsByNumberTransliterationAndWord() {
        // Exact number sorts first.
        assertEquals("G25", WordLexicon.search("G25").first().number)
        assertEquals("G25", WordLexicon.search("g25").first().number)
        // Transliteration match.
        assertTrue(WordLexicon.search("agapao").any { it.number == "G25" })
        // English surface word from the occurrence index.
        assertTrue(WordLexicon.search("loved").any { it.number == "G25" })
        // Hebrew transliteration (Strong's spells H430 "elohiym" with a
        // yod → 'y'; the search matches diacritic-free substrings).
        assertTrue(WordLexicon.search("elohiym").any { it.number == "H430" })
        // Number prefix.
        assertTrue(WordLexicon.search("H72").any { it.number == "H7225" })
    }

    @Test
    fun searchIgnoresBlankQueriesAndCapsResults() {
        assertTrue(WordLexicon.search("").isEmpty())
        assertTrue(WordLexicon.search("   ").isEmpty())
        assertTrue(WordLexicon.search("a", maxResults = 5).size <= 5)
    }

    @Test
    fun ensureLoadedIsIdempotent() {
        val before = WordLexicon.occurrences("G25").size
        runBlocking { WordLexicon.ensureLoaded() }
        assertEquals(before, WordLexicon.occurrences("G25").size)
    }

    @Test
    fun unknownNumbersReturnEmpty() {
        assertTrue(WordLexicon.occurrences("G99999").isEmpty())
        assertFalse(WordLexicon.relatedNumbers("G99999").isNotEmpty())
    }
}
