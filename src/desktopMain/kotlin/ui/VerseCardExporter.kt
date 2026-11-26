package ui

import data.BibleCatalog
import data.SettingsManager
import java.awt.BasicStroke
import java.awt.Color
import java.awt.FileDialog
import java.awt.Font
import java.awt.Frame
import java.awt.GradientPaint
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO


/**
 * One verse as it appears on an exported card. Keeps its chapter number so
 * cross-chapter ranges still print the right reference.
 */
internal data class CardVerse(
    val chapter: Int,
    val verse: Int,
    val text: String
)


/** The four stylish card designs plus a restrained plain one. */
internal enum class VerseCardStyle(val label: String, val blurb: String) {
    PLAIN("Plain", "White card, quiet serif type"),
    CLASSIC("Classic", "Ivory paper, double rule, letterpress"),
    MIDNIGHT("Midnight", "Deep navy gradient with gold"),
    HORIZON("Horizon", "Warm sunset gradient, light type"),
    SCRIPTURE("Scripture", "Cream card, olive frame and cross")
}


/**
 * Everything needed to draw / export a verse card: the book, the verses
 * (already filtered to the chosen range) and the translation name shown
 * in the footer.
 */
internal data class VerseCardSpec(
    val bookName: String,
    val verses: List<CardVerse>,
    val translationName: String? = null,
    val style: VerseCardStyle = VerseCardStyle.PLAIN
) {
    /** A postcard is one image, so it holds one or two verses at most. */
    val supportsPostcard: Boolean
        get() = verses.size in 1..2

    /** `John 3:16`, `John 3:16-18` or `Luke 7:1-8:1`. */
    val referenceLabel: String
        get() {
            val first = verses.firstOrNull() ?: return bookName.trim()
            val last = verses.lastOrNull() ?: first
            return buildString {
                append(bookName.trim())
                append(' ')
                append(first.chapter)
                append(':')
                append(first.verse)
                if (last.chapter != first.chapter || last.verse != first.verse) {
                    append('-')
                    if (last.chapter != first.chapter) {
                        append(last.chapter)
                        append(':')
                    }
                    append(last.verse)
                }
            }
        }
}


/**
 * Renders scripture as shareable cards: a postcard PNG and a PowerPoint
 * deck (one slide per verse). Both come from the same Java2D drawing so
 * the slide and the postcard look identical.
 *
 * The PowerPoint writer builds a minimal, standards-compliant OOXML
 * package with `java.util.zip` — no extra dependency — embedding each
 * rendered card as a full-slide picture. That keeps the deck opening
 * cleanly in PowerPoint, Keynote and LibreOffice while the card's fonts
 * and ornaments stay exactly as designed.
 */
internal object VerseCardExporter {

    private const val POSTCARD_WIDTH = 1748
    private const val POSTCARD_HEIGHT = 1240

    // 16:9 slide canvas (10 in x 5.625 in at 96 dpi ≈ 960 x 540 pt).
    private const val SLIDE_WIDTH_EMU = 12192000L
    private const val SLIDE_HEIGHT_EMU = 6858000L
    private const val SLIDE_IMAGE_WIDTH = 1920
    private const val SLIDE_IMAGE_HEIGHT = 1080


    /** Renders the whole range as one postcard image. */
    fun renderCard(spec: VerseCardSpec): BufferedImage =
        render(spec, POSTCARD_WIDTH, POSTCARD_HEIGHT)


    /**
     * Renders one image per verse (for the deck). A single-verse card is
     * rendered once; a range becomes one slide per verse so each verse
     * gets a readable card of its own.
     */
    fun renderSlides(spec: VerseCardSpec): List<BufferedImage> {
        val split = spec.verses.map { verse ->
            spec.copy(verses = listOf(verse))
        }
        return split.map { render(it, SLIDE_IMAGE_WIDTH, SLIDE_IMAGE_HEIGHT) }
    }


    /** Asks where to save and writes a PNG postcard. */
    fun exportPostcard(spec: VerseCardSpec): Path? {
        if (spec.verses.isEmpty()) return null
        val defaultName = "${fileStem(spec)}.png"
        val target = promptForPath("Save verse postcard", defaultName, "png") ?: return null
        return runCatching {
            ImageIO.write(renderCard(spec), "png", target.toFile())
            target
        }.getOrNull()
    }


