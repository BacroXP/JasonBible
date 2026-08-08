@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class
)

package ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import data.MediaReferenceToken
import data.ReferenceToken
import data.findMediaReferenceTokens
import data.findReferenceTokens
import data.parseRange
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.sp


// ---------------------------------------------------------------------------
// Live WYSIWYG-style transformation. Underlying source markdown is preserved
// verbatim; only the display is transformed.
//
// Hidden markers use two strategies:
//   1) Line-prefix `hiddenLen` stripping for `#`, `##`, `>`, `>>`, `>>>`
//      `-`, `\d+\.`, `RLM`, `LRM`. A real OffsetMapping tracks the
//      delta so cursor positions stay correct.
//   2) Mid-line markup stripping in Pass 3 for reference lines and
//      colored quotes. `$Book$C$V` references drop every `$` (they
//      become zero-width MappingSpans, so cursor / selection / tap
//      offsets round-trip) and display human-readable separators
//      ("Book C:V"). Colored quotes strip the `"…"[#hex]` wrapper and
//      emit four MappingSpans per line (opener `"` → zero-width, inner
//      text identity, closer+bracket gap → zero-width, trailing text
//      identity) so the colored quote hugs the trailing text with no
//      horizontal gap. Save format is unchanged: the parser reads
//      the full source markdown so the round-trip is lossless.
//
// Save format is also unchanged: `NotesRepository.parseNoteFile`
// parses the editor's output (this transformation's source text)
// identically to the original
// markdown. The shift to no-space "text"+"trailing" in the parser is
// matching-side only — sources that begin with explicit `<space>`
// after the `[#hex]` still capture them via the regex's `\s*(.*)` and
// the new join drops the leading space to match the stripped display.
// ---------------------------------------------------------------------------

internal data class NotePalette(
    val onSurface: Color,
    val onSurfaceVariant: Color,
    val primary: Color,
    val tertiary: Color,
    val faded: Color,
    // Soft tint behind a `$Book$C$V` reference chip — primary at low
    // alpha reads as a subtle highlight in both light and dark themes.
    val referenceBackground: Color
)


@Composable
internal fun rememberNoteVisualTransformation(fontScale: Float = 1f): VisualTransformation {
    val palette = NotePalette(
        onSurface = MaterialTheme.colorScheme.onSurface,
        onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant,
        primary = MaterialTheme.colorScheme.primary,
        tertiary = MaterialTheme.colorScheme.tertiary,
        faded = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
        referenceBackground = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
    )
    // Keyed on fontScale: zoom (A− / A+) must rebuild the transformation
    // so heading/quote spans scale in lockstep with the base body text.
    return remember(fontScale) { NoteVisualTransformation(palette, fontScale) }
}


