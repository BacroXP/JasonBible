package ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import data.BibleRepository
import data.ReadingPlan
import data.SettingsManager
import data.SoundManager
import data.SoundEvent
import kotlinx.coroutines.delay
import model.Book
import java.time.LocalDate
import java.util.Random


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


@Composable
fun HomeScreen(
    openBible: () -> Unit,
    openNotes: () -> Unit,
    openSettings: () -> Unit,
    openQuit: () -> Unit,
    onOpenVerse: (BibleReferenceSelection) -> Unit
) {
    // Today's verse, date-seeded from the active translation. Loaded lazily
    // (the module parse runs off the UI thread) and re-picked whenever the
    // translation or the day changes. The effect re-runs on every Home
    // visit anyway (HomeScreen leaves composition on navigation), so a
    // translation switched elsewhere is picked up automatically.
    val todaySeed = LocalDate.now().toEpochDay()
    var dailyVerse by remember { mutableStateOf<DailyVerse?>(null) }
    // Distinguishes "still loading" from "loaded, but no verse available",
    // so the card can't look stuck on the loading note forever.
    var dailyVerseLoaded by remember { mutableStateOf(false) }
    // Books of the active module, shared by the Daily Verse, the Reading
    // Plan card and the Ctrl+F Bible search (loaded once per Home visit /
    // translation change).
    var planBooks by remember { mutableStateOf<List<Book>>(emptyList()) }
    LaunchedEffect(BibleRepository.currentModuleId(), todaySeed) {
        val books = BibleRepository.loadBooks()
        dailyVerse = pickDailyVerse(books, todaySeed)
        dailyVerseLoaded = true
        planBooks = books
    }

    // ------------------------------------------------------------------
    // Full-text Bible search (Ctrl+F), reusing the Bible pane's search
    // bar + result list. ALL search state lives in the shared
    // [BibleSearchState] (query / toggles / scope / results, the debounced
    // scan, focus, Ctrl+F / Esc handling and session persistence), so Home
    // and the Bible pane can't drift. On Home there is no open
    // book/chapter, so BOOK / CHAPTER scopes degrade to the whole Bible
    // (the default null scope targets). Clicking a result closes the bar
    // and jumps to the verse in the Bible pane. The search floats as an
    // OVERLAY over the Home cards (which stay composed, dimmed behind a
    // scrim) instead of replacing them, so closing the search returns
    // exactly to where the user was.
    // ------------------------------------------------------------------
    val search = rememberBibleSearchState(books = planBooks)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .onPreviewKeyEvent(search::handleKeyEvent),
        contentAlignment = Alignment.Center
    ) {
        // The search overlays the Home cards: it is drawn ABOVE them
        // (zIndex), dims them with a translucent scrim background, and
        // swallows pointer events so the cards beneath stay inert while
        // it is open. The cards Column below stays composed, so Esc /
        // Ctrl+F / the ✕ return exactly to where the user was.
        if (search.open) {
            Column(
                modifier = Modifier
                    .widthIn(max = 560.dp)
                    .fillMaxWidth()
                    .fillMaxHeight()
                    .zIndex(1f)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f))
                    // No ripple — the whole panel is a scrim; clicks are
                    // consumed so they can't reach the cards underneath.
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { }
                    // ALSO swallow hover / move events: clickable only
                    // blocks press events, so without this the hoverable
                    // nodes on the cards beneath ("Read in Bible →", the
                    // reading-plan rows) would still fire their hover
                    // sounds while the search is open. Children of this
                    // column are hit-tested first, so the search field and
                    // result rows keep working normally.
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                awaitPointerEvent().changes.forEach { it.consume() }
                            }
                        }
                    },
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "\uD83D\uDD0D Search the Bible",
                    style = MaterialTheme.typography.headlineMedium
                )
                // The bar and results bind straight to the shared state,
                // so their wiring can't drift from the Bible pane's.
                // showScope is hidden on Home: there is no open
                // book/chapter, so BOOK / CHAPTER scopes would silently
                // degrade to the whole Bible — hide the control rather
                // than mislead.
                BibleSearchBarFor(search, showScope = false)
                BibleSearchResultsFor(
                    state = search,
                    onOpen = { match ->
                        SoundManager.play(SoundEvent.Click)
                        search.close()
                        onOpenVerse(
                            BibleReferenceSelection(
                                book = match.book.name,
                                chapter = match.chapter,
                                verse = match.verse
                            )
                        )
                    },
                    modifier = Modifier.weight(1f)
                )
            }
        }
        // Home cards — always composed; the search overlay floats above
        // them while open.
        Column(
            modifier = Modifier
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(18.dp),
                modifier = Modifier.padding(24.dp)
            ) {
                Text(
                    text = "📖 Daily Verse",
                    style = MaterialTheme.typography.headlineMedium
                )

                Card {
                    val verse = dailyVerse
                    when {
                        // The module parse (first open of a large
                        // translation) runs off the UI thread, so the card
                        // briefly shows a note before the verse lands.
                        !dailyVerseLoaded -> Text(
                            modifier = Modifier.padding(20.dp),
                            text = "Loading today's verse…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        // Loaded but the active module has no verses at all.
                        verse == null -> Text(
                            modifier = Modifier.padding(20.dp),
                            text = "No verse available.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        else -> {
                            // Strong's markup (if the active translation is
                            // a word-study module) is stripped so the card
                            // reads as clean scripture.
                            val verseText = stripWordStudyMarkup(verse.text)
                            val linkHover = remember { MutableInteractionSource() }
                            val isHovered by linkHover.collectIsHoveredAsState()
                            LaunchedEffect(isHovered) {
                                if (isHovered) {
                                    delay(60)
                                    SoundManager.play(SoundEvent.Hover)
                                }
                            }
                            Column(
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                                modifier = Modifier.padding(20.dp)
                            ) {
                                Text(
                                    text = "\"$verseText\"",
                                    style = MaterialTheme.typography.bodyLarge
                                )
                                Text(
                                    text = "${verse.book.name} ${verse.chapter}:${verse.verse}",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = "Read in Bible →",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier
                                        .hoverable(linkHover)
                                        .clickable {
                                            SoundManager.play(SoundEvent.Click)
                                            onOpenVerse(
                                                BibleReferenceSelection(
                                                    book = verse.book.name,
                                                    chapter = verse.chapter,
                                                    verse = verse.verse
                                                )
                                            )
                                        }
                                )
                            }
                        }
                    }
                }

                Button(
                    onClick = {
                        SoundManager.play(SoundEvent.Click)
                        openBible()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Bible")
                }

                Button(
                    onClick = {
                        SoundManager.play(SoundEvent.Click)
                        openNotes()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Notes")
                }

                Button(
                    onClick = {
                        SoundManager.play(SoundEvent.Click)
                        openSettings()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Settings")
                }

                Button(
                    onClick = {
                        SoundManager.play(SoundEvent.Click)
                        openQuit()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Quit")
                }
            }
        }

            // 365-day reading plan card: today's deterministic chapter
            // assignments from the active translation, overall progress
            // (chapters read / total), and a one-tap "mark today read".
            ReadingPlanCard(
                books = planBooks,
                onOpenVerse = onOpenVerse
            )
        }
    }
}


/**
 * "Bible in a year" card: shows the plan day, today's chapters (from the
 * active translation), the overall read-progress bar, and a toggle that
 * marks / unmarks today's chapters as read. Each chapter line opens the
 * Bible pane at that chapter.
 */
@Composable
private fun ReadingPlanCard(
    books: List<Book>,
    onOpenVerse: (BibleReferenceSelection) -> Unit
) {
    val today = LocalDate.now()
    val day = ReadingPlan.planDay(today)
    val assignments = remember(books, day) {
        ReadingPlan.chaptersForDay(books, day)
    }
    val total = ReadingPlan.totalChapters(books)
    val read = SettingsManager.readChapterCount()
    val progress = ReadingPlan.progress(books)
    val allRead = assignments.isNotEmpty() &&
        assignments.all { (bn, cn) -> SettingsManager.isChapterRead(bn, cn) }

    Card(
        modifier = Modifier
            .widthIn(max = 560.dp)
            .fillMaxWidth()
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(20.dp)
        ) {
            Text(
                text = "\uD83D\uDCC5 Reading Plan",
                style = MaterialTheme.typography.headlineSmall
            )
            Text(
                text = "Day ${day + 1} of ${ReadingPlan.PLAN_DAYS}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )

            if (books.isEmpty()) {
                Text(
                    text = "Loading reading plan\u2026",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = "$read of $total chapters read",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (assignments.isNotEmpty()) {
                    Text(
                        text = "Today's reading:",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Column(
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        assignments.forEach { (bn, cn) ->
                            val name = books.find { it.book == bn }?.name
                                ?: "Book $bn"
                            val rowHover = remember { MutableInteractionSource() }
                            val isHovered by rowHover.collectIsHoveredAsState()
                            LaunchedEffect(isHovered) {
                                if (isHovered) {
                                    delay(60)
                                    SoundManager.play(SoundEvent.Hover)
                                }
                            }
                            Text(
                                text = "\u2022 $name $cn" +
                                    if (SettingsManager.isChapterRead(bn, cn)) "  \u2713" else "",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier
                                    .hoverable(rowHover)
                                    .clickable {
                                        SoundManager.play(SoundEvent.Click)
                                        onOpenVerse(
                                            BibleReferenceSelection(
                                                book = name,
                                                chapter = cn,
                                                verse = null
                                            )
                                        )
                                    }
                            )
                        }
                    }
                    Text(
                        text = if (allRead) "Unmark today as read" else "Mark today as read",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable {
                            SoundManager.play(SoundEvent.Click)
                            assignments.forEach { (bn, cn) ->
                                SettingsManager.setChapterRead(bn, cn, !allRead)
                            }
                        }
                    )
                }
            }
        }
    }
}
