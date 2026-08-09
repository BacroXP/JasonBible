package ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import data.SoundEvent
import data.SoundManager
import data.StrongsRepository


// ---------------------------------------------------------------------------
// Word study (Strong's concordance)
//
// Two bundled translations carry per-word Strong's markup:
//
//  1. "KJV with Strongs" — `word{G####}` with an optional
//     `{(G####)}` tense/voice/mood lemma code, e.g. `loved{G25}{(G5656)}`.
//  2. "TR Parsed" (Greek NT, trparsed) — space-separated
//     `word G#### [G####…] CODE`, e.g. `ηγαπησεν G25 G5656 V-AAI-3S`
//     (first G-number = lemma, further G-numbers = TVM codes, final
//     token = morphological parsing code).
//
// When a verse carries either markup, every token's `G`/`H` number is
// rendered as a clickable span (a plain `Text` with `LinkAnnotation`s —
// non-annotated text still falls through to the verse's own clickable, so
// verse selection keeps working). In the parsed module the TVM codes are
// clickable too. Clicking a number opens the [WordStudyPanel] under the
// verse with the decoded Strong's definition, root, transliteration and
// pronunciation from `strongs_definitions.json`; clicking a TVM code
// shows that code's tense/voice/mood explanation.
// ---------------------------------------------------------------------------

/** The word-study identity of a clicked token, used to render its panel. */
internal data class StudyWordToken(
    val bookNumber: Int,
    val chapter: Int,
    val verse: Int,
    val word: String,
    val number: String,   // e.g. "G25" / "H1"
    val parsing: String?  // e.g. "(G5656)", or null when absent
)

/** One parsed word-study token in a verse's raw text. */
internal data class StrongsToken(
    val word: String,
    val number: String,
    val parsing: String?,
    val start: Int,   // source offset of the whole token (start..end)
    val end: Int,
    // Parsed Greek module only: the TVM (tense/voice/mood) code number
    // that follows the lemma, e.g. `G5656` in `ηγαπησεν G25 G5656
    // V-AAI-3S`. Rendered clickable so its explanation is reachable.
    val tvm: String? = null
)

// `word{G1063}` with an optional `{(G5656)}` follow-up. The word may carry
// apostrophes/hyphens; punctuation (`,`, `.`) always sits OUTSIDE the braces.
private val STRONGS_TOKEN = Regex("([^\\s{}]+)\\{([GH]\\d+)}(?:\\{(\\([^)]*\\))} )?")

internal fun parseStrongsTokens(text: String): List<StrongsToken> {
    val tokens = ArrayList<StrongsToken>()
    for (match in STRONGS_TOKEN.findAll(text)) {
        tokens += StrongsToken(
            word = match.groupValues[1],
            number = match.groupValues[2],
            parsing = match.groupValues[3].ifEmpty { null },
            start = match.range.first,
            end = match.range.last + 1
        )
    }
    return tokens
}

// Parsed Greek module (trparsed) tokens: `word G#### [G#### …] CODE`. The
// first G-number is the lemma's Strong's number; any further G-numbers are
// the TVM (tense/voice/mood) codes; the final token is the morphological
// parsing code (e.g. `V-AAI-3S`). Both the word and the trailing code are
// required NOT to be G-numbers so the optional groups can't swallow a bare
// number and mis-parse a neighbour. Unmatched fragments (e.g. the module's
// `VAR2:` / `}` variant markers) stay as plain text between tokens.
private val PARSED_TOKEN =
    Regex("((?!G\\d)\\S+) (G\\d+)(?: (G\\d+))?(?: (G\\d+))?(?: (G\\d+))? ((?!G\\d)\\S+)")

// Suffix distinguishing a TVM-code link tag from a lemma-number link tag
// (both encode the token's source offset; the listener strips it back).
// internal so the word-aligned interlinear's per-word links reuse it.
internal const val TVM_TAG_SUFFIX = ":tvm"

/**
 * Parses the space-separated `word G#### [G####…] CODE` tokens of the
 * bundled parsed Greek module (trparsed), e.g.
 * `ηγαπησεν G25 G5656 V-AAI-3S`. The lemma's first G-number becomes the
 * token's clickable [StrongsToken.number]; everything after it (further
 * G-numbers plus the parsing code, e.g. `G5656 V-AAI-3S`) becomes
 * [StrongsToken.parsing] — the same slot KJV's `(G5656)` occupies.
 */
