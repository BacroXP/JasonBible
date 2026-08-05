package ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Card
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp


/**
 * Shared outer wrapper for the Bible and Notes widget panes so both
 * present the same "centered ~980dp card with 24dp window-edge
 * padding" look in standalone mode, and the same "card fills its
 * half-window pane with 16dp padding" look in SPLIT mode.
 *
 * The wrapper owns ONLY:
 *   - Window-edge padding: 24.dp standalone, 16.dp SPLIT.
 *   - `contentAlignment`: Center standalone, TopStart SPLIT.
 *   - `Card` with `widthIn(max = maxWidth).fillMaxHeight().fillMaxWidth()`
 *     standalone, `fillMaxSize()` SPLIT.
 *
 * Inner Column/Row padding is left at the caller's discretion because
 * the Bible reads at 24.dp inner (matching the outer) while the editor
 * reads at 10.dp inner (so text sits a couple dp off the Card edge) —
 * those are content-shape decisions, not layout scaffolding.
 *
 * Callers can attach additional Modifiers via [modifier] — e.g.
 * `Modifier.onPreviewKeyEvent(...)` for the Bible pane's chapter
 * shortcuts. The parameter is chained AFTER `fillMaxSize` + `padding`
 * via `Modifier.then(modifier)` so it matches the original
 * `BibleScreen` ordering where the key handler sat at the outermost
 * layer of the outer Box's modifier chain.
 *
 * @param compact `true` = SPLIT (no width cap, fills the caller-supplied
 *   pane width); `false` = standalone (`maxWidth` cap applies, card
 *   centers in window).
 * @param modifier Extra modifiers appended to the outer `Box` (after
 *   `fillMaxSize` and `padding`).
 * @param maxWidth Maximum card width in standalone mode. Ignored in
 *   compact/SPLIT mode.
 * @param content The pane content. Rendered inside the centered Card.
 */
@Composable
fun MaxWidthScaffold(
    compact: Boolean,
    modifier: Modifier = Modifier,
    maxWidth: Dp = 980.dp,
    content: @Composable () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(if (compact) 16.dp else 24.dp)
            .then(modifier),
        contentAlignment = if (compact) Alignment.TopStart else Alignment.Center
    ) {
        Card(
            modifier = if (compact) {
                Modifier.fillMaxSize()
            } else {
                Modifier
                    .widthIn(max = maxWidth)
                    .fillMaxHeight()
                    .fillMaxWidth()
            }
        ) {
            content()
        }
    }
}
