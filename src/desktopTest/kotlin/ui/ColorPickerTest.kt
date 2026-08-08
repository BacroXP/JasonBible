package ui

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue


class ColorPickerTest {

    @Test
    fun parses6DigitHex() {
        val c = colorFromHexInternal("#FFD54F")
        assertEquals(1f, c.red, 0.01f)
        assertEquals(0.835f, c.green, 0.01f)
        assertEquals(0.31f, c.blue, 0.01f)
    }

    @Test
    fun parses8DigitArgb() {
        // Old ARGB verse-marker values (alpha first) are tolerated.
        val c = colorFromHexInternal("#80FF0000")
        assertEquals(0.5f, c.alpha, 0.01f)
        assertEquals(1f, c.red, 0.01f)
        assertEquals(0f, c.green, 0.01f)
        assertEquals(0f, c.blue, 0.01f)
    }

    @Test
    fun parses3DigitShorthand() {
        val c = colorFromHexInternal("#F00")
        assertEquals(1f, c.red, 0.01f)
        assertEquals(0f, c.green, 0.01f)
        assertEquals(0f, c.blue, 0.01f)
    }

    @Test
    fun invalidHexFallsBack() {
        assertEquals(Color(0xFFFFB300), colorFromHexInternal(""))
        assertEquals(Color(0xFFFFB300), colorFromHexInternal("#GGGGGG"))
        assertEquals(Color(0xFFFFB300), colorFromHexInternal("#12345"))
    }

    @Test
    fun formatsColorToCanonical6DigitHex() {
        assertEquals("#FFD54F", colorToHexInternal(Color(0xFFFFD54F)))
        assertEquals("#000000", colorToHexInternal(Color.Black))
        assertEquals("#FFFFFF", colorToHexInternal(Color.White))
    }

    @Test
    fun hexToColorRoundTrips() {
        val hex = "#3B82F6"
        assertEquals(hex, colorToHexInternal(colorFromHexInternal(hex)))
    }

    @Test
    fun validatesMarkerHex() {
        assertTrue(isValidMarkerHex("#FFD54F"))
        assertTrue(isValidMarkerHex("#abcdef"))
        assertFalse(isValidMarkerHex(""))
        assertFalse(isValidMarkerHex("#FFF"))
        assertFalse(isValidMarkerHex("FFD54F"))
        assertFalse(isValidMarkerHex("#FFD54"))
        assertFalse(isValidMarkerHex("#FFD54FG"))
    }

    @Test
    fun sanitizesTypedInput() {
        assertEquals("", sanitizeHexInput(""))
        assertEquals("#FFD54F", sanitizeHexInput("ffd54f"))
        assertEquals("#FFD54F", sanitizeHexInput("#ffd54f"))
        assertEquals("#FFD54F", sanitizeHexInput("#ffd54fzzzz"))
        // Non-hex characters are dropped, and the length is capped.
        assertEquals("#ABCDEF", sanitizeHexInput("ab-cd_ef ghi"))
        assertEquals("#ABCDEF", sanitizeHexInput("abcdef123456"))
    }

    @Test
    fun hsvConversionIsStable() {
        // Red (hue 0) → a pure red Color.
        val red = hsvToColorInternal(0f, 1f, 1f)
        assertEquals(1f, red.red, 0.01f)
        assertEquals(0f, red.green, 0.01f)
        assertEquals(0f, red.blue, 0.01f)

        // White: any hue at sat 0 / value 1.
        val white = hsvToColorInternal(200f, 0f, 1f)
        assertEquals(1f, white.red, 0.01f)
        assertEquals(1f, white.green, 0.01f)
        assertEquals(1f, white.blue, 0.01f)

        // Black: any sat at value 0.
        val black = hsvToColorInternal(120f, 0.8f, 0f)
        assertEquals(0f, black.red, 0.01f)
        assertEquals(0f, black.green, 0.01f)
        assertEquals(0f, black.blue, 0.01f)
    }

    @Test
    fun argbToHsvRoundTripsThroughHsvToColor() {
        val argb = 0xFF3B82F6.toInt()
        val (h, s, v) = argbToHsv(argb)
        val back = hsvToColorInternal(h, s, v)
        assertEquals(
            colorToHexInternal(Color(argb)),
            colorToHexInternal(back)
        )
    }

    @Test
    fun hsvHueWrapsTo0() {
        val (h, _, _) = argbToHsv(0xFFFF0000.toInt()) // pure red
        assertEquals(0f, h, 0.01f)
    }
}
