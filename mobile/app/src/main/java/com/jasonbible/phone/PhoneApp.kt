package com.jasonbible.phone

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val PAGE_FILES = 0
private const val PAGE_EDITOR = 1
private const val PAGE_BIBLE = 2

/** How long typing must pause before the open note is written to disk. */
private const val AUTO_SAVE_DELAY_MS = 600L


/**
 * The phone app: three pages laid out side by side — files, editor, Bible.
 * Swiping right on the editor (there is nothing further left but files)
 * opens the notes file list; swiping left opens the Bible. Both stay a
 * horizontal swipe away from the editor, matching how the desktop app's
 * split view reads left-to-right.
 */
@Composable
fun PhoneApp() {
    val context = LocalContext.current
    val store = remember { NotesStore(File(context.filesDir, "notes")) }
    val scope = rememberCoroutineScope()

    var notes by remember { mutableStateOf(store.list()) }
    var openFile by remember { mutableStateOf<File?>(null) }
    var editorText by remember { mutableStateOf("") }

    var translation by remember { mutableStateOf(TRANSLATIONS.first()) }
    var books by remember { mutableStateOf<List<Book>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var bookIndex by remember { mutableIntStateOf(0) }
    var chapterIndex by remember { mutableIntStateOf(0) }

    val pagerState = rememberPagerState(initialPage = PAGE_EDITOR) { 3 }

    LaunchedEffect(translation) {
        loading = true
        val loaded = withContext(Dispatchers.IO) {
            runCatching { BibleLibrary.books(context, translation) }.getOrDefault(emptyList())
        }
        books = loaded
        bookIndex = bookIndex.coerceIn(0, (loaded.size - 1).coerceAtLeast(0))
        chapterIndex = chapterIndex.coerceIn(
            0,
            (loaded.getOrNull(bookIndex)?.chapters?.size?.minus(1) ?: 0).coerceAtLeast(0)
        )
        loading = false
    }

    // Auto-save the open note once typing pauses. `editorText` lives above
    // the pager, so a page switch never loses a draft.
    LaunchedEffect(openFile, editorText) {
        val file = openFile ?: return@LaunchedEffect
        if (store.read(file) == editorText) return@LaunchedEffect
        delay(AUTO_SAVE_DELAY_MS)
        store.save(file, editorText)
        notes = store.list()
    }

    fun goToPage(page: Int) {
        scope.launch { pagerState.animateScrollToPage(page) }
    }

    fun openNote(file: File) {
        openFile = file
        editorText = store.read(file)
        goToPage(PAGE_EDITOR)
    }

    fun createNote() {
        val text = "# Untitled\n\n"
        val file = store.save(null, text)
        notes = store.list()
        openFile = file
        editorText = text
        goToPage(PAGE_EDITOR)
    }

    fun deleteNote(file: File) {
        store.delete(file)
        if (openFile == file) {
            openFile = null
            editorText = ""
        }
        notes = store.list()
    }

    Scaffold(
        topBar = {
            PhoneTopBar(
                page = pagerState.currentPage,
                translationLabel = translation.shortLabel,
                onPage = ::goToPage
            )
        }
    ) { padding ->
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.padding(padding).fillMaxSize(),
            beyondViewportPageCount = 1
        ) { page ->
            when (page) {
                PAGE_FILES -> FilesPane(
                    notes = notes,
                    selected = openFile,
                    onOpen = ::openNote,
                    onCreate = ::createNote,
                    onDelete = ::deleteNote
                )

                PAGE_EDITOR -> EditorPane(
                    text = editorText,
                    hasNote = openFile != null,
                    onChange = { editorText = it },
                    onNewNote = ::createNote
                )

                else -> BiblePane(
                    books = books,
                    loading = loading,
                    translation = translation,
                    onTranslationChange = { translation = it },
                    bookIndex = bookIndex,
                    chapterIndex = chapterIndex,
                    onBookChange = { index ->
                        bookIndex = index
                        chapterIndex = 0
                    },
                    onChapterChange = { chapterIndex = it }
                )
            }
        }
    }
}


@Composable
private fun PhoneTopBar(
    page: Int,
    translationLabel: String,
    onPage: (Int) -> Unit
) {
    Surface(tonalElevation = 3.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // The status bar sits above the app's content; without this
                // the tabs would be under it and taps would never reach them.
                .statusBarsPadding()
                .padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PageTab("Files", page == PAGE_FILES) { onPage(PAGE_FILES) }
            PageTab("Editor", page == PAGE_EDITOR) { onPage(PAGE_EDITOR) }
            PageTab("Bible", page == PAGE_BIBLE) { onPage(PAGE_BIBLE) }
            Spacer(Modifier.weight(1f))
            Text(
                text = translationLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 8.dp)
            )
        }
    }
}


