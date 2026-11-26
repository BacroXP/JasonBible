package ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue


class StrongsGlossesTest {

    @Test
    fun readsTheGlossListAfterTheDefinition() {
        // H7225 — "beginning, chief (-est), first (-fruits, part, time) …"
        val glosses = strongsTranslationGlosses(
            "From the same as H7218; the <i>first</i>, in place, time, order " +
                "or rank (specifically a <i>firstfruit</i>): - beginning, " +
                "chief (-est), first (-fruits, part, time), principal thing."
        )
        assertEquals(
            listOf("beginning", "chief", "first", "principal thing"),
            glosses
        )
    }

    @Test
    fun dropsTheXMarkerAndBracketedNotes() {
        // H430 — "angels, X exceeding, God (gods) (-dess, -ly), X (very) great …"
        val glosses = strongsTranslationGlosses(
            "Plural of H433; <i>gods</i> in the ordinary sense: - angels, " +
                "X exceeding, God (gods) (-dess, -ly), X (very) great, " +
                "judges, X mighty."
        )
        assertEquals(
            listOf("angels", "exceeding", "God", "great", "judges"),
            glosses
        )
    }

    @Test
    fun handlesTheDoubleSpacedMarker() {
        // G2316 — the entry writes ":  - X exceeding, God, god [-ly, -ward]."
        val glosses = strongsTranslationGlosses(
            "Of uncertain affinity; a <i>deity</i>, especially (with G3588) " +
                "<i>the</i> supreme <i>Divinity</i>; by Hebraism <i>very:</i>" +
                "  - X exceeding, God, god [-ly, -ward]."
        )
        assertEquals(listOf("exceeding", "God", "god"), glosses)
    }

    @Test
    fun returnsNothingWhenTheEntryHasNoGlossList() {
        assertTrue(
            strongsTranslationGlosses("Of uncertain affinity; to love.").isEmpty()
        )
    }

    @Test
    fun honoursTheLimit() {
        val glosses = strongsTranslationGlosses(
            "A primitive word: - one, two, three, four, five, six, seven."
        )
        assertEquals(2, strongsTranslationGlosses(
            "A primitive word: - one, two, three, four.",
            max = 2
        ).size)
        assertTrue(glosses.size <= 5)
    }
}
