package ui

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlin.test.Test
import kotlin.test.assertEquals


class EditorSelectionBoundsTest {

    @Test
    fun clampsSelectionLeftPastTheShorterText() {
        // A Replace-All-sized shrink with the old [1900, 2000) range:
        // whatever produced those offsets, the guard pulls them inside.
        val value = TextFieldValue("x".repeat(1955), selection = TextRange(1900, 2000))
        val clamped = value.withSelectionInBounds()
        assertEquals(TextRange(1900, 1955), clamped.selection)
        assertEquals(value.text, clamped.text)
    }

    @Test
    fun clampsCollapsedCaretPastTheEnd() {
        val value = TextFieldValue("abc", selection = TextRange(10))
        assertEquals(TextRange(3), value.withSelectionInBounds().selection)
    }

    @Test
    fun keepsValidSelectionUntouched() {
        val value = TextFieldValue("hello world", selection = TextRange(0, 5))
        assertEquals(value, value.withSelectionInBounds())
    }
}
