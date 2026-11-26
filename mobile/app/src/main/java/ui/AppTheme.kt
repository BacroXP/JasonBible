package ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import data.DEFAULT_ACCENT_ARGB
import kotlin.math.max
import kotlin.math.min


// ---------------------------------------------------------------------------
// App color styles
//
// The app supports four selectable color styles (Settings → Appearance).
// Every style is built from the Pages palette, so the navy + gold identity
// survives the choice:
//   NORMAL     — the Pages palette exactly as designed.
//   SATURATED  — the same palette with the accent family pushed to high
//                chroma and the panels tinted a deeper blue.
//   GRAY       — the palette stripped of saturation, a monochrome
//                paper / night look.
//   CUSTOM     — the Pages surfaces with the accent family re-hued from the
//                user-picked seed color (the swatch row in Settings).
//
// A style is applied by transforming the base scheme's roles in HSL (hue /
// saturation held, lightness kept), never by deriving a whole scheme from
// scratch: that keeps the navy panels, cream text and the full container
// family intact, so no style can reintroduce the grey-panel look.
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
 * The Pages-site palette: deep navy, leather gold and warm cream. The whole
 * desktop app uses this scheme so every screen shares the landing page's
 * visual language; dark mode uses the site's near-black navy, light mode
 * shifts to the cream paper variant.
 */
private val PagesGold = Color(0xFFF2B65A)
private val PagesGoldSoft = Color(0xFFFFD79A)
private val PagesInk = Color(0xFF070A10)
private val PagesNavy = Color(0xFF0B1220)
private val PagesPanel = Color(0xFF0D1420)
private val PagesPanel2 = Color(0xFF111A28)
private val PagesCream = Color(0xFFF5EFE3)
private val PagesText = Color(0xFFDDD6C8)
private val PagesMuted = Color(0xFFB3AB9C)
private val PagesLine = Color(0xFF2A3547)

internal fun pagesScheme(dark: Boolean): ColorScheme = if (dark) {
    darkColorScheme(
        primary = PagesGold,
        onPrimary = PagesNavy,
        primaryContainer = Color(0xFF3A2B13),
        onPrimaryContainer = PagesGoldSoft,
        secondary = PagesGoldSoft,
        onSecondary = PagesNavy,
        secondaryContainer = PagesActiveNavy,
        onSecondaryContainer = PagesGoldSoft,
        tertiary = PagesGold,
        onTertiary = PagesNavy,
        tertiaryContainer = PagesActiveNavy,
        onTertiaryContainer = PagesGoldSoft,
        background = PagesInk,
        onBackground = PagesCream,
        surface = PagesPanel,
        onSurface = PagesCream,
        surfaceVariant = PagesPanel2,
        onSurfaceVariant = PagesMuted,
        // Tint elevated surfaces with navy rather than gold: gold at a low
        // elevation alpha over navy averages to a neutral grey-brown, which
        // is what made every tonalElevation Surface look grey.
        surfaceTint = Color(0xFF1B2B47),
        // The container family must be filled in by hand: leaving these at
        // the M3 baseline is what made every default Card / menu / dialog a
        // grey-purple panel sitting inside the navy theme.
        surfaceDim = Color(0xFF070C14),
        surfaceBright = Color(0xFF1B2A44),
        surfaceContainerLowest = PagesInk,
        surfaceContainerLow = PagesNavy,
        surfaceContainer = Color(0xFF0E1828),
        surfaceContainerHigh = Color(0xFF111E31),
        surfaceContainerHighest = Color(0xFF14243A),
        inversePrimary = PagesGoldSoft,
        outline = PagesLine,
        outlineVariant = Color(0xFF1A2332),
        inverseSurface = PagesCream,
        inverseOnSurface = PagesNavy,
        error = Color(0xFFFFB4AB),
        onError = Color(0xFF690005),
        errorContainer = Color(0xFF93000A),
        onErrorContainer = Color(0xFFFFDAD6),
        scrim = Color(0xFF000000)
    )
} else {
    lightColorScheme(
        primary = Color(0xFF8A5A12),
        onPrimary = Color.White,
        primaryContainer = PagesGoldSoft,
        onPrimaryContainer = Color(0xFF2B1A00),
        secondary = Color(0xFF8A5A12),
        onSecondary = Color.White,
        secondaryContainer = PagesActivePaper,
        onSecondaryContainer = Color(0xFF2B1A00),
        tertiary = Color(0xFF8A5A12),
        onTertiary = Color.White,
        tertiaryContainer = PagesActivePaper,
        onTertiaryContainer = Color(0xFF2B1A00),
        background = PagesCream,
        onBackground = PagesNavy,
        surface = Color(0xFFFBF7EF),
        onSurface = PagesNavy,
        surfaceVariant = Color(0xFFEDE4D4),
        onSurfaceVariant = Color(0xFF5D5547),
        surfaceDim = Color(0xFFE8DECB),
        surfaceBright = Color(0xFFFFFBF3),
        surfaceContainerLowest = Color(0xFFFFFDF8),
        surfaceContainerLow = Color(0xFFFBF6EC),
        surfaceContainer = Color(0xFFF6EFE2),
        surfaceContainerHigh = Color(0xFFF0E7D6),
        surfaceContainerHighest = Color(0xFFEADFCB),
        surfaceTint = Color(0xFFE3D3B4),
        inversePrimary = PagesGold,
        outline = Color(0xFFC9BFAE),
        outlineVariant = Color(0xFFDCCFBA),
        inverseSurface = PagesNavy,
        inverseOnSurface = PagesCream,
        errorContainer = Color(0xFFFFDAD6),
        onErrorContainer = Color(0xFF410002),
        scrim = Color(0xFF000000)
    )
}