internal fun parseParsedTokens(text: String): List<StrongsToken> {
    val tokens = ArrayList<StrongsToken>()
    for (match in PARSED_TOKEN.findAll(text)) {
        // Group 2 is the lemma's Strong's number; group 3 (if present) is
        // the TVM code number (e.g. `G5656`); groups 4-6 are any further
        // numbers plus the trailing morphological code (`V-AAI-3S`).
        val tvm = match.groupValues.getOrNull(3)?.takeIf { it.isNotEmpty() }
        val parsing = (4..6).mapNotNull { index ->
            match.groupValues.getOrNull(index)?.takeIf { it.isNotEmpty() }
        }.joinToString(" ")
        tokens += StrongsToken(
            word = match.groupValues[1],
            number = match.groupValues[2],
            parsing = parsing.ifEmpty { null },
            tvm = tvm,
            start = match.range.first,
            end = match.range.last + 1
        )
    }
    return tokens
}

/**
 * Detects which word-study markup a verse carries — the `word{G####}`
 * braces of "KJV with Strongs" or the space-separated `word G#### CODE`
 * tokens of the parsed Greek module — and returns its clickable tokens.
 * Returns an empty list when the verse has no Strong's markup at all, so
 * plain translations keep their regular rendering.
 */
internal fun parseWordStudyTokens(text: String): List<StrongsToken> {
    parseStrongsTokens(text).takeIf { it.isNotEmpty() }?.let { return it }
    return parseParsedTokens(text)
}

/** One column of the word-aligned interlinear. */
internal data class AlignedPair(
    /** The Greek TR token, or null when the English word has no Greek counterpart. */
    val greek: StrongsToken?,
    /** The English token, or null when the Greek word has no English gloss. */
    val english: StrongsToken?
)

/**
 * Word alignment between an English Strong's-tagged verse (KJV-style
 * `word{G####}` tokens) and the parsed Greek TR verse. The join key is
 * the Strong's number: each Greek token is paired with the FIRST still-
 * unclaimed English token carrying the same number (one-to-one, in
 * English reading order), so word-order differences between the two
 * languages are absorbed (e.g. Greek `ουτως γαρ ηγαπησεν` still lands on
 * "so / For / loved"). English tokens keep their positions. A Greek token
 * with no matching English word (e.g. an article the translation leaves
 * untagged) is spliced in right after the English column matched just
 * before it in Greek order, so untranslated words stay near where they
 * occur instead of piling up at the end. Numbers are compared
 * case-insensitively.
 */
internal fun alignGreekToEnglish(
    english: List<StrongsToken>,
    greek: List<StrongsToken>
): List<AlignedPair> {
    val out = ArrayList<AlignedPair>(english.size + greek.size)
    for (token in english) out += AlignedPair(greek = null, english = token)
    if (greek.isEmpty()) return out

    // Phase 1: decide each Greek token's fate — the English column it
    // pairs with (first unclaimed, same number), or, when unmatched, the
    // anchor column it should be spliced after (the last column matched
    // at that point in Greek order; -1 = before everything).
    val claimed = BooleanArray(english.size)
    val matchedWith = IntArray(greek.size) { -1 }
    val spliceAfter = IntArray(greek.size) { -1 }
    var lastMatched = -1
    for ((gi, g) in greek.withIndex()) {
        var target = -1
        for (i in english.indices) {
            if (!claimed[i] && english[i].number.equals(g.number, ignoreCase = true)) {
                claimed[i] = true
                target = i
                break
            }
        }
        if (target != -1) {
            matchedWith[gi] = target
            lastMatched = target
        } else {
            spliceAfter[gi] = lastMatched
        }
    }

    // Phase 2: attach matched tokens to their English columns.
    for ((gi, g) in greek.withIndex()) {
        val target = matchedWith[gi]
        if (target != -1) out[target] = out[target].copy(greek = g)
    }

    // Phase 3: splice unmatched tokens after their anchor column.
    // Iterating in reverse keeps consecutive tokens with the same anchor
    // in Greek order (each insert at anchor+1 pushes the previous right).
    for (gi in greek.indices.reversed()) {
        if (matchedWith[gi] != -1) continue
        out.add(spliceAfter[gi] + 1, AlignedPair(greek = greek[gi], english = null))
    }
    return out
}


