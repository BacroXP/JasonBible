package com.jasonbible.phone

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class Screen { Home, Bible, Notes, Settings }

private data class StudyProgress(
    val oldTestament: Int,
    val newTestament: Int,
    val wholeBible: Int
)

/** How long typing must pause before the open note is written to disk. */
private const val AUTO_SAVE_DELAY_MS = 600L

private const val DAILY_VERSE =
    "Thy word is a lamp unto my feet, and a light unto my path."
private const val DAILY_REFERENCE = "Psalm 119:105 (KJV)"

private class ReadingProgress(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("reading-progress", Context.MODE_PRIVATE)

    fun read(): Set<String> =
        prefs.getStringSet("chapters", emptySet())?.toSet().orEmpty()

    fun add(key: String): Set<String> {
        val updated = read() + key
        prefs.edit().putStringSet("chapters", updated).apply()
        return updated
    }
}

/**
 * The phone app opens on a single main menu: Bible, Notes, Settings and Quit,
 * with a daily verse and reading progress. The old three-page pager is gone.
 */
@Composable
fun PhoneApp() {
    var splashVisible by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        delay(1800)
        splashVisible = false
    }
    if (splashVisible) {
        SplashScreen()
        return
    }

    val context = LocalContext.current
    val activity = context as? Activity
    val store = remember { NotesStore(File(context.filesDir, "notes")) }
    val reading = remember { ReadingProgress(context) }

    var screen by remember { mutableStateOf(Screen.Home) }

    var notes by remember { mutableStateOf(store.list()) }
    var openFile by remember { mutableStateOf<File?>(null) }
    var editorText by remember { mutableStateOf("") }

    var translation by remember { mutableStateOf(TRANSLATIONS.first()) }
    var books by remember { mutableStateOf<List<Book>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var bookIndex by remember { mutableIntStateOf(0) }
    var chapterIndex by remember { mutableIntStateOf(0) }
    var readChapters by remember { mutableStateOf(reading.read()) }

    LaunchedEffect(translation) {
        loading = true
        val loaded = withContext(Dispatchers.IO) {
            runCatching { BibleLibrary.books(context, translation) }
                .getOrDefault(emptyList())
        }
        books = loaded
        bookIndex = bookIndex.coerceIn(0, (loaded.size - 1).coerceAtLeast(0))
        chapterIndex = chapterIndex.coerceIn(
            0,
            (loaded.getOrNull(bookIndex)?.chapters?.size?.minus(1) ?: 0).coerceAtLeast(0)
        )
        loading = false
    }

    // Auto-save the open note once typing pauses.
    LaunchedEffect(openFile, editorText) {
        val file = openFile ?: return@LaunchedEffect
        if (store.read(file) == editorText) return@LaunchedEffect
        delay(AUTO_SAVE_DELAY_MS)
        store.save(file, editorText)
        notes = store.list()
    }

    fun openNote(file: File) {
        openFile = file
        editorText = store.read(file)
    }

    fun createNote() {
        val text = "# Untitled\n\n"
        val file = store.save(null, text)
        notes = store.list()
        openFile = file
        editorText = text
        screen = Screen.Notes
    }

    fun closeNote() {
        openFile?.let { file ->
            store.save(file, editorText)
            notes = store.list()
        }
        openFile = null
        editorText = ""
    }

    fun deleteNote(file: File) {
        store.delete(file)
        if (openFile == file) {
            openFile = null
            editorText = ""
        }
        notes = store.list()
    }

    fun openPsalm119() {
        val index = books.indexOfFirst { it.name.contains("Psalm", ignoreCase = true) }
        if (index < 0) return
        val target = books[index].chapters.indexOfFirst { it.number == 119 }
        if (target < 0) return
        bookIndex = index
        chapterIndex = target
        screen = Screen.Bible
    }

    BackHandler(enabled = screen != Screen.Home) {
        when {
            screen == Screen.Notes && openFile != null -> closeNote()
            else -> screen = Screen.Home
        }
    }

    when (screen) {
        Screen.Home -> HomeScreen(
            progress = studyProgress(books, readChapters),
            onBible = { screen = Screen.Bible },
            onNotes = {
                openFile = null
                editorText = ""
                screen = Screen.Notes
            },
            onSettings = { screen = Screen.Settings },
            onQuit = { activity?.finishAffinity() },
            onDailyVerse = ::openPsalm119
        )

        Screen.Bible -> BibleScreen(
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
            onChapterChange = { chapterIndex = it },
            onBack = { screen = Screen.Home },
            onChapterRead = { book, chapter ->
                val key = "${book.number}:${chapter.number}"
                if (key !in readChapters) readChapters = reading.add(key)
            }
        )

        Screen.Notes -> NotesScreen(
            notes = notes,
            openFile = openFile,
            editorText = editorText,
            onOpen = ::openNote,
            onCreate = ::createNote,
            onDelete = ::deleteNote,
            onChange = { editorText = it },
            onBack = { screen = Screen.Home },
            onCloseNote = ::closeNote
        )

        Screen.Settings -> SettingsScreen(
            translation = translation,
            onTranslationChange = { translation = it },
            onBack = { screen = Screen.Home }
        )
    }
}

