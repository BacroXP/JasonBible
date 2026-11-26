package data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue


class HebrewLexiconTest {

    private fun definition(
        number: String,
        rootWord: String,
        transliteration: String,
        entry: String
    ) = StrongsRepository.StrongsDefinition(
        number = number,
        rootWord = rootWord,
        transliteration = transliteration,
        pronunciation = "",
        tvm = null,
        entry = entry
    )

    @Test
    fun splitsWordsWithoutPunctuation() {
        val words = HebrewLexicon.words(
            "בְּרֵאשִׁ֖ית בָּרָ֣א אֱלֹהִ֑ים, אֵ֥ת הַשָּׁמַ֖יִם וְאֵ֥ת הָאָֽרֶץ׃"
        )
        assertEquals(
            listOf("בְּרֵאשִׁ֖ית", "בָּרָ֣א", "אֱלֹהִ֑ים", "אֵ֥ת", "הַשָּׁמַ֖יִם", "וְאֵ֥ת", "הָאָֽרֶץ"),
            words
        )
    }

    @Test
    fun normalizeStripsPointsAndPunctuation() {
        assertEquals("בראשית", HebrewLexicon.normalize("בְּרֵאשִׁ֖ית"))
        assertEquals("שמים", HebrewLexicon.normalize("שָּׁמַ֖יִם"))
        assertEquals("", HebrewLexicon.normalize("\u05BE\u05C3"))
    }

    @Test
    fun exactRootMatchYieldsTheGlossList() {
        val definitions = listOf(
            definition(
                "H430", "אֱלֹהִים", "'ĕlōhı̄ym",
                "Plural of H433; gods in the ordinary sense: - angels, " +
                    "X exceeding, God (gods) (-dess, -ly), X mighty."
            ),
            definition(
                "H7225", "רֵאשִׁית", "rē'shı̄yth",
                "From the same as H7218; the first: - beginning, " +
                    "chief (-est), first (-fruits), principal thing."
            ),
            definition("G2316", "θεός", "theos", "a deity: - God.")
        )

        assertEquals(
            listOf("angels", "exceeding", "God", "mighty"),
            HebrewLexicon.translationOptions("אֱלֹהִים", definitions)
        )
        // A prefixed surface form (בְּ + root) still finds the lemma.
        assertEquals(
            listOf("beginning", "chief", "first", "principal thing"),
            HebrewLexicon.translationOptions("בְּרֵאשִׁית", definitions)
        )
    }

    @Test
    fun prefixedFormDoesNotFallThroughToALooseMatch() {
        // "the heavens" must resolve to H8064, never to "water" (H4325) by
        // peeling the article plus the שׁ and matching mere consonants.
        val definitions = listOf(
            definition("H8064", "שָׁמַיִם שָׁמֶה", "shâmayim", "The sky: - air, heaven (-s)."),
            definition("H4325", "מַיִם", "mayim", "water: - water.")
        )
        val options = HebrewLexicon.translationOptions("הַשָּׁמַיִם", definitions)
        assertEquals(listOf("air", "heaven"), options)
        assertFalse(options.contains("water"))
    }

    @Test
    fun wordsWithoutAnEntryHaveNoOptions() {
        val definitions = listOf(
            definition("H1254", "בָּרָא", "bârâ'", "to create: - create.")
        )
        // Nothing to look up: the verse panel dims these words.
        assertTrue(HebrewLexicon.translationOptions("הוליד", definitions).isEmpty())
        // Greek lemmas never match a Hebrew skeleton.
        assertTrue(HebrewLexicon.translationOptions("ἀγάπη", definitions).isEmpty())
        // Too short to be a meaningful lookup.
        assertTrue(HebrewLexicon.translationOptions("בְּ", definitions).isEmpty())
    }

    @Test
    fun entriesWithoutAGlossListOfferNoTranslation() {
        val definitions = listOf(
            definition("H853", "אֵת", "'êth", "(As such unrepresented in English.)")
        )
        assertTrue(HebrewLexicon.translationOptions("את", definitions).isEmpty())
    }

    @Test
    fun verseLookupCoversEveryWord() {
        val definitions = listOf(
            definition("H430", "אֱלֹהִים", "'ĕlōhı̄ym", "gods: - angels, God."),
            definition("H1254", "בָּרָא", "bârâ'", "to create: - create, choose.")
        )
        val words = HebrewLexicon.words("בראשית ברא אלהים")
        val options = HebrewLexicon.translationOptionsForVerse(words, definitions)

        assertEquals(words, options.keys.toList())
        assertEquals(setOf("create", "choose"), options.getValue("ברא").toSet())
        assertTrue(options.getValue("בראשית").isEmpty())
    }
}
