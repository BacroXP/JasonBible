@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class
)

package ui

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue



private enum class WordClass { WHITESPACE, ALPHANUM, OTHER }
private fun wordClass(c: Char): WordClass = when {
    c.isWhitespace() -> WordClass.WHITESPACE
    c.isLetterOrDigit() -> WordClass.ALPHANUM
    else -> WordClass.OTHER
}
internal enum class WordDir { PREV, NEXT }
internal fun nextWordBoundary(text: String, pos: Int, dir: WordDir): Int {
    val n = text.length
    val p = pos.coerceIn(0, n)
    if (dir == WordDir.NEXT) {
        if (p >= n) return n
        var i = p
        val startCls = wordClass(text[i])
        while (i < n && wordClass(text[i]) == startCls) i++
        while (i < n && text[i].isWhitespace()) i++
        // Always advance at least one char so the user sees motion even
        // when the cursor already parks at the end of an alphanumeric
        // run followed only by punctuation / EOL.
        return i.coerceAtLeast(p + 1)
    } else {
        if (p <= 0) return 0
        var i = p
        while (i > 0 && text[i - 1].isWhitespace()) i--
        if (i == 0) return 0
        val endCls = wordClass(text[i - 1])
        while (i > 0 && wordClass(text[i - 1]) == endCls) i--
        return i
    }
}
// Returns [start, end) of the alphanumeric word containing `pos` in
// `text`. If `pos` lands on whitespace or punctuation, the returned
// range is empty (start == end). Used by double-click word selection.
internal fun wordBoundsAt(text: String, pos: Int): IntRange {
    val n = text.length
    val p = pos.coerceIn(0, n)
    var s = p
    while (s > 0 && wordClass(text[s - 1]) == WordClass.ALPHANUM) s--
    var e = p
    while (e < n && wordClass(text[e]) == WordClass.ALPHANUM) e++
    return s until e
}

// Text-stats payload shown in the editor footer. Deliberately limited to
// the two numbers a writer actually acts on — word count and character
// count. Derived trivia (lines, characters without spaces, estimated
// reading time) was dropped: it changed on every keystroke without ever
// telling the user anything useful.
internal data class TextStats(
    val words: Int,
    val chars: Int
)
internal fun computeTextStats(text: String): TextStats {
    if (text.isEmpty()) return TextStats(0, 0)
    val trimmed = text.trim()
    val words = if (trimmed.isEmpty()) 0 else trimmed.split(Regex("\\s+")).size
    return TextStats(words, text.length)
}

// ---------------------------------------------------------------------------
// Editor mutations (operate on raw markdown text)
// ---------------------------------------------------------------------------
/**
 * Returns this value with its caret / selection pulled back inside the
 * current text.
 *
 * Edits that SHORTEN the text (Replace All being the main one) are the
 * classic source of an offset that no longer fits: the field then slices
 * the new text with the old offsets and throws
 * `IndexOutOfBoundsException: Range [start, end) out of bounds`.
 *
 * `TextFieldValue` itself constrains its selection today, so this is a
 * defensive normaliser rather than a reproduction of the reported crash
 * (see `ReplaceAllShrinkTest`). It keeps every edit path — present or
 * future — from handing the field an offset it would have to fix up.
 */
internal fun TextFieldValue.withSelectionInBounds(): TextFieldValue {
    val last = text.length
    val start = selection.start.coerceIn(0, last)
    val end = selection.end.coerceIn(0, last)
    return if (start == selection.start && end == selection.end) {
        this
    } else {
        copy(selection = TextRange(start, end))
    }
}


/**
 * Replaces every [matches] range in [current] with [replacement] and parks
 * the caret at the start of the first match.
 *
 * Substitutions run in REVERSE order so earlier offsets stay valid while
 * later positions shift. The result is passed through
 * [withSelectionInBounds] because Replace All usually SHORTENS the note —
 * a stale caret / selection past the new end crashes the field.
 */
internal fun replaceAllMatches(
    current: TextFieldValue,
    matches: List<IntRange>,
    replacement: String
): TextFieldValue {
    if (matches.isEmpty()) return current
    var newText = current.text
    matches.reversed().forEach { range ->
        val start = range.first.coerceIn(0, newText.length)
        val end = (range.last + 1).coerceIn(start, newText.length)
        newText = newText.substring(0, start) + replacement + newText.substring(end)
    }
    val caret = matches.first().first.coerceIn(0, newText.length)
    return current
        .copy(text = newText, selection = TextRange(caret))
        .withSelectionInBounds()
}


