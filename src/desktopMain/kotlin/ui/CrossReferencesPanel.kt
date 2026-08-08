package ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import data.CrossReferences
import data.SoundEvent
import data.SoundManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import model.Book


/**
 * Cross-reference panel for one selected verse. References are DERIVED
 * from shared Strong's lemmas (no curated dataset is bundled, so nothing
 * is invented): very high overlap = parallel passages, an NT verse echoing
 * an OT verse = OT quotation / allusion, solid overlap = thematically
 * related. Each row shows the target reference plus a text snippet (from
 * the active translation when it contains the book) and opens the target
 * on click. A short note marks that curated direct references are not yet
 * bundled.
 *
 * The header toggle ("⇄") switches between the grouped LIST and a
 * SIDE-BY-SIDE comparison ("parallele Stellen nebeneinander"): each
 * related passage is shown next to the selected verse in a two-column
 * card (source | target), which is especially useful for Gospel
 * parallels and repeated Psalms.
 */
@Composable
internal fun CrossReferencesPanel(
    bookNumber: Int,
    chapterNumber: Int,
    verseNumber: Int,
    books: List<Book>,
    onOpenReference: (book: String, chapter: Int, verse: Int) -> Unit,
    modifier: Modifier = Modifier
) {
    var references by remember(bookNumber, chapterNumber, verseNumber) {
        mutableStateOf<List<CrossReferences.Reference>?>(null)
    }
    var hasCurated by remember(bookNumber, chapterNumber, verseNumber) {
        mutableStateOf(false)
    }
    var sideBySide by remember(bookNumber, chapterNumber, verseNumber) {
        mutableStateOf(false)
    }

    LaunchedEffect(bookNumber, chapterNumber, verseNumber) {
        references = withContext(Dispatchers.Default) {
            CrossReferences.ensureLoaded()
            CrossReferences.referencesFor(bookNumber, chapterNumber, verseNumber)
        }
        hasCurated = CrossReferences.curatedFor(bookNumber, chapterNumber, verseNumber).isNotEmpty()
    }

    fun bookName(number: Int): String =
        books.find { it.book == number }?.name ?: "Book $number"

    fun verseText(number: Int, chapter: Int, verse: Int): String? =
        books.find { it.book == number }
            ?.chapters?.find { it.chapter == chapter }
            ?.verses?.find { it.verse == verse }
            ?.text?.let { stripWordStudyMarkup(it) }

    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(14.dp)
        ) {
            val refs = references
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = RibbonIcons.Reference,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "Cross references — ${bookName(bookNumber)} $chapterNumber:$verseNumber",
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 6.dp)
                )
                // The ⇄ toggle only makes sense once there is something to
                // compare — hidden while loading / when no passages exist.
                if (refs != null && refs.isNotEmpty()) {
                    SideBySideToggle(
                        active = sideBySide,
                        onClick = {
                            SoundManager.play(SoundEvent.Click)
                            sideBySide = !sideBySide
                        }
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            when {
                refs == null -> Text(
                    text = "Finding related passages…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                refs.isEmpty() && !hasCurated -> Text(
                    text = "No related passages found (this verse may not be Strong's-tagged in the index).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                sideBySide -> {
                    // "Parallele Stellen nebeneinander": the selected verse
                    // beside each related passage, two columns per card,
                    // still grouped by kind so parallel accounts, OT
                    // quotations and thematic links stay clearly distinct.
                    CrossReferences.Kind.entries.forEach { kind ->
                        val group = refs.filter { it.kind == kind }.take(6)
                        if (group.isEmpty()) return@forEach
                        Text(
                            text = kind.label,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        group.forEach { ref ->
                            SideBySideCard(
                                sourceLabel = "${bookName(bookNumber)} $chapterNumber:$verseNumber",
                                sourceText = verseText(bookNumber, chapterNumber, verseNumber),
                                targetLabel = "${bookName(ref.book)} ${ref.chapter}:${ref.verse}",
                                targetText = verseText(ref.book, ref.chapter, ref.verse),
                                onOpenTarget = {
                                    onOpenReference(bookName(ref.book), ref.chapter, ref.verse)
                                }
                            )
                        }
                    }
                }

                else -> {
                    CrossReferences.Kind.entries.forEach { kind ->
                        val group = refs.filter { it.kind == kind }
                        if (group.isEmpty()) return@forEach
                        Text(
                            text = kind.label,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        group.forEach { ref ->
                            val target = "${bookName(ref.book)} ${ref.chapter}:${ref.verse}"
                            val snippet = verseText(ref.book, ref.chapter, ref.verse)
                            val rowHover = MutableInteractionSource()
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(interactionSource = rowHover, indication = null) {
                                        SoundManager.play(SoundEvent.Click)
                                        onOpenReference(bookName(ref.book), ref.chapter, ref.verse)
                                    }
                            ) {
                                Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = target,
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                        Text(
                                            text = " · ${ref.sharedLemmas} shared words",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(start = 6.dp)
                                        )
                                    }
                                    if (!snippet.isNullOrBlank()) {
                                        Text(
                                            text = "\"$snippet\"",
                                            style = MaterialTheme.typography.bodySmall,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (hasCurated) {
                Text(
                    text = "Curated direct references: bundled dataset not yet available.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}


/** The header toggle that swaps the cross-reference list for the
 *  side-by-side comparison mode (mirrors the CrossRefsToggle pill
 *  language: accent container while active). */
@Composable
private fun SideBySideToggle(active: Boolean, onClick: () -> Unit) {
    ToolbarTip(
        label = if (active) {
            "Show related passages as a list"
        } else {
            "Show the selected verse next to each related passage (side by side)"
        }
    ) {
        Surface(
            shape = PillShape,
            color = if (active) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f)
            },
            modifier = Modifier.clickable(onClick = onClick)
        ) {
            Text(
                text = if (active) "⇄·on" else "⇄",
                style = MaterialTheme.typography.labelSmall,
                color = if (active) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
            )
        }
    }
}


/** One side-by-side card: the selected verse (left) next to one related
 *  passage (right). The target column is clickable and jumps to the
 *  passage; a verse missing from the active translation shows a short
 *  placeholder instead of inventing text. */
@Composable
private fun SideBySideCard(
    sourceLabel: String,
    sourceText: String?,
    targetLabel: String,
    targetText: String?,
    onOpenTarget: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        // IntrinsicSize.Min gives the divider a definite height to span,
        // no matter which column is taller.
        Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            ComparisonColumn(
                label = sourceLabel,
                text = sourceText,
                modifier = Modifier.weight(1f)
            )
            VerticalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                modifier = Modifier.padding(vertical = 8.dp)
            )
            ComparisonColumn(
                label = targetLabel,
                text = targetText,
                onClick = {
                    SoundManager.play(SoundEvent.Click)
                    onOpenTarget()
                },
                modifier = Modifier.weight(1f)
            )
        }
    }
}


/** One column of a side-by-side card: the reference label over its verse
 *  text (up to 7 lines, ellipsized). Clickable only when [onClick] is
 *  set — the target column is, the source column is not. */
@Composable
private fun ComparisonColumn(
    label: String,
    text: String?,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    // Same no-ripple click convention as the panel's list rows: the
    // target column reads as a link, not a button.
    val hoverSource = remember { MutableInteractionSource() }
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        interactionSource = hoverSource,
                        indication = null,
                        onClick = onClick
                    )
                } else {
                    Modifier
                }
            )
            .padding(10.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = text?.let { "\"$it\"" }
                ?: "(Text not available in this translation)",
            style = MaterialTheme.typography.bodySmall,
            maxLines = 7,
            overflow = TextOverflow.Ellipsis
        )
    }
}