private fun studyProgress(books: List<Book>, read: Set<String>): StudyProgress {
    if (books.isEmpty()) return StudyProgress(0, 0, 0)

    fun chaptersIn(books: List<Book>) = books.sumOf { it.chapters.size }
    fun readIn(books: List<Book>) = books.sumOf { book ->
        book.chapters.count { "${book.number}:${it.number}" in read }
    }
    fun percent(read: Int, total: Int) =
        if (total == 0) 0 else (read * 100f / total).roundToInt()

    val old = books.filter { it.number <= 39 }
    val new = books.filter { it.number > 39 }
    val oldRead = readIn(old)
    val newRead = readIn(new)
    return StudyProgress(
        oldTestament = percent(oldRead, chaptersIn(old)),
        newTestament = percent(newRead, chaptersIn(new)),
        wholeBible = percent(oldRead + newRead, chaptersIn(books))
    )
}

@Composable
private fun SplashScreen() {
    val logoAlpha = remember { Animatable(0f) }
    val logoScale = remember { Animatable(0.72f) }
    val titleAlpha = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        launch { logoAlpha.animateTo(1f, tween(durationMillis = 500)) }
        launch {
            titleAlpha.animateTo(
                1f,
                tween(durationMillis = 700, delayMillis = 250, easing = FastOutSlowInEasing)
            )
        }
        logoScale.animateTo(
            1f,
            tween(durationMillis = 850, easing = FastOutSlowInEasing)
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Image(
                painter = painterResource(R.drawable.logo),
                contentDescription = "JasonBible",
                modifier = Modifier
                    .size(148.dp)
                    .graphicsLayer {
                        scaleX = logoScale.value
                        scaleY = logoScale.value
                        alpha = logoAlpha.value
                    }
            )
            Spacer(Modifier.height(24.dp))
            Text(
                text = "HOLY BIBLE",
                color = MaterialTheme.colorScheme.primary,
                fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.Bold,
                fontSize = 28.sp,
                letterSpacing = 3.sp,
                modifier = Modifier.graphicsLayer { alpha = titleAlpha.value }
            )
        }
        CircularProgressIndicator(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 84.dp)
                .size(22.dp),
            color = MaterialTheme.colorScheme.primary,
            strokeWidth = 2.dp
        )
    }
}