internal fun insertAtSelection(current: TextFieldValue, insertion: String): TextFieldValue {
    val start = minOf(current.selection.start, current.selection.end).coerceIn(0, current.text.length)
    val end = maxOf(current.selection.start, current.selection.end).coerceIn(0, current.text.length)
    val newText = buildString {
        append(current.text.substring(0, start))
        append(insertion)
        append(current.text.substring(end))
    }
    val cursor = start + insertion.length
    return current.copy(text = newText, selection = TextRange(cursor, cursor))
}


internal fun toggleWrap(current: TextFieldValue, marker: String): TextFieldValue {
    val start = minOf(current.selection.start, current.selection.end).coerceIn(0, current.text.length)
    val end = maxOf(current.selection.start, current.selection.end).coerceIn(0, current.text.length)
    if (start == end) return insertAtSelection(current, "$marker$marker")

    val selected = current.text.substring(start, end)
    val wrapped = if (selected.startsWith(marker) && selected.endsWith(marker)) {
        selected.removePrefix(marker).removeSuffix(marker)
    } else {
        "$marker$selected$marker"
    }
    val newText = buildString {
        append(current.text.substring(0, start))
        append(wrapped)
        append(current.text.substring(end))
    }
    val cursor = start + wrapped.length
    return current.copy(text = newText, selection = TextRange(cursor, cursor))
}


internal fun prefixSelectedLines(current: TextFieldValue, prefix: String): TextFieldValue {
    val start = minOf(current.selection.start, current.selection.end).coerceIn(0, current.text.length)
    val end = maxOf(current.selection.start, current.selection.end).coerceIn(0, current.text.length)

    // Prefixes go AFTER any leading alignment/direction markers so a
    // centered line stays centered when it becomes a heading (`\u200B# …`
    // rather than `# \u200B…`, which would mis-parse the heading).
    //
    // Pressing the same list / heading button twice removes the prefix
    // again (Word's toggle behaviour), and pressing a different one
    // REPLACES the current block prefix instead of stacking `- # text`.
    fun applyToLine(line: String): String {
        val markers = leadingMarkers(line)
        val content = stripLeadingMarkers(line)
        val newContent = if (prefix.isNotEmpty() && content.startsWith(prefix)) {
            content.removePrefix(prefix)
        } else {
            prefix + stripBlockPrefixes(content)
        }
        return markers + newContent
    }

    if (start == end) {
        val lineStart = current.text.lastIndexOf('\n', start - 1).let { if (it < 0) 0 else it + 1 }
        val line = current.text.substring(lineStart)
        val newLine = applyToLine(line)
        val newText = buildString {
            append(current.text.substring(0, lineStart))
            append(newLine)
        }
        // The prefix is written at the LINE START, so the caret keeps its
        // position relative to the text: it shifts by exactly the number
        // of characters that were added/removed. A blank line really does
        // get the prefix (starting a list / heading on a fresh line used
        // to be a no-op that also pushed the caret past the end of the
        // line, so the next keystroke landed on the wrong line).
        val cursor = (start + (newLine.length - line.length))
            .coerceIn(lineStart, newText.length)
        return current.copy(text = newText, selection = TextRange(cursor, cursor))
    }

    val block = current.text.substring(start, end)
    val prefixed = block.lines().joinToString("\n") { line ->
        if (line.isBlank()) line else applyToLine(line)
    }
    val newText = buildString {
        append(current.text.substring(0, start))
        append(prefixed)
        append(current.text.substring(end))
    }
    val cursor = start + prefixed.length
    return current.copy(text = newText, selection = TextRange(start, cursor))
}


/**
 * Paragraph styles selectable from the Word-style "Styles" dropdown.
 * Each style maps to the block prefix written into the source text.
 */
internal enum class NoteStyle(
    val label: String,
    val prefix: String,
    /** Markdown heading level of this style (1–6), 0 for non-headings. */
    val headingLevel: Int = 0
) {
    NORMAL("Normal", ""),
    H1("H1", "# ", 1),
    H2("H2", "## ", 2),
    H3("H3", "### ", 3),
    H4("H4", "#### ", 4),
    H5("H5", "##### ", 5),
    H6("H6", "###### ", 6),
    QUOTE("Quote", "> ")
}

