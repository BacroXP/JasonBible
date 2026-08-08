package data

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import model.Book
import model.Chapter
import model.Verse


object JsonLoader {

    private val json = Json {
        ignoreUnknownKeys = true
    }


    // ------------------------------------------------------------------
    // SWORD-format Bible module: { "metadata": {...}, "verses": [...] }
    // (one flat verse record per line of scripture).
    // ------------------------------------------------------------------

    @Serializable
    private data class SwordModule(
        val metadata: SwordMetadata? = null,
        val verses: List<SwordVerse> = emptyList()
    )

    @Serializable
    private data class SwordMetadata(
        val name: String? = null,
        val module: String? = null
    )

    @Serializable
    private data class SwordVerse(
        val book: Int = 0,
        val book_name: String? = null,
        val chapter: Int? = null,
        val verse: Int? = null,
        val text: String? = null
    )

    /**
     * Load a SWORD-format Bible module from the classpath and group its
     * flat verse records into the Book → Chapter → Verse model. Books and
     * chapters are kept in canonical order; records with missing/zero
     * book, chapter or verse numbers are dropped (some modules carry
     * intro records or partial canons, e.g. a New-Testament-only file).
     */
    fun loadBible(resourcePath: String): List<Book> {
        val stream = open(resourcePath)
            ?: error("Bible module missing on classpath: $resourcePath")
        val module = stream.use {
            json.decodeFromString(
                SwordModule.serializer(),
                it.bufferedReader().readText()
            )
        }
        val valid = module.verses.filter { verse ->
            verse.book > 0 &&
                (verse.chapter ?: 0) > 0 &&
                (verse.verse ?: 0) > 0
        }
        return valid
            .groupBy { it.book }
            .map { (bookNumber, verses) ->
                val name = verses.firstNotNullOfOrNull { it.book_name }
                    ?.trim()
                    ?.ifEmpty { null }
                    ?: "Book $bookNumber"
                val chapters = verses
                    .groupBy { it.chapter!! }
                    .map { (chapterNumber, chapterVerses) ->
                        Chapter(
                            chapter = chapterNumber,
                            verses = chapterVerses
                                .sortedBy { it.verse }
                                .map { verse ->
                                    Verse(verse.verse!!, verse.text.orEmpty())
                                }
                        )
                    }
                    .sortedBy { it.chapter }
                Book(bookNumber, name, chapters)
            }
            .sortedBy { it.book }
    }

    /**
     * Read ONLY the human-readable `metadata.name` of a module, for the
     * Bible picker. The stream is cut as soon as the `"verses"` array
     * starts, so a multi-megabyte module is never fully read just to
     * list it. The name is the first `"name"` field of the metadata
     * object, so it is extracted straight from the prefix and decoded as
     * a JSON string literal. Returns null when the module or its name
     * can't be read.
     */
    fun loadMetadataName(resourcePath: String): String? {
        val stream = open(resourcePath) ?: return null
        val prefix = stream.use { input ->
            val buf = StringBuilder()
            val chunk = ByteArray(4096)
            while (buf.length < 128 * 1024) {
                val n = input.read(chunk)
                if (n <= 0) break
                buf.append(String(chunk, 0, n, Charsets.UTF_8))
                if (buf.indexOf("\"verses\":") != -1) break
            }
            buf.toString()
        }
        val cut = prefix.indexOf("\"verses\":")
        val head = if (cut == -1) prefix else prefix.substring(0, cut)
        val match = NAME_IN_METADATA.find(head) ?: return null
        val rawName = match.groupValues[1]
        return runCatching { json.decodeFromString<String>("\"$rawName\"") }
            .getOrNull()
            ?.let(::stripHtml)
            ?.trim()
            ?.ifEmpty { null }
    }

    // Matches `"name":"…"` — the metadata's name field — capturing the
    // raw (still escaped) string content so it can be decoded properly.
    private val NAME_IN_METADATA =
        Regex("\"name\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")


    // ------------------------------------------------------------------
    // Localized book-name lists (Extras/books_*.json)
    // ------------------------------------------------------------------

    @Serializable
    internal data class LocalizedBook(
        val id: Int,
        val name: String? = null,
        val shortname: String? = null,
        val matching1: String? = null,
        val matching2: String? = null
    )

    /**
     * Load one `books_<lang>.json` list — localized book names per
     * canonical book number — used to build the cross-language name
     * index so references typed in one language still resolve after a
     * Bible switch. Returns null if the file can't be read or parsed.
     */
    internal fun loadBookList(resourcePath: String): List<LocalizedBook>? {
        val stream = open(resourcePath) ?: return null
        return stream.use {
            runCatching {
                json.decodeFromString(
                    ListSerializer(LocalizedBook.serializer()),
                    it.bufferedReader().readText()
                )
            }.getOrNull()
        }
    }


    private fun open(resourcePath: String) =
        (object {}).javaClass.classLoader.getResourceAsStream(resourcePath)

    /**
     * Remove HTML tags (replacing them with a space) and collapse runs of
     * whitespace. Shared with StrongsRepository, whose dictionary entries
     * are marked up with `<i>` / `<b>` / `<p>` tags.
     */
    internal fun stripHtml(text: String): String =
        text.replace(Regex("<[^>]*>"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
}
