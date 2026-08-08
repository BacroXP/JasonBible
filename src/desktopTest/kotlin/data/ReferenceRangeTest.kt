package data

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import ui.NotePalette
import ui.NoteVisualTransformation
import ui.findFirstReferenceOffset
import ui.referenceLineRegex


/**
 * Unit tests for custom verse references: `$Book&C&V` (parts separated
 * by `&`, `$` accepted for older notes) with an optional end-based range
 * suffix — `+`, `+N`, `-V2`, `-V2+N`, and cross-chapter forms like
 * `-&C2`, `-&C2&V2`, `-&C2+N` (e.g. `$Lukas&7&1-&8&1` = Luk 7:1 to 8:1).
 * Covers the inline token parser ([findReferenceTokens]), the shared
 * [parseRange] resolver, the whole-line [referenceLineRegex] capture and
 * the rendered chip text / offset mapping.
 */
class ReferenceRangeTest {

    private fun firstToken(text: String): ReferenceToken? =
        findReferenceTokens(text).firstOrNull()

    // ------------------------------------------------------------------
    // Inline parser — & separators and range suffixes
    // ------------------------------------------------------------------

    @Test
    fun plainReferenceHasNoRange() {
        val t = assertNotNull(firstToken("\$Lukas&7&1"))
        assertEquals("Lukas", t.book)
        assertEquals(7, t.chapter)
        assertEquals(1, t.verse)
        assertNull(t.endChapter)
        assertNull(t.endVerse)
    }

    @Test
    fun plusExtendsByOneVerse() {
        val t = assertNotNull(firstToken("Read \$Lukas&7&1+ today"))
        assertEquals(1, t.verse)
        assertEquals(7, t.endChapter)
        assertEquals(2, t.endVerse)
        // The '+' is part of the token.
        assertEquals("\$Lukas&7&1+", t.sourceText("Read \$Lukas&7&1+ today"))
    }

    @Test
    fun plusWithCountAddsFollowingVerses() {
        val t = assertNotNull(firstToken("\$Lukas&7&1+5"))
        assertEquals(1, t.verse)
        assertEquals(7, t.endChapter)
        assertEquals(6, t.endVerse)
        assertEquals("\$Lukas&7&1+5", t.sourceText("\$Lukas&7&1+5"))
    }

    @Test
    fun minusToVerseIsEndBased() {
        // `-3` means "to verse 3" (end-based), not "3 verses total".
        val t = assertNotNull(firstToken("\$Lukas&7&1-3"))
        assertEquals(7, t.endChapter)
        assertEquals(3, t.endVerse)
    }

    @Test
    fun minusToVersePlusCount() {
        // `-8+10` = to verse 8, plus 10 more = through verse 18.
        val t = assertNotNull(firstToken("\$Lukas&7&1-8+10"))
        assertEquals(7, t.endChapter)
        assertEquals(18, t.endVerse)
    }

    @Test
    fun crossChapterRange() {
        // `-&8&1` = chapter 7 verse 1 through chapter 8 verse 1.
        val t = assertNotNull(firstToken("\$Lukas&7&1-&8&1"))
        assertEquals(8, t.endChapter)
        assertEquals(1, t.endVerse)
        assertEquals("\$Lukas&7&1-&8&1", t.sourceText("\$Lukas&7&1-&8&1"))
    }

    @Test
    fun crossChapterDefaultsToVerseOne() {
        // `-&8` alone means "to chapter 8, verse 1".
        val t = assertNotNull(firstToken("\$Lukas&7&1-&8"))
        assertEquals(8, t.endChapter)
        assertEquals(1, t.endVerse)
    }

    @Test
    fun crossChapterPlusCount() {
        // `-&8+10` = to chapter 8 verse 1, plus 10 = through 8:11.
        val t = assertNotNull(firstToken("\$Lukas&7&1-&8+10"))
        assertEquals(8, t.endChapter)
        assertEquals(11, t.endVerse)
    }

