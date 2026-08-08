@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class
)

package ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import data.SoundEvent
import data.SoundManager



@Composable
internal fun EditorToolbar(
    editorValue: TextFieldValue,
    canUndo: Boolean,
    canRedo: Boolean,
    autoContinueLists: Boolean,
    onEditorValueChange: (TextFieldValue) -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onToggleOrientation: () -> Unit,
    onAlignLeft: () -> Unit,
    onAlignCenter: () -> Unit,
    onAlignRight: () -> Unit,
    onToggleAutoContinue: () -> Unit,
    onCopy: () -> Unit,
    onCut: () -> Unit,
    onPaste: () -> Unit,
    onToggleFind: () -> Unit,
    onSelectAll: () -> Unit,
    onRemoveColor: () -> Unit,
    onOpenColorPicker: () -> Unit,
    onClearFormatting: () -> Unit,
    /**
     * Open the Bible-reference picker dialog pre-set to the given
     * granularity. The dialog lets the user type a book name (with
     * autocomplete) and choose chapter / verse before inserting the
     * `$Book$C$V` / `$Book$C` / `$Book` line at the caret.
     */
    onOpenReferencePicker: (ReferenceKind) -> Unit,
    /**
     * Open the media-link picker dialog (YouTube / Spotify / Vimeo /
     * SoundCloud / generic URL). Inserts a `@service:content ` chip at
     * the caret, the media counterpart to the Bible reference picker.
     */
    onOpenMediaPicker: () -> Unit,
    onInsertDate: () -> Unit
) {
    val dividerColor = MaterialTheme.colorScheme.outlineVariant
    // Cut / Copy act on the current selection and disable when the caret
    // is collapsed (no selected text to take), like Word's clipboard
    // buttons. Paste is always available.
    val canClip = !editorValue.selection.collapsed
    // Paragraph style at the caret, mirrored in the Styles dropdown.
    val currentStyle = currentBlockStyle(editorValue)
    // Paragraph alignment at the caret, mirrored in the Alignment group.
    val currentAlignment = currentLineAlignment(editorValue)
    // Which ribbon tab is expanded. Word remembers the last tab; this
    // keeps the user's choice for the lifetime of the editor screen.
    var selectedTab by remember { mutableStateOf(RibbonTab.HOME) }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        RibbonTabBar(
            selected = selectedTab,
            onSelect = { selectedTab = it }
        )
        HorizontalDivider(color = dividerColor.copy(alpha = 0.6f))

        when (selectedTab) {
            // -----------------------------------------------------------------
            // Home — History · Clipboard · Edit and Style · Highlight ·
            // Format (Word: Home > Clipboard + Font).
            // -----------------------------------------------------------------
            RibbonTab.HOME -> {
                ToolbarRow {
                    ToolbarGroup("History") {
                        ToolbarActionButton(icon = RibbonIcons.Undo, enabled = canUndo, tooltip = "Undo", shortcut = "Ctrl+Z", onClick = onUndo)
                        ToolbarActionButton(icon = RibbonIcons.Redo, enabled = canRedo, tooltip = "Redo", shortcut = "Ctrl+Shift+Z", onClick = onRedo)
                    }
                    ToolbarDivider(dividerColor)

                    ToolbarGroup("Clipboard") {
                        ToolbarActionButton(icon = RibbonIcons.Cut, enabled = canClip, tooltip = "Cut selection", shortcut = "Ctrl+X", onClick = onCut)
                        ToolbarActionButton(icon = RibbonIcons.Copy, enabled = canClip, tooltip = "Copy selection", shortcut = "Ctrl+C", onClick = onCopy)
                        ToolbarActionButton(icon = RibbonIcons.Paste, tooltip = "Paste from clipboard", shortcut = "Ctrl+V", onClick = onPaste)
                    }
                    ToolbarDivider(dividerColor)

                    ToolbarGroup("Edit") {
                        ToolbarActionButton(icon = RibbonIcons.Find, tooltip = "Find in note", shortcut = "Ctrl+F", onClick = onToggleFind)
                        ToolbarActionButton(icon = RibbonIcons.SelectAll, tooltip = "Select all text", shortcut = "Ctrl+A", onClick = onSelectAll)
                    }
                }
                HorizontalDivider(color = dividerColor)

                ToolbarRow {
                    ToolbarGroup("Style") {
                        InlineButton("B", bold = true, tooltip = "Bold", shortcut = "Ctrl+B") { onEditorValueChange(toggleWrap(editorValue, "**")) }
                        InlineButton("I", italic = true, tooltip = "Italic", shortcut = "Ctrl+I") { onEditorValueChange(toggleWrap(editorValue, "*")) }
                        InlineButton("U", underline = true, tooltip = "Underline", shortcut = "Ctrl+U") { onEditorValueChange(toggleWrap(editorValue, "__")) }
                    }
                    ToolbarDivider(dividerColor)

                    ToolbarGroup("Highlight") {
                        listOf(
                            ColorMark(Color(0xFFFFD54F), "#FFD54F", "Yellow"),
                            ColorMark(Color(0xFF64B5F6), "#64B5F6", "Blue"),
                            ColorMark(Color(0xFF81C784), "#81C784", "Green"),
                            ColorMark(Color(0xFFBA68C8), "#BA68C8", "Purple"),
                            ColorMark(Color(0xFFE57373), "#E57373", "Red")
                        ).forEach { mark ->
                            ColorDot(
                                color = mark.color,
                                tooltip = "Highlight ${mark.name}",
                                onClick = {
                                    onEditorValueChange(toggleColoredQuote(editorValue, mark.hex))
                                }
                            )
                        }
                        // Word's "No colour": strips [#hex] markers, keeps the quote.
                        NoColorDot(tooltip = "Remove highlight color", onClick = onRemoveColor)
                        // Custom color: rainbow dot opens the color picker
                        // dialog; the chosen color is applied to the
                        // selection / cursor line like any preset dot.
                        ToolbarTip(label = "Custom color…", shortcut = null) {
                            RainbowDot(
                                modifier = Modifier.size(20.dp),
                                onClick = onOpenColorPicker
                            )
                        }
                    }
                    ToolbarDivider(dividerColor)

                    ToolbarGroup("Format") {
                        ToolbarActionButton(
                            icon = RibbonIcons.ClearFormat,
                            tooltip = "Clear formatting",
                            onClick = onClearFormatting
                        )
                    }
                }
            }

            // -----------------------------------------------------------------
            // Insert — Bible references & date (Word: Insert > Text).
            // Each reference button opens the target picker pre-set to its
            // granularity (verse / chapter / book) so the user can select
            // the actual book, chapter and verse before inserting.
            // -----------------------------------------------------------------
            RibbonTab.INSERT -> {
                ToolbarRow {
                    ToolbarGroup("Insert") {
                        StyleButton(icon = RibbonIcons.Reference, accent = true, tooltip = "Insert Bible reference", shortcut = "Ctrl+K") {
                            onOpenReferencePicker(ReferenceKind.VERSE)
                        }
                        StyleButton(icon = RibbonIcons.Chapter, accent = true, tooltip = "Insert chapter reference") {
                            onOpenReferencePicker(ReferenceKind.CHAPTER)
                        }
                        StyleButton(icon = RibbonIcons.Book, accent = true, tooltip = "Insert book reference", shortcut = "Ctrl+Shift+K") {
                            onOpenReferencePicker(ReferenceKind.BOOK)
                        }
                        StyleButton(icon = RibbonIcons.Media, accent = true, tooltip = "Insert media link (YouTube, Spotify, Vimeo, SoundCloud, URL)") {
                            onOpenMediaPicker()
                        }
                        StyleButton(icon = RibbonIcons.Date, accent = true, tooltip = "Insert today's date") {
                            onInsertDate()
                        }
                    }
                }
            }

            // -----------------------------------------------------------------
            // Layout — paragraph styles, block styles & text direction
            // (Word: Layout > Paragraph).
            // -----------------------------------------------------------------
            RibbonTab.LAYOUT -> {
                ToolbarRow {
                    ToolbarGroup("Styles") {
                        StyleDropdown(
                            currentStyle = currentStyle,
                            onStyleSelected = { style ->
                                onEditorValueChange(applyBlockStyle(editorValue, style))
                            }
                        )
                    }
                    ToolbarDivider(dividerColor)

                    ToolbarGroup("Block") {
                        StyleButton(icon = RibbonIcons.Heading1, tooltip = "Heading 1") {
                            onEditorValueChange(prefixSelectedLines(editorValue, "# "))
                        }
                        StyleButton(icon = RibbonIcons.Heading2, tooltip = "Heading 2") {
                            onEditorValueChange(prefixSelectedLines(editorValue, "## "))
                        }
                        StyleButton(icon = RibbonIcons.Quote, tooltip = "Block quote") {
                            onEditorValueChange(prefixSelectedLines(editorValue, "> "))
                        }
                        ListButton(ordered = false, icon = RibbonIcons.BulletList, tooltip = "Bullet list") {
                            onEditorValueChange(prefixSelectedLines(editorValue, "- "))
                        }
                        ListButton(ordered = true, icon = RibbonIcons.NumberedList, tooltip = "Numbered list") {
                            onEditorValueChange(prefixSelectedLines(editorValue, "1. "))
                        }
                        ToolbarActionButton(
                            icon = RibbonIcons.AutoList,
                            label = if (autoContinueLists) "ON" else "OFF",
                            accent = autoContinueLists,
                            tooltip = "Auto-continue lists",
                            onClick = onToggleAutoContinue
                        )
                    }
                    ToolbarDivider(dividerColor)

                    ToolbarGroup("Alignment") {
                        ToolbarActionButton(
                            icon = RibbonIcons.AlignLeft,
                            accent = currentAlignment == LineAlignment.LEFT,
                            tooltip = "Align left",
                            shortcut = "Ctrl+L",
                            onClick = onAlignLeft
                        )
                        ToolbarActionButton(
                            icon = RibbonIcons.AlignCenter,
                            accent = currentAlignment == LineAlignment.CENTER,
                            tooltip = "Align center",
                            shortcut = "Ctrl+E",
                            onClick = onAlignCenter
                        )
                        ToolbarActionButton(
                            icon = RibbonIcons.AlignRight,
                            accent = currentAlignment == LineAlignment.RIGHT,
                            tooltip = "Align right",
                            shortcut = "Ctrl+R",
                            onClick = onAlignRight
                        )
                    }
                    ToolbarDivider(dividerColor)

                    ToolbarGroup("Direction") {
                        ToolbarActionButton(
                            icon = RibbonIcons.Direction,
                            accent = true,
                            tooltip = "Toggle text direction (LTR / RTL)",
                            onClick = onToggleOrientation
                        )
                    }
                }
            }
        }

        HorizontalDivider(color = dividerColor.copy(alpha = 0.6f))
    }
}


