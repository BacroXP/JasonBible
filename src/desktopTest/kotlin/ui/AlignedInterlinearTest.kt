package ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull


/**
 * Unit tests for the word-aligned interlinear join [alignGreekToEnglish]:
 * Greek TR tokens (parsed-module shape) are paired one-to-one with the
 * English tokens sharing their Strong's number, absorbing word-order
 * differences between the two languages.
 */
class AlignedInterlinearTest {

    /** An English KJV-style `word{G####}` token. */
    private fun en(word: String, number: String) =
        StrongsToken(word = word, number = number, parsing = null, start = 0, end = word.length)

    /** A Greek parsed-module token (`word G#### [G####…] CODE`). */
    private fun gr(word: String, number: String, tvm: String? = null) =
        StrongsToken(
            word = word,
            number = number,
            parsing = "V-AAI-3S",
            start = 0,
            end = word.length,
            tvm = tvm
        )


    @Test
    fun emptySidesGiveEmptyAlignment() {
        assertEquals(emptyList(), alignGreekToEnglish(emptyList(), emptyList()))
    }

    @Test
    fun englishOnlyColumnsHaveNoGreek() {
        val pairs = alignGreekToEnglish(
            english = listOf(en("God", "G2316"), en("loved", "G25")),
            greek = emptyList()
        )
        assertEquals(2, pairs.size)
        assertNull(pairs[0].greek)
        assertEquals("God", pairs[0].english?.word)
        assertEquals("loved", pairs[1].english?.word)
    }

    @Test
    fun greekTokensJoinEnglishByNumberDespiteOrderDifferences() {
        // Greek word order differs from English; each Greek token still
        // lands on the English word sharing its G-number.
        val pairs = alignGreekToEnglish(
            english = listOf(
                en("For", "G1063"),
                en("God", "G2316"),
                en("so", "G3779"),
                en("loved", "G25")
            ),
            greek = listOf(
                gr("\u03BF\u03C5\u03C4\u03C9\u03C2", "G3779"),        // so
                gr("\u03B3\u03B1\u03C1", "G1063"),                   // For
                gr("\u03B7\u03B3\u03B1\u03C0\u03B7\u03C3\u03B5\u03BD", "G25", tvm = "G5656")
            )
        )
        assertEquals(4, pairs.size)
        assertEquals("\u03BF\u03C5\u03C4\u03C9\u03C2", pairs[2].greek?.word) // so
        assertEquals("\u03B3\u03B1\u03C1", pairs[0].greek?.word)            // For
        assertEquals("\u03B7\u03B3\u03B1\u03C0\u03B7\u03C3\u03B5\u03BD", pairs[3].greek?.word) // loved
    }

    @Test
    fun duplicateNumbersMatchOneToOneInOrder() {
        val pairs = alignGreekToEnglish(
            english = listOf(
                en("In", "G1722"),
                en("the", "G3588"),
                en("beginning", "G746"),
                en("was", "G2258"),
                en("the", "G3588"),
                en("Word", "G3056")
            ),
            greek = listOf(
                gr("\u03B5\u03BD", "G1722"),
                gr("\u03B1\u03C1\u03C7\u03B7", "G746"),
                gr("\u03B7\u03BD", "G2258"),
                gr("\u03BF", "G3588"),
                gr("\u03BB\u03BF\u03B3\u03BF\u03C2", "G3056")
            )
        )
        // The two G3588s pair in order: ο→first "the"; the second "the"
        // (only present in the English) has no Greek gloss.
        assertEquals("\u03BF", pairs[1].greek?.word)
        assertNull(pairs[4].greek)
        assertEquals("\u03BB\u03BF\u03B3\u03BF\u03C2", pairs[5].greek?.word)
    }

    @Test
    fun unmatchedGreekTokensSpliceAfterTheirAnchor() {
        // γαρ has no English G1063 counterpart; it splices right after the
        // last English column matched before it (the "love" column), so
        // it stays near its Greek position instead of trailing the row.
        val pairs = alignGreekToEnglish(
            english = listOf(en("love", "G26")),
            greek = listOf(gr("\u03B1\u03B3\u03B1\u03C0\u03B7\u03BD", "G26"), gr("\u03B3\u03B1\u03C1", "G1063"))
        )
        assertEquals(2, pairs.size)
        assertEquals("\u03B1\u03B3\u03B1\u03C0\u03B7\u03BD", pairs[0].greek?.word)
        assertNull(pairs[1].english)
        assertEquals("\u03B3\u03B1\u03C1", pairs[1].greek?.word)
    }

    @Test
    fun unmatchedGreekTokensStayNearTheirGreekPosition() {
        // X (G999) has no English counterpart and sits between the tokens
        // matching A and B in Greek order — it is spliced between their
        // columns, not appended at the end.
        val pairs = alignGreekToEnglish(
            english = listOf(en("A", "G1"), en("B", "G2")),
            greek = listOf(gr("a", "G1"), gr("X", "G999"), gr("b", "G2"))
        )
        assertEquals(3, pairs.size)
        assertEquals(listOf("A", "B"), pairs.mapNotNull { it.english?.word })
        assertEquals(listOf("a", "X", "b"), pairs.mapNotNull { it.greek?.word })
    }

    @Test
    fun surplusGreekDuplicatesBecomeTrailingColumns() {
        // Two Greek G3588s against one English G3588: the second article
        // has no column to join and splices after the one that did.
        val pairs = alignGreekToEnglish(
            english = listOf(en("the", "G3588")),
            greek = listOf(gr("\u03BF", "G3588"), gr("\u03C4\u03BF\u03BD", "G3588"))
        )
        assertEquals(2, pairs.size)
        assertEquals("\u03BF", pairs[0].greek?.word)
        assertNull(pairs[1].english)
        assertEquals("\u03C4\u03BF\u03BD", pairs[1].greek?.word)
    }

    @Test
    fun unmatchedGreekLeadingTheVerseSplicesAtTheFront() {
        // An untranslated opening particle (before any match) goes to the
        // front of the row.
        val pairs = alignGreekToEnglish(
            english = listOf(en("Word", "G3056")),
            greek = listOf(gr("\u03BA\u03B1\u03B9", "G2532"), gr("\u03BB\u03BF\u03B3\u03BF\u03C2", "G3056"))
        )
        assertEquals(2, pairs.size)
        assertEquals("\u03BA\u03B1\u03B9", pairs[0].greek?.word)
        assertNull(pairs[0].english)
        assertEquals("\u03BB\u03BF\u03B3\u03BF\u03C2", pairs[1].greek?.word)
        assertEquals("Word", pairs[1].english?.word)
    }

    @Test
    fun matchingIsCaseInsensitive() {
        val pairs = alignGreekToEnglish(
            english = listOf(en("God", "g2316")),
            greek = listOf(gr("\u03B8\u03B5\u03BF\u03C2", "G2316"))
        )
        assertEquals("\u03B8\u03B5\u03BF\u03C2", pairs[0].greek?.word)
        assertEquals("God", pairs[0].english?.word)
    }

    @Test
    fun englishOrderIsPreserved() {
        val pairs = alignGreekToEnglish(
            english = listOf(en("A", "G1"), en("B", "G2"), en("C", "G3")),
            greek = listOf(gr("c", "G3"), gr("a", "G1"), gr("b", "G2"))
        )
        assertEquals(listOf("A", "B", "C"), pairs.map { it.english?.word })
        assertEquals(listOf("a", "b", "c"), pairs.map { it.greek?.word })
    }
}