/** The [NoteStyle] for a markdown heading [level] (1–6), or null. */
internal fun styleForHeadingLevel(level: Int): NoteStyle? =
    NoteStyle.entries.firstOrNull { it.headingLevel == level }


/**
 * Returns the paragraph style currently applied to the line holding the
 * cursor (or the start of a multi-line selection), so the Styles
 * dropdown can mirror what Word shows at the caret.
 */
internal fun currentBlockStyle(current: TextFieldValue): NoteStyle {
    val caret = minOf(current.selection.start, current.selection.end).coerceIn(0, current.text.length)
    val lineStart = current.text.lastIndexOf('\n', caret - 1).let { if (it < 0) 0 else it + 1 }
    val lineEnd = current.text.indexOf('\n', caret).let { if (it < 0) current.text.length else it }
    val line = stripLeadingMarkers(current.text.substring(lineStart, lineEnd))
    val headingLevel = headingLevelOf(line)
    if (headingLevel > 0) return styleForHeadingLevel(headingLevel) ?: NoteStyle.NORMAL
    return if (line.startsWith(">")) NoteStyle.QUOTE else NoteStyle.NORMAL
}


/**
 * Removes every block-level prefix from a raw source line — headings,
 * quote chains (incl. `> .` / `> #` list items), bullets and numbered
 * list markers — leaving only the line's plain content. Used when
 * applying a new paragraph style so the result is idempotent
 * (re-applying the same style never stacks prefixes).
 */
private fun stripBlockPrefix(line: String): String =
    leadingMarkers(line) + stripBlockPrefixes(stripLeadingMarkers(line))


/**
 * The line's plain content with every block-level prefix removed —
 * headings at ANY level (1–6), quote chains, bullets and numbered list
 * markers. Callers add the leading alignment / direction markers back.
 */
private fun stripBlockPrefixes(content: String): String {
    var result = content

    headingPrefixRegex.find(result)?.takeIf { it.range.first == 0 }?.let { match ->
        result = result.removeRange(match.range)
    }

    val quoteDepth = result.takeWhile { it == '>' }.length
    if (quoteDepth > 0) {
        result = result.drop(quoteDepth).trimStart(' ', '.', '#')
        if (result.startsWith(" ")) result = result.drop(1)
    }

    if (result.startsWith("- ")) result = result.removePrefix("- ")

    orderedListRegex.matchAt(result, 0)?.let { result = result.drop(it.value.length) }

    return result
}


/**
 * Applies [style] to the current line (or every line of the selection)
 * by stripping any existing block prefix and writing the style's own
 * prefix. The caret follows the edit so focus stays on the same text.
 */
internal fun applyBlockStyle(current: TextFieldValue, style: NoteStyle): TextFieldValue {
    val start = minOf(current.selection.start, current.selection.end).coerceIn(0, current.text.length)
    val end = maxOf(current.selection.start, current.selection.end).coerceIn(0, current.text.length)

    if (start == end) {
        val lineStart = current.text.lastIndexOf('\n', start - 1).let { if (it < 0) 0 else it + 1 }
        val lineEnd = current.text.indexOf('\n', start).let { if (it < 0) current.text.length else it }
        val rawLine = current.text.substring(lineStart, lineEnd)
        val stripped = stripBlockPrefix(rawLine)
        val newLine = style.prefix + stripped
        val newText = buildString {
            append(current.text.substring(0, lineStart))
            append(newLine)
            append(current.text.substring(lineEnd))
        }
        // Keep the caret anchored to the same character: shift it by
        // however many prefix characters were removed/added.
        val caretInLine = (start - lineStart).coerceIn(0, rawLine.length)
        val newCaret = lineStart + (caretInLine - (rawLine.length - stripped.length) + style.prefix.length)
            .coerceIn(0, newLine.length)
        return current.copy(text = newText, selection = TextRange(newCaret, newCaret))
    }

    val block = current.text.substring(start, end)
    val styled = block.lines().joinToString("\n") { line ->
        if (line.isBlank()) line else style.prefix + stripBlockPrefix(line)
    }
    val newText = buildString {
        append(current.text.substring(0, start))
        append(styled)
        append(current.text.substring(end))
    }
    val cursor = start + styled.length
    return current.copy(text = newText, selection = TextRange(start, cursor))
}


