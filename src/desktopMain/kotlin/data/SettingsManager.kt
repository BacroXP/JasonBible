package data

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path


@Serializable
private data class PrivateSettings(
    val darkMode: Boolean = true,
    val fullScreen: Boolean = true,
    val language: String = "Deutsch",
    val translation: String = "Luther 1912",
    val starredBooks: List<Int> = emptyList(),
    val starredChapters: List<String> = emptyList(),
    val readChapters: List<String> = emptyList(),
    val verseMarkers: List<VerseMarker> = emptyList(),
    val lastRead: LastReadRef? = null,
    val soundEffectsEnabled: Boolean = true,
    val soundVolume: Int = 70,
    // Fraction of the SPLIT row's usable width given to the bible pane;
    // 0.5 = equal halves, 0.7 = bible pane wider, 0.3 = notes pane wider.
    // Clamped 0.2..0.8 so neither pane disappears entirely.
    val splitRatio: Float = 0.5f,
    // Maximum card width (px-in-dp) when each pane renders standalone.
    // Each pane reads its own field via SettingsManager.bibleMaxWidth /
    // .editorMaxWidth and passes it to ui.components.MaxWidthScaffold.
    // Default 980f matches the historical hardcoded value. Stored as
    // Float (NOT @Serializable Dp) because Compose's Dp type is just
    // a value-class wrapper around Float — much simpler to round-trip
    // via JSON. Clamped 480..1800 dp at the setter so the user can
    // tune within sensible bounds but can't shrink the pane past
    // readability or stretch it past ultrawide heights.
    val bibleMaxWidth: Float = 980f,
    val editorMaxWidth: Float = 980f,
    // Editor view zoom: multiplier for the editor's body text size.
    // 1.0 = 100%, 1.5 = 150% etc. Pure view setting — never written
    // into the note's .note file. Clamped 0.75..2.0 at the setter so
    // the A− / A+ toolbar buttons can't shrink text past readability
    // or blow it up past usefulness.
    val editorFontScale: Float = 1f,
    // True once the bundled sample notes have been copied into
    // ~/.bibleapp/notes. Guards NotesRepository against re-seeding the
    // sample whenever the notes folder happens to be empty (e.g. after
    // the user deletes every note) — seeding happens exactly once.
    val notesInitialized: Boolean = false,
    // Last Bible full-text search session, persisted so reopening the
    // search bar (Ctrl+F) resumes exactly where the user left off:
    // the query text and the two matching toggles (Aa / whole word).
    val bibleSearchQuery: String = "",
    val bibleSearchMatchCase: Boolean = false,
    val bibleSearchWholeWord: Boolean = false,
    // Recently used Bible search queries (most recent first, capped at
    // [MAX_BIBLE_SEARCH_RECENTS]), shown in a dropdown on the search bar.
    val bibleSearchRecents: List<String> = emptyList(),
    // When true, copied verses / chapters / ranges include the active
    // translation's display name, e.g. "John 3:16 — … (Luther Bible 1912)".
    val copyWithTranslationName: Boolean = false,
    // Global Ctrl+F search thresholds: a Bible book is shown at book level
    // once the search term appears at least [searchBookThreshold] times in
    // it, and a chapter is shown at chapter level (instead of as individual
    // verses) once it appears at least [searchChapterThreshold] times in
    // that chapter. Sub-threshold books/chapters appear as "lone"
    // chapters/verses at the end of the results.
    val searchBookThreshold: Int = 5,
    val searchChapterThreshold: Int = 3,
    // Last global search session, persisted so reopening the search
    // (Ctrl+F) or restarting the app resumes exactly where the user left
    // off: the query text and the Aa (match case) / abc (whole word)
    // toggles.
    val globalSearchQuery: String = "",
    val globalSearchMatchCase: Boolean = false,
    val globalSearchWholeWord: Boolean = false,
    // Recently used global search queries (most recent first, capped at
    // [MAX_GLOBAL_SEARCH_RECENTS]), shown in a dropdown on the search icon.
    val globalSearchRecents: List<String> = emptyList()
)


@Serializable
private data class VerseMarker(
    val book: Int,
    val chapter: Int,
    val verse: Int,
    val tags: List<String> = emptyList(),
    val markerColor: String? = null
)