    @Test
    fun crossChapterVersePlusCount() {
        val t = assertNotNull(firstToken("\$Lukas&7&1-&8&1+10"))
        assertEquals(8, t.endChapter)
        assertEquals(11, t.endVerse)
    }

    @Test
    fun oldDollarSeparatorsStillParse() {
        val plain = assertNotNull(firstToken("\$John\$3\$16"))
        assertEquals("John", plain.book)
        assertEquals(3, plain.chapter)
        assertEquals(16, plain.verse)

        val ranged = assertNotNull(firstToken("\$John\$3\$16-22"))
        assertEquals(3, ranged.endChapter)
        assertEquals(22, ranged.endVerse)
    }

    @Test
    fun dollarSeparatorCrossChapterStillParses() {
        val t = assertNotNull(firstToken("\$John\$3\$16-\$4\$1"))
        assertEquals(4, t.endChapter)
        assertEquals(1, t.endVerse)
    }

    @Test
    fun backwardRangeIsNotConsumed() {
        // `-7` from verse 16 goes backwards — it must not become a range.
        // The token ends at the verse digits and the suffix stays plain
        // text after it.
        val t = assertNotNull(firstToken("\$John&3&16-7"))
        assertEquals(16, t.verse)
        assertNull(t.endChapter)
        assertNull(t.endVerse)
        assertEquals("\$John&3&16", t.sourceText("\$John&3&16-7"))
    }

    @Test
    fun bareDashIsNotARange() {
        val t = assertNotNull(firstToken("\$John&3&16- "))
        assertEquals(16, t.verse)
        assertNull(t.endVerse)
        assertEquals("\$John&3&16", t.sourceText("\$John&3&16- "))
    }

    @Test
    fun bareSeparatorAfterDashIsNotARange() {
        // `-&` with no chapter digits stays a terminator.
        val t = assertNotNull(firstToken("\$John&3&16-& note"))
        assertEquals(16, t.verse)
        assertNull(t.endVerse)
        assertEquals("\$John&3&16", t.sourceText("\$John&3&16-& note"))
    }

    @Test
    fun commaStillTerminates() {
        // The old count-based `-7,5` is gone; the comma ends the token.
        val t = assertNotNull(firstToken("\$John&3&16-22, please"))
        assertEquals(22, t.endVerse)
        assertEquals("\$John&3&16-22", t.sourceText("\$John&3&16-22, please"))
    }

    // ------------------------------------------------------------------
    // parseRange resolver
    // ------------------------------------------------------------------

    @Test
    fun parseRangeHelper() {
        assertEquals(ReferenceRange(7, 2), parseRange(7, 1, "+"))
        assertEquals(ReferenceRange(7, 6), parseRange(7, 1, "+5"))
        assertEquals(ReferenceRange(7, 3), parseRange(7, 1, "-3"))
        assertEquals(ReferenceRange(7, 18), parseRange(7, 1, "-8+10"))
        assertEquals(ReferenceRange(8, 1), parseRange(7, 1, "-&8"))
        assertEquals(ReferenceRange(8, 1), parseRange(7, 1, "-&8&1"))
        assertEquals(ReferenceRange(8, 11), parseRange(7, 1, "-&8+10"))
        assertEquals(ReferenceRange(8, 11), parseRange(7, 1, "-&8&1+10"))
        // `$` works as the cross-chapter separator too.
        assertEquals(ReferenceRange(8, 1), parseRange(7, 1, "-\$8\$1"))
    }

    @Test
    fun parseRangeRejectsDegenerateAndMalformed() {
        // Backward same-chapter range.
        assertNull(parseRange(3, 16, "-7"))
        // Same verse is not a range.
        assertNull(parseRange(7, 1, "-1"))
        // Cross-chapter end equal to the start.
        assertNull(parseRange(7, 1, "-&7"))
        // `+0` extends nothing.
        assertNull(parseRange(7, 1, "+0"))
        assertNull(parseRange(7, 1, ""))
        assertNull(parseRange(7, 1, "-"))
        assertNull(parseRange(7, 1, "-&"))
        assertNull(parseRange(7, 1, "-&8&"))
        assertNull(parseRange(7, 1, "garbage"))
        // No start verse → nothing to extend.
        assertNull(parseRange(7, null, "-3"))
        assertNull(parseRange(null, 1, "-3"))
    }

