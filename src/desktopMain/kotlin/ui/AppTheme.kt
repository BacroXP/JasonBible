package ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import data.DEFAULT_ACCENT_ARGB
import kotlin.math.max
import kotlin.math.min


// ---------------------------------------------------------------------------
// App color styles
//
// The app supports four selectable color styles (Settings → Appearance):
//   NORMAL     — the stock Material 3 baseline scheme (what the app shipped
//                with; purple-toned in light, lavender in dark).
//   SATURATED  — the same hue family pushed to high chroma so the UI reads
//                noticeably more vivid than the muted M3 baseline.
//   GRAY       — a neutral monochrome palette (zero saturation), for a
//                subdued, paper-like look.
//   CUSTOM     — a tonal palette derived from a user-picked seed color,
//                exposed as a row of accent swatches in Settings.
//
// Desktop Compose has no dynamicColorScheme() (Android-only), so the
// derived styles build the full M3 ColorScheme by hand: the seed color is
// converted to HSL and the ~24 key roles are computed as tones along that
// hue (with a 30° tertiary shift), leaving the neutral surface/error
// families at their baseline values.
// ---------------------------------------------------------------------------

/** The four selectable color styles, keyed by the persisted string. */
enum class AppColorStyle(val key: String, val label: String) {
    NORMAL("normal", "Normal"),
    SATURATED("saturated", "Saturated"),
    GRAY("gray", "Gray"),
    CUSTOM("custom", "Custom");

    companion object {
        /** Resolve a persisted key to a style; unknown keys → NORMAL so a
         *  stale config value can never crash the theme build. */
        fun fromKey(key: String): AppColorStyle =
            entries.firstOrNull { it.key == key } ?: NORMAL
    }
}


/** Fully-rounded "pill" shape used for chips, toggles and badges. */
internal val PillShape = RoundedCornerShape(999.dp)


/** Preset accent seeds offered for the CUSTOM style. */
internal val ACCENT_SEEDS: List<Long> = listOf(
    DEFAULT_ACCENT_ARGB, // blue
    0xFF6366F1L, // indigo
    0xFF8B5CF6L, // violet
    0xFFEC4899L, // pink
    0xFFEF4444L, // red
    0xFFF97316L, // orange
    0xFFF59E0BL, // amber
    0xFF22C55EL, // green
    0xFF14B8A6L, // teal
    0xFF0EA5E9L  // sky
)


/**
 * The ColorScheme for the active [style], given the current dark-mode
 * flag and (for CUSTOM) the persisted accent seed. NORMAL returns the
 * stock baseline; every other style derives its key roles from a seed
 * via [deriveTonalScheme].
 */
internal fun appColorScheme(
    dark: Boolean,
    style: AppColorStyle,
    customAccent: Long
): ColorScheme = when (style) {
    AppColorStyle.NORMAL -> if (dark) darkColorScheme() else lightColorScheme()
    AppColorStyle.SATURATED -> deriveTonalScheme(Color(0xFF7C3AEDL), dark)
    AppColorStyle.GRAY -> deriveTonalScheme(Color(0xFF9E9E9EL), dark)
    AppColorStyle.CUSTOM -> deriveTonalScheme(Color(saneAccent(customAccent)), dark)
}


/** The default seed used when the persisted custom accent is missing /
 *  corrupted (zero or fully transparent — which would render the whole
 *  theme invisible). Mirrors the AppColorStyle.fromKey fallback so a
 *  stale config value can never break the theme build. */
internal fun saneAccent(customAccent: Long): Long =
    if (customAccent and 0xFF000000L == 0L) DEFAULT_ACCENT_ARGB else customAccent


/** Preview swatch color for a style (its primary), for the Settings UI. */
internal fun stylePrimaryColor(
    style: AppColorStyle,
    dark: Boolean,
    customAccent: Long
): Color = appColorScheme(dark, style, customAccent).primary


/**
 * Builds a tonal M3 scheme from [seed]: the seed's hue drives primary /
 * secondary / tertiary (secondary desaturated, tertiary shifted +30°),
 * and each role is placed on that hue's lightness ramp. Surface and
 * error families stay at the baseline defaults, so the derived styles
 * keep the app's neutral chrome and only recolor the "brand" roles.
 */
