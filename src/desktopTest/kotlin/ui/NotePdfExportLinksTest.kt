package ui

import data.MediaPreviewInfo
import data.MediaPreviewInfoCache
import data.MediaTitleCache
import data.findMediaReferenceTokens
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue


/**
 * The exported PDF prints a media link as the media's name and author and
 * lists the URL itself as a numbered footnote, so the note stays readable
 * in print while the link is still recoverable.
 */
class NotePdfExportLinksTest {

    @Test
    fun mediaLinkPrintsNameAndAuthorWithUrlAsFootnote() {
        MediaPreviewInfoCache.clearForTests()
        MediaTitleCache.clearForTests()
        val pdf = Files.createTempFile("note-links", ".pdf")
        try {
            val token = findMediaReferenceTokens("@youtube:dQw4w9WgXcQ").single()
            val url = token.resolveUrl()!!
            MediaPreviewInfoCache.put(
                url,
                MediaPreviewInfo(
                    title = "Never Gonna Give You Up",
                    author = "Rick Astley"
                )
            )

            NotePdfExporter.writePdf(
                target = pdf,
                title = "Test note",
                subtitle = "test.note",
                content = "Watch @youtube:dQw4w9WgXcQ today\n"
            )

            val text = Loader.loadPDF(pdf.toFile()).use { doc ->
                PDFTextStripper().getText(doc)
            }
            // Name + author replace the link in the body, with a marker…
            assertTrue(text.contains("Never Gonna Give You Up — Rick Astley [1]"), text)
            // …and the URL appears only in the footnote block under Links.
            assertTrue(text.contains("[1] $url"), text)
            assertFalse(text.contains("@youtube:"), text)
        } finally {
            MediaPreviewInfoCache.clearForTests()
            MediaTitleCache.clearForTests()
            Files.deleteIfExists(pdf)
        }
    }

    @Test
    fun noteWithoutLinksHasNoFootnoteBlock() {
        val pdf = Files.createTempFile("note-no-links", ".pdf")
        try {
            NotePdfExporter.writePdf(
                target = pdf,
                title = "Test note",
                subtitle = "test.note",
                content = "Plain paragraph.\n"
            )
            val text = Loader.loadPDF(pdf.toFile()).use { doc ->
                PDFTextStripper().getText(doc)
            }
            assertTrue(text.contains("Plain paragraph."), text)
            assertFalse(text.contains("Links"), text)
        } finally {
            Files.deleteIfExists(pdf)
        }
    }
}