@Serializable
data class LastReadRef(
    val bookNumber: Int,
    val chapterNumber: Int,
    val verseNumber: Int? = null
)


object SettingsManager {

    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    private val storageFile: Path = Path.of(
        System.getProperty("user.home"),
        ".bibleapp",
        "private.json"
    )

    private val darkModeState = mutableStateOf(true)
    private val fullScreenState = mutableStateOf(true)
    private val languageState = mutableStateOf("Deutsch")
    private val translationState = mutableStateOf("Luther 1912")
    private val starredBooksState = mutableStateOf(setOf<Int>())
    private val starredChaptersState = mutableStateOf(setOf<String>())
    private val readChaptersState = mutableStateOf(setOf<String>())
    private val verseMarkersState = mutableStateOf<Map<String, VerseMarker>>(emptyMap())
    private val lastReadState = mutableStateOf<LastReadRef?>(null)
    private val soundEffectsEnabledState = mutableStateOf(true)
    private val soundVolumeState = mutableStateOf(70)
    private val splitRatioState = mutableStateOf(0.5f)
    private val bibleMaxWidthState = mutableStateOf(980.dp)
    private val editorMaxWidthState = mutableStateOf(980.dp)
    private val editorFontScaleState = mutableStateOf(1f)
    private val notesInitializedState = mutableStateOf(false)
    private val bibleSearchQueryState = mutableStateOf("")
    private val bibleSearchMatchCaseState = mutableStateOf(false)
    private val bibleSearchWholeWordState = mutableStateOf(false)
    private val bibleSearchRecentsState = mutableStateOf<List<String>>(emptyList())
    private val copyWithTranslationNameState = mutableStateOf(false)
    private val searchBookThresholdState = mutableStateOf(5)
    private val searchChapterThresholdState = mutableStateOf(3)
    private val globalSearchQueryState = mutableStateOf("")
    private val globalSearchMatchCaseState = mutableStateOf(false)
    private val globalSearchWholeWordState = mutableStateOf(false)
    private val globalSearchRecentsState = mutableStateOf<List<String>>(emptyList())

    private const val MIN_SPLIT_RATIO = 0.2f
    private const val MAX_SPLIT_RATIO = 0.8f

    // Global-search promotion thresholds, clamped to a sane range.
    private const val MIN_SEARCH_THRESHOLD = 1
    private const val MAX_SEARCH_THRESHOLD = 30

    /** How many recent Bible search queries are remembered. */
    private const val MAX_BIBLE_SEARCH_RECENTS = 10

    /** How many recent global search queries are remembered. */
    private const val MAX_GLOBAL_SEARCH_RECENTS = 10

    private const val MIN_MAX_WIDTH = 480f
    private const val MAX_MAX_WIDTH = 1800f

    // Editor zoom range. Public so the NotesScreen footer slider, the
    // in-editor clamp and the persisted clamp all share one definition
    // and can never drift apart.
    const val MIN_FONT_SCALE = 0.75f
    const val MAX_FONT_SCALE = 2f

    var darkMode: Boolean
        get() = darkModeState.value
        set(value) {
            if (darkModeState.value != value) {
                darkModeState.value = value
                save()
            }
        }

    var fullScreen: Boolean
        get() = fullScreenState.value
        set(value) {
            if (fullScreenState.value != value) {
                fullScreenState.value = value
                save()
            }
        }

    var language: String
        get() = languageState.value
        set(value) {
            if (languageState.value != value) {
                languageState.value = value
                save()
            }
        }

    var translation: String
        get() = translationState.value
        set(value) {
            if (translationState.value != value) {
                translationState.value = value
                save()
            }
        }

    var soundEffectsEnabled: Boolean
        get() = soundEffectsEnabledState.value
        set(value) {
            if (soundEffectsEnabledState.value != value) {
                soundEffectsEnabledState.value = value
                save()
            }
        }

    /**
     * Master volume for sound effects, 0..100. 0 means silent (still
     * respects the [soundEffectsEnabled] mute toggle).
     */
    var soundVolume: Int
        get() = soundVolumeState.value
        set(value) {
            val clamped = value.coerceIn(0, 100)
            if (soundVolumeState.value != clamped) {
                soundVolumeState.value = clamped
                save()
            }
        }

