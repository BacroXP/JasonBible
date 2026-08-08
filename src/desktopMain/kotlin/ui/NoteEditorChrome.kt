@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class
)

package ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import data.SoundEvent
import data.SoundManager
import kotlinx.coroutines.delay
import model.ParsedNote
import kotlin.math.roundToInt



@Composable
internal fun EditorHeader(
    note: ParsedNote,
    saving: Boolean,
    onSave: () -> Unit,
    onExport: () -> Unit,
    onNew: (() -> Unit)?,
    onDelete: (() -> Unit)?,
    onBack: (() -> Unit)?
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = note.title.ifBlank { note.fileName },
                style = MaterialTheme.typography.titleLarge
            )
            Text(
                text = note.fileName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (onBack != null) {
                ToolbarTip(label = "Back to previous screen", shortcut = "Ctrl+W") {
                    Button(onClick = onBack) {
                        Icon(RibbonIcons.Back, contentDescription = null, modifier = Modifier.size(16.dp))
                        Text("Back", modifier = Modifier.padding(start = 6.dp))
                    }
                }
            }
            ToolbarTip(label = "Export note as PDF", shortcut = "Ctrl+P") {
                Button(onClick = onExport) {
                    Icon(RibbonIcons.Export, contentDescription = null, modifier = Modifier.size(16.dp))
                    Text("Export", modifier = Modifier.padding(start = 6.dp))
                }
            }
            ToolbarTip(label = "Save note", shortcut = "Ctrl+S") {
                Button(onClick = onSave) {
                    Icon(RibbonIcons.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                    Text(if (saving) "Saving..." else "Save", modifier = Modifier.padding(start = 6.dp))
                }
            }
            if (onNew != null) {
                ToolbarTip(label = "Create a new note") {
                    Button(onClick = onNew) {
                        Icon(RibbonIcons.New, contentDescription = null, modifier = Modifier.size(16.dp))
                        Text("New", modifier = Modifier.padding(start = 6.dp))
                    }
                }
            }
            if (onDelete != null) {
                ToolbarTip(label = "Delete note") {
                    Button(
                        onClick = onDelete,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError
                        )
                    ) {
                        Icon(RibbonIcons.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                        Text("Delete", modifier = Modifier.padding(start = 6.dp))
                    }
                }
            }
        }
    }
}


@Composable
internal fun EditorFooter(
    value: TextFieldValue,
    canUndo: Boolean,
    canRedo: Boolean,
    fontScale: Float,
    onFontScaleChange: (Float) -> Unit,
    onFontScaleCommit: () -> Unit,
    onEditorValueChange: (TextFieldValue) -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onToggleOrientation: () -> Unit
) {
    val onSurface = MaterialTheme.colorScheme.onSurfaceVariant
    val stats = remember(value.text) { computeTextStats(value.text) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = if (stats.words == 0) {
                "${stats.chars} chars  \u00B7  ${stats.lines} lines"
            } else {
                "${stats.words} \u00B7 ${stats.chars} chars" +
                    "\u00B7 ${stats.charsNoSpaces} no-space" +
                    "\u00B7 ${stats.lines} ln" +
                    "\u00B7 ~${stats.readingMinutes} min"
            },
            style = MaterialTheme.typography.bodySmall,
            color = onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.then(if (canUndo) Modifier.clickable { onUndo() } else Modifier)
            ) {
                Icon(
                    RibbonIcons.Undo,
                    contentDescription = null,
                    tint = if (canUndo) MaterialTheme.colorScheme.primary else onSurface.copy(alpha = 0.4f),
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    text = "Undo",
                    color = if (canUndo) MaterialTheme.colorScheme.primary else onSurface.copy(alpha = 0.4f),
                    style = MaterialTheme.typography.labelLarge
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.then(if (canRedo) Modifier.clickable { onRedo() } else Modifier)
            ) {
                Icon(
                    RibbonIcons.Redo,
                    contentDescription = null,
                    tint = if (canRedo) MaterialTheme.colorScheme.primary else onSurface.copy(alpha = 0.4f),
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    text = "Redo",
                    color = if (canRedo) MaterialTheme.colorScheme.primary else onSurface.copy(alpha = 0.4f),
                    style = MaterialTheme.typography.labelLarge
                )
            }
            Text(
                text = "LTR\u21C4RTL",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.clickable { onToggleOrientation() }
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable { onEditorValueChange(TextFieldValue("")) }
            ) {
                Icon(
                    RibbonIcons.Delete,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    text = "Clear",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelLarge
                )
            }

            // Word-style status-bar zoom: − slider +, percentage label.
            // Live-preview on drag, persisted once on release. The cluster
            // uses tight 4dp spacing and the slider shrinks first (48..90dp)
            // so the footer never clips the zoom on narrow SPLIT panes.
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "\u2212",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.clickable {
                        onFontScaleChange(fontScale - 0.1f)
                        onFontScaleCommit()
                    }
                )
                Slider(
                    value = fontScale,
                    onValueChange = onFontScaleChange,
                    onValueChangeFinished = onFontScaleCommit,
                    valueRange = ZOOM_MIN..ZOOM_MAX,
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .widthIn(min = 48.dp, max = 90.dp)
                        .height(24.dp)
                )
                Text(
                    text = "+",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.clickable {
                        onFontScaleChange(fontScale + 0.1f)
                        onFontScaleCommit()
                    }
                )
                Text(
                    text = "${(fontScale * 100).roundToInt()}%",
                    style = MaterialTheme.typography.labelMedium,
                    color = onSurface,
                    textAlign = TextAlign.End,
                    modifier = Modifier.width(38.dp)
                )
            }
        }
    }
}


@Composable
internal fun NoteFileCard(
    note: ParsedNote,
    selected: Boolean,
    onClick: () -> Unit,
    onDelete: (() -> Unit)? = null,
    /** Move the note to another folder (revealed on hover). */
    onMove: (() -> Unit)? = null
) {
    val hoverSource = remember { MutableInteractionSource() }
    val isHovered by hoverSource.collectIsHoveredAsState()
    androidx.compose.runtime.LaunchedEffect(isHovered) {
        if (isHovered) {
            kotlinx.coroutines.delay(60)
            data.SoundManager.play(data.SoundEvent.Hover)
        }
    }

    Card(
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .hoverable(hoverSource)
            .clickable {
                data.SoundManager.play(data.SoundEvent.Click)
                onClick()
            }
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = note.title.ifBlank { note.fileName },
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    modifier = Modifier.weight(1f)
                )
                // Quick move-to-folder affordance, revealed on hover.
                if (isHovered && onMove != null) {
                    Text(
                        text = "\u21F1",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier
                            .padding(start = 6.dp)
                            .clickable {
                                data.SoundManager.play(data.SoundEvent.Click)
                                onMove()
                            }
                    )
                }
                // Quick delete affordance, revealed on hover. Routes into
                // the same confirmation dialog as the EditorHeader button.
                if (isHovered && onDelete != null) {
                    Text(
                        text = "\u00D7",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier
                            .padding(start = 6.dp)
                            .clickable {
                                data.SoundManager.play(data.SoundEvent.Click)
                                onDelete()
                            }
                    )
                }
            }
            Text(
                text = note.fileName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
    }
}