internal class NoteVisualTransformation(
    private val palette: NotePalette,
    private val fontScale: Float = 1f
) : VisualTransformation {

    var offsetMapping: OffsetMapping? = null
        private set

    /**
     * Displayed (transformed-text) character ranges of each "Book C:V"
     * reference chip, in document order. Repopulated on every `filter`
     * pass. `EditorSurface` maps these through its `TextLayoutResult` to
     * draw the rounded chip background pill — `SpanStyle.background`
     * paints only a flat rectangle with no corner radius, so the chip is
     * drawn by hand behind the glyphs.
     */
    var referenceChipRanges: List<IntRange> = emptyList()
        private set

    /** Theme-aware fill used for the reference chip pill. */
    val referenceChipColor: Color
        get() = palette.referenceBackground

    enum class BlockKind {
        HEADING1, HEADING2, BULLET, NUMBERED, QUOTE, QUOTE_BULLET, QUOTE_NUMBERED,
        COLORED_QUOTE, REFERENCE, PARAGRAPH, RTL_LINE, LTR_LINE
    }

    data class LineAnalysis(
        val kind: BlockKind,
        val hiddenLen: Int,
        val quoteDepth: Int = 0,
        /**
         * Hex string for COLORED_QUOTE lines (e.g. `"#FFD54F"`),
         * empty string for every other line kind (default). Making
         * the field non-nullable means consumers in
         * `applyColoredQuoteStyles` and elsewhere can pass it
         * straight to `colorFromHex(hex: String)` without an
         * `?: ""` dance; an empty hex falls through
         * `colorFromHex` to `null` and lands on the primary-color
         * fallback via `?: palette.primary`.
         */
        val quoteColorHex: String = "",
        /**
         * For COLORED_QUOTE lines — byte-length of the inner text
         * captured by `coloredQuoteRegex` group 1 (the part between the
         * two literal `"` quotes). Drives the colored-span width in
         * `applyColoredQuoteStyles` and the span decomposition in Pass 3
         * where the closing `"…"[#hex]` markup is stripped from the
         * displayed text. Stays at 0 for all other line kinds.
         */
        val innerTextLength: Int = 0,
        val isReferenceLine: Boolean = false,
        /**
         * For REFERENCE lines — display length of the "Book C:V" chip
         * (book name + a space + chapter + a colon + verse). Bounds the
         * background-highlight span in `applyReferenceStyles` so a
         * trailing label stays plain. Stays at 0 for all other kinds.
         */
        val referenceTextLen: Int = 0,
        val direction: TextDirection = TextDirection.Ltr
    )

    override fun filter(text: AnnotatedString): TransformedText {
        val source = text.text
        val chipRanges = mutableListOf<IntRange>()

        // ----- Pass 1: classify every line independently -----
        val analyses = mutableListOf<LineAnalysis>()
        val lineEndsByIndex = mutableListOf<Int>()
        var scanPos = 0
        while (scanPos <= source.length) {
            val nlPos = source.indexOf('\n', scanPos)
            val lineEnd = if (nlPos == -1) source.length else nlPos
            val raw = source.substring(scanPos, lineEnd)
            analyses.add(analyzeLine(raw))
            lineEndsByIndex.add(lineEnd)
            if (nlPos == -1) break
            scanPos = nlPos + 1
        }

        // ----- Pass 2: assign auto-numbers to QUOTE_NUMBERED lines -----
        // Quote-prefixed lines (PLAIN QUOTE, QUOTE_BULLET, QUOTE_NUMBERED)
        // continue the sequence; anything else (paragraph, heading,
        // reference, RLM/LTR marker, etc.) resets the counter to 0.
        val assignedNumbers = IntArray(analyses.size)
        var counter = 0
        for (i in analyses.indices) {
            val kind = analyses[i].kind
            when {
                kind == BlockKind.QUOTE_NUMBERED -> {
                    counter += 1
                    assignedNumbers[i] = counter
                }
                kind == BlockKind.QUOTE || kind == BlockKind.QUOTE_BULLET -> {
                    // Chain alive; do not reset, do not increment.
                }
                else -> counter = 0
            }
        }

        // ----- Pass 3: build the displayed output & OffsetMapping -----
        val out = AnnotatedString.Builder()
        val ranges = mutableListOf<MappingSpan>()

        for (i in analyses.indices) {
            val analysis = analyses[i]
            val lineEnd = lineEndsByIndex[i]
            val lineStart = if (i == 0) 0 else lineEndsByIndex[i - 1] + 1

            // Emit a newline span between lines (skip before the first).
            if (i > 0) {
                val nlOutStart = out.length
                out.append("\n")
                ranges.add(
                    MappingSpan(
                        originalStart = lineStart - 1,
                        originalEnd = lineStart,
                        transformedStart = nlOutStart,
                        transformedEnd = nlOutStart + 1,
                        delta = 0,
                        prependedLen = 0
                    )
                )
            }

            // Synthesised display prefix that does not exist in source.
            // `mapping` records `prependedLen` so the OffsetMapping can
            // route clicks inside it to a sensible source position.
            // COLORED_QUOTE / REFERENCE never synthesise a prefix — the
            // visible text is just the inner-stripped content of the
            // line, so the `prepended + visibleText` style call below
            // receives an empty prefix for those cases.
            val prepended = when (analysis.kind) {
                BlockKind.QUOTE_NUMBERED -> "${assignedNumbers[i]}. "
                BlockKind.QUOTE_BULLET -> "\u2022 "
                else -> ""
            }
            val prependedLen = prepended.length

            val visibleStartInOut: Int
            val visibleText: String
            // Display ranges (absolute, in transformed-text coordinates) of
            // inline reference chips rendered inside this line's running
            // text. Populated by the standard-flow branch when it finds
            // `$Book$C$V` tokens; merged into [chipRanges] after the line's
            // base styles are applied so EditorSurface paints a pill over
            // them. Whole-line REFERENCE lines record their chip directly.
            val lineChipRanges = mutableListOf<IntRange>()

            if (analysis.kind == BlockKind.COLORED_QUOTE) {
                // Colored quotes have a decorative wrapper of the form
                // `"inner"[#hex]trailing` in source. The wrapper has to
                // be fully stripped from rendering so the trailing text
                // hugs the colored text without a horizontal gap;
                // Compose's font-metric width applies to transparent
                // chars too, so Color.Transparent isn't enough.
                //
                // The strip is bimodal: 1 char of opener at the start,
                // 1 + bracketLen chars of closer+bracket in the MIDDLE
                // of the source line. The standard `delta = hiddenLen`
                // model can only hide a contiguous prefix or suffix,
                // so emit FOUR MappingSpans per COLORED_QUOTE line
                // instead — opener (zero-width), inner text (identity),
                // gap (zero-width), trailing (identity, optional).
                // The NoteOffsetMapping binary search handles this
                // naturally; both endpoints of the gap converge on
                // `transformedStart = visibleStartInOut + innerTextLength`
                // so cursor positions round-trip cleanly between source
                // and display coordinates.
                val rawLine = source.substring(lineStart, lineEnd)
                val cqMatch = coloredQuoteRegex.matchEntire(rawLine)
                val cqInnerText = cqMatch?.groupValues?.getOrNull(1).orEmpty()
                val cqHex = cqMatch?.groupValues?.getOrNull(2).orEmpty()
                val cqTrailing = cqMatch?.groupValues?.getOrNull(3).orEmpty()
                val bracketLen = if (cqHex.isNotEmpty()) "[#$cqHex]".length else 0

                visibleStartInOut = out.length
                out.append(cqInnerText)
                out.append(cqTrailing)
                visibleText = cqInnerText + cqTrailing

                val openerSrcEnd = lineStart + 1
                val innerSrcEnd = openerSrcEnd + cqInnerText.length
                val gapSrcEnd = innerSrcEnd + 1 + bracketLen
                val trailSrcEnd = gapSrcEnd + cqTrailing.length

                // Span 1: opening `"` → zero display chars
                ranges.add(
                    MappingSpan(
                        originalStart = lineStart,
                        originalEnd = openerSrcEnd,
                        transformedStart = visibleStartInOut,
                        transformedEnd = visibleStartInOut,
                        delta = 1
                    )
                )
                // Span 2: inner text
                ranges.add(
                    MappingSpan(
                        originalStart = openerSrcEnd,
                        originalEnd = innerSrcEnd,
                        transformedStart = visibleStartInOut,
                        transformedEnd = visibleStartInOut + cqInnerText.length,
                        delta = 0
                    )
                )
                // Span 3: closing `"` + `[#hex]` → zero display chars
                ranges.add(
                    MappingSpan(
                        originalStart = innerSrcEnd,
                        originalEnd = gapSrcEnd,
                        transformedStart = visibleStartInOut + cqInnerText.length,
                        transformedEnd = visibleStartInOut + cqInnerText.length,
                        delta = 1 + bracketLen
                    )
                )
                // Span 4: trailing text (identity, only if non-empty)
                if (cqTrailing.isNotEmpty()) {
                    ranges.add(
                        MappingSpan(
                            originalStart = gapSrcEnd,
                            originalEnd = trailSrcEnd,
                            transformedStart = visibleStartInOut + cqInnerText.length,
                            transformedEnd = visibleStartInOut + cqInnerText.length + cqTrailing.length,
                            delta = 0
                        )
                    )
                }
            } else if (analysis.kind == BlockKind.REFERENCE) {
                // Reference lines are whole-line scaffolds of the form
                // `$Book$C$V Label` (chapter and verse optional). The `$`
                // markers are dropped from the display entirely and
                // human-readable separators substituted — `$Book$C$V`
                // renders as `Book C:V` — so the reference reads as a
                // natural chip instead of a raw scaffold. Every marker
                // becomes a zero-width MappingSpan (or, for the two
                // separators, a 1:1 char substitution) so cursor /
                // selection / tap offsets round-trip exactly; the
                // optional trailing label is emitted verbatim and stays
                // outside the chip.
                val rawLine = source.substring(lineStart, lineEnd)
                val content = rawLine.removePrefix(RLM).removePrefix(LRM)
                val refMatch = referenceLineRegex.matchEntire(content)

                visibleStartInOut = out.length
                if (refMatch == null) {
                    // Defensive-only: analyzeLine classifies REFERENCE
                    // with this exact regex on this exact RLM/LRM-stripped
                    // content, so this branch is unreachable today — but
                    // if the grammar ever drifts, render verbatim so no
                    // text is lost instead of crashing.
                    visibleText = rawLine
                    out.append(visibleText)
                    ranges.add(
                        MappingSpan(
                            originalStart = lineStart,
                            originalEnd = lineEnd,
                            transformedStart = visibleStartInOut,
                            transformedEnd = visibleStartInOut + visibleText.length,
                            delta = 0,
                            prependedLen = 0
                        )
                    )
                } else {
                    val book = refMatch.groupValues[1]
                    val chapter = refMatch.groupValues[2].takeIf { it.isNotEmpty() }
                    val verse = refMatch.groupValues[3].takeIf { it.isNotEmpty() }
                    val chapterNum = chapter?.toIntOrNull()
                    val verseNum = verse?.toIntOrNull()
                    // Resolve the optional range suffix (`+`, `+N`, `-V2`,
                    // `-&C2&V2`, …) to an inclusive end so the chip reads
                    // as a real range ("John 3:16-22", "Luk 7:1-8:1")
                    // instead of the raw shorthand. A captured suffix that
                    // does not resolve (e.g. a backward `-7` after verse
                    // 16) is shown VERBATIM as plain text after the chip.
                    val range = refMatch.groupValues[4]
                    val rangeResolved = if (verseNum != null) {
                        parseRange(chapterNum, verseNum, range)
                    } else null
                    val contentStart = lineStart + analysis.hiddenLen

                    // Source offset just after the verse digits (before
                    // the range suffix); everything from there on is
                    // emitted outside the chip.
                    val headEnd = contentStart + 1 + book.length +
                        (if (chapter != null) 1 + chapter.length else 0) +
                        (if (verse != null) 1 + verse.length else 0)
                    val tailStart = headEnd + range.length

                    visibleText = buildString {
                        append(book)
                        if (chapter != null) {
                            append(' ')
                            append(chapter)
                        }
                        if (verse != null) {
                            append(':')
                            append(verse)
                            if (rangeResolved != null) {
                                append('-')
                                if (rangeResolved.endChapter != chapterNum) {
                                    append(rangeResolved.endChapter)
                                    append(':')
                                }
                                append(rangeResolved.endVerse)
                            } else if (range.isNotEmpty()) {
                                append(range)
                            }
                        }
                        append(source.substring(tailStart, lineEnd))
                    }
                    out.append(visibleText)

                    // Record the displayed "Book C:V" span so EditorSurface
                    // can paint the rounded chip pill behind these chars.
                    if (analysis.referenceTextLen > 0) {
                        chipRanges.add(
                            visibleStartInOut until visibleStartInOut + analysis.referenceTextLen
                        )
                    }

                    var src = contentStart
                    var dst = visibleStartInOut
                    // Hidden RLM / LRM direction-marker prefix.
                    if (analysis.hiddenLen > 0) {
                        ranges.add(
                            MappingSpan(
                                originalStart = lineStart,
                                originalEnd = src,
                                transformedStart = dst,
                                transformedEnd = dst,
                                delta = analysis.hiddenLen
                            )
                        )
                    }
                    // Hidden `$` before the book name.
                    ranges.add(
                        MappingSpan(
                            originalStart = src,
                            originalEnd = src + 1,
                            transformedStart = dst,
                            transformedEnd = dst,
                            delta = 1
                        )
                    )
                    src += 1
                    // Book name.
                    ranges.add(
                        MappingSpan(
                            originalStart = src,
                            originalEnd = src + book.length,
                            transformedStart = dst,
                            transformedEnd = dst + book.length,
                            delta = 0
                        )
                    )
                    src += book.length
                    dst += book.length
                    // `$C` → ` C`.
                    if (chapter != null) {
                        ranges.add(
                            MappingSpan(
                                originalStart = src,
                                originalEnd = src + 1,
                                transformedStart = dst,
                                transformedEnd = dst + 1,
                                delta = 0
                            )
                        )
                        src += 1
                        dst += 1
                        ranges.add(
                            MappingSpan(
                                originalStart = src,
                                originalEnd = src + chapter.length,
                                transformedStart = dst,
                                transformedEnd = dst + chapter.length,
                                delta = 0
                            )
                        )
                        src += chapter.length
                        dst += chapter.length
                    }
                    // `$V` → `:V`.
                    if (verse != null) {
                        ranges.add(
                            MappingSpan(
                                originalStart = src,
                                originalEnd = src + 1,
                                transformedStart = dst,
                                transformedEnd = dst + 1,
                                delta = 0
                            )
                        )
                        src += 1
                        dst += 1
                        ranges.add(
                            MappingSpan(
                                originalStart = src,
                                originalEnd = src + verse.length,
                                transformedStart = dst,
                                transformedEnd = dst + verse.length,
                                delta = 0
                            )
                        )
                        src += verse.length
                        dst += verse.length
                        // Range suffix `+` / `-V2` / `-&C2&V2` → `-E` or
                        // `-C2:E` (resolved end). One span covers the whole
                        // raw suffix; its delta absorbs the length
                        // difference so cursor / tap offsets round-trip.
                        // A captured suffix that does not resolve is kept
                        // as plain verbatim text with an identity span.
                        if (rangeResolved != null) {
                            val cross = rangeResolved.endChapter != chapterNum
                            val suffixDstLen = 1 +
                                (if (cross) rangeResolved.endChapter.toString().length + 1 else 0) +
                                rangeResolved.endVerse.toString().length
                            ranges.add(
                                MappingSpan(
                                    originalStart = src,
                                    originalEnd = src + range.length,
                                    transformedStart = dst,
                                    transformedEnd = dst + suffixDstLen,
                                    delta = range.length - suffixDstLen
                                )
                            )
                            src += range.length
                            dst += suffixDstLen
                        } else if (range.isNotEmpty()) {
                            ranges.add(
                                MappingSpan(
                                    originalStart = src,
                                    originalEnd = src + range.length,
                                    transformedStart = dst,
                                    transformedEnd = dst + range.length,
                                    delta = 0
                                )
                            )
                            src += range.length
                            dst += range.length
                        }
                    }
                    // Label + trailing whitespace (verbatim).
                    if (tailStart < lineEnd) {
                        ranges.add(
                            MappingSpan(
                                originalStart = tailStart,
                                originalEnd = lineEnd,
                                transformedStart = dst,
                                transformedEnd = dst + (lineEnd - tailStart),
                                delta = 0
                            )
                        )
                    }
                }
            } else {
                // Standard flow: optional synthesised list-marker prefix
                // (`1. ` / `• `) PLUS prefix-stripped visibleText. The
                // mapping spans cover the whole line; the standard
                // `delta = hiddenLen` formula in NoteOffsetMapping
                // handles the prefix-hide.
                //
                // Inline Bible references (`$Book$C$V` tokens embedded in
                // a sentence, ending at a space) are rendered as chips:
                // the `$` markers are hidden and the separators
                // substituted with a space / colon — exactly like a
                // whole-line REFERENCE — so "Read $Lukas$3$16 today"
                // displays as "Read Lukas 3:16 today" with a pill around
                // "Lukas 3:16". Cursor / selection / tap offsets still
                // round-trip because every hidden marker is a
                // zero-width MappingSpan and each substituted separator
                // is a 1:1 span.
                val visibleStartInSource = lineStart + analysis.hiddenLen
                val rawContent = if (visibleStartInSource < lineEnd) {
                    source.substring(visibleStartInSource, lineEnd)
                } else ""

                visibleStartInOut = out.length
                out.append(prepended)
                val inlineTokens = findReferenceTokens(rawContent)
                val mediaTokens = findMediaReferenceTokens(rawContent)

                if (inlineTokens.isEmpty() && mediaTokens.isEmpty()) {
                    visibleText = rawContent
                    out.append(visibleText)
                    ranges.add(
                        MappingSpan(
                            originalStart = lineStart,
                            originalEnd = lineEnd,
                            transformedStart = visibleStartInOut,
                            transformedEnd = visibleStartInOut + prependedLen + visibleText.length,
                            delta = analysis.hiddenLen,
                            prependedLen = prependedLen
                        )
                    )
                } else {
                    // Hidden markup prefix (`# `, `- `, RLM/LRM, …) is a
                    // zero-width span snapping to the display start. It
                    // carries prependedLen so clicks inside the
                    // synthesised `1. ` / `• ` marker route to the first
                    // visible source char.
                    ranges.add(
                        MappingSpan(
                            originalStart = lineStart,
                            originalEnd = visibleStartInSource,
                            transformedStart = visibleStartInOut,
                            transformedEnd = visibleStartInOut + prependedLen,
                            delta = analysis.hiddenLen,
                            prependedLen = prependedLen
                        )
                    )

                    val display = StringBuilder()
                    var srcAbs = visibleStartInSource
                    var dst = visibleStartInOut + prependedLen

                    // Emit a plain-text identity span for [fromAbs, toAbs).
                    fun emitPlain(fromAbs: Int, toAbs: Int) {
                        if (toAbs <= fromAbs) return
                        val plain = source.substring(fromAbs, toAbs)
                        display.append(plain)
                        ranges.add(
                            MappingSpan(
                                originalStart = fromAbs,
                                originalEnd = toAbs,
                                transformedStart = dst,
                                transformedEnd = dst + plain.length,
                                delta = 0
                            )
                        )
                        dst += plain.length
                    }

                    // Inline chips in SOURCE order: Bible and media tokens
                    // can interleave in one line ("See @youtube:X and
                    // $Lukas$3$16"), and every chip must emit its mapping
                    // spans at ascending source positions. Merge both token
                    // lists and walk the union once.
                    val mergedTokens =
                        ArrayList<Triple<Int, Int, Any>>(inlineTokens.size + mediaTokens.size)
                    inlineTokens.forEach { mergedTokens.add(Triple(it.sourceStart, it.sourceEnd, it)) }
                    mediaTokens.forEach { mergedTokens.add(Triple(it.sourceStart, it.sourceEnd, it)) }
                    mergedTokens.sortBy { it.first }

                    for ((tokenStartRel, tokenEndRel, token) in mergedTokens) {
                        val tStart = tokenStartRel + visibleStartInSource
                        val tEnd = tokenEndRel + visibleStartInSource
                        emitPlain(srcAbs, tStart)

                        when (token) {
                            is ReferenceToken -> {
                                val chipStart = dst
                                // Hidden `$` before the book name (zero-width).
                                ranges.add(MappingSpan(tStart, tStart + 1, dst, dst, delta = 1))
                                // Book name (identity).
                                display.append(token.book)
                                ranges.add(
                                    MappingSpan(
                                        tStart + 1,
                                        tStart + 1 + token.book.length,
                                        dst,
                                        dst + token.book.length,
                                        delta = 0
                                    )
                                )
                                dst += token.book.length
                                var src = tStart + 1 + token.book.length
                                // `$C` → ` C` (1:1 separator substitution).
                                if (token.chapter != null) {
                                    display.append(' ')
                                    ranges.add(MappingSpan(src, src + 1, dst, dst + 1, delta = 0))
                                    src += 1
                                    dst += 1
                                    val digits = token.chapter.toString()
                                    display.append(digits)
                                    ranges.add(
                                        MappingSpan(src, src + digits.length, dst, dst + digits.length, delta = 0)
                                    )
                                    src += digits.length
                                    dst += digits.length
                                }
                                // `$V` → `:V`.
                                if (token.verse != null) {
                                    display.append(':')
                                    ranges.add(MappingSpan(src, src + 1, dst, dst + 1, delta = 0))
                                    src += 1
                                    dst += 1
                                    val digits = token.verse.toString()
                                    display.append(digits)
                                    ranges.add(
                                        MappingSpan(src, src + digits.length, dst, dst + digits.length, delta = 0)
                                    )
                                    src += digits.length
                                    dst += digits.length
                                    // Range suffix `+` / `-V2` / `-&C2&V2`
                                    // → `-E` or `-C2:E` (resolved end). The
                                    // raw suffix occupies the token chars
                                    // between the verse digits and
                                    // [token.sourceEnd]; one MappingSpan
                                    // covers it, its delta absorbing the
                                    // display length difference. (The inline
                                    // scanner only consumes ranges that
                                    // resolve, so `token.endVerse != null`
                                    // implies a valid suffix here.)
                                    if (token.endVerse != null) {
                                        val cross = token.endChapter != null &&
                                            token.endChapter != token.chapter
                                        val suffixSrcLen = tEnd - src
                                        val suffixDstLen = 1 +
                                            (if (cross) token.endChapter.toString().length + 1 else 0) +
                                            token.endVerse.toString().length
                                        display.append('-')
                                        if (cross) {
                                            display.append(token.endChapter.toString())
                                            display.append(':')
                                        }
                                        display.append(token.endVerse.toString())
                                        ranges.add(
                                            MappingSpan(
                                                src,
                                                src + suffixSrcLen,
                                                dst,
                                                dst + suffixDstLen,
                                                delta = suffixSrcLen - suffixDstLen
                                            )
                                        )
                                        src += suffixSrcLen
                                        dst += suffixDstLen
                                    }
                                }
                                lineChipRanges.add(chipStart until dst)
                            }

                            is MediaReferenceToken -> {
                                // `@service:content` → a synthesized chip
                                // (emoji + label [+ short id/domain]). The
                                // ENTIRE source token hides behind one
                                // zero-delta span mapped onto the chip text:
                                // the chip is built no longer than the
                                // token (see [MediaReferenceToken.chipText]),
                                // so every click/caret inside the chip
                                // round-trips into the token's source range
                                // and tap/hover still resolve it.
                                val chipStart = dst
                                val chipText = token.chipText()
                                display.append(chipText)
                                ranges.add(
                                    MappingSpan(
                                        tStart,
                                        tEnd,
                                        dst,
                                        dst + chipText.length,
                                        delta = 0
                                    )
                                )
                                dst += chipText.length
                                lineChipRanges.add(chipStart until dst)
                            }

                            else -> Unit
                        }
                        srcAbs = tEnd
                    }
                    emitPlain(srcAbs, lineEnd)

                    visibleText = display.toString()
                    out.append(visibleText)
                }
            }

            // Style applied over the line's full visible span (prefix + content).
            applyLineStyles(out, visibleStartInOut, prepended + visibleText, analysis)

            // Inline reference chips: bold-primary text over the chip's
            // display range, applied AFTER the line's base styles so the
            // chip styling wins. The rounded pill behind the text is
            // painted by EditorSurface from `referenceChipRanges`.
            if (lineChipRanges.isNotEmpty()) {
                lineChipRanges.forEach { range ->
                    out.addStyle(
                        SpanStyle(color = palette.primary, fontWeight = FontWeight.Bold),
                        range.first,
                        range.last + 1
                    )
                }
                chipRanges.addAll(lineChipRanges)
            }
        }

        referenceChipRanges = chipRanges
        val mapping = NoteOffsetMapping(
            spans = ranges,
            originalLength = source.length,
            transformedLength = out.length
        )
        offsetMapping = mapping
        return TransformedText(out.toAnnotatedString(), mapping)
    }

    /**
     * Classify a single line. When the line starts with an orientation
     * marker (RLM / LRM), strip it and re-classify the remainder so that
     * `‏$Book$C$V$` keeps its REFERENCE classification with RTL direction
     * applied, instead of being treated as a plain RTL line.
     */
    private fun analyzeLine(raw: String): LineAnalysis {
        if (raw.isEmpty()) return LineAnalysis(BlockKind.PARAGRAPH, hiddenLen = 0)

        if (raw.startsWith(RLM)) {
            val inner = analyzeLine(raw.removePrefix(RLM))
            return inner.copy(
                hiddenLen = RLM.length + inner.hiddenLen,
                direction = TextDirection.Rtl
            )
        }
        if (raw.startsWith(LRM)) {
            val inner = analyzeLine(raw.removePrefix(LRM))
            return inner.copy(
                hiddenLen = LRM.length + inner.hiddenLen,
                direction = TextDirection.Ltr
            )
        }

        if (raw.startsWith("## ")) return LineAnalysis(BlockKind.HEADING2, hiddenLen = 3)
        if (raw.startsWith("# ")) return LineAnalysis(BlockKind.HEADING1, hiddenLen = 2)
        if (raw.startsWith("- ")) return LineAnalysis(BlockKind.BULLET, hiddenLen = 2)

        orderedListRegex.matchAt(raw, 0)?.let { match ->
            return LineAnalysis(BlockKind.NUMBERED, hiddenLen = match.value.length)
        }

        if (raw.startsWith(">")) {
            val depth = raw.takeWhile { it == '>' }.length.coerceAtLeast(1)
            // After the `>` chain we may have `.` or `#` for a list item,
            // or just regular content for a plain quote.
            val afterChain = if (depth < raw.length) raw[depth] else ' '
            when (afterChain) {
                '.' -> {
                    var hidden = depth + 1
                    if (hidden < raw.length && raw[hidden] == ' ') hidden += 1
                    return LineAnalysis(
                        kind = BlockKind.QUOTE_BULLET,
                        hiddenLen = hidden,
                        quoteDepth = depth
                    )
                }
                '#' -> {
                    var hidden = depth + 1
                    if (hidden < raw.length && raw[hidden] == ' ') hidden += 1
                    return LineAnalysis(
                        kind = BlockKind.QUOTE_NUMBERED,
                        hiddenLen = hidden,
                        quoteDepth = depth
                    )
                }
                else -> {
                    var hidden = depth
                    if (hidden < raw.length && raw[hidden] == ' ') hidden += 1
                    return LineAnalysis(
                        kind = BlockKind.QUOTE,
                        hiddenLen = hidden,
                        quoteDepth = depth
                    )
                }
            }
        }

        referenceLineRegex.matchEntire(raw)?.let { match ->
            val book = match.groupValues[1]
            val chapter = match.groupValues[2].takeIf { it.isNotEmpty() }
            val verse = match.groupValues[3].takeIf { it.isNotEmpty() }
            val chapterNum = chapter?.toIntOrNull()
            // Display length of the "Book C:V" chip: book name plus a
            // space before the chapter, a colon before the verse, and —
            // for extended ranges — a hyphen plus the resolved end
            // ("Book 3:16-22" same chapter, "Book 7:1-8:1" cross
            // chapter).
            var refLen = book.length
            if (chapter != null) refLen += 1 + chapter.length
            if (verse != null) {
                refLen += 1 + verse.length
                val resolved = parseRange(chapterNum, verse.toIntOrNull(), match.groupValues[4])
                if (resolved != null) {
                    refLen += 1
                    if (resolved.endChapter != chapterNum) {
                        refLen += resolved.endChapter.toString().length + 1
                    }
                    refLen += resolved.endVerse.toString().length
                }
            }
            return LineAnalysis(
                kind = BlockKind.REFERENCE,
                hiddenLen = 0,
                isReferenceLine = true,
                referenceTextLen = refLen
            )
        }

        coloredQuoteRegex.matchEntire(raw)?.let { match ->
            val innerText = match.groupValues[1]
            val hex = match.groupValues[2]
            return LineAnalysis(
                kind = BlockKind.COLORED_QUOTE,
                hiddenLen = 0,
                quoteColorHex = hex,
                innerTextLength = innerText.length
            )
        }

        return LineAnalysis(BlockKind.PARAGRAPH, hiddenLen = 0)
    }

    private fun applyLineStyles(
        builder: AnnotatedString.Builder,
        visibleStart: Int,
        visibleText: String,
        analysis: LineAnalysis
    ) {
        if (visibleText.isEmpty()) return
        val visibleEnd = visibleStart + visibleText.length

        paragraphFor(analysis)?.let { builder.addStyle(it, visibleStart, visibleEnd) }
        spanFor(analysis)?.let { builder.addStyle(it, visibleStart, visibleEnd) }

        when (analysis.kind) {
            BlockKind.COLORED_QUOTE -> applyColoredQuoteStyles(builder, visibleStart, visibleText, analysis)
            BlockKind.REFERENCE -> applyReferenceStyles(builder, visibleStart, visibleText, analysis)
            else -> { /* nothing */ }
        }

        applyInlineEmphasis(builder, visibleText, visibleStart)
    }

    /**
     * Apply the hex colour span to the inner text of a colored quote
     * line. The visibleText passed in is already the stripped form (the
     * `"…"[#hex]` wrapper has been removed by the COLORED_QUOTE branch
     * in `filter`), so the inner text occupies the entire `visibleText`
     * up to `analysis.innerTextLength` bytes; any trailing text after
     * that is plain. No `Color.Transparent` spans are emitted here —
     * they're unnecessary because the decorative chars aren't in the
     * displayed text anymore.
     */
    private fun applyColoredQuoteStyles(
        builder: AnnotatedString.Builder,
        visibleStart: Int,
        visibleText: String,
        analysis: LineAnalysis
    ) {
        val innerLen = analysis.innerTextLength
        if (innerLen == 0 || visibleText.length < innerLen) return

        // `quoteColorHex` is non-nullable on LineAnalysis (defaults to ""
        // for non-COLORED_QUOTE lines), so this passes straight through
        // to `colorFromHex` without a coalesce dance. An empty hex
        // returns null from `colorFromHex` and falls through to the
        // primary-color fallback via the `?:` below.
        val color = colorFromHex(analysis.quoteColorHex) ?: palette.primary
        builder.addStyle(
            SpanStyle(color = color),
            visibleStart,
            visibleStart + innerLen
        )
    }

    /**
     * Chip styling for the reference portion of a REFERENCE line (the
     * "Book C:V" text). The `$` markers were already stripped from the
     * display in Pass 3. The background pill is deliberately NOT painted
     * here — `SpanStyle.background` only supports a flat rectangle with
     * no corner radius, so `EditorSurface` draws a rounded pill behind
     * the glyphs instead (see `referenceChipRanges`). This span keeps the
     * chip text bold primary, the "this is a clickable link" cue. Bounded
     * by `analysis.referenceTextLen` so a trailing label stays plain.
     */
    private fun applyReferenceStyles(
        builder: AnnotatedString.Builder,
        visibleStart: Int,
        visibleText: String,
        analysis: LineAnalysis
    ) {
        val len = analysis.referenceTextLen
        if (len <= 0 || visibleText.length < len) return
        builder.addStyle(
            SpanStyle(
                color = palette.primary,
                fontWeight = FontWeight.Bold
            ),
            visibleStart,
            visibleStart + len
        )
    }

    private fun spanFor(analysis: LineAnalysis): SpanStyle? = when (analysis.kind) {
        BlockKind.HEADING1 -> SpanStyle(fontWeight = FontWeight.Bold, fontSize = (28 * fontScale).sp, color = palette.onSurface)
        BlockKind.HEADING2 -> SpanStyle(fontWeight = FontWeight.Bold, fontSize = (22 * fontScale).sp, color = palette.onSurface)
        BlockKind.QUOTE -> SpanStyle(
            fontStyle = FontStyle.Italic,
            fontSize = (16 * fontScale).sp,
            color = palette.onSurface.copy(alpha = 0.92f)
        )
        BlockKind.QUOTE_BULLET -> SpanStyle(
            color = palette.onSurface.copy(alpha = 0.92f),
            fontSize = (16 * fontScale).sp
        )
        BlockKind.QUOTE_NUMBERED -> SpanStyle(
            color = palette.primary,
            fontWeight = FontWeight.SemiBold,
            fontSize = (16 * fontScale).sp
        )
        BlockKind.COLORED_QUOTE -> SpanStyle(fontStyle = FontStyle.Italic, fontSize = (16 * fontScale).sp, color = palette.onSurface)
        // Base look for REFERENCE lines. The chip styling itself (bold
        // primary text on a soft primary background) is applied over the
        // "Book C:V" portion only by `applyReferenceStyles`; this keeps
        // the font size consistent and leaves a trailing label (if any)
        // in plain paragraph text.
        BlockKind.REFERENCE -> SpanStyle(fontSize = 15.sp, color = palette.onSurface)
        BlockKind.BULLET, BlockKind.NUMBERED, BlockKind.RTL_LINE, BlockKind.LTR_LINE ->
            SpanStyle(fontSize = 15.sp, color = palette.onSurface)
        BlockKind.PARAGRAPH -> SpanStyle(fontSize = 16.sp, color = palette.onSurface)
    }

    private fun paragraphFor(analysis: LineAnalysis): ParagraphStyle? {
        val quoteDepthDp = analysis.quoteDepth * 24
        // Quote-prefixed list items start with the synthesised glyph
        // (e.g. `1. ` or `• `) directly, so we leave the first-line
        // indent at zero on those lines; the quote-depth indent still
        // applies on wrap.
        val firstIndentDp = when (analysis.kind) {
            BlockKind.QUOTE -> quoteDepthDp
            BlockKind.QUOTE_BULLET, BlockKind.QUOTE_NUMBERED -> 0
            BlockKind.BULLET, BlockKind.NUMBERED -> 20
            else -> quoteDepthDp
        }
        val restIndentDp = when (analysis.kind) {
            BlockKind.QUOTE -> quoteDepthDp
            BlockKind.QUOTE_BULLET, BlockKind.QUOTE_NUMBERED -> quoteDepthDp
            BlockKind.BULLET, BlockKind.NUMBERED -> 20
            else -> quoteDepthDp
        }
        val dir = analysis.direction
        return when (analysis.kind) {
            BlockKind.HEADING1 -> ParagraphStyle(
                lineHeight = 34.sp,
                textIndent = TextIndent(firstLine = firstIndentDp.sp, restLine = restIndentDp.sp),
                textDirection = dir
            )
            BlockKind.HEADING2 -> ParagraphStyle(
                lineHeight = 30.sp,
                textIndent = TextIndent(firstLine = firstIndentDp.sp, restLine = restIndentDp.sp),
                textDirection = dir
            )
            BlockKind.QUOTE -> ParagraphStyle(
                lineHeight = 22.sp,
                textIndent = TextIndent(firstLine = firstIndentDp.sp, restLine = restIndentDp.sp),
                textDirection = dir
            )
            BlockKind.QUOTE_BULLET, BlockKind.QUOTE_NUMBERED -> ParagraphStyle(
                lineHeight = 22.sp,
                textIndent = TextIndent(firstLine = firstIndentDp.sp, restLine = restIndentDp.sp),
                textDirection = dir
            )
            BlockKind.COLORED_QUOTE -> ParagraphStyle(
                lineHeight = 22.sp,
                textIndent = TextIndent(firstLine = 24.sp, restLine = 24.sp),
                textDirection = dir
            )
            BlockKind.REFERENCE -> ParagraphStyle(
                lineHeight = 22.sp,
                textIndent = TextIndent(firstLine = 0.sp, restLine = 0.sp),
                textDirection = dir
            )
            BlockKind.BULLET, BlockKind.NUMBERED -> ParagraphStyle(
                lineHeight = 22.sp,
                textIndent = TextIndent(firstLine = firstIndentDp.sp, restLine = restIndentDp.sp),
                textDirection = dir
            )
            BlockKind.RTL_LINE, BlockKind.LTR_LINE, BlockKind.PARAGRAPH -> ParagraphStyle(
                lineHeight = 24.sp,
                textIndent = TextIndent(firstLine = firstIndentDp.sp, restLine = restIndentDp.sp),
                textDirection = dir
            )
        }
    }

    private fun applyInlineEmphasis(
        builder: AnnotatedString.Builder,
        visible: String,
        offset: Int
    ) {
        INLINE_BOLD.findAll(visible).forEach { match ->
            val s = match.range.first + 2
            val e = match.range.last - 1
            if (s < e) builder.addStyle(SpanStyle(fontWeight = FontWeight.Bold), offset + s, offset + e)
        }
        INLINE_ITALIC.findAll(visible).forEach { match ->
            val s = match.range.first + 1
            val e = match.range.last - 1
            if (s < e) builder.addStyle(SpanStyle(fontStyle = FontStyle.Italic), offset + s, offset + e)
        }
        INLINE_UNDER.findAll(visible).forEach { match ->
            val s = match.range.first + 2
            val e = match.range.last - 1
            if (s < e) builder.addStyle(
                SpanStyle(textDecoration = TextDecoration.Underline),
                offset + s, offset + e
            )
        }
    }
}