    /** Asks where to save and writes a .pptx deck. */
    fun exportPowerPoint(spec: VerseCardSpec): Path? {
        if (spec.verses.isEmpty()) return null
        val defaultName = "${fileStem(spec)}.pptx"
        val target = promptForPath("Export verses as PowerPoint", defaultName, "pptx")
            ?: return null
        return runCatching {
            val images = renderSlides(spec).map { image ->
                ByteArrayOutputStream().use { out ->
                    ImageIO.write(image, "png", out)
                    out.toByteArray()
                }
            }
            writePptx(target, images)
            target
        }.getOrNull()
    }


    /** Display name of the active translation, for the card footer. */
    fun activeTranslationName(): String? =
        BibleCatalog.entryFor(SettingsManager.translation)?.displayName


    private fun fileStem(spec: VerseCardSpec): String =
        spec.referenceLabel
            .replace(Regex("""[\\/:*?"<>|]"""), "-")
            .replace(' ', '_')
            .ifBlank { "verse" }


    private fun promptForPath(title: String, defaultName: String, ext: String): Path? =
        runCatching {
            val dialog = FileDialog(null as Frame?, title, FileDialog.SAVE)
            dialog.file = defaultName
            @Suppress("DEPRECATION")
            dialog.isVisible = true
            val dir = dialog.directory ?: return@runCatching null
            val file = dialog.file ?: return@runCatching null
            val candidate = Paths.get(dir, file)
            if (candidate.toString().endsWith(".$ext", ignoreCase = true)) {
                candidate
            } else {
                Paths.get(dir, "$file.$ext")
            }
        }.getOrNull()


    // ---------------------------------------------------------------------
    // Card rendering
    // ---------------------------------------------------------------------

    private fun render(spec: VerseCardSpec, width: Int, height: Int): BufferedImage {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        try {
            g.setRenderingHint(
                RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON
            )
            g.setRenderingHint(
                RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON
            )
            g.setRenderingHint(
                RenderingHints.KEY_RENDERING,
                RenderingHints.VALUE_RENDER_QUALITY
            )
            when (spec.style) {
                VerseCardStyle.PLAIN -> drawPlain(g, spec, width, height)
                VerseCardStyle.CLASSIC -> drawClassic(g, spec, width, height)
                VerseCardStyle.MIDNIGHT -> drawMidnight(g, spec, width, height)
                VerseCardStyle.HORIZON -> drawHorizon(g, spec, width, height)
                VerseCardStyle.SCRIPTURE -> drawScripture(g, spec, width, height)
            }
        } finally {
            g.dispose()
        }
        return image
    }


    private fun drawPlain(g: Graphics2D, spec: VerseCardSpec, w: Int, h: Int) {
        val ink = Color(0x1D1D1F)
        val accent = Color(0x8A8A8E)
        g.color = Color.WHITE
        g.fillRect(0, 0, w, h)
        g.color = Color(0xE6E6E8)
        g.stroke = BasicStroke(3f)
        g.drawRect(28, 28, w - 57, h - 57)

        val margin = (w * 0.105f).toInt()
        var y = drawEyebrow(g, spec.referenceLabel, accent, margin, (h * 0.14f).toInt(), 30f)
        y += (h * 0.035f).toInt()
        g.color = Color(0xFFC107)
        g.fillRect(margin, y, 64, 6)
        y += (h * 0.075f).toInt()
        y = drawVerses(g, spec, serifFont(Font.PLAIN), ink, margin, y, w - margin * 2, h, 1.0f, false)
        drawFooterCentered(g, spec, accent, w / 2, h - (h * 0.07f).toInt(), 26f)
    }


    private fun drawClassic(g: Graphics2D, spec: VerseCardSpec, w: Int, h: Int) {
        val paper = Color(0xF7F1E3)
        val ink = Color(0x2C2418)
        val rule = Color(0xB08A4F)
        g.color = paper
        g.fillRect(0, 0, w, h)

        // Double letterpress rule.
        g.color = rule
        g.stroke = BasicStroke(5f)
        g.drawRect(46, 46, w - 93, h - 93)
        g.stroke = BasicStroke(1.6f)
        g.drawRect(64, 64, w - 129, h - 129)

        val margin = (w * 0.12f).toInt()
        val center = w / 2
        drawCentered(g, "❦", serifFont(Font.PLAIN), rule, center, (h * 0.145f).toInt(), 42f)
        var y = drawEyebrowCentered(
            g, spec.referenceLabel, rule, center, (h * 0.215f).toInt(), 28f
        )
        y += (h * 0.06f).toInt()
        y = drawVerses(
            g, spec, serifFont(Font.ITALIC), ink, margin, y, w - margin * 2, h, 1.0f, true
        )
        drawFooterCentered(g, spec, withAlpha(rule, 200), center, h - (h * 0.155f).toInt(), 24f)
    }


