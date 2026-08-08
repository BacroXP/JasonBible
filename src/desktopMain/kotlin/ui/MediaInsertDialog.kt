package ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import data.MediaService
import data.NotesRepository
import data.SoundEvent
import data.SoundManager
import java.awt.FileDialog
import java.awt.Frame


/**
 * "Insert media reference" picker. The user chooses a service (YouTube,
 * Vimeo, Spotify, SoundCloud or a generic web link), pastes an ID or URL,
 * and confirms with Insert or the dialog button. Inserts
 * `@service:content ` at the caret — the editor renders it as a chip,
 * exactly like the Bible-reference picker inserts `$Book$C$V `.
 */
@Composable
internal fun MediaInsertDialog(
    onDismiss: () -> Unit,
    onInsert: (String) -> Unit,
    onOpenGlobalSearch: () -> Unit = {}
) {
    var service by remember { mutableStateOf(MediaService.YOUTUBE) }
    var content by remember { mutableStateOf("") }
    val trimmed = content.trim()
    val resolvedUrl = if (trimmed.isEmpty()) null else service.buildUrl(trimmed)
    // Local-file service: instead of typing an ID, the user picks a file
    // with a chooser; it is imported into the notes media folder and its
    // `@file:media/…` reference is inserted.
    val isFile = service == MediaService.FILE
    var fileRef by remember { mutableStateOf<String?>(null) }
    val canInsert = if (isFile) fileRef != null else resolvedUrl != null
    val insertText = if (isFile) {
        fileRef?.let { "@file:$it " }.orEmpty()
    } else if (canInsert) {
        "@${service.key}:$trimmed "
    } else {
        ""
    }

    // Ctrl+F while this dialog (a separate window) has focus: dismiss it
    // and open the global search — the root handler can't see it.
    val dialogKeyHandler = globalSearchDialogKeyHandler(onDismiss, onOpenGlobalSearch)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Insert media reference") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.onPreviewKeyEvent(dialogKeyHandler)
            ) {
                // Service selector (Word-style segmented chips).
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    MediaService.entries.forEach { s ->
                        val isSelected = s == service
                        // One muted color per chip, like the main menu's
                        // single-color icons — the accent only for the
                        // selected service so it reads without shouting.
                        val chipColor = if (isSelected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = if (isSelected) {
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                            },
                            modifier = Modifier
                                .clickable {
                                    SoundManager.play(SoundEvent.Click)
                                    service = s
                                }
                                .padding(horizontal = 10.dp, vertical = 5.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = s.icon,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = chipColor
                                )
                                Text(
                                    text = s.label,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = chipColor,
                                    modifier = Modifier.padding(start = 5.dp)
                                )
                            }
                        }
                    }
                }

                if (isFile) {
                    // Local file picker: choose an image / video / audio
                    // file; it is copied into the app's notes media folder
                    // and embedded in the note.
                    Button(
                        onClick = {
                            SoundManager.play(SoundEvent.Click)
                            val dialog = FileDialog(
                                null as Frame?,
                                "Choose an image, video or audio file",
                                FileDialog.LOAD
                            )
                            dialog.isVisible = true
                            val chosen = dialog.files.firstOrNull()
                            if (chosen != null) {
                                fileRef = NotesRepository.importMediaFile(chosen.toPath())
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (fileRef == null) "Choose file\u2026" else "Choose another file\u2026")
                    }
                    Text(
                        text = if (fileRef != null) {
                            "Will insert: @file:$fileRef"
                        } else {
                            "Images, videos and audio files are copied into your notes folder " +
                                "and displayed in the note (no title or artist metadata)."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (fileRef != null) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                } else {
                    OutlinedTextField(
                        value = content,
                        onValueChange = { content = it },
                        label = { Text(mediaLabelFor(service)) },
                        placeholder = { Text(mediaPlaceholderFor(service)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = mediaHelpFor(service),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = if (resolvedUrl != null) {
                            "Opens: $resolvedUrl"
                        } else {
                            "Enter a valid ${service.label} ID or URL."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (resolvedUrl != null) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = canInsert,
                modifier = Modifier.onPreviewKeyEvent(dialogKeyHandler),
                onClick = {
                    SoundManager.play(SoundEvent.Click)
                    onInsert(insertText)
                }
            ) {
                Text("Insert")
            }
        },
        dismissButton = {
            TextButton(
                modifier = Modifier.onPreviewKeyEvent(dialogKeyHandler),
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


private fun mediaLabelFor(service: MediaService) = when (service) {
    MediaService.YOUTUBE -> "Video ID or URL"
    MediaService.VIMEO -> "Video ID or URL"
    MediaService.SPOTIFY -> "Track / album / playlist ID or URL"
    MediaService.SOUNDCLOUD -> "Track URL"
    MediaService.LINK -> "Web URL"
    MediaService.FILE -> "Local file"
}

private fun mediaPlaceholderFor(service: MediaService) = when (service) {
    MediaService.YOUTUBE -> "dQw4w9WgXcQ or https://youtu.be/\u2026"
    MediaService.VIMEO -> "123456789 or https://vimeo.com/\u2026"
    MediaService.SPOTIFY -> "track:4cOdonKdQq7vF2eXyQfPqA or https://open.spotify.com/\u2026"
    MediaService.SOUNDCLOUD -> "https://soundcloud.com/artist/track"
    MediaService.LINK -> "https://example.com/\u2026"
    MediaService.FILE -> "Choose an image, video or audio file"
}

private fun mediaHelpFor(service: MediaService) = when (service) {
    MediaService.YOUTUBE ->
        "Paste a YouTube URL or an 11-character video ID (optionally with ?t=\u2026 for a timestamp)."
    MediaService.VIMEO ->
        "Paste a Vimeo URL or the numeric video ID."
    MediaService.SPOTIFY ->
        "Prefix the 22-character ID with track:, album:, playlist: or episode: \u2014 or paste a Spotify URL."
    MediaService.SOUNDCLOUD ->
        "SoundCloud links use the full track URL."
    MediaService.LINK ->
        "Any http(s) web address; the chip shows its domain."
    MediaService.FILE ->
        "Images, videos and audio files are copied into your notes folder and displayed in the note."
}
