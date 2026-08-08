package ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue


/**
 * Tests for the deterministic waveform bar heights behind the SoundCloud
 * progress fill ([waveformHeights]): the same track must always draw the
 * same wave (stable across recompositions), different tracks differ, and
 * heights stay within their painted bounds.
 */
class MediaPlaybackTest {

    @Test
    fun waveformHeightsAreDeterministicPerSeed() {
        assertEquals(waveformHeights(42, 18), waveformHeights(42, 18))
        assertEquals(waveformHeights(-7, 5), waveformHeights(-7, 5))
        assertEquals(waveformHeights(Int.MAX_VALUE, 12), waveformHeights(Int.MAX_VALUE, 12))
    }

    @Test
    fun waveformHeightsDifferAcrossSeeds() {
        assertNotEquals(waveformHeights(1, 18), waveformHeights(2, 18))
    }

    @Test
    fun waveformHeightsStayWithinBounds() {
        waveformHeights(12345, 40).forEach { height ->
            assertTrue(height >= 0.2f && height <= 1f, "height $height out of range")
        }
    }

    @Test
    fun waveformHeightsRespectCount() {
        assertEquals(0, waveformHeights(1, 0).size)
        assertEquals(18, waveformHeights(1, 18).size)
    }
}
