package ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
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
    fun normalStyleIsExactlyThePagesPalette() {
        for (dark in listOf(true, false)) {
            val pages = pagesScheme(dark)
            val normal = appColorScheme(dark, AppColorStyle.NORMAL, 0xFF3B82F6L)
            assertEquals(pages.primary, normal.primary)
            assertEquals(pages.background, normal.background)
            assertEquals(pages.surface, normal.surface)
            assertEquals(pages.onSurface, normal.onSurface)
        }
    }

    @Test
    fun everyStyleKeepsThePagesDarknessAndContrast() {
        for (style in AppColorStyle.entries) {
            val dark = appColorScheme(true, style, 0xFF3B82F6L)
            val light = appColorScheme(false, style, 0xFF3B82F6L)
            assertTrue(
                dark.background.luminance() < 0.2f,
                "dark background for ${style.key}"
            )
            assertTrue(
                light.background.luminance() > 0.7f,
                "light background for ${style.key}"
            )
            // Body text must stay clearly readable on its panel.
            assertTrue(
                dark.onSurface.luminance() > dark.surface.luminance() + 0.5f,
                "dark text contrast for ${style.key}"
            )
            assertTrue(
                light.onSurface.luminance() < light.surface.luminance() - 0.5f,
                "light text contrast for ${style.key}"
            )
        }
    }

    @Test
    fun saturatedStyleRaisesChroma() {
        val normal = appColorScheme(true, AppColorStyle.NORMAL, 0L)
        val saturated = appColorScheme(true, AppColorStyle.SATURATED, 0L)
        assertTrue(saturationOf(saturated.primary) > saturationOf(normal.primary))
        assertTrue(saturationOf(saturated.surface) > saturationOf(normal.surface))
        assertTrue(saturated.primary != normal.primary)
        assertTrue(saturated.surface != normal.surface)
    }

    @Test
    fun grayStyleIsMonochrome() {
        for (dark in listOf(true, false)) {
            val gray = appColorScheme(dark, AppColorStyle.GRAY, 0xFF3B82F6L)
            for (role in listOf(
                gray.primary,
                gray.secondary,
                gray.background,
                gray.surface,
                gray.surfaceContainerHigh,
                gray.outline,
                gray.error
            )) {
                assertEquals(0f, saturationOf(role), 0.01f, "role in ${if (dark) "dark" else "light"}")
            }
        }
    }

    @Test
    fun customStyleTakesTheSeedHue() {
        val blueSeed = 0xFF3B82F6L
        val greenSeed = 0xFF22C55EL
        val blue = appColorScheme(true, AppColorStyle.CUSTOM, blueSeed)
        val green = appColorScheme(true, AppColorStyle.CUSTOM, greenSeed)
        assertEquals(hueOf(Color(blueSeed)), hueOf(blue.primary), 0.02f)
        assertEquals(hueOf(Color(greenSeed)), hueOf(green.primary), 0.02f)
        assertTrue(blue.primary != green.primary)
        // Panels pick up the seed but stay navy-dark.
        assertTrue(blue.surface.luminance() < 0.2f)
    }

    @Test
    fun customStyleWithNeutralSeedKeepsTheGoldAccent() {
        val normal = appColorScheme(true, AppColorStyle.NORMAL, 0L)
        val graySeed = appColorScheme(true, AppColorStyle.CUSTOM, 0xFF808080L)
        assertEquals(normal.primary, graySeed.primary)
    }

    @Test
    fun darkAndLightPagesSchemesDiffer() {
        val light = pagesScheme(false)
        val dark = pagesScheme(true)
        assertTrue(light.background != dark.background, "light/dark backgrounds must differ")
        assertTrue(light.primary != dark.primary, "light/dark primaries must differ")
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


internal fun saturationOf(color: Color): Float = color.toHsl().second

internal fun hueOf(color: Color): Float = color.toHsl().first