@Composable
private fun HomeScreen(
    progress: StudyProgress,
    onBible: () -> Unit,
    onNotes: () -> Unit,
    onSettings: () -> Unit,
    onQuit: () -> Unit,
    onDailyVerse: () -> Unit
) {
    val gold = MaterialTheme.colorScheme.primary
    val cream = MaterialTheme.colorScheme.onBackground

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(8.dp))
        CrossMark(
            color = gold,
            modifier = Modifier.width(24.dp).height(34.dp)
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = "HOLY BIBLE",
            color = gold,
            fontFamily = FontFamily.Serif,
            fontWeight = FontWeight.Bold,
            fontSize = 33.sp,
            letterSpacing = 3.sp
        )
        Spacer(Modifier.height(26.dp))

        DailyVerseCard(onClick = onDailyVerse)
        Spacer(Modifier.height(24.dp))

        MenuButton("Bible", filled = true, icon = { BookIcon(it) }, onClick = onBible)
        Spacer(Modifier.height(12.dp))
        MenuButton("Notes", filled = false, icon = { PencilIcon(it) }, onClick = onNotes)
        Spacer(Modifier.height(12.dp))
        MenuButton("Settings", filled = false, icon = { SlidersIcon(it) }, onClick = onSettings)
        Spacer(Modifier.height(12.dp))
        MenuButton("Quit", filled = false, icon = { ExitIcon(it) }, onClick = onQuit)

        Spacer(Modifier.height(30.dp))
        Text(
            text = "STUDY PROGRESS",
            color = gold,
            fontSize = 11.sp,
            letterSpacing = 2.5.sp,
            fontWeight = FontWeight.Medium
        )
        Spacer(Modifier.height(18.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            ProgressRing(progress.oldTestament, "Old Testament", Modifier.weight(1f))
            ProgressRing(progress.newTestament, "New Testament", Modifier.weight(1f))
            ProgressRing(progress.wholeBible, "Whole Bible", Modifier.weight(1f))
        }
        Spacer(Modifier.height(20.dp))
        Text(
            text = "Read a chapter to grow your progress.",
            color = cream.copy(alpha = 0.55f),
            fontSize = 12.sp,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun DailyVerseCard(onClick: () -> Unit) {
    val gold = MaterialTheme.colorScheme.primary
    val cream = MaterialTheme.colorScheme.onBackground
    val shape = RoundedCornerShape(16.dp)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .border(1.dp, gold.copy(alpha = 0.8f), shape)
            .padding(horizontal = 18.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "DAILY VERSE",
            color = gold,
            fontSize = 10.sp,
            letterSpacing = 2.5.sp,
            fontWeight = FontWeight.Medium
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = "\u201C$DAILY_VERSE\u201D",
            color = cream,
            fontFamily = FontFamily.Serif,
            fontStyle = FontStyle.Italic,
            fontSize = 19.sp,
            lineHeight = 27.sp,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(14.dp))
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .clickable(onClick = onClick)
                .padding(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = DAILY_REFERENCE,
                color = gold,
                fontFamily = FontFamily.Serif,
                fontSize = 13.sp,
                textDecoration = TextDecoration.Underline
            )
            Text(
                text = "  \u2192",
                color = gold,
                fontFamily = FontFamily.Serif,
                fontSize = 13.sp
            )
        }
    }
}

