package ui

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * End-to-end reproduction of the Replace All shrink case behind the 2.2.0
 * crash:
 *
 *   IndexOutOfBoundsException: Range [1900, 2000) out of bounds for length 1955
 *
 * A ~1955-char note was searched in the Find/Replace bar and every match
 * was replaced with a SHORTER string, while the editor still carried a
 * selection near the end of the longer note. The test drives the real
 * pipeline — [findMatches] → [replaceAllMatches] — on the real note length
 * and the real selection range, and pins the three things the crash
 * depended on:
 *
 *  1. the splice really shrinks the text (1955 → 1955 − 25 × matches),
 *  2. the value handed to the field is always inside the new text,
 *  3. match ranges that outlive the text they were found in (a stale Find
 *     bar result applied to a later, shorter note) are clamped instead of
 *     slicing past the end.
 */
class ReplaceAllShrinkTest {

    private val query = "Der barmherzige Samariter"
    private val entry = "Der barmherzige Samariter half dem Mann aus Lukas zehn am Weg. "
    private val prefix = "Notizen zum Gleichnis.\n"

    /** The 1955-char note that crashed, with complete occurrences of [query]. */
    private fun noteText(): String {
        val body = StringBuilder(prefix)
        while (body.length + entry.length <= 1955) body.append(entry)
        while (body.length < 1955) body.append('.')
        return body.toString()
    }

    @Test
    fun replaceAllShrinksTheNoteAndKeepsTheSelectionInside() {
        val text = noteText()
        assertEquals(1955, text.length)

        val matches = findMatches(text, query, caseSensitive = false)
        assertTrue(matches.size > 1, "expected several occurrences, got ${matches.size}")

        // The selection the editor really held: the reported [1900, 2000)
        // range, clipped to the note it was still valid in.
        val before = TextFieldValue(text, selection = TextRange(1900, 1955))

        val after = replaceAllMatches(before, matches, replacement = "")

        // Every occurrence is gone and nothing else changed.
        assertEquals(text.replace(query, ""), after.text)
        assertEquals(1955 - query.length * matches.size, after.text.length)

        // The crash condition: the shrink moves the end of the text past the
        // old selection end, so any offset kept from before is now invalid.
        assertTrue(
            after.text.length < 1900,
            "the shrink must leave the old selection past the new end " +
                "(new length ${after.text.length})"
        )

        // What the field receives is always safe.
        assertTrue(after.selection.start in 0..after.text.length)
        assertTrue(after.selection.end in 0..after.text.length)
        // And the guard is a no-op on an already valid value.
        assertEquals(after, after.withSelectionInBounds())
    }

    @Test
    fun shorterReplacementAlsoShrinksAndParksTheCaretAtTheFirstMatch() {
        val text = noteText()
        val matches = findMatches(text, query, caseSensitive = false)
        val before = TextFieldValue(text, selection = TextRange(1200, 1600))

        val after = replaceAllMatches(before, matches, replacement = "Samariter")

        assertEquals(text.replace(query, "Samariter"), after.text)
        assertEquals(prefix.length, after.selection.start)
        assertTrue(after.selection.end <= after.text.length)
    }

    @Test
    fun staleMatchRangesAreClampedInsteadOfSlicingPastTheEnd() {
        // Matches found in the longer note, applied after it shrank under
        // them (the old [1900, 2000) shape on a 1955-char note).
        val text = noteText()
        val stale = listOf(IntRange(1900, 1999))

        val after = replaceAllMatches(
            TextFieldValue(text, selection = TextRange(0, 1955)),
            stale,
            replacement = ""
        )

        assertEquals(text.substring(0, 1900), after.text)
        assertTrue(after.selection.end <= after.text.length)
    }

    @Test
    fun noMatchesIsANoOp() {
        val value = TextFieldValue("short note", selection = TextRange(2, 4))
        assertEquals(value, replaceAllMatches(value, emptyList(), replacement = ""))
    }
}
