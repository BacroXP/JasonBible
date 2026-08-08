package ui

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
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
    fun normalStyleIsTheBaselineScheme() {
        val light = appColorScheme(false, AppColorStyle.NORMAL, 0xFF3B82F6L)
        assertEquals(lightColorScheme().primary, light.primary)
        val dark = appColorScheme(true, AppColorStyle.NORMAL, 0xFF3B82F6L)
        assertEquals(darkColorScheme().primary, dark.primary)
    }

    @Test
    fun grayStyleProducesGrayscalePrimaries() {
        val light = appColorScheme(false, AppColorStyle.GRAY, 0xFF3B82F6L)
        val dark = appColorScheme(true, AppColorStyle.GRAY, 0xFF3B82F6L)
        // Zero-saturation seed → every tonal role is R == G == B.
        for (scheme in listOf(light, dark)) {
            assertEquals(scheme.primary.red, scheme.primary.green, 0.001f)
            assertEquals(scheme.primary.green, scheme.primary.blue, 0.001f)
            assertEquals(scheme.primaryContainer.red, scheme.primaryContainer.green, 0.001f)
            assertEquals(scheme.secondary.red, scheme.secondary.green, 0.001f)
            assertEquals(scheme.tertiary.red, scheme.tertiary.green, 0.001f)
        }
        // Gray is deliberately NOT colorful.
        assertTrue(light.primary.green > 0.2f && light.primary.green < 0.9f)
    }

    @Test
    fun saturatedStyleDiffersFromNormal() {
        val normal = appColorScheme(false, AppColorStyle.NORMAL, 0xFF3B82F6L)
        val saturated = appColorScheme(false, AppColorStyle.SATURATED, 0xFF3B82F6L)
        assertTrue(
            saturated.primary != normal.primary,
            "saturated primary should differ from the muted baseline"
        )
        // The saturated seed (#7C3AED) is high-chroma: its primary should
        // carry real saturation, unlike the muted M3 baseline.
        val (_, sat, _) = saturated.primary.toHsl()
        assertTrue(sat > 0.5f, "saturated primary saturation was $sat")
    }

    @Test
    fun customStyleTracksTheSeedHue() {
        val seed = Color(0xFF22C55E) // green
        val scheme = appColorScheme(false, AppColorStyle.CUSTOM, 0xFF22C55E)
        val (seedHue, _, _) = seed.toHsl()
        val (primaryHue, _, _) = scheme.primary.toHsl()
        // Light tone 40 preserves the seed hue (small fuzz for float math).
        assertEquals(seedHue, primaryHue, 0.02f)
        // Tertiary is the +30° companion.
        val (tertiaryHue, _, _) = scheme.tertiary.toHsl()
        assertEquals((seedHue + 1f / 12f) % 1f, tertiaryHue, 0.02f)
    }

    @Test
    fun customSeedIsApplied() {
        val blue = appColorScheme(false, AppColorStyle.CUSTOM, 0xFF3B82F6L)
        val green = appColorScheme(false, AppColorStyle.CUSTOM, 0xFF22C55E)
        assertTrue(blue.primary != green.primary)
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
