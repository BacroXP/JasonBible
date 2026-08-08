package data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue


class MediaReferencesTest {

    // ------------------------------------------------------------------
    // findMediaReferenceTokens — tokenizer
    // ------------------------------------------------------------------

    @Test
    fun youtubeIdInlineInSentence() {
        val tokens = findMediaReferenceTokens("Watch @youtube:dQw4w9WgXcQ today")
        assertEquals(1, tokens.size)
        val token = tokens.first()
        assertEquals(MediaService.YOUTUBE, token.service)
        assertEquals("dQw4w9WgXcQ", token.content)
        assertEquals(6, token.sourceStart)
        assertEquals(26, token.sourceEnd) // excludes the trailing space
    }

    @Test
    fun trailingSentencePunctuationIsStripped() {
        // "@youtube:" is 9 chars, "@vimeo:" is 7; ids are 11 / 9 chars.
        val tokens = findMediaReferenceTokens("@youtube:dQw4w9WgXcQ, and @vimeo:123456789.")
        assertEquals(2, tokens.size)
        assertEquals("dQw4w9WgXcQ", tokens[0].content)
        assertEquals(0, tokens[0].sourceStart)
        assertEquals(20, tokens[0].sourceEnd) // ends before the ','
        assertEquals("123456789", tokens[1].content)
        assertEquals(26, tokens[1].sourceStart)
        assertEquals(42, tokens[1].sourceEnd) // ends before the '.'
    }

    @Test
    fun spotifyTrackTypePrefix() {
        val tokens = findMediaReferenceTokens("@spotify:track:4cOdonKdQq7vF2eXyQfPqA")
        assertEquals(1, tokens.size)
        assertEquals(MediaService.SPOTIFY, tokens[0].service)
        assertEquals("track:4cOdonKdQq7vF2eXyQfPqA", tokens[0].content)
    }

    @Test
    fun spotifyBareIdDefaultsToTrack() {
        val tokens = findMediaReferenceTokens("@spotify:4cOdonKdQq7vF2eXyQfPqA")
        assertEquals(1, tokens.size)
        assertEquals(
            "https://open.spotify.com/track/4cOdonKdQq7vF2eXyQfPqA",
            tokens[0].resolveUrl()
        )
    }

    @Test
    fun soundcloudAndGenericUrl() {
        val sc = findMediaReferenceTokens("@soundcloud:https://soundcloud.com/artist/track")
        assertEquals(1, sc.size)
        assertEquals("https://soundcloud.com/artist/track", sc[0].content)
        assertEquals(
            "https://soundcloud.com/artist/track",
            sc[0].resolveUrl()
        )

        val link = findMediaReferenceTokens("@url:https://example.com/post/123")
        assertEquals(1, link.size)
        assertEquals(MediaService.LINK, link[0].service)
        assertEquals("https://example.com/post/123", link[0].resolveUrl())
    }

    @Test
    fun unknownServiceIsRejected() {
        assertTrue(findMediaReferenceTokens("@foo:bar123").isEmpty())
        // Email-ish mention must not become a media chip.
        assertTrue(findMediaReferenceTokens("mail me @home:office please").isEmpty())
    }

    @Test
    fun malformedPayloadsAreRejected() {
        // Too-short YouTube id.
        assertTrue(findMediaReferenceTokens("@youtube:short").isEmpty())
        // SoundCloud requires a full URL, not a bare id.
        assertTrue(findMediaReferenceTokens("@soundcloud:abc123").isEmpty())
        // Non-http generic link.
        assertTrue(findMediaReferenceTokens("@url:ftp://example.com/x").isEmpty())
        // Empty content after the colon.
        assertTrue(findMediaReferenceTokens("@youtube:").isEmpty())
    }

    @Test
    fun serviceKeyIsCaseInsensitive() {
        val tokens = findMediaReferenceTokens("@YouTube:dQw4w9WgXcQ")
        assertEquals(1, tokens.size)
        assertEquals(MediaService.YOUTUBE, tokens[0].service)
    }

    @Test
    fun multipleTokensInOneLine() {
        val tokens = findMediaReferenceTokens(
            "a @youtube:dQw4w9WgXcQ b @spotify:track:4cOdonKdQq7vF2eXyQfPqA c"
        )
        assertEquals(2, tokens.size)
        // Source order preserved.
        assertEquals(MediaService.YOUTUBE, tokens[0].service)
        assertEquals(MediaService.SPOTIFY, tokens[1].service)
        assertTrue(tokens[0].sourceStart < tokens[1].sourceStart)
    }

    @Test
    fun youtubeIdWithHyphenAndUnderscore() {
        val id = "Xx_9-abcDEF" // exactly 11 chars: letters, digit, _ and -
        val tokens = findMediaReferenceTokens("@youtube:$id")
        assertEquals(1, tokens.size)
        assertEquals("https://www.youtube.com/watch?v=$id", tokens[0].resolveUrl())
    }

    // ------------------------------------------------------------------
    // resolveUrl — canonical URL building
    // ------------------------------------------------------------------

    @Test
    fun youtubeIdBuildsWatchUrl() {
        val token = findMediaReferenceTokens("@youtube:dQw4w9WgXcQ").single()
        assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ", token.resolveUrl())
    }

    @Test
    fun youtubeTimestampIsPreserved() {
        val token = findMediaReferenceTokens("@youtube:dQw4w9WgXcQ?t=63").single()
        assertEquals(
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=63",
            token.resolveUrl()
        )
    }