/**
 * The plate behind an ACTIVE chrome item: a selected section in a rail, an
 * armed jump chip, the lit icon of a toggle button. Gold at a low alpha over
 * the navy mixes into a muddy grey, so the active state is drawn as a solid
 * deep-blue plate instead and keeps the gold for its border and label.
 */
private val PagesActiveNavy = Color(0xFF16294D)
private val PagesActivePaper = Color(0xFFF6EBD6)


/**
 * Fill of an active chrome item. It comes from the scheme's
 * secondary-container role, so it follows the selected color style instead
 * of being hardcoded navy.
 */
@Composable
internal fun activeChromeFill(): Color = MaterialTheme.colorScheme.secondaryContainer


/** Site-style typography: serif display/headings, sans body copy. */
internal fun appTypography(): Typography {
    val base = Typography()
    fun serif(style: TextStyle) = style.copy(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.SemiBold
    )
    return Typography(
        displayLarge = serif(base.displayLarge),
        displayMedium = serif(base.displayMedium),
        displaySmall = serif(base.displaySmall),
        headlineLarge = serif(base.headlineLarge),
        headlineMedium = serif(base.headlineMedium),
        headlineSmall = serif(base.headlineSmall),
        titleLarge = serif(base.titleLarge),
        titleMedium = serif(base.titleMedium),
        bodyLarge = base.bodyLarge.copy(fontFamily = FontFamily.SansSerif),
        bodyMedium = base.bodyMedium.copy(fontFamily = FontFamily.SansSerif),
        bodySmall = base.bodySmall.copy(fontFamily = FontFamily.SansSerif),
        labelLarge = base.labelLarge.copy(fontFamily = FontFamily.SansSerif),
        labelMedium = base.labelMedium.copy(fontFamily = FontFamily.SansSerif),
        labelSmall = base.labelSmall.copy(fontFamily = FontFamily.SansSerif)
    )
}

/** Soft, site-like panel corners for the whole app. */
internal val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(9.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp)
)

/**
 * The scheme for one persisted style. The dark toggle picks the navy or
 * cream variant of the Pages palette; [style] then selects how its accent
 * roles (and, lightly, its panels) are colorized.
 */
