package ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import data.AppRelease
import data.AppUpdater
import data.BibleRepository
import data.ReadingStatistics
import data.SoundEvent
import data.SoundManager
import data.openExternalUrl
import java.time.LocalDate
import java.util.Random
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import model.Book


/** The verse shown on Home as today's Daily Verse. */
internal data class DailyVerse(
    val book: Book,
    val chapter: Int,
    val verse: Int,
    val text: String
)

/**
 * Deterministically picks the verse for a given day: [seed] (the date's
 * epoch day) drives a [Random] that selects a flat index across every verse
 * of [books], so the same day always shows the same verse within a
 * translation and it changes at midnight. Returns null when the module has
 * no verses at all.
 */
internal fun pickDailyVerse(books: List<Book>, seed: Long): DailyVerse? {
    var total = 0
    for (book in books) {
        for (chapter in book.chapters) {
            total += chapter.verses.size
        }
    }
    if (total == 0) return null
    var remaining = Random(seed).nextInt(total)
    for (book in books) {
        for (chapter in book.chapters) {
            val size = chapter.verses.size
            if (remaining < size) {
                val verse = chapter.verses[remaining]
                return DailyVerse(book, chapter.chapter, verse.verse, verse.text)
            }
            remaining -= size
        }
    }
    return null
}

private data class StudyProgress(
    val oldTestament: Int,
    val newTestament: Int,
    val wholeBible: Int
)

// The menu draws from the same Pages palette as Settings (AppTheme): ink
// background, navy panel surfaces, leather gold accents and cream text, so
// nothing on this screen is hand-picked any more.
private val MenuBackground: Color
    @Composable get() = MaterialTheme.colorScheme.background
private val MenuSurface: Color
    @Composable get() = MaterialTheme.colorScheme.surface
private val MenuGold: Color
    @Composable get() = MaterialTheme.colorScheme.primary
private val MenuCream: Color
    @Composable get() = MaterialTheme.colorScheme.onSurface
private val MenuMuted: Color
    @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant
private val MenuLine: Color
    @Composable get() = MaterialTheme.colorScheme.outlineVariant