    // ------------------------------------------------------------------
    // Whole-line regex — group layout (4 = range, 5 = label)
    // ------------------------------------------------------------------

    @Test
    fun wholeLineRegexCapturesRangeAndLabel() {
        val m = assertNotNull(referenceLineRegex.matchEntire("\$Lukas&7&1-&8&1 label"))
        assertEquals("Lukas", m.groupValues[1])
        assertEquals("7", m.groupValues[2])
        assertEquals("1", m.groupValues[3])
        assertEquals("-&8&1", m.groupValues[4])
        assertEquals("label", m.groupValues[5].trim())
    }

    @Test
    fun wholeLineRegexCapturesPlusCount() {
        val m = assertNotNull(referenceLineRegex.matchEntire("\$Lukas&7&1+5 my note"))
        assertEquals("+5", m.groupValues[4])
        assertEquals("my note", m.groupValues[5].trim())
        assertEquals(ReferenceRange(7, 6), parseRange(7, 1, m.groupValues[4]))
    }

    @Test
    fun wholeLineRegexAcceptsOldDollarSeparators() {
        val m = assertNotNull(referenceLineRegex.matchEntire("\$John\$3\$16"))
        assertEquals("John", m.groupValues[1])
        assertEquals("3", m.groupValues[2])
        assertEquals("16", m.groupValues[3])
        assertEquals("", m.groupValues[4])
    }

    @Test
    fun wholeLineRegexWithoutRangeKeepsLabelInGroupFive() {
        val m = assertNotNull(referenceLineRegex.matchEntire("\$Lukas&7&1 my note"))
        assertEquals("", m.groupValues[4])
        assertEquals("my note", m.groupValues[5].trim())
    }

    // ------------------------------------------------------------------
    // Rendering: chip text, cross-chapter labels and offset round-trips
    // ------------------------------------------------------------------

    private fun transformation(): NoteVisualTransformation = NoteVisualTransformation(
        NotePalette(
            onSurface = Color.Black,
            onSurfaceVariant = Color.Gray,
            primary = Color.Blue,
            tertiary = Color.Cyan,
            faded = Color.DarkGray,
            referenceBackground = Color.LightGray,
            noteLinkBackground = Color.Cyan.copy(alpha = 0.2f)
        )
    )

    @Test
    fun transformationRendersSameChapterRange() {
        val vt = transformation()
        val out = vt.filter(AnnotatedString("\$Lukas&7&1-3"))
        assertEquals("Lukas 7:1-3", out.text.text)
    }

    @Test
    fun transformationRendersCrossChapterRange() {
        val vt = transformation()
        val out = vt.filter(AnnotatedString("\$Lukas&7&1-&8&1"))
        assertEquals("Lukas 7:1-8:1", out.text.text)
    }

    @Test
    fun transformationRendersChapterEndWithoutVerse() {
        // `-&8` (no end verse) still shows the resolved "8:1" end.
        val vt = transformation()
        val out = vt.filter(AnnotatedString("\$Lukas&7&1-&8"))
        assertEquals("Lukas 7:1-8:1", out.text.text)
    }

    @Test
    fun transformationKeepsLabelAfterRange() {
        val vt = transformation()
        val withLabel = vt.filter(AnnotatedString("\$Lukas&7&1+ devotion"))
        assertEquals("Lukas 7:1-2 devotion", withLabel.text.text)
    }

    @Test
    fun transformationRendersInlineRange() {
        val vt = transformation()
        val inline = vt.filter(AnnotatedString("Read \$Lukas&7&1-&8&1 today"))
        assertEquals("Read Lukas 7:1-8:1 today", inline.text.text)
    }

