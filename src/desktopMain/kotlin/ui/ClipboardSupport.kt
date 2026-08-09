package ui

import androidx.compose.ui.platform.ClipEntry
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable

/**
 * Desktop helpers for the non-deprecated suspend
 * [androidx.compose.ui.platform.Clipboard] API. On the JVM desktop a
 * [ClipEntry] is a thin wrapper around an AWT [Transferable], so plain text
 * is written as a [StringSelection] and read back via the string
 * [DataFlavor].
 */

/** Builds a [ClipEntry] carrying [text] as plain text. */
fun plainTextClipEntry(text: String): ClipEntry = ClipEntry(StringSelection(text))

/** Reads the plain-text payload of [this] entry, or null when it holds none. */
fun ClipEntry?.readPlainText(): String? {
    val transferable = this?.nativeClipEntry as? Transferable ?: return null
    return try {
        transferable.getTransferData(DataFlavor.stringFlavor) as? String
    } catch (_: Exception) {
        null
    }
}