private data class MappingSpan(
    val originalStart: Int,
    val originalEnd: Int,
    val transformedStart: Int,
    val transformedEnd: Int,
    val delta: Int,
    val prependedLen: Int = 0
)


/**
 * Bidirectional offset mapping built from a list of `MappingSpan`s. The
 * spans cover the original text contiguously (one per visible line plus
 * one per newline) and carry `delta = originalEnd - originalStart -
 * (transformedEnd - transformedStart)` so the within-span formulas know
 * how many source characters were hidden on a line.
 */
private class NoteOffsetMapping(
    private val spans: List<MappingSpan>,
    private val originalLength: Int,
    private val transformedLength: Int
) : OffsetMapping {

    override fun originalToTransformed(offset: Int): Int {
        if (spans.isEmpty()) return offset.coerceIn(0, transformedLength)
        val clamped = offset.coerceIn(0, originalLength)
        val span = spans[findSpanIndexForOriginal(clamped)]
        if (clamped >= span.originalEnd) {
            val carry = clamped - span.originalEnd
            return (span.transformedEnd + carry).coerceAtMost(transformedLength)
        }
        // Stripped prefix region: clicks inside the hidden `>#` (etc.)
        // snap to the start of the synthesised display prefix (call it
        // "before the 1."), so the user's natural intent — to put the
        // caret nearest the visible content — wins.
        if (clamped < span.originalStart + span.delta) {
            return span.transformedStart.coerceIn(0, transformedLength)
        }
        val within = (clamped - span.originalStart) - span.delta
        return (span.transformedStart + span.prependedLen + within).coerceIn(0, transformedLength)
    }

    override fun transformedToOriginal(offset: Int): Int {
        if (spans.isEmpty()) return offset.coerceIn(0, originalLength)
        val clamped = offset.coerceIn(0, transformedLength)
        val span = spans[findSpanIndexForTransformed(clamped)]
        if (clamped >= span.transformedEnd) {
            val carry = clamped - span.transformedEnd
            return (span.originalEnd + carry).coerceAtMost(originalLength)
        }
        // Synthesised display prefix (`1. `, `• `): any click inside it
        // maps to just-after the stripped markdown prefix in source —
        // the user can't actually edit into the synthesised area, so
        // we collapse it to the same point as if they clicked on the
        // first character of the visible content.
        if (clamped < span.transformedStart + span.prependedLen) {
            return (span.originalStart + span.delta).coerceIn(0, originalLength)
        }
        val within = (clamped - span.transformedStart) - span.prependedLen
        return (span.originalStart + span.delta + within).coerceIn(0, originalLength)
    }

    private fun findSpanIndexForOriginal(offset: Int): Int {
        var lo = 0
        var hi = spans.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val span = spans[mid]
            when {
                offset < span.originalStart -> hi = mid - 1
                offset > span.originalEnd -> lo = mid + 1
                else -> return mid
            }
        }
        return (lo - 1).coerceAtLeast(0)
    }

    private fun findSpanIndexForTransformed(offset: Int): Int {
        var lo = 0
        var hi = spans.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val span = spans[mid]
            when {
                offset < span.transformedStart -> hi = mid - 1
                offset > span.transformedEnd -> lo = mid + 1
                else -> return mid
            }
        }
        return (lo - 1).coerceAtLeast(0)
    }
}


internal data class ColorMark(val color: Color, val hex: String, val name: String)


private fun colorFromHex(hex: String): Color? {
    val cleaned = hex.removePrefix("#")
    val value = runCatching { cleaned.toLong(16) }.getOrNull() ?: return null
    return when (cleaned.length) {
        6 -> Color(
            red = ((value shr 16) and 0xFF).toFloat() / 255f,
            green = ((value shr 8) and 0xFF).toFloat() / 255f,
            blue = (value and 0xFF).toFloat() / 255f
        )
        8 -> Color(
            alpha = ((value shr 24) and 0xFF).toFloat() / 255f,
            red = ((value shr 16) and 0xFF).toFloat() / 255f,
            green = ((value shr 8) and 0xFF).toFloat() / 255f,
            blue = (value and 0xFF).toFloat() / 255f
        )
        else -> null
    }
}

