package data

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue


/**
 * Tests for [CrossReferences] against the REAL bundled `kjv_strongs`
 * module: parallels are derived from shared Strong's lemmas (same-
 * testament), Old-Testament quotations in the New Testament from shared
 * surface wording (H/G number spaces never overlap). Mirrors the
 * WordLexiconTest pattern — the index is built once per JVM.
 */
class CrossReferencesTest {

    companion object {
        init {
            runBlocking { CrossReferences.ensureLoaded() }
        }
    }

    @Test
    fun parallelGospelAccountsAreDetected() {
        // Matthew 14:19 — the feeding of the five thousand.
        val refs = CrossReferences.referencesFor(40, 14, 19)
        val mark = refs.find { it.book == 41 && it.chapter == 6 && it.verse == 41 }
        assertNotNull(mark, "Mark 6:41 (parallel feeding account) should be found")
        assertEquals(CrossReferences.Kind.PARALLEL, mark.kind)
        val luke = refs.find { it.book == 42 && it.chapter == 9 && it.verse == 16 }
        assertNotNull(luke, "Luke 9:16 (parallel feeding account) should be found")
        assertEquals(CrossReferences.Kind.PARALLEL, luke.kind)
    }

    @Test
    fun sameChapterAndSelfAreExcluded() {
        val refs = CrossReferences.referencesFor(40, 14, 19)
        assertFalse(refs.any { it.book == 40 && it.chapter == 14 })
        assertFalse(refs.any { it.book == 40 && it.chapter == 14 && it.verse == 19 })
    }

    @Test
    fun otQuotationsInNtAreMarked() {
        // Matthew 27:46 — "My God, my God, why hast thou forsaken me?"
        // echoes Psalm 22:1.
        val refs = CrossReferences.referencesFor(40, 27, 46)
        val psalm = refs.find { it.book == 19 && it.chapter == 22 && it.verse == 1 }
        assertNotNull(psalm, "Psalm 22:1 (OT quotation) should be found")
        assertEquals(CrossReferences.Kind.OT_QUOTE, psalm.kind)

        // Matthew 4:4 — "Man shall not live by bread alone…" quotes
        // Deuteronomy 8:3.
        val refs2 = CrossReferences.referencesFor(40, 4, 4)
        val deuteronomy = refs2.find { it.book == 5 && it.chapter == 8 }
        assertNotNull(deuteronomy, "Deuteronomy 8 (OT quotation) should be found")
        assertEquals(CrossReferences.Kind.OT_QUOTE, deuteronomy.kind)
    }

    @Test
    fun resultsAreSortedByKindThenOverlap() {
        val refs = CrossReferences.referencesFor(40, 14, 19)
        assertTrue(refs.isNotEmpty())
        val rank = mapOf(
            CrossReferences.Kind.PARALLEL to 0,
            CrossReferences.Kind.OT_QUOTE to 1,
            CrossReferences.Kind.THEMATIC to 2
        )
        val ranks = refs.map { rank[it.kind] ?: 3 }
        assertEquals(ranks.sorted(), ranks, "results must be grouped by kind")
    }

    @Test
    fun unknownOrUntaggedVersesReturnEmpty() {
        assertTrue(CrossReferences.referencesFor(999, 1, 1).isEmpty())
        // Genesis 1:1 is Strong's-tagged → derived references exist.
        assertTrue(CrossReferences.referencesFor(1, 1, 1).isNotEmpty())
    }

    @Test
    fun ensureLoadedIsIdempotent() {
        val before = CrossReferences.indexedVerseCount
        assertTrue(before > 10_000, "the index should cover the whole Bible")
        runBlocking { CrossReferences.ensureLoaded() }
        assertEquals(before, CrossReferences.indexedVerseCount)
    }

    @Test
    fun curatedSlotIsPreparedButEmpty() {
        // No curated dataset is bundled; the structure is prepared but
        // must not invent references.
        assertTrue(CrossReferences.curatedFor(40, 1, 1).isEmpty())
    }
}
