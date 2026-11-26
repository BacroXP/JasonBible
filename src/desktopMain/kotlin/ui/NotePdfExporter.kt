@file:Suppress("TooManyFunctions")

package ui

import androidx.compose.ui.graphics.Color
import data.findMediaReferenceTokens
import data.findNoteLinkTokens
import data.findReferenceTokens
import model.ParsedNote
import model.Verse
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import java.awt.FileDialog
import java.awt.Frame
import java.nio.file.Path
import java.nio.file.Paths


/**
 * Export a note as a paginated PDF.
 *
 * Workflow:
 *  1. Open a native save-file dialog (`java.awt.FileDialog`) and let
 *     the user pick a destination. ".pdf" is appended if missing.
 *  2. Render the note's title + body to an A4 PDF using Apache PDFBox.
 *     The body is parsed with the SAME markup grammar the editor's
 *     visual transformation uses, so the page looks like the editor:
 *     `#`/`###` become real headings, `**bold**` / `*italic*` /
  * `__underline__` become styled runs, `- ` becomes a `•` bullet,
 *     quoted and coloured lines keep their look, and the editor-only
 *     markers (`#`, `*`, `$`, `>`, alignment control chars) never reach
 *     the page. Reference / media chips keep the editor's chip colour
 *     and media links print as their real URL. PDFBox's standard fonts are WinAnsi-encoded, so any
 *     character outside WinAnsi degrades to `?` instead of failing the
 *     export.
 *  3. Word-wrap to page width and auto-paginate when the page fills up.
 *     Paragraphs are separated by a fixed block gap (runs of blank lines
 *     collapse into it) and every page carries a centred `Page N of M`
 *     footer, so the printed note reads like a Word document.
 *
 * Returns the destination [Path] on success, or null if the user
 * cancelled the dialog or the write failed.
 */
object NotePdfExporter {

