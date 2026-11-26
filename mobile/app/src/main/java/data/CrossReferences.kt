package data



// ---------------------------------------------------------------------------
// Cross references (Querverweise)
//
// No curated cross-reference dataset is bundled, so nothing is invented.
// Instead, cross references are DERIVED from the real text itself via the
// Strong's-marked English module (`kjv_strongs`, the whole Bible):
//   • Same-testament similarity uses shared LEMMAS (Strong's numbers) —
//     the synoptic Gospel parallels and repeated Psalms surface naturally.
//   • Old-Testament quotations / allusions in the New Testament use the
//     shared SURFACE ENGLISH WORDS, because H-numbers and G-numbers are
//     disjoint number spaces and can never overlap (a Greek NT verse
//     quoting the Hebrew OT shares wording, not lemmas).
//
// The UI distinguishes three derived kinds (so the spec's request to tell
// "echte Querverweise" from "thematisch ähnliche Stellen" apart is met):
//   • PARALLEL   — very high lemma overlap (parallel accounts)
//   • OT_QUOTE   — a New-Testament verse echoing an Old-Testament verse
//   • THEMATIC   — solid overlap, looser than a parallel
//
// A curated-data slot (CrossReferences.curatedFor) is prepared for future
// bundled reference sets; today it returns nothing.
// ---------------------------------------------------------------------------

object CrossReferences {

    /** How a derived reference relates to the source verse. */
    enum class Kind(val label: String) {
        PARALLEL("Parallel passage"),
        OT_QUOTE("Old-Testament quotation / allusion"),
        THEMATIC("Thematically related")
    }

    /** One related verse, with its similarity figures. */
    data class Reference(
        val book: Int,
        val chapter: Int,
        val verse: Int,
        val kind: Kind,
        val sharedLemmas: Int,
        val jaccard: Float
    )

    // Lemma-overlap thresholds for same-testament pairs, tuned so real
    // parallels (feeding of the 5,000, the transfiguration) land in
    // PARALLEL while loose topical overlap stays THEMATIC.
    private const val PARALLEL_SHARED = 7
    private const val PARALLEL_JACCARD = 0.42f
    private const val THEMATIC_SHARED = 4
    private const val THEMATIC_JACCARD = 0.16f

    // Word-overlap thresholds for NT→OT quotation detection.
    private const val OT_QUOTE_WORDS = 5
    private const val OT_QUOTE_WORD_JACCARD = 0.22f

    /** Number of verses in the derived index (0 until loaded). */
    val indexedVerseCount: Int get() = WordLexicon.indexedVerseCount

    /**
     * Build the underlying verse index once (reuses the lexicon's single
     * parse of `kjv_strongs`). Safe to call repeatedly.
     */
    suspend fun ensureLoaded() {
        WordLexicon.ensureLoaded()
    }

    /**
     * Related verses for (book, chapter, verse), derived from shared
     * lemmas / wording and sorted by relevance (kind, then overlap).
     * The source verse itself and same-chapter verses are excluded (a
     * chapter's neighbouring verses always share its topic). Returns an
     * empty list when the verse has no Strong's markup or the index isn't
     * loaded. [limit] caps the result.
     */
    fun referencesFor(
        book: Int,
        chapter: Int,
        verse: Int,
        limit: Int = 40
    ): List<Reference> {
        val source = WordLexicon.numbersForVerse(book, chapter, verse)
        if (source.isEmpty()) return emptyList()
        val sourceKey = verseKey(book, chapter, verse)
        val sourceWords = WordLexicon.wordsForVerse(book, chapter, verse)

        val out = ArrayList<Reference>(64)
        WordLexicon.forEachIndexedVerse { candidateKey, numbers, words ->
            if (candidateKey == sourceKey) return@forEachIndexedVerse
            // Skip same-chapter verses: they share the chapter's topic by
            // construction and would dominate the results.
            if (sameChapter(candidateKey, book, chapter)) return@forEachIndexedVerse

            val (candidateBook, candidateChapter, candidateVerse) = splitKey(candidateKey)
            val kind = when {
                // NT verse echoing an OT verse: lemmas can't overlap
                // across the H/G number spaces, so compare wording.
                book > 39 && candidateBook <= 39 ->
                    classifyOtQuote(sourceWords, words)

                else -> classifySameTestament(source, numbers)
            } ?: return@forEachIndexedVerse

            val shared = source.intersect(numbers).size
            val jaccard = if (source.size + numbers.size - shared > 0) {
                shared.toFloat() / (source.size + numbers.size - shared)
            } else {
                0f
            }
            out += Reference(
                book = candidateBook,
                chapter = candidateChapter,
                verse = candidateVerse,
                kind = kind,
                sharedLemmas = shared,
                jaccard = jaccard
            )
        }

        val rank = mapOf(
            Kind.PARALLEL to 0,
            Kind.OT_QUOTE to 1,
            Kind.THEMATIC to 2
        )
        return out
            .sortedWith(
                compareBy<Reference>({ rank[it.kind] ?: 3 }, { -it.jaccard })
            )
            .take(limit)
    }

    /** OT-quotation detection: shared surface words across the testaments. */
    private fun classifyOtQuote(
        sourceWords: Set<String>,
        candidateWords: Set<String>
    ): Kind? {
        if (sourceWords.isEmpty() || candidateWords.isEmpty()) return null
        val shared = sourceWords.intersect(candidateWords).size
        if (shared < OT_QUOTE_WORDS) return null
        val jaccard = shared.toFloat() / (sourceWords.size + candidateWords.size - shared)
        return if (jaccard >= OT_QUOTE_WORD_JACCARD) Kind.OT_QUOTE else null
    }

    /** Same-testament classification via shared Strong's lemmas. */
    private fun classifySameTestament(
        source: Set<String>,
        candidate: Set<String>
    ): Kind? {
        val shared = source.intersect(candidate).size
        if (shared < THEMATIC_SHARED) return null
        val jaccard = shared.toFloat() / (source.size + candidate.size - shared)
        return when {
            shared >= PARALLEL_SHARED && jaccard >= PARALLEL_JACCARD -> Kind.PARALLEL
            shared >= THEMATIC_SHARED && jaccard >= THEMATIC_JACCARD -> Kind.THEMATIC
            else -> null
        }
    }

    private fun sameChapter(key: String, book: Int, chapter: Int): Boolean {
        val firstColon = key.indexOf(':')
        val secondColon = key.indexOf(':', firstColon + 1)
        if (firstColon < 0 || secondColon < 0) return false
        return key.substring(0, firstColon).toIntOrNull() == book &&
            key.substring(firstColon + 1, secondColon).toIntOrNull() == chapter
    }

    private fun splitKey(key: String): Triple<Int, Int, Int> {
        val firstColon = key.indexOf(':')
        val secondColon = key.indexOf(':', firstColon + 1)
        return Triple(
            key.substring(0, firstColon).toIntOrNull() ?: 0,
            key.substring(firstColon + 1, secondColon).toIntOrNull() ?: 0,
            key.substring(secondColon + 1).toIntOrNull() ?: 0
        )
    }

    private fun verseKey(book: Int, chapter: Int, verse: Int) = "$book:$chapter:$verse"

    /**
     * Curated (non-derived) cross references — prepared for a future
     * bundled dataset (e.g. a classical reference Bible). Today no such
     * data ships, so this always returns an empty list and the panel
     * shows a short note instead of pretending to have the data.
     */
    fun curatedFor(book: Int, chapter: Int, verse: Int): List<Reference> = emptyList()
}
