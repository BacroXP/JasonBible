package data

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import testutil.TestEnv


/**
 * A note reference with a range (`$John&3&16-18`) must associate the note
 * with every verse of its span, so the Bible reader shows the note chip
 * under 16, 17 AND 18 — not only under the start verse.
 */
class NoteRangeTaggingTest {

    companion object {
        init {
            TestEnv.homeDir
        }
    }

    private var created: String? = null

    @AfterTest
    fun cleanUp() {
        created?.let { NotesRepository.deleteNote(it) }
    }

    @Test
    fun rangedLineReferenceTagsEveryVerseInTheSpan() {
        ensureBundledModule()
        val note = NotesRepository.createNote("Range tagging test")
        created = note.fileName
        NotesRepository.saveNoteInPlace(
            note.fileName,
            "# Range tagging test\n\n\$John&3&16-18\n"
        )

        assertTrue(
            NotesRepository.titlesForVerse("John", 3, 16).contains("Range tagging test")
        )
        assertTrue(
            NotesRepository.titlesForVerse("John", 3, 17).contains("Range tagging test")
        )
        assertTrue(
            NotesRepository.titlesForVerse("John", 3, 18).contains("Range tagging test")
        )
        assertFalse(
            NotesRepository.titlesForVerse("John", 3, 19).contains("Range tagging test")
        )
        assertFalse(
            NotesRepository.titlesForVerse("John", 3, 15).contains("Range tagging test")
        )
    }

    @Test
    fun inlineRangedTokenTagsEveryVerseInTheSpan() {
        ensureBundledModule()
        val note = NotesRepository.createNote("Inline range tagging test")
        created = note.fileName
        NotesRepository.saveNoteInPlace(
            note.fileName,
            "# Inline range tagging test\n\nRead \$John&3&16-18 today.\n"
        )

        assertTrue(
            NotesRepository.titlesForVerse("John", 3, 17)
                .contains("Inline range tagging test")
        )
        assertFalse(
            NotesRepository.titlesForVerse("John", 3, 19)
                .contains("Inline range tagging test")
        )
    }

    private fun ensureBundledModule() {
        // `bookNumberFor` resolves against the active module; point at a
        // bundled translation with the canonical English book names.
        SettingsManager.translation = "luther_1912"
    }
}
