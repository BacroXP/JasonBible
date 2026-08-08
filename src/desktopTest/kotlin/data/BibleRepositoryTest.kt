package data

import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import testutil.TestEnv
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue


/**
 * Tests for [BibleRepository]'s module loading and caching: the per-module
 * cache (parsing once, returning the SAME list instance afterwards), the
 * in-flight guard that shares one parse between concurrent cold callers,
 * and the current-module helpers. The SettingsManager-dependent tests
 * redirect `user.home` via [TestEnv] so the real settings are untouched.
 */
class BibleRepositoryTest {

    companion object {
        init {
            TestEnv.homeDir
        }
    }

    @Test
    fun loadModuleReturnsEmptyListForUnknownModule() = runBlocking {
        // A module id not in the catalog yields an empty list (the app
        // renders \"nothing to show\"), never an exception.
        assertTrue(BibleRepository.loadModule("no_such_module_xyz").isEmpty())
    }

    @Test
    fun loadModuleCachesParsedResult() = runBlocking {
        val first = BibleRepository.loadModule(BibleRepository.INTERLINEAR_MODULE_ID)
        assertTrue(first.isNotEmpty())

        // Second call is a cache hit: the very same list instance, so no
        // re-parse of the ~6.5 MB Greek module.
        val second = BibleRepository.loadModule(BibleRepository.INTERLINEAR_MODULE_ID)
        assertSame(first, second)
    }

    @Test
    fun concurrentLoadModuleCallsShareOneParse() = runBlocking {
        // Two callers hit a COLD module at once — "tr" is deliberately a
        // module no other test loads, so the cache can't mask this. The
        // in-flight guard makes the second await the FIRST's parse (both
        // receive the same list instance). Without the guard each would
        // parse its own list and the instances would differ.
        val first = async { BibleRepository.loadModule("tr") }
        val second = async { BibleRepository.loadModule("tr") }
        assertSame(first.await(), second.await())
    }

    @Test
    fun currentModuleIdFallsBackToCatalogDefaultForStaleSavedId() {
        // A saved translation id that no longer maps to a module (e.g. the
        // user swapped out files) resolves through the catalog default.
        SettingsManager.translation = "not_a_real_module_id"
        val id = BibleRepository.currentModuleId()
        assertNotNull(id)
        assertEquals(BibleCatalog.defaultId, id)
    }

    @Test
    fun germanAbbreviationsResolveToCanonicalBooks() {
        // The German books list (books_de.json) carries the standard
        // SWORD/Luther abbreviation set. References like `$Joh$3$16`
        // depend on these resolving to canonical numbers — `$Joh` is the
        // abbreviation the app is expected to accept for Johannes/John.
        // Note the index keys each whole field lowercased (no space
        // splitting), so every alias must be a single token to resolve.
        val index = BibleCatalog.nameToBookNumber
        assertEquals(43, index["joh"])
        assertEquals(6, index["jos"])
        assertEquals(19, index["ps"])
        assertEquals(23, index["jes"])
        assertEquals(40, index["mt"])
        assertEquals(42, index["lk"])
        assertEquals(44, index["apg"])
        assertEquals(45, index["r\u00F6m"]) // Röm
        assertEquals(46, index["1kor"])
        assertEquals(66, index["offb"])

        // And through the resolution entry point the editor uses for a
        // tapped `$Joh` chip.
        assertEquals(43, BibleRepository.bookNumberFor("Joh"))
    }

    @Test
    fun cachedBooksReflectsLoadState() = runBlocking {
        // Pick a module no other test loads so this stays order-independent.
        SettingsManager.translation = "wo_wol_nt_2010"
        val id = BibleRepository.currentModuleId()!!
        assertNull(BibleRepository.cachedBooks()) // not loaded yet

        val loaded = BibleRepository.loadBooks()
        assertTrue(loaded.isNotEmpty())
        // cachedBooks() hands back the very list that was parsed.
        assertSame(loaded, BibleRepository.cachedBooks())
    }
}
