package ui

import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import data.SoundEvent
import data.SoundManager
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds


/**
 * Compose-layer helpers that wire the [SoundManager] into UI events.
 *
 *   - [soundOnClick] — drop-in lambda wrapper. Plays [SoundEvent.Click]
 *     then invokes the original action. Used everywhere we'd write
 *     `Button(onClick = { … })` or `Modifier.clickable { … }`.
 *   - [rememberSoundHover] — Modifier extension. Plays [SoundEvent.Hover]
 *     once per genuine hover-enter, ignoring rapid edge crossings via a
 *     short `delay`. Returns the underlying [Modifier.hoverable] chain so
 *     callers can compose it with `clickable`, `fillMaxWidth`, etc.
 *   - [PlayOpenCloseSound] — composable that fires [SoundEvent.Open] /
 *     [SoundEvent.Close] once per *stable* transition. Use alongside
 *     any [androidx.compose.animation.AnimatedVisibility].
 */


/**
 * Wraps [action] so the wrapped lambda first plays a click sound and
 * then invokes the original. Returns a plain `() -> Unit` so it slots
 * in wherever Kotlin / Compose expect a callback.
 */
fun soundOnClick(action: () -> Unit): () -> Unit = {
    SoundManager.play(SoundEvent.Click)
    action()
}


/**
 * Same as [soundOnClick] but for `(T) -> Unit` callbacks — useful when
 * the click target also carries a payload (e.g. an item index).
 */
fun <T> soundOnClick(action: (T) -> Unit): (T) -> Unit = { value ->
    SoundManager.play(SoundEvent.Click)
    action(value)
}


/**
 * Returns a Modifier that:
 *   - tracks hover state via its own [MutableInteractionSource],
 *   - plays [SoundEvent.Hover] once per genuine hover-enter (cancelling
 *     the sound when the cursor exits within 60 ms so passing the cursor
 *     over a control without "landing" on it doesn't chirp).
 *   - exposes a normal `Modifier.hoverable(source)` so it can be chained
 *     into any Modifier chain (`fillMaxWidth().then(rememberSoundHover())`
 *     or `rememberSoundHover().clickable { … }`).
 */
@Composable
fun rememberSoundHover(): Modifier = Modifier.composed {
    val source = remember { MutableInteractionSource() }
    val isHovered by source.collectIsHoveredAsState()
    LaunchedEffect(isHovered) {
        if (isHovered) {
            // Debounce: if the cursor leaves again within 60 ms we cancel
            // the sound via LaunchedEffect re-keying. This keeps rapid
            // edge crossings from spamming the audio mixer.
            delay(60.milliseconds)
            SoundManager.play(SoundEvent.Hover)
        }
    }
    Modifier.hoverable(source)
}


/**
 * Variant of [rememberSoundHover] that reuses an existing interaction
 * source (e.g. the [MutableInteractionSource] that already drives a
 * sidebar's hover-to-open behaviour). Useful when the surface has two
 * reasons to watch hover state — avoids creating a redundant second
 * pointer pipeline.
 */
@Composable
fun Modifier.soundHoverOn(source: MutableInteractionSource): Modifier {
    val isHovered by source.collectIsHoveredAsState()
    LaunchedEffect(source, isHovered) {
        if (isHovered) {
            delay(60.milliseconds)
            SoundManager.play(SoundEvent.Hover)
        }
    }
    return this
}


/**
 * Fires [SoundEvent.Open] when [visible] flips false→true, and
 * [SoundEvent.Close] when it flips true→false. The 80 ms debounce
 * coalesces rapid chatter (sidebar hover wavering, hover-then-click on
 * trig surfaces) into a single Open or Close per actual transition.
 *
 * Place this composable directly alongside any
 * [androidx.compose.animation.AnimatedVisibility] whose visible state
 * should be audibly reflected.
 */
@Composable
fun PlayOpenCloseSound(visible: Boolean) {
    // Two-line memo so we know which direction the transition went —
    // LaunchedEffect alone could fire on first composition without
    // announcing it, and on rapid toggling we'd otherwise confuse
    // open→close with close→open.
    var last by remember { mutableStateOf(visible) }
    LaunchedEffect(visible) {
        if (visible == last) return@LaunchedEffect
        delay(80.milliseconds)
        if (visible == last) return@LaunchedEffect
        SoundManager.play(if (visible) SoundEvent.Open else SoundEvent.Close)
        last = visible
    }
}