    private fun drawMidnight(g: Graphics2D, spec: VerseCardSpec, w: Int, h: Int) {
        val dark = Color(0x0A1A30)
        val light = Color(0x1A3255)
        val gold = Color(0xD9B96A)
        val cream = Color(0xF6EBD0)
        g.paint = GradientPaint(0f, 0f, dark, w.toFloat(), h.toFloat(), light)
        g.fillRect(0, 0, w, h)

        g.color = withAlpha(gold, 26)
        g.fillOval(-w / 5, -h / 3, w, h)
        g.color = gold
        g.stroke = BasicStroke(3f)
        g.drawRect(52, 52, w - 105, h - 105)
        g.stroke = BasicStroke(1f)
        g.drawRect(70, 70, w - 141, h - 141)

        val margin = (w * 0.12f).toInt()
        var y = drawEyebrow(g, spec.referenceLabel, gold, margin, (h * 0.16f).toInt(), 29f)
        y += (h * 0.05f).toInt()
        g.color = gold
        g.fillRect(margin, y, 72, 4)
        y += (h * 0.075f).toInt()
        y = drawVerses(
            g, spec, serifFont(Font.PLAIN), cream, margin, y, w - margin * 2, h, 1.0f, true
        )
        drawFooterCentered(g, spec, withAlpha(gold, 220), w / 2, h - (h * 0.075f).toInt(), 25f)
    }


    private fun drawHorizon(g: Graphics2D, spec: VerseCardSpec, w: Int, h: Int) {
        val dusk = Color(0x2E1A3B)
        val ember = Color(0xC2542F)
        val warm = Color(0xFDF3E7)
        val pale = Color(0xF0C6A0)
        g.paint = GradientPaint(0f, h.toFloat(), dusk, w.toFloat(), 0f, ember)
        g.fillRect(0, 0, w, h)

        // Sun disc sinking at the bottom edge.
        g.color = Color(255, 214, 160, 70)
        g.fillOval(w / 2 - w / 4, h - h / 3, w / 2, h / 2)
        g.color = Color(255, 245, 235, 90)
        g.fillOval(w / 2 - w / 6, h - h / 4, w / 3, h / 3)

        val margin = (w * 0.115f).toInt()
        var y = drawEyebrow(g, spec.referenceLabel, pale, margin, (h * 0.16f).toInt(), 29f)
        y += (h * 0.05f).toInt()
        g.color = pale
        g.fillRect(margin, y, 80, 5)
        y += (h * 0.078f).toInt()
        y = drawVerses(g, spec, serifFont(Font.PLAIN), warm, margin, y, w - margin * 2, h, 1.0f, true)
        drawFooterCentered(g, spec, pale, w / 2, h - (h * 0.075f).toInt(), 25f)
    }


    private fun drawScripture(g: Graphics2D, spec: VerseCardSpec, w: Int, h: Int) {
        val cream = Color(0xFBFAF4)
        val olive = Color(0x6B7A4F)
        val ink = Color(0x2F3526)
        g.color = cream
        g.fillRect(0, 0, w, h)

        g.color = olive
        g.stroke = BasicStroke(6f)
        g.drawRect(40, 40, w - 81, h - 81)
        g.stroke = BasicStroke(1.2f)
        g.drawRect(58, 58, w - 117, h - 117)

        val center = w / 2
        // Simple cross mark at the top.
        g.stroke = BasicStroke(6f)
        val crossX = center - 40
        val crossY = (h * 0.075f).toInt()
        g.drawLine(crossX, crossY, crossX + 80, crossY)
        g.drawLine(center, crossY - 46, center, crossY + 60)

        val margin = (w * 0.13f).toInt()
        var y = drawEyebrowCentered(
            g, spec.referenceLabel, olive, center, (h * 0.21f).toInt(), 27f
        )
        y += (h * 0.055f).toInt()
        y = drawVerses(
            g, spec, serifFont(Font.PLAIN), ink, margin, y, w - margin * 2, h, 1.0f, false
        )
        drawFooterCentered(g, spec, withAlpha(olive, 210), center, h - (h * 0.15f).toInt(), 23f)
    }


