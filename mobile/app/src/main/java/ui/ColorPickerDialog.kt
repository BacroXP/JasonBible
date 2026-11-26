@file:OptIn(
    androidx.compose.ui.ExperimentalComposeUiApi::class
)

package ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import data.SoundEvent
import data.SoundManager



// Rainbow hues for the hue slider, from red around back to red.
// (The hex / HSV conversion helpers live in ColorUtils.kt.)
private val hueStripStops: Array<Pair<Float, Color>> = arrayOf(
    0f to Color.Red,
    0.16f to Color.Yellow,
    0.33f to Color.Green,
    0.5f to Color.Cyan,
    0.66f to Color.Blue,
    0.83f to Color.Magenta,
    1f to Color.Red
)


/**
 * The shared color picker used by the Bible verse marker panel and the
 * editor toolbar's Highlight group. A square Saturation/Value field plus
 * a hue slider, a live preview, preset swatches and a hex color field —
 * so the user can either drag to a color or type one precisely.
 *
 * [initialHex] seeds the dialog (the current marker color, or null for a
 * fresh pick); [onPick] is called with the chosen `#RRGGBB` when the
 * user confirms.
 */
@Composable
internal fun ColorPickerDialog(
    initialHex: String?,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit
) {
    // Working color as HSV (hue 0..360, sat/value 0..1) so the SV field
    // and hue slider both edit it directly.
    var hue by remember(initialHex) {
        mutableStateOf(
            if (initialHex != null) {
                argbToHsv(colorFromHexInternal(initialHex).toArgb()).first
            } else {
                50f
            }
        )
    }
    var sat by remember(initialHex) {
        mutableStateOf(
            if (initialHex != null) {
                argbToHsv(colorFromHexInternal(initialHex).toArgb()).second
            } else {
                0.6f
            }
        )
    }
    var value by remember(initialHex) {
        mutableStateOf(
            if (initialHex != null) {
                argbToHsv(colorFromHexInternal(initialHex).toArgb()).third
            } else {
                0.9f
            }
        )
    }
    val currentColor = remember(hue, sat, value) {
        hsvToColorInternal(hue, sat, value)
    }
    // The hex field text; typing a valid hex overrides the SV/hue pick.
    var hexText by remember(initialHex) {
        mutableStateOf(initialHex?.uppercase() ?: colorToHexInternal(currentColor))
    }

    fun applyHex(hex: String) {
        if (!isValidMarkerHex(hex)) return
        val c = colorFromHexInternal(hex)
        val (h, s, v) = argbToHsv(c.toArgb())
        hue = h
        sat = s
        value = v
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pick a marker color") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // Saturation × Value field (fixed size so the thumb offset
                // math stays in plain dp). The hue anchors the top-right
                // corner; white fades in from the left and black from the
                // bottom (two stacked gradients give the classic square).
                val fieldW = 260.dp
                val fieldH = 150.dp
                Box(
                    modifier = Modifier
                        .size(fieldW, fieldH)
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            Brush.linearGradient(
                                colors = listOf(
                                    Color.White,
                                    hsvToColorInternal(hue, 1f, 1f)
                                ),
                                start = Offset.Zero,
                                end = Offset(
                                    with(LocalDensity.current) { fieldW.toPx() },
                                    0f
                                )
                            )
                        )
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    Color.Black
                                )
                            )
                        )
                        .pointerInput(hue) {
                            detectDragGestures { change, _ ->
                                change.consume()
                                sat = (change.position.x / size.width)
                                    .coerceIn(0f, 1f)
                                value = 1f - (change.position.y / size.height)
                                    .coerceIn(0f, 1f)
                            }
                        }
                        .pointerInput(hue) {
                            detectTapGestures { pos ->
                                sat = (pos.x / size.width).coerceIn(0f, 1f)
                                value = 1f - (pos.y / size.height).coerceIn(0f, 1f)
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    // Thumb: white ring around the picked position.
                    Box(
                        modifier = Modifier
                            .offset(x = (sat * 260 - 9).dp, y = ((1f - value) * 150 - 9).dp)
                            .size(18.dp)
                            .border(2.dp, Color.White, CircleShape)
                            .background(Color.Black.copy(alpha = 0.25f), CircleShape)
                    )
                }

                // Hue slider (same fixed width as the SV field).
                Box(
                    modifier = Modifier
                        .size(width = fieldW, height = 18.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(
                            Brush.horizontalGradient(
                                *hueStripStops
                            )
                        )
                        .pointerInput(Unit) {
                            detectDragGestures { change, _ ->
                                change.consume()
                                hue = (change.position.x / size.width)
                                    .coerceIn(0f, 1f) * 360f
                            }
                        }
                        .pointerInput(Unit) {
                            detectTapGestures { pos ->
                                hue = (pos.x / size.width).coerceIn(0f, 1f) * 360f
                            }
                        },
                    contentAlignment = Alignment.CenterStart
                ) {
                    Box(
                        modifier = Modifier
                            .offset(x = ((hue / 360f) * 260 - 7).dp)
                            .size(14.dp)
                            .border(2.dp, Color.White, CircleShape)
                            .background(Color.Black.copy(alpha = 0.25f), CircleShape)
                    )
                }

                // Hex color field + live preview swatch.
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .border(
                                1.dp,
                                MaterialTheme.colorScheme.outlineVariant,
                                RoundedCornerShape(8.dp)
                            )
                            .background(currentColor, RoundedCornerShape(8.dp))
                    )
                    HexColorField(
                        value = hexText,
                        onValueChange = { raw ->
                            hexText = sanitizeHexInput(raw)
                            if (isValidMarkerHex(hexText)) {
                                applyHex(hexText)
                            }
                        },
                        onCommit = { applyHex(hexText) },
                        modifier = Modifier.weight(1f)
                    )
                }

                // Preset swatches (the ribbon / verse panel dot hues).
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ColorPickerSwatches.forEach { swatch ->
                        val selected = colorToHexInternal(currentColor)
                            .equals(colorToHexInternal(swatch), ignoreCase = true)
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .border(
                                    width = if (selected) 2.dp else 1.dp,
                                    color = if (selected) {
                                        MaterialTheme.colorScheme.onSurface
                                    } else {
                                        MaterialTheme.colorScheme.outlineVariant
                                    },
                                    shape = CircleShape
                                )
                                .background(swatch, CircleShape)
                                .clickable {
                                    SoundManager.play(SoundEvent.Click)
                                    hexText = colorToHexInternal(swatch)
                                    applyHex(hexText)
                                }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    SoundManager.play(SoundEvent.Click)
                    onPick(colorToHexInternal(currentColor))
                }
            ) {
                Text("OK")
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    SoundManager.play(SoundEvent.Click)
                    onDismiss()
                }
            ) {
                Text("Cancel")
            }
        }
    )
}


