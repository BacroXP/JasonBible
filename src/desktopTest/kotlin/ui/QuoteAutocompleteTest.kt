package ui

import androidx.compose.ui.text.input.TextFieldValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import model.Book
import model.Chapter
import model.Verse


class QuoteAutocompleteTest {

    // A tiny canon mirroring the real data shape: Genesis (book 1), John
    // (book 43, with Strong's-marked verses like the bundled modules) and
    // Revelation (book 66) so next-verse walks cross chapters and books.
    private val genesis = Book(
        book = 1,
        name = "Genesis",
        chapters = listOf(
            Chapter(1, listOf(Verse(1, "In the beginning God created the heaven and the earth.")))
        )
    )
    private val john = Book(
        book = 43,
        name = "John",
        chapters = listOf(
            Chapter(
                3,
                listOf(
                    Verse(
                        16,
                        "For God so loved the world, that he gave his only begotten Son, " +
                            "that whosoever believeth in him should not perish, but have everlasting life."
                    ),
                    Verse(
                        17,
                        "For God sent not his Son into the world to condemn the world; " +
                            "but that the world through him might be saved."
                    ),
                    Verse(
                        18,
                        "He that believeth on him{H4100} is not condemned: but he that believeth " +
                            "not is condemned already, because he hath not believed in the name of " +
                            "the only begotten Son of God."
                    )
                )
            ),
            Chapter(
                4,
                listOf(
                    Verse(
                        1,
                        "When therefore the Lord knew how the Pharisees had heard that Jesus made " +
                            "and baptized more disciples than John,"
                    )
                )
            )
        )
    )
    private val revelation = Book(
        book = 66,
        name = "Revelation",
        chapters = listOf(
            Chapter(22, listOf(Verse(21, "The grace of our Lord Jesus Christ be with you all. Amen.")))
        )
    )
    private val books = listOf(genesis, john, revelation)

    // ------------------------------------------------------------------
    // normalizeForMatch
    // ------------------------------------------------------------------

    @Test
    fun normalizeDropsPunctuationAndCollapsesWhitespace() {
        assertEquals("for god so loved", normalizeForMatch("For God so loved..."))
        assertEquals(
            "for god so loved the world that",
            normalizeForMatch("For God so loved the world, that...")
        )
        // Alphanumeric runs survive (the brace markup is stripped by
        // cleanVerseText before matching, not by the normalizer itself).
        assertEquals("he that believeth on him h4100", normalizeForMatch("He  that\tbelieveth on him{H4100}"))
    }

    // ------------------------------------------------------------------
    // quotePrefixAt
    // ------------------------------------------------------------------

    @Test
    fun prefixFoundOnFreshLine() {
        val text = "For god so loved" // 16 chars
        val prefix = quotePrefixAt(text, text.length)
        assertNotNull(prefix)
        assertEquals(0, prefix.start)
        assertEquals(16, prefix.end)
        assertEquals("for god so loved", prefix.text)
    }

    @Test
    fun prefixStartsAfterSentenceBoundary() {
        val text = "I believe. For god so loved"
        val prefix = quotePrefixAt(text, text.length)
        assertNotNull(prefix)
        assertEquals(11, prefix.start)
        assertEquals(text.length, prefix.end)
        assertEquals("for god so loved", prefix.text)
    }

    @Test
    fun prefixStripsBlockMarkers() {
        val text = "> For god so loved"
        val prefix = quotePrefixAt(text, text.length)
        assertNotNull(prefix)
        assertEquals(2, prefix.start)
        assertEquals("for god so loved", prefix.text)
    }

    @Test
    fun noPrefixMidLine() {
        val text = "For god so loved"
        assertNull(quotePrefixAt(text, 5))
    }

    @Test
    fun noPrefixTooShortOrSingleWord() {
        assertNull(quotePrefixAt("For", 3))
        assertNull(quotePrefixAt("Because", 7))
    }

    @Test
    fun noPrefixOnReferenceOrQuoteLines() {
        assertNull(quotePrefixAt("\$Joh", 4))
        assertNull(quotePrefixAt("\"text\"[#FF0000]", 15))
    }

    @Test
    fun trailingSentencePunctuationKeepsSuggestion() {
        // The user's example ends with an ellipsis — a trailing period /
        // ellipsis must not count as a sentence boundary (that would leave
        // an empty prefix and kill the suggestion).
        val ellipsis = quotePrefixAt("For god so loved...", 19)
        assertNotNull(ellipsis)
        assertEquals("for god so loved", ellipsis.text)
        assertEquals(0, ellipsis.start)
        // The replacement range still covers the typed punctuation.
        assertEquals(19, ellipsis.end)

        val period = quotePrefixAt("For god so loved.", 17)
        assertNotNull(period)
        assertEquals("for god so loved", period.text)
        assertEquals(17, period.end)

        // A real boundary before the phrase still splits it correctly.
        val mixedText = "I believe. For god so loved..." // 30 chars
        val mixed = quotePrefixAt(mixedText, mixedText.length)
        assertNotNull(mixed)
        assertEquals(11, mixed.start)
        assertEquals(30, mixed.end)
        assertEquals("for god so loved", mixed.text)
    }

