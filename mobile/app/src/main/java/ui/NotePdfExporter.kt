@file:Suppress("TooManyFunctions")

package ui

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import androidx.compose.ui.graphics.Color
import data.findMediaReferenceTokens
import data.findNoteLinkTokens
import data.findReferenceTokens
import model.ParsedNote
import model.Verse
import platform.saveToDownloads
import java.io.ByteArrayOutputStream
import java.nio.file.Path


/**
 * Export a note as a paginated PDF (Android edition).
 *
 * The markup parser, styled runs, word wrapping and block layout are the
 * same code the desktop build uses, so a note prints exactly like the
 * editor shows it: headings, bold / italic / underline, bullets, quote
 * colours, reference / media / note-link chips in their editor colours.
 * Only the page writer differs: android.graphics.pdf.PdfDocument instead
 * of PDFBox, and the finished file lands in the device's Download/BibleApp
 * folder (no save dialog to navigate on a phone).
 *
 * Returns the destination [Path] on success, or null when the write failed.
 */
private const val PAGE_WIDTH = 595f
private const val PAGE_HEIGHT = 842f


object NotePdfExporter {

    private const val MARGIN = 50f

    fun exportAsPdf(
        note: ParsedNote,
        sourceContent: String,
        /** The editor's `primary` — reference / media chips print in it. */
        primaryColor: Color = colorFromHexInternal(CITE_BLUE_HEX),
        /** The editor's `tertiary` — `[[note links]]` print in it. */
        noteLinkColor: Color = primaryColor
    ): Path? {
        val title = note.title.ifBlank { note.fileName.removeSuffix(".note") }
        val linkColors = PdfLinkColors(
            primary = pdfColorOf(primaryColor),
            tertiary = pdfColorOf(noteLinkColor)
        )
        val bytes = runCatching {
            render(title, note.fileName, sourceContent, linkColors)
        }.getOrNull() ?: return null
        return saveToDownloads("${safeFileName(title)}.pdf", "application/pdf", bytes)
    }

    /**
     * Export a Bible chapter as a paginated PDF: the `Book C` title (with
     * the active translation's display name as a caption when non-null),
     * then each verse as `N. text` on its own line.
     */
    fun exportChapterAsPdf(
        bookName: String,
        chapterNumber: Int,
        verses: List<Verse>,
        translationName: String?
    ): Path? {
        val body = buildString {
            for (verse in verses) {
                append("${verse.verse}. ")
                append(stripWordStudyMarkup(verse.text).trim())
                append('\n')
            }
        }
        val title = "${bookName.trim()} $chapterNumber"
        val bytes = runCatching {
            render(title, translationName.orEmpty(), body, DEFAULT_LINK_COLORS)
        }.getOrNull() ?: return null
        return saveToDownloads(
            "${safeFileName(title)}.pdf",
            "application/pdf",
            bytes
        )
    }

    private fun safeFileName(text: String): String =
        text.trim()
            .replace(Regex("[\\\\/:*?\"<>|]"), "-")
            .ifBlank { "Note" }
            .take(80)


    private fun render(
        title: String,
        subtitle: String,
        content: String,
        linkColors: PdfLinkColors
    ): ByteArray {
        val flow = PdfPageWriter(MARGIN)

        // ----- Title (wrapped like any other block) -----
        val titleBlock = PdfBlock(
            runs = listOf(PdfRun(title, bold = true)),
            fontSize = TITLE_FONT_SIZE,
            lineHeight = TITLE_FONT_SIZE * 1.3f,
            bold = true
        )
        drawBlock(flow, titleBlock, MARGIN)
        flow.gap(6f)

        // ----- Caption (source file name / translation name) -----
        if (subtitle.isNotBlank()) {
            val captionBlock = PdfBlock(
                runs = listOf(PdfRun(subtitle, color = PDF_MUTED)),
                fontSize = 9f,
                lineHeight = 12f
            )
            drawBlock(flow, captionBlock, MARGIN)
            flow.gap(6f)
        }

        // ----- Horizontal divider -----
        flow.drawRule(MARGIN, PAGE_WIDTH - MARGIN, flow.baseline() + 4f, 0.7f)
        flow.gap(12f)

        // ----- Body blocks, paginated and word-wrapped -----
        var paragraphBreak = false
        var previousWasQuote: Boolean? = null
        for (block in buildPdfBlocks(bodyWithoutTitle(content), linkColors)) {
            if (block.blank) {
                paragraphBreak = true
                continue
            }
            val quoteBreak = previousWasQuote != null && previousWasQuote != block.quote
            val spaceBefore = maxOf(
                block.spaceBefore,
                if (quoteBreak) BODY_LINE_HEIGHT else 0f,
                if (paragraphBreak) PARAGRAPH_GAP else 0f
            )
            paragraphBreak = false
            previousWasQuote = block.quote
            flow.gap(spaceBefore)
            drawBlock(flow, block, MARGIN)
            flow.gap(block.spaceAfter)
        }

        return flow.finish()
    }