internal fun deriveTonalScheme(seed: Color, dark: Boolean): ColorScheme {
    val (hue, sat, _) = seed.toHsl()
    val tertiaryHue = (hue + 1f / 12f) % 1f // +30° shift

    fun tonal(lightness: Float, s: Float = sat): Color =
        Color.fromHsl(hue, s.coerceIn(0f, 1f), lightness)
    fun tertiary(lightness: Float, s: Float = sat): Color =
        Color.fromHsl(tertiaryHue, s.coerceIn(0f, 1f), lightness)
    // Secondary sits at ~55% of the primary's saturation (M3-style muted
    // companion), so it reads as a softer sibling rather than a duplicate.
    val secondarySat = sat * 0.55f

    return if (dark) {
        darkColorScheme(
            primary = tonal(0.80f),
            onPrimary = tonal(0.10f),
            primaryContainer = tonal(0.30f),
            onPrimaryContainer = tonal(0.90f),
            inversePrimary = tonal(0.40f),
            secondary = tonal(0.80f, secondarySat),
            onSecondary = tonal(0.10f, secondarySat),
            secondaryContainer = tonal(0.30f, secondarySat),
            onSecondaryContainer = tonal(0.90f, secondarySat),
            tertiary = tertiary(0.80f),
            onTertiary = tertiary(0.10f),
            tertiaryContainer = tertiary(0.30f),
            onTertiaryContainer = tertiary(0.90f),
            primaryFixed = tonal(0.90f),
            primaryFixedDim = tonal(0.80f),
            onPrimaryFixed = tonal(0.10f),
            onPrimaryFixedVariant = tonal(0.30f),
            secondaryFixed = tonal(0.90f, secondarySat),
            secondaryFixedDim = tonal(0.80f, secondarySat),
            onSecondaryFixed = tonal(0.10f, secondarySat),
            onSecondaryFixedVariant = tonal(0.30f, secondarySat),
            tertiaryFixed = tertiary(0.90f),
            tertiaryFixedDim = tertiary(0.80f),
            onTertiaryFixed = tertiary(0.10f),
            onTertiaryFixedVariant = tertiary(0.30f)
        )
    } else {
        lightColorScheme(
            primary = tonal(0.40f),
            onPrimary = tonal(0.97f),
            primaryContainer = tonal(0.90f),
            onPrimaryContainer = tonal(0.15f),
            inversePrimary = tonal(0.80f),
            secondary = tonal(0.40f, secondarySat),
            onSecondary = tonal(0.97f, secondarySat),
            secondaryContainer = tonal(0.90f, secondarySat),
            onSecondaryContainer = tonal(0.15f, secondarySat),
            tertiary = tertiary(0.40f),
            onTertiary = tertiary(0.97f),
            tertiaryContainer = tertiary(0.90f),
            onTertiaryContainer = tertiary(0.15f),
            primaryFixed = tonal(0.90f),
            primaryFixedDim = tonal(0.80f),
            onPrimaryFixed = tonal(0.15f),
            onPrimaryFixedVariant = tonal(0.35f),
            secondaryFixed = tonal(0.90f, secondarySat),
            secondaryFixedDim = tonal(0.80f, secondarySat),
            onSecondaryFixed = tonal(0.15f, secondarySat),
            onSecondaryFixedVariant = tonal(0.35f, secondarySat),
            tertiaryFixed = tertiary(0.90f),
            tertiaryFixedDim = tertiary(0.80f),
            onTertiaryFixed = tertiary(0.15f),
            onTertiaryFixedVariant = tertiary(0.35f)
        )
    }
}


/** (hue, saturation, lightness) in 0..1, alpha ignored. */
internal fun Color.toHsl(): Triple<Float, Float, Float> {
    val r = red
    val g = green
    val b = blue
    val max = max(r, max(g, b))
    val min = min(r, min(g, b))
    val l = (max + min) / 2f
    if (max == min) return Triple(0f, 0f, l)
    val d = max - min
    val s = if (l > 0.5f) d / (2f - max - min) else d / (max + min)
    val h = when (max) {
        r -> (g - b) / d + (if (g < b) 6f else 0f)
        g -> (b - r) / d + 2f
        else -> (r - g) / d + 4f
    } / 6f
    return Triple(h, s, l)
}


/** Build an opaque Color from (hue, saturation, lightness) in 0..1. */
internal fun Color.Companion.fromHsl(h: Float, s: Float, l: Float): Color {
    val sat = s.coerceIn(0f, 1f)
    val light = l.coerceIn(0f, 1f)
    if (sat == 0f) {
        val v = light
        return Color(v, v, v)
    }
    val q = if (light < 0.5f) light * (1f + sat) else light + sat - light * sat
    val p = 2f * light - q
    fun hueToRgb(t0: Float): Float {
        var t = t0
        if (t < 0f) t += 1f
        if (t > 1f) t -= 1f
        return when {
            t < 1f / 6f -> p + (q - p) * 6f * t
            t < 1f / 2f -> q
            t < 2f / 3f -> p + (q - p) * (2f / 3f - t) * 6f
            else -> p
        }
    }
    return Color(hueToRgb(h + 1f / 3f), hueToRgb(h), hueToRgb(h - 1f / 3f))
}
