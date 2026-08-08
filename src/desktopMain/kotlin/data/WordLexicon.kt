package data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import model.Book


// ---------------------------------------------------------------------------
// Word lexicon (Hebrew / Greek / Aramaic)
//
// The central word-study data layer, building on the bundled resources:
//
//   • Strong's definitions (`strongs_definitions.json`, via
//     StrongsRepository) — the ORIGINAL WORD in Hebrew/Greek script lives
//     in the `root_word` field, together with transliteration,
//     pronunciation and the prose meaning. The entries also cross-
//     reference related lemmas ("From H433", "Plural of H433", "Compare
//     G5368"), which drives the ROOT / RELATED-WORD navigation.
//   • Occurrences — every passage where a word occurs, scanned from two
//     bundled modules: `trparsed` (the Greek original New Testament,
//     whose words carry parsing codes) and `kjv_strongs` (the whole
//     English Bible with `{H####}` / `{G####}` markup). The Hebrew
//     original (wlc) ships WITHOUT markup, so Hebrew occurrences are
//     sourced from the English translation — each occurrence carries its
//     source label, so the UI can distinguish original-language from
//     translated occurrences.
//
// Aramaic support is prepared (LanguageKind.ARAMAIC) but no Aramaic data
// is bundled, so nothing is invented.
// ---------------------------------------------------------------------------

object WordLexicon {

    /** Language of an original-language word. Aramaic is reserved for a
     *  future aram-tagged module; today's data tags OT Aramaic sections
     *  with H-numbers, which resolve to [HEBREW]. */
    enum class LanguageKind(val label: String) { HEBREW("Hebrew"), GREEK("Greek"), ARAMAIC("Aramaic") }

    /** One passage where a Strong's number occurs. [parsing] is the
     *  morphological code (e.g. `V-AAI-3S`) from the Greek original
     *  module; English occurrences have none. */
    data class Occurrence(
        val book: Int,
        val chapter: Int,
        val verse: Int,
        val word: String,
        val number: String,
        val parsing: String?,
        val language: LanguageKind,
        val sourceLabel: String
    )

    /** A lexicon search result, showing what matched. */
    data class SearchHit(
        val number: String,
        val originalWord: String,
        val transliteration: String
    )

    private const val GREEK_MODULE = "trparsed"
    private const val ENGLISH_MODULE = "kjv_strongs"
    private const val GREEK_LABEL = "TR Parsed (Greek)"
    private const val ENGLISH_LABEL = "KJV with Strongs (English)"

    // Brace markup `{G1161}` / `{(G5656)}` is stripped before extracting a
    // verse's plain words; the remaining text is then split on anything
    // non-alphanumeric. Function words are rarely Strong's-tagged, so the
    // FULL text (not just tagged tokens) is what backs the cross-
    // testament quotation matcher.
    private val MARKUP_REGEX = Regex("\\{[^}]*\\}")
    private val NON_WORD_REGEX = Regex("[^\\p{L}\\p{Nd}]+")

    // Mirror of ui/WordStudy.kt's token regexes (kept here so the data
    // layer can build the index without depending on the UI package; the
    // two parsers must stay in sync with that file's).
    private val STRONGS_REGEX = Regex("([^\\s{}]+)\\{([GH]\\d+)\\}(?:\\{(\\([^)]*\\))\\})?")
    private val PARSED_REGEX =
        Regex("((?!G\\d)\\S+) (G\\d+)(?: (G\\d+))?(?: (G\\d+))?(?: (G\\d+))? ((?!G\\d)\\S+)")

    // Cross-references inside the Strong's entry prose ("From G3004",
    // "Plural of H433", "Compare G5368", "See G5777", …).
    private val ENTRY_REF_REGEX = Regex("\\b([HG]\\d{1,4})\\b")
    private val FROM_ROOT_REGEX = Regex("(?i)\\b(?:from|by) (?:the same as )?([HG]\\d{1,4})\\b")
    private val PLURAL_OF_REGEX = Regex("(?i)\\bplural of ([HG]\\d{1,4})\\b")
    private val COMPARE_REGEX = Regex("(?i)\\bcompare ([HG]\\d{1,4})\\b")

    // Written once on a background thread. A SINGLE volatile reference to
    // the fully-built [LoadedData] is published, so readers can never
    // observe a torn half-built state (e.g. the occurrence index live but
    // the word index still missing) — every tier of the search and the
    // occurrence lookups agree on the same snapshot.
    @Volatile
    private var loaded: LoadedData? = null

    /** Canonical verse key `book:chapter:verse`. */
    private fun verseKey(book: Int, chapter: Int, verse: Int) = "$book:$chapter:$verse"

    private val loadingScope = CoroutineScope(Dispatchers.Default)
    private var inFlight: Deferred<Unit>? = null