    /**
     * Draws one parsed block: bullet / number marker on the first line,
     * hanging indent for wrapped lines, and the block's own alignment.
     */
    private fun drawBlock(flow: PdfPageWriter, block: PdfBlock, margin: Float) {
        val size = block.fontSize
        val markerGap = if (block.marker != null) 8f else 0f
        val markerWidth = block.marker
            ?.let { flow.width(it, bold = true, italic = false, size = size) + markerGap }
            ?: 0f
        val left = margin + block.indent + markerWidth
        val available = (flow.contentWidth - block.indent - markerWidth).coerceAtLeast(24f)
        val lines = wrapPieces(piecesOf(block.runs), size, available, flow)

        // An empty run list (e.g. a quote marker with nothing after it)
        // still occupies one line, exactly like the editor shows it.
        if (lines.isEmpty()) {
            flow.ensureSpace(block.lineHeight)
            flow.advance(block.lineHeight)
            return
        }

        lines.forEachIndexed { lineIndex, line ->
            flow.ensureSpace(block.lineHeight)
            val baseline = flow.baseline(size)
            if (lineIndex == 0 && block.marker != null) {
                flow.drawText(
                    text = block.marker,
                    bold = true,
                    italic = false,
                    size = size,
                    x = margin + block.indent,
                    baseline = baseline
                )
            }
            drawPieces(flow, line, block, left, available, baseline)
            flow.advance(block.lineHeight)
        }
    }


    /** Draws one wrapped line of styled pieces, honouring the alignment. */
    private fun drawPieces(
        flow: PdfPageWriter,
        pieces: List<PdfPiece>,
        block: PdfBlock,
        left: Float,
        available: Float,
        baseline: Float
    ) {
        if (pieces.isEmpty()) return
        val size = block.fontSize
        var total = 0f
        pieces.forEachIndexed { index, piece ->
            if (index > 0 && piece.spaceBefore) {
                total += flow.spaceWidth(size, piece.bold, piece.italic)
            }
            total += flow.pieceWidth(piece, size)
        }
        var x = left + when (block.align) {
            PdfAlign.LEFT -> 0f
            PdfAlign.CENTER -> ((available - total) / 2f).coerceAtLeast(0f)
            PdfAlign.RIGHT -> (available - total).coerceAtLeast(0f)
        }
        pieces.forEachIndexed { index, piece ->
            if (index > 0 && piece.spaceBefore) {
                x += flow.spaceWidth(size, piece.bold, piece.italic)
            }
            x += flow.drawText(
                text = piece.text,
                bold = piece.bold,
                italic = piece.italic,
                size = size,
                x = x,
                baseline = baseline,
                color = piece.color,
                underline = piece.underline
            )
        }
    }
}


// ---------------------------------------------------------------------------
// Markup → styled blocks
// ---------------------------------------------------------------------------

private const val TITLE_FONT_SIZE = 20f
private const val BODY_FONT_SIZE = 11f
private const val BODY_LINE_HEIGHT = 15f
private const val LIST_INDENT = 16f
private const val QUOTE_INDENT = 16f

/** Air between two paragraphs, replacing the source's blank line(s). */
private const val PARAGRAPH_GAP = 9f
private const val FOOTER_FONT_SIZE = 8f
private const val FOOTER_BASELINE = 30f

private val PDF_MUTED = PdfColor(0.42f, 0.42f, 0.42f)

/** The editor's link-chip colours (primary for references / media,
 *  tertiary for `[[note links]]`). Defaults match the default accent so
 *  the chapter export and any non-UI caller still get coloured chips. */
private data class PdfLinkColors(val primary: PdfColor, val tertiary: PdfColor)

