package ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString


/**
 * Android clipboard facade shaped like the desktop Compose API the shared
 * UI was originally written against. The desktop code used
 * `LocalClipboard.current` + `setClipEntry(plainTextClipEntry(text))`;
 * on Android the same plain-text operations are `ClipboardManager.setText`
 * / `getText`, so the call sites stay one-liners.
 */
class AppClipboard(private val manager: ClipboardManager) {

    fun setText(text: String) {
        manager.setText(AnnotatedString(text))
    }

    fun getText(): String? = manager.getText()?.text
}


/** The clipboard for this composition (Android `ClipboardManager`). */
@Composable
fun rememberAppClipboard(): AppClipboard {
    val manager = LocalClipboardManager.current
    return remember(manager) { AppClipboard(manager) }
}
