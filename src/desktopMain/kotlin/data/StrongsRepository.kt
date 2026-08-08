package data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json


/**
 * Loads and caches the bundled Strong's concordance
 * (`bible/Extras/strongs_definitions.json` — one record per Hebrew `H####`
 * / Greek `G####` number) and decodes the HTML-entity / `<i>`-style markup
 * the records are stored in.
 *
 * The file (~4.4 MB, ~14.7k entries) is parsed lazily on a background
 * thread the first time a Strong's-enabled translation (e.g. "KJV with
 * Strongs") is studied; the parsed map is then kept for the rest of the
 * session. The word-study UI reads [definition] synchronously after
 * [ensureLoaded] completes.
 */
object StrongsRepository {

    @Serializable
    private data class StrongsRecord(
        val id: Int = 0,
        val number: String? = null,
        val root_word: String? = null,
        val transliteration: String? = null,
        val pronunciation: String? = null,
        val tvm: String? = null,
        val entry: String? = null
    )

    /** Decoded definition for one Strong's number (e.g. `G25` or `H1`). */
    data class StrongsDefinition(
        val number: String,
        val rootWord: String,
        val transliteration: String,
        val pronunciation: String,
        val tvm: String?,
        val entry: String
    )

    private val json = Json { ignoreUnknownKeys = true }

    // Written once on a background thread, read from the UI thread; the
    // volatile publish guarantees readers see the fully-built map.
    @Volatile
    private var cache: Map<String, StrongsDefinition>? = null

    // Shared background scope for the one-time parse plus the in-flight
    // guard (mirrors BibleRepository's module-loading pattern): rapid word
    // clicks share ONE parse of the ~4.4 MB file instead of starting two.
    // The cache is published from INSIDE the async so cancelling the
    // calling coroutine (e.g. the user clicks another word mid-load, which
    // re-keys the UI's LaunchedEffect) can never lose the result.
    private val loadingScope = CoroutineScope(Dispatchers.Default)
    private var inFlight: Deferred<Map<String, StrongsDefinition>>? = null

    val isLoaded: Boolean get() = cache != null

    /** Parse (if needed) and cache the definitions. Safe to call repeatedly. */
    suspend fun ensureLoaded() {
        if (cache != null) return
        val deferred = synchronized(this) {
            cache?.let { return }
            inFlight ?: loadingScope.async {
                load().also { loaded -> cache = loaded }
            }.also { created ->
                // Drop the guard once the parse finishes (success or
                // failure) so the next cold access re-parses; the cached
                // result is what later callers actually read.
                created.invokeOnCompletion {
                    synchronized(this) { inFlight = null }
                }
                inFlight = created
            }
        }
        deferred.await()
    }

    fun definition(number: String): StrongsDefinition? = cache?.get(number)

    /** Every loaded definition, for searches over the whole concordance
     *  (the lexicon's number / transliteration / root / word search).
     *  Empty until [ensureLoaded] completes. */
    fun allDefinitions(): Collection<StrongsDefinition> =
        cache?.values ?: emptyList()

    private fun load(): Map<String, StrongsDefinition> {
        val stream = (object {}).javaClass.classLoader
            .getResourceAsStream("bible/Extras/strongs_definitions.json")
            ?: return emptyMap()
        val records = stream.use {
            json.decodeFromString(
                ListSerializer(StrongsRecord.serializer()),
                it.bufferedReader().readText()
            )
        }
        val map = HashMap<String, StrongsDefinition>(records.size * 2)
        for (record in records) {
            val number = record.number?.trim().orEmpty()
            if (number.isEmpty()) continue
            map[number] = StrongsDefinition(
                number = number,
                rootWord = decodeAndClean(record.root_word),
                transliteration = decodeAndClean(record.transliteration),
                pronunciation = decodeAndClean(record.pronunciation),
                tvm = decodeTvm(record.tvm),
                entry = decodeAndClean(record.entry)
            )
        }
        return map
    }

    // Decode HTML entities FIRST so encoded tags (&lt;…&gt;) become real
    // tags, then strip tags and collapse whitespace (the same cleaning the
    // module metadata names go through in JsonLoader).
    private fun decodeAndClean(text: String?): String {
        if (text.isNullOrEmpty()) return ""
        return JsonLoader.stripHtml(decodeHtmlEntities(text))
    }

    /**
     * Decode the tvm (tense/voice/mood) field. Unlike the other fields it
     * is HTML with `<br>` line breaks, so we keep its line structure:
     * entities are decoded first, then `<br>` / block tags become
     * newlines (e.g. `Tense: Aorist, See G5777`), remaining tags are
     * stripped and each line is trimmed. Returns null when empty. The
     * panel renders this multi-line text directly.
     */
    private fun decodeTvm(text: String?): String? {
        if (text.isNullOrEmpty()) return null
        val decoded = decodeHtmlEntities(text)
        val withBreaks = decoded
            .replace(Regex("(?i)<br\\s*/?>"), "\n")
            .replace(Regex("(?i)<\\s*/?\\s*p\\s*/?>"), "\n")
            .replace(Regex("(?i)<\\s*/?\\s*div\\s*/?>"), "\n")
        return withBreaks
            .replace(Regex("<[^>]*>"), " ")
            .replace(Regex("[ \\t]+"), " ")
            .split("\n")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
            .ifEmpty { null }
    }

    /**
     * Decode `&#NNN;` / `&#xHH;` numeric HTML entities and the small set
     * of named entities the Strong's file uses. Unknown entities are left
     * as-is. Numeric entities cover the Greek/Hebrew glyphs plus combining
     * diacritics in `root_word` / `transliteration`.
     */
    private fun decodeHtmlEntities(text: String): String {
        if (text.indexOf('&') == -1) return text
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c != '&') {
                out.append(c)
                i++
                continue
            }
            val semi = text.indexOf(';', i + 1)
            if (semi != -1 && semi - i <= 12) {
                val body = text.substring(i + 1, semi)
                val decoded: Char? = when {
                    body.startsWith("#x", ignoreCase = true) ->
                        body.substring(2).toIntOrNull(16)?.let(::toCharOrNull)

                    body.startsWith("#") ->
                        body.substring(1).toIntOrNull()?.let(::toCharOrNull)

                    else -> NAMED_ENTITIES[body]
                }
                if (decoded != null) {
                    out.append(decoded)
                    i = semi + 1
                    continue
                }
            }
            out.append(c)
            i++
        }
        return out.toString()
    }

    // Skip control characters and the UTF-16 surrogate range.
    private fun toCharOrNull(code: Int): Char? =
        if (code in 0x20..0x10FFFF && (code < 0xD800 || code > 0xDFFF)) {
            code.toChar()
        } else {
            null
        }

    private val NAMED_ENTITIES = mapOf(
        "amp" to '&',
        "lt" to '<',
        "gt" to '>',
        "quot" to '"',
        "apos" to '\'',
        "nbsp" to '\u00A0',
        "ndash" to '\u2013',
        "mdash" to '\u2014',
        "hellip" to '\u2026',
        "lsquo" to '\u2018',
        "rsquo" to '\u2019',
        "ldquo" to '\u201C',
        "rdquo" to '\u201D'
    )
}