private val DEFAULT_LINK_COLORS = PdfLinkColors(
    primary = pdfColorOf(colorFromHexInternal(CITE_BLUE_HEX)),
    tertiary = pdfColorOf(colorFromHexInternal(CITE_BLUE_HEX))
)

private enum class PdfAlign { LEFT, CENTER, RIGHT }

private data class PdfColor(val r: Float, val g: Float, val b: Float)

/** One styled span of text: inline emphasis plus an optional colour. */
private data class PdfRun(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val color: PdfColor? = null
)

/** A single word (plus its style) as laid out on a line. */
private data class PdfPiece(
    val text: String,
    val bold: Boolean,
    val italic: Boolean,
    val underline: Boolean,
    val color: PdfColor?,
    val spaceBefore: Boolean
)

/** A paragraph-level unit: its runs plus how the block is laid out. */
private data class PdfBlock(
    val runs: List<PdfRun>,
    val fontSize: Float,
    val lineHeight: Float,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val indent: Float = 0f,
    val marker: String? = null,
    /** True for quote chains and coloured citations (blank-line separated
     *  from normal paragraphs). */
    val quote: Boolean = false,
    val align: PdfAlign = PdfAlign.LEFT,
    val spaceBefore: Float = 0f,
    val spaceAfter: Float = 0f
) {
    val blank: Boolean get() = runs.isEmpty() && marker == null
}


/**
 * The note body without its leading `# Title` heading — that heading is
 * already printed as the document title, so repeating it would duplicate
 * the title on page 1.
 */
private fun bodyWithoutTitle(content: String): String {
    val lines = content.lines()
    val first = lines.indexOfFirst { it.isNotBlank() }
    if (first < 0) return ""
    if (headingLevelOf(stripLeadingMarkers(lines[first])) == 1) {
        return lines.filterIndexed { index, _ -> index != first }.joinToString("\n")
    }
    return content
}


/**
 * Parses the editor's source markup into styled blocks using the same
 * grammar as `NoteVisualTransformation`: headings `#`–`######`, quote
 * chains, `- ` bullets, `n. ` numbered list items, `"text"[#hex]`
 * coloured quotes, plus inline `**bold**`, `*italic*`, `__underline__`
 * and reference / media / note-link chips (coloured like the editor;
 * media tokens print their resolved URL).
 */