    val isLoaded: Boolean get() = loaded != null

    /** Language of a Strong's number (`H####` Hebrew, `G####` Greek). */
    fun languageOf(number: String): LanguageKind =
        if (number.startsWith("H", ignoreCase = true)) LanguageKind.HEBREW else LanguageKind.GREEK

    /** Build (once) the occurrence index, the word index, the relation
     *  graph and the root map. Safe to call repeatedly. */
    suspend fun ensureLoaded() {
        if (loaded != null) return
        val deferred = synchronized(this) {
            loaded?.let { return }
            inFlight ?: loadingScope.async {
                StrongsRepository.ensureLoaded()
                // The original-language modules load here (inside the
                // coroutine — loadModule is suspend), then the index is
                // built from the parsed books on a worker thread. A
                // missing / unparseable module degrades to an empty book
                // list instead of aborting the whole build.
                val greekBooks = try {
                    BibleRepository.loadModule(GREEK_MODULE)
                } catch (e: Exception) {
                    emptyList()
                }
                val englishBooks = try {
                    BibleRepository.loadModule(ENGLISH_MODULE)
                } catch (e: Exception) {
                    emptyList()
                }
                val built = withContext(Dispatchers.Default) {
                    build(greekBooks, englishBooks)
                }
                loaded = LoadedData(
                    occurrences = built.first,
                    words = built.second,
                    relations = built.third,
                    roots = built.fourth,
                    verseNumbers = built.fifth,
                    verseWords = built.sixth
                )
            }.also { created ->
                created.invokeOnCompletion {
                    synchronized(this) { inFlight = null }
                }
                inFlight = created
            }
        }
        deferred.await()
    }

    /** Every passage where [number] occurs, in canonical order (Greek
     *  original occurrences first for G-numbers), or empty when unknown. */
    fun occurrences(number: String): List<Occurrence> =
        loaded?.occurrences?.get(number).orEmpty()

    /** Distinct surface forms of [number] as they appear in the Bible —
     *  the Greek original forms first (e.g. for G25: ἠγάπησεν, ἀγαπᾷ, …),
     *  then the translation's words. Empty when the index isn't loaded. */
    fun originalWords(number: String): List<String> =
        occurrences(number)
            .groupBy { it.language }
            .toSortedMap(compareByDescending<LanguageKind> { it == LanguageKind.GREEK })
            .values.flatten()
            .map { it.word }
            .distinct()

    /**
     * The root lemma of [number], from the entry's "From …" / "Plural
     * of …" / "Compare …" cross-references (e.g. H430 → H433, H7225 →
     * H7218, G25 → G5368). Null when the entry names no root.
     */
    fun rootOf(number: String): String? = loaded?.roots?.get(number)

    /**
     * The distinct Strong's numbers appearing in one verse of the
     * Strong's-marked English module (`kjv_strongs`, the whole Bible),
     * or an empty set when the index isn't loaded / the verse has no
     * markup. Drives the derived cross-reference similarity search.
     */
    fun numbersForVerse(book: Int, chapter: Int, verse: Int): Set<String> =
        loaded?.verseNumbers?.get(verseKey(book, chapter, verse)).orEmpty()

    /** Number of verses with Strong's markup in the English index. */
    val indexedVerseCount: Int get() = loaded?.verseNumbers?.size ?: 0

    /**
     * The distinct (lowercased) surface words of one verse of the
     * Strong's-marked English module. Unlike the lemma numbers these
     * ARE comparable across testaments (G-numbers and H-numbers never
     * overlap), so they drive the OT-quotation-in-NT detection.
     */
    fun wordsForVerse(book: Int, chapter: Int, verse: Int): Set<String> =
        loaded?.verseWords?.get(verseKey(book, chapter, verse)).orEmpty()

    /** Visit every indexed verse's (canonical key, lemma set, surface-word
     *  set). Used by the cross-reference similarity scan; the index is
     *  immutable after publication, so the traversal is safe from any
     *  thread. Not `inline` — the traversal reads private state, which an
     *  inline public function is not allowed to do. */
    fun forEachIndexedVerse(action: (String, Set<String>, Set<String>) -> Unit) {
        val data = loaded ?: return
        data.verseNumbers.forEach { (key, numbers) ->
            action(key, numbers, data.verseWords[key].orEmpty())
        }
    }

    /**
     * Related lemmas of [number]: every number this entry references
     * plus every number referencing it (e.g. H430 references H433, and
     * any entry mentioning H430 appears here). Excludes the number
     * itself; sorted by number.
     */
    fun relatedNumbers(number: String): List<String> =
        loaded?.relations?.get(number).orEmpty().filter { it != number }