    @Test
    fun youtubeUrlsPassThrough() {
        assertEquals(
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
            MediaService.YOUTUBE.buildUrl("https://www.youtube.com/watch?v=dQw4w9WgXcQ")
        )
        assertEquals(
            "https://youtu.be/dQw4w9WgXcQ?t=30",
            MediaService.YOUTUBE.buildUrl("https://youtu.be/dQw4w9WgXcQ?t=30")
        )
        // A non-YouTube URL is not accepted by the YouTube builder.
        assertNull(MediaService.YOUTUBE.buildUrl("https://example.com/v"))
    }

    @Test
    fun spotifyTypesAndUrls() {
        assertEquals(
            "https://open.spotify.com/album/4cOdonKdQq7vF2eXyQfPqA",
            MediaService.SPOTIFY.buildUrl("album:4cOdonKdQq7vF2eXyQfPqA")
        )
        assertEquals(
            "https://open.spotify.com/playlist/4cOdonKdQq7vF2eXyQfPqA",
            MediaService.SPOTIFY.buildUrl("playlist:4cOdonKdQq7vF2eXyQfPqA")
        )
        assertEquals(
            "https://open.spotify.com/track/4cOdonKdQq7vF2eXyQfPqA",
            MediaService.SPOTIFY.buildUrl("https://open.spotify.com/track/4cOdonKdQq7vF2eXyQfPqA")
        )
        // Unknown type prefix rejected.
        assertNull(MediaService.SPOTIFY.buildUrl("video:4cOdonKdQq7vF2eXyQfPqA"))
        // Wrong id length rejected.
        assertNull(MediaService.SPOTIFY.buildUrl("track:tooshort"))
    }

    @Test
    fun vimeoNumericIdAndUrl() {
        assertEquals(
            "https://vimeo.com/76979871",
            MediaService.VIMEO.buildUrl("76979871")
        )
        assertEquals(
            "https://vimeo.com/76979871",
            MediaService.VIMEO.buildUrl("https://vimeo.com/76979871")
        )
        // Letters are not a Vimeo id.
        assertNull(MediaService.VIMEO.buildUrl("abc123"))
    }

    @Test
    fun genericLinkRequiresHttpAndHost() {
        assertEquals(
            "https://example.com/x",
            MediaService.LINK.buildUrl("https://example.com/x")
        )
        assertNull(MediaService.LINK.buildUrl("example.com/x"))
        assertNull(MediaService.LINK.buildUrl(""))
    }

    // ------------------------------------------------------------------
    // chipText — display label + the length invariant
    // ------------------------------------------------------------------

    @Test
    fun chipTextNeverLongerThanTheSourceToken() {
        // The chip display is synthesized over the hidden source token;
        // if it were longer, clicks on the right part of the chip would
        // map outside the token and stop resolving. Every valid token
        // must therefore render a chip no longer than itself.
        val cases = listOf(
            "@youtube:dQw4w9WgXcQ",
            "@youtube:dQw4w9WgXcQ?t=1m30s",
            "@youtube:https://youtu.be/dQw4w9WgXcQ",
            "@vimeo:76979871",
            "@vimeo:https://vimeo.com/76979871",
            "@spotify:track:4cOdonKdQq7vF2eXyQfPqA",
            "@spotify:album:4cOdonKdQq7vF2eXyQfPqA",
            "@spotify:4cOdonKdQq7vF2eXyQfPqA",
            "@soundcloud:https://soundcloud.com/artist/track",
            "@url:https://example.com/post/123"
        )
        for (source in cases) {
            val token = findMediaReferenceTokens(source).single()
            val tokenLen = token.sourceEnd - token.sourceStart
            val chip = token.chipText()
            assertTrue(
                chip.length <= tokenLen,
                "chip \"$chip\" (${chip.length}) longer than token \"$source\" ($tokenLen)"
            )
            assertTrue(chip.isNotBlank())
        }
    }

    @Test
    fun chipTextShowsServiceAndContentHint() {
        val yt = findMediaReferenceTokens("@youtube:dQw4w9WgXcQ").single().chipText()
        assertTrue(yt.startsWith("▶️"))
        assertTrue(yt.contains("YouTube"))

        val link = findMediaReferenceTokens("@url:https://example.com/post/123").single().chipText()
        assertTrue(link.startsWith("🔗"))
        assertTrue(link.contains("example.com"))
    }

    // ------------------------------------------------------------------
    // Non-collision with the Bible reference grammar
    // ------------------------------------------------------------------

    @Test
    fun bibleAndMediaTokenizersDoNotCollide() {
        // Media tokens contain no `$`, so the Bible tokenizer ignores them.
        assertTrue(findReferenceTokens("Watch @youtube:dQw4w9WgXcQ").isEmpty())
        // Bible tokens contain no `@service:` shape, so the media
        // tokenizer ignores them.
        assertTrue(findMediaReferenceTokens("Read \$Lukas\$3\$16 today").isEmpty())
        // Both kinds coexist in one line without overlapping.
        val media = findMediaReferenceTokens(
            "See @youtube:dQw4w9WgXcQ and \$Joh\$3\$16"
        )
        val bible = findReferenceTokens(
            "See @youtube:dQw4w9WgXcQ and \$Joh\$3\$16"
        )
        assertEquals(1, media.size)
        assertEquals(1, bible.size)
    }
}