private fun buildPdfBlocks(
    content: String,
    linkColors: PdfLinkColors = DEFAULT_LINK_COLORS
): List<PdfBlock> {
    val blocks = mutableListOf<PdfBlock>()
    var numberedCounter = 0

    for (raw in content.split('\n')) {
        val align = when {
            raw.startsWith(ALIGN_CENTER) -> PdfAlign.CENTER
            raw.startsWith(ALIGN_RIGHT) -> PdfAlign.RIGHT
            else -> PdfAlign.LEFT
        }
        var line = stripLeadingMarkers(raw).trimEnd()

        if (line.isBlank()) {
            numberedCounter = 0
            blocks.add(
                PdfBlock(
                    runs = emptyList(),
                    fontSize = BODY_FONT_SIZE,
                    lineHeight = BODY_LINE_HEIGHT
                )
            )
            continue
        }

        // Coloured quote: `"inner"[#hex] trailing` → the inner text in
        // its own colour, then the trailing note as plain text.
        val colored = coloredQuoteRegex.matchEntire(line)
        if (colored != null) {
            numberedCounter = 0
            val inner = colored.groupValues[1]
            val hex = colored.groupValues[2]
            val trailing = colored.groupValues[3].trim()
            // Same fallback as the editor: an unparsable / missing hex
            // uses the theme's primary rather than printing black.
            val runs = mutableListOf(
                PdfRun(
                    inner,
                    italic = true,
                    color = pdfColorFromHex(hex) ?: linkColors.primary
                )
            )
            if (trailing.isNotEmpty()) {
                // Keep exactly the gap the editor shows: one space when
                // the source had whitespace, none when it did not.
                val trailingStart = colored.groups[3]?.range?.first ?: line.length
                val hadGap = trailingStart > 0 && line[trailingStart - 1].isWhitespace()
                if (hadGap) runs.add(PdfRun(" "))
                runs.addAll(readableRuns(trailing, linkColors))
            }
            blocks.add(
                PdfBlock(
                    runs = runs,
                    fontSize = BODY_FONT_SIZE,
                    lineHeight = BODY_LINE_HEIGHT,
                    italic = true,
                    indent = QUOTE_INDENT,
                    quote = true,
                    align = align,
                    spaceBefore = 2f
                )
            )
            continue
        }

        // Headings keep Word's visual hierarchy: bigger, bolder, with a
        // little air above so sections read as sections.
        val headingPrefix = headingPrefixRegex.find(line)?.takeIf { it.range.first == 0 }
        if (headingPrefix != null) {
            numberedCounter = 0
            val level = headingPrefix.value.count { it == '#' }
            val text = line.substring(headingPrefix.range.last + 1).trim()
            val size = when (level) {
                1 -> 20f
                2 -> 16f
                3 -> 14f
                4 -> 12.5f
                5 -> 11.5f
                else -> 11f
            }
            blocks.add(
                PdfBlock(
                    runs = readableRuns(text, linkColors, bold = true),
                    fontSize = size,
                    lineHeight = size * 1.3f,
                    bold = true,
                    align = align,
                    spaceBefore = if (level <= 2) 14f else 9f,
                    spaceAfter = 4f
                )
            )
            continue
        }

        // Quote chains (`>`, `>>`, …) indent the line and italicise it,
        // like the editor's quote style.
        var quoteDepth = 0
        while (line.startsWith(">")) {
            quoteDepth++
            line = line.drop(1)
        }
        if (quoteDepth > 0) line = line.trimStart()

        // Bullets / numbered items: the source marker is hidden and its
        // own glyph written instead — exactly what the editor displays.
        var marker: String? = null
        when {
            line.startsWith("- ") -> {
                marker = "\u2022"
                line = line.removePrefix("- ")
                numberedCounter = 0
            }
            quoteDepth > 0 && line.startsWith(".") -> {
                marker = "\u2022"
                line = line.removePrefix(".").trimStart()
                numberedCounter = 0
            }
            quoteDepth > 0 && line.startsWith("#") -> {
                numberedCounter += 1
                marker = "$numberedCounter."
                line = line.removePrefix("#").trimStart()
            }
            else -> {
                val ordered = orderedListRegex.find(line)?.takeIf { it.range.first == 0 }
                if (ordered != null) {
                    numberedCounter += 1
                    marker = "$numberedCounter."
                    line = line.substring(ordered.range.last + 1)
                }
            }
        }

        blocks.add(
            PdfBlock(
                runs = readableRuns(
                    line.trim(),
                    linkColors,
                    italic = quoteDepth > 0
                ),
                fontSize = BODY_FONT_SIZE,
                lineHeight = BODY_LINE_HEIGHT,
                italic = quoteDepth > 0,
                quote = quoteDepth > 0,
                indent = quoteDepth * QUOTE_INDENT + if (marker != null) LIST_INDENT else 0f,
                marker = marker,
                align = align,
                spaceBefore = if (quoteDepth > 0) 2f else 0f
            )
        )
    }
    return blocks
}


// ---------------------------------------------------------------------------
// Inline markup → runs
// ---------------------------------------------------------------------------

private data class PdfRunStyle(
    val bold: Boolean,
    val italic: Boolean,
    val underline: Boolean,
    val color: PdfColor?
)

/** Splits [text] on inline emphasis markers into styled runs. */
private fun inlineRuns(
    text: String,
    bold: Boolean = false,
    italic: Boolean = false,
    color: PdfColor? = null
): List<PdfRun> {
    val out = mutableListOf<PdfRun>()
    parseInline(text, PdfRunStyle(bold, italic, false, color), out)
    return out.filter { it.text.isNotEmpty() }
}

private fun parseInline(text: String, style: PdfRunStyle, out: MutableList<PdfRun>) {
    val plain = StringBuilder()

    fun flush() {
        if (plain.isEmpty()) return
        out.add(PdfRun(plain.toString(), style.bold, style.italic, style.underline, style.color))
        plain.setLength(0)
    }

    var i = 0
    while (i < text.length) {
        when {
            text.startsWith("**", i) -> {
                val close = text.indexOf("**", i + 2)
                if (close > i + 2) {
                    flush()
                    parseInline(text.substring(i + 2, close), style.copy(bold = true), out)
                    i = close + 2
                } else {
                    plain.append(text[i])
                    i++
                }
            }
            text.startsWith("__", i) -> {
                val close = text.indexOf("__", i + 2)
                if (close > i + 2) {
                    flush()
                    parseInline(text.substring(i + 2, close), style.copy(underline = true), out)
                    i = close + 2
                } else {
                    plain.append(text[i])
                    i++
                }
            }
            text[i] == '*' -> {
                val close = text.indexOf('*', i + 1)
                if (close > i + 1) {
                    flush()
                    parseInline(text.substring(i + 1, close), style.copy(italic = true), out)
                    i = close + 1
                } else {
                    plain.append(text[i])
                    i++
                }
            }
            else -> {
                plain.append(text[i])
                i++
            }
        }
    }
    flush()
}