internal fun toggleColoredQuote(current: TextFieldValue, colorHex: String): TextFieldValue {
    val start = minOf(current.selection.start, current.selection.end).coerceIn(0, current.text.length)
    val end = maxOf(current.selection.start, current.selection.end).coerceIn(0, current.text.length)
    if (start == end) return insertAtSelection(current, "\"text\"[$colorHex]")
    val selected = current.text.substring(start, end)
    val quoted = "\"$selected\"[$colorHex]"
    val newText = buildString {
        append(current.text.substring(0, start))
        append(quoted)
        append(current.text.substring(end))
    }
    val cursor = start + quoted.length
    return current.copy(text = newText, selection = TextRange(cursor, cursor))
}


/**
 * Toggles the orientation marker for the cursor line. Default is LTR (no
 * marker). Clicking on an LTR line prepends an RLM (RTL); clicking again
 * removes it (back to LTR). LRM markers are also stripped on click. The
 * leading alignment marker (if any) is preserved, so a centered line
 * stays centered when its direction toggles.
 */
internal fun toggleLineOrientation(current: TextFieldValue): TextFieldValue {
    val start = minOf(current.selection.start, current.selection.end).coerceIn(0, current.text.length)
    val lineStart = current.text.lastIndexOf('\n', start - 1).let { if (it < 0) 0 else it + 1 }
    val lineEnd = current.text.indexOf('\n', lineStart).let { if (it < 0) current.text.length else it }
    val line = current.text.substring(lineStart, lineEnd)

    val alignment = alignmentMarkerOf(line)
    val directionPart = if (alignment.isNotEmpty()) line.removePrefix(alignment) else line
    val newDirectionPart = when {
        directionPart.startsWith(RLM) -> directionPart.removePrefix(RLM)
        directionPart.startsWith(LRM) -> directionPart.removePrefix(LRM)
        else -> RLM + directionPart
    }
    val newLine = alignment + newDirectionPart
    val newText = buildString {
        append(current.text.substring(0, lineStart))
        append(newLine)
        append(current.text.substring(lineEnd))
    }
    val cursorDelta = newLine.length - line.length
    val newCursor = (start + cursorDelta).coerceIn(lineStart, newText.length)
    return current.copy(text = newText, selection = TextRange(newCursor, newCursor))
}


// ---------------------------------------------------------------------------
// Paragraph alignment (left / center / right)
//
// Real visual alignment, stored as an invisible leading marker per line:
// CENTER = \u200B (zero width space), RIGHT = \u2060 (word joiner), LEFT =
// the absence of both. The visual transformation strips the marker and
// applies ParagraphStyle.textAlign to the line, so the text field lays
// the paragraph out aligned. Toggling the same alignment again returns
// the line to left (Word-style), while LEFT always strips any marker.
// ---------------------------------------------------------------------------

/**
 * Alignment of the line holding the caret (or the start of a
 * multi-line selection), for mirroring in the toolbar buttons.
 */
internal fun currentLineAlignment(current: TextFieldValue): LineAlignment {
    val caret = minOf(current.selection.start, current.selection.end).coerceIn(0, current.text.length)
    val lineStart = current.text.lastIndexOf('\n', caret - 1).let { if (it < 0) 0 else it + 1 }
    val lineEnd = current.text.indexOf('\n', caret).let { if (it < 0) current.text.length else it }
    val line = current.text.substring(lineStart, lineEnd)
    return when {
        line.startsWith(ALIGN_CENTER) -> LineAlignment.CENTER
        line.startsWith(ALIGN_RIGHT) -> LineAlignment.RIGHT
        else -> LineAlignment.LEFT
    }
}

/**
 * Apply [alignment] to the cursor line — or every line of the
 * selection. CENTER / RIGHT toggle (re-applying returns the line to
 * left); LEFT always strips any center/right marker. The marker goes
 * FIRST, before any block prefix / direction marker, so the line keeps
 * its heading / quote / direction classification.
 */
