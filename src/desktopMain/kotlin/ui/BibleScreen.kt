@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class
)

package ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.draganddrop.dragAndDropSource
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerMoveFilter
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draganddrop.DragAndDropTransferAction
import androidx.compose.ui.draganddrop.DragAndDropTransferData
import androidx.compose.ui.draganddrop.DragAndDropTransferable
import data.BibleRepository
import data.NotesRepository
import data.SettingsManager
import data.SoundEvent
import data.SoundManager
import model.Book
import ui.components.MaxWidthScaffold
import java.awt.datatransfer.StringSelection
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch


private enum class Testament {
    OLD,
    NEW
}private enum class MarkerColor(
    val hex: String, val color: Color
) {
    Yellow("#FFF59D", Color(0xFFFFF176)),
    Green("#A5D6A7", Color(0xFF81C784)),
    Blue("#90CAF9", Color(0xFF64B5F6)),
    Purple("#CE93D8", Color(0xFFBA68C8)),
    Red("#EF9A9A", Color(0xFFE57373))
}


// Persist the per-(book,chapter) scroll-offset cache across navigation
// (and configuration changes) by stringifying each "$book:$chapter -> $offset"
// entry into a single ArrayList<String> that Bundle can carry.
private val ScrollPositionSaver: Saver<MutableMap<String, Int>, ArrayList<String>> =
    Saver(
        save = { map ->
            ArrayList(map.map { (k, v) -> "$k=$v" })
        },
        restore = { saved ->
            saved.associate { entry ->
                val sep = entry.indexOf('=')
                entry.substring(0, sep) to entry.substring(sep + 1).toInt()
            }.toMutableMap()
        }
    )


