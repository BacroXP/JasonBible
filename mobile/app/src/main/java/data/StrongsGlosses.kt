package data


/**
 * The "can be translated as" alternatives of one Strong's entry: the
 * comma-separated gloss list the concordance prints after its definition
 * (`… : - beginning, chief (-est), first (-fruits) …`). Parenthetical
 * qualifiers and the `X` marker are dropped so the reader gets plain
 * alternatives instead of the raw concordance code.
 *
 * Shared by the word-study panels (clicked Greek/Hebrew Strong's numbers)
 * and the Hebrew verse panel, which resolves a verse's words to lemmas by
 * consonant skeleton and needs the same alternatives.
 */
internal fun strongsGlossList(entry: String, max: Int = 5): List<String> {
    val plain = entry
        .replace(Regex("<[^>]+>"), "")
        .replace("&quot;", "\"")
        .replace("&amp;", "&")
    // The gloss list starts after the definition's `: -` marker.
    val marker = Regex(":\\s*-\\s*").findAll(plain).lastOrNull()
    var tail = if (marker != null) plain.substring(marker.range.last + 1) else ""
    tail = tail.substringBefore("Compare ")
    // Qualifiers like `(gods)`, `(-fruits, part, time)` or `[-ly, -ward]`
    // are notes on the gloss, not alternatives; drop them (twice, since a
    // few entries nest them).
    val paren = Regex("\\([^)]*\\)")
    val bracket = Regex("\\[[^]]*\\]")
    while (paren.containsMatchIn(tail)) tail = tail.replace(paren, "")
    while (bracket.containsMatchIn(tail)) tail = tail.replace(bracket, "")

    val allowed = Regex("[A-Za-z][A-Za-z' \\-]*")
    return tail.split(',', ';')
        .map { it.trim().trim('.', ':', '-', ' ').trim() }
        .map { gloss ->
            val marker2 = listOf("X ", "\u00D7 ").firstOrNull { gloss.startsWith(it) }
            if (marker2 != null) gloss.removePrefix(marker2).trim() else gloss
        }
        .filter { gloss ->
            gloss.isNotEmpty() &&
                gloss.length <= 28 &&
                gloss[0].isLetter() &&
                allowed.matches(gloss)
        }
        .distinct()
        .take(max)
}
