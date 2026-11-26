package com.jasonbible.phone

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import data.AppUpdater
import data.BibleCatalog
import data.BibleRepository
import data.NotesRepository
import data.SettingsManager
import data.SoundEvent
import data.SoundManager
import data.openExternalUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import model.Book
import model.ParsedNote
import ui.BibleReferenceSelection
import ui.BibleScreen
import ui.CollectionsScreen
import ui.GlobalSearchOverlay
import ui.EmbeddedPlayer
import ui.LexiconScreen
import ui.NotesScreen
import ui.SettingsScreen
import ui.SplashScreen
import ui.rememberAppSearchState
import ui.StatisticsScreen
import ui.VerseCompareScreen
import platform.AppPlatform


/**
 * The phone edition of the app.
 *
 * The desktop's side-by-side Bible + editor becomes ONE horizontal pager.
 * Swiping left/right moves between:
 *
 *     Books  ⇄  Chapters  ⇄  BIBLE  ⇄  EDITOR  ⇄  All notes
 *
 * i.e. swiping past the Bible continues into the chapter list and then the
 * book list, and swiping past the editor reveals the notes library — the
 * exact navigation the phone layout is meant to have. Everything else
 * (theme, screens, editors, exports, media, settings) is the desktop code.
 */
@Composable
fun PhoneApp() {
    // ---- Startup: warm the catalog + settings off the UI thread ----
    var progress by remember { mutableStateOf(0f) }
    var ready by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            SettingsManager.darkMode
            BibleCatalog.normalizeSavedTranslation()
            BibleCatalog.entries
            BibleCatalog.nameToBookNumber
            NotesRepository.notes
        }
        progress = 0.75f
        withContext(Dispatchers.IO) { BibleRepository.loadBooks() }
        SoundManager.init()
        progress = 1f
        SoundManager.play(SoundEvent.Boot)
        ready = true
    }
    if (!ready) {
        SplashScreen(progress = progress)
        return
    }

    val pages = PhonePage.entries
    val pager = rememberPagerState(initialPage = PhonePage.BIBLE.ordinal) { pages.size }
    val scope = rememberCoroutineScope()
    val appSearch = rememberAppSearchState()

    // ---- Shared Bible location (drives Bible / Chapters / Books) ----
    var books by remember { mutableStateOf<List<Book>>(emptyList()) }
    var bookIndex by remember { mutableIntStateOf(0) }
    var chapterIndex by remember { mutableIntStateOf(0) }
    var bibleJumpKey by remember { mutableIntStateOf(0) }
    var bibleReference by remember { mutableStateOf<BibleReferenceSelection?>(null) }

    LaunchedEffect(SettingsManager.translation) {
        books = withContext(Dispatchers.IO) {
            runCatching { BibleRepository.loadBooks() }.getOrDefault(emptyList())
        }
        bookIndex = bookIndex.coerceIn(0, (books.size - 1).coerceAtLeast(0))
        chapterIndex = chapterIndex.coerceIn(
            0,
            (books.getOrNull(bookIndex)?.chapters?.size?.minus(1) ?: 0).coerceAtLeast(0)
        )
    }

    val currentBook = books.getOrNull(bookIndex)

    fun goTo(page: PhonePage) {
        scope.launch { pager.animateScrollToPage(page.ordinal) }
    }

    fun openBible(reference: BibleReferenceSelection) {
        bibleReference = reference
        bibleJumpKey++
        val index = books.indexOfFirst { it.name.equals(reference.book, ignoreCase = true) }
        if (index >= 0) {
            bookIndex = index
            val chapter = reference.chapter
            chapterIndex = if (chapter == null) 0 else {
                books[index].chapters.indexOfFirst { it.chapter == chapter }
                    .coerceAtLeast(0)
            }
        }
        goTo(PhonePage.BIBLE)
    }

    // ---- Editor / notes state ----
    var selectedNoteFile by remember { mutableStateOf<String?>(null) }
    var notes by remember { mutableStateOf<List<ParsedNote>>(emptyList()) }
    fun refreshNotes() {
        notes = runCatching { NotesRepository.listFiles() }.getOrDefault(emptyList())
    }
    LaunchedEffect(Unit) { refreshNotes() }

    fun openNote(fileName: String) {
        selectedNoteFile = fileName
        refreshNotes()
        goTo(PhonePage.EDITOR)
    }

    fun newNote() {
        val note = NotesRepository.createNote()
        refreshNotes()
        selectedNoteFile = note.fileName
        goTo(PhonePage.EDITOR)
    }

    // ---- Full-screen overlays from the desktop main menu ----
    var overlay by remember { mutableStateOf<PhoneOverlay?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var compareReference by remember { mutableStateOf<BibleReferenceSelection?>(null) }
    var lexiconQuery by remember { mutableStateOf<String?>(null) }

    BackHandler(enabled = overlay != null || pager.currentPage != PhonePage.BIBLE.ordinal) {
        when {
            overlay != null -> overlay = null
            pager.currentPage > PhonePage.BIBLE.ordinal ->
                scope.launch { pager.animateScrollToPage(pager.currentPage - 1) }
            else -> scope.launch { pager.animateScrollToPage(pager.currentPage + 1) }
        }
    }

    Box(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
        Column(modifier = Modifier.fillMaxSize()) {
            PhoneTopBar(
                page = pages[pager.currentPage],
                onSearch = { appSearch.openSearch() },
                onMenu = { menuOpen = true },
                menuOpen = menuOpen,
                onDismissMenu = { menuOpen = false },
                onNotes = { goTo(PhonePage.NOTES) },
                onSettings = { overlay = PhoneOverlay.SETTINGS },
                onStatistics = { overlay = PhoneOverlay.STATISTICS },
                onCollections = { overlay = PhoneOverlay.COLLECTIONS },
                onLexicon = {
                    lexiconQuery = null
                    overlay = PhoneOverlay.LEXICON
                },
                onCompare = {
                    compareReference = currentBook?.let { book ->
                        BibleReferenceSelection(
                            book = book.name,
                            chapter = book.chapters.getOrNull(chapterIndex)?.chapter,
                            verse = 1
                        )
                    }
                    overlay = PhoneOverlay.COMPARE
                },
                onCheckUpdates = {
                    scope.launch {
                        val release = runCatching { AppUpdater.checkForUpdate() }.getOrNull()
                        if (release == null) {
                            AppPlatform.toast("BibleApp is up to date.")
                        } else {
                            val started = runCatching {
                                AppUpdater.installAndRestart(release)
                            }.getOrDefault(false)
                            if (!started) openExternalUrl(release.pageUrl)
                        }
                    }
                }
            )
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
            )

            HorizontalPager(
                state = pager,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                beyondViewportPageCount = 1
            ) { index ->
                when (pages[index]) {
                    PhonePage.BOOKS -> BookListPage(
                        books = books,
                        onPick = { indexInBible ->
                            bookIndex = indexInBible
                            chapterIndex = 0
                            goTo(PhonePage.CHAPTERS)
                        }
                    )

                    PhonePage.CHAPTERS -> ChapterListPage(
                        book = currentBook,
                        onPick = { picked ->
                            chapterIndex = picked
                            currentBook?.let { book ->
                                val chapter = book.chapters.getOrNull(picked)
                                if (chapter != null) {
                                    bibleReference = BibleReferenceSelection(
                                        book = book.name,
                                        chapter = chapter.chapter,
                                        verse = 1
                                    )
                                }
                            }
                            bibleJumpKey++
                            goTo(PhonePage.BIBLE)
                        }
                    )

                    PhonePage.BIBLE -> key(bibleJumpKey) {
                        BibleScreen(
                            back = {},
                            initialReference = bibleReference,
                            showBackButton = false,
                            compact = false,
                            onOpenNoteTitle = { title, _ ->
                                NotesRepository.findByTitle(title)?.let { note ->
                                    openNote(note.fileName)
                                }
                            },
                            onOpenLexicon = { number ->
                                lexiconQuery = number
                                overlay = PhoneOverlay.LEXICON
                            },
                            onOpenCompare = { bookName, chapter, verse ->
                                compareReference = BibleReferenceSelection(
                                    book = bookName,
                                    chapter = chapter,
                                    verse = verse
                                )
                                overlay = PhoneOverlay.COMPARE
                            }
                        )
                    }

                    PhonePage.EDITOR -> NotesScreen(
                        back = {},
                        selectedFileName = selectedNoteFile,
                        showBackButton = false,
                        compact = true,
                        onOpenBibleReference = { reference -> openBible(reference) },
                        onSelectedNoteChange = { fileName ->
                            selectedNoteFile = fileName
                            refreshNotes()
                        }
                    )

                    PhonePage.NOTES -> NotesLibraryPage(
                        notes = notes,
                        selectedFileName = selectedNoteFile,
                        onOpen = { openNote(it) },
                        onCreate = { newNote() },
                        onDelete = { note ->
                            NotesRepository.deleteNote(note.fileName)
                            if (selectedNoteFile == note.fileName) selectedNoteFile = null
                            refreshNotes()
                        }
                    )
                }
            }

            PhonePageIndicator(
                current = pager.currentPage,
                pages = pages,
                onSelect = { goTo(it) }
            )
        }

        // The desktop's embedded player floats above every screen.
        EmbeddedPlayer(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(end = 12.dp, bottom = 56.dp)
        )

        if (appSearch.open) {
            GlobalSearchOverlay(
                state = appSearch,
                onOpenReference = { reference ->
                    appSearch.close()
                    openBible(reference)
                },
                onOpenNoteHit = { hit ->
                    appSearch.close()
                    openNote(hit.note.fileName)
                },
                onOpenSettings = {
                    appSearch.close()
                    overlay = PhoneOverlay.SETTINGS
                }
            )
        }

        overlay?.let { active ->
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background
            ) {
                when (active) {
                    PhoneOverlay.SETTINGS -> SettingsScreen(back = { overlay = null })
                    PhoneOverlay.STATISTICS -> StatisticsScreen(back = { overlay = null })
                    PhoneOverlay.COLLECTIONS -> CollectionsScreen(
                        back = { overlay = null },
                        onOpenVerse = { bookName, chapter, verse ->
                            overlay = null
                            openBible(
                                BibleReferenceSelection(bookName, chapter, verse)
                            )
                        }
                    )
                    PhoneOverlay.LEXICON -> LexiconScreen(
                        initialQuery = lexiconQuery,
                        onInitialQueryConsumed = { lexiconQuery = null },
                        onOpenVerse = { book, chapter, verse ->
                            overlay = null
                            openBible(BibleReferenceSelection(book, chapter, verse))
                        },
                        onBack = { overlay = null }
                    )
                    PhoneOverlay.COMPARE -> VerseCompareScreen(
                        initialReference = compareReference,
                        back = { overlay = null }
                    )
                }
            }
        }
    }

}