    /** Small uppercase reference line, left-aligned; returns the next Y. */
    private fun drawEyebrow(
        g: Graphics2D,
        text: String,
        color: Color,
        x: Int,
        y: Int,
        size: Float
    ): Int {
        val font = sansFont(Font.BOLD).deriveFont(size)
        g.font = font
        g.color = color
        val metrics = g.fontMetrics
        g.drawString(text.uppercase(), x, y + metrics.ascent)
        return y + metrics.height
    }


    private fun drawEyebrowCentered(
        g: Graphics2D,
        text: String,
        color: Color,
        center: Int,
        y: Int,
        size: Float
    ): Int {
        val font = sansFont(Font.BOLD).deriveFont(size)
        g.font = font
        g.color = color
        val metrics = g.fontMetrics
        val upper = text.uppercase()
        g.drawString(upper, center - metrics.stringWidth(upper) / 2, y + metrics.ascent)
        return y + metrics.height
    }


    private fun drawCentered(
        g: Graphics2D,
        text: String,
        base: Font,
        color: Color,
        center: Int,
        y: Int,
        size: Float
    ) {
        val font = base.deriveFont(size)
        g.font = font
        g.color = color
        val metrics = g.fontMetrics
        g.drawString(text, center - metrics.stringWidth(text) / 2, y + metrics.ascent)
    }


    /** Draws every verse (number + wrapped text); returns the next Y. */
    private fun drawVerses(
        g: Graphics2D,
        spec: VerseCardSpec,
        base: Font,
        color: Color,
        x: Int,
        startY: Int,
        maxWidth: Int,
        height: Int,
        scale: Float,
        quoteMarks: Boolean
    ): Int {
        val bodyFont = base.deriveFont(44f * scale)
        val numberFont = sansFont(Font.BOLD).deriveFont(24f * scale)
        val bottomLimit = (height * 0.82f).toInt()
        var y = startY
        val verses = spec.verses
        val showNumbers = verses.size > 1
        for ((index, verse) in verses.withIndex()) {
            if (y > bottomLimit) break
            if (index > 0) y += (height * 0.035f).toInt()
            // A decorative opening quote only when there is no verse
            // number above the text (otherwise the two collide).
            if (quoteMarks && index == 0 && !showNumbers) {
                g.font = bodyFont
                g.color = withAlpha(color, 150)
                g.drawString("\u201C", x, y + g.fontMetrics.ascent)
                y += (bodyFont.size2D * 0.55f).toInt()
            }
            if (showNumbers) {
                g.font = numberFont
                g.color = withAlpha(color, 170)
                g.drawString(verse.verse.toString(), x, y + g.fontMetrics.ascent)
                y += g.fontMetrics.height + 2
            }
            val text = stripWordStudyMarkup(verse.text).trim()
            y = drawWrapped(
                g = g,
                text = text,
                font = bodyFont,
                color = color,
                x = x,
                y = y,
                maxWidth = maxWidth,
                lineGap = (bodyFont.size2D * 0.42f).toInt(),
                bottomLimit = bottomLimit
            )
        }
        return y
    }


    private fun drawFooterCentered(
        g: Graphics2D,
        spec: VerseCardSpec,
        color: Color,
        center: Int,
        y: Int,
        size: Float
    ) {
        val font = sansFont(Font.PLAIN).deriveFont(size)
        g.font = font
        g.color = color
        val metrics = g.fontMetrics
        val label = spec.translationName?.takeIf { it.isNotBlank() } ?: "BibleApp"
        g.drawString(label, center - metrics.stringWidth(label) / 2, y)
    }


