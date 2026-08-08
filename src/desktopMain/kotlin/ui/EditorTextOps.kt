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

// Text-stats payload shown in the editor footer ("words · chars · …").
internal data class TextStats(
    val words: Int,
    val chars: Int,
    val charsNoSpaces: Int,
    val lines: Int,
    val readingMinutes: Int
)
internal fun computeTextStats(text: String): TextStats {
    if (text.isEmpty()) return TextStats(0, 0, 0, 0, 0)
    val trimmed = text.trim()
    val words = if (trimmed.isEmpty()) 0 else trimmed.split(Regex("\\s+")).size
    val chars = text.length
    val charsNoSpaces = text.count { !it.isWhitespace() }
    val lines = text.count { it == '\n' } + 1
    val readingMinutes = if (words == 0) 0 else ((words + 199) / 200).coerceAtLeast(1)
    return TextStats(words, chars, charsNoSpaces, lines, readingMinutes)
}

// ---------------------------------------------------------------------------
// Editor mutations (operate on raw markdown text)
// ---------------------------------------------------------------------------
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

    if (start == end) {
        val lineStart = current.text.lastIndexOf('\n', start - 1).let { if (it < 0) 0 else it + 1 }
        val newText = buildString {
            append(current.text.substring(0, lineStart))
            append(prefix)
            append(current.text.substring(lineStart))
        }
        val cursor = start + prefix.length
        return current.copy(text = newText, selection = TextRange(cursor, cursor))
    }

    val block = current.text.substring(start, end)
    val prefixed = block.lines().joinToString("\n") { line ->
        if (line.isBlank()) line else prefix + line
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
internal enum class NoteStyle(val label: String, val prefix: String) {
    NORMAL("Normal", ""),
    H1("H1", "# "),
    H2("H2", "## "),
    QUOTE("Quote", "> ")
}


/**
 * Returns the paragraph style currently applied to the line holding the
 * cursor (or the start of a multi-line selection), so the Styles
 * dropdown can mirror what Word shows at the caret.
 */
internal fun currentBlockStyle(current: TextFieldValue): NoteStyle {
    val caret = minOf(current.selection.start, current.selection.end).coerceIn(0, current.text.length)
    val lineStart = current.text.lastIndexOf('\n', caret - 1).let { if (it < 0) 0 else it + 1 }
    val lineEnd = current.text.indexOf('\n', caret).let { if (it < 0) current.text.length else it }
    val line = current.text.substring(lineStart, lineEnd)
        .removePrefix(RLM)
        .removePrefix(LRM)
    return when {
        line.startsWith("## ") -> NoteStyle.H2
        line.startsWith("# ") -> NoteStyle.H1
        line.startsWith(">") -> NoteStyle.QUOTE
        else -> NoteStyle.NORMAL
    }
}


/**
 * Removes every block-level prefix from a raw source line — headings,
 * quote chains (incl. `> .` / `> #` list items), bullets and numbered
 * list markers — leaving only the line's plain content. Used when
 * applying a new paragraph style so the result is idempotent
 * (re-applying the same style never stacks prefixes).
 */
private fun stripBlockPrefix(line: String): String {
    // Preserve an RTL/LTR direction marker (RLM/LRM) that may sit in
    // front of the block prefix, e.g. RLM + "# text" from toggling the
    // line orientation on a heading. The marker is re-appended after the
    // prefix is stripped so applying a style keeps the line's direction.
    val directionMarker = when {
        line.startsWith(RLM) -> RLM
        line.startsWith(LRM) -> LRM
        else -> ""
    }
    var result = if (directionMarker.isNotEmpty()) line.removePrefix(directionMarker) else line

    if (result.startsWith("## ")) result = result.removePrefix("## ")
    else if (result.startsWith("# ")) result = result.removePrefix("# ")

    val quoteDepth = result.takeWhile { it == '>' }.length
    if (quoteDepth > 0) {
        result = result.drop(quoteDepth).trimStart(' ', '.', '#')
        if (result.startsWith(" ")) result = result.drop(1)
    }

    if (result.startsWith("- ")) result = result.removePrefix("- ")

    orderedListRegex.matchAt(result, 0)?.let { result = result.drop(it.value.length) }

    return directionMarker + result
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
 * removes it (back to LTR). LRM markers are also stripped on click.
 */
internal fun toggleLineOrientation(current: TextFieldValue): TextFieldValue {
    val start = minOf(current.selection.start, current.selection.end).coerceIn(0, current.text.length)
    val lineStart = current.text.lastIndexOf('\n', start - 1).let { if (it < 0) 0 else it + 1 }
    val lineEnd = current.text.indexOf('\n', lineStart).let { if (it < 0) current.text.length else it }
    val line = current.text.substring(lineStart, lineEnd)

    val newLine = when {
        line.startsWith(RLM) -> line.removePrefix(RLM)
        line.startsWith(LRM) -> line.removePrefix(LRM)
        else -> RLM + line
    }
    val newText = buildString {
        append(current.text.substring(0, lineStart))
        append(newLine)
        append(current.text.substring(lineEnd))
    }
    val cursorDelta = newLine.length - line.length
    val newCursor = (start + cursorDelta).coerceIn(lineStart, newText.length)
    return current.copy(text = newText, selection = TextRange(newCursor, newCursor))
}

/**
 * Forces a specific text direction on the cursor's line by stripping any
 * existing RLM / LRM marker and prepending RLM iff [wantRtl] is true.
 * Unlike [toggleLineOrientation] (which cycles LTR ↔ RTL), this always
 * sets the direction to exactly what the caller asked for — keyed
 * shortcuts Ctrl+L (force-LTR) / Ctrl+R (force-RTL) reuse this so a
 * re-press lands on the same state.
 */
internal fun forceLineOrientation(current: TextFieldValue, wantRtl: Boolean): TextFieldValue {
    val start = minOf(current.selection.start, current.selection.end).coerceIn(0, current.text.length)
    val lineStart = current.text.lastIndexOf('\n', start - 1).let { if (it < 0) 0 else it + 1 }
    val lineEnd = current.text.indexOf('\n', lineStart).let { if (it < 0) current.text.length else it }
    val line = current.text.substring(lineStart, lineEnd)
    val stripped = line.removePrefix(RLM).removePrefix(LRM)
    val newLine = if (wantRtl) RLM + stripped else stripped
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
        var l = line
        // `"quote"[#hex]` → `quote` (colour AND quote markers removed).
        l = l.replace(Regex("^\"(.*?)\"\\[(?:#[0-9A-Fa-f]{3,8})](.*)$")) { m ->
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
    var pos = beforeStart
    while (pos > 0) {
        val nlIdx = source.lastIndexOf('\n', pos - 1)
        val lineStart = if (nlIdx < 0) 0 else nlIdx + 1
        val lineEnd = if (nlIdx < 0) pos else nlIdx
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



