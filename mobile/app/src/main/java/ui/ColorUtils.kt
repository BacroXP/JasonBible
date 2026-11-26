package ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb


// ---------------------------------------------------------------------------
// Shared marker-color helpers
//
// Marker colors are stored as hex strings everywhere: `#RRGGBB` in the
// note editor's `"quote"[#hex]` markup and in the Bible verse marker
// settings, with `#AARRGGBB` tolerated when loading older ARGB values.
// These helpers are the single conversion point between hex and Compose
// [Color], used by the verse marker panel, the editor toolbar and the
// color picker dialog. The HSV converters power the picker's SV field
// and hue slider (and the tests that round-trip them).
// ---------------------------------------------------------------------------

/** Parse a `#RGB` / `#RRGGBB` / `#AARRGGBB` hex string into a Color.
 *  Invalid input falls back to [fallback] (default: a neutral amber). */
internal fun colorFromHexInternal(hex: String, fallback: Color = Color(0xFFFFB300)): Color {
    val cleaned = hex.trim().removePrefix("#")
    if (cleaned.length !in listOf(3, 4, 6, 8)) return fallback
    val value = cleaned.toLongOrNull(16) ?: return fallback
    return when (cleaned.length) {
        3 -> Color(
            red = ((value shr 8) and 0xF).toFloat() / 15f,
            green = ((value shr 4) and 0xF).toFloat() / 15f,
            blue = (value and 0xF).toFloat() / 15f
        )

        4 -> Color(
            alpha = ((value shr 12) and 0xF).toFloat() / 15f,
            red = ((value shr 8) and 0xF).toFloat() / 15f,
            green = ((value shr 4) and 0xF).toFloat() / 15f,
            blue = (value and 0xF).toFloat() / 15f
        )

        6 -> Color(
            red = ((value shr 16) and 0xFF).toFloat() / 255f,
            green = ((value shr 8) and 0xFF).toFloat() / 255f,
            blue = (value and 0xFF).toFloat() / 255f
        )

        else -> Color(
            alpha = ((value shr 24) and 0xFF).toFloat() / 255f,
            red = ((value shr 16) and 0xFF).toFloat() / 255f,
            green = ((value shr 8) and 0xFF).toFloat() / 255f,
            blue = (value and 0xFF).toFloat() / 255f
        )
    }
}

/** Serialize a Color to the editor's canonical `#RRGGBB` marker format
 *  (alpha dropped — highlight markers don't carry transparency). */
internal fun colorToHexInternal(color: Color): String {
    val argb = color.toArgb()
    return String.format(
        "#%02X%02X%02X",
        (argb shr 16) and 0xFF,
        (argb shr 8) and 0xFF,
        argb and 0xFF
    )
}

private val MARKER_HEX_REGEX = Regex("^#([0-9A-Fa-f]{6})$")

/** True when [hex] is a valid 6-digit `#RRGGBB` marker color (the format
 *  the note editor's `[#hex]` markup and the verse marker settings use). */
internal fun isValidMarkerHex(hex: String): Boolean =
    hex.matches(MARKER_HEX_REGEX)

/** Rejects non-hex characters while typing, keeping the `#` prefix and
 *  uppercasing for a consistent look in the color field. */
internal fun sanitizeHexInput(raw: String): String {
    if (raw.isEmpty()) return ""
    val cleaned = raw.uppercase().filter { it.isDigit() || it in 'A'..'F' }
    return if (cleaned.startsWith("#")) cleaned.take(7) else "#${cleaned.take(6)}"
}

/** (hue 0..360, saturation 0..1, value 0..1) for an opaque ARGB int. */
internal fun argbToHsv(argb: Int): Triple<Float, Float, Float> {
    val r = ((argb shr 16) and 0xFF) / 255f
    val g = ((argb shr 8) and 0xFF) / 255f
    val b = (argb and 0xFF) / 255f
    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    val d = max - min
    val v = max
    val s = if (max == 0f) 0f else d / max
    val h = when {
        d == 0f -> 0f
        max == r -> 60f * (((g - b) / d) % 6f)
        max == g -> 60f * ((b - r) / d + 2f)
        else -> 60f * ((r - g) / d + 4f)
    }
    return Triple((h + 360f) % 360f, s, v)
}

/** Build an opaque Color from (hue 0..360, sat 0..1, value 0..1). */
internal fun hsvToColorInternal(hue: Float, sat: Float, value: Float): Color {
    val h = ((hue % 360f) + 360f) % 360f
    val c = value * sat
    val x = c * (1f - kotlin.math.abs(((h / 60f) % 2f) - 1f))
    val m = value - c
    val (r, g, b) = when (h.toInt()) {
        in 0..59 -> Triple(c, x, 0f)
        in 60..119 -> Triple(x, c, 0f)
        in 120..179 -> Triple(0f, c, x)
        in 180..239 -> Triple(0f, x, c)
        in 240..299 -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    return Color(r + m, g + m, b + m)
}