@Composable
fun BibleScreen(
    back: () -> Unit,
    initialReference: BibleReferenceSelection? = null,
    showBackButton: Boolean = true,
    compact: Boolean = false,
    hoveredBibleReference: BibleReferenceSelection? = null,
    onOpenNoteTitle: (String, BibleReferenceSelection?) -> Unit = { _, _ -> }
) {
    val bookGridState = rememberLazyGridState()
    val chapterGridState = rememberLazyGridState()
    val verseScrollState = rememberScrollState()

    val scope = rememberCoroutineScope()

    val scrollOffsets: MutableMap<String, Int> = rememberSaveable(
        saver = ScrollPositionSaver
    ) { mutableMapOf() }

    var testament by remember { mutableStateOf(Testament.OLD) }
    var selectedBookNumber by remember { mutableStateOf<Int?>(null) }
    var selectedChapterNumber by remember { mutableStateOf<Int?>(null) }
    var selectedVerseNumber by remember { mutableStateOf<Int?>(null) }

    // Scroll-to-verse plumbing for tag-driven navigation.
    //
    // `verseYOffsetsMap` records each rendered verse's Y position inside
    // the scrolling verses Column (populated by VerseRow via
    // Modifier.onGloballyPositioned). `verseYOffsetsVersion` is bumped
    // every time the map mutates so the scroll-to-verse LaunchedEffect
    // re-fires and re-checks whether the target verse is now laid out.
    //
    // `pendingScrollVerse` is set when the user clicks a Bible
    // reference from the notes editor (the LaunchedEffect(initialReference)
    // arms it). The scroll-to-verse LaunchedEffect waits for the verse's
    // Y offset to be reported, then animateScrollTo's and clears the
    // flag. Same-chapter navigation also works: the verses are already
    // laid out so the offset is in the map and the scroll fires
    // immediately.
    val verseYOffsetsMap = remember { mutableMapOf<Int, Int>() }
    var verseYOffsetsVersion by remember { mutableStateOf(0) }
    var pendingScrollVerse by remember { mutableStateOf<Int?>(null) }

    val books = BibleRepository.books
    val visibleBooks = books.filter { book ->
        when (testament) {
            Testament.OLD -> book.book <= 39
            Testament.NEW -> book.book > 39
        }
    }

    val selectedBook = selectedBookNumber?.let { number ->
        books.find { it.book == number }
    }
    val selectedChapter = selectedBook?.chapters?.find {
        it.chapter == selectedChapterNumber
    }

    androidx.compose.runtime.LaunchedEffect(initialReference) {
        val incoming = initialReference
        if (incoming != null) {
            val matchingBook = books.find { it.name == incoming.book }
            // Unknown book names (e.g. a stale scaffold like `$Book`, or a
            // book-only line that doesn't resolve) are a no-op — don't
            // reset the pane to the book picker, just leave the current
            // view alone.
            if (matchingBook == null) return@LaunchedEffect
            selectedBookNumber = matchingBook.book
            testament = if (matchingBook.book <= 39) {
                Testament.OLD
            } else {
                Testament.NEW
            }
            selectedChapterNumber = incoming.chapter
            selectedVerseNumber = incoming.verse
            // Only queue a scroll-to-verse if the requested verse actually
            // exists in the destination chapter. Without this guard, a
            // bogus reference like `$Genesis$1$999` would set
            // pendingScrollVerse to a verse that never appears in
            // verseYOffsetsMap, the scroll-to-verse LaunchedEffect would
            // never resolve, and future chapter changes would lose their
            // normal scroll-restore behaviour until the flag was cleared.
            val targetChapter = matchingBook?.chapters?.find { it.chapter == incoming.chapter }
            pendingScrollVerse = incoming.verse?.takeIf { v ->
                targetChapter?.verses?.any { it.verse == v } == true
            }
        } else {
            // Nothing requested explicitly — resume the user's last-read
            // book/chapter from SettingsManager if it still maps to a real book.
            val last = SettingsManager.getLastRead()
            if (last != null && books.any { it.book == last.bookNumber }) {
                selectedBookNumber = last.bookNumber
                selectedChapterNumber = last.chapterNumber
                selectedVerseNumber = last.verseNumber
                testament = if (last.bookNumber <= 39) {
                    Testament.OLD
                } else {
                    Testament.NEW
                }
            }
        }
    }

    // Persist the active book/chapter and restore its scroll position
    // whenever the active chapter changes.
    androidx.compose.runtime.LaunchedEffect(selectedBookNumber, selectedChapterNumber) {
        val bn = selectedBookNumber
        val cn = selectedChapterNumber
        if (bn != null && cn != null) {
            SettingsManager.setLastRead(bn, cn, selectedVerseNumber)
            // Skip the saved-offset restore when a tag-driven scroll-to-verse
            // is pending; that scroll will run as soon as the verse's Y
            // position is reported by VerseRow.onGloballyPositioned. Without
            // this guard the saved offset from a previous visit would
            // override the tag navigation and the user would see the chapter
            // snap to its old scroll position instead of landing on the
            // requested verse.
            if (pendingScrollVerse == null) {
                val key = "$bn:$cn"
                val savedOffset = scrollOffsets[key]
                if (savedOffset != null) {
                    verseScrollState.scrollTo(
                        savedOffset.coerceAtMost(verseScrollState.maxValue)
                    )
                }
            }
        }
    }

    // Scroll-to-verse: when the user clicks a Bible reference from the
    // notes editor, pendingScrollVerse is set and the destination verse's
    // Y position gets reported via Modifier.onGloballyPositioned as the
    // new chapter lays out. Wait for both conditions, then
    // animateScrollTo. The 50ms delay at the start gives
    // verseScrollState.maxValue time to settle to the new chapter's full
    // content height — without it, animateScrollTo can land short of the
    // requested offset if maxValue hasn't caught up to the freshly
    // mounted verse list yet.
    androidx.compose.runtime.LaunchedEffect(pendingScrollVerse, verseYOffsetsVersion) {
        val target = pendingScrollVerse
        if (target != null) {
            val offset = verseYOffsetsMap[target]
            if (offset != null) {
                kotlinx.coroutines.delay(50)
                verseScrollState.animateScrollTo(offset)
                pendingScrollVerse = null
            }
        }
    }

    // Track the live verse-scroll position for the active chapter so re-
    // entering it via the jump strip resumes at the same place.
    androidx.compose.runtime.LaunchedEffect(verseScrollState.value) {
        val bn = selectedBookNumber
        val cn = selectedChapterNumber
        if (bn != null && cn != null) {
            scrollOffsets["$bn:$cn"] = verseScrollState.value
        }
    }

    // Keyboard shortcuts for the reading view. We attach onPreviewKeyEvent to
    // the outer Box so events bubble up from any focused descendant (a chapter
    // card, a verse row, a clickable link, etc.) — no explicit focusable needed.
    val chapterShortcutHandler:
        (androidx.compose.ui.input.key.KeyEvent) -> Boolean = handler@{ event ->
        if (event.type != KeyEventType.KeyDown) {
            return@handler false
        }
        val chapter = selectedChapter
        val book = selectedBook
        when {
            chapter != null && book != null &&
                ((event.isCtrlPressed && event.key == Key.DirectionLeft) ||
                    event.key == Key.PageUp) -> {
                if (chapter.chapter > 1) {
                    selectedChapterNumber = (chapter.chapter - 1).coerceAtLeast(1)
                    selectedVerseNumber = null
                }
                true
            }

            chapter != null && book != null &&
                ((event.isCtrlPressed && event.key == Key.DirectionRight) ||
                    event.key == Key.PageDown) -> {
                if (chapter.chapter < book.chapters.size) {
                    selectedChapterNumber =
                        (chapter.chapter + 1).coerceAtMost(book.chapters.size)
                    selectedVerseNumber = null
                }
                true
            }

            selectedChapterNumber != null && event.key == Key.Escape -> {
                selectedChapterNumber = null
                selectedVerseNumber = null
                true
            }

            else -> false
        }
    }

    MaxWidthScaffold(
        compact = compact,
        modifier = Modifier.onPreviewKeyEvent(chapterShortcutHandler),
        maxWidth = SettingsManager.bibleMaxWidth
    ) {
            // In SPLIT mode the bible pane is half the window width, so the
            // non-compact 24.dp inner padding (which looks generous in the
            // standalone full-width view) is excessive here — it shrinks the
            // usable content area by ~40.dp on each side relative to the
            // notes editor pane (which uses 16.dp outer + 0.dp inner
            // padding), making the two panes look visibly mismatched in
            // size. We drop the inner padding to 12.dp in compact mode so
            // both panes end up with a comparable 16.dp + 12.dp = 28.dp
            // content margin and the verses column gets more breathing room.
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier
                    .padding(if (compact) 12.dp else 24.dp)
                    .fillMaxSize()
            ) {
                if (showBackButton) {
                    Text(
                        text = "Bible",
                        style = MaterialTheme.typography.headlineMedium
                    )
                }

                // Three reading modes: book picker → chapter picker → verses.
                // The chapters list, book header, and testament indicator are kept
                // out of the reading view so the bible text gets the screen.
                when {
                    selectedBook == null -> {
                        TabRow(selectedTabIndex = testament.ordinal) {
                            Tab(
                                selected = testament == Testament.OLD,
                                onClick = {
                                    testament = Testament.OLD
                                    selectedBookNumber = null
                                    selectedChapterNumber = null
                                    selectedVerseNumber = null
                                },
                                text = { Text("Old Testament") }
                            )
                            Tab(
                                selected = testament == Testament.NEW,
                                onClick = {
                                    testament = Testament.NEW
                                    selectedBookNumber = null
                                    selectedChapterNumber = null
                                    selectedVerseNumber = null
                                },
                                text = { Text("New Testament") }
                            )
                        }

                        Text(
                            text = "${visibleBooks.size} books",
                            style = MaterialTheme.typography.bodyMedium
                        )

                        BoxWithConstraints(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                        ) {
                            val columns = responsiveColumns(maxWidth, 160.dp)

                            LazyVerticalGrid(
                                columns = GridCells.Fixed(columns),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .fillMaxHeight(),
                                state = bookGridState
                            ) {
                                items(visibleBooks) { book ->
                                BookCard(
                                    book = book,
                                    starred = SettingsManager.isBookStarred(book.book),
                                    onOpen = {
                                        SoundManager.play(SoundEvent.Click)
                                        selectedBookNumber = book.book
                                        selectedChapterNumber = null
                                        selectedVerseNumber = null
                                    },
                                    onToggleStar = {
                                        SoundManager.play(SoundEvent.Click)
                                        SettingsManager.toggleBookStar(book.book)
                                    }
                                )
                                }
                            }
                        }
                    }

                    selectedChapterNumber == null -> {
                        Text(
                            text = if (testament == Testament.OLD) {
                                "Old Testament"
                            } else {
                                "New Testament"
                            },
                            style = MaterialTheme.typography.titleMedium
                        )

                        Row(
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = selectedBook.name,
                                    style = MaterialTheme.typography.titleLarge
                                )
                                Text(
                                    text = "${selectedBook.chapters.size} chapters",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }

                            Text(
                                text = "Back to books",
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.clickable {
                                    SoundManager.play(SoundEvent.Click)
                                    selectedBookNumber = null
                                    selectedChapterNumber = null
                                    selectedVerseNumber = null
                                }
                            )
                        }

                        BoxWithConstraints(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                        ) {
                            val columns = responsiveColumns(maxWidth, 220.dp)

                            LazyVerticalGrid(
                                columns = GridCells.Fixed(columns),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .fillMaxHeight(),
                                state = chapterGridState
                            ) {
                                items(selectedBook.chapters) { chapter ->
                                ChapterCard(
                                    book = selectedBook,
                                    chapterNumber = chapter.chapter,
                                    starred = SettingsManager.isChapterStarred(
                                        selectedBook.book,
                                        chapter.chapter
                                    ),
                                    onOpen = {
                                        SoundManager.play(SoundEvent.Click)
                                        selectedChapterNumber = chapter.chapter
                                        selectedVerseNumber = null
                                    },
                                    onToggleStar = {
                                        SoundManager.play(SoundEvent.Click)
                                        SettingsManager.toggleChapterStar(
                                            selectedBook.book,
                                            chapter.chapter
                                        )
                                    }
                                )
                                }
                            }
                        }
                    }

                    else -> {
                        // A chapter is selected — show only the bible text.
                        selectedChapter?.let { chapter ->
                            val goPrev: () -> Unit = {
                                selectedChapterNumber =
                                    (chapter.chapter - 1).coerceAtLeast(1)
                                selectedVerseNumber = null
                            }
                            val goNext: () -> Unit = {
                                selectedChapterNumber =
                                    (chapter.chapter + 1).coerceAtMost(
                                        selectedBook.chapters.size
                                    )
                                selectedVerseNumber = null
                            }
                            val goScrub: (Int) -> Unit = { newChapter ->
                                selectedChapterNumber = newChapter.coerceIn(
                                    1,
                                    selectedBook.chapters.size
                                )
                                selectedVerseNumber = null
                            }
                            val goBack: () -> Unit = {
                                selectedChapterNumber = null
                                selectedVerseNumber = null
                            }

                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f)
                            ) {
                                Column(
                                    verticalArrangement = Arrangement.spacedBy(12.dp),
                                    modifier = Modifier
                                        .padding(if (compact) 0.dp else 16.dp)
                                        .fillMaxSize()
                                ) {
                                    Row(
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(
                                            text = "${selectedBook.name} ${chapter.chapter}",
                                            style = MaterialTheme.typography.titleLarge
                                        )
                                        Text(
                                            text = "← Chapters",
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.clickable {
                                                SoundManager.play(SoundEvent.Click)
                                                goBack()
                                            }
                                        )
                                    }

                                    ChapterJumpStrip(
                                        book = selectedBook,
                                        chapterNumber = chapter.chapter,
                                        onPrev = goPrev,
                                        onNext = goNext,
                                        onScrub = goScrub,
                                        onScrollToTop = {
                                            scope.launch {
                                                verseScrollState.scrollTo(0)
                                            }
                                        }
                                    )

                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .weight(1f),
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        Column(
                                            modifier = Modifier
                                                .weight(1f)
                                                .fillMaxHeight()
                                                .verticalScroll(verseScrollState),
                                            verticalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            chapter.verses.forEach { verse ->
                                                // Hover highlight adapts to the reference's granularity: a
                                                // verse ref highlights that single verse, a chapter ref
                                                // highlights every verse in the chapter, a book ref
                                                // highlights nothing (book-level hover has no verse
                                                // target to tint).
                                                val hovered = hoveredBibleReference
                                                val hoverHighlighted = hovered != null &&
                                                    hovered.book == selectedBook.name &&
                                                    when {
                                                        hovered.verse != null ->
                                                            hovered.chapter == chapter.chapter &&
                                                                hovered.verse == verse.verse

                                                        hovered.chapter != null ->
                                                            hovered.chapter == chapter.chapter

                                                        else -> false
                                                    }
                                                VerseRow(
                                                    bookName = selectedBook.name,
                                                    bookNumber = selectedBook.book,
                                                    chapterNumber = chapter.chapter,
                                                    verseNumber = verse.verse,
                                                    text = verse.luther1912,
                                                    selected = selectedVerseNumber == verse.verse,
                                                    hoverHighlighted = hoverHighlighted,
                                                    onOpenNoteTitle = onOpenNoteTitle,
                                                    onClick = {
                                                        SoundManager.play(SoundEvent.Click)
                                                        selectedVerseNumber = if (
                                                            selectedVerseNumber == verse.verse
                                                        ) {
                                                            null
                                                        } else {
                                                            verse.verse
                                                        }
                                                    },
                                                    onToggleMarker = { markerColor ->
                                                        SoundManager.play(SoundEvent.Click)
                                                        SettingsManager.setVerseMarkerColor(
                                                            selectedBook.book,
                                                            chapter.chapter,
                                                            verse.verse,
                                                            markerColor
                                                        )
                                                    },
                                                    onVersePositioned = { verseNum, y ->
                                                        // Mutate the raw map and bump a
                                                        // version counter so the parent
                                                        // LaunchedEffect re-fires without
                                                        // allocating a new stateful map on
                                                        // every callback.
                                                        verseYOffsetsMap[verseNum] = y
                                                        verseYOffsetsVersion++
                                                    }
                                                )
                                            }
                                        }

                                        if (verseScrollState.maxValue > 0) {
                                            VerticalScrollbar(
                                                adapter = rememberScrollbarAdapter(verseScrollState),
                                                modifier = Modifier.fillMaxHeight()
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                if (showBackButton) {
                    Text(
                        text = "Back",
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable {
                            SoundManager.play(SoundEvent.Click)
                            back()
                        }
                    )
                }
            }
        }
}


@Composable
private fun BookCard(
    book: Book,
    starred: Boolean,
    onOpen: () -> Unit,
    onToggleStar: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .dragAndDropSource {
                DragAndDropTransferData(
                    transferable = DragAndDropTransferable(
                        StringSelection(bookTag(book.name))
                    ),
                    supportedActions = listOf(DragAndDropTransferAction.Copy)
                )
            }
            .clickable { onOpen() }
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(14.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = book.name,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )

                StarGlyph(
                    starred = starred,
                    onClick = onToggleStar
                )
            }

            Text(
                text = "${book.chapters.size} chapters",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}


@Composable
private fun ChapterCard(
    book: Book,
    chapterNumber: Int,
    starred: Boolean,
    onOpen: () -> Unit,
    onToggleStar: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 100.dp)
            .dragAndDropSource {
                DragAndDropTransferData(
                    transferable = DragAndDropTransferable(
                        StringSelection(chapterTag(book.name, chapterNumber))
                    ),
                    supportedActions = listOf(DragAndDropTransferAction.Copy)
                )
            }
            .clickable { onOpen() }
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(14.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Ch. $chapterNumber",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )

                StarGlyph(
                    starred = starred,
                    onClick = onToggleStar
                )
            }

            Text(
                text = book.name,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}


@Composable
private fun ChapterJumpStrip(
    book: Book,
    chapterNumber: Int,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onScrub: (Int) -> Unit,
    onScrollToTop: () -> Unit
) {
    val totalChapters = book.chapters.size
    val hasNext = chapterNumber < totalChapters
    val hasPrev = chapterNumber > 1

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(
            modifier = Modifier
                .size(width = 72.dp, height = 44.dp)
                .clickable(enabled = hasPrev) { onPrev() },
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "← Prev",
                style = MaterialTheme.typography.titleSmall,
                color = if (hasPrev) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
                }
            )
        }

        // The chapter label doubles as a "Top" affordance — clicking it
        // scrolls the verses list back to the top of the active chapter.
        // Persistence happens automatically via the value-keyed LaunchedEffect
        // in BibleScreen (scrollOffsets["$book:$chapter"] updates on the
        // resulting value change).
        Text(
            text = "↑ Ch. $chapterNumber / $totalChapters",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .padding(horizontal = 4.dp)
                .clickable { onScrollToTop() }
        )

        Slider(
            value = chapterNumber.toFloat(),
            onValueChange = { v ->
                onScrub(v.toInt().coerceIn(1, totalChapters.coerceAtLeast(1)))
            },
            valueRange = 1f..totalChapters.toFloat().coerceAtLeast(1f),
            steps = (totalChapters - 2).coerceAtLeast(0),
            enabled = totalChapters > 1,
            modifier = Modifier.weight(1f)
        )

        Box(
            modifier = Modifier
                .size(width = 72.dp, height = 44.dp)
                .clickable(enabled = hasNext) { onNext() },
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "Next →",
                style = MaterialTheme.typography.titleSmall,
                color = if (hasNext) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
                }
            )
        }
    }
}


