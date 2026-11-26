package ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import data.SoundEvent
import data.SoundManager
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ---------------------------------------------------------------------------
// Bible chrome
//
// The reading pane shares the Settings screen's visual language: a gold mono
// eyebrow over a serif title, numbered cards for every list level, and the
// navy + gold palette from AppTheme. The pieces live here so the book list,
// the chapter list and the verse panel all draw the same shapes.
// ---------------------------------------------------------------------------


/** Gold monospaced eyebrow line ("OLD TESTAMENT · 39 BOOKS"). */
@Composable
internal fun BibleEyebrow(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall.copy(
            fontFamily = FontFamily.Monospace,
            letterSpacing = 2.sp,
            fontWeight = FontWeight.SemiBold
        ),
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.9f),
        modifier = modifier
    )
}


/** Serif page title with its gold eyebrow and optional sub-line. */
@Composable
internal fun BibleHero(
    eyebrow: String,
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(5.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        BibleEyebrow(eyebrow)
        Text(
            text = title,
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}


/** The gold `01` gutter number shared by every numbered list level. */
@Composable
internal fun SectionNumber(number: String, modifier: Modifier = Modifier) {
    Text(
        text = number,
        style = MaterialTheme.typography.labelLarge.copy(
            fontFamily = FontFamily.Monospace,
            letterSpacing = 1.sp,
            fontWeight = FontWeight.SemiBold
        ),
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier
    )
}


/**
 * A big numbered panel for the top level of the Bible drill-down (the two
 * testaments): gold number gutter, serif title, book count and a chevron.
 */
@Composable
internal fun TestamentSection(
    number: String,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    LaunchedEffect(hovered) {
        if (hovered) {
            delay(60.milliseconds)
            SoundManager.play(SoundEvent.Hover)
        }
    }
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (hovered) {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
        },
        border = BorderStroke(
            width = 1.dp,
            color = if (hovered) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
            } else {
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            }
        ),
        modifier = modifier
            .hoverable(hover)
            .clickable {
                SoundManager.play(SoundEvent.Click)
                onClick()
            }
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 18.dp)
        ) {
            SectionNumber(number)
            Column(
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = "\u2192",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}


/**
 * Copy icon with transient feedback: writes [copyText] to the clipboard and
 * flips to the gold "active" state for a moment so the click is visible
 * without adding a label to the header.
 */
@Composable
internal fun CopyIconButton(
    copyText: String,
    description: String = "Copy"
) {
    val clipboard = rememberAppClipboard()
    val scope = rememberCoroutineScope()
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1500.milliseconds)
            copied = false
        }
    }
    ChromeIconButton(
        icon = RibbonIcons.Copy,
        description = if (copied) "Copied" else description,
        active = copied,
        onClick = {
            scope.launch { clipboard.setText(copyText) }
            copied = true
        }
    )
}


/** Small round icon button used for the verse copy / mark actions. */
@Composable
internal fun ChromeIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    active: Boolean = false,
    danger: Boolean = false,
    onClick: () -> Unit
) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    LaunchedEffect(hovered) {
        if (hovered) {
            delay(60.milliseconds)
            SoundManager.play(SoundEvent.Hover)
        }
    }
    val tint = when {
        danger -> MaterialTheme.colorScheme.error
        active -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(30.dp)
            .background(
                color = when {
                    active -> activeChromeFill()
                    hovered -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
                    else -> Color.Transparent
                },
                shape = CircleShape
            )
            .border(
                width = 1.dp,
                color = if (active) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
                } else {
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
                },
                shape = CircleShape
            )
            .hoverable(hover)
            .clickable {
                SoundManager.play(SoundEvent.Click)
                onClick()
            }
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            modifier = Modifier.size(15.dp),
            tint = tint
        )
    }
}


/**
 * Pill toggle that marks the chapter on screen read (or unread). Uses the
 * same navy fill + gold border as the section chips for the read state, so
 * an active mark never introduces a grey panel.
 */
