@file:Suppress("TooManyFunctions")

package ui

import model.ParsedNote
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


/** Right-to-Left / Left-to-Right markers — strip from PDF (no bidi support). */
private const val RLM = "\u200F"
private const val LRM = "\u200E"


/**
 * Export a note as a paginated PDF.
 *
 * Workflow:
 *  1. Open a native save-file dialog (`java.awt.FileDialog`) and let
 *     the user pick a destination. ".pdf" is appended if missing.
 *  2. Render the note's title + raw markdown body to an A4 PDF using
 *     Apache PDFBox. Latin-1 / WinAnsi characters only — anything
 *     outside that range is replaced with `?` so we never hit an
 *     encoding exception mid-write.
 *  3. Word-wrap to page width and auto-paginate when y < top margin.
 *
 * Returns the destination [Path] on success, or null if the user
 * cancelled the dialog or the write failed.
 */
object NotePdfExporter {

    fun exportAsPdf(note: ParsedNote, sourceContent: String): Path? {
        val defaultName = (note.title.ifBlank { note.fileName.removeSuffix(".note") }) + ".pdf"
        val target = promptForPdfPath(defaultName) ?: return null
        return try {
            writePdf(target, note, sourceContent)
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


    private fun writePdf(target: Path, note: ParsedNote, content: String) {
        PDDocument().use { doc ->
            val font = PDType1Font(Standard14Fonts.FontName.HELVETICA)
            val fontBold = PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD)
            val pageSize = PDRectangle.A4
            val margin = 50f
            val topY = pageSize.height - margin
            val usableWidth = pageSize.width - 2 * margin
            val fontSize = 11f
            val lineHeight = fontSize * 1.4f
            val titleFontSize = 18f

            var page = PDPage(pageSize)
            doc.addPage(page)
            var stream = PDPageContentStream(doc, page)
            var y = topY

            // ----- Title -----
            val title = sanitizePdfText(note.title.ifBlank { note.fileName.removeSuffix(".note") })
            stream.beginText()
            stream.setFont(fontBold, titleFontSize)
            stream.newLineAtOffset(margin, y)
            stream.showText(title)
            stream.endText()
            y -= titleFontSize + 10f

            // ----- Source file name (small gray-ish caption) -----
            val fileNameDisplay = sanitizePdfText(note.fileName)
            stream.beginText()
            stream.setFont(font, 9f)
            stream.newLineAtOffset(margin, y)
            stream.showText(fileNameDisplay)
            stream.endText()
            y -= 18f

            // ----- Horizontal divider -----
            stream.setStrokingColor(180f / 255f, 180f / 255f, 180f / 255f)
            stream.moveTo(margin, y + 6f)
            stream.lineTo(pageSize.width - margin, y + 6f)
            stream.stroke()
            y -= 12f

            // ----- Body lines, paginated and word-wrapped -----
            content.split("\n").forEach { rawLine ->
                val line = sanitizePdfText(rawLine)
                wrapLineForWidth(line, font, fontSize, usableWidth).forEach { wrapped ->
                    if (y < margin) {
                        stream.close()
                        page = PDPage(pageSize)
                        doc.addPage(page)
                        stream = PDPageContentStream(doc, page)
                        y = topY
                    }
                    stream.beginText()
                    stream.setFont(font, fontSize)
                    stream.newLineAtOffset(margin, y)
                    stream.showText(wrapped)
                    stream.endText()
                    y -= lineHeight
                }
            }
            stream.close()
            doc.save(target.toFile())
        }
    }


    /**
     * Strip the editor-only RTL/LTR markers and escape anything that's
     * not safely encodable in PDFBox's WinAnsi-encoded Helvetica. PDF
     * showText() does not honour embedded \n, so we replace newlines
     * with spaces (the line-by-line loop above handles line breaks).
     */
    private fun sanitizePdfText(text: String): String {
        val stripped = text.replace(RLM, "").replace(LRM, "")
        val sb = StringBuilder(stripped.length)
        for (c in stripped) {
            val cp = c.code
            when {
                cp == 0x0A -> sb.append(' ')
                cp in 0x20..0x7E -> sb.append(c)
                cp in 0xA1..0xFF -> sb.append(c)
                else -> sb.append('?')
            }
        }
        return sb.toString()
    }


    /**
     * Wrap a single source line into one or more display lines no
     * wider than `maxWidth` at the given font size. Uses the font's
     * `getStringWidth` measurement (in 1/1000ths of an em) so the
     * result will fit in the PDF without overflow.
     *
     * Splits on spaces; if a single word is wider than the page, it
     * gets character-broken.
     */
    private fun wrapLineForWidth(
        line: String,
        font: PDType1Font,
        fontSize: Float,
        maxWidth: Float
    ): List<String> {
        if (line.isEmpty()) return listOf("")
        val words = line.split(' ')
        val out = mutableListOf<String>()
        var current = StringBuilder()
        for (word in words) {
            val candidate = if (current.isEmpty()) word else current.toString() + " " + word
            val candidateWidth = measuredWidth(candidate, font, fontSize)
            if (candidateWidth > maxWidth && current.isNotEmpty()) {
                out.add(current.toString())
                current = StringBuilder()
                // Word itself too wide? fall back to character-break.
                if (measuredWidth(word, font, fontSize) > maxWidth) {
                    var partial = StringBuilder()
                    for (c in word) {
                        val withC = partial.toString() + c
                        if (measuredWidth(withC, font, fontSize) > maxWidth && partial.isNotEmpty()) {
                            out.add(partial.toString())
                            partial = StringBuilder(c.toString())
                        } else {
                            partial.append(c)
                        }
                    }
                    current = partial
                } else {
                    current.append(word)
                }
            } else {
                current = StringBuilder(candidate)
            }
        }
        if (current.isNotEmpty()) out.add(current.toString())
        return out
    }


    private fun measuredWidth(text: String, font: PDType1Font, fontSize: Float): Float {
        if (text.isEmpty()) return 0f
        return font.getStringWidth(text) / 1000f * fontSize
    }
}