/** One link token to print: its source span, display text and colour. */
private data class PdfLinkSpan(
    val start: Int,
    val end: Int,
    val text: String,
    val color: PdfColor
)

/**
 * Splits [text] into runs like [inlineRuns], but turns the editor's link
 * tokens into the text the editor shows — `$Book&C&V` → "Book C:V",
 * `[[Title]]` → Title, `@service:id` → its resolved URL — painted with
 * the editor's chip colours (bold primary / tertiary). This keeps `$`,
 * `&`, `[[`, `]]` and `@` off the printed page while keeping the links
 * coloured like they are on screen, and prints the actual media URL so a
 * printed Spotify / YouTube link stays usable.
 */
private fun readableRuns(
    text: String,
    linkColors: PdfLinkColors,
    bold: Boolean = false,
    italic: Boolean = false,
    color: PdfColor? = null
): List<PdfRun> {
    if (text.isEmpty()) return emptyList()
    val tokens = mutableListOf<PdfLinkSpan>()
    findNoteLinkTokens(text).forEach { token ->
        tokens.add(
            PdfLinkSpan(
                token.sourceStart,
                token.sourceEnd,
                token.title,
                linkColors.tertiary
            )
        )
    }
    findMediaReferenceTokens(text).forEach { token ->
        tokens.add(
            PdfLinkSpan(
                token.sourceStart,
                token.sourceEnd,
                token.pdfLinkText(),
                linkColors.primary
            )
        )
    }
    findReferenceTokens(text).forEach { token ->
        tokens.add(
            PdfLinkSpan(
                token.sourceStart,
                token.sourceEnd,
                token.pdfText(),
                linkColors.primary
            )
        )
    }
    tokens.sortBy { it.start }

    val out = mutableListOf<PdfRun>()
    var cursor = 0
    for (token in tokens) {
        if (token.start < cursor) continue
        if (token.start > cursor) {
            out.addAll(inlineRuns(text.substring(cursor, token.start), bold, italic, color))
        }
        out.add(
            PdfRun(
                text = token.text,
                // Link chips are bold on screen, so they print bold too.
                bold = true,
                italic = italic,
                color = token.color
            )
        )
        cursor = token.end
    }
    if (cursor < text.length) {
        out.addAll(inlineRuns(text.substring(cursor), bold, italic, color))
    }
    return out
}


/**
 * The text printed for a media token: its resolved, openable URL (so a
 * `@spotify:track:…` reference becomes a real Spotify link on paper),
 * falling back to the editor's chip label when the token has no URL.
 */
private fun data.MediaReferenceToken.pdfLinkText(): String =
    resolveUrl() ?: chipText()

/** `Book C:V` (plus a resolved `-E` / `-C2:E` range) without markers. */
private fun data.ReferenceToken.pdfText(): String = buildString {
    append(book)
    if (chapter != null) {
        append(' ')
        append(chapter)
    }
    if (verse != null) {
        append(':')
        append(verse)
        if (endVerse != null) {
            append('-')
            if (endChapter != null && endChapter != chapter) {
                append(endChapter)
                append(':')
            }
            append(endVerse)
        }
    }
}


/** Compose colour → PDF colour (alpha dropped). */
private fun pdfColorOf(color: Color): PdfColor =
    PdfColor(color.red, color.green, color.blue)


/** `[#hex]` marker → PDF colour (alpha dropped; null when unparsable).
 *  Accepts the same 6/8-digit forms the editor's colour parser does, so
 *  the page can never disagree with the screen about a quote's colour. */
private fun pdfColorFromHex(hex: String): PdfColor? {
    val cleaned = hex.removePrefix("#")
    if (cleaned.length !in listOf(6, 8)) return null
    return pdfColorOf(colorFromHexInternal(hex))
}


// ---------------------------------------------------------------------------
// Wrapping / measuring
// ---------------------------------------------------------------------------

