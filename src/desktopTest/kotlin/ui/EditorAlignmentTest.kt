package ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.style.TextAlign
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue


/**
 * Tests for paragraph alignment in the note editor: the marker-based
 * toggle helpers ([toggleLineAlignment] / [currentLineAlignment]) and the
 * visual transformation's per-line `ParagraphStyle.textAlign` spans, plus
 * the offset mapping that hides the markers from display.
 */
class EditorAlignmentTest {

    private fun transform(text: String): TransformedText {
        val vt = NoteVisualTransformation(
            palette = NotePalette(
                onSurface = Color.Black,
                onSurfaceVariant = Color.Gray,
                primary = Color.Blue,
                tertiary = Color.Magenta,
                faded = Color.DarkGray,
                referenceBackground = Color.Blue.copy(alpha = 0.1f),
                noteLinkBackground = Color.Magenta.copy(alpha = 0.2f)
            ),
            fontScale = 1f
        )
        return vt.filter(AnnotatedString(text))
    }

    // ------------------------------------------------------------------
    // toggleLineAlignment / currentLineAlignment
    // ------------------------------------------------------------------

    @Test
    fun centerToggleWritesThenRemovesTheMarker() {
        val caretOnLineTwo = TextFieldValue("alpha\nbeta", selection = TextRange(8))
        val centered = toggleLineAlignment(caretOnLineTwo, LineAlignment.CENTER)
        assertEquals("alpha\n\u200Bbeta", centered.text)
        assertEquals(LineAlignment.CENTER, currentLineAlignment(centered))
        // A re-press of the same alignment returns the line to left.
        assertEquals("alpha\nbeta", toggleLineAlignment(centered, LineAlignment.CENTER).text)
    }

    @Test
    fun rightToggleWritesThenRemovesTheMarker() {
        val caretOnLineTwo = TextFieldValue("alpha\nbeta", selection = TextRange(8))
        val right = toggleLineAlignment(caretOnLineTwo, LineAlignment.RIGHT)
        assertEquals("alpha\n\u2060beta", right.text)
        assertEquals(LineAlignment.RIGHT, currentLineAlignment(right))
        assertEquals("alpha\nbeta", toggleLineAlignment(right, LineAlignment.RIGHT).text)
    }

    @Test
    fun alignLeftStripsAnyAlignmentMarker() {
        // Caret on the CENTERED first line (\u200Bcenter\nplain).
        val centered = TextFieldValue("\u200Bcenter\nplain", selection = TextRange(3))
        assertEquals(LineAlignment.CENTER, currentLineAlignment(centered))
        val left = toggleLineAlignment(centered, LineAlignment.LEFT)
        assertEquals("center\nplain", left.text)
        assertEquals(LineAlignment.LEFT, currentLineAlignment(left))
    }

    @Test
    fun switchingAlignmentReplacesTheMarker() {
        val centered = TextFieldValue("\u200Bhello", selection = TextRange(8))
        val right = toggleLineAlignment(centered, LineAlignment.RIGHT)
        assertEquals("\u2060hello", right.text)
    }

    @Test
    fun multiLineSelectionAlignsEveryLine() {
        val selected = TextFieldValue(
            "one\ntwo\nthree",
            selection = TextRange(0, 13)
        )
        val centered = toggleLineAlignment(selected, LineAlignment.CENTER)
        assertEquals("\u200Bone\n\u200Btwo\n\u200Bthree", centered.text)
    }

    @Test
    fun aligningAHeadingKeepsTheHeadingMarkerFirst() {
        // The alignment marker must sit BEFORE the block prefix so the
        // line stays a heading.
        val heading = TextFieldValue("# Title", selection = TextRange(8))
        val centered = toggleLineAlignment(heading, LineAlignment.CENTER)
        assertEquals("\u200B# Title", centered.text)
    }

    @Test
    fun directionTogglePreservesAlignmentMarker() {
        val centeredRtl = TextFieldValue("\u200B\u200Fשלום", selection = TextRange(8))
        val toggled = toggleLineOrientation(centeredRtl)
        // Alignment marker stays first; the RLM is stripped → LTR.
        assertEquals("\u200Bשלום", toggled.text)
    }

    // ------------------------------------------------------------------
    // Visual transformation — per-line textAlign
    // ------------------------------------------------------------------

    @Test
    fun centeredLineGetsCenterParagraphStyleAndHiddenMarker() {
        val result = transform("\u200Bcenter me")
        assertFalse(result.text.text.contains("\u200B"), "marker must be hidden")
        val centerSpans = result.text.paragraphStyles.filter {
            it.item.textAlign == TextAlign.Center
        }
        assertTrue(centerSpans.isNotEmpty(), "expected a Center ParagraphStyle span")
    }

    @Test
    fun rightAlignedLineGetsRightParagraphStyle() {
        val result = transform("\u2060right me")
        assertFalse(result.text.text.contains("\u2060"), "marker must be hidden")
        assertTrue(
            result.text.paragraphStyles.any { it.item.textAlign == TextAlign.Right },
            "expected a Right ParagraphStyle span"
        )
    }

    @Test
    fun plainLineDefaultsToStartAlignment() {
        val result = transform("plain")
        assertTrue(
            result.text.paragraphStyles.any { it.item.textAlign == TextAlign.Start },
            "expected a Start ParagraphStyle span"
        )
    }

    @Test
    fun centeredHeadingStillClassifiesAsHeading() {
        val result = transform("\u200B# Title")
        // `\u200B` + `# ` (3 hidden chars) → display "Title".
        assertEquals("Title", result.text.text)
        assertTrue(
            result.text.paragraphStyles.any { it.item.textAlign == TextAlign.Center },
            "centered heading must stay Center-aligned"
        )
    }

    // ------------------------------------------------------------------
    // Offset mapping with hidden markers
    // ------------------------------------------------------------------

    @Test
    fun offsetMappingHidesTheAlignmentMarker() {
        val result = transform("\u200Babc")
        val mapping = result.offsetMapping
        assertEquals(0, mapping.originalToTransformed(0)) // inside marker
        assertEquals(0, mapping.originalToTransformed(1)) // 'a'
        assertEquals(3, mapping.originalToTransformed(4)) // end of text
        assertEquals(4, mapping.transformedToOriginal(3))
    }
}