@Composable
internal fun ChapterReadButton(
    read: Boolean,
    onToggle: () -> Unit
) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    LaunchedEffect(hovered) {
        if (hovered) {
            delay(60.milliseconds)
            SoundManager.play(SoundEvent.Hover)
        }
    }
    Surface(
        shape = PillShape,
        color = when {
            read -> activeChromeFill()
            hovered -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
            else -> Color.Transparent
        },
        border = BorderStroke(
            width = 1.dp,
            color = if (read) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.65f)
            } else {
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
            }
        ),
        modifier = Modifier
            .hoverable(hover)
            .clickable {
                SoundManager.play(SoundEvent.Click)
                onToggle()
            }
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Text(
                text = if (read) "\u2713" else "\u25CB",
                style = MaterialTheme.typography.labelMedium,
                color = if (read) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
            Text(
                text = if (read) "Read" else "Mark as read",
                style = MaterialTheme.typography.labelMedium,
                color = if (read) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
    }
}


/** One jump target in the Bible's section chrome (a testament). */
internal data class BibleNavSection(
    val id: String,
    val number: String,
    val title: String
)


/** Height of the scrolling Bible top bar (empty until it fades in). */
internal val BIBLE_BAR_HEIGHT = 54.dp


/** Section heading: gold number gutter over a serif title and its count. */
@Composable
internal fun BibleSectionHeader(
    number: String,
    title: String,
    subtitle: String
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp, bottom = 4.dp)
    ) {
        androidx.compose.material3.HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
        )
        Row(
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(top = 10.dp)
        ) {
            SectionNumber(number)
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        Text(
            text = subtitle,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 30.dp)
        )
    }
}


/** One numbered pill in the Bible top bar that jumps to its testament. */
@Composable
private fun BibleSectionChip(
    number: String,
    title: String,
    active: Boolean,
    onClick: () -> Unit
) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    LaunchedEffect(hovered) {
        if (hovered) {
            delay(60.milliseconds)
            SoundManager.play(SoundEvent.Hover)
        }
    }
    Surface(
        shape = PillShape,
        color = when {
            active -> activeChromeFill()
            hovered -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
            else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        },
        border = BorderStroke(
            width = 1.dp,
            color = if (active) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.65f)
            } else {
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            }
        ),
        modifier = Modifier
            .hoverable(hover)
            .clickable {
                SoundManager.play(SoundEvent.Click)
                onClick()
            }
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        ) {
            SectionNumber(number)
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = if (active) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.padding(start = 6.dp)
            )
        }
    }
}


/**
 * Scroll-aware top bar of the Bible index: a book glyph, the page title and
 * the numbered testament chips. Fades in once the hero has scrolled away, so
 * the top of the page stays clean.
 */
@Composable
internal fun BibleSectionBar(
    title: String,
    sections: List<BibleNavSection>,
    activeId: String?,
    onJump: (String) -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(BIBLE_BAR_HEIGHT)
            .padding(horizontal = 14.dp)
    ) {
        Icon(
            imageVector = RibbonIcons.Bible,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp)
        )
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = 8.dp)
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .weight(1f, fill = false)
                .horizontalScroll(rememberScrollState())
                .padding(start = 12.dp)
        ) {
            sections.forEach { section ->
                BibleSectionChip(
                    number = section.number,
                    title = section.title,
                    active = section.id == activeId,
                    onClick = { onJump(section.id) }
                )
            }
        }
        Spacer(Modifier.weight(1f))
    }
}


/**
 * Numbered section rail beside the Bible index (wide windows only), mirroring
 * the Settings rail: the testament whose heading last passed the top is
 * highlighted gold.
 */
@Composable
internal fun BibleSectionRail(
    sections: List<BibleNavSection>,
    activeId: String?,
    onJump: (String) -> Unit
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier
            .width(184.dp)
            .padding(start = 18.dp, top = 30.dp, end = 10.dp)
    ) {
        Text(
            text = "SECTIONS",
            style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = FontFamily.Monospace,
                letterSpacing = 2.sp,
                fontWeight = FontWeight.SemiBold
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
            modifier = Modifier.padding(start = 8.dp, bottom = 10.dp)
        )
        sections.forEach { section ->
            val selected = section.id == activeId
            val hover = remember { MutableInteractionSource() }
            val hovered by hover.collectIsHoveredAsState()
            LaunchedEffect(hovered) {
                if (hovered) {
                    delay(60.milliseconds)
                    SoundManager.play(SoundEvent.Hover)
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        if (selected) {
                            activeChromeFill()
                        } else {
                            Color.Transparent
                        }
                    )
                    .hoverable(hover)
                    .clickable {
                        SoundManager.play(SoundEvent.Click)
                        onJump(section.id)
                    }
                    .padding(horizontal = 8.dp, vertical = 7.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SectionNumber(section.number)
                Text(
                    text = section.title,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (selected) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