internal fun toggleLineAlignment(current: TextFieldValue, alignment: LineAlignment): TextFieldValue {
    val start = minOf(current.selection.start, current.selection.end).coerceIn(0, current.text.length)
    val end = maxOf(current.selection.start, current.selection.end).coerceIn(0, current.text.length)

    val from: Int
    val to: Int
    if (start == end) {
        from = current.text.lastIndexOf('\n', start - 1).let { if (it < 0) 0 else it + 1 }
        to = current.text.indexOf('\n', start).let { if (it < 0) current.text.length else it }
    } else {
        from = start
        to = end
    }

    val affected = current.text.substring(from, to)
    val realigned = affected.lines().joinToString("\n") { line ->
        if (line.isBlank()) line else alignLine(line, alignment)
    }
    if (realigned == affected) return current

    val newText = buildString {
        append(current.text.substring(0, from))
        append(realigned)
        append(current.text.substring(to))
    }
    val newEnd = from + realigned.length
    val newSelection = if (start == end) {
        TextRange(newEnd.coerceIn(0, newText.length))
    } else {
        TextRange(from, newEnd.coerceIn(from, newText.length))
    }
    return current.copy(text = newText, selection = newSelection)
}

private fun alignLine(line: String, alignment: LineAlignment): String {
    // Strip any existing alignment marker, then re-apply the requested
    // one (LEFT = no marker). Direction markers are left untouched —
    // alignment and direction are independent.
    val stripped = line.removePrefix(ALIGN_CENTER).removePrefix(ALIGN_RIGHT)
    return when (alignment) {
        LineAlignment.LEFT -> stripped
        LineAlignment.CENTER -> if (line.startsWith(ALIGN_CENTER)) stripped else ALIGN_CENTER + stripped
        LineAlignment.RIGHT -> if (line.startsWith(ALIGN_RIGHT)) stripped else ALIGN_RIGHT + stripped
    }
}
// ---------------------------------------------------------------------------
// Word-style "Clear Formatting"
//
// Strips inline markdown markers (**bold**, *italic*, __underline__, and
// `"text"[#hex]` coloured quotes unwrap back to plain text) plus line
// prefixes (H1/H2/quote/bullet/number) from the selection — or from the
// cursor's line when nothing is selected. Mirrors Word's "Clear All
// Formatting" eraser: content survives, styling does not.
// ---------------------------------------------------------------------------
internal fun clearInlineFormatting(current: TextFieldValue): TextFieldValue {
    val start = minOf(current.selection.start, current.selection.end).coerceIn(0, current.text.length)
    val end = maxOf(current.selection.start, current.selection.end).coerceIn(0, current.text.length)

    val from: Int
    val to: Int
    if (start == end) {
        // No selection → operate on the cursor's line.
        from = current.text.lastIndexOf('\n', start - 1).let { if (it < 0) 0 else it + 1 }
        to = current.text.indexOf('\n', start).let { if (it < 0) current.text.length else it }
    } else {
        from = start
        to = end
    }

    val affected = current.text.substring(from, to)
    val cleaned = stripMarkdownFormatting(affected)
    if (cleaned == affected) return current

    val newText = buildString {
        append(current.text.substring(0, from))
        append(cleaned)
        append(current.text.substring(to))
    }
    // Park the selection inside the cleaned block so the user can keep
    // working; a collapsed cursor lands just after the cleaned text.
    val newEnd = from + cleaned.length
    val newSelection = if (start == end) TextRange(newEnd) else TextRange(from, newEnd)
    return current.copy(text = newText, selection = newSelection)
}


private fun stripMarkdownFormatting(text: String): String {
    val noInline = text
        .replace(INLINE_BOLD, "$1")
        .replace(INLINE_UNDER, "$1")
        .replace(INLINE_ITALIC, "$1")
    return noInline.lines().joinToString("\n") { line ->
        // Alignment markers are editor-only styling: clear formatting
        // drops them (Word's eraser clears alignment too).
        var l = line.removePrefix(ALIGN_CENTER).removePrefix(ALIGN_RIGHT)
        // `"quote"[#hex]` → `quote` (colour AND quote markers removed).
        l = l.replace(Regex("^\"(.*?)\"\\[#[0-9A-Fa-f]{3,8}](.*)$")) { m ->
            (m.groupValues[1].trim() + " " + m.groupValues[2].trim()).trim()
        }
        // Line prefixes: H1/H2, quotes, bullets, numbered lists.
        l = l.replace(Regex("^#{1,2}\\s+"), "")
        l = l.replace(Regex("^>+\\s*"), "")
        l = l.replace(Regex("^[-*]\\s+"), "")
        l = l.replace(Regex("^\\d+\\.\\s+"), "")
        l.trimEnd()
    }
}