    /**
     * Bible pane's share of the SPLIT row width, clamped 0.2..0.8 so
     * neither pane disappears entirely. 0.5 = equal halves. Persisted so
     * the user's preferred split ratio survives app restarts.
     */
    var splitRatio: Float
        get() = splitRatioState.value
        set(value) {
            val clamped = value.coerceIn(MIN_SPLIT_RATIO, MAX_SPLIT_RATIO)
            if (splitRatioState.value != clamped) {
                splitRatioState.value = clamped
                save()
            }
        }

    /**
     * Maximum card width in dp when the bible pane renders standalone
     * (i.e. on the BIBLE screen, not in SPLIT). Clamped 480..1800 dp
     * so the pane never collapses past readability nor stretches past
     * ultrawide heights. Default 980.dp matches the historical
     * hardcoded MaxWidthScaffold value. Persisted in `private.json`
     * and exposed as Dp to callers.
     */
    var bibleMaxWidth: Dp
        get() = bibleMaxWidthState.value
        set(value) {
            val clamped = value.value.coerceIn(MIN_MAX_WIDTH, MAX_MAX_WIDTH).dp
            if (bibleMaxWidthState.value != clamped) {
                bibleMaxWidthState.value = clamped
                save()
            }
        }

    /**
     * Maximum card width in dp when the notes editor renders standalone
     * (i.e. on the NOTES screen, not in SPLIT). Same clamp range & default
     * as [bibleMaxWidth] — independent slider so the user can widen one
     * pane without affecting the other.
     */
    var editorMaxWidth: Dp
        get() = editorMaxWidthState.value
        set(value) {
            val clamped = value.value.coerceIn(MIN_MAX_WIDTH, MAX_MAX_WIDTH).dp
            if (editorMaxWidthState.value != clamped) {
                editorMaxWidthState.value = clamped
                save()
            }
        }

    /**
     * Editor view zoom multiplier, clamped 0.75..2.0 (75%..200%).
     * Drives the A− / A+ buttons in the editor toolbar and persists so
     * the user's preferred text size survives app restarts.
     */
    var editorFontScale: Float
        get() = editorFontScaleState.value
        set(value) {
            val clamped = value.coerceIn(MIN_FONT_SCALE, MAX_FONT_SCALE)
            if (editorFontScaleState.value != clamped) {
                editorFontScaleState.value = clamped
                save()
            }
        }

    /**
     * Whether the bundled sample notes have been seeded into the user's
     * notes folder. Set to true by [data.NotesRepository.ensureSeeded]
     * after the first run so deleting every note doesn't resurrect the
     * sample on the next repository access.
     */
    var notesInitialized: Boolean
        get() = notesInitializedState.value
        set(value) {
            if (notesInitializedState.value != value) {
                notesInitializedState.value = value
                save()
            }
        }

    /**
     * Persisted Bible full-text search query. Written on every keystroke
     * while the search bar is open; restored the next time the bar opens
     * (BibleScreen seeds its state from this in `remember`).
     */
    var bibleSearchQuery: String
        get() = bibleSearchQueryState.value
        set(value) {
            if (bibleSearchQueryState.value != value) {
                bibleSearchQueryState.value = value
                save()
            }
        }

    /** Persisted "Aa" (match case) toggle for the Bible search bar. */
    var bibleSearchMatchCase: Boolean
        get() = bibleSearchMatchCaseState.value
        set(value) {
            if (bibleSearchMatchCaseState.value != value) {
                bibleSearchMatchCaseState.value = value
                save()
            }
        }

    /** Persisted whole-word toggle for the Bible search bar. */
    var bibleSearchWholeWord: Boolean
        get() = bibleSearchWholeWordState.value
        set(value) {
            if (bibleSearchWholeWordState.value != value) {
                bibleSearchWholeWordState.value = value
                save()
            }
        }

    /** Persisted recently-used Bible search queries (most recent first). */
    var bibleSearchRecents: List<String>
        get() = bibleSearchRecentsState.value
        set(value) {
            if (bibleSearchRecentsState.value != value) {
                bibleSearchRecentsState.value = value
                save()
            }
        }