    /**
     * Word-wraps [text] and draws it, returning the Y just below the last
     * line. Long single words are broken by character so a translation
     * without spaces still fits the card.
     */
    private fun drawWrapped(
        g: Graphics2D,
        text: String,
        font: Font,
        color: Color,
        x: Int,
        y: Int,
        maxWidth: Int,
        lineGap: Int,
        bottomLimit: Int
    ): Int {
        g.font = font
        g.color = color
        val metrics = g.fontMetrics
        val lineHeight = metrics.height + lineGap
        var cursorY = y
        val words = text.split(' ').filter { it.isNotEmpty() }
        var line = StringBuilder()
        for (word in words) {
            if (cursorY > bottomLimit) return cursorY
            val candidate = if (line.isEmpty()) word else "$line $word"
            if (metrics.stringWidth(candidate) <= maxWidth) {
                line = StringBuilder(candidate)
            } else {
                if (line.isNotEmpty()) {
                    g.drawString(line.toString(), x, cursorY + metrics.ascent)
                    cursorY += lineHeight
                }
                // Break a word that is wider than the whole card.
                var rest = word
                while (metrics.stringWidth(rest) > maxWidth && rest.length > 1) {
                    var cut = rest.length
                    while (cut > 1 && metrics.stringWidth(rest.substring(0, cut)) > maxWidth) {
                        cut--
                    }
                    if (cursorY > bottomLimit) return cursorY
                    g.drawString(rest.substring(0, cut), x, cursorY + metrics.ascent)
                    cursorY += lineHeight
                    rest = rest.substring(cut)
                }
                line = StringBuilder(rest)
            }
        }
        if (line.isNotEmpty() && cursorY <= bottomLimit) {
            g.drawString(line.toString(), x, cursorY + metrics.ascent)
            cursorY += lineHeight
        }
        return cursorY
    }


    private fun serifFont(style: Int): Font = Font(Font.SERIF, style, 44)

    private fun sansFont(style: Int): Font = Font(Font.SANS_SERIF, style, 28)

    private fun withAlpha(color: Color, alpha: Int): Color =
        Color(color.red, color.green, color.blue, alpha.coerceIn(0, 255))


    // ---------------------------------------------------------------------
    // Minimal PPTX writer (one full-slide picture per verse)
    // ---------------------------------------------------------------------

    private fun xmlHeader(): String =
        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"

    private fun nsDecl(): String =
        " xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\"" +
            " xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"" +
            " xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\""

    private fun emptySpTree(id: Int): String =
        "<p:spTree><p:nvGrpSpPr><p:cNvPr id=\"$id\" name=\"\"/>" +
            "<p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>" +
            "<p:grpSpPr><a:xfrm><a:off x=\"0\" y=\"0\"/>" +
            "<a:ext cx=\"0\" cy=\"0\"/><a:chOff x=\"0\" y=\"0\"/>" +
            "<a:chExt cx=\"0\" cy=\"0\"/></a:xfrm></p:grpSpPr></p:spTree>"


    private fun writePptx(target: Path, images: List<ByteArray>) {
        Files.write(target, buildPptx(images))
    }


