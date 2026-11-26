package ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import platform.AppPlatform
import java.nio.file.Path


/**
 * "Open folder" equivalent on Android: the notes live in the app's private
 * storage, which no file manager can browse, so the path is shown instead.
 */
fun openFolder(path: Path) {
    AppPlatform.toast("Notes are stored in the app: $path")
}


/**
 * In-app folder chooser. The desktop build used Swing's JFileChooser to
 * point the notes library at ANY folder; Android apps cannot hand a raw
 * filesystem path from outside their sandbox to java.nio, so the chooser
 * browses the app's own storage root (the notes folder and any subfolders
 * or new folders created here). Everything else behaves the same:
 * picking a folder migrates the notes into it.
 */
@Composable
internal fun NotesFolderPickerDialog(
    initial: Path,
    onDismiss: () -> Unit,
    onPicked: (Path) -> Unit
) {
    val root = remember {
        AppPlatform.filesDir.toPath().toAbsolutePath().normalize()
    }
    var current by remember {
        mutableStateOf(
            initial.toAbsolutePath().normalize()
                .takeIf { it.startsWith(root) } ?: root
        )
    }
    var newFolder by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose the notes folder") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = current.toString(),
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                HorizontalDivider()

                val parent = current.parent
                if (parent != null && parent.startsWith(root)) {
                    Text(
                        text = "\\u2039 Up",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { current = parent }
                            .padding(horizontal = 6.dp, vertical = 4.dp)
                    )
                }

                val folders = remember(current) {
                    current.toFile()
                        .listFiles { file -> file.isDirectory }
                        ?.sortedBy { it.name.lowercase() }
                        .orEmpty()
                }
                if (folders.isEmpty()) {
                    Text(
                        text = "No subfolders here.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 220.dp)) {
                        items(folders) { folder ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { current = folder.toPath() }
                                    .padding(horizontal = 6.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = folder.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    OutlinedTextField(
                        value = newFolder,
                        onValueChange = { newFolder = it },
                        label = { Text("New folder") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(
                        enabled = newFolder.isNotBlank(),
                        onClick = {
                            val created = current.resolve(
                                newFolder.trim().replace(Regex("[/\\\\]"), "-")
                            )
                            if (runCatching { created.toFile().mkdirs() }.getOrDefault(false)) {
                                current = created
                                newFolder = ""
                            }
                        }
                    ) { Text("Create") }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onPicked(current) }) { Text("Use this folder") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