    /**
     * Record a Bible search query as recently used: de-duplicates
     * case-insensitively (keeping the newest spelling), moves it to the
     * front and caps the list at [MAX_BIBLE_SEARCH_RECENTS]. Blank
     * queries are ignored.
     */
    fun addBibleSearchRecent(query: String) {
        val q = query.trim()
        if (q.isEmpty()) return
        val updated = listOf(q) + bibleSearchRecentsState.value
            .filter { !it.equals(q, ignoreCase = true) }
        bibleSearchRecents = updated.take(MAX_BIBLE_SEARCH_RECENTS)
    }

    /**
     * Whether copied verses / chapters / ranges carry the active
     * translation's display name (e.g. "… (Luther Bible 1912)").
     */
    var copyWithTranslationName: Boolean
        get() = copyWithTranslationNameState.value
        set(value) {
            if (copyWithTranslationNameState.value != value) {
                copyWithTranslationNameState.value = value
                save()
            }
        }

    /**
     * Global search: minimum times the term must appear in a book before
     * the book is shown at book level (instead of as lone chapters /
     * verses). Name-matched books always win promotion regardless.
     */
    var searchBookThreshold: Int
        get() = searchBookThresholdState.value
        set(value) {
            val clamped = value.coerceIn(MIN_SEARCH_THRESHOLD, MAX_SEARCH_THRESHOLD)
            if (searchBookThresholdState.value != clamped) {
                searchBookThresholdState.value = clamped
                save()
            }
        }

    /**
     * Global search: minimum times the term must appear in a chapter
     * before the chapter is shown at chapter level (instead of as
     * individual verses) inside a non-promoted book.
     */
    var searchChapterThreshold: Int
        get() = searchChapterThresholdState.value
        set(value) {
            val clamped = value.coerceIn(MIN_SEARCH_THRESHOLD, MAX_SEARCH_THRESHOLD)
            if (searchChapterThresholdState.value != clamped) {
                searchChapterThresholdState.value = clamped
                save()
            }
        }

    /**
     * Persisted global search query. Written when the search closes;
     * restored the next time it opens (AppSearchState seeds from this).
     */
    var globalSearchQuery: String
        get() = globalSearchQueryState.value
        set(value) {
            if (globalSearchQueryState.value != value) {
                globalSearchQueryState.value = value
                save()
            }
        }

    /** Persisted "Aa" (match case) toggle for the global search. */
    var globalSearchMatchCase: Boolean
        get() = globalSearchMatchCaseState.value
        set(value) {
            if (globalSearchMatchCaseState.value != value) {
                globalSearchMatchCaseState.value = value
                save()
            }
        }

    /** Persisted "abc" (whole word) toggle for the global search. */
    var globalSearchWholeWord: Boolean
        get() = globalSearchWholeWordState.value
        set(value) {
            if (globalSearchWholeWordState.value != value) {
                globalSearchWholeWordState.value = value
                save()
            }
        }

    /** Persisted recently-used global search queries (most recent first). */
    var globalSearchRecents: List<String>
        get() = globalSearchRecentsState.value
        set(value) {
            if (globalSearchRecentsState.value != value) {
                globalSearchRecentsState.value = value
                save()
            }
        }

    /**
     * Record a global search query as recently used: de-duplicates
     * case-insensitively (keeping the newest spelling), moves it to the
     * front and caps the list at [MAX_GLOBAL_SEARCH_RECENTS]. Blank
     * queries are ignored.
     */
    fun addGlobalSearchRecent(query: String) {
        val q = query.trim()
        if (q.isEmpty()) return
        val updated = listOf(q) + globalSearchRecentsState.value
            .filter { !it.equals(q, ignoreCase = true) }
        globalSearchRecents = updated.take(MAX_GLOBAL_SEARCH_RECENTS)
    }

    init {
        load()
    }


    fun isBookStarred(bookNumber: Int): Boolean {
        return bookNumber in starredBooksState.value
    }


    fun isChapterStarred(bookNumber: Int, chapterNumber: Int): Boolean {
        return chapterKey(bookNumber, chapterNumber) in starredChaptersState.value
    }


    fun toggleBookStar(bookNumber: Int) {
        starredBooksState.value = starredBooksState.value.toMutableSet().apply {
            if (!add(bookNumber)) {
                remove(bookNumber)
            }
        }.toSet()
        save()
    }


    fun toggleChapterStar(bookNumber: Int, chapterNumber: Int) {
        val key = chapterKey(bookNumber, chapterNumber)

        starredChaptersState.value = starredChaptersState.value.toMutableSet().apply {
            if (!add(key)) {
                remove(key)
            }
        }.toSet()

        save()
    }