/**
 * Removes Strong's markup from a verse's raw text, leaving the plain words
 * (e.g. `loved{G25}{(G5656)}` → `loved`), so a copied verse reads as normal
 * prose. No-op for verses without markup.
 */
internal fun stripStrongsMarkup(text: String): String =
    STRONGS_TOKEN.replace(text, "$1")

/**
 * Removes parsed-module markup from a verse's raw text, leaving the Greek
 * words joined by single spaces (variant markers such as `VAR2:` are
 * dropped too), so a copied verse reads as clean prose.
 */
internal fun stripParsedMarkup(text: String): String =
    parseParsedTokens(text).joinToString(" ") { it.word }

/**
 * Strips whichever Strong's markup a verse carries (braces or parsed
 * space-separated tokens); passes plain verses through unchanged. Used for
 * the "copy verse" clipboard text.
 */
internal fun stripWordStudyMarkup(text: String): String = when {
    STRONGS_TOKEN.containsMatchIn(text) -> stripStrongsMarkup(text)
    PARSED_TOKEN.containsMatchIn(text) -> stripParsedMarkup(text)
    else -> text
}


/**
 * Renders a Strong's-marked-up verse. Each token's `G####`/`H####` number
 * is drawn as a small primary superscript and is CLICKABLE (a
 * [LinkAnnotation] span); the optional parsing code is a tiny muted
 * superscript, and the token word being studied gets a soft background so
 * the open panel is easy to trace back to its word.
 *
 * Only the number span carries the link: clicks on the word, the parsing
 * code, or the plain text between tokens fall through to the enclosing
 * verse Card's clickable, preserving verse selection.
 */
@Composable
internal fun StrongsVerseText(
    text: String,
    tokens: List<StrongsToken>,
    activeNumber: String?,
    onToggle: (StrongsToken) -> Unit,
    modifier: Modifier = Modifier
) {
    val primary = MaterialTheme.colorScheme.primary
    val primaryContainer = MaterialTheme.colorScheme.primaryContainer
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val bodyFontSize = MaterialTheme.typography.bodyMedium.fontSize

    // Shared hover/press feedback for every token span.
    val linkStyles = TextLinkStyles(
        style = SpanStyle(color = primary, fontWeight = FontWeight.SemiBold),
        hoveredStyle = SpanStyle(
            color = primary,
            background = primaryContainer.copy(alpha = 0.35f)
        ),
        pressedStyle = SpanStyle(
            color = primary,
            background = primaryContainer
        )
    )

    // The annotation tag carries the token's source offset (plus a `:tvm`
    // suffix for the TVM-code span), which both identifies the token and
    // stays stable across recompositions. The tag is only exposed on the
    // Clickable subclass, so the listener narrows the received annotation
    // before reading it. A TVM-code click reports the token with its
    // `number` swapped to the TVM code so the panel shows that record's
    // tense/voice/mood explanation.
    val listener = LinkInteractionListener { link ->
        val clickable = link as? LinkAnnotation.Clickable
            ?: return@LinkInteractionListener
        val tag = clickable.tag
        val isTvm = tag.endsWith(TVM_TAG_SUFFIX)
        val start = tag.removeSuffix(TVM_TAG_SUFFIX)
            .toIntOrNull()
            ?: return@LinkInteractionListener
        val token = tokens.firstOrNull { it.start == start }
            ?: return@LinkInteractionListener
        onToggle(
            if (isTvm) token.tvm?.let { tvm -> token.copy(number = tvm) } ?: token
            else token
        )
    }

    Text(
        text = buildStrongsText(
            text = text,
            tokens = tokens,
            activeNumber = activeNumber,
            bodyFontSize = bodyFontSize,
            primary = primary,
            primaryContainer = primaryContainer,
            muted = muted,
            linkStyles = linkStyles,
            listener = listener
        ),
        style = MaterialTheme.typography.bodyMedium,
        modifier = modifier
    )
}


