package data


// ---------------------------------------------------------------------------
// Morphology explainer
//
// The bundled parsed Greek module (trparsed) tags every word with a
// compact morphological code — e.g. `V-AAI-3S` (Verb, Aorist Active
// Indicative, 3rd person singular) or `N-NSF` (Noun, Nominative Singular
// Feminine) — alongside its Strong's number. Instead of showing the raw
// code, [explain] expands it into human-readable labels so the word-study
// views can render "Tense: Aorist · Voice: Active · Mood: Indicative ·
// Person: 3rd · Number: Singular".
//
// The code scheme (Robinson/TAG-style) has three shapes:
//   • simple tags        ADV, CONJ, PREP, PRT, INTJ, …
//   • nominal tags       N-NSF, A-ASN, P-1GS, T-GSM, D-ASN, … where the
//                        suffix is case+number(+gender), optionally
//                        preceded by a person digit for pronouns.
//   • verb tags          V-AAI-3S, V-2AAI (2nd aorist), V-PAN (infinitive),
//                        V-PAP-NSM (participle with case suffix), …
//
// Unknown letters are left as-is rather than guessed, so a code from a
// future module revision degrades gracefully instead of mislabelling.
// ---------------------------------------------------------------------------

object MorphologyExplainer {

    private val TENSES = mapOf(
        'P' to "Present", 'I' to "Imperfect", 'F' to "Future", 'A' to "Aorist",
        'R' to "Perfect", 'L' to "Pluperfect"
    )

    // D / O / N mark the ambiguous middle-or-passive voice in this scheme.
    private val VOICES = mapOf(
        'A' to "Active", 'M' to "Middle", 'P' to "Passive",
        'D' to "Middle/Passive", 'O' to "Middle/Passive", 'N' to "Middle/Passive"
    )

    private val MOODS = mapOf(
        'I' to "Indicative", 'S' to "Subjunctive", 'O' to "Optative",
        'M' to "Imperative", 'N' to "Infinitive", 'P' to "Participle"
    )

    private val CASES = mapOf(
        'N' to "Nominative", 'V' to "Vocative", 'G' to "Genitive",
        'D' to "Dative", 'A' to "Accusative"
    )

    private val GENDERS = mapOf(
        'M' to "Masculine", 'F' to "Feminine", 'N' to "Neuter"
    )

    private val PARTS_OF_SPEECH = mapOf(
        'V' to "Verb", 'N' to "Noun", 'A' to "Adjective",
        'D' to "Demonstrative pronoun", 'P' to "Personal pronoun",
        'R' to "Relative pronoun", 'T' to "Article", 'C' to "Cardinal numeral",
        'I' to "Interrogative pronoun", 'X' to "Indefinite pronoun",
        'F' to "Correlative pronoun", 'K' to "Correlative pronoun",
        'Q' to "Correlative pronoun", 'S' to "Possessive pronoun"
    )

    // Codes that carry no parsing suffix.
    private val SIMPLE = mapOf(
        "ADV" to "Adverb",
        "CONJ" to "Conjunction",
        "PREP" to "Preposition",
        "PRT" to "Particle",
        "INTJ" to "Interjection",
        "COND" to "Conditional conjunction",
        "ATT" to "Attributive",
        "ARAM" to "Aramaic word (transliterated)",
        "HEB" to "Hebrew word (transliterated)"
    )

    private val PERSONS = mapOf('1' to "1st person", '2' to "2nd person", '3' to "3rd person")
    private val NUMBERS = mapOf('S' to "Singular", 'P' to "Plural")

    /**
     * Expand a morphological code into readable labels, e.g.
     * `V-AAI-3S` → ["Verb", "Aorist", "Active", "Indicative",
     * "3rd person", "Singular"]. Unknown input is returned as a single
     * label with the raw code, so the UI always has something to show.
     */
    fun explain(code: String): List<String> {
        val c = code.trim()
        if (c.isEmpty()) return listOf("(no parsing data)")
        SIMPLE[c]?.let { return listOf(it) }
        val dash = c.indexOf('-')
        if (dash <= 0) return listOf(c)
        val posLetter = c[0]
        val pos = PARTS_OF_SPEECH[posLetter] ?: return listOf(c)
        val rest = c.substring(dash + 1)
        if (posLetter == 'N' && rest == "PRI") return listOf(pos, "Proper noun")
        return if (posLetter == 'V') explainVerb(pos, rest) else explainNominal(pos, rest)
    }