    fun isChapterRead(bookNumber: Int, chapterNumber: Int): Boolean {
        return chapterKey(bookNumber, chapterNumber) in readChaptersState.value
    }


    fun setChapterRead(bookNumber: Int, chapterNumber: Int, read: Boolean) {
        val key = chapterKey(bookNumber, chapterNumber)
        readChaptersState.value = readChaptersState.value.toMutableSet().apply {
            if (read) {
                add(key)
            } else {
                remove(key)
            }
        }.toSet()
        save()
    }


    fun toggleChapterRead(bookNumber: Int, chapterNumber: Int) {
        val key = chapterKey(bookNumber, chapterNumber)
        readChaptersState.value = readChaptersState.value.toMutableSet().apply {
            if (!add(key)) {
                remove(key)
            }
        }.toSet()
        save()
    }


    fun readChapterCount(): Int {
        return readChaptersState.value.size
    }


    fun readChaptersInRange(bookNumbers: Set<Int>): Int {
        return readChaptersState.value.count { key ->
            key.substringBefore(":").toIntOrNull() in bookNumbers
        }
    }


    fun getVerseTags(bookNumber: Int, chapterNumber: Int, verseNumber: Int): List<String> {
        return verseMarker(bookNumber, chapterNumber, verseNumber)?.tags.orEmpty()
    }


    fun getVerseMarkerColor(
        bookNumber: Int,
        chapterNumber: Int,
        verseNumber: Int
    ): String? {
        return verseMarker(bookNumber, chapterNumber, verseNumber)?.markerColor
    }


    fun setVerseMarkerColor(
        bookNumber: Int,
        chapterNumber: Int,
        verseNumber: Int,
        markerColor: String?
    ) {
        upsertVerseMarker(bookNumber, chapterNumber, verseNumber) { current ->
            current.copy(markerColor = markerColor)
        }
    }


    fun setVerseTags(
        bookNumber: Int,
        chapterNumber: Int,
        verseNumber: Int,
        tags: List<String>
    ) {
        upsertVerseMarker(bookNumber, chapterNumber, verseNumber) { current ->
            current.copy(tags = tags)
        }
    }


    fun getLastRead(): LastReadRef? {
        return lastReadState.value
    }


    fun setLastRead(bookNumber: Int, chapterNumber: Int, verseNumber: Int? = null) {
        val next = LastReadRef(bookNumber, chapterNumber, verseNumber)
        if (lastReadState.value != next) {
            lastReadState.value = next
            save()
        }
    }


    fun clearLastRead() {
        if (lastReadState.value != null) {
            lastReadState.value = null
            save()
        }
    }


    private fun load() {
        val loaded = runCatching {
            if (Files.exists(storageFile)) {
                json.decodeFromString(
                    PrivateSettings.serializer(),
                    Files.readString(storageFile)
                )
            } else {
                null
            }
        }.getOrNull()

        if (loaded == null) {
            save()
            return
        }

        applyLoadedSettings(loaded)
    }


