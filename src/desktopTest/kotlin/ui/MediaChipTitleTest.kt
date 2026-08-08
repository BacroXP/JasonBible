package ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import data.MediaReferenceToken
import data.findMediaReferenceTokens
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue


/**
 * Media chips render the media's TITLE (oEmbed) instead of the raw
 * `@youtube:…` link once it has been fetched. A title can be much longer
 * than the hidden token, so the media MappingSpan clamps
 * ([MappingSpan.clampToOriginal]) — every tap / caret inside the longer
 * chip must still resolve back into the token's source range.
 */
class MediaChipTitleTest {

    private fun transformation(
        titleLookup: (MediaReferenceToken) -> String? = { null }
    ): NoteVisualTransformation = NoteVisualTransformation(
        NotePalette(
            onSurface = Color.Black,
            onSurfaceVariant = Color.Gray,
            primary = Color.Blue,
            tertiary = Color.Cyan,
            faded = Color.DarkGray,
            referenceBackground = Color.LightGray,
            noteLinkBackground = Color.Cyan.copy(alpha = 0.2f)
        ),
        fontScale = 1f,
        mediaTitleLookup = titleLookup
    )

    @Test
    fun titleChipReplacesTheLinkText() {
        val vt = transformation { "Never Gonna Give You Up" }
        val out = vt.filter(AnnotatedString("Watch @youtube:dQw4w9WgXcQ today"))
        // The media's title replaces the raw id / service label (chips
        // carry no service glyph — just the muted title text).
        assertTrue(out.text.text.contains("Never Gonna Give You Up"))
        assertTrue(!out.text.text.contains("dQw4w9WgXcQ"))
    }

    @Test
    fun longTitleChipRendersFullyAndKeepsTapsResolving() {
        // The title is MUCH longer than the 21-char token.
        val title = "Never Gonna Give You Up (Official Music Video)"
        val vt = transformation { title }
        val source = "Watch @youtube:dQw4w9WgXcQ today"
        val out = vt.filter(AnnotatedString(source))
        // Full title displayed (no emoji prefix), no truncation at 60 chars.
        assertTrue(out.text.text.contains(title))

        // Every display position inside the longer chip must map back into
        // the token's source range (tap/hover hit-testing), not drift
        // before it.
        val token = findMediaReferenceTokens(source).single()
        val chipStart = out.text.text.indexOf(title)
        val chipEnd = chipStart + title.length
        for (t in chipStart until chipEnd) {
            val back = out.offsetMapping.transformedToOriginal(t)
            assertTrue(
                back in token.sourceStart until token.sourceEnd,
                "display $t maps to source $back, outside token " +
                    "[${token.sourceStart}, ${token.sourceEnd})"
            )
        }
        // And the endpoints still round-trip cleanly.
        assertEquals(
            out.text.text.length,
            out.offsetMapping.originalToTransformed(source.length)
        )
        assertEquals(
            source.length,
            out.offsetMapping.transformedToOriginal(out.text.text.length)
        )
    }

    @Test
    fun titleLongerThanChipCapIsTruncatedWithEllipsis() {
        val long = "A".repeat(100)
        val vt = transformation { long }
        val out = vt.filter(AnnotatedString("@youtube:dQw4w9WgXcQ"))
        // Capped at 60 chars + an ellipsis.
        assertTrue(out.text.text.startsWith("A"))
        assertTrue(out.text.text.endsWith("…"))
        assertTrue(out.text.text.length <= 60 + 1)
    }

    @Test
    fun noTitleFallsBackToServiceChip() {
        val vt = transformation { null }
        val out = vt.filter(AnnotatedString("Watch @youtube:dQw4w9WgXcQ today"))
        // The fallback chip is the bounded service+id text (truncated to
        // the token length, so the full 11-char id may not fit) — the
        // point is that the title is NOT shown when unknown.
        assertTrue(out.text.text.contains("YouTube"))
        assertTrue(!out.text.text.contains("Never Gonna"))
    }

    @Test
    fun blankTitleFallsBackToServiceChip() {
        val vt = transformation { "   " }
        val out = vt.filter(AnnotatedString("@youtube:dQw4w9WgXcQ"))
        // A blank title is not rendered — the bounded service+id chip is.
        assertTrue(out.text.text.contains("YouTube"))
        assertTrue(!out.text.text.contains("   "))
    }

    @Test
    fun titleChipInColoredQuoteTrailingTextResolvesTaps() {
        // Colored-quote trailing text carries its own media-chip walk
        // (findMediaReferenceTokens on the trailing segment) — the same
        // title + clamp behavior must hold there.
        val title = "Josia Queen - My God"
        val vt = transformation { title }
        val source = "\"Watch this\"[#3B82F6] @youtube:dQw4w9WgXcQ"
        val out = vt.filter(AnnotatedString(source))
        assertTrue(out.text.text.contains(title))

        val token = findMediaReferenceTokens(source).single()
        val chipStart = out.text.text.indexOf(title)
        val chipEnd = chipStart + title.length
        for (t in chipStart until chipEnd) {
            val back = out.offsetMapping.transformedToOriginal(t)
            assertTrue(
                back in token.sourceStart until token.sourceEnd,
                "display $t maps to source $back, outside token " +
                    "[${token.sourceStart}, ${token.sourceEnd})"
            )
        }
    }
}
