package data

import kotlin.test.Test
import kotlin.test.assertEquals


/**
 * Unit tests for [MorphologyExplainer], which expands the compact
 * morphological codes of the parsed Greek module (e.g. `V-AAI-3S`,
 * `N-NSF`, `V-PAP-NSM`) into readable labels.
 */
class MorphologyExplainerTest {

    @Test
    fun finiteVerbCodeExpands() {
        assertEquals(
            listOf("Verb", "Aorist", "Active", "Indicative", "3rd person", "Singular"),
            MorphologyExplainer.explain("V-AAI-3S")
        )
    }

    @Test
    fun secondAoristStemIsNoted() {
        assertEquals(
            listOf("Verb", "Aorist", "Active", "Indicative", "2. stem"),
            MorphologyExplainer.explain("V-2AAI")
        )
    }

    @Test
    fun infinitiveCodeHasNoPerson() {
        assertEquals(
            listOf("Verb", "Present", "Active", "Infinitive"),
            MorphologyExplainer.explain("V-PAN")
        )
    }

    @Test
    fun participleCarriesCaseSuffix() {
        assertEquals(
            listOf("Verb", "Present", "Active", "Participle", "Nominative", "Singular", "Masculine"),
            MorphologyExplainer.explain("V-PAP-NSM")
        )
        // The leading stem digit (2nd aorist) is surfaced for participles
        // too — it genuinely distinguishes the aorist stems.
        assertEquals(
            listOf(
                "Verb", "Aorist", "Active", "Participle", "2. stem",
                "Nominative", "Plural", "Masculine"
            ),
            MorphologyExplainer.explain("V-2AAP-NPM")
        )
    }

    @Test
    fun nounCodeExpands() {
        assertEquals(
            listOf("Noun", "Nominative", "Singular", "Feminine"),
            MorphologyExplainer.explain("N-NSF")
        )
        assertEquals(
            listOf("Noun", "Accusative", "Plural", "Neuter"),
            MorphologyExplainer.explain("N-APN")
        )
        assertEquals(listOf("Noun", "Proper noun"), MorphologyExplainer.explain("N-PRI"))
    }

    @Test
    fun adjectiveAndPronounCodes() {
        assertEquals(
            listOf("Adjective", "Genitive", "Singular", "Masculine"),
            MorphologyExplainer.explain("A-GSM")
        )
        assertEquals(
            listOf("Personal pronoun", "1st person", "Genitive", "Singular"),
            MorphologyExplainer.explain("P-1GS")
        )
        assertEquals(
            listOf("Article", "Dative", "Singular", "Masculine"),
            MorphologyExplainer.explain("T-DSM")
        )
    }

    @Test
    fun simpleTagsExpand() {
        assertEquals(listOf("Adverb"), MorphologyExplainer.explain("ADV"))
        assertEquals(listOf("Conjunction"), MorphologyExplainer.explain("CONJ"))
        assertEquals(listOf("Preposition"), MorphologyExplainer.explain("PREP"))
    }

    @Test
    fun unknownCodesStayRaw() {
        assertEquals(listOf("G25"), MorphologyExplainer.explain("G25"))
        assertEquals(listOf("(no parsing data)"), MorphologyExplainer.explain(""))
        // Surrounding whitespace is trimmed before expanding.
        assertEquals(
            listOf("Verb", "Aorist", "Active", "Indicative", "3rd person", "Singular"),
            MorphologyExplainer.explain("  V-AAI-3S  ")
        )
    }
}
