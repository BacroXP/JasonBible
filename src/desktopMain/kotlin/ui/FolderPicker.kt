package ui

import java.nio.file.Path
import javax.swing.JFileChooser


/**
 * Ask the user for a folder with the platform file chooser.
 *
 * @param initial Folder the dialog opens on (falls back to its parent).
 * @param title Dialog title.
 * @return the chosen folder as an absolute, normalised [Path], or null when
 *   the dialog was cancelled or no chooser is available.
 */
fun chooseFolder(initial: Path, title: String = "Choose folder"): Path? = runCatching {
    val start = initial.toFile().takeIf { it.isDirectory } ?: initial.parent?.toFile()
    val chooser = JFileChooser(start).apply {
        fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        dialogTitle = title
        isAcceptAllFileFilterUsed = false
        isMultiSelectionEnabled = false
    }
    if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
        chooser.selectedFile?.toPath()?.toAbsolutePath()?.normalize()
    } else {
        null
    }
}.getOrNull()