/**
 * One row of the 3-row editor ribbon. Each row is a horizontally
 * scrollable strip of labelled groups, so on very narrow windows (e.g.
 * the SPLIT pane) the ribbon clips gracefully instead of overflowing.
 */
@Composable
private fun ToolbarRow(
    content: @Composable RowScope.() -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
        content = content
    )
}


@Composable
private fun ToolbarGroup(
    label: String,
    content: @Composable RowScope.() -> Unit
) {
    // Word-ribbon layout: buttons on top, tiny group label underneath.
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}


@Composable
private fun ToolbarDivider(color: Color) {
    Box(
        modifier = Modifier
            .height(28.dp)
            .width(1.dp)
            .background(color.copy(alpha = 0.7f))
    )
}


/**
 * The ribbon tabs (Word-style). Only the selected tab's groups render
 * below the strip, so a single tab is visible at a time.
 */
private enum class RibbonTab(val label: String) {
    HOME("Home"),
    INSERT("Insert"),
    LAYOUT("Layout")
}


/**
 * Word-style tab strip above the ribbon rows. The selected tab renders
 * as an active pill; clicking another tab swaps the visible groups.
 */
@Composable
private fun RibbonTabBar(
    selected: RibbonTab,
    onSelect: (RibbonTab) -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        RibbonTab.entries.forEach { tab ->
            val isSelected = tab == selected
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = if (isSelected) {
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                } else {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                },
                modifier = Modifier.clickable {
                    data.SoundManager.play(data.SoundEvent.Click)
                    onSelect(tab)
                }
            ) {
                Text(
                    text = tab.label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 5.dp)
                )
            }
        }
    }
}


