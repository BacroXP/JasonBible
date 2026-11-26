package data


/**
 * Hebrew word study for the verse panel: splitting a Hebrew verse into
 * clickable words and resolving each one to the translation options of its
 * Strong's entry, so tapping a word can say "…can be translated as …".
 *
 * The bundled Hebrew module (`he_modern`) ships as plain unpointed text
 * without Strong's markup, so the lookup matches on the consonant skeleton:
 * niqqud / cantillation is stripped from both sides, then inseparable
 * prefixes (וְ / הַ / בְּ / לְ / מִ / כְּ …) are peeled off and the remainder
 * must match a lemma EXACTLY. Loose matching (a word merely starting with a
 * lemma) is deliberately avoided: it confidently turned "the heavens" into
 * "water" and "I shall lack" into "brother". Words that still find no entry
 * simply have no options — the verse panel dims them instead of inventing a
 * translation.
 */
object HebrewLexicon {

    /** One lemma reachable from a consonant skeleton. */
    private data class Entry(
        val number: String,
        val options: List<String>
    )

    /** Niqqud (vowel points) and cantillation accents. */
    private val MARKS = Regex("[\\u0591-\\u05C7]")

    /** Maqaf, sof pasuq, geresh / gershayim, quotes and ASCII punctuation. */
    private val PUNCT = Regex("[\\u05BE\\u05C0\\u05C3\\u05F3\\u05F4\"'.,;:!?\\-\\u2013\\u2014]")

    private val SPACES = Regex("\\s+")

    private const val MAX_OPTIONS = 5

    /**
     * Inseparable prepositions, the article, the conjunction, the relative
     * particle and the verbal prefixes (י/ת/א/נ). Peeling them off only ever
     * succeeds when the remainder is a real lemma, so root letters that
     * happen to start a word cost nothing.
     */
    private val PREFIX_LETTERS =
        setOf('\u05D1', '\u05D4', '\u05D5', '\u05DB', '\u05DC', '\u05DE', '\u05E9', '\u05D9', '\u05EA', '\u05D0', '\u05E0')

    /** The consonant skeleton used for matching (points and punctuation out). */
    fun normalize(word: String): String =
        PUNCT.replace(MARKS.replace(word, ""), "").trim()

    /** Words of [verse] in reading order, punctuation-free. */
    fun words(verse: String): List<String> =
        SPACES.split(PUNCT.replace(verse, " "))
            .map { it.trim() }
            .filter { word -> word.isNotEmpty() && word.any { it.isLetter() } }

    /**
     * The skeletons to try for [word], most specific first: the word itself,
     * then with one and then two leading prefix letters removed.
     */
    private fun skeletons(word: String): List<String> {
        val needle = normalize(word)
        if (needle.length < 2) return emptyList()
        val out = ArrayList<String>(3)
        out += needle
        var bare = needle
        var stripped = 0
        while (stripped < 2 && bare.length > 2 && bare.first() in PREFIX_LETTERS) {
            bare = bare.drop(1)
            stripped++
            if (bare.length >= 2) out += bare
        }
        return out
    }

    // Skeleton → lemmas. Rebuilt only when the definition collection
    // changes: the caller passes `StrongsRepository.allDefinitions()`, a
    // view over one immutable map, so size plus the first/last number is
    // enough to tell snapshots apart without re-hashing the whole concordance.
    private var indexKey: Triple<Int, String, String>? = null
    private var indexMap: Map<String, List<Entry>> = emptyMap()

    private fun index(definitions: Collection<StrongsRepository.StrongsDefinition>): Map<String, List<Entry>> {
        val key = Triple(
            definitions.size,
            definitions.firstOrNull()?.number.orEmpty(),
            definitions.lastOrNull()?.number.orEmpty()
        )
        synchronized(this) {
            if (indexKey == key) return indexMap
            val map = HashMap<String, MutableList<Entry>>(definitions.size * 2)
            for (def in definitions) {
                if (!def.number.startsWith("H", ignoreCase = true)) continue
                // No gloss list means the concordance offers no translation
                // for the lemma (e.g. H853, the untranslatable object
                // marker) — leave it out so its words read as untranslatable.
                val options = strongsGlossList(def.entry, MAX_OPTIONS)
                if (options.isEmpty()) continue
                val entry = Entry(def.number, options)
                for (skeleton in normalize(def.rootWord).split(SPACES)) {
                    if (skeleton.length < 2) continue
                    map.getOrPut(skeleton) { ArrayList(2) } += entry
                }
            }
            indexKey = key
            indexMap = map
            return map
        }
    }

    private fun optionsOf(word: String, index: Map<String, List<Entry>>, max: Int): List<String> {
        for (skeleton in skeletons(word)) {
            val entries = index[skeleton] ?: continue
            val options = entries
                .flatMap { it.options }
                .distinct()
                .take(max)
            if (options.isNotEmpty()) return options
        }
        return emptyList()
    }

    /**
     * Plain-language translation options for [word] (at most [max]), taken
     * from the gloss lists of the lemmas on its consonant skeleton. Empty
     * when the word has no concordance entry — the caller shows it dimmed.
     */
    fun translationOptions(
        word: String,
        definitions: Collection<StrongsRepository.StrongsDefinition>,
        max: Int = MAX_OPTIONS
    ): List<String> = optionsOf(word, index(definitions), max)

    /**
     * The same lookup for every word of one verse, resolved from a single
     * pass over the concordance. Words with no options map to an empty list.
     */
    fun translationOptionsForVerse(
        words: List<String>,
        definitions: Collection<StrongsRepository.StrongsDefinition>,
        max: Int = MAX_OPTIONS
    ): Map<String, List<String>> {
        val index = index(definitions)
        return words.associateWith { word -> optionsOf(word, index, max) }
    }
}