/**
 * Removes only the `[#hex]` colour markers from the selection (or cursor
 * line), keeping the quote markers — the toolbar's "no colour" dot. The
 * coloured quote falls back to a plain quote instead of being unwrapped.
 */
internal fun removeColorMarkers(current: TextFieldValue): TextFieldValue {
    val start = minOf(current.selection.start, current.selection.end).coerceIn(0, current.text.length)
    val end = maxOf(current.selection.start, current.selection.end).coerceIn(0, current.text.length)

    val from: Int
    val to: Int
    if (start == end) {
        from = current.text.lastIndexOf('\n', start - 1).let { if (it < 0) 0 else it + 1 }
        to = current.text.indexOf('\n', start).let { if (it < 0) current.text.length else it }
    } else {
        from = start
        to = end
    }

    val affected = current.text.substring(from, to)
    val cleaned = affected.lines().joinToString("\n") { line ->
        line.replace(colorMarkerRegex, "")
    }
    if (cleaned == affected) return current

    val newText = buildString {
        append(current.text.substring(0, from))
        append(cleaned)
        append(current.text.substring(to))
    }
    val newEnd = from + cleaned.length
    val newSelection = if (start == end) TextRange(newEnd) else TextRange(from, newEnd)
    return current.copy(text = newText, selection = newSelection)
}

// ---------------------------------------------------------------------------
// Enter-key list continuation
//
// When `autoContinueLists` is on and the user presses Enter at the end of
// a list / enumeration line, we want a NEW list entry to appear. Detection
// runs in `applyEditorChange` by intercepting the TextFieldValue change:
// if exactly one `\n` was added at the previous cursor position AND the
// line that the cursor was on ended in a recognised list prefix, we
// rewrite `next` so the new line already has the matching continuation.
//
// Empty list items are demoted: pressing Enter on a final `- ` / `>. ` /
// `># ` strips the prefix so the user exits the list cleanly onto a
// blank line.
// ---------------------------------------------------------------------------
private enum class ContinuationKind { CONTINUE, EXIT }


private data class DetectedContinuation(
    val kind: ContinuationKind,
    val prefix: String
)


internal fun continueListAtEnter(prev: TextFieldValue, next: TextFieldValue): TextFieldValue {
    val selStart = minOf(prev.selection.start, prev.selection.end)
    val selEnd = maxOf(prev.selection.start, prev.selection.end)
    if (selStart != selEnd) return next
    if (next.selection.start != next.selection.end) return next

    // Exactly one newline was inserted, AT selStart of prev.
    if (next.text.length - prev.text.length != 1) return next
    if (selStart !in 0..next.text.lastIndex || next.text[selStart] != '\n') return next

    // Compute the line the cursor was on (in prev).
    val lineStart = prev.text.lastIndexOf('\n', selStart - 1).let { if (it < 0) 0 else it + 1 }
    val lineEnd = prev.text.indexOf('\n', lineStart).let { if (it < 0) prev.text.length else it }
    if (selStart != lineEnd) return next
    val line = prev.text.substring(lineStart, lineEnd)

    val detected = detectListContinuation(line, prev.text, lineStart) ?: return next

    return when (detected.kind) {
        ContinuationKind.EXIT -> {
            // Strip the empty list prefix from prev so the new line stays blank.
            val newText = prev.text.substring(0, lineStart) + "\n"
            val newCursor = newText.length
            next.copy(text = newText, selection = TextRange(newCursor))
        }
        ContinuationKind.CONTINUE -> {
            // Insert continuation prefix immediately after the entered \n.
            val withContinuation =
                next.text.substring(0, selStart + 1) +
                    detected.prefix +
                    next.text.substring(selStart + 1)
            val newCursor = selStart + 1 + detected.prefix.length
            next.copy(text = withContinuation, selection = TextRange(newCursor))
        }
    }
}


private val emptyQuoteBulletRegex = Regex("^>+\\.\\s*$")
private val emptyQuoteNumberedRegex = Regex("^>+#\\s*$")


