package data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import model.Book


object BibleRepository {

    // Modules are parsed once and cached per module id; a translation
    // switch just swaps which cached list is returned. Written on the UI
    // thread only (Compose), so a plain map is safe.
    private val moduleCache = mutableMapOf<String, List<Book>>()

    // Shared background scope for module parses plus the in-flight guard:
    // concurrent callers (e.g. the Bible pane and the insert-reference
    // dialog loading the same cold module) share ONE parse of a (possibly
    // 30 MB) file instead of starting two.
    private val loadingScope = CoroutineScope(Dispatchers.Default)
    private val inFlight = mutableMapOf<String, Deferred<List<Book>>>()

    /**
     * Module id of the bundled parsed-Greek NT (trparsed) used by the
     * interlinear view. Loaded alongside the active translation when the
     * interlinear toggle is on, so the Greek TR verse renders beneath the
     * English verse with clickable Strong's numbers.
     */
    const val INTERLINEAR_MODULE_ID = "trparsed"

    /**
     * Module id of the currently selected translation, falling back to
     * the catalog default when the saved id no longer exists. Reads
     * [SettingsManager.translation] (a Compose snapshot state), so
     * composables that read this recompose when the user switches Bibles.
     * Returns null when no module is available at all.
     */
    fun currentModuleId(): String? {
        val saved = SettingsManager.translation
        val entry = BibleCatalog.entryFor(saved)
            ?: BibleCatalog.entryFor(BibleCatalog.defaultId)
            ?: return null
        return entry.moduleId
    }

    /**
     * The parsed books of the current module, or null when that module
     * hasn't been loaded yet. Never parses — used by the async loading
     * path (BibleScreen) to avoid freezing the UI on big modules.
     * NOTE: null means "not loaded yet" (show a spinner); an EMPTY list
     * means "no module available at all" (nothing to show).
     */
    fun cachedBooks(): List<Book>? {
        val id = currentModuleId() ?: return emptyList()
        return moduleCache[id]
    }

    /**
     * Parses (if needed) and returns the books of ANY module by id, with
     * the same per-module cache and in-flight guard as [loadBooks]. Used
     * by the interlinear view to load the Greek TR module alongside the
     * active translation. Returns an empty list when the module id isn't
     * in the catalog (e.g. the user removed the file).
     */
    suspend fun loadModule(moduleId: String): List<Book> {
        moduleCache[moduleId]?.let { return it }
        val entry = BibleCatalog.entryFor(moduleId) ?: return emptyList()
        val deferred = synchronized(inFlight) {
            inFlight[moduleId] ?: loadingScope.async {
                JsonLoader.loadBible(entry.resourcePath)
            }.also { created ->
                // Drop the guard once the parse finishes (success or
                // failure) so the next cold access re-parses; the cached
                // result is what later callers actually read.
                created.invokeOnCompletion {
                    synchronized(inFlight) { inFlight -= moduleId }
                }
                inFlight[moduleId] = created
            }
        }
        val parsed = deferred.await()
        moduleCache[moduleId] = parsed
        return parsed
    }

    /**
     * Parses (if needed) and returns the current module's books. The
     * parse runs on [Dispatchers.Default], so calling this from a
     * coroutine never blocks the UI thread — big translations (~30 MB)
     * take a moment on first open and are cached afterwards.
     */
    suspend fun loadBooks(): List<Book> {
        val id = currentModuleId() ?: return emptyList()
        return loadModule(id)
    }

    /**
     * Synchronous, parse-on-demand access to the current module's books.
     * Blocks the calling thread on a cold cache — prefer [loadBooks] from
     * a coroutine for the interactive Bible view.
     */
    fun books(): List<Book> {
        val id = currentModuleId() ?: return emptyList()
        moduleCache[id]?.let { return it }
        val entry = BibleCatalog.entryFor(id) ?: return emptyList()
        val parsed = JsonLoader.loadBible(entry.resourcePath)
        moduleCache[id] = parsed
        return parsed
    }

    /**
     * Text of ONE verse inside an already-parsed module, or null when the
     * book / chapter / verse doesn't exist there (e.g. a New-Testament
     * verse in an Old-Testament-only module, or a verse beyond the last
     * one). Pure lookup — no parsing, no I/O — so the verse-comparison
     * screen and its tests share exactly one extraction implementation.
     */
    fun verseTextFor(
        books: List<Book>?,
        bookNumber: Int,
        chapterNumber: Int,
        verseNumber: Int
    ): String? {
        val book = books?.find { it.book == bookNumber } ?: return null
        val chapter = book.chapters.find { it.chapter == chapterNumber } ?: return null
        return chapter.verses.find { it.verse == verseNumber }?.text
    }

    /**
     * Greek TR verse text for every verse of one chapter of the parsed-
     * Greek module, keyed by verse number — the interlinear lookup used by
     * the chapter view. Empty when the module isn't loaded, the book /
     * chapter doesn't exist, or the book is Old Testament (trparsed is
     * New-Testament-only). Extracted here so the app and its tests share
     * exactly one lookup implementation.
     */
    fun greekVersesForChapter(
        greekBooks: List<Book>?,
        bookNumber: Int,
        chapterNumber: Int
    ): Map<Int, String> {
        val book = greekBooks?.find { it.book == bookNumber } ?: return emptyMap()
        val chapter = book.chapters.find { it.chapter == chapterNumber } ?: return emptyMap()
        return chapter.verses.associate { it.verse to it.text }
    }

    /**
     * Greek TR verse text for every verse of one book, keyed by
     * (chapter, verse) — the whole-book interlinear lookup used by the
     * continuous reading view. Empty when the module isn't loaded or the
     * book doesn't exist (Old-Testament books included).
     */
    fun greekVersesForBook(
        greekBooks: List<Book>?,
        bookNumber: Int
    ): Map<Pair<Int, Int>, String> {
        val book = greekBooks?.find { it.book == bookNumber } ?: return emptyMap()
        return buildMap {
            for (chapter in book.chapters) {
                for (verse in chapter.verses) {
                    put(chapter.chapter to verse.verse, verse.text)
                }
            }
        }
    }

    /**
     * Resolve a book by name — first against the active Bible's own
     * names, then through the cross-language alias index (e.g. `$Lukas`
     * in a German note still finds "Luke" after switching to English).
     */
    fun getBook(name: String): Book? {
        val list = books()
        list.find { it.name.equals(name, ignoreCase = true) }?.let { return it }
        val number = BibleCatalog.nameToBookNumber[name.trim().lowercase()] ?: return null
        return getBook(number)
    }

    fun getBook(number: Int): Book? {
        return books().find { it.book == number }
    }


    /**
     * Canonical book number for a name in any known language, or null.
     * Used for cross-language identity comparisons (e.g. matching a
     * note's German reference against the active English Bible).
     */
    fun bookNumberFor(name: String): Int? {
        val trimmed = name.trim()
        books().find { it.name.equals(trimmed, ignoreCase = true) }?.let { return it.book }
        return BibleCatalog.nameToBookNumber[trimmed.lowercase()]
    }
}