    private fun applyLoadedSettings(settings: PrivateSettings) {
        darkModeState.value = settings.darkMode
        fullScreenState.value = settings.fullScreen
        languageState.value = settings.language
        translationState.value = settings.translation
        starredBooksState.value = settings.starredBooks.toSet()
        starredChaptersState.value = settings.starredChapters.toSet()
        readChaptersState.value = settings.readChapters.toSet()
        verseMarkersState.value = settings.verseMarkers.associateBy { verseKey(
            it.book,
            it.chapter,
            it.verse
        ) }
        lastReadState.value = settings.lastRead
        soundEffectsEnabledState.value = settings.soundEffectsEnabled
        soundVolumeState.value = settings.soundVolume.coerceIn(0, 100)
        splitRatioState.value = settings.splitRatio.coerceIn(MIN_SPLIT_RATIO, MAX_SPLIT_RATIO)
        bibleMaxWidthState.value = settings.bibleMaxWidth
            .coerceIn(MIN_MAX_WIDTH, MAX_MAX_WIDTH)
            .dp
        editorMaxWidthState.value = settings.editorMaxWidth
            .coerceIn(MIN_MAX_WIDTH, MAX_MAX_WIDTH)
            .dp
        editorFontScaleState.value = settings.editorFontScale
            .coerceIn(MIN_FONT_SCALE, MAX_FONT_SCALE)
        notesInitializedState.value = settings.notesInitialized
        bibleSearchQueryState.value = settings.bibleSearchQuery
        bibleSearchMatchCaseState.value = settings.bibleSearchMatchCase
        bibleSearchWholeWordState.value = settings.bibleSearchWholeWord
        bibleSearchRecentsState.value = settings.bibleSearchRecents
        copyWithTranslationNameState.value = settings.copyWithTranslationName
        searchBookThresholdState.value = settings.searchBookThreshold
            .coerceIn(MIN_SEARCH_THRESHOLD, MAX_SEARCH_THRESHOLD)
        searchChapterThresholdState.value = settings.searchChapterThreshold
            .coerceIn(MIN_SEARCH_THRESHOLD, MAX_SEARCH_THRESHOLD)
        globalSearchQueryState.value = settings.globalSearchQuery
        globalSearchMatchCaseState.value = settings.globalSearchMatchCase
        globalSearchWholeWordState.value = settings.globalSearchWholeWord
        globalSearchRecentsState.value = settings.globalSearchRecents
    }


    private fun save() {
        runCatching {
            Files.createDirectories(storageFile.parent)
            Files.writeString(
                storageFile,
                json.encodeToString(
                    PrivateSettings.serializer(),
                    PrivateSettings(
                        darkMode = darkModeState.value,
                        fullScreen = fullScreenState.value,
                        language = languageState.value,
                        translation = translationState.value,
                        starredBooks = starredBooksState.value.sorted(),
                        starredChapters = starredChaptersState.value.sorted(),
                        readChapters = readChaptersState.value.sorted(),
                        verseMarkers = verseMarkersState.value.values
                            .sortedWith(
                                compareBy<VerseMarker> { it.book }
                                    .thenBy { it.chapter }
                                    .thenBy { it.verse }
                            ),
                        lastRead = lastReadState.value,
                        soundEffectsEnabled = soundEffectsEnabledState.value,
                        soundVolume = soundVolumeState.value,
                        splitRatio = splitRatioState.value,
                        bibleMaxWidth = bibleMaxWidthState.value.value,
                        editorMaxWidth = editorMaxWidthState.value.value,
                        editorFontScale = editorFontScaleState.value,
                        notesInitialized = notesInitializedState.value,
                        bibleSearchQuery = bibleSearchQueryState.value,
                        bibleSearchMatchCase = bibleSearchMatchCaseState.value,
                        bibleSearchWholeWord = bibleSearchWholeWordState.value,
                        bibleSearchRecents = bibleSearchRecentsState.value,
                        copyWithTranslationName = copyWithTranslationNameState.value,
                        searchBookThreshold = searchBookThresholdState.value,
                        searchChapterThreshold = searchChapterThresholdState.value,
                        globalSearchQuery = globalSearchQueryState.value,
                        globalSearchMatchCase = globalSearchMatchCaseState.value,
                        globalSearchWholeWord = globalSearchWholeWordState.value,
                        globalSearchRecents = globalSearchRecentsState.value
                    )
                )
            )
        }
    }


    private fun verseMarker(
        bookNumber: Int,
        chapterNumber: Int,
        verseNumber: Int
    ): VerseMarker? {
        return verseMarkersState.value[verseKey(bookNumber, chapterNumber, verseNumber)]
    }


    private fun upsertVerseMarker(
        bookNumber: Int,
        chapterNumber: Int,
        verseNumber: Int,
        transform: (VerseMarker) -> VerseMarker
    ) {
        val key = verseKey(bookNumber, chapterNumber, verseNumber)
        val current = verseMarkersState.value[key]
            ?: VerseMarker(bookNumber, chapterNumber, verseNumber)

        verseMarkersState.value = verseMarkersState.value.toMutableMap().apply {
            put(key, transform(current))
        }
        save()
    }


    private fun chapterKey(bookNumber: Int, chapterNumber: Int): String {
        return "$bookNumber:$chapterNumber"
    }


    private fun verseKey(bookNumber: Int, chapterNumber: Int, verseNumber: Int): String {
        return "$bookNumber:$chapterNumber:$verseNumber"
    }
}