/**
 * Granularity of a Bible reference being inserted via the picker dialog.
 * VERSE produces `$Book$C$V`, CHAPTER produces `$Book$C`, BOOK produces
 * just `$Book`.
 */
internal enum class ReferenceKind(val label: String) {
    VERSE("Verse"),
    CHAPTER("Chapter"),
    BOOK("Book")
}


/**
 * Word-style "Styles" dropdown. Shows the paragraph style at the caret
 * (Normal / H1 / H2 / Quote) and applies the chosen style's block
 * prefix to the current line — replacing any existing prefix rather
 * than stacking on top of it.
 */
@Composable
private fun StyleDropdown(
    currentStyle: NoteStyle,
    onStyleSelected: (NoteStyle) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    ToolbarTip(label = "Apply paragraph style to current line") {
        Box {
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier
                    .height(28.dp)
                    .clickable {
                        data.SoundManager.play(data.SoundEvent.Click)
                        expanded = true
                    }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        RibbonIcons.Styles,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = currentStyle.label,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "▾",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                NoteStyle.entries.forEach { style ->
                    DropdownMenuItem(
                        text = { Text(style.label) },
                        onClick = {
                            expanded = false
                            data.SoundManager.play(data.SoundEvent.Click)
                            if (style != currentStyle) onStyleSelected(style)
                        }
                    )
                }
            }
        }
    }
}