    /**
     * Search the lexicon by Strong's number (prefix), transliteration,
     * original word (root or surface form) or pronunciation. Returns up
     * to [maxResults] hits, exact number matches first.
     */
    fun search(query: String, maxResults: Int = 80): List<SearchHit> {
        val q = normalize(query)
        if (q.isEmpty()) return emptyList()
        val defs = StrongsRepository.allDefinitions()
        if (defs.isEmpty()) return emptyList()
        val defByNumber = defs.associateBy { it.number }

        val priorities = HashMap<String, Int>()
        val hits = LinkedHashMap<String, SearchHit>()
        fun add(priority: Int, number: String) {
            if (hits.containsKey(number)) return
            val def = defByNumber[number] ?: return
            priorities[number] = priority
            hits[number] = SearchHit(def.number, def.rootWord, def.transliteration)
        }

        // Exact number (G25 / h25 …) sorts first.
        defByNumber[q.uppercase()]?.let { add(0, it.number) }
        // Number prefix (g2 → G2xxx).
        val upper = q.uppercase()
        for (def in defs) {
            if (def.number.length > 2 && def.number.startsWith(upper)) add(1, def.number)
        }
        // Transliteration / original word / pronunciation contains. Both
        // sides are diacritic-stripped, so a plain "elohim" or "agapao"
        // finds entries whose transliteration carries combining marks
        // ("'ĕlōhı̄ym", "agapaō").
        for (def in defs) {
            if (normalize(def.transliteration).contains(q) ||
                normalize(def.rootWord).contains(q) ||
                normalize(def.pronunciation).contains(q)
            ) {
                add(2, def.number)
            }
        }
            // Surface words from the occurrence index (original or translated).
        // Exact word matches rank above prefix matches above loose
        // contains-matches, so typing "loved" surfaces the G25 lemma
        // before dozens of "beloved"-flavoured contains-hits flood the
        // (capped) result list.
        loaded?.words?.let { wordIndex ->
            for ((word, numbers) in wordIndex) {
                val normalized = normalize(word)
                val priority = when {
                    normalized == q -> 3
                    normalized.startsWith(q) -> 4
                    normalized.contains(q) -> 5
                    else -> null
                } ?: continue
                for (number in numbers) add(priority, number)
            }
        }

        return hits.entries
            .sortedWith(compareBy({ priorities[it.key] }, { it.key }))
            .map { it.value }
            .take(maxResults)
    }

