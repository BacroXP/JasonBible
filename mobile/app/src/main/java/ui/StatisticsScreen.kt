package ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import data.BibleRepository
import data.ReadingPlan
import data.ReadingStatistics
import data.SettingsManager
import data.SoundEvent
import data.SoundManager
import model.Book
import ui.components.MaxWidthScaffold
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt


/**
 * Reading statistics screen: personal Bible-reading progress, fully
 * offline. Shows the totals (chapters / books / overall percentage), the
 * Old vs New Testament split, a per-book progress list, and activity bar
 * charts for the last 14 days / 12 weeks / 12 months — all derived from
 * the same read-chapter tracking the Reading Plan uses, so every view
 * agrees on what counts as read. The activity charts are backed by the
 * date-bearing read history recorded since this feature shipped; chapters
 * marked read before that only count toward the totals.
 */
@Composable
fun StatisticsScreen(back: () -> Unit) {
    // Books of the active translation, loaded once per visit (the parse
    // runs off the UI thread).
    var books by remember { mutableStateOf<List<Book>?>(null) }
    LaunchedEffect(Unit) {
        books = BibleRepository.loadBooks()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { event ->
                // Esc anywhere on the screen returns to Home.
                if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                    SoundManager.play(SoundEvent.Click)
                    back()
                    true
                } else {
                    false
                }
            }
    ) {
        // Compact top bar with the back button and the title, mirroring
        // the SPLIT screen's back-button pattern.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "← Back",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier
                    .clickable {
                        SoundManager.play(SoundEvent.Click)
                        back()
                    }
                    .padding(horizontal = 16.dp, vertical = 6.dp)
            )
            Icon(
                imageVector = RibbonIcons.Statistics,
                contentDescription = null,
                modifier = Modifier
                    .padding(start = 8.dp)
                    .size(22.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "Statistics",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(start = 8.dp)
            )
        }
        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
        )

        val loaded = books
        if (loaded == null) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Loading statistics…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                MaxWidthScaffold(
                    compact = false,
                    maxWidth = SettingsManager.bibleMaxWidth
                ) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(18.dp),
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(20.dp)
                    ) {
                        OverviewSection(loaded)
                        TestamentSection(loaded)
                        BookProgressSection(loaded)
                        ActivitySection()
                    }
                }
            }
        }
    }
}


/** Totals row: chapters read, fully-read books and overall percentage. */
@Composable
private fun OverviewSection(books: List<Book>) {
    val total = ReadingPlan.totalChapters(books)
    val read = SettingsManager.readChapterCount()
    val readBooks = ReadingStatistics.readBookCount(books)
    val percent = if (total > 0) (100f * read / total).roundToInt() else 0

    Text(
        text = "Overview",
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface
    )
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        StatCard(value = "$read", label = "Chapters read", modifier = Modifier.weight(1f))
        StatCard(value = "$readBooks", label = "Books read", modifier = Modifier.weight(1f))
        StatCard(value = "$percent%", label = "Bible read", modifier = Modifier.weight(1f))
    }
    Spacer(modifier = Modifier.height(8.dp))
    LinearProgressIndicator(
        progress = { if (total > 0) read.toFloat() / total else 0f },
        modifier = Modifier.fillMaxWidth()
    )
    Text(
        text = "$read of $total chapters read",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp)
    )
}


@Composable
private fun StatCard(value: String, label: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = value,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}


/** Old / New Testament progress bars, split at canonical book 40. */
@Composable
private fun TestamentSection(books: List<Book>) {
    Text(
        text = "Old & New Testament",
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface
    )
    val (otRead, otTotal) = ReadingStatistics.testamentProgress(books, oldTestament = true)
    val (ntRead, ntTotal) = ReadingStatistics.testamentProgress(books, oldTestament = false)
    TestamentBar("Old Testament", otRead, otTotal)
    Spacer(modifier = Modifier.height(10.dp))
    TestamentBar("New Testament", ntRead, ntTotal)
}


@Composable
private fun TestamentBar(label: String, read: Int, total: Int) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "$read / $total",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { if (total > 0) read.toFloat() / total else 0f },
            modifier = Modifier.fillMaxWidth()
        )
    }
}


/** One thin progress row per book of the canon. */
@Composable
private fun BookProgressSection(books: List<Book>) {
    Text(
        text = "Per-book progress",
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (book in books) {
            val (read, total) = ReadingStatistics.bookProgress(book)
            val fraction = if (total > 0) read.toFloat() / total else 0f
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = book.name,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = "$read / $total",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.height(3.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(
                            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        )
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(fraction)
                            .fillMaxHeight()
                            .background(MaterialTheme.colorScheme.primary)
                    )
                }
            }
        }
    }
}


/** Daily / weekly / monthly activity charts from the read history. */
@Composable
private fun ActivitySection() {
    val today = LocalDate.now()
    val entries = SettingsManager.readHistoryEntries()

    val daily = remember(today, entries) {
        ReadingStatistics.dailyActivity(entries, today, days = 14)
    }
    val weekly = remember(today, entries) {
        ReadingStatistics.weeklyActivity(entries, today, weeks = 12)
    }
    val monthly = remember(today, entries) {
        ReadingStatistics.monthlyActivity(entries, today, months = 12)
    }

    Text(
        text = "Activity",
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface
    )
    ActivityCard(
        title = "Last 14 days",
        values = daily.map { it.chapters },
        labels = daily.map { it.date.dayOfMonth.toString() }
    )
    ActivityCard(
        title = "Last 12 weeks",
        values = weekly.map { it.chapters },
        labels = weekly.map { it.start.format(DateTimeFormatter.ofPattern("d.M")) }
    )
    ActivityCard(
        title = "Last 12 months",
        values = monthly.map { it.chapters },
        labels = monthly.map {
            it.month.month.getDisplayName(TextStyle.SHORT, Locale.getDefault())
        }
    )
}


@Composable
private fun ActivityCard(title: String, values: List<Int>, labels: List<String>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(10.dp))
            BarChart(values = values, labels = labels)
        }
    }
}


/**
 * A compact pure-Compose bar chart: one column per value with the count
 * on top, a rounded bar whose height scales with the value (zero bars are
 * a short dim stub so the strip stays continuous), and a label below.
 */
@Composable
private fun BarChart(
    values: List<Int>,
    labels: List<String>,
    maxBarHeight: Dp = 64.dp,
    modifier: Modifier = Modifier
) {
    val max = maxOf(values.maxOrNull() ?: 0, 1)
    Row(
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        values.forEachIndexed { index, value ->
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = value.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (value > 0) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1
                )
                Spacer(modifier = Modifier.height(2.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.72f)
                        .height(
                            if (value > 0) {
                                maxBarHeight * (value.toFloat() / max)
                            } else {
                                4.dp
                            }
                        )
                        .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                        .background(
                            if (value > 0) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                            }
                        )
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = labels.getOrElse(index) { "" },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
        }
    }
}