@Composable
private fun InlineButton(
    label: String,
    bold: Boolean = false,
    italic: Boolean = false,
    underline: Boolean = false,
    tooltip: String? = null,
    shortcut: String? = null,
    onClick: () -> Unit
) {
    ToolbarTip(label = tooltip ?: label, shortcut = shortcut) {
        Surface(
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        modifier = Modifier
            .height(28.dp)
            .clickable {
                data.SoundManager.play(data.SoundEvent.Click)
                onClick()
            }
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                style = TextStyle(
                    fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
                    fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal,
                    textDecoration = if (underline) TextDecoration.Underline else null
                ),
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
    }
}


@Composable
private fun StyleButton(
    label: String? = null,
    icon: ImageVector? = null,
    sizeSp: Int? = null,
    accent: Boolean = false,
    tooltip: String? = null,
    shortcut: String? = null,
    onClick: () -> Unit
) {
    ToolbarTip(label = tooltip ?: label ?: "Button", shortcut = shortcut) {
        Surface(
            shape = RoundedCornerShape(6.dp),
            color = if (accent) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier
                .height(28.dp)
                .clickable {
                    data.SoundManager.play(data.SoundEvent.Click)
                    onClick()
                }
        ) {
            Box(
                modifier = Modifier.padding(horizontal = if (icon != null) 8.dp else 10.dp),
                contentAlignment = Alignment.Center
            ) {
                if (icon != null) {
                    Icon(
                        icon,
                        contentDescription = tooltip,
                        tint = if (accent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(16.dp)
                    )
                } else {
                    Text(
                        text = label ?: "",
                        style = TextStyle(fontSize = (sizeSp ?: 13).sp),
                        color = if (accent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ToolbarTip(
    label: String,
    shortcut: String? = null,
    content: @Composable () -> Unit
) {
    // Hover tooltip for ribbon buttons. This Material3 version dropped
    // PlainTooltip, so the tooltip surface is built here on top of
    // TooltipBox (inverseSurface = the classic "dark bubble" look).
    // The optional `shortcut` renders as a small kbd-style chip under
    // the label, e.g. "Bold" + "Ctrl+B".
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(),
        state = rememberTooltipState(),
        tooltip = {
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = MaterialTheme.colorScheme.inverseSurface,
                shadowElevation = 4.dp
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.inverseOnSurface
                    )
                    if (shortcut != null) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.16f)
                        ) {
                            Text(
                                text = shortcut,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.9f),
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
            }
        }
    ) {
        content()
    }
}


@Composable
internal fun ToolbarActionButton(
    label: String? = null,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    accent: Boolean = false,
    tooltip: String? = null,
    shortcut: String? = null,
    onClick: () -> Unit
) {
    val baseColor = if (accent) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
    val textColor = when {
        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
        accent -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurface
    }
    ToolbarTip(label = tooltip ?: label ?: "Button", shortcut = shortcut) {
        Surface(
            shape = RoundedCornerShape(6.dp),
            color = if (enabled) baseColor else baseColor.copy(alpha = 0.3f),
            modifier = Modifier
                .height(28.dp)
                .then(
                    if (enabled) Modifier.clickable {
                        data.SoundManager.play(data.SoundEvent.Click)
                        onClick()
                    } else Modifier
                )
        ) {
            Box(
                modifier = Modifier.padding(horizontal = if (icon != null && label == null) 8.dp else 10.dp),
                contentAlignment = Alignment.Center
            ) {
                when {
                    icon != null && label != null -> Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            icon,
                            contentDescription = tooltip,
                            tint = textColor,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(label, style = MaterialTheme.typography.labelMedium, color = textColor)
                    }

                    icon != null -> Icon(
                        icon,
                        contentDescription = tooltip,
                        tint = textColor,
                        modifier = Modifier.size(16.dp)
                    )

                    else -> Text(
                        text = label ?: "",
                        style = MaterialTheme.typography.labelLarge,
                        color = textColor
                    )
                }
            }
        }
    }
}


@Composable
private fun ListButton(
    ordered: Boolean,
    icon: ImageVector? = null,
    tooltip: String? = null,
    shortcut: String? = null,
    onClick: () -> Unit
) {
    val label = if (ordered) "1. List" else "• List"
    ToolbarTip(label = tooltip ?: label, shortcut = shortcut) {
        Surface(
            shape = RoundedCornerShape(6.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier
                .height(28.dp)
                .clickable {
                    data.SoundManager.play(data.SoundEvent.Click)
                    onClick()
                }
        ) {
            Box(
                modifier = Modifier.padding(horizontal = if (icon != null) 8.dp else 10.dp),
                contentAlignment = Alignment.Center
            ) {
                if (icon != null) {
                    Icon(
                        icon,
                        contentDescription = tooltip,
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(16.dp)
                    )
                } else {
                    Text(text = label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }
}


@Composable
private fun ColorDot(
    color: Color,
    tooltip: String? = null,
    shortcut: String? = null,
    onClick: () -> Unit
) {
    ToolbarTip(label = tooltip ?: "Highlight", shortcut = shortcut) {
        Box(
        modifier = Modifier
            .size(20.dp)
            .background(color.copy(alpha = 0.85f), CircleShape)
            .clickable {
                data.SoundManager.play(data.SoundEvent.Click)
                onClick()
            }
    )
    }
}


@Composable
private fun NoColorDot(
    tooltip: String? = null,
    shortcut: String? = null,
    onClick: () -> Unit
) {
    // Hollow circle with a diagonal slash — Word's "No colour" swatch:
    // strips [#hex] highlight markers from the selection / cursor line.
    val lineColor = MaterialTheme.colorScheme.onSurfaceVariant
    ToolbarTip(label = tooltip ?: "No colour", shortcut = shortcut) {
        Box(
        modifier = Modifier
            .size(20.dp)
            .border(1.dp, lineColor.copy(alpha = 0.8f), CircleShape)
            .clickable {
                data.SoundManager.play(data.SoundEvent.Click)
                onClick()
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawLine(
                color = lineColor,
                start = Offset(size.width * 0.22f, size.height * 0.78f),
                end = Offset(size.width * 0.78f, size.height * 0.22f),
                strokeWidth = 2.dp.toPx()
            )
        }
    }
    }
}