internal fun appColorScheme(
    dark: Boolean,
    style: AppColorStyle,
    customAccent: Long
): ColorScheme {
    val pages = pagesScheme(dark)
    return when (style) {
        AppColorStyle.NORMAL -> pages

        // Accent roles at (near) full chroma, panels a touch bluer: the same
        // gold-on-navy, just louder.
        AppColorStyle.SATURATED -> pages
            .mapAccents { it.withSaturation(it.toHsl().second * 1.25f + 0.20f) }
            .mapSurfaces { it.withSaturation(it.toHsl().second + 0.18f) }

        // Zero chroma everywhere — including errors, so the whole window
        // reads as one material instead of grey with stray colored bits.
        AppColorStyle.GRAY -> pages.mapAllRoles { it.withSaturation(0f) }

        AppColorStyle.CUSTOM -> {
            val seed = Color(saneAccent(customAccent))
            val (hue, seedSaturation, _) = seed.toHsl()
            if (seedSaturation < 0.08f) {
                // A grey / black seed names no hue; keep the gold rather
                // than inventing an arbitrary one.
                pages
            } else {
                val accentSaturation = (seedSaturation * 0.9f).coerceIn(0.35f, 0.95f)
                pages
                    .mapAccents { it.withHue(hue).withSaturation(accentSaturation) }
                    // Panels pick up just a hint of the seed, so the custom
                    // style reads as themed rather than recolored text.
                    .mapSurfaces {
                        it.withHue(hue).withSaturation(it.toHsl().second * 0.45f + 0.06f)
                    }
            }
        }
    }
}


/** The accent roles: primary / secondary / tertiary families and their
 *  on-colors, containers and fixed variants. */
private fun ColorScheme.mapAccents(transform: (Color) -> Color): ColorScheme = copy(
    primary = transform(primary),
    onPrimary = transform(onPrimary),
    primaryContainer = transform(primaryContainer),
    onPrimaryContainer = transform(onPrimaryContainer),
    inversePrimary = transform(inversePrimary),
    secondary = transform(secondary),
    onSecondary = transform(onSecondary),
    secondaryContainer = transform(secondaryContainer),
    onSecondaryContainer = transform(onSecondaryContainer),
    tertiary = transform(tertiary),
    onTertiary = transform(onTertiary),
    tertiaryContainer = transform(tertiaryContainer),
    onTertiaryContainer = transform(onTertiaryContainer)
)


/** The neutral roles: page background, the panel / container family, the
 *  tint and the outlines. Text roles are left alone on purpose. */
private fun ColorScheme.mapSurfaces(transform: (Color) -> Color): ColorScheme = copy(
    background = transform(background),
    surface = transform(surface),
    surfaceVariant = transform(surfaceVariant),
    surfaceDim = transform(surfaceDim),
    surfaceBright = transform(surfaceBright),
    surfaceContainerLowest = transform(surfaceContainerLowest),
    surfaceContainerLow = transform(surfaceContainerLow),
    surfaceContainer = transform(surfaceContainer),
    surfaceContainerHigh = transform(surfaceContainerHigh),
    surfaceContainerHighest = transform(surfaceContainerHighest),
    surfaceTint = transform(surfaceTint),
    outline = transform(outline),
    outlineVariant = transform(outlineVariant),
    inverseSurface = transform(inverseSurface)
)


/** Every role, accents and neutrals and the text / error roles too. */
private fun ColorScheme.mapAllRoles(transform: (Color) -> Color): ColorScheme =
    mapAccents(transform).mapSurfaces(transform).copy(
        onBackground = transform(onBackground),
        onSurface = transform(onSurface),
        onSurfaceVariant = transform(onSurfaceVariant),
        inverseOnSurface = transform(inverseOnSurface),
        error = transform(error),
        onError = transform(onError),
        errorContainer = transform(errorContainer),
        onErrorContainer = transform(onErrorContainer),
        scrim = transform(scrim)
    )


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
        )
    }
}


/** The same color at [saturation] (0..1), lightness and alpha untouched. */
internal fun Color.withSaturation(saturation: Float): Color {
    val (h, _, l) = toHsl()
    return Color.fromHsl(h, saturation.coerceIn(0f, 1f), l).copy(alpha = alpha)
}


/** The same color on [hue] (0..1), saturation and lightness untouched. */
internal fun Color.withHue(hue: Float): Color {
    val (_, s, l) = toHsl()
    val wrapped = ((hue % 1f) + 1f) % 1f
    return Color.fromHsl(wrapped, s, l).copy(alpha = alpha)
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