private enum class PhoneOverlay { SETTINGS, STATISTICS, COLLECTIONS, LEXICON, COMPARE }


/** Pager order: swiping left from the Bible reveals chapters, then books;
 *  swiping right from the editor reveals the whole notes library. */
internal enum class PhonePage(val label: String) {
    BOOKS("Books"),
    CHAPTERS("Chapters"),
    BIBLE("Bible"),
    EDITOR("Editor"),
    NOTES("Notes")
}


@Composable
private fun PhoneTopBar(
    page: PhonePage,
    onSearch: () -> Unit,
    menuOpen: Boolean,
    onMenu: () -> Unit,
    onDismissMenu: () -> Unit,
    onNotes: () -> Unit,
    onSettings: () -> Unit,
    onStatistics: () -> Unit,
    onCollections: () -> Unit,
    onLexicon: () -> Unit,
    onCompare: () -> Unit,
    onCheckUpdates: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = page.label,
            style = MaterialTheme.typography.titleMedium.copy(
                fontFamily = FontFamily.Serif
            ),
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(horizontal = 8.dp)
        )
        Spacer(Modifier.weight(1f))

        TextButton(onClick = onSearch) {
            Text("Search", color = MaterialTheme.colorScheme.primary, fontSize = 13.sp)
        }
        TextButton(onClick = onNotes) {
            Text("Notes", color = MaterialTheme.colorScheme.primary, fontSize = 13.sp)
        }

        Box {
            TextButton(onClick = onMenu) {
                Text("\u2630", color = MaterialTheme.colorScheme.primary, fontSize = 18.sp)
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = onDismissMenu) {
                DropdownMenuItem(
                    text = { Text("Settings") },
                    onClick = { onDismissMenu(); onSettings() }
                )
                DropdownMenuItem(
                    text = { Text("Reading statistics") },
                    onClick = { onDismissMenu(); onStatistics() }
                )
                DropdownMenuItem(
                    text = { Text("Collections") },
                    onClick = { onDismissMenu(); onCollections() }
                )
                DropdownMenuItem(
                    text = { Text("Word study") },
                    onClick = { onDismissMenu(); onLexicon() }
                )
                DropdownMenuItem(
                    text = { Text("Compare translations") },
                    onClick = { onDismissMenu(); onCompare() }
                )
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text("Check for updates") },
                    onClick = { onDismissMenu(); onCheckUpdates() }
                )
            }
        }
    }
}