    /** Builds the complete .pptx package in memory (also used by tests). */
    internal fun buildPptx(images: List<ByteArray>): ByteArray {
        ByteArrayOutputStream().use { out ->
            ZipOutputStream(out).use { zip ->
                fun put(name: String, bytes: ByteArray) {
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(bytes)
                    zip.closeEntry()
                }

                fun putXml(name: String, body: String) {
                    put(name, (xmlHeader() + body).toByteArray(Charsets.UTF_8))
                }

                val slideCount = images.size.coerceAtLeast(1)

                // [Content_Types].xml
                val overrides = buildString {
                    append(
                        "<Override PartName=\"/ppt/presentation.xml\" " +
                            "ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml\"/>"
                    )
                    append(
                        "<Override PartName=\"/ppt/slideMasters/slideMaster1.xml\" " +
                            "ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.slideMaster+xml\"/>"
                    )
                    append(
                        "<Override PartName=\"/ppt/slideLayouts/slideLayout1.xml\" " +
                            "ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.slideLayout+xml\"/>"
                    )
                    append(
                        "<Override PartName=\"/ppt/theme/theme1.xml\" " +
                            "ContentType=\"application/vnd.openxmlformats-officedocument.theme+xml\"/>"
                    )
                    for (index in 1..slideCount) {
                        append(
                            "<Override PartName=\"/ppt/slides/slide$index.xml\" " +
                                "ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.slide+xml\"/>"
                        )
                    }
                }
                putXml(
                    "[Content_Types].xml",
                    "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
                        "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
                        "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
                        "<Default Extension=\"png\" ContentType=\"image/png\"/>" +
                        overrides +
                        "</Types>"
                )

                // Package relationships → presentation.
                putXml(
                    "_rels/.rels",
                    "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                        "<Relationship Id=\"rId1\" " +
                        "Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" " +
                        "Target=\"ppt/presentation.xml\"/></Relationships>"
                )

                // Presentation part.
                val sldIds = buildString {
                    for (index in 1..slideCount) {
                        append("<p:sldId id=\"${255 + index}\" r:id=\"rId${index + 1}\"/>")
                    }
                }
                putXml(
                    "ppt/presentation.xml",
                    "<p:presentation${nsDecl()}>" +
                        "<p:sldMasterIdLst><p:sldMasterId id=\"2147483648\" r:id=\"rId1\"/></p:sldMasterIdLst>" +
                        "<p:sldIdLst>$sldIds</p:sldIdLst>" +
                        "<p:sldSz cx=\"$SLIDE_WIDTH_EMU\" cy=\"$SLIDE_HEIGHT_EMU\"/>" +
                        "<p:notesSz cx=\"6858000\" cy=\"9144000\"/>" +
                        "</p:presentation>"
                )

                // Presentation relationships: master, slides, theme.
                val presRels = buildString {
                    append(
                        "<Relationship Id=\"rId1\" " +
                            "Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideMaster\" " +
                            "Target=\"slideMasters/slideMaster1.xml\"/>"
                    )
                    for (index in 1..slideCount) {
                        append(
                            "<Relationship Id=\"rId${index + 1}\" " +
                                "Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide\" " +
                                "Target=\"slides/slide$index.xml\"/>"
                        )
                    }
                    append(
                        "<Relationship Id=\"rId${slideCount + 2}\" " +
                            "Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/theme\" " +
                            "Target=\"theme/theme1.xml\"/>"
                    )
                }
                putXml(
                    "ppt/_rels/presentation.xml.rels",
                    "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                        presRels + "</Relationships>"
                )

                // Slide master.
                putXml(
                    "ppt/slideMasters/slideMaster1.xml",
                    "<p:sldMaster${nsDecl()}>" +
                        "<p:cSld><p:bg><p:bgRef idx=\"1001\"><a:schemeClr val=\"bg1\"/></p:bgRef></p:bg>" +
                        emptySpTree(1) + "</p:cSld>" +
                        "<p:clrMap bg1=\"lt1\" tx1=\"dk1\" bg2=\"lt2\" tx2=\"dk2\" " +
                        "accent1=\"accent1\" accent2=\"accent2\" accent3=\"accent3\" " +
                        "accent4=\"accent4\" accent5=\"accent5\" accent6=\"accent6\" " +
                        "hlink=\"hlink\" folHlink=\"folHlink\"/>" +
                        "<p:sldLayoutIdLst>" +
                        "<p:sldLayoutId id=\"2147483649\" r:id=\"rId1\"/>" +
                        "</p:sldLayoutIdLst>" +
                        "</p:sldMaster>"
                )
                putXml(
                    "ppt/slideMasters/_rels/slideMaster1.xml.rels",
                    "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                        "<Relationship Id=\"rId1\" " +
                        "Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideLayout\" " +
                        "Target=\"../slideLayouts/slideLayout1.xml\"/></Relationships>"
                )

                // Blank layout.
                putXml(
                    "ppt/slideLayouts/slideLayout1.xml",
                    "<p:sldLayout${nsDecl()} type=\"blank\" preserve=\"1\">" +
                        "<p:cSld name=\"Blank\">" + emptySpTree(1) + "</p:cSld>" +
                        "<p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr>" +
                        "</p:sldLayout>"
                )
                putXml(
                    "ppt/slideLayouts/_rels/slideLayout1.xml.rels",
                    "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                        "<Relationship Id=\"rId1\" " +
                        "Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideMaster\" " +
                        "Target=\"../slideMasters/slideMaster1.xml\"/></Relationships>"
                )

                // Theme.
                putXml("ppt/theme/theme1.xml", themeXml())

                // Slides + their images.
                images.forEachIndexed { index, png ->
                    val slideNo = index + 1
                    put("ppt/media/card$slideNo.png", png)
                    val slide = "<p:sld${nsDecl()}><p:cSld>" + emptySpTree(1)
                        .replace(
                            "</p:spTree>",
                            "<p:pic><p:nvPicPr><p:cNvPr id=\"2\" name=\"Verse card $slideNo\"/>" +
                                "<p:cNvPicPr><a:picLocks noChangeAspect=\"1\"/></p:cNvPicPr>" +
                                "<p:nvPr/></p:nvPicPr>" +
                                "<p:blipFill><a:blip r:embed=\"rId1\"/>" +
                                "<a:stretch><a:fillRect/></a:stretch></p:blipFill>" +
                                "<p:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/>" +
                                "<a:ext cx=\"$SLIDE_WIDTH_EMU\" cy=\"$SLIDE_HEIGHT_EMU\"/></a:xfrm>" +
                                "<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></p:spPr>" +
                                "</p:pic></p:spTree>"
                        ) +
                        "</p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sld>"
                    putXml("ppt/slides/slide$slideNo.xml", slide)
                    putXml(
                        "ppt/slides/_rels/slide$slideNo.xml.rels",
                        "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                            "<Relationship Id=\"rId1\" " +
                            "Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/image\" " +
                            "Target=\"../media/card$slideNo.png\"/>" +
                            "<Relationship Id=\"rId2\" " +
                            "Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideLayout\" " +
                            "Target=\"../slideLayouts/slideLayout1.xml\"/></Relationships>"
                    )
                }
            }
            return out.toByteArray()
        }
    }


