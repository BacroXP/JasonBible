package ui

import kotlin.test.Test
import kotlin.test.assertEquals


/**
 * Unit tests for [stripWordStudyMarkup] and its two style-specific
 * strippers — the KJV `word{G####}{(G####)}` brace markup and the parsed
 * Greek module's space-separated `word G#### CODE` tokens. These back the
 * "copy verse" / PDF / home-verse paths, so the stripped text must read as
 * clean prose while punctuation outside the markup survives.
 */
class WordStudyMarkupTest {

    @Test
    fun braceMarkupKeepsPunctuationAfterToken() {
        // Trailing punctuation sits OUTSIDE the braces and must survive.
        assertEquals("loved,", stripWordStudyMarkup("loved{G25}{(G5656)},"))
        assertEquals("loved.", stripWordStudyMarkup("loved{G25}{(G5656)}."))
    }

    @Test
    fun braceMarkupStripsAllTokensInRun() {
        assertEquals(
            "loved the world.",
            stripWordStudyMarkup("loved{G25}{(G5656)} the{G3588} world{G2889}.")
        )
    }

    @Test
    fun hebrewNumbersAreStripped() {
        assertEquals("bereshith elohim", stripWordStudyMarkup("bereshith{H7225} elohim{H430}"))
    }

    @Test
    fun plainTextPassesThrough() {
        assertEquals("plain text", stripWordStudyMarkup("plain text"))
        assertEquals("", stripWordStudyMarkup(""))
    }

    @Test
    fun emptyParsingBracesAreLeftUntouched() {
        // `{}` has no `(…)` group, so the optional parsing part does not
        // match — the braces stay (they are not a legal word-study token).
        assertEquals("dog{} tail", stripWordStudyMarkup("dog{G2962}{} tail"))
    }

    @Test
    fun malformedBraceMarkupIsLeftUntouched() {
        // Missing closing brace → no token, no stripping.
        assertEquals("loved{G25", stripWordStudyMarkup("loved{G25"))
    }

    @Test
    fun braceBranchWinsOverParsedTokens() {
        // A verse carrying BOTH markup styles (shouldn't happen in the
        // bundled modules, but defensive): the brace style is detected
        // first and stripped, leaving the parsed Greek text verbatim.
        assertEquals(
            "loved \u03B7\u03B3\u03B1\u03C0\u03B7\u03C3\u03B5\u03BD G25 G5656 V-AAI-3S",
            stripWordStudyMarkup("loved{G25}{(G5656)} \u03B7\u03B3\u03B1\u03C0\u03B7\u03C3\u03B5\u03BD G25 G5656 V-AAI-3S")
        )
    }

    @Test
    fun parsedMarkupJoinsWordsWithSingleSpaces() {
        // One token with a lemma number, TVM code and morphology code.
        assertEquals(
            "\u03B7\u03B3\u03B1\u03C0\u03B7\u03C3\u03B5\u03BD",
            stripWordStudyMarkup("\u03B7\u03B3\u03B1\u03C0\u03B7\u03C3\u03B5\u03BD G25 G5656 V-AAI-3S")
        )
        // Real trparsed format: `word G#### CODE` (morphology code after
        // the lemma). Multiple tokens join with single spaces.
        assertEquals(
            "\u03BF\u1F57\u03C4\u03C9\u03C2 \u03B3\u03AC\u03C1 \u1F21\u03B3\u03AC\u03C0\u03B7\u03C3\u03B5\u03BD",
            stripWordStudyMarkup(
                "\u03BF\u1F57\u03C4\u03C9\u03C2 G3779 ADV " +
                    "\u03B3\u03AC\u03C1 G1063 CONJ " +
                    "\u1F21\u03B3\u03AC\u03C0\u03B7\u03C3\u03B5\u03BD G25 G5656 V-AAI-3S"
            )
        )
    }

    @Test
    fun parsedMarkupDropsMalformedFragments() {
        // A bare Greek word without the G-number + code shape is not a
        // token; it is dropped rather than preserved (the parsed module is
        // the token source, variant markers like `VAR2:` stay out).
        assertEquals(
            "\u03BB\u03CC\u03B3\u03BF\u03C2",
            stripWordStudyMarkup("VAR2: \u03BB\u03CC\u03B3\u03BF\u03C2 G3056 N-ASN")
        )
    }

    @Test
    fun parsedMarkupLeavesGreekWithoutMarkupUntouched() {
        // Plain Greek prose matches neither style's detector → unchanged.
        val plain = "\u1F10\u03BD \u1F00\u03C1\u03C7\u1FC7"
        assertEquals(plain, stripWordStudyMarkup(plain))
    }
}
