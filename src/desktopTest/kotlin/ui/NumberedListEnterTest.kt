package ui

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Regression test for the crash reported from the running app:
 *
 *   StringIndexOutOfBoundsException: Range [1929, 1928) out of bounds for length 1941
 *     at ui.EditorTextOpsKt.lastPlainNumberedNumber(EditorTextOps.kt:678)
 *     at ui.EditorTextOpsKt.detectListContinuation(EditorTextOps.kt:651)
 *     at ui.EditorTextOpsKt.continueListAtEnter(EditorTextOps.kt:603)
 *
 * Enter inside a numbered list walks backwards to the previous numbered
 * line to keep 1, 2, 3 … going. The walk assumed the newline before the
 * line was at the line start, producing `substring(start, start - 1)` on
 * every numbered list that wasn't the first line of the note.
 */
class NumberedListEnterTest {

    /** Simulates the editor handing over the value after one Enter press. */
    private fun enterAt(text: String, caret: Int): TextFieldValue {
        val prev = TextFieldValue(text, selection = TextRange(caret))
        val next = TextFieldValue(
            text.substring(0, caret) + "\n" + text.substring(caret),
            selection = TextRange(caret + 1)
        )
        return continueListAtEnter(prev, next)
    }

    @Test
    fun enterInALaterNumberedItemContinuesTheSequence() {
        val text = "# Notiz\n1. Eins\n2. Zwei"
        val result = enterAt(text, text.length)
        assertEquals("$text\n3. ", result.text)
        assertEquals(result.text.length, result.selection.start)
    }

    @Test
    fun enterInTheSecondNumberedItemWorksOnANoteWithoutHeading() {
        val text = "1. Eins\n2. Zwei"
        val result = enterAt(text, text.length)
        assertEquals("$text\n3. ", result.text)
    }

    @Test
    fun enterAfterABlankLineStartsFromTheCurrentNumber() {
        val text = "1. Eins\n\n2. Zwei"
        val result = enterAt(text, text.length)
        assertEquals("$text\n3. ", result.text)
    }

    @Test
    fun enterOnAnEmptyLineIsUnaffected() {
        val text = "1. Eins\n"
        val result = enterAt(text, text.length)
        assertEquals("1. Eins\n\n", result.text)
    }
}