    /** `AAI3S`, `2AAI`, `PAN`, `PAP-NSM`, `2AAP-NPM`, `AAI-3S` … */
    private fun explainVerb(pos: String, rest: String): List<String> {
        val (core, suffix) = if ('-' in rest) {
            rest.substringBefore('-') to rest.substringAfter('-')
        } else {
            rest to null
        }
        // A leading digit marks an alternate stem (2nd aorist, …). Only a
        // digit in the FIRST position counts — later digits belong to the
        // finite person/number suffix (AAI3S → 3rd person singular).
        val hasStem = core.isNotEmpty() && core[0].isDigit()
        val start = if (hasStem) 1 else 0
        val stemDigit = if (hasStem) core[0] else null

        // Finite verbs carry person + number: appended to the core (AAI3S)
        // or split by a second dash (V-AAI-3S — the form the bundled
        // trparsed module actually uses). A dashed suffix that starts with
        // a CASE letter is a participle's case suffix instead (PAP-NSM).
        val caseSuffix: String?
        var person: Char? = null
        var number: Char? = null
        if (suffix != null && suffix.isNotEmpty() && suffix[0].isDigit()) {
            person = suffix[0]
            number = suffix.getOrNull(1)
            caseSuffix = null
        } else if (suffix != null) {
            caseSuffix = suffix
        } else {
            caseSuffix = null
            // Only treat the core's tail as person+number when it really
            // is one: the second-to-last char must be a person digit and
            // the last a number letter (S/P). A dash-less participle
            // suffix like …NSM ends in two CASE letters and is skipped,
            // so its case info flows into the case decoding below instead
            // of being silently dropped.
            if (core.length >= start + 5 &&
                core[core.length - 2].isDigit() &&
                NUMBERS.containsKey(core.last())
            ) {
                person = core[core.length - 2]
                number = core.last()
            }
        }
        val tvaEnd = if (person != null && suffix == null) core.length - 2 else core.length
        val tva = core.substring(start, tvaEnd)

        val out = mutableListOf(pos)
        TENSES[tva.getOrNull(0)]?.let { out += it }
        VOICES[tva.getOrNull(1)]?.let { out += it }
        MOODS[tva.getOrNull(2)]?.let { out += it }
        stemDigit?.let { out += "${it}. stem" }
        person?.let { PERSONS[it]?.let { p -> out += p } }
        number?.let { NUMBERS[it]?.let { n -> out += n } }
        if (caseSuffix != null) out += explainCaseNumberGender(caseSuffix)
        return out
    }

    /** `NSF`, `ASN`, `PRI`, `1GS`, `2DP`, `NUI` … */
    private fun explainNominal(pos: String, rest: String): List<String> {
        val out = mutableListOf(pos)
        if (rest == "PRI") { out += "Proper noun"; return out }
        if (rest == "NUI") { out += "Numeral"; return out }
        // Pronouns may lead with a person digit (P-1GS → 1st person, Gen.
        // Sing.); the remainder is case + number (+ gender).
        val person = rest.firstOrNull(Char::isDigit)
        val suffix = if (person != null) rest.drop(1) else rest
        person?.let { PERSONS[it]?.let { p -> out += p } }
        out += explainCaseNumberGender(suffix)
        return out
    }

    /** Decode a case+number(+gender) suffix such as `NSM`, `GSF`, `DP`. */
    private fun explainCaseNumberGender(suffix: String): List<String> {
        val out = mutableListOf<String>()
        suffix.getOrNull(0)?.let { CASES[it]?.let { c -> out += c } }
        suffix.getOrNull(1)?.let { NUMBERS[it]?.let { n -> out += n } }
        suffix.getOrNull(2)?.let { GENDERS[it]?.let { g -> out += g } }
        // Unknown letters (rare vendor extensions) are surfaced, not hidden.
        suffix.forEach { ch ->
            if (ch !in CASES && ch !in NUMBERS && ch !in GENDERS && !ch.isDigit()) {
                out += "($ch?)"
            }
        }
        return out
    }
}
