@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class
)

package ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import data.SoundEvent
import data.SoundManager


/**
 * The verses an export dialog starts from. [verses] is the selectable pool
 * (a chapter's verses, or exactly the verses a note reference covers);
 * [startIndex] / [endIndex] select the initial range. Range editing is
 * offered only when [chapter] is set and more than one verse is available.
 */
internal data class VerseExportSeed(
    val bookName: String,
    val chapter: Int?,
    val verses: List<CardVerse>,
    val startIndex: Int = 0,
    val endIndex: Int = (verses.size - 1).coerceAtLeast(0),
    val translationName: String? = VerseCardExporter.activeTranslationName()
) {
    val selectedVerses: List<CardVerse>
        get() {
            if (verses.isEmpty()) return emptyList()
            val start = startIndex.coerceIn(0, verses.lastIndex)
            val end = endIndex.coerceIn(start, verses.lastIndex)
            return verses.subList(start, end + 1)
        }
}


/**
 * Export dialog shared by the Bible reader and the notes editor: pick one
 * of the four stylish card designs (or the plain one), narrow the verse
 * range, then save a postcard PNG or a PowerPoint deck.
 */
@Composable
internal fun VerseExportDialog(
    seed: VerseExportSeed,
    onDismiss: () -> Unit
) {
    var style by remember { mutableStateOf(VerseCardStyle.CLASSIC) }
    var startIndex by remember(seed) {
        mutableStateOf(seed.startIndex.coerceIn(0, seed.verses.lastIndex.coerceAtLeast(0)))
    }
    var endIndex by remember(seed) {
        mutableStateOf(seed.endIndex.coerceIn(0, seed.verses.lastIndex.coerceAtLeast(0)))
    }
    var status by remember(seed) { mutableStateOf<String?>(null) }

    val rangeEditable = seed.chapter != null && seed.verses.size > 1
    val selected = remember(seed, startIndex, endIndex) {
        if (seed.verses.isEmpty()) {
            emptyList()
        } else {
            val start = startIndex.coerceIn(0, seed.verses.lastIndex)
            val end = endIndex.coerceIn(start, seed.verses.lastIndex)
            seed.verses.subList(start, end + 1)
        }
    }
    val spec = VerseCardSpec(
        bookName = seed.bookName,
        verses = selected,
        translationName = seed.translationName,
        style = style
    )
    // A postcard is a single readable image, so it holds at most two
    // verses. Longer ranges stay available for the PowerPoint deck.
    val canPostcard = spec.supportsPostcard

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Export scripture") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = spec.referenceLabel,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )

                if (rangeEditable) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        VersePicker(
                            label = "From",
                            verses = seed.verses,
                            selectedIndex = startIndex,
                            onSelected = { index ->
                                startIndex = index
                                if (endIndex < index) endIndex = index
                            }
                        )
                        VersePicker(
                            label = "To",
                            verses = seed.verses,
                            selectedIndex = endIndex,
                            onSelected = { index ->
                                endIndex = index
                                if (startIndex > index) startIndex = index
                            }
                        )
                        Text(
                            text = "${selected.size} " +
                                if (selected.size == 1) "verse" else "verses",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Text(
                    text = "Format",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                // Two cards per row: four stylish designs plus the plain
                // one, each with a small swatch of its palette.
                VerseCardStyle.entries.chunked(2).forEach { rowStyles ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        rowStyles.forEach { candidate ->
                            StyleCard(
                                style = candidate,
                                selected = candidate == style,
                                onClick = {
                                    SoundManager.play(SoundEvent.Click)
                                    style = candidate
                                },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        if (rowStyles.size == 1) {
                            Box(modifier = Modifier.weight(1f))
                        }
                    }
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Button(
                        enabled = canPostcard,
                        onClick = {
                            SoundManager.play(SoundEvent.Click)
                            val path = VerseCardExporter.exportPostcard(spec)
                            status = if (path != null) {
                                "Postcard saved to ${path.fileName}"
                            } else {
                                "Postcard export cancelled."
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Postcard (PNG)")
                    }
                    OutlinedButton(
                        onClick = {
                            SoundManager.play(SoundEvent.Click)
                            val path = VerseCardExporter.exportPowerPoint(spec)
                            status = if (path != null) {
                                "PowerPoint saved to ${path.fileName}"
                            } else {
                                "PowerPoint export cancelled."
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("PowerPoint")
                    }
                }

                Text(
                    text = status ?: if (canPostcard) {
                        "A postcard is one image; PowerPoint gets one slide per verse."
                    } else {
                        "A postcard holds one or two verses — shorten the range, " +
                            "or use PowerPoint for the whole passage."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (status != null) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                SoundManager.play(SoundEvent.Click)
                onDismiss()
            }) {
                Text("Close")
            }
        }
    )
}


/** One selectable card design: palette swatch, name and one-line blurb. */
@Composable
private fun StyleCard(
    style: VerseCardStyle,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val (background, foreground) = stylePreviewColors(style)
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        },
        border = androidx.compose.foundation.BorderStroke(
            width = if (selected) 2.dp else 1.dp,
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
            }
        ),
        modifier = modifier.clickable(onClick = onClick)
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(10.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(width = 40.dp, height = 26.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(background)
                ) {
                    Box(
                        modifier = Modifier
                            .padding(start = 8.dp, top = 10.dp)
                            .size(width = 22.dp, height = 3.dp)
                            .background(foreground)
                    )
                }
                Text(
                    text = style.label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                text = style.blurb,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}


/** Tiny on-screen approximation of each rendered card palette. */
private fun stylePreviewColors(style: VerseCardStyle): Pair<Color, Color> = when (style) {
    VerseCardStyle.PLAIN -> Color(0xFFFFFFFF) to Color(0x1D1D1F)
    VerseCardStyle.CLASSIC -> Color(0xFFF7F1E3) to Color(0xFFB08A4F)
    VerseCardStyle.MIDNIGHT -> Color(0xFF14304F) to Color(0xFFD9B96A)
    VerseCardStyle.HORIZON -> Color(0xFFC2542F) to Color(0xFFFDF3E7)
    VerseCardStyle.SCRIPTURE -> Color(0xFFFBFAF4) to Color(0xFF6B7A4F)
}


/** Compact verse-number dropdown used for the From / To range selectors. */
@Composable
private fun VersePicker(
    label: String,
    verses: List<CardVerse>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit
) {
    var open by remember { mutableStateOf(false) }
    val selected = verses.getOrNull(selectedIndex)
    Box {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier
                .height(36.dp)
                .width(108.dp)
                .clickable {
                    SoundManager.play(SoundEvent.Click)
                    open = true
                }
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.padding(horizontal = 10.dp)
            ) {
                Text(
                    text = buildString {
                        append(label)
                        append(": ")
                        append(selected?.verse?.toString().orEmpty())
                    },
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1
                )
            }
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false }
        ) {
            verses.forEachIndexed { index, verse ->
                DropdownMenuItem(
                    text = { Text("Verse ${verse.verse}") },
                    onClick = {
                        SoundManager.play(SoundEvent.Click)
                        open = false
                        onSelected(index)
                    }
                )
            }
        }
    }
}