@Composable
fun HomeScreen(
    openBible: () -> Unit,
    openNotes: () -> Unit,
    openSettings: () -> Unit,
    openQuit: () -> Unit,
    onOpenVerse: (BibleReferenceSelection) -> Unit
) {
    val todaySeed = LocalDate.now().toEpochDay()
    var dailyVerse by remember { mutableStateOf<DailyVerse?>(null) }
    var dailyVerseLoaded by remember { mutableStateOf(false) }
    var books by remember { mutableStateOf<List<Book>>(emptyList()) }
    var revealed by remember { mutableStateOf(false) }
    // Newer stable release from GitHub (null = up to date / offline), and
    // whether its installer is currently being fetched and staged.
    var update by remember { mutableStateOf<AppRelease?>(null) }
    var updating by remember { mutableStateOf(false) }
    // Download percentage while [updating]; -1 until the server reports a
    // size (or when it reports none at all).
    var updateProgress by remember { mutableStateOf(-1) }
    val scope = rememberCoroutineScope()
    val reveal by animateFloatAsState(
        targetValue = if (revealed) 1f else 0f,
        animationSpec = tween(durationMillis = 550),
        label = "home-reveal"
    )
    LaunchedEffect(Unit) { revealed = true }

    // One check per app launch (AppUpdater caches it session-wide), so
    // returning to Home never re-hits the GitHub API.
    LaunchedEffect(Unit) {
        update = AppUpdater.checkForUpdate()
    }

    LaunchedEffect(BibleRepository.currentModuleId(), todaySeed) {
        val loaded = BibleRepository.loadBooks()
        dailyVerse = pickDailyVerse(loaded, todaySeed)
        dailyVerseLoaded = true
        books = loaded
    }

    Box(
        modifier = Modifier.fillMaxSize().background(MenuBackground),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 40.dp, vertical = 34.dp)
                .graphicsLayer {
                    alpha = reveal
                    translationY = (1f - reveal) * 24.dp.toPx()
                },
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            CrossMark(
                color = MenuGold,
                modifier = Modifier.width(25.dp).height(36.dp)
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = "HOLY BIBLE",
                color = MenuCream,
                fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.Bold,
                fontSize = 36.sp,
                letterSpacing = 3.sp
            )
            Spacer(Modifier.height(26.dp))

            DailyVerseCard(
                verse = dailyVerse,
                loaded = dailyVerseLoaded,
                onOpen = onOpenVerse
            )
            Spacer(Modifier.height(24.dp))

            HomeMenuButton(
                label = "Bible",
                icon = RibbonIcons.Bible,
                filled = true,
                onClick = openBible
            )
            Spacer(Modifier.height(12.dp))
            HomeMenuButton(
                label = "Notes",
                icon = RibbonIcons.Notes,
                filled = false,
                onClick = openNotes
            )
            Spacer(Modifier.height(12.dp))
            HomeMenuButton(
                label = "Settings",
                icon = RibbonIcons.Settings,
                filled = false,
                onClick = openSettings
            )
            Spacer(Modifier.height(12.dp))
            HomeMenuButton(
                label = "Quit",
                icon = RibbonIcons.Quit,
                filled = false,
                onClick = openQuit
            )

            Spacer(Modifier.height(30.dp))
            Text(
                text = "STUDY PROGRESS",
                color = MenuGold,
                fontSize = 11.sp,
                letterSpacing = 2.5.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(18.dp))
            val progress = studyProgress(books)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                ProgressRing(progress.oldTestament, "Old Testament", Modifier.weight(1f))
                ProgressRing(progress.newTestament, "New Testament", Modifier.weight(1f))
                ProgressRing(progress.wholeBible, "Whole Bible", Modifier.weight(1f))
            }
            Spacer(Modifier.height(18.dp))
            Text(
                text = "Read a chapter to grow your progress.",
                color = MenuMuted.copy(alpha = 0.7f),
                fontSize = 12.sp,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(6.dp))
        }

        Row(
            modifier = Modifier.align(Alignment.BottomStart).padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "v${AppUpdater.CURRENT_VERSION}",
                color = MenuMuted.copy(alpha = 0.55f),
                fontSize = 10.sp,
                letterSpacing = 1.sp
            )
            update?.let { release ->
                Spacer(Modifier.width(10.dp))
                UpdateChip(
                    version = release.version,
                    busy = updating,
                    progress = updateProgress,
                    onClick = {
                        if (updating) return@UpdateChip
                        updating = true
                        updateProgress = -1
                        scope.launch {
                            val installed = AppUpdater.installAndRestart(release) { percent ->
                                updateProgress = percent
                            }
                            if (installed) {
                                // The staged script waits for this process
                                // to exit, installs, then relaunches it.
                                openQuit()
                            } else {
                                // No staged installer (dev build, missing
                                // asset, download failed): let the user
                                // grab it from the release page.
                                updating = false
                                update = null
                                openExternalUrl(release.pageUrl)
                            }
                        }
                    }
                )
            }
        }
    }
}


private fun studyProgress(books: List<Book>): StudyProgress {
    if (books.isEmpty()) return StudyProgress(0, 0, 0)

    fun percent(pair: Pair<Int, Int>): Int =
        if (pair.second == 0) 0 else (pair.first * 100f / pair.second).roundToInt()

    val old = ReadingStatistics.testamentProgress(books, oldTestament = true)
    val new = ReadingStatistics.testamentProgress(books, oldTestament = false)
    return StudyProgress(
        oldTestament = percent(old),
        newTestament = percent(new),
        wholeBible = percent(old.first + new.first to old.second + new.second)
    )
}


@Composable
private fun DailyVerseCard(
    verse: DailyVerse?,
    loaded: Boolean,
    onOpen: (BibleReferenceSelection) -> Unit
) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MenuSurface.copy(alpha = 0.72f))
            .border(1.dp, MenuLine.copy(alpha = 0.6f), shape)
            .padding(horizontal = 22.dp, vertical = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "DAILY VERSE",
            color = MenuGold,
            fontSize = 10.sp,
            letterSpacing = 2.5.sp,
            fontWeight = FontWeight.Medium
        )
        Spacer(Modifier.height(12.dp))

        when {
            !loaded -> Text(
                text = "Loading today's verse\u2026",
                color = MenuMuted,
                fontSize = 14.sp
            )

            verse == null -> Text(
                text = "No verse available.",
                color = MenuMuted,
                fontSize = 14.sp
            )

            else -> {
                Text(
                    text = "\u201C${stripWordStudyMarkup(verse.text)}\u201D",
                    color = MenuCream,
                    fontFamily = FontFamily.Serif,
                    fontStyle = FontStyle.Italic,
                    fontSize = 20.sp,
                    lineHeight = 30.sp,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .clickable {
                            SoundManager.play(SoundEvent.Click)
                            onOpen(
                                BibleReferenceSelection(
                                    book = verse.book.name,
                                    chapter = verse.chapter,
                                    verse = verse.verse
                                )
                            )
                        }
                        .padding(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${verse.book.name} ${verse.chapter}:${verse.verse}",
                        color = MenuGold,
                        fontFamily = FontFamily.Serif,
                        fontSize = 14.sp,
                        textDecoration = TextDecoration.Underline
                    )
                    Text(
                        text = "  \u2192",
                        color = MenuGold,
                        fontFamily = FontFamily.Serif,
                        fontSize = 14.sp
                    )
                }
            }
        }
    }
}


