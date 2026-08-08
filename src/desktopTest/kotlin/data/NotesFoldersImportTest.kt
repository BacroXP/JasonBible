package data

import model.ParsedNote
import testutil.TestEnv
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue


/**
 * Tests for the notes organisation layer: folder create / rename /
 * delete, moving notes between folders, importing .note / .txt / .md
 * files (never overwriting an existing note), and the `[[Title]]`
 * note-to-note link extraction. `user.home` is redirected via [TestEnv]
 * so no real notes are touched.
 */
class NotesFoldersImportTest {

    companion object {
        init {
            TestEnv.homeDir
            // Skip the bundled sample-note seeding; tests write their own.
            SettingsManager.notesInitialized = true
        }
    }

    private fun write(content: String, name: String): ParsedNote {
        NotesRepository.saveNoteInPlace(name, content)
        return assertNotNull(NotesRepository.loadNote(name))
    }

    @Test
    fun noteLinksAreExtractedFromParagraphsAndQuoteTrailing() {
        val note = write(
            "# My Title\n" +
                "\n" +
                "See [[Prayer Notes]] and [[Study Notes]].\n" +
                "\"Verse\"[#FFD54F] see also [[Devotional]]\n",
            "links-test.note"
        )
        assertEquals(listOf("Prayer Notes", "Study Notes", "Devotional"), note.links)
    }

    @Test
    fun noteLinkTokenFinderRangesIncludeBrackets() {
        val text = "See [[Alpha]] and [[Beta Gamma]] here."
        val tokens = findNoteLinkTokens(text)
        assertEquals(2, tokens.size)
        assertEquals("Alpha", tokens[0].title)
        assertEquals("Beta Gamma", tokens[1].title)
        assertEquals("[[Alpha]]", text.substring(tokens[0].sourceStart, tokens[0].sourceEnd))
        assertEquals("[[Beta Gamma]]", text.substring(tokens[1].sourceStart, tokens[1].sourceEnd))
    }

    @Test
    fun folderRoundTripCreateMoveRename() {
        assertTrue(NotesRepository.createFolder("Study"))
        val created = NotesRepository.createNote("Folder Note", folder = "Study")
        assertEquals("Study", created.folder)
        assertTrue(created.fileName.startsWith("Study/"))

        // Renaming a folder moves its notes along; the note is then found
        // under its new path (load by title, since the old path is gone).
        assertTrue(NotesRepository.renameFolder("Study", "Research"))
        val renamed = NotesRepository.listFiles().find { it.title == "Folder Note" }
        assertEquals("Research", renamed?.folder)

        // Move back to root.
        val moved = NotesRepository.moveNote(renamed!!.fileName, "")
        assertNotNull(moved)
        assertEquals("", NotesRepository.loadNote(moved)?.folder)

        // The folder is now empty and deletable.
        assertTrue(NotesRepository.deleteFolder("Research"))
        assertTrue(NotesRepository.folders().isEmpty())
    }

    @Test
    fun nonEmptyFolderRefusesDeletion() {
        NotesRepository.createFolder("Keep")
        val inside = NotesRepository.createNote("Inside", folder = "Keep")
        assertEquals(false, NotesRepository.deleteFolder("Keep"))
        assertTrue(NotesRepository.folders().contains("Keep"))

        // Moving the note out empties the folder, which is then deletable.
        val moved = NotesRepository.moveNote(inside.fileName, "")
        assertNotNull(moved)
        assertTrue(NotesRepository.deleteFolder("Keep"))
        assertTrue(!NotesRepository.folders().contains("Keep"))
    }

    @Test
    fun nestedSubfoldersAreSupported() {
        // "Unterordner": folder paths nest via `/`, and renaming a parent
        // folder moves the whole nested tree along with its notes.
        assertTrue(NotesRepository.createFolder("Study/Deep"))
        val nested = NotesRepository.createNote("Nested", folder = "Study/Deep")
        assertTrue(nested.fileName.startsWith("Study/Deep/"))

        // Renaming the parent folder relocates the nested note.
        assertTrue(NotesRepository.renameFolder("Study", "Research"))
        val relocated = NotesRepository.listFiles().find { it.title == "Nested" }
        assertNotNull(relocated)
        assertEquals("Research/Deep", relocated.folder)
        assertTrue(relocated.fileName.startsWith("Research/Deep/"))

        // Moving a nested note back to the root works too.
        val moved = NotesRepository.moveNote(relocated.fileName, "")
        assertNotNull(moved)
        assertEquals("", NotesRepository.loadNote(moved)?.folder)

        // Both nested folders are now empty and deletable.
        assertTrue(NotesRepository.deleteFolder("Research/Deep"))
        assertTrue(NotesRepository.deleteFolder("Research"))
    }

    @Test
    fun importNeverOverwritesExistingNote() {
        val tmp = Files.createTempFile("import-src", ".md")
        Files.writeString(tmp, "# Imported Title\n\nSome imported text.\n")
        val first = NotesRepository.importNote(tmp)
        assertNotNull(first.fileName)
        assertEquals("Imported Title", first.title)

        // A second import of the same file must NOT overwrite the first —
        // it gets a deduplicated name and stays undoable.
        val second = NotesRepository.importNote(tmp)
        assertNotNull(second.fileName)
        assertTrue(second.fileName != first.fileName)
        assertEquals("Imported Title", second.title)

        val loaded = NotesRepository.loadNote(first.fileName)
        assertNotNull(loaded)
        assertTrue(loaded.content.contains("Some imported text."))
    }

    @Test
    fun saveInFolderKeepsFolderOnRename() {
        NotesRepository.createFolder("Devotional")
        val created = NotesRepository.createNote("Old Title", folder = "Devotional")
        // Change the title (rename) and save — the note must STAY in its
        // folder instead of being dragged back to the root.
        val saved = NotesRepository.saveNote(
            originalFileName = created.fileName,
            content = "# New Title\n\nRenamed content.\n"
        )
        assertTrue(saved.fileName.startsWith("Devotional/"))
        assertEquals("Devotional", saved.folder)
        assertEquals("New Title", saved.title)
        // The old file is gone; the folder copy holds the new content.
        assertEquals(null, NotesRepository.loadNote(created.fileName))
    }

    @Test
    fun folderNamesCannotEscapeNotesRoot() {
        assertEquals(false, NotesRepository.createFolder("../escape"))
        assertEquals(false, NotesRepository.createFolder("/abs"))
        assertEquals(false, NotesRepository.createFolder("a//b"))
    }

    @Test
    fun findByTitleHasCaseInsensitiveFallback() {
        val note = write("# Exact Title\n", "case-test.note")
        val hit = NotesRepository.findByTitle("exact title")
        assertNotNull(hit)
        assertEquals(note.fileName, hit.fileName)
    }

    @Test
    fun importTxtKeepsContentAndDerivesTitleFromFileName() {
        val tmp = Files.createTempFile("plain", ".txt")
        Files.writeString(tmp, "Just some plain text without a heading.\n")
        val result = NotesRepository.importNote(tmp)
        assertNotNull(result.fileName)
        assertTrue(result.title.isNotBlank())
        val loaded = NotesRepository.loadNote(result.fileName)
        assertNotNull(loaded)
        assertTrue(loaded.content.contains("Just some plain text"))
    }
}
