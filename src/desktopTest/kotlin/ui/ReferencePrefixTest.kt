package ui

import kotlin.test.Test
import kotlin.test.assertEquals


/**
 * Unit tests for [referencePrefixAt] — the backwards scan that finds the
 * `$` starting a book-name token for the editor's inline autocomplete
 * (typing `$Joh` → "Joh" suggests "John"). The function returns the
 * index of the `$`, or -1 when the caret is not on an autocompletable
 * reference prefix.
 */
class ReferencePrefixTest {

    @Test
    fun findsDollarAtStartOfTypedBookName() {
        // Caret at the end of the typed prefix, after the $.
        assertEquals(0, referencePrefixAt("\$Joh", 4))
        // "See $Lukas": the $ sits at index 4 (S-e-e-space-$).
        assertEquals(4, referencePrefixAt("See \$Lukas", 10))
    }

    @Test
    fun supportsMultiWordBookNames() {
        // "1 Mose" — space and digit are part of the prefix, so the scan
        // walks straight back to the opening $.
        assertEquals(0, referencePrefixAt("\$1 Mose 3", 9))
    }

    @Test
    fun freshDollarOnlyAfterWordBoundary() {
        // "$" alone directly before the caret is a valid fresh start only
        // when preceded by whitespace, punctuation or start of text…
        assertEquals(5, referencePrefixAt("Read \$", 6))
        assertEquals(0, referencePrefixAt("\$", 1))
        assertEquals(4, referencePrefixAt("abc,\$", 5))
        // …not when glued to a word ("abc$" reads as a variable name).
        assertEquals(-1, referencePrefixAt("abc\$", 4))
    }

    @Test
    fun caretInsideAWordIsNotAPrefix() {
        // The caret sits before a letter, so the scan refuses: the user is
        // mid-word, not completing a reference.
        assertEquals(-1, referencePrefixAt("\$John", 4))
        // Caret at position 0 (or beyond the text) is never a prefix.
        assertEquals(-1, referencePrefixAt("\$Joh", 0))
        assertEquals(-1, referencePrefixAt("\$Joh", 5))
    }

    @Test
    fun moneyIsNotABookReference() {
        // The '.' in "$5.99" terminates the backwards scan before it can
        // reach the $, so money never autocompletes as a book name.
        assertEquals(-1, referencePrefixAt("price \$5.99", 11))
    }

    @Test
    fun noDollarMeansNoPrefix() {
        assertEquals(-1, referencePrefixAt("Read John", 9))
        assertEquals(-1, referencePrefixAt("", 0))
    }

    @Test
    fun newlineTerminatesTheScan() {
        // A line break BEFORE the $ is fine — the scan still reaches it…
        assertEquals(5, referencePrefixAt("line\n\$Jo", 8))
        // …but a break BETWEEN the $ and the caret aborts the scan.
        assertEquals(-1, referencePrefixAt("\$J\no", 4))
    }

    @Test
    fun punctuationTerminatesTheScan() {
        // A terminator between the caret and the $ aborts the scan.
        assertEquals(-1, referencePrefixAt("\$Joh, and", 5))
    }
}
