package ui

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue


class AppThemeTest {

    // ------------------------------------------------------------------
    // HSL round-trip
    // ------------------------------------------------------------------

    @Test
    fun hslRoundTripsThroughKnownColors() {
        // Pure red, green, blue and a mid gray must survive rgb→hsl→rgb.
        val cases = listOf(
            Color(0xFFFF0000),
            Color(0xFF00FF00),
            Color(0xFF0000FF),
            Color(0xFF808080),
            Color(0xFF3B82F6)
        )
        for (c in cases) {
            val (h, s, l) = c.toHsl()
            val back = Color.fromHsl(h, s, l)
            assertEquals(c.red, back.red, 0.001f, "red of ${c.toString()}")
            assertEquals(c.green, back.green, 0.001f, "green of ${c.toString()}")
            assertEquals(c.blue, back.blue, 0.001f, "blue of ${c.toString()}")
        }
    }

    @Test
    fun grayHasZeroSaturation() {
        val (h, s, _) = Color(0xFF9E9E9E).toHsl()
        assertEquals(0f, s, 0.001f)
        // Hue is meaningless for a gray; the returned 0 is fine.
        assertEquals(0f, h, 0.001f)
    }

    @Test
    fun fromHslProducesOpaqueColors() {
        val c = Color.fromHsl(0.6f, 1f, 0.5f)
        assertEquals(1f, c.alpha, 0.001f)
    }

    // ------------------------------------------------------------------
    // Style resolution
    // ------------------------------------------------------------------

    @Test
    fun fromKeyFallsBackToNormalForUnknownKeys() {
        assertEquals(AppColorStyle.NORMAL, AppColorStyle.fromKey("neon-rainbow"))
        assertEquals(AppColorStyle.NORMAL, AppColorStyle.fromKey(""))
        assertEquals(AppColorStyle.SATURATED, AppColorStyle.fromKey("saturated"))
        assertEquals(AppColorStyle.GRAY, AppColorStyle.fromKey("gray"))
        assertEquals(AppColorStyle.CUSTOM, AppColorStyle.fromKey("custom"))
    }

    // ------------------------------------------------------------------
    // Derived schemes
    // ------------------------------------------------------------------

    @Test
    fun everyStyleUsesThePagesPalette() {
        val light = pagesScheme(false)
        val dark = pagesScheme(true)
        for (style in AppColorStyle.entries) {
            val lightScheme = appColorScheme(false, style, 0xFF3B82F6L)
            val darkScheme = appColorScheme(true, style, 0xFF3B82F6L)
            assertEquals(light.primary, lightScheme.primary, "light primary for ${style.key}")
            assertEquals(light.background, lightScheme.background)
            assertEquals(dark.primary, darkScheme.primary, "dark primary for ${style.key}")
            assertEquals(dark.background, darkScheme.background)
        }
    }

    @Test
    fun darkAndLightPagesSchemesDiffer() {
        val light = pagesScheme(false)
        val dark = pagesScheme(true)
        assertTrue(light.background != dark.background, "light/dark backgrounds must differ")
        assertTrue(light.primary != dark.primary, "light/dark primaries must differ")
    }

    @Test
    fun styleSelectionDoesNotAlterThePagesPalette() {
        val normal = appColorScheme(false, AppColorStyle.NORMAL, 0xFF3B82F6L)
        for (style in AppColorStyle.entries) {
            val scheme = appColorScheme(false, style, 0xFF3B82F6L)
            assertEquals(normal.primary, scheme.primary)
            assertEquals(normal.surface, scheme.surface)
        }
    }

    @Test
    fun customAccentIsIgnoredByThePagesPalette() {
        val blue = appColorScheme(false, AppColorStyle.CUSTOM, 0xFF3B82F6L)
        val green = appColorScheme(false, AppColorStyle.CUSTOM, 0xFF22C55E)
        assertEquals(blue.primary, green.primary)
    }

    @Test
    fun corruptedCustomAccentFallsBackToDefault() {
        // Zero / transparent persisted accents would render the theme
        // invisible — the guard must fall back to the default seed.
        assertEquals(0xFF3B82F6L, saneAccent(0L))
        assertEquals(0xFF3B82F6L, saneAccent(0x003B82F6L)) // alpha byte zeroed
        // Opaque values pass through untouched.
        assertEquals(0xFF22C55EL, saneAccent(0xFF22C55EL))
        val scheme = appColorScheme(false, AppColorStyle.CUSTOM, 0L)
        assertEquals(1f, scheme.primary.alpha, 0.001f)
    }
}
