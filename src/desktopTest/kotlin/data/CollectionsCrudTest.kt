package data

import testutil.TestEnv
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue


/**
 * Tests for the personal collections (Sammlungen) data layer in
 * [SettingsManager] — create / read / update / delete plus the verse
 * lookup [SettingsManager.collectionsForVerse]. `user.home` is
 * redirected via [TestEnv] so no real settings file is touched; each
 * test uses a unique collection id and cleans up in a finally block so
 * the shared singleton can't leak state into other test classes.
 */
class CollectionsCrudTest {

    companion object {
        init {
            TestEnv.homeDir
        }
    }

    @Test
    fun saveReadUpdateDeleteRoundTrip() {
        val id = "test-collection-${System.nanoTime()}"
        try {
            val created = CollectionEntry(
                id = id,
                name = "Gospel",
                description = "Key verses",
                references = listOf(SavedReference(43, 3, 16)),
                notes = "For evangelism",
                tags = listOf("gospel", "memory")
            )
            SettingsManager.saveCollection(created)

            val loaded = SettingsManager.collection(id)
            assertNotNull(loaded)
            assertEquals("Gospel", loaded.name)
            assertEquals("Key verses", loaded.description)
            assertEquals(listOf(SavedReference(43, 3, 16)), loaded.references)
            assertEquals(listOf("gospel", "memory"), loaded.tags)

            // Update: same id replaces the entry.
            SettingsManager.saveCollection(created.copy(name = "Renamed"))
            assertEquals("Renamed", SettingsManager.collection(id)?.name)
            assertEquals(1, SettingsManager.collections.count { it.id == id })

            // Delete.
            SettingsManager.deleteCollection(id)
            assertNull(SettingsManager.collection(id))
        } finally {
            SettingsManager.deleteCollection(id)
        }
    }

    @Test
    fun verseLevelReferenceMatchesOnlyThatVerse() {
        val id = "test-collection-verse-${System.nanoTime()}"
        try {
            SettingsManager.saveCollection(
                CollectionEntry(
                    id = id,
                    name = "John 3:16",
                    references = listOf(SavedReference(43, 3, 16))
                )
            )
            assertTrue(SettingsManager.collectionsForVerse(43, 3, 16).any { it.id == id })
            assertTrue(SettingsManager.collectionsForVerse(43, 3, 17).none { it.id == id })
            assertTrue(SettingsManager.collectionsForVerse(43, 4, 16).none { it.id == id })
        } finally {
            SettingsManager.deleteCollection(id)
        }
    }

    @Test
    fun chapterLevelReferenceMatchesAnyVerseInChapter() {
        val id = "test-collection-chapter-${System.nanoTime()}"
        try {
            SettingsManager.saveCollection(
                CollectionEntry(
                    id = id,
                    name = "Psalm 23",
                    references = listOf(SavedReference(19, 23))
                )
            )
            assertTrue(SettingsManager.collectionsForVerse(19, 23, 1).any { it.id == id })
            assertTrue(SettingsManager.collectionsForVerse(19, 23, 6).any { it.id == id })
            assertTrue(SettingsManager.collectionsForVerse(19, 24, 1).none { it.id == id })
        } finally {
            SettingsManager.deleteCollection(id)
        }
    }
}