    private fun themeXml(): String {
        val solid = { color: String ->
            "<a:solidFill><a:srgbClr val=\"$color\"/></a:solidFill>"
        }
        val line = { color: String ->
            "<a:ln w=\"6350\">" + solid(color) + "</a:ln>"
        }
        return "<a:theme xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" name=\"BibleApp\">" +
            "<a:themeElements>" +
            "<a:clrScheme name=\"BibleApp\">" +
            "<a:dk1><a:srgbClr val=\"1D1D1F\"/></a:dk1>" +
            "<a:lt1><a:srgbClr val=\"FFFFFF\"/></a:lt1>" +
            "<a:dk2><a:srgbClr val=\"0A1A30\"/></a:dk2>" +
            "<a:lt2><a:srgbClr val=\"F7F1E3\"/></a:lt2>" +
            "<a:accent1><a:srgbClr val=\"B08A4F\"/></a:accent1>" +
            "<a:accent2><a:srgbClr val=\"6B7A4F\"/></a:accent2>" +
            "<a:accent3><a:srgbClr val=\"C2542F\"/></a:accent3>" +
            "<a:accent4><a:srgbClr val=\"D9B96A\"/></a:accent4>" +
            "<a:accent5><a:srgbClr val=\"1A3255\"/></a:accent5>" +
            "<a:accent6><a:srgbClr val=\"8A8A8E\"/></a:accent6>" +
            "<a:hlink><a:srgbClr val=\"1A3255\"/></a:hlink>" +
            "<a:folHlink><a:srgbClr val=\"6B7A4F\"/></a:folHlink>" +
            "</a:clrScheme>" +
            "<a:fontScheme name=\"BibleApp\">" +
            "<a:majorFont><a:latin typeface=\"Georgia\"/><a:ea typeface=\"\"/><a:cs typeface=\"\"/></a:majorFont>" +
            "<a:minorFont><a:latin typeface=\"Verdana\"/><a:ea typeface=\"\"/><a:cs typeface=\"\"/></a:minorFont>" +
            "</a:fontScheme>" +
            "<a:fmtScheme name=\"BibleApp\">" +
            "<a:fillStyleLst>" +
            solid("FFFFFF") + solid("F7F1E3") + solid("0A1A30") +
            "</a:fillStyleLst>" +
            "<a:lnStyleLst>" +
            line("B08A4F") + line("6B7A4F") + line("1D1D1F") +
            "</a:lnStyleLst>" +
            "<a:effectStyleLst>" +
            "<a:effectStyle><a:effectLst/></a:effectStyle>" +
            "<a:effectStyle><a:effectLst/></a:effectStyle>" +
            "<a:effectStyle><a:effectLst/></a:effectStyle>" +
            "</a:effectStyleLst>" +
            "<a:bgFillStyleLst>" +
            solid("FFFFFF") + solid("F7F1E3") + solid("0A1A30") +
            "</a:bgFillStyleLst>" +
            "</a:fmtScheme>" +
            "</a:themeElements>" +
            "</a:theme>"
    }
}