    @Test
    fun transformationShowsBackwardRangeVerbatim() {
        // A captured suffix that does not resolve is preserved as plain
        // text instead of being dropped from the display.
        val vt = transformation()
        val out = vt.filter(AnnotatedString("\$John&3&16-7"))
        assertEquals("John 3:16-7", out.text.text)
    }

    @Test
    fun transformationMappingRoundTripsAroundSuffix() {
        val vt = transformation()
        // `-&8` (3 raw chars) resolves to `-8:1` (4 display chars) — an
        // expansion, so the offset mapping is one-to-one through the
        // suffix and every cursor / tap position round-trips exactly.
        val source = "\$Lukas&7&1-&8"
        val out = vt.filter(AnnotatedString(source))
        assertEquals("Lukas 7:1-8:1", out.text.text)
        // The display ends at the resolved suffix; endpoints map cleanly.
        assertEquals(out.text.length, out.offsetMapping.originalToTransformed(source.length))
        assertEquals(source.length, out.offsetMapping.transformedToOriginal(out.text.length))
        // Cursor / tap offsets round-trip (source -> display -> source)
        // across the book name, separators, digits and the expanded
        // suffix. (Source offset 0 — the hidden '$' marker — deliberately
        // maps onto the book name, so it is not one-to-one and is
        // skipped.)
        for (o in 1 until source.length) {
            val display = out.offsetMapping.originalToTransformed(o)
            assertEquals(o, out.offsetMapping.transformedToOriginal(display), "round-trip at source $o")
        }
    }

    // ------------------------------------------------------------------
    // Scroll lookup — a cross-chapter range covers its END chapter too
    // ------------------------------------------------------------------

    @Test
    fun scrollLookupMatchesEndChapterOfCrossChapterRange() {
        // Shift-picking 8:1 from a `$Lukas&7&1-&8&1` chip must resolve a
        // scroll target even though the line starts in chapter 7.
        val line = "\$Lukas&7&1-&8&1"
        assertNotNull(findFirstReferenceOffset(line, "Lukas", 7, 1))
        assertNotNull(findFirstReferenceOffset(line, "Lukas", 8, 1))
        assertNotNull(findFirstReferenceOffset(line, "Lukas", 8, null))
        // Verses outside the span still do not match.
        assertNull(findFirstReferenceOffset(line, "Lukas", 6, 1))
        assertNull(findFirstReferenceOffset(line, "Lukas", 9, 1))
        // Same behaviour for an inline token in running text.
        val inline = "Read \$Lukas&7&1-&8&1 today"
        assertNotNull(findFirstReferenceOffset(inline, "Lukas", 8, 1))
        assertNull(findFirstReferenceOffset(inline, "Lukas", 6, 1))
    }

    @Test
    fun transformationCompressedSuffixKeepsTapsOnChip() {
        // `-&8&1` (5 raw chars) resolves to `-8:1` (4 display chars) — a
        // compression. Five source chars cannot map one-to-one onto four
        // display slots, so interior positions near the suffix are
        // approximate; what MUST hold is that the endpoints round-trip
        // and every display position inside the chip still resolves back
        // into the token's source range (the tap/hover hit-test that
        // matters for clicking the chip).
        val vt = transformation()
        val source = "\$Lukas&7&1-&8&1"
        val out = vt.filter(AnnotatedString(source))
        assertEquals("Lukas 7:1-8:1", out.text.text)
        assertEquals(
            out.text.length,
            out.offsetMapping.originalToTransformed(source.length)
        )
        assertEquals(
            source.length,
            out.offsetMapping.transformedToOriginal(out.text.length)
        )
        for (t in 0 until out.text.length) {
            val back = out.offsetMapping.transformedToOriginal(t)
            assertTrue(
                back in 0..source.length,
                "display $t maps out of bounds to $back"
            )
        }
    }

    private fun ReferenceToken.sourceText(source: String): String =
        source.substring(sourceStart, sourceEnd)
}