/** Splits styled runs into words, remembering which words had a space. */
private fun piecesOf(runs: List<PdfRun>): List<PdfPiece> {
    val pieces = mutableListOf<PdfPiece>()
    val word = StringBuilder()
    var wordStyle: PdfRun? = null
    var pendingSpace = false

    fun flushWord() {
        val style = wordStyle
        if (style == null || word.isEmpty()) return
        pieces.add(
            PdfPiece(
                text = word.toString(),
                bold = style.bold,
                italic = style.italic,
                underline = style.underline,
                color = style.color,
                spaceBefore = pendingSpace && pieces.isNotEmpty()
            )
        )
        word.setLength(0)
        pendingSpace = false
    }

    for (run in runs) {
        for (c in run.text) {
            if (c == ' ' || c == '\t' || c == '\n') {
                flushWord()
                if (pieces.isNotEmpty()) pendingSpace = true
            } else {
                if (word.isEmpty()) wordStyle = run
                word.append(c)
            }
        }
    }
    flushWord()
    return pieces
}


/**
 * Greedy word wrap at [size] inside [maxWidth]; a word that cannot fit
 * on a line by itself is character-broken so nothing overflows the page.
 */
private fun wrapPieces(
    pieces: List<PdfPiece>,
    size: Float,
    maxWidth: Float,
    flow: PdfPageWriter
): List<List<PdfPiece>> {
    if (pieces.isEmpty()) return emptyList()
    val lines = mutableListOf<MutableList<PdfPiece>>()
    var current = mutableListOf<PdfPiece>()
    var currentWidth = 0f

    fun flush() {
        if (current.isEmpty()) return
        lines.add(current)
        current = mutableListOf()
        currentWidth = 0f
    }

    for (piece in pieces) {
        val wordWidth = flow.pieceWidth(piece, size)
        if (wordWidth > maxWidth) {
            flush()
            var chunk = StringBuilder()
            for (c in piece.text) {
                val candidate = chunk.toString() + c
                if (chunk.isNotEmpty() &&
                    flow.pieceWidth(piece.copy(text = candidate, spaceBefore = false), size) > maxWidth
                ) {
                    lines.add(mutableListOf(piece.copy(text = chunk.toString(), spaceBefore = false)))
                    chunk = StringBuilder(c.toString())
                } else {
                    chunk.append(c)
                }
            }
            if (chunk.isNotEmpty()) {
                val tail = piece.copy(text = chunk.toString(), spaceBefore = false)
                current = mutableListOf(tail)
                currentWidth = flow.pieceWidth(tail, size)
            }
            continue
        }

        val separator = if (current.isNotEmpty() && piece.spaceBefore) {
            flow.spaceWidth(size, piece.bold, piece.italic)
        } else 0f
        if (current.isNotEmpty() && currentWidth + separator + wordWidth > maxWidth) flush()

        val sep = if (current.isNotEmpty() && piece.spaceBefore) {
            flow.spaceWidth(size, piece.bold, piece.italic)
        } else 0f
        current.add(piece)
        currentWidth += sep + wordWidth
    }
    flush()
    return lines
}




// ---------------------------------------------------------------------------
// Page writer (android.graphics.pdf)
// ---------------------------------------------------------------------------

/**
 * Position-keeper around a PdfDocument canvas: tracks the vertical cursor,
 * starts a new page when it runs out of room, and draws text with the
 * right font / colour / underline. Same API as the desktop PDFBox writer,
 * so the block layout above is shared verbatim.
 */
private class PdfPageWriter(private val margin: Float) {

    private val doc = PdfDocument()
    private val pages = mutableListOf<PdfDocument.Page>()
    private var page: PdfDocument.Page = startPage()
    private var y: Float = PAGE_HEIGHT - margin
    private val paints = mutableMapOf<Triple<Boolean, Boolean, Int>, Paint>()

    val contentWidth: Float get() = PAGE_WIDTH - 2 * margin

    private fun startPage(): PdfDocument.Page {
        val info = PdfDocument.PageInfo.Builder(
            PAGE_WIDTH.toInt(),
            PAGE_HEIGHT.toInt(),
            pages.size + 1
        ).create()
        return doc.startPage(info)
    }

    private fun newPage() {
        pages.add(page)
        page = startPage()
        y = PAGE_HEIGHT - margin
    }