private fun detectListContinuation(line: String, fullSource: String, lineStart: Int): DetectedContinuation? {
    val quoteDepth = if (line.startsWith(">")) line.takeWhile { it == '>' }.length else 0

    // Empty list items are demoted (Enter exits the list).
    if (line == "- ") return DetectedContinuation(ContinuationKind.EXIT, "- ")
    if (emptyQuoteBulletRegex.matches(line)) {
        return DetectedContinuation(ContinuationKind.EXIT, line)
    }
    if (emptyQuoteNumberedRegex.matches(line)) {
        return DetectedContinuation(ContinuationKind.EXIT, line)
    }

    // Non-empty plain bullet `- foo` -> continue with `- `
    if (line.startsWith("- ") && line.length > 2) {
        return DetectedContinuation(ContinuationKind.CONTINUE, "- ")
    }

    // Non-empty plain numbered `1. foo` -> walk backwards for the previous
    // numbered line and increment from there so the user's sequence is
    // preserved.
    orderedListRegex.matchAt(line, 0)?.let { match ->
        val currentNum = match.value.substringBefore('.').toIntOrNull() ?: 1
        val prevNum = lastPlainNumberedNumber(fullSource, lineStart)
        val nextNum = (maxOf(currentNum, prevNum ?: currentNum) + 1)
        return DetectedContinuation(ContinuationKind.CONTINUE, "$nextNum. ")
    }

    // Non-empty quote-bullet / quote-numbered `>. foo` / `># foo` (and
    // their multi-`>` variants). For `>#` we save the raw `#` because
    // the editor synthesises the displayed digit at render time.
    if (quoteDepth > 0 && line.length > quoteDepth) {
        val after = line[quoteDepth]
        val prefix = ">".repeat(quoteDepth)
        when (after) {
            '.' -> return DetectedContinuation(ContinuationKind.CONTINUE, "$prefix. ")
            '#' -> return DetectedContinuation(ContinuationKind.CONTINUE, "$prefix# ")
        }
    }

    return null
}


private fun lastPlainNumberedNumber(source: String, beforeStart: Int): Int? {
    // `pos` is always the START of a line (it begins at the caret's line
    // start and each iteration moves to the previous line's start), so the
    // character at `pos - 1` is that line's preceding '\n'. Looking for the
    // newline AT `pos - 1` (the old code did) made lineEnd = pos - 1 and
    // lineStart = pos, i.e. `substring(1929, 1928)` — the
    // "Range [1929, 1928) out of bounds" crash on Enter in a numbered list.
    var pos = beforeStart.coerceIn(0, source.length)
    while (pos > 0) {
        val lineEnd = pos - 1
        val nlIdx = source.lastIndexOf('\n', lineEnd - 1)
        val lineStart = if (nlIdx < 0) 0 else nlIdx + 1
        val line = source.substring(lineStart, lineEnd)
        orderedListRegex.matchAt(line, 0)?.let { match ->
            return match.value.substringBefore('.').toIntOrNull()
        }
        // Stop at blank line — numbered sequences don't cross blank lines.
        if (line.isBlank()) return null
        pos = lineStart
    }
    return null
}

// ---------------------------------------------------------------------------
// Undo / Redo
//
// `past` is the stack of states we can `undo` back to. The active editor
// value lives in the composable (not here); it's passed in `undo(current)`
// / `redo(current)` so we can push it onto the opposite stack and pop the
// correct previous/next state.
// ---------------------------------------------------------------------------
internal class UndoManager(private val maxSize: Int = 200) {
    private val past = ArrayDeque<TextFieldValue>()
    private val future = ArrayDeque<TextFieldValue>()

    fun reset() {
        past.clear()
        future.clear()
    }

    fun recordChange(prev: TextFieldValue, next: TextFieldValue) {
        if (prev.text == next.text && prev.selection == next.selection) return
        if (past.isNotEmpty() &&
            past.last().text == prev.text &&
            past.last().selection == prev.selection
        ) return
        past.addLast(prev)
        while (past.size > maxSize) past.removeFirst()
        future.clear()
    }

    fun undo(current: TextFieldValue): TextFieldValue? {
        if (past.isEmpty()) return null
        future.addLast(current)
        return past.removeLast()
    }

    fun redo(current: TextFieldValue): TextFieldValue? {
        if (future.isEmpty()) return null
        val next = future.removeLast()
        past.addLast(current)
        return next
    }

    fun canUndo(): Boolean = past.isNotEmpty()
    fun canRedo(): Boolean = future.isNotEmpty()
}