@Composable
private fun VerseRow(
    bookNumber: Int,
    bookName: String,
    chapterNumber: Int,
    verseNumber: Int,
    text: String,
    selected: Boolean,
    hoverHighlighted: Boolean = false,
    onOpenNoteTitle: (String, BibleReferenceSelection?) -> Unit,
    onClick: () -> Unit,
    onToggleMarker: (String?) -> Unit,
    onVersePositioned: (Int, Int) -> Unit = { _, _ -> }
) {
    var hovered by remember(bookNumber, chapterNumber, verseNumber) {
        mutableStateOf(false)
    }

    val noteTitles = NotesRepository.titlesForVerse(
        bookName,
        chapterNumber,
        verseNumber
    )
    val markerPreview = SettingsManager.getVerseMarkerColor(
        bookNumber,
        chapterNumber,
        verseNumber
    )

    val highlightColor = when {
        markerPreview != null && (selected || hoverHighlighted) -> colorFromHex(markerPreview).copy(alpha = 0.32f)
        markerPreview != null && hoverHighlighted -> colorFromHex(markerPreview).copy(alpha = 0.22f)
        markerPreview != null -> colorFromHex(markerPreview).copy(alpha = 0.18f)
        selected -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.8f)
        hoverHighlighted -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f)
        hovered -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f)
        else -> Color.Transparent
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        // Report this verse's Y position inside the scrolling Column so the
        // parent BibleScreen can scroll-to-verse when a tag-driven navigation
        // lands on this verse. The callback is a no-op for non-tracking
        // callers (e.g. when VerseRow is rendered outside the reading view).
        //
        // Compose 1.9 removed `LayoutCoordinates.positionInParent()`; the
        // replacement is `parentLayoutCoordinates.localPositionOf(this,
        // Offset.Zero)` which gives the same offset (position relative to
        // the immediate parent, here the scrolling verses Column).
        modifier = Modifier.onGloballyPositioned { coords ->
            val localY = coords.parentLayoutCoordinates
                ?.localPositionOf(coords, Offset.Zero)
                ?.y
                ?.toInt()
                ?: 0
            onVersePositioned(verseNumber, localY)
        }
    ) {
        Card(
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = highlightColor),
            modifier = Modifier
                .fillMaxWidth()
                .dragAndDropSource {
                    DragAndDropTransferData(
                        transferable = DragAndDropTransferable(
                            StringSelection(
                                verseTag(
                                    bookName = bookName,
                                    chapterNumber = chapterNumber,
                                    verseNumber = verseNumber
                                )
                            )
                        ),
                        supportedActions = listOf(DragAndDropTransferAction.Copy)
                    )
                }
                .pointerMoveFilter(
                    onEnter = {
                        hovered = true
                        false
                    },
                    onExit = {
                        hovered = false
                        false
                    }
                )
                .clickable { onClick() }
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(14.dp)
            ) {
                Card(
                    shape = RoundedCornerShape(10.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xFFFFC107).copy(alpha = 0.18f)
                    )
                ) {
                    Text(
                        text = verseNumber.toString(),
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelLarge,
                        color = Color(0xFFFFB300)
                    )
                }

                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodyMedium
                    )

                    if (noteTitles.isNotEmpty()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            noteTitles.forEach { title ->
                                NoteChip(
                                    text = title,
                                    onClick = {
                                        onOpenNoteTitle(
                                            title,
                                            BibleReferenceSelection(
                                                book = bookName,
                                                chapter = chapterNumber,
                                                verse = verseNumber
                                            )
                                        )
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }

        if (selected) {
            VerseMarkerPanel(
                bookName = bookName,
                bookNumber = bookNumber,
                chapterNumber = chapterNumber,
                verseNumber = verseNumber,
                onOpenNoteTitle = onOpenNoteTitle,
                onPickColor = onToggleMarker
            )
        }
    }
}


@Composable
private fun VerseMarkerPanel(
    bookName: String,
    bookNumber: Int,
    chapterNumber: Int,
    verseNumber: Int,
    onOpenNoteTitle: (String, BibleReferenceSelection?) -> Unit,
    onPickColor: (String?) -> Unit
) {
    val references = NotesRepository.referencesForVerse(bookName, chapterNumber, verseNumber)

    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(14.dp)
        ) {
            Text(
                text = "Referenced notes",
                style = MaterialTheme.typography.titleMedium
            )

            if (references.isEmpty()) {
                Text(
                    text = "No notes reference this verse.",
                    style = MaterialTheme.typography.bodySmall
                )
            } else {
                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    references.forEach { ref ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            NoteChip(
                                text = ref.noteTitle,
                                onClick = {
                                    onOpenNoteTitle(
                                        ref.noteTitle,
                                        BibleReferenceSelection(
                                            book = bookName,
                                            chapter = chapterNumber,
                                            verse = verseNumber
                                        )
                                    )
                                }
                            )
                            ref.label?.let {
                                Text(text = it)
                            }
                        }
                    }
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                MarkerColor.entries.forEach { marker ->
                    ColorDot(
                        color = marker.color,
                        onClick = {
                            SoundManager.play(SoundEvent.Click)
                            onPickColor(marker.hex)
                        }
                    )
                }

                ColorDot(
                    color = MaterialTheme.colorScheme.outline,
                    label = "×",
                    onClick = {
                        SoundManager.play(SoundEvent.Click)
                        onPickColor(null)
                    }
                )
            }
        }
    }
}