private fun buildStrongsText(
    text: String,
    tokens: List<StrongsToken>,
    activeNumber: String?,
    bodyFontSize: TextUnit,
    primary: Color,
    primaryContainer: Color,
    muted: Color,
    linkStyles: TextLinkStyles,
    listener: LinkInteractionListener
): AnnotatedString {
    val numberSize = bodyFontSize * 0.72f
    val parsingSize = bodyFontSize * 0.6f
    return buildAnnotatedString {
        var pos = 0
        for (token in tokens) {
            if (token.start > pos) append(text.substring(pos, token.start))
            // The studied word is highlighted whether its lemma number OR
            // its TVM code was clicked.
            val wordActive = token.number == activeNumber || token.tvm == activeNumber
            if (wordActive) {
                // Persistent background on the word being studied, so the
                // open panel is easy to trace back to its word.
                withStyle(SpanStyle(background = primaryContainer.copy(alpha = 0.3f))) {
                    append(token.word)
                }
            } else {
                append(token.word)
            }
            // Only the G/H numbers are clickable (per the "clickable G/H
            // numbers" design): the word and parsing spans stay plain so
            // their clicks fall through to the verse Card's own clickable
            // and verse selection keeps working. Compose 1.9 has no
            // SpanStyle.linkAnnotation — links are string annotations added
            // via the builder.
            val numberStart = length
            val numberActive = token.number == activeNumber
            withStyle(
                SpanStyle(
                    color = primary,
                    fontSize = numberSize,
                    baselineShift = BaselineShift.Superscript,
                    background = if (numberActive) {
                        primaryContainer.copy(alpha = 0.5f)
                    } else {
                        Color.Unspecified
                    }
                )
            ) {
                append(token.number)
            }
            addLink(
                LinkAnnotation.Clickable(
                    tag = token.start.toString(),
                    styles = linkStyles,
                    linkInteractionListener = listener
                ),
                numberStart,
                length
            )
            // The parsed Greek module's TVM code number is clickable too:
            // it resolves to a record whose tvm field holds the
            // tense/voice/mood explanation shown in the panel.
            token.tvm?.let { tvm ->
                val tvmStart = length
                val tvmActive = tvm == activeNumber
                withStyle(
                    SpanStyle(
                        color = primary,
                        fontSize = numberSize,
                        baselineShift = BaselineShift.Superscript,
                        background = if (tvmActive) {
                            primaryContainer.copy(alpha = 0.5f)
                        } else {
                            Color.Unspecified
                        }
                    )
                ) {
                    append(tvm)
                }
                addLink(
                    LinkAnnotation.Clickable(
                        tag = "${token.start}$TVM_TAG_SUFFIX",
                        styles = linkStyles,
                        linkInteractionListener = listener
                    ),
                    tvmStart,
                    length
                )
            }
            if (token.parsing != null) {
                withStyle(
                    SpanStyle(
                        color = muted,
                        fontSize = parsingSize,
                        baselineShift = BaselineShift.Superscript
                    )
                ) {
                    append(token.parsing)
                }
            }
            pos = token.end
        }
        if (pos < text.length) append(text.substring(pos))
    }
}


/**
 * Definition card shown under the verse whose word was clicked: the Strong's
 * number badge and surface word, then the decoded root / transliteration /
 * pronunciation / parsing, then the full definition entry. While the
 * dictionary is still loading (first click ever) it shows a brief progress
 * note and fills in once [StrongsRepository.ensureLoaded] completes.
 */