@Composable
private fun MenuButton(
    label: String,
    filled: Boolean,
    icon: @Composable (Color) -> Unit,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(12.dp)
    val gold = MaterialTheme.colorScheme.primary
    val contentColor = if (filled) MaterialTheme.colorScheme.onPrimary else gold
    val background = if (filled) gold else Color.Transparent
    val borderColor = if (filled) Color.Transparent else gold.copy(alpha = 0.75f)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(60.dp)
            .clip(shape)
            .background(background)
            .border(1.dp, borderColor, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        icon(contentColor)
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
private fun ProgressRing(percent: Int, label: String, modifier: Modifier = Modifier) {
    val gold = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.surfaceVariant
    val cream = MaterialTheme.colorScheme.onBackground

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier.size(82.dp),
            contentAlignment = Alignment.Center
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val stroke = 6.dp.toPx()
                drawArc(
                    color = track,
                    startAngle = -90f,
                    sweepAngle = 360f,
                    useCenter = false,
                    style = Stroke(width = stroke, cap = StrokeCap.Round)
                )
                if (percent > 0) {
                    drawArc(
                        color = gold,
                        startAngle = -90f,
                        sweepAngle = 360f * percent / 100f,
                        useCenter = false,
                        style = Stroke(width = stroke, cap = StrokeCap.Round)
                    )
                }
            }
            Text(
                text = "$percent%",
                color = cream,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = label,
            color = cream,
            fontFamily = FontFamily.Serif,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun ScreenHeader(
    title: String,
    onBack: () -> Unit,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextButton(onClick = onBack) {
            Text(
                text = "\u2039",
                color = MaterialTheme.colorScheme.primary,
                fontSize = 28.sp,
                lineHeight = 28.sp
            )
        }
        Text(
            text = title,
            color = MaterialTheme.colorScheme.onBackground,
            fontFamily = FontFamily.Serif,
            fontSize = 22.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f)
        )
        if (actionLabel != null && onAction != null) {
            TextButton(onClick = onAction) {
                Text(actionLabel, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun BibleScreen(
    books: List<Book>,
    loading: Boolean,
    translation: Translation,
    onTranslationChange: (Translation) -> Unit,
    bookIndex: Int,
    chapterIndex: Int,
    onBookChange: (Int) -> Unit,
    onChapterChange: (Int) -> Unit,
    onBack: () -> Unit,
    onChapterRead: (Book, Chapter) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().statusBarsPadding()
    ) {
        ScreenHeader(title = "Bible", onBack = onBack)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            BiblePane(
                books = books,
                loading = loading,
                translation = translation,
                onTranslationChange = onTranslationChange,
                bookIndex = bookIndex,
                chapterIndex = chapterIndex,
                onBookChange = onBookChange,
                onChapterChange = onChapterChange
            )
        }
    }

    LaunchedEffect(books, bookIndex, chapterIndex) {
        val book = books.getOrNull(bookIndex) ?: return@LaunchedEffect
        val chapter = book.chapters.getOrNull(chapterIndex) ?: return@LaunchedEffect
        onChapterRead(book, chapter)
    }
}

@Composable
private fun NotesScreen(
    notes: List<NoteFile>,
    openFile: File?,
    editorText: String,
    onOpen: (File) -> Unit,
    onCreate: () -> Unit,
    onDelete: (File) -> Unit,
    onChange: (String) -> Unit,
    onBack: () -> Unit,
    onCloseNote: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().statusBarsPadding()
    ) {
        ScreenHeader(
            title = if (openFile == null) "Notes" else "Note",
            onBack = if (openFile == null) onBack else onCloseNote,
            actionLabel = if (openFile == null) "+ New" else null,
            onAction = if (openFile == null) onCreate else null
        )

        if (openFile != null) {
            OutlinedTextField(
                value = editorText,
                onValueChange = onChange,
                modifier = Modifier.fillMaxSize().padding(16.dp),
                textStyle = MaterialTheme.typography.bodyLarge,
                label = { Text("Note") }
            )
        } else if (notes.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No notes yet. Tap + New to start one.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                items(notes) { note ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { onOpen(note.file) }
                            .padding(horizontal = 12.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = note.title,
                            color = MaterialTheme.colorScheme.onBackground,
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = "\u2715",
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
}

@Composable
private fun SettingsScreen(
    translation: Translation,
    onTranslationChange: (Translation) -> Unit,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().statusBarsPadding()
    ) {
        ScreenHeader(title = "Settings", onBack = onBack)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Bible translation",
                color = MaterialTheme.colorScheme.onBackground,
                fontFamily = FontFamily.Serif,
                fontSize = 20.sp,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = "Choose the text shown in the Bible tab.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp
            )

            TRANSLATIONS.forEach { item ->
                val selected = item.id == translation.id
                val shape = RoundedCornerShape(12.dp)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(shape)
                        .border(
                            1.dp,
                            if (selected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.outline
                            },
                            shape
                        )
                        .clickable { onTranslationChange(item) }
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = selected,
                        onClick = { onTranslationChange(item) },
                        colors = RadioButtonDefaults.colors(
                            selectedColor = MaterialTheme.colorScheme.primary,
                            unselectedColor = MaterialTheme.colorScheme.outline
                        )
                    )
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(
                            text = item.label,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Text(
                            text = item.shortLabel,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            Text(
                text = "About",
                color = MaterialTheme.colorScheme.onBackground,
                fontFamily = FontFamily.Serif,
                fontSize = 20.sp,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = "JasonBible Phone \u00b7 3.0.0-phone",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp
            )
            Text(
                text = "Offline KJV and Luther 1912. Notes stay on this device.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp
            )
        }
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

@Composable
private fun BookIcon(color: Color) {
    Canvas(Modifier.size(24.dp)) {
        val w = size.width
        val h = size.height
        val stroke = 1.8.dp.toPx()
        val left = Path().apply {
            moveTo(w * 0.08f, h * 0.24f)
            quadraticBezierTo(w * 0.28f, h * 0.13f, w * 0.48f, h * 0.26f)
            lineTo(w * 0.48f, h * 0.9f)
            quadraticBezierTo(w * 0.28f, h * 0.78f, w * 0.08f, h * 0.86f)
            close()
        }
        val right = Path().apply {
            moveTo(w * 0.52f, h * 0.26f)
            quadraticBezierTo(w * 0.72f, h * 0.13f, w * 0.92f, h * 0.24f)
            lineTo(w * 0.92f, h * 0.86f)
            quadraticBezierTo(w * 0.72f, h * 0.78f, w * 0.52f, h * 0.9f)
            close()
        }
        drawPath(left, color, style = Stroke(stroke, cap = StrokeCap.Round))
        drawPath(right, color, style = Stroke(stroke, cap = StrokeCap.Round))
        drawLine(
            color = color,
            start = Offset(w * 0.5f, h * 0.26f),
            end = Offset(w * 0.5f, h * 0.9f),
            strokeWidth = stroke,
            cap = StrokeCap.Round
        )
    }
}

@Composable
private fun PencilIcon(color: Color) {
    Canvas(Modifier.size(24.dp)) {
        val w = size.width
        val h = size.height
        val stroke = 1.8.dp.toPx()
        rotate(degrees = -45f) {
            drawRoundRect(
                color = color,
                topLeft = Offset(w * 0.42f, h * 0.08f),
                size = Size(w * 0.16f, h * 0.56f),
                cornerRadius = CornerRadius(w * 0.08f),
                style = Stroke(stroke)
            )
            drawLine(
                color = color,
                start = Offset(w * 0.42f, h * 0.64f),
                end = Offset(w * 0.5f, h * 0.9f),
                strokeWidth = stroke,
                cap = StrokeCap.Round
            )
            drawLine(
                color = color,
                start = Offset(w * 0.58f, h * 0.64f),
                end = Offset(w * 0.5f, h * 0.9f),
                strokeWidth = stroke,
                cap = StrokeCap.Round
            )
        }
    }
}

@Composable
private fun SlidersIcon(color: Color) {
    Canvas(Modifier.size(24.dp)) {
        val w = size.width
        val h = size.height
        val stroke = 1.8.dp.toPx()
        val positions = listOf(0.38f, 0.62f, 0.46f)
        positions.forEachIndexed { index, fraction ->
            val y = h * (0.25f + index * 0.25f)
            drawLine(
                color = color,
                start = Offset(w * 0.12f, y),
                end = Offset(w * 0.88f, y),
                strokeWidth = stroke,
                cap = StrokeCap.Round
            )
            drawCircle(
                color = color,
                radius = 3.dp.toPx(),
                center = Offset(w * fraction, y),
                style = Stroke(stroke)
            )
        }
    }
}

@Composable
private fun ExitIcon(color: Color) {
    Canvas(Modifier.size(24.dp)) {
        val w = size.width
        val h = size.height
        val stroke = 1.8.dp.toPx()
        drawLine(
            color = color,
            start = Offset(w * 0.16f, h * 0.14f),
            end = Offset(w * 0.16f, h * 0.86f),
            strokeWidth = stroke,
            cap = StrokeCap.Round
        )
        drawLine(
            color = color,
            start = Offset(w * 0.16f, h * 0.14f),
            end = Offset(w * 0.44f, h * 0.14f),
            strokeWidth = stroke,
            cap = StrokeCap.Round
        )
        drawLine(
            color = color,
            start = Offset(w * 0.16f, h * 0.86f),
            end = Offset(w * 0.44f, h * 0.86f),
            strokeWidth = stroke,
            cap = StrokeCap.Round
        )
        drawLine(
            color = color,
            start = Offset(w * 0.42f, h * 0.5f),
            end = Offset(w * 0.86f, h * 0.5f),
            strokeWidth = stroke,
            cap = StrokeCap.Round
        )
        drawLine(
            color = color,
            start = Offset(w * 0.7f, h * 0.34f),
            end = Offset(w * 0.86f, h * 0.5f),
            strokeWidth = stroke,
            cap = StrokeCap.Round
        )
        drawLine(
            color = color,
            start = Offset(w * 0.7f, h * 0.66f),
            end = Offset(w * 0.86f, h * 0.5f),
            strokeWidth = stroke,
            cap = StrokeCap.Round
        )
    }
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