@Composable
private fun ColorDot(
    color: Color,
    label: String? = null,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(28.dp)
            .hoverable(remember { MutableInteractionSource() })
            .background(color.copy(alpha = 0.8f), CircleShape)
            .clickable {
                SoundManager.play(SoundEvent.Click)
                onClick()
            },
        contentAlignment = Alignment.Center
    ) {
        if (label != null) {
            Text(
                text = label,
                color = Color.White,
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}


@Composable
private fun NoteChip(
    text: String,
    onClick: () -> Unit
) {
    val hoverSource = remember { MutableInteractionSource() }
    val isHovered by hoverSource.collectIsHoveredAsState()
    LaunchedEffect(isHovered) {
        if (isHovered) {
            delay(60)
            SoundManager.play(SoundEvent.Hover)
        }
    }
    Card(
        shape = RoundedCornerShape(999.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        modifier = Modifier
            .hoverable(hoverSource)
            .clickable {
                SoundManager.play(SoundEvent.Click)
                onClick()
            }
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelSmall
        )
    }
}


@Composable
private fun StarGlyph(
    starred: Boolean,
    onClick: () -> Unit
) {
    val hoverSource = remember { MutableInteractionSource() }
    val isHovered by hoverSource.collectIsHoveredAsState()
    LaunchedEffect(isHovered) {
        if (isHovered) {
            delay(60)
            SoundManager.play(SoundEvent.Hover)
        }
    }
    Text(
        text = if (starred) "★" else "☆",
        color = Color(0xFFFFC107),
        style = MaterialTheme.typography.titleLarge,
        modifier = Modifier
            .hoverable(hoverSource)
            .clickable {
                SoundManager.play(SoundEvent.Click)
                onClick()
            }
    )
}


private fun colorFromHex(hex: String): Color {
    return runCatching {
        val cleaned = hex.removePrefix("#")
        val value = cleaned.toLong(16)
        when (cleaned.length) {
            6 -> Color(
                red = ((value shr 16) and 0xFF).toFloat() / 255f,
                green = ((value shr 8) and 0xFF).toFloat() / 255f,
                blue = (value and 0xFF).toFloat() / 255f
            )

            8 -> Color(
                alpha = ((value shr 24) and 0xFF).toFloat() / 255f,
                red = ((value shr 16) and 0xFF).toFloat() / 255f,
                green = ((value shr 8) and 0xFF).toFloat() / 255f,
                blue = (value and 0xFF).toFloat() / 255f
            )

            else -> Color(0xFF6750A4)
        }
    }.getOrElse {
        Color(0xFF6750A4)
    }
}


private fun bookTag(bookName: String): String {
    return "$" + bookName
}


private fun chapterTag(bookName: String, chapterNumber: Int): String {
    return "$" + bookName + "$" + chapterNumber
}


private fun verseTag(
    bookName: String,
    chapterNumber: Int,
    verseNumber: Int
): String {
    return "$" + bookName + "$" + chapterNumber + "$" + verseNumber
}


private fun responsiveColumns(
    maxWidth: Dp,
    minCardWidth: Dp
): Int {
    return (maxWidth / minCardWidth)
        .toInt()
        .coerceIn(1, 6)
}
