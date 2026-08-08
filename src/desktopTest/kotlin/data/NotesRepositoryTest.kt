package data

import model.BookBlock
import model.ChapterReferenceBlock
import model.HeadingBlock
import model.ListBlock
import model.NoteBlock
import model.NoteReference
import model.ParagraphBlock
import model.ParsedNote
import model.QuoteBlock
import model.VerseReferenceBlock
import testutil.TestEnv
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue


/**
 * Tests for the note-markdown parser behind [NotesRepository] (the
 * private `parseNoteFile` — exercised through the public write/read
 * round-trip `saveNoteInPlace` → `loadNote`). Covers the block grammar:
 * headings, lists, quotes (plain, deep, colored), reference lines at all
 * three granularities (book / chapter / verse + label), inline references
 * embedded in paragraphs, blank-line skipping and the filename fallback
 * title. `user.home` is redirected via [TestEnv] so no real notes are
 * touched.
 */
class NotesRepositoryTest {

    companion object {
        init {
            TestEnv.homeDir
            // The repository seeds bundled sample notes on first access
            // unless this flag is set; tests write their own files.
            SettingsManager.notesInitialized = true
        }
    }

    /** Write [content] to a uniquely-named note and parse it back. */
    private fun parse(content: String, name: String = "parse-test"): ParsedNote {
        val fileName = "$name.note"
        NotesRepository.saveNoteInPlace(fileName, content)
        val note = NotesRepository.loadNote(fileName)
        assertNotNull(note, "note $fileName must parse")
        return note
    }

    @Test
    fun headingsAndListsParseToBlocks() {
        val note = parse(
            """
            |# My Title
            |
            |## Sub Section
            |
            |- bullet one
            |- bullet two
            |
            |1. first item
            |2. second item
            |""".trimMargin()
        )
        assertEquals("My Title", note.title)
        assertEquals(
            listOf<NoteBlock>(
                HeadingBlock(1, "My Title"),
                HeadingBlock(2, "Sub Section"),
                ListBlock(ordered = false, text = "bullet one"),
                ListBlock(ordered = false, text = "bullet two"),
                ListBlock(ordered = true, text = "first item"),
                ListBlock(ordered = true, text = "second item")
            ),
            note.blocks
        )
    }

    @Test
    fun blankLinesAreSkippedNotBlocked() {
        // Blank lines separate blocks but produce no block of their own.
        val note = parse("\n\n# Title\n\n\nparagraph\n\n")
        assertEquals(2, note.blocks.size)
        assertEquals(HeadingBlock(1, "Title"), note.blocks[0])
        assertEquals(ParagraphBlock("paragraph"), note.blocks[1])
    }

    @Test
    fun quotesParseWithDepthAndColoredVariant() {
        val note = parse(
            """
            |> single depth
            |>> double depth
            |># numbered item
            |> text
            |"Inner text"[#FFD54F] trailing
            |"Plain quote"
            |""".trimMargin()
        )
        assertEquals(
            listOf<NoteBlock>(
                QuoteBlock(depth = 1, text = "single depth"),
                QuoteBlock(depth = 2, text = "double depth"),
                ListBlock(ordered = true, text = "numbered item"),
                QuoteBlock(depth = 1, text = "text"),
                QuoteBlock(depth = 0, text = "Inner texttrailing", colorHex = "FFD54F"),
                QuoteBlock(depth = 0, text = "Plain quote", colorHex = null)
            ),
            note.blocks
        )
    }

    @Test
    fun referenceLinesParseAtThreeGranularities() {
        val note = parse(
            """
            |# My Title
            |${'$'}Lukas
            |${'$'}Lukas${'$'}3
            |${'$'}Lukas${'$'}3${'$'}16
            |${'$'}Lukas${'$'}3${'$'}16 My label
            |${'$'}1 Mose${'$'}3${'$'}16
            |""".trimMargin()
        )
        assertEquals(
            listOf<NoteBlock>(
                HeadingBlock(1, "My Title"),
                BookBlock("Lukas"),
                ChapterReferenceBlock("Lukas", 3),
                VerseReferenceBlock("Lukas", 3, 16, extra = null),
                VerseReferenceBlock("Lukas", 3, 16, extra = "My label"),
                VerseReferenceBlock("1 Mose", 3, 16, extra = null)
            ),
            note.blocks
        )
        // Only verse-scoped references feed the reference index — the
        // chapter-only and book-only lines contribute none, and the
        // labeled line is still a verse ref (the label is just its
        // `extra`). The title is whatever the `# ` heading set.
        assertEquals(3, note.references.size)
        assertEquals(NoteReference("My Title", "Lukas", 3, 16, label = null), note.references[0])
        assertEquals(NoteReference("My Title", "Lukas", 3, 16, "My label"), note.references[1])
        assertEquals(NoteReference("My Title", "1 Mose", 3, 16), note.references[2])
    }

    @Test
    fun inlineReferencesInParagraphsBecomeVerseScopedReferences() {
        // `$Book$C$V` tokens embedded in a sentence (ending at a space)
        // are extracted; book-only tokens have no verse and are skipped.
        val note = parse(
            """
            |Read ${'$'}Lukas${'$'}3${'$'}16 today, and see ${'$'}Joh${'$'}3${'$'}16 too.
            |See ${'$'}Lukas for context.
            |""".trimMargin()
        )
        assertEquals(2, note.blocks.size) // 2 paragraphs, no heading
        assertTrue(note.blocks[1] is ParagraphBlock)
        assertEquals(2, note.references.size)
        // Inline refs carry the note title like line refs do (here the
        // filename fallback, since this note has no `# ` heading).
        assertEquals(NoteReference("parse-test", "Lukas", 3, 16), note.references[0])
        assertEquals(NoteReference("parse-test", "Joh", 3, 16), note.references[1])
    }

    @Test
    fun titleFallsBackToFileNameWithoutHeading() {
        // No `# ` line anywhere → the file name (without extension) is
        // the title, and the paragraph becomes the only block.
        val note = parse("just a paragraph\n\nanother one", name = "fallback-title")
        assertEquals("fallback-title", note.title)
        assertEquals(2, note.blocks.size)
        assertEquals(ParagraphBlock("just a paragraph"), note.blocks[0])
    }

    @Test
    fun dollarMoneyIsNotARefLineAndParsesAsParagraph() {
        // `$5.99` doesn't match the reference regex (needs a book name),
        // and — unlike a `$Book` line — isn't a reference line at all.
        val note = parse("The price is \$5.99")
        assertEquals(1, note.blocks.size)
        assertEquals(ParagraphBlock("The price is \$5.99"), note.blocks[0])
        assertTrue(note.references.isEmpty())
    }
}