@Composable
private fun HomeMenuButton(
    label: String,
    icon: ImageVector,
    filled: Boolean,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(12.dp)
    val background = if (filled) MenuGold else Color.Transparent
    val contentColor = if (filled) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MenuGold
    }
    val borderColor = if (filled) {
        Color.Transparent
    } else {
        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.9f)
    }
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val scale by animateFloatAsState(
        targetValue = if (hovered) 1.012f else 1f,
        animationSpec = tween(durationMillis = 140),
        label = "menu-button-scale"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(58.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(shape)
            .background(background)
            .border(1.dp, borderColor, shape)
            .hoverable(interaction)
            .clickable {
                SoundManager.play(SoundEvent.Click)
                onClick()
            }
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(23.dp)
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = label.uppercase(),
            color = contentColor,
            fontFamily = FontFamily.Serif,
            fontWeight = FontWeight.Medium,
            fontSize = 18.sp,
            letterSpacing = 1.5.sp
        )
    }
}


@Composable
private fun UpdateChip(
    version: String,
    busy: Boolean,
    progress: Int,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(4.dp)
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        modifier = Modifier
            .clip(shape)
            .background(
                if (hovered && !busy) MenuGold.copy(alpha = 0.16f)
                else Color.Transparent
            )
            .border(
                1.dp,
                MenuGold.copy(alpha = if (busy) 0.35f else 0.7f),
                shape
            )
            .hoverable(interaction)
            .clickable(enabled = !busy) {
                SoundManager.play(SoundEvent.Click)
                onClick()
            }
            .padding(horizontal = 7.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = when {
                !busy -> "UPDATE $version"
                progress >= 0 -> "UPDATING $progress%"
                else -> "UPDATING\u2026"
            },
            color = MenuGold.copy(alpha = if (busy) 0.6f else 1f),
            fontSize = 10.sp,
            letterSpacing = 1.5.sp,
            fontWeight = FontWeight.Medium
        )
    }
}


@Composable
private fun ProgressRing(percent: Int, label: String, modifier: Modifier = Modifier) {
    val ringColor = MenuLine
    val progressColor = MenuGold
    val animatedProgress by animateFloatAsState(
        targetValue = percent / 100f,
        animationSpec = tween(durationMillis = 700),
        label = "progress-ring"
    )
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier.size(86.dp),
            contentAlignment = Alignment.Center
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val stroke = 6.dp.toPx()
                drawArc(
                    color = ringColor,
                    startAngle = -90f,
                    sweepAngle = 360f,
                    useCenter = false,
                    style = Stroke(width = stroke, cap = StrokeCap.Round)
                )
                if (animatedProgress > 0f) {
                    drawArc(
                        color = progressColor,
                        startAngle = -90f,
                        sweepAngle = 360f * animatedProgress,
                        useCenter = false,
                        style = Stroke(width = stroke, cap = StrokeCap.Round)
                    )
                }
            }
            Text(
                text = "$percent%",
                color = MenuCream,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = label,
            color = MenuCream,
            fontFamily = FontFamily.Serif,
            fontSize = 12.sp,
            textAlign = TextAlign.Center
        )
    }
}


@Composable
private fun CrossMark(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val stroke = size.width * 0.15f
        drawLine(
            color = color,
            start = Offset(size.width / 2f, 0f),
            end = Offset(size.width / 2f, size.height),
            strokeWidth = stroke,
            cap = StrokeCap.Round
        )
        drawLine(
            color = color,
            start = Offset(0f, size.height * 0.34f),
            end = Offset(size.width, size.height * 0.34f),
            strokeWidth = stroke,
            cap = StrokeCap.Round
        )
    }
}