@Composable
internal fun WordStudyPanel(
    token: StudyWordToken,
    loaded: Boolean,
    onClose: () -> Unit,
    /** Opens the central lexicon at [String] (a Strong's number). Wired
     *  by the host screen; defaults to a no-op so existing call sites
     *  compile unchanged. */
    onOpenLexicon: (String) -> Unit = {}
) {
    val definition = if (loaded) StrongsRepository.definition(token.number) else null

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(14.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Card(
                        shape = RoundedCornerShape(10.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = Color(0xFFFFC107).copy(alpha = 0.18f)
                        )
                    ) {
                        Text(
                            text = token.number,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            style = MaterialTheme.typography.labelLarge,
                            color = Color(0xFFFFB300)
                        )
                    }
                    Text(
                        text = token.word,
                        style = MaterialTheme.typography.titleMedium
                    )
                }

                Text(
                    text = "✕",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clickable {
                            SoundManager.play(SoundEvent.Click)
                            onClose()
                        }
                        .padding(horizontal = 4.dp)
                )
            }

            // Link into the central word-study / lexicon view.
            if (loaded) {
                Text(
                    text = "Open in Word Study →",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable {
                        SoundManager.play(SoundEvent.Click)
                        onOpenLexicon(token.number)
                    }
                )
            }

            when {
                !loaded -> {
                    Text(
                        text = "Loading Strong's definitions…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                definition == null -> {
                    Text(
                        text = "No definition found for ${token.number}.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                else -> {
                    if (definition.rootWord.isNotEmpty()) {
                        DefinitionRow(
                            label = "Root",
                            value = definition.rootWord,
                            valueStyle = MaterialTheme.typography.bodyLarge
                        )
                    }
                    if (definition.transliteration.isNotEmpty()) {
                        DefinitionRow("Transliteration", definition.transliteration)
                    }
                    if (definition.pronunciation.isNotEmpty()) {
                        DefinitionRow("Pronunciation", definition.pronunciation)
                    }
                    token.parsing?.let { DefinitionRow("Parsing", it) }

                    // Tense/voice/mood explanation — populated on the TVM
                    // code records (e.g. G5656), shown when the user clicks
                    // a TVM code in the parsed Greek module. Multi-line
                    // ("Tense: Aorist\nVoice: Active…").
                    definition.tvm?.let { tvm ->
                        Text(
                            text = "Tense \u00B7 Voice \u00B7 Mood",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = tvm,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }

                    // Some records (notably the TVM codes) have no prose
                    // definition — only the parsing / TVM info above — so
                    // the Definition section is skipped when empty.
                    if (definition.entry.isNotEmpty()) {
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        )
                        Text(
                            text = "Definition",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = definition.entry,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
    }
}


/**
 * Compact, inline word-study card for the whole-book continuous reading
 * view — a lighter alternative to the full [WordStudyPanel] that sits
 * directly under the verse instead of opening a large panel below it.
 * Shows the same decoded data (number badge, word, root / transliteration
 * / pronunciation on one dimmed line, then a truncated definition
 * preview) with a small ✕ close. While the dictionary is still loading it
 * shows the same brief progress note as the full panel.
 */
@Composable
internal fun WordStudyMiniPanel(
    token: StudyWordToken,
    loaded: Boolean,
    onClose: () -> Unit,
    /** Opens the central lexicon at [String] (a Strong's number). Wired
     *  by the host screen; defaults to a no-op so existing call sites
     *  compile unchanged. */
    onOpenLexicon: (String) -> Unit = {}
) {
    val definition = if (loaded) StrongsRepository.definition(token.number) else null

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                // Number badge + word, same visual language as the full
                // panel but compact.
                Card(
                    shape = RoundedCornerShape(6.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xFFFFC107).copy(alpha = 0.18f)
                    )
                ) {
                    Text(
                        text = token.number,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFFFFB300)
                    )
                }
                Text(
                    text = token.word,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontWeight = FontWeight.SemiBold
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "✕",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clickable {
                            SoundManager.play(SoundEvent.Click)
                            onClose()
                        }
                        .padding(horizontal = 4.dp)
                )
                if (loaded) {
                    Text(
                        text = "Word Study →",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable {
                            SoundManager.play(SoundEvent.Click)
                            onOpenLexicon(token.number)
                        }
                    )
                }
            }

            when {
                !loaded -> {
                    Text(
                        text = "Loading Strong's definitions…",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                definition == null -> {
                    Text(
                        text = "No definition found for ${token.number}.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                else -> {
                    // Root / transliteration / pronunciation condensed to
                    // one dimmed line so the card stays genuinely inline.
                    val summary = listOfNotNull(
                        definition.rootWord.takeIf { it.isNotEmpty() }?.let { "Root: $it" },
                        definition.transliteration.takeIf { it.isNotEmpty() },
                        definition.pronunciation.takeIf { it.isNotEmpty() },
                        token.parsing?.takeIf { it.isNotEmpty() },
                        definition.tvm?.takeIf { it.isNotEmpty() }
                            ?.replace("\n", " · ")
                    ).joinToString("  ·  ")
                    if (summary.isNotEmpty()) {
                        Text(
                            text = summary,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    // Definition preview, capped so the continuous scroll
                    // stays tight — the full panel shows the whole entry.
                    if (definition.entry.isNotEmpty()) {
                        Text(
                            text = definition.entry,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}


@Composable
private fun DefinitionRow(
    label: String,
    value: String,
    valueStyle: TextStyle = MaterialTheme.typography.bodySmall
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(104.dp)
        )
        Text(
            text = value,
            style = valueStyle,
            modifier = Modifier.weight(1f)
        )
    }
}