    /** Lowercase and strip combining marks (accents, macrons) so plain
     *  ASCII queries match diacritic-laden transliterations. Strong's
     *  Hebrew transliterations spell yod with the dotless \u0131
     *  ("'e\u0306lo\u0304h\u0131\u0304ym"), which NFD leaves alone —
     *  it is folded to a plain "i" too. */
    private fun normalize(s: String): String =
        java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD)
            .replace(NON_SPACING, "")
            .replace('\u0131', 'i')

    private val NON_SPACING = Regex("\\p{M}")

    private fun build(
        greekBooks: List<Book>,
        englishBooks: List<Book>
    ): Quad {
        val occ = HashMap<String, MutableList<Occurrence>>()
        val words = HashMap<String, MutableSet<String>>()

        // Greek original New Testament (trparsed): word G#### [TVM] CODE.
        for (book in greekBooks) {
            for (chapter in book.chapters) {
                for (verse in chapter.verses) {
                    for (match in PARSED_REGEX.findAll(verse.text)) {
                        val word = match.groupValues[1]
                        val number = match.groupValues[2]
                        val tvm = match.groupValues.getOrNull(3)?.takeIf { it.isNotEmpty() }
                        val parsing = (4..6).mapNotNull { index ->
                            match.groupValues.getOrNull(index)?.takeIf { it.isNotEmpty() }
                        }.joinToString(" ")
                        val code = parsing.ifEmpty { tvm }
                        occ.getOrPut(number) { mutableListOf() }.add(
                            Occurrence(
                                book = book.book,
                                chapter = chapter.chapter,
                                verse = verse.verse,
                                word = word,
                                number = number,
                                parsing = code,
                                language = LanguageKind.GREEK,
                                sourceLabel = GREEK_LABEL
                            )
                        )
                        words.getOrPut(word.lowercase()) { mutableSetOf() }.add(number)
                    }
                }
            }
        }

        // Whole English Bible with Strong's markup (kjv_strongs): the
        // Hebrew OT (H-numbers, English words) and the Greek NT.
        for (book in englishBooks) {
            for (chapter in book.chapters) {
                for (verse in chapter.verses) {
                    for (match in STRONGS_REGEX.findAll(verse.text)) {
                        val word = match.groupValues[1]
                        val number = match.groupValues[2]
                        occ.getOrPut(number) { mutableListOf() }.add(
                            Occurrence(
                                book = book.book,
                                chapter = chapter.chapter,
                                verse = verse.verse,
                                word = word,
                                number = number,
                                parsing = null,
                                language = languageOf(number),
                                sourceLabel = ENGLISH_LABEL
                            )
                        )
                        words.getOrPut(word.lowercase()) { mutableSetOf() }.add(number)
                    }
                }
            }
        }

        // Sort each number's occurrences canonically (Greek original
        // first, then by book/chapter/verse).
        val sortedOcc = HashMap<String, List<Occurrence>>(occ.size * 2)
        for ((number, list) in occ) {
            sortedOcc[number] = list.sortedWith(
                compareBy<Occurrence> { if (it.language == LanguageKind.GREEK) 0 else 1 }
                    .thenBy { it.book }
                    .thenBy { it.chapter }
                    .thenBy { it.verse }
            )
        }

        val sortedWords = HashMap<String, List<String>>(words.size * 2)
        for ((word, numbers) in words) sortedWords[word] = numbers.sorted()

        // Per-verse Strong's number sets from the English module (the
        // whole Bible), for the derived cross-reference similarity search.
        // Built with mutable sets (one allocation per verse, not per
        // token), then frozen into immutable snapshots.
        val mutableVerseNumbers = HashMap<String, MutableSet<String>>()
        for ((number, list) in occ) {
            for (item in list) {
                if (item.sourceLabel == ENGLISH_LABEL) {
                    mutableVerseNumbers
                        .getOrPut(verseKey(item.book, item.chapter, item.verse)) {
                            mutableSetOf()
                        }
                        .add(number)
                }
            }
        }
        val verseNumbers = HashMap<String, Set<String>>(mutableVerseNumbers.size * 2)
        for ((key, set) in mutableVerseNumbers) verseNumbers[key] = set.toSet()

        // Per-verse FULL-TEXT word sets (markup stripped). Function words
        // are rarely Strong's-tagged, so tagged tokens alone would miss
        // the wording of quotations — split the plain text instead. These
        // sets cross the H/G number-space boundary (which lemmas never
        // do), so they back the OT-quotation-in-NT detection.
        val mutableVerseWords = HashMap<String, Set<String>>()
        for (book in englishBooks) {
            for (chapter in book.chapters) {
                for (verse in chapter.verses) {
                    val words = MARKUP_REGEX.replace(verse.text, "")
                        .split(NON_WORD_REGEX)
                        .map { it.lowercase() }
                        .filter { it.isNotEmpty() }
                        .toSet()
                    if (words.isNotEmpty()) {
                        mutableVerseWords[
                            verseKey(book.book, chapter.chapter, verse.verse)
                        ] = words
                    }
                }
            }
        }
        val verseWords = HashMap<String, Set<String>>(mutableVerseWords.size * 2)
        verseWords.putAll(mutableVerseWords)

        // Relation graph + roots from the Strong's entry prose.
        val outRelations = HashMap<String, MutableSet<String>>()
        val rootMap = HashMap<String, String>()
        for (def in StrongsRepository.allDefinitions()) {
            val refs = ENTRY_REF_REGEX.findAll(def.entry)
                .map { it.groupValues[1] }
                .filter { it != def.number }
                .toSet()
            if (refs.isNotEmpty()) {
                outRelations.getOrPut(def.number) { mutableSetOf() }.addAll(refs)
                for (ref in refs) outRelations.getOrPut(ref) { mutableSetOf() }.add(def.number)
            }
            rootOf(def)?.let { rootMap[def.number] = it }
        }
        val sortedRelations = HashMap<String, List<String>>(outRelations.size * 2)
        for ((number, refs) in outRelations) sortedRelations[number] = refs.sorted()

        return Quad(sortedOcc, sortedWords, sortedRelations, rootMap, verseNumbers, verseWords)
    }

    private fun rootOf(def: StrongsRepository.StrongsDefinition): String? {
        val entry = def.entry
        PLURAL_OF_REGEX.find(entry)?.let { return it.groupValues[1] }
        FROM_ROOT_REGEX.find(entry)?.let { return it.groupValues[1] }
        COMPARE_REGEX.find(entry)?.let { return it.groupValues[1] }
        return null
    }

    private data class Quad(
        val first: Map<String, List<Occurrence>>,
        val second: Map<String, List<String>>,
        val third: Map<String, List<String>>,
        val fourth: Map<String, String>,
        val fifth: Map<String, Set<String>>,
        val sixth: Map<String, Set<String>>
    )

    /** The fully-built lexicon snapshot, published atomically. */
    private class LoadedData(
        val occurrences: Map<String, List<Occurrence>>,
        val words: Map<String, List<String>>,
        val relations: Map<String, List<String>>,
        val roots: Map<String, String>,
        val verseNumbers: Map<String, Set<String>>,
        val verseWords: Map<String, Set<String>>
    )
}