@Composable
private fun PhonePageIndicator(
    current: Int,
    pages: List<PhonePage>,
    onSelect: (PhonePage) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        pages.forEachIndexed { index, page ->
            Box(
                modifier = Modifier
                    .padding(horizontal = 5.dp)
                    .size(if (index == current) 9.dp else 7.dp)
                    .clip(CircleShape)
                    .background(
                        if (index == current) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.outlineVariant
                        }
                    )
                    .clickable { onSelect(page) }
            )
        }
    }
}


@Composable
private fun BookListPage(
    books: List<Book>,
    onPick: (Int) -> Unit
) {
    if (books.isEmpty()) {
        EmptyPage("Loading the Bible…")
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        item {
            PageHeading("Old Testament")
        }
        items(books.take(39).mapIndexed { index, book -> index to book }) { (index, book) ->
            BookRow(book, onPick = { onPick(index) })
        }
        item {
            Spacer(Modifier.height(16.dp))
            PageHeading("New Testament")
        }
        items(
            books.drop(39).mapIndexed { offset, book -> (offset + 39) to book }
        ) { (index, book) ->
            BookRow(book, onPick = { onPick(index) })
        }
    }
}


@Composable
private fun BookRow(book: Book, onPick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onPick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = book.name,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = "${book.chapters.size}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}


@Composable
private fun ChapterListPage(
    book: Book?,
    onPick: (Int) -> Unit
) {
    if (book == null) {
        EmptyPage("Pick a book first.")
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 68.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
            PageHeading(book.name)
        }
        items(book.chapters.size) { index ->
            val chapter = book.chapters[index]
            Box(
                modifier = Modifier
                    .height(52.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    .clickable { onPick(index) },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = chapter.chapter.toString(),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}


@Composable
private fun NotesLibraryPage(
    notes: List<ParsedNote>,
    selectedFileName: String?,
    onOpen: (String) -> Unit,
    onCreate: () -> Unit,
    onDelete: (ParsedNote) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "${notes.size} note${if (notes.size == 1) "" else "s"}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onCreate) { Text("+ New note") }
        }
        if (notes.isEmpty()) {
            EmptyPage("No notes yet. Tap + New note to start one.")
            return
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            items(notes, key = { it.fileName }) { note ->
                val selected = note.fileName == selectedFileName
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            if (selected) {
                                MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f)
                            } else {
                                androidx.compose.ui.graphics.Color.Transparent
                            }
                        )
                        .clickable { onOpen(note.fileName) }
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = note.title.ifBlank { note.fileName.removeSuffix(".note") },
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onBackground,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (note.folder.isNotBlank()) {
                            Text(
                                text = note.folder,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Text(
                        text = "\\u2715",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .clickable { onDelete(note) }
                            .padding(8.dp)
                    )
                }
            }
        }
    }
}


@Composable
private fun PageHeading(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.6.sp),
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
    )
}


@Composable
private fun EmptyPage(message: String) {
    Box(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}