/** The preset marker swatches offered by the picker (the ribbon / verse
 *  panel color dots share the same hues). */
internal val ColorPickerSwatches: List<Color> = listOf(
    Color(0xFFFFD54F), // yellow
    Color(0xFFFFB300), // amber
    Color(0xFFFF8A65), // orange
    Color(0xFFE57373), // red
    Color(0xFFBA68C8), // purple
    Color(0xFF64B5F6), // blue
    Color(0xFF4DD0E1), // cyan
    Color(0xFF81C784), // green
    Color(0xFFA1887F), // brown
    Color(0xFFB0BEC5), // gray
    Color(0xFF90A4AE), // blue-gray
    Color(0xFF78909C)  // dark gray-blue
)


/**
 * A hex color field: a small single-line input that accepts `#RRGGBB`
 * (auto-`#`-prefixed, non-hex characters dropped, uppercased). The
 * border turns red while the text is not a valid color, and [onCommit]
 * fires on Enter so the caller can snap the picker to a typed color.
 */
@Composable
internal fun HexColorField(
    value: String,
    onValueChange: (String) -> Unit,
    onCommit: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val borderColor = if (isValidMarkerHex(value)) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.error
    }
    Box(
        modifier = modifier
            .background(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                RoundedCornerShape(8.dp)
            )
            .border(1.dp, borderColor, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            textStyle = TextStyle(
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 14.sp,
                fontFamily = FontFamily.Monospace
            ),
            singleLine = true,
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            modifier = Modifier
                .fillMaxWidth()
                .onPreviewKeyEvent { event ->
                    if (event.key == Key.Enter) {
                        onCommit()
                        true
                    } else {
                        false
                    }
                }
        )
    }
}


/**
 * The rainbow "custom color" trigger used in the verse marker panel and
 * the editor toolbar: a small circle painted with a conic rainbow sweep,
 * opening the color picker dialog on click.
 */
@Composable
internal fun RainbowDot(
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(
                Brush.sweepGradient(
                    listOf(
                        Color.Red,
                        Color.Yellow,
                        Color.Green,
                        Color.Cyan,
                        Color.Blue,
                        Color.Magenta,
                        Color.Red
                    )
                )
            )
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
            .clickable {
                SoundManager.play(SoundEvent.Click)
                onClick()
            }
    )
}