    @Test
    fun caretAtEndOfTextAndAtNewlineBothWork() {
        // Caret directly on the line's trailing newline.
        val prefix = quotePrefixAt("For god so loved\n", 16)
        assertNotNull(prefix)
        assertEquals("for god so loved", prefix.text)
        // Caret at the very end of the document (no newline) also works.
        val prefix2 = quotePrefixAt("For god so loved", 16)
        assertNotNull(prefix2)
        assertEquals(16, prefix2.end)
    }

    // ------------------------------------------------------------------
    // findFreshCite
    // ------------------------------------------------------------------

    @Test
    fun freshCiteMatchesVerseStart() {
        val match = findFreshCite(books, "for god so loved")
        assertNotNull(match)
        assertEquals("John", match.book.name)
        assertEquals(3, match.chapter)
        assertEquals(16, match.verse)
        assertTrue(match.text.startsWith("For God so loved"))
    }

    @Test
    fun freshCiteStripsStrongsMarkup() {
        val match = findFreshCite(books, "he that believeth on him is not")
        assertNotNull(match)
        assertEquals(18, match.verse)
        assertTrue(match.text.startsWith("He that believeth on him is not condemned"))
        // The markup must be gone from the insertable text.
        assertTrue("{" !in match.text)
    }

    @Test
    fun freshCiteNoMatch() {
        assertNull(findFreshCite(books, "xyzzy plugh"))
    }

    // ------------------------------------------------------------------
    // computeChainSuggestion
    // ------------------------------------------------------------------

    private fun freshInsert(): TextFieldValue {
        val prefix = quotePrefixAt("For god so loved", 16)!!
        val suggestion = FreshCiteSuggestion(
            book = john,
            chapter = 3,
            verse = 16,
            text = cleanVerseText(john.chapters[0].verses[0]),
            prefixStart = prefix.start,
            prefixEnd = prefix.end
        )
        return applyCiteSuggestion(TextFieldValue("For god so loved"), suggestion)
    }

    @Test
    fun chainSuggestsNextVerseAfterInsert() {
        val after = freshInsert()
        assertEquals(after.text.length, after.selection.end)
        val chain = computeChainSuggestion(books, after.text, after.selection.end)
        assertNotNull(chain)
        assertEquals("John", chain.book.name)
        assertEquals(3, chain.chapter)
        assertEquals(17, chain.verse)
        assertEquals(after.text.length, chain.lineEnd)
    }

    @Test
    fun chainRequiresMatchingInnerText() {
        val text = "\"Some unrelated quote\"[#3B82F6] \$John&3&16 "
        assertNull(computeChainSuggestion(books, text, text.length))
    }

    @Test
    fun chainAfterRangeSuggestsNextAfterRangeEnd() {
        // The inner text must end with the last cited verse; build a real
        // one so the verification passes.
        val v16 = cleanVerseText(john.chapters[0].verses[0])
        val v17 = cleanVerseText(john.chapters[0].verses[1])
        val real = "\"$v16 $v17\"[#3B82F6] \$John&3&16-17 "
        val chain = computeChainSuggestion(books, real, real.length)
        assertNotNull(chain)
        assertEquals(18, chain.verse)
    }

    @Test
    fun chainRequiresCaretAtLineEnd() {
        val after = freshInsert()
        assertNull(computeChainSuggestion(books, after.text, 5))
    }

    // ------------------------------------------------------------------
    // applyCiteSuggestion
    // ------------------------------------------------------------------

    @Test
    fun freshInsertBuildsBlueQuoteWithReference() {
        val result = freshInsert()
        val v16 = cleanVerseText(john.chapters[0].verses[0])
        assertEquals("\"$v16\"[$CITE_BLUE_HEX] \$John&3&16 ", result.text)
        assertEquals(result.text.length, result.selection.end)
    }

    @Test
    fun chainInsertAppendsVerseAndCorrectsReference() {
        val after = freshInsert()
        val chain = computeChainSuggestion(books, after.text, after.selection.end)!!
        val appended = applyCiteSuggestion(after, chain)
        val v17 = cleanVerseText(john.chapters[0].verses[1])
        assertTrue(appended.text.endsWith("$v17\"[$CITE_BLUE_HEX] \$John&3&16-17 "))
        assertEquals(appended.text.length, appended.selection.end)

        // Tab again extends the range to 16-18.
        val chain2 = computeChainSuggestion(books, appended.text, appended.selection.end)
        assertNotNull(chain2)
        assertEquals(18, chain2.verse)
        val appended2 = applyCiteSuggestion(appended, chain2)
        assertTrue(appended2.text.endsWith("\$John&3&16-18 "))
    }

    // ------------------------------------------------------------------
    // nextVerse / buildRangeReference
    // ------------------------------------------------------------------

    @Test
    fun nextVerseWalksChaptersAndBooks() {
        val nextInChapter = nextVerse(books, john, 3, 17)
        assertEquals(Triple(john, 3, 18), nextInChapter)
        val nextChapter = nextVerse(books, john, 3, 18)
        assertEquals(Triple(john, 4, 1), nextChapter)
        val nextBook = nextVerse(books, john, 4, 1)
        assertEquals(Triple(revelation, 22, 21), nextBook)
        // Last verse of the canon has no successor.
        assertNull(nextVerse(books, revelation, 22, 21))
    }

    @Test
    fun rangeReferenceSameAndCrossChapter() {
        assertEquals("\$John&3&16-18", buildRangeReference("John", 3, 16, 3, 18))
        assertEquals("\$John&3&16-&4&1", buildRangeReference("John", 3, 16, 4, 1))
    }
}