    private fun paint(bold: Boolean, italic: Boolean, color: PdfColor): Paint =
        paints.getOrPut(
            Triple(bold, italic, android.graphics.Color.rgb(
                (color.r * 255).toInt().coerceIn(0, 255),
                (color.g * 255).toInt().coerceIn(0, 255),
                (color.b * 255).toInt().coerceIn(0, 255)
            ))
        ) {
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = Typeface.create(
                    Typeface.SERIF,
                    when {
                        bold && italic -> Typeface.BOLD_ITALIC
                        bold -> Typeface.BOLD
                        italic -> Typeface.ITALIC
                        else -> Typeface.NORMAL
                    }
                )
                this.color = android.graphics.Color.rgb(
                    (color.r * 255).toInt().coerceIn(0, 255),
                    (color.g * 255).toInt().coerceIn(0, 255),
                    (color.b * 255).toInt().coerceIn(0, 255)
                )
            }
        }

    fun width(text: String, bold: Boolean, italic: Boolean, size: Float): Float {
        val safe = sanitizePdfText(text)
        if (safe.isEmpty()) return 0f
        return paint(bold, italic, PdfColor(0f, 0f, 0f)).apply {
            textSize = size
        }.measureText(safe)
    }

    fun pieceWidth(piece: PdfPiece, size: Float): Float =
        width(piece.text, piece.bold, piece.italic, size)

    fun spaceWidth(size: Float, bold: Boolean, italic: Boolean): Float =
        width(" ", bold, italic, size)

    fun ensureSpace(height: Float) {
        if (y - height >= margin) return
        newPage()
    }

    fun gap(height: Float) {
        if (height <= 0f) return
        ensureSpace(height)
        y -= height
    }

    /** Baseline (text origin) of a line of [size] type at the cursor. */
    fun baseline(size: Float = BODY_FONT_SIZE): Float = y - size

    fun advance(height: Float) {
        y -= height
    }

    /** Writes [text] with its baseline at [baseline]; returns its width. */
    fun drawText(
        text: String,
        bold: Boolean,
        italic: Boolean,
        size: Float,
        x: Float,
        baseline: Float,
        color: PdfColor? = null,
        underline: Boolean = false
    ): Float {
        val safe = sanitizePdfText(text)
        if (safe.isEmpty()) return 0f
        val p = paint(bold, italic, color ?: PdfColor(0f, 0f, 0f))
        p.textSize = size
        page.canvas.drawText(safe, x, baseline, p)
        val advance = p.measureText(safe)
        if (underline) {
            val thickness = (size * 0.06f).coerceAtLeast(0.5f)
            page.canvas.drawRect(
                x,
                baseline - size * 0.14f,
                x + advance,
                baseline - size * 0.14f + thickness,
                p
            )
        }
        return advance
    }

    fun drawRule(x1: Float, x2: Float, yy: Float, gray: Float) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.rgb(
                (gray * 255).toInt(),
                (gray * 255).toInt(),
                (gray * 255).toInt()
            )
            strokeWidth = 0.8f
        }
        page.canvas.drawLine(x1, yy, x2, yy, p)
    }

    /** Stamps `Page N of M` on every page, then returns the PDF bytes. */
    fun finish(): ByteArray {
        pages.add(page)
        val total = pages.size
        val footerZ = FOOTER_BASELINE
        pages.forEachIndexed { index, finished ->
            val label = "Page ${index + 1} of $total"
            val p = paint(false, false, PDF_MUTED).apply { textSize = FOOTER_FONT_SIZE }
            val width = p.measureText(label)
            finished.canvas.drawText(
                label,
                (PAGE_WIDTH - width) / 2f,
                PAGE_HEIGHT - footerZ,
                p
            )
        }
        pages.forEach { doc.finishPage(it) }
        return ByteArrayOutputStream().use { out ->
            doc.writeTo(out)
            doc.close()
            out.toByteArray()
        }
    }
}


/**
 * Drops the editor-only markers (RLM / LRM / alignment). Unlike the
 * desktop build nothing has to be transliterated to WinAnsi — Android
 * renders the full Unicode range with the system serif font.
 */
private fun sanitizePdfText(text: String): String =
    text.replace(RLM, "")
        .replace(LRM, "")
        .replace(ALIGN_CENTER, "")
        .replace(ALIGN_RIGHT, "")
        .replace('\n', ' ')
        .replace('\r', ' ')
        .replace('\t', ' ')
