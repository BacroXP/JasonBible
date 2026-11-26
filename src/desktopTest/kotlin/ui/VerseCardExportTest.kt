package ui

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipInputStream
import javax.imageio.ImageIO
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue


/**
 * Covers range-aware reference highlighting plus the verse-card renderer
 * and its dependency-free PowerPoint package: every card style renders,
 * a range becomes one slide per verse, and the generated .pptx contains
 * the parts / well-formed XML a reader needs.
 */
class VerseCardExportTest {

    companion object {
        init {
            System.setProperty("java.awt.headless", "true")
        }
    }

    private val spec = VerseCardSpec(
        bookName = "John",
        verses = listOf(
            CardVerse(3, 16, "For God so loved the world, that he gave his only begotten Son."),
            CardVerse(3, 17, "For God sent not his Son into the world to condemn the world.")
        ),
        translationName = "Luther Bible 1912",
        style = VerseCardStyle.CLASSIC
    )


    // ------------------------------------------------------------------
    // Range coverage
    // ------------------------------------------------------------------

    @Test
    fun rangedReferenceHighlightsEveryCoveredVerse() {
        val selection = BibleReferenceSelection(
            book = "John",
            chapter = 3,
            verse = 16,
            endChapter = 3,
            endVerse = 18
        )
        assertTrue(selection.highlights(3, 16))
        assertTrue(selection.highlights(3, 17))
        assertTrue(selection.highlights(3, 18))
        assertFalse(selection.highlights(3, 15))
        assertFalse(selection.highlights(3, 19))
        assertFalse(selection.highlights(4, 16))
        assertEquals("John 3:16-18", selection.displayText())
    }

    @Test
    fun crossChapterReferenceHighlightsBothChaptersAndTheSpan() {
        val selection = BibleReferenceSelection(
            book = "Luke",
            chapter = 7,
            verse = 1,
            endChapter = 8,
            endVerse = 1
        )
        assertTrue(selection.highlights(7, 1))
        assertTrue(selection.highlights(7, 44))
        assertTrue(selection.highlights(8, 1))
        assertFalse(selection.highlights(8, 2))
        assertFalse(selection.highlights(6, 30))
        assertEquals("Luke 7:1-8:1", selection.displayText())
    }

    @Test
    fun chapterReferenceHighlightsWholeChapter() {
        val selection = BibleReferenceSelection(book = "John", chapter = 3)
        assertTrue(selection.highlights(3, 1))
        assertTrue(selection.highlights(3, 36))
        assertFalse(selection.highlights(4, 1))
        assertEquals("John 3", selection.displayText())
    }

    @Test
    fun singleVerseReferenceDoesNotLeak() {
        val selection = BibleReferenceSelection(book = "John", chapter = 3, verse = 16)
        assertTrue(selection.highlights(3, 16))
        assertFalse(selection.highlights(3, 17))
        assertEquals("John 3:16", selection.displayText())
    }


    // ------------------------------------------------------------------
    // Card rendering
    // ------------------------------------------------------------------

    @Test
    fun referenceLabelDescribesTheRange() {
        assertEquals("John 3:16-17", spec.referenceLabel)
        assertEquals(
            "Luke 7:1-8:1",
            spec.copy(
                bookName = "Luke",
                verses = listOf(CardVerse(7, 1, "a"), CardVerse(8, 1, "b"))
            ).referenceLabel
        )
        assertEquals(
            "John 3:16",
            spec.copy(verses = listOf(CardVerse(3, 16, "a"))).referenceLabel
        )
    }

    @Test
    fun postcardOnlySupportsOneOrTwoVerses() {
        assertTrue(spec.copy(verses = spec.verses.take(1)).supportsPostcard)
        assertTrue(spec.copy(verses = spec.verses.take(2)).supportsPostcard)
        assertFalse(
            spec.copy(
                verses = spec.verses + CardVerse(3, 18, "He that believeth on him is not condemned.")
            ).supportsPostcard
        )
        assertFalse(spec.copy(verses = emptyList()).supportsPostcard)
    }

    @Test
    fun everyStyleRendersAFullSizePostcard() {
        val outDir = Path.of("build", "test-cards")
        Files.createDirectories(outDir)
        for (style in VerseCardStyle.entries) {
            val image = VerseCardExporter.renderCard(spec.copy(style = style))
            assertEquals(1748, image.width)
            assertEquals(1240, image.height)
            val target = outDir.resolve("postcard-${style.name.lowercase()}.png")
            ImageIO.write(image, "png", target.toFile())
            assertTrue(Files.size(target) > 1_000, "$style produced an empty image")
        }
    }

    @Test
    fun rangeBecomesOneSlidePerVerse() {
        val slides = VerseCardExporter.renderSlides(spec)
        assertEquals(2, slides.size)
        slides.forEach { image ->
            assertEquals(1920, image.width)
            assertEquals(1080, image.height)
        }
        val outDir = Path.of("build", "test-cards")
        Files.createDirectories(outDir)
        ImageIO.write(slides.first(), "png", outDir.resolve("slide-classic.png").toFile())
    }


    // ------------------------------------------------------------------
    // PPTX package
    // ------------------------------------------------------------------

    @Test
    fun pptxPackageContainsSlidesMediaAndValidXml() {
        val png = ByteArrayOutputStream().use { out ->
            ImageIO.write(VerseCardExporter.renderSlides(spec).first(), "png", out)
            out.toByteArray()
        }
        val bytes = VerseCardExporter.buildPptx(listOf(png, png))
        assertTrue(bytes.size > 2_000)

        // Keep a copy on disk so the package can be opened / converted
        // with an external office suite when debugging.
        val outDir = Path.of("build", "test-cards")
        Files.createDirectories(outDir)
        Files.write(outDir.resolve("range-deck.pptx"), bytes)

        val entries = mutableMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    entries[entry.name] = zip.readBytes()
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }

        listOf(
            "[Content_Types].xml",
            "_rels/.rels",
            "ppt/presentation.xml",
            "ppt/_rels/presentation.xml.rels",
            "ppt/slideMasters/slideMaster1.xml",
            "ppt/slideLayouts/slideLayout1.xml",
            "ppt/theme/theme1.xml",
            "ppt/slides/slide1.xml",
            "ppt/slides/slide2.xml",
            "ppt/slides/_rels/slide1.xml.rels",
            "ppt/media/card1.png",
            "ppt/media/card2.png"
        ).forEach { name ->
            assertTrue(entries.containsKey(name), "missing package part: $name")
        }

        // Every XML part must parse — a malformed part makes PowerPoint
        // declare the file corrupt.
        val factory = DocumentBuilderFactory.newInstance()
        entries
            .filterKeys { it.endsWith(".xml") || it.endsWith(".rels") }
            .forEach { (name, data) ->
                runCatching {
                    factory.newDocumentBuilder().parse(ByteArrayInputStream(data))
                }.onFailure { error ->
                    throw AssertionError("$name is not well-formed XML", error)
                }
            }
    }
}
