package data

import testutil.TestEnv
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue


/**
 * Settings → Notes → Save location moves the library to the picked folder.
 * These cover the two promises the UI makes: the notes really move (not
 * copy) and an existing file at the destination is never overwritten.
 */
class NotesFolderMigrationTest {

    private fun tempDir(prefix: String): Path =
        Files.createTempDirectory(prefix).toAbsolutePath().normalize()

    @Test
    fun movesNotesSubfoldersAndMediaToThePickedFolder() {
        // Redirects user.home before SettingsManager initialises its file.
        TestEnv.homeDir
        val original = SettingsManager.notesFolder
        val source = tempDir("bibleapp-notes-source")
        val destination = tempDir("bibleapp-notes-target").resolve("library")
        try {
            Files.writeString(source.resolve("Sermon.note"), "# Sermon\n")
            Files.createDirectories(source.resolve("Series"))
            Files.writeString(source.resolve("Series/One.note"), "# One\n")
            Files.createDirectories(source.resolve("media"))
            Files.writeString(source.resolve("media/clip.txt"), "media")
            SettingsManager.notesFolder = source.toString()
            assertEquals(source, NotesRepository.notesFolder())

            val result = NotesRepository.migrateTo(destination)

            assertTrue(result.ok, "migration failed: ${result.error}")
            assertEquals(0, result.skipped)
            assertEquals(destination, result.folder)
            assertEquals(destination.toString(), SettingsManager.notesFolder)
            assertEquals(destination, NotesRepository.notesFolder())
            assertTrue(Files.exists(destination.resolve("Sermon.note")))
            assertTrue(Files.exists(destination.resolve("Series/One.note")))
            assertTrue(Files.exists(destination.resolve("media/clip.txt")))
            // Moved, not copied.
            assertFalse(Files.exists(source.resolve("Sermon.note")))
            assertFalse(Files.exists(source.resolve("Series")))
        } finally {
            SettingsManager.notesFolder = original
        }
    }

    @Test
    fun neverOverwritesFilesAlreadyInTheTarget() {
        TestEnv.homeDir
        val original = SettingsManager.notesFolder
        val source = tempDir("bibleapp-notes-collide-source")
        val destination = tempDir("bibleapp-notes-collide-target")
        try {
            Files.writeString(source.resolve("Sermon.note"), "# New\n")
            Files.writeString(destination.resolve("Sermon.note"), "# Old\n")
            SettingsManager.notesFolder = source.toString()

            val result = NotesRepository.migrateTo(destination)

            assertTrue(result.ok, "migration failed: ${result.error}")
            assertEquals(1, result.skipped)
            assertEquals("# Old\n", Files.readString(destination.resolve("Sermon.note")))
            assertTrue(
                Files.exists(source.resolve("Sermon.note")),
                "the conflicting note must stay where it was"
            )
        } finally {
            SettingsManager.notesFolder = original
        }
    }
}