@Composable
private fun PageTab(text: String, selected: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Text(
            text = text,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
    }
}


@Composable
private fun FilesPane(
    notes: List<NoteFile>,
    selected: File?,
    onOpen: (File) -> Unit,
    onCreate: () -> Unit,
    onDelete: (File) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Notes",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onCreate) { Text("+ New") }
        }
        Text(
            text = "Swipe left for the editor, then again for the Bible.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        if (notes.isEmpty()) {
            Text(
                text = "No notes yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            items(notes) { note ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpen(note.file) }
                        .padding(horizontal = 4.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = note.title,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (selected == note.file) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = "\u2715",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .clickable { onDelete(note.file) }
                            .padding(8.dp)
                    )
                }
            }
        }
    }
}


@Composable
private fun EditorPane(
    text: String,
    hasNote: Boolean,
    onChange: (String) -> Unit,
    onNewNote: () -> Unit
) {
    if (!hasNote) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(text = "No note open", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "Swipe right for your notes, or start a new one.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp)
            )
            Button(onClick = onNewNote) { Text("New note") }
        }
        return
    }

    OutlinedTextField(
        value = text,
        onValueChange = onChange,
        modifier = Modifier.fillMaxSize().padding(12.dp),
        textStyle = MaterialTheme.typography.bodyLarge,
        label = { Text("Note") }
    )
}


@Composable
private fun BiblePane(
    books: List<Book>,
    loading: Boolean,
    translation: Translation,
    onTranslationChange: (Translation) -> Unit,
    bookIndex: Int,
    chapterIndex: Int,
    onBookChange: (Int) -> Unit,
    onChapterChange: (Int) -> Unit
) {
    if (loading) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }
    if (books.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text(
                text = "No Bible translation could be loaded.",
                style = MaterialTheme.typography.bodyMedium
            )
        }
        return
    }

    val book = books[bookIndex.coerceIn(books.indices)]
    val chapterIdx = chapterIndex.coerceIn(book.chapters.indices)
    val chapter = book.chapters[chapterIdx]

    var bookMenu by remember { mutableStateOf(false) }
    var translationMenu by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box {
                TextButton(onClick = { bookMenu = true }) {
                    Text(
                        text = book.name,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.width(170.dp)
                    )
                    Text(" \u25be")
                }
                DropdownMenu(expanded = bookMenu, onDismissRequest = { bookMenu = false }) {
                    books.forEachIndexed { index, item ->
                        DropdownMenuItem(
                            text = { Text(item.name) },
                            onClick = {
                                onBookChange(index)
                                bookMenu = false
                            }
                        )
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            Box {
                TextButton(onClick = { translationMenu = true }) {
                    Text(translation.shortLabel)
                }
                DropdownMenu(
                    expanded = translationMenu,
                    onDismissRequest = { translationMenu = false }
                ) {
                    TRANSLATIONS.forEach { item ->
                        DropdownMenuItem(
                            text = { Text(item.label) },
                            onClick = {
                                onTranslationChange(item)
                                translationMenu = false
                            }
                        )
                    }
                }
            }
        }

        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = PaddingValues(horizontal = 12.dp)
        ) {
            items(book.chapters.size) { index ->
                FilterChip(
                    selected = index == chapterIdx,
                    onClick = { onChapterChange(index) },
                    label = { Text(book.chapters[index].number.toString()) }
                )
            }
        }

        HorizontalDivider(modifier = Modifier.padding(top = 6.dp))

        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Text(
                    text = "${book.name} ${chapter.number}",
                    style = MaterialTheme.typography.titleLarge
                )
            }
            items(chapter.verses) { verse ->
                Row {
                    Text(
                        text = verse.number.toString(),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.width(30.dp).padding(top = 3.dp)
                    )
                    Text(text = verse.text, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(
                enabled = chapterIdx > 0,
                onClick = { onChapterChange(chapterIdx - 1) }
            ) { Text("\u2039 Prev") }
            Spacer(Modifier.weight(1f))
            Text(
                text = "Chapter ${chapter.number}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.weight(1f))
            TextButton(
                enabled = chapterIdx < book.chapters.size - 1,
                onClick = { onChapterChange(chapterIdx + 1) }
            ) { Text("Next \u203a") }
        }
    }
}