    fun exportAsPdf(
        note: ParsedNote,
        sourceContent: String,
        /** The editor's `primary` — reference / media chips print in it. */
        primaryColor: Color = colorFromHexInternal(CITE_BLUE_HEX),
        /** The editor's `tertiary` — `[[note links]]` print in it. */
        noteLinkColor: Color = primaryColor
    ): Path? {
        val defaultName = (note.title.ifBlank { note.fileName.removeSuffix(".note") }) + ".pdf"
        val target = promptForPdfPath(defaultName) ?: return null
        return try {
            writePdf(
                target = target,
                title = note.title.ifBlank { note.fileName.removeSuffix(".note") },
                subtitle = note.fileName,
                content = sourceContent,
                linkColors = PdfLinkColors(
                    primary = pdfColorOf(primaryColor),
                    tertiary = pdfColorOf(noteLinkColor)
                )
            )
            target
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * Export a Bible chapter as a paginated PDF: the `Book C` title (with
     * the active translation's display name as a caption when non-null),
     * then each verse as `N. text` on its own line. Reuses the same
     * save-dialog, pagination, wrapping and font pipeline as the note
     * export (the numbered lines come out as a real numbered list).
     */
    fun exportChapterAsPdf(
        bookName: String,
        chapterNumber: Int,
        verses: List<Verse>,
        translationName: String?
    ): Path? {
        val safeBook = bookName.trim()
            .replace(Regex("[\\\\/:*?\"<>|]"), "-")
            .ifBlank { "Bible" }
        val defaultName = "$safeBook $chapterNumber.pdf"
        val target = promptForPdfPath(defaultName) ?: return null
        return try {
            val body = buildString {
                for (verse in verses) {
                    // Strip Strong's markup so the exported chapter reads
                    // as clean prose (same rule as the copy actions).
                    append("${verse.verse}. ")
                    append(stripWordStudyMarkup(verse.text).trim())
                    append('\n')
                }
            }
            writePdf(
                target = target,
                title = "${bookName.trim()} $chapterNumber",
                subtitle = translationName.orEmpty(),
                content = body
            )
            target
        } catch (t: Throwable) {
            null
        }
    }


    private fun promptForPdfPath(defaultName: String): Path? = runCatching {
        val dialog = FileDialog(null as Frame?, "Save note as PDF", FileDialog.SAVE)
        dialog.file = defaultName
        @Suppress("DEPRECATION")
        dialog.isVisible = true
        val dir = dialog.directory ?: return@runCatching null
        val file = dialog.file ?: return@runCatching null
        val candidate = Paths.get(dir, file)
        if (candidate.toString().endsWith(".pdf", ignoreCase = true)) candidate
        else Paths.get(dir, "$file.pdf")
    }.getOrNull()


    private fun writePdf(
        target: Path,
        title: String,
        subtitle: String,
        content: String,
        linkColors: PdfLinkColors = DEFAULT_LINK_COLORS
    ) {
        PDDocument().use { doc ->
            val pageSize = PDRectangle.A4
            val margin = 50f
            val flow = PdfPageWriter(doc, pageSize, margin)

            // ----- Title (wrapped like any other block) -----
            val titleBlock = PdfBlock(
                runs = listOf(PdfRun(title, bold = true)),
                fontSize = TITLE_FONT_SIZE,
                lineHeight = TITLE_FONT_SIZE * 1.3f,
                bold = true
            )
            drawBlock(flow, titleBlock, margin)
            flow.gap(6f)

            // ----- Caption (source file name / translation name) -----
            if (subtitle.isNotBlank()) {
                val captionBlock = PdfBlock(
                    runs = listOf(PdfRun(subtitle, color = PDF_MUTED)),
                    fontSize = 9f,
                    lineHeight = 12f
                )
                drawBlock(flow, captionBlock, margin)
                flow.gap(6f)
            }

            // ----- Horizontal divider -----
            flow.drawRule(margin, pageSize.width - margin, flow.baseline() + 4f, 0.7f)
            flow.gap(12f)

            // ----- Body blocks, paginated and word-wrapped -----
            // A blank source line is a paragraph break, not an empty line:
            // runs of them collapse into one fixed gap, and a block that
            // already asks for space above (a heading) never gets it twice.
            var paragraphBreak = false
            // Quotes / citations get a whole blank line of separation from
            // the surrounding normal text (and vice versa), so a cited
            // verse never runs straight into the paragraph below it.
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
                drawBlock(flow, block, margin)
                flow.gap(block.spaceAfter)
            }

            flow.close()
            drawPageNumbers(doc, pageSize)
            doc.save(target.toFile())
        }
    }


    /**
     * Stamps `Page N of M`, centred under the text block, on every page.
     * Run after all pages exist so the total is known; the footer sits
     * below the bottom margin, clear of the last line of body text.
     */
    private fun drawPageNumbers(doc: PDDocument, pageSize: PDRectangle) {
        val total = doc.numberOfPages
        val font = PDType1Font(Standard14Fonts.FontName.HELVETICA)
        for (index in 0 until total) {
            val label = "Page ${index + 1} of $total"
            val width = font.getStringWidth(label) / 1000f * FOOTER_FONT_SIZE
            PDPageContentStream(
                doc,
                doc.getPage(index),
                PDPageContentStream.AppendMode.APPEND,
                true,
                true
            ).use { footer ->
                footer.beginText()
                footer.setNonStrokingColor(0.42f, 0.42f, 0.42f)
                footer.setFont(font, FOOTER_FONT_SIZE)
                footer.newLineAtOffset((pageSize.width - width) / 2f, FOOTER_BASELINE)
                footer.showText(label)
                footer.endText()
            }
        }
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
// Page writer
// ---------------------------------------------------------------------------

/**
 * Thin position-keeper around a PDFBox content stream: tracks the
 * current vertical cursor, starts a new page when it runs out of room,
 * and writes text with the right font / colour / underline.
 */
private class PdfPageWriter(
    private val doc: PDDocument,
    private val pageSize: PDRectangle,
    private val margin: Float
) {
    private var page: PDPage = PDPage(pageSize).also { doc.addPage(it) }
    private var stream: PDPageContentStream = PDPageContentStream(doc, page)
    private var y: Float = pageSize.height - margin
    private val fonts = mutableMapOf<Pair<Boolean, Boolean>, PDType1Font>()

    val contentWidth: Float get() = pageSize.width - 2 * margin

    private fun font(bold: Boolean, italic: Boolean): PDType1Font =
        fonts.getOrPut(bold to italic) {
            PDType1Font(
                when {
                    bold && italic -> Standard14Fonts.FontName.HELVETICA_BOLD_OBLIQUE
                    bold -> Standard14Fonts.FontName.HELVETICA_BOLD
                    italic -> Standard14Fonts.FontName.HELVETICA_OBLIQUE
                    else -> Standard14Fonts.FontName.HELVETICA
                }
            )
        }

    fun width(text: String, bold: Boolean, italic: Boolean, size: Float): Float {
        val safe = sanitizePdfText(text)
        if (safe.isEmpty()) return 0f
        return font(bold, italic).getStringWidth(safe) / 1000f * size
    }

    fun pieceWidth(piece: PdfPiece, size: Float): Float =
        width(piece.text, piece.bold, piece.italic, size)

    fun spaceWidth(size: Float, bold: Boolean, italic: Boolean): Float =
        width(" ", bold, italic, size)

    fun ensureSpace(height: Float) {
        if (y - height >= margin) return
        newPage()
    }

    private fun newPage() {
        stream.close()
        page = PDPage(pageSize)
        doc.addPage(page)
        stream = PDPageContentStream(doc, page)
        y = pageSize.height - margin
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
        val font = font(bold, italic)
        val fill = color ?: PdfColor(0f, 0f, 0f)
        stream.setNonStrokingColor(fill.r, fill.g, fill.b)
        stream.beginText()
        stream.setFont(font, size)
        stream.newLineAtOffset(x, baseline)
        stream.showText(safe)
        stream.endText()
        val advance = font.getStringWidth(safe) / 1000f * size
        if (underline) {
            stream.addRect(x, baseline - size * 0.14f, advance, (size * 0.06f).coerceAtLeast(0.5f))
            stream.fill()
        }
        return advance
    }

    fun drawRule(x1: Float, x2: Float, yy: Float, gray: Float) {
        stream.setStrokingColor(gray, gray, gray)
        stream.moveTo(x1, yy)
        stream.lineTo(x2, yy)
        stream.stroke()
    }

    fun close() {
        stream.close()
    }
}


/**
 * Drops the editor-only markers (RLM / LRM / alignment) and escapes
 * anything PDFBox's WinAnsi-encoded standard fonts cannot encode.
 */
private fun sanitizePdfText(text: String): String {
    val stripped = text
        .replace(RLM, "")
        .replace(LRM, "")
        .replace(ALIGN_CENTER, "")
        .replace(ALIGN_RIGHT, "")
    val sb = StringBuilder(stripped.length)
    for (c in stripped) {
        when {
            c == '\n' || c == '\r' || c == '\t' -> sb.append(' ')
            c.code in 0x20..0x7E -> sb.append(c)
            c.code in 0xA0..0xFF -> sb.append(c)
            c in WIN_ANSI_EXTRA -> sb.append(c)
            else -> sb.append('?')
        }
    }
    return sb.toString()
}

/**
 * Typographic characters outside Latin-1 that the editor itself writes
 * (bullet, dashes, curly quotes) and that WinAnsi can still encode — so
 * a formatted note keeps its bullets and quotes in the PDF.
 */
private val WIN_ANSI_EXTRA = setOf(
    '\u20AC', '\u201A', '\u0192', '\u201E', '\u2026', '\u2020', '\u2021',
    '\u02C6', '\u2030', '\u0160', '\u2039', '\u0152', '\u017D', '\u2018',
    '\u2019', '\u201C', '\u201D', '\u2022', '\u2013', '\u2014', '\u02DC',
    '\u2122', '\u0161', '\u203A', '\u0153', '\u017E', '\u0178'
)
