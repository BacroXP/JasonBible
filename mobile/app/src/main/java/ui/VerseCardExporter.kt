package ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import data.BibleCatalog
import data.SettingsManager
import platform.saveToDownloads
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream


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

    // 16:9 slide canvas (10 in x 5.625 in), same as the desktop deck.
    private const val SLIDE_WIDTH_EMU = 12192000L
    private const val SLIDE_HEIGHT_EMU = 6858000L
    private const val SLIDE_IMAGE_WIDTH = 1920
    private const val SLIDE_IMAGE_HEIGHT = 1080


    /** Renders the whole range as one postcard image. */
    fun renderCard(spec: VerseCardSpec): Bitmap =
        render(spec, POSTCARD_WIDTH, POSTCARD_HEIGHT)


    /** Renders one image per verse (one slide each) for the deck. */
    fun renderSlides(spec: VerseCardSpec): List<Bitmap> =
        spec.verses.map { verse -> render(spec.copy(verses = listOf(verse)), SLIDE_IMAGE_WIDTH, SLIDE_IMAGE_HEIGHT) }


    /** Renders the card and saves it as a PNG in Download/BibleApp. */
    fun exportPostcard(spec: VerseCardSpec): Path? {
        if (spec.verses.isEmpty()) return null
        val bytes = runCatching {
            ByteArrayOutputStream().use { out ->
                renderCard(spec).compress(Bitmap.CompressFormat.PNG, 100, out)
                out.toByteArray()
            }
        }.getOrNull() ?: return null
        return saveToDownloads("${fileStem(spec)}.png", "image/png", bytes)
    }


    /** Renders the deck and saves it as a .pptx in Download/BibleApp. */
    fun exportPowerPoint(spec: VerseCardSpec): Path? {
        if (spec.verses.isEmpty()) return null
        val images = runCatching {
            renderSlides(spec).map { bitmap ->
                ByteArrayOutputStream().use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                    out.toByteArray()
                }
            }
        }.getOrNull() ?: return null
        val deck = runCatching { buildPptx(images) }.getOrNull() ?: return null
        return saveToDownloads(
            "${fileStem(spec)}.pptx",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            deck
        )
    }


    /** Display name of the active translation, for the card footer. */
    fun activeTranslationName(): String? =
        BibleCatalog.entryFor(SettingsManager.translation)?.displayName


    private fun fileStem(spec: VerseCardSpec): String =
        spec.referenceLabel
            .replace(Regex("[\\\\/:*?\"<>|]"), "-")
            .replace(' ', '_')
            .ifBlank { "verse" }


    // ------------------------------------------------------------------
    // Card rendering (Android Canvas)
    // ------------------------------------------------------------------

    private data class Palette(
        val top: Int,
        val bottom: Int,
        val ink: Int,
        val accent: Int,
        val muted: Int
    )

    private fun palette(style: VerseCardStyle): Palette = when (style) {
        VerseCardStyle.PLAIN -> Palette(
            0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt(),
            0xFF1D1D1F.toInt(), 0xFFB08A4F.toInt(), 0xFF6B6B70.toInt()
        )
        VerseCardStyle.CLASSIC -> Palette(
            0xFFFBF6EA.toInt(), 0xFFF3EAD6.toInt(),
            0xFF2B2B2B.toInt(), 0xFF8C6E2F.toInt(), 0xFF6E6553.toInt()
        )
        VerseCardStyle.MIDNIGHT -> Palette(
            0xFF0B1B3A.toInt(), 0xFF13294D.toInt(),
            0xFFF7F1E3.toInt(), 0xFFF2C14E.toInt(), 0xFFB9C2D8.toInt()
        )
        VerseCardStyle.HORIZON -> Palette(
            0xFFF7C873.toInt(), 0xFFE8794A.toInt(),
            0xFF2A1A12.toInt(), 0xFF7A2E1B.toInt(), 0xFF5A382A.toInt()
        )
        VerseCardStyle.SCRIPTURE -> Palette(
            0xFFF4EEDF.toInt(), 0xFFEDE4CE.toInt(),
            0xFF3A3A2E.toInt(), 0xFF6B7A4A.toInt(), 0xFF6E6A58.toInt()
        )
    }


    private fun render(spec: VerseCardSpec, width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val palette = palette(spec.style)

        // ---- Background ----
        val background = Paint(Paint.ANTI_ALIAS_FLAG)
        if (palette.top == palette.bottom) {
            canvas.drawColor(palette.top)
        } else {
            background.shader = LinearGradient(
                0f, 0f, 0f, height.toFloat(),
                palette.top, palette.bottom, Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), background)
        }

        val margin = width * 0.09f

        // ---- Ornamental frame / rules ----
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.accent
            style = Paint.Style.STROKE
            strokeWidth = width * 0.0025f
        }
        when (spec.style) {
            VerseCardStyle.CLASSIC -> {
                canvas.drawRect(margin, margin, width - margin, height - margin, stroke)
                val inset = margin + width * 0.012f
                val thin = Paint(stroke).apply { strokeWidth = width * 0.001f }
                canvas.drawRect(inset, inset, width - inset, height - inset, thin)
            }
            VerseCardStyle.SCRIPTURE -> {
                canvas.drawRect(
                    margin, margin, width - margin, height - margin,
                    Paint(stroke).apply { strokeWidth = width * 0.004f }
                )
                drawCross(canvas, width / 2f, margin + height * 0.075f, width * 0.022f, palette.accent)
            }
            VerseCardStyle.MIDNIGHT -> {
                canvas.drawRect(
                    margin, margin, width - margin, height - margin,
                    Paint(stroke).apply { strokeWidth = width * 0.0015f }
                )
            }
            else -> Unit
        }

        // ---- Reference label ----
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
            textSize = width * 0.024f
            color = palette.accent
            textAlign = Paint.Align.CENTER
            letterSpacing = 0.08f
        }
        val labelY = if (spec.style == VerseCardStyle.SCRIPTURE) {
            margin + height * 0.135f
        } else {
            margin + height * 0.06f
        }
        canvas.drawText(spec.referenceLabel.uppercase(), width / 2f, labelY, labelPaint)

        // ---- Accent rule under the reference ----
        val rulePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.accent
            strokeWidth = width * 0.003f
        }
        val ruleY = labelY + height * 0.035f
        canvas.drawLine(width * 0.38f, ruleY, width * 0.62f, ruleY, rulePaint)

        // ---- Verse text ----
        val versePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.SERIF, Typeface.ITALIC)
            textSize = if (spec.verses.size > 1) width * 0.030f else width * 0.036f
            color = palette.ink
            textAlign = Paint.Align.CENTER
        }
        val maxTextWidth = width - margin * 2 - width * 0.06f
        val quoted = spec.verses.joinToString("\n\n") { "\u201C${it.text.trim()}\u201D" }
        val lines = wrap(quoted, versePaint, maxTextWidth)
        val lineHeight = versePaint.textSize * 1.35f
        val totalHeight = lines.size * lineHeight
        val firstVerseTop = if (spec.style == VerseCardStyle.SCRIPTURE) {
            margin + height * 0.19f
        } else {
            ruleY + height * 0.10f
        }
        var baseline = firstVerseTop + (height - firstVerseTop - height * 0.16f - totalHeight) / 2f
        if (baseline < firstVerseTop) baseline = firstVerseTop
        for (line in lines) {
            canvas.drawText(line, width / 2f, baseline + versePaint.textSize, versePaint)
            baseline += lineHeight
        }

        // ---- Footer: translation name ----
        val footerText = spec.translationName.orEmpty()
        if (footerText.isNotBlank()) {
            val footerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
                textSize = width * 0.020f
                color = palette.muted
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText(footerText, width / 2f, height - margin - height * 0.02f, footerPaint)
        }

        return bitmap
    }


    private fun drawCross(canvas: Canvas, cx: Float, cy: Float, arm: Float, color: Int) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            strokeWidth = arm * 0.38f
            strokeCap = Paint.Cap.ROUND
        }
        canvas.drawLine(cx, cy - arm, cx, cy + arm, paint)
        canvas.drawLine(cx - arm * 0.72f, cy - arm * 0.18f, cx + arm * 0.72f, cy - arm * 0.18f, paint)
    }


    /** Greedy word wrap honouring explicit newlines. */
    private fun wrap(text: String, paint: Paint, maxWidth: Float): List<String> {
        val lines = mutableListOf<String>()
        for (paragraph in text.split('\n')) {
            if (paragraph.isBlank()) {
                lines.add("")
                continue
            }
            val words = paragraph.trim().split(' ')
            val current = StringBuilder()
            for (word in words) {
                val candidate = if (current.isEmpty()) word else "$current $word"
                if (paint.measureText(candidate) <= maxWidth) {
                    current.setLength(0)
                    current.append(candidate)
                } else {
                    if (current.isNotEmpty()) lines.add(current.toString())
                    current.setLength(0)
                    current.append(word)
                }
            }
            if (current.isNotEmpty()) lines.add(current.toString())
        }
        return lines
    }


    // ------------------------------------------------------------------
    // PowerPoint (OOXML) writer — unchanged package structure
    // ------------------------------------------------------------------

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
