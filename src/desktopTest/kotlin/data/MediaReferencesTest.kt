package data

import kotlinx.coroutines.runBlocking
import testutil.TestEnv
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue


class MediaReferencesTest {

    companion object {
        init {
            // Redirect user.home to a throwaway dir so @file tests touch a
            // temp notes folder instead of the developer's real one.
            TestEnv.homeDir
            SettingsManager.notesInitialized = true
        }
    }

    /** Create a dummy media file in the (temp) notes media folder. */
    private fun createMediaFile(name: String): Path {
        val dir = Path.of(
            System.getProperty("user.home"),
            ".bibleapp",
            "notes",
            "media"
        )
        Files.createDirectories(dir)
        val path = dir.resolve(name)
        Files.write(path, byteArrayOf(0x01, 0x02, 0x03))
        return path
    }

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
        // The chip carries the plain service label + id / domain — no
        // colorful emoji glyph (icons are rendered as muted vectors by
        // the UI layer instead).
        val yt = findMediaReferenceTokens("@youtube:dQw4w9WgXcQ").single().chipText()
        assertTrue(yt.contains("YouTube"))
        assertTrue(yt.contains("dQw4w9WgXcQ"))

        val link = findMediaReferenceTokens("@url:https://example.com/post/123").single().chipText()
        assertTrue(link.contains("example.com"))
    }

    // ------------------------------------------------------------------
    // Duration badge helpers
    // ------------------------------------------------------------------

    @Test
    fun formatDurationSecondsProducesMssAndHmmss() {
        assertEquals("12:47", formatDurationSeconds(767))
        assertEquals("8:15", formatDurationSeconds(495))
        assertEquals("3:28", formatDurationSeconds(208))
        assertEquals("0:00", formatDurationSeconds(0))
        assertEquals("0:05", formatDurationSeconds(5))
        assertEquals("1:00:00", formatDurationSeconds(3600))
        assertEquals("2:05:09", formatDurationSeconds(7509))
    }

    @Test
    fun youtubeDurationScrapeRejectsNonYoutubeTokensWithoutNetwork() {
        val vimeo = findMediaReferenceTokens("@vimeo:76979871").single()
        // Non-YouTube services short-circuit before any network call.
        assertEquals(
            null,
            kotlinx.coroutines.runBlocking { fetchYouTubeDurationSeconds(vimeo) }
        )
    }

    // ------------------------------------------------------------------
    // playerUrl — in-app playback
    //
    // YouTube / Vimeo / SoundCloud are played NATIVELY by JavaFX
    // MediaPlayer on a direct stream resolved by the bundled yt-dlp at
    // play time, so they have no player URL here (null). Spotify alone
    // keeps an official embed URL (its content has no public direct
    // stream); local files return their `file://` URI.
    // ------------------------------------------------------------------

    @Test
    fun youtubePlayerUrlIsNull_PlayedNativelyViaResolvedStream() {
        val plain = findMediaReferenceTokens("@youtube:dQw4w9WgXcQ").single()
        assertNull(plain.playerUrl())
        val watch = findMediaReferenceTokens(
            "@youtube:https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=63"
        ).single()
        assertNull(watch.playerUrl())
        val short = findMediaReferenceTokens("@youtube:https://youtu.be/dQw4w9WgXcQ").single()
        assertNull(short.playerUrl())
        // ...but they remain playable (the play button still appears).
        assertTrue(plain.service.isPlayable)
    }

    @Test
    fun vimeoPlayerUrlIsNull_PlayedNativelyViaResolvedStream() {
        val plain = findMediaReferenceTokens("@vimeo:76979871").single()
        assertNull(plain.playerUrl())
        val url = findMediaReferenceTokens("@vimeo:https://vimeo.com/76979871").single()
        assertNull(url.playerUrl())
        assertTrue(url.service.isPlayable)
    }

    @Test
    fun soundcloudPlayerUrlIsNull_PlayedNativelyViaResolvedStream() {
        val sc = findMediaReferenceTokens(
            "@soundcloud:https://soundcloud.com/artist/track"
        ).single()
        assertNull(sc.playerUrl())
        assertTrue(sc.service.isPlayable)
    }

    @Test
    fun spotifyPlayerUrlEmbedsTrackOrAlbum() {
        val track = findMediaReferenceTokens("@spotify:track:4cOdonKdQq7vF2eXyQfPqA").single()
        assertEquals(
            "https://open.spotify.com/embed/track/4cOdonKdQq7vF2eXyQfPqA?autoplay=true",
            track.playerUrl()
        )
        val album = findMediaReferenceTokens("@spotify:album:4cOdonKdQq7vF2eXyQfPqA").single()
        assertEquals(
            "https://open.spotify.com/embed/album/4cOdonKdQq7vF2eXyQfPqA?autoplay=true",
            album.playerUrl()
        )
    }

    @Test
    fun linksHaveNoPlayer() {
        val link = findMediaReferenceTokens("@url:https://example.com/post/123").single()
        assertNull(link.playerUrl())
    }

    @Test
    fun isPlayableExcludesGenericLinks() {
        assertTrue(MediaService.YOUTUBE.isPlayable)
        assertTrue(MediaService.VIMEO.isPlayable)
        assertTrue(MediaService.SPOTIFY.isPlayable)
        assertTrue(MediaService.SOUNDCLOUD.isPlayable)
        assertFalse(MediaService.LINK.isPlayable)
    }

    // ------------------------------------------------------------------
    // Local media files (@file: tokens)
    // ------------------------------------------------------------------

    @Test
    fun fileTokenParsesWhenFileExists() {
        val media = createMediaFile("photo-abc123.png")
        val tokens = findMediaReferenceTokens("@file:media/photo-abc123.png")
        assertEquals(1, tokens.size)
        val token = tokens.first()
        assertEquals(MediaService.FILE, token.service)
        assertEquals("media/photo-abc123.png", token.content)
        assertTrue(token.resolveUrl()!!.startsWith("file:"))
        assertEquals(media.toString(), token.localPath())
        // In-app player URL is the same file:// URI (played natively).
        assertTrue(token.playerUrl()!!.startsWith("file:"))
    }

    @Test
    fun fileChipTextNeverLongerThanTheSourceToken() {
        createMediaFile("photo-abc123.png")
        val source = "@file:media/photo-abc123.png"
        val token = findMediaReferenceTokens(source).single()
        val tokenLen = token.sourceEnd - token.sourceStart
        val chip = token.chipText()
        assertTrue(
            chip.length <= tokenLen,
            "chip \"$chip\" (${chip.length}) longer than token \"$source\" ($tokenLen)"
        )
        assertTrue(chip.contains("File"))
        assertTrue(chip.isNotBlank())
    }

    @Test
    fun fileTokenIsRejectedWhenFileIsMissing() {
        assertTrue(findMediaReferenceTokens("@file:media/gone.jpg").isEmpty())
        // And a localPath() for a missing file is null.
        assertNull(MediaService.FILE.buildUrl("media/gone.jpg"))
    }

    @Test
    fun mediaKindIsClassifiedByExtension() {
        assertEquals(MediaFileKind.IMAGE, mediaKindFor("photo.PNG"))
        assertEquals(MediaFileKind.IMAGE, mediaKindFor("scan.jpeg"))
        assertEquals(MediaFileKind.IMAGE, mediaKindFor("art.webp"))
        assertEquals(MediaFileKind.VIDEO, mediaKindFor("clip.mp4"))
        assertEquals(MediaFileKind.VIDEO, mediaKindFor("movie.MOV"))
        assertEquals(MediaFileKind.AUDIO, mediaKindFor("song.mp3"))
        assertEquals(MediaFileKind.AUDIO, mediaKindFor("voice.wav"))
        assertEquals(null, mediaKindFor("notes.txt"))
        assertEquals(null, mediaKindFor("archive.zip"))
        assertEquals(null, mediaKindFor("noextension"))
    }

    @Test
    fun importMediaFileCopiesIntoMediaFolderWithUniqueName() {
        val source = Files.createTempFile("vacation-photo", ".jpg")
        val ref = NotesRepository.importMediaFile(source)
        assertNotNull(ref)
        assertTrue(ref!!.startsWith("media/"))
        assertTrue(ref.endsWith(".jpg"))
        // The copy exists on disk and resolves back to a real file.
        val target = NotesRepository.resolveMediaRef(ref)
        assertNotNull(target)
        assertTrue(Files.exists(target))
        // The original is untouched.
        assertTrue(Files.exists(source))
    }

    @Test
    fun importMediaFileRejectsUnsupportedTypesAndDirectories() {
        val txt = Files.createTempFile("doc", ".txt")
        assertNull(NotesRepository.importMediaFile(txt))
        val dir = Files.createTempDirectory("folder")
        assertNull(NotesRepository.importMediaFile(dir))
    }

    // ------------------------------------------------------------------
    // Channel / profile references (YouTube channels, Spotify users)
    // -------------------------------------------------------------------

    @Test
    fun payloadMayContainAtSign() {
        // YouTube handles start with `@`; the payload must keep it.
        val token = findMediaReferenceTokens("@youtube:@BibleProject").single()
        assertEquals("@BibleProject", token.content)
    }

    @Test
    fun youtubeChannelHandleResolvesToProfilePage() {
        val token = findMediaReferenceTokens("@youtube:@BibleProject").single()
        assertEquals("https://www.youtube.com/@BibleProject", token.resolveUrl())
        assertTrue(token.isProfile)
        // Channels have no video embed — nothing to play in-app.
        assertNull(token.playerUrl())
    }

    @Test
    fun youtubeChannelShapesResolve() {
        val channel = MediaService.YOUTUBE.buildUrl("channel/UCdQw4w9WgXcQdQw4w9WgXcQ")
        assertEquals(
            "https://www.youtube.com/channel/UCdQw4w9WgXcQdQw4w9WgXcQ",
            channel
        )
        assertEquals(
            "https://www.youtube.com/c/BibleProject",
            MediaService.YOUTUBE.buildUrl("c/BibleProject")
        )
        assertEquals(
            "https://www.youtube.com/user/oldName",
            MediaService.YOUTUBE.buildUrl("user/oldName")
        )
        // URL pass-through keeps the profile shape.
        val url = MediaService.YOUTUBE.buildUrl("https://www.youtube.com/@BibleProject")
        assertEquals("https://www.youtube.com/@BibleProject", url)
        assertTrue(
            findMediaReferenceTokens("@youtube:https://www.youtube.com/@BibleProject")
                .single().isProfile
        )
        // A channel shape is NOT a video id.
        assertNull(MediaService.YOUTUBE.buildUrl("channel/short"))
        assertNull(MediaService.YOUTUBE.buildUrl("@"))
    }

    @Test
    fun spotifyUserProfileResolves() {
        val token = findMediaReferenceTokens("@spotify:user:james").single()
        assertEquals("https://open.spotify.com/user/james", token.resolveUrl())
        assertTrue(token.isProfile)
        // No embed exists for user profiles.
        assertNull(token.playerUrl())
    }

    @Test
    fun spotifyArtistIsProfileButStillPlayable() {
        val artist = findMediaReferenceTokens("@spotify:artist:4cOdonKdQq7vF2eXyQfPqA").single()
        assertTrue(artist.isProfile)
        // Artist embeds exist, so playback still works.
        assertNotNull(artist.playerUrl())
        assertEquals(
            "https://open.spotify.com/artist/4cOdonKdQq7vF2eXyQfPqA",
            artist.resolveUrl()
        )
    }

    @Test
    fun videoAndTrackTokensAreNotProfiles() {
        val video = findMediaReferenceTokens("@youtube:dQw4w9WgXcQ").single()
        assertFalse(video.isProfile)
        val track = findMediaReferenceTokens("@spotify:track:4cOdonKdQq7vF2eXyQfPqA").single()
        assertFalse(track.isProfile)
        // SoundCloud links are never profiles either.
        val sc = findMediaReferenceTokens(
            "@soundcloud:https://soundcloud.com/artist/track"
        ).single()
        assertFalse(sc.isProfile)
    }

    @Test
    fun profileFetchShortCircuitsForNonProfilesWithoutNetwork() {
        val video = findMediaReferenceTokens("@youtube:dQw4w9WgXcQ").single()
        assertEquals(null, kotlinx.coroutines.runBlocking { fetchProfileInfo(video) })
        val track = findMediaReferenceTokens("@spotify:track:4cOdonKdQq7vF2eXyQfPqA").single()
        assertEquals(null, kotlinx.coroutines.runBlocking { fetchProfileInfo(track) })
    }

    @Test
    fun htmlEntitiesAreDecoded() {
        assertEquals("Tom & Jerry", decodeHtmlEntities("Tom &amp; Jerry"))
        assertEquals("Rock 'n' Roll", decodeHtmlEntities("Rock &#39;n&#39; Roll"))
        assertEquals("10 < 20", decodeHtmlEntities("10 &lt; 20"))
        assertEquals("Say \"hi\"", decodeHtmlEntities("Say &quot;hi&quot;"))
        assertEquals("a\u00e9b", decodeHtmlEntities("a&#233;b"))
        // Unknown entities and stray ampersands pass through untouched.
        assertEquals("a &bogus; c", decodeHtmlEntities("a &bogus; c"))
        assertEquals("rock & roll", decodeHtmlEntities("rock & roll"))
    }

    @Test
    fun parseProfileMetaExtractsNameAndAvatar() {
        // YouTube channel pages: `property` first, avatar URL with a
        // `\u0026` escape.
        val html = """
            <html><head>
            <meta property="og:title" content="BibleProject" />
            <meta property="og:image" content="https://yt3.ggpht.com/avatar.png?s=900\u0026k=x" />
            </head></html>
        """.trimIndent()
        val info = parseProfileMeta(html)
        assertEquals("BibleProject", info.name)
        assertEquals("https://yt3.ggpht.com/avatar.png?s=900&k=x", info.avatarUrl)
    }

    @Test
    fun parseProfileMetaStripsServiceSuffixFromName() {
        // YouTube appends " - YouTube" to the channel's og:title; the
        // card should show the bare channel name (this also flows into
        // the cached info, so the popup agrees).
        val html = """<meta property="og:title" content="Josia Queen - YouTube" />"""
        assertEquals("Josia Queen", parseProfileMeta(html).name)

        // Spotify artist pages can carry the same decoration.
        val spotify = """<meta property="og:title" content="Some Artist - Spotify" />"""
        assertEquals("Some Artist", parseProfileMeta(spotify).name)

        // A channel genuinely named "Foo - YouTube" keeps just its name.
        assertEquals(
            "Foo",
            cleanProfileName("Foo - YouTube")
        )
        // No suffix → untouched.
        assertEquals("Plain Name", cleanProfileName("Plain Name"))
    }

    @Test
    fun parseProfileMetaHandlesAttributeOrderAndEntities() {
        // `content` before `property`, entity-encoded title.
        val html = """<meta content="John &amp; Jane" property="og:title">"""
        val info = parseProfileMeta(html)
        assertEquals("John & Jane", info.name)
        assertNull(info.avatarUrl)
    }

    @Test
    fun parseProfileMetaMissingTagsReturnNulls() {
        val info = parseProfileMeta("<html><head><title>no og tags</title></head></html>")
        assertNull(info.name)
        assertNull(info.avatarUrl)
        assertNull(info.followerCount)
        assertNull(info.badge)
    }

    @Test
    fun parseProfileMetaDetectsOfficialArtistBadge() {
        // Official Artist Channels carry the artist marker in their
        // embedded ytInitialData JSON.
        val html = """<script>var ytInitialData = {"header":{"c4TabbedHeaderRenderer":{
            |"badges":[{"metadataBadgeRenderer":{"style":"BADGE_STYLE_TYPE_VERIFIED_ARTIST"}}]}}};</script>""".trimMargin()
        val info = parseProfileMeta(html)
        assertEquals("Official Artist", info.badge)
    }

    @Test
    fun parseProfileMetaDetectsVerifiedBadge() {
        // Plain verified channels: the CSS class in the no-JS fallback
        // (or the VERIFIED style in the JSON).
        val html = """<html><body><span class="badge-style-type-verified"></span></body></html>"""
        val info = parseProfileMeta(html)
        assertEquals("Verified", info.badge)
    }

    @Test
    fun parseProfileMetaDetectsVerifiedBadgeFromJson() {
        // The JSON-style VERIFIED marker (ytInitialData), not the CSS
        // class — closes the gap to the artist test above.
        val html = """<script>var ytInitialData = {"badges":[
            |{"metadataBadgeRenderer":{"style":"BADGE_STYLE_TYPE_VERIFIED"}}]};</script>""".trimMargin()
        assertEquals("Verified", parseProfileMeta(html).badge)
    }

    @Test
    fun parseProfileMetaArtistBadgeWinsOverVerified() {
        // The artist marker contains "VERIFIED" as a substring, so the
        // artist check must run first and win.
        val html = """<script>var ytInitialData = {"badges":[
            |{"metadataBadgeRenderer":{"style":"BADGE_STYLE_TYPE_VERIFIED_ARTIST"}},
            |{"metadataBadgeRenderer":{"style":"BADGE_STYLE_TYPE_VERIFIED"}}]};</script>""".trimMargin()
        assertEquals("Official Artist", parseProfileMeta(html).badge)
    }

    @Test
    fun detectProfileBadgeIsNullForUnverifiedOrNonYouTube() {
        assertNull(detectProfileBadge("<html><body>no markers here</body></html>"))
        // A Spotify artist page has no YouTube badge markers.
        assertNull(detectProfileBadge("""<meta property="og:title" content="Some Artist · Artist">"""))
    }

    @Test
    fun fetchProfileInfoServesCachedProfileWithoutNetwork() = runBlocking {
        // Seed the session cache with a deterministic payload for a real
        // profile token's URL — [fetchProfileInfo] must return it without
        // touching the network (a live scrape of the fake URL could never
        // produce this exact name / badge).
        ProfileInfoCache.clearForTests()
        try {
            val token = findMediaReferenceTokens("@youtube:@TestHandle").first()
            val url = token.resolveUrl()!!
            ProfileInfoCache.put(
                url,
                MediaProfileInfo(
                    name = "Cached Channel",
                    avatarUrl = "https://example.com/avatar.png",
                    followerCount = "9.99K subscribers",
                    badge = "Verified"
                )
            )
            val result = fetchProfileInfo(token)
            assertEquals("Cached Channel", result?.name)
            assertEquals("9.99K subscribers", result?.followerCount)
            assertEquals("Verified", result?.badge)
        } finally {
            ProfileInfoCache.clearForTests()
        }
    }

    @Test
    fun profileInfoCacheEvictsOldestBeyondCap() {
        ProfileInfoCache.clearForTests()
        try {
            repeat(70) { i ->
                ProfileInfoCache.put("https://example.com/channel/$i", null)
            }
            // Access-ordered LRU cap (64): the oldest entries are gone,
            // the newest survive.
            assertFalse(ProfileInfoCache.isCached("https://example.com/channel/0"))
            assertFalse(ProfileInfoCache.isCached("https://example.com/channel/5"))
            assertTrue(ProfileInfoCache.isCached("https://example.com/channel/69"))
        } finally {
            ProfileInfoCache.clearForTests()
        }
    }

    // ------------------------------------------------------------------
    // Media title cache — chips show the media's TITLE, not the link
    // ------------------------------------------------------------------

    @Test
    fun fetchMediaTitleReturnsNullForLinksWithoutNetwork() {
        MediaTitleCache.clearForTests()
        try {
            // Generic links have no oEmbed title — short-circuits before
            // any network call and the null is cached so the editor's
            // per-keystroke re-filter never re-fetches.
            val link = findMediaReferenceTokens("@url:https://example.com/post/123").single()
            assertEquals(null, kotlinx.coroutines.runBlocking { fetchMediaTitle(link) })
            assertTrue(MediaTitleCache.isCached(link.resolveUrl()!!))
        } finally {
            MediaTitleCache.clearForTests()
        }
    }

    @Test
    fun fetchMediaTitleUsesFileNameForLocalFilesWithoutNetwork() {
        MediaTitleCache.clearForTests()
        try {
            createMediaFile("photo-abc123.png")
            val token = findMediaReferenceTokens("@file:media/photo-abc123.png").single()
            // File name is the label (files carry no metadata title).
            assertEquals(
                "photo-abc123.png",
                kotlinx.coroutines.runBlocking { fetchMediaTitle(token) }
            )
        } finally {
            MediaTitleCache.clearForTests()
        }
    }

    @Test
    fun fetchMediaTitleServesCachedTitleWithoutNetwork() {
        // Seed the session cache with a deterministic payload for a real
        // token's URL — fetchMediaTitle must return it without touching
        // the network (a live oEmbed of the fake URL could never produce
        // this exact title).
        MediaTitleCache.clearForTests()
        try {
            val token = findMediaReferenceTokens("@youtube:dQw4w9WgXcQ").single()
            val url = token.resolveUrl()!!
            MediaTitleCache.put(url, "Never Gonna Give You Up")
            assertEquals(
                "Never Gonna Give You Up",
                kotlinx.coroutines.runBlocking { fetchMediaTitle(token) }
            )
        } finally {
            MediaTitleCache.clearForTests()
        }
    }

    @Test
    fun mediaTitleCacheEvictsOldestBeyondCap() {
        MediaTitleCache.clearForTests()
        try {
            repeat(140) { i ->
                MediaTitleCache.put("https://example.com/media/$i", null)
            }
            // Access-ordered LRU cap (128): the oldest entries are gone,
            // the newest survive.
            assertFalse(MediaTitleCache.isCached("https://example.com/media/0"))
            assertFalse(MediaTitleCache.isCached("https://example.com/media/5"))
            assertTrue(MediaTitleCache.isCached("https://example.com/media/139"))
        } finally {
            MediaTitleCache.clearForTests()
        }
    }

    @Test
    fun parseProfileMetaExtractsFollowerCountFromDescription() {
        // Spotify artists advertise their listeners in the description
        // meta (`name` before `content`).
        val html = """<meta name="description" content="Artist · 2.4M monthly listeners" />"""
        assertEquals("2.4M monthly listeners", parseProfileMeta(html).followerCount)

        // og:description works too, with an entity-encoded number.
        val og = """<meta property="og:description" content="@Handle has 1.23M subscribers &amp; uploads weekly" />"""
        assertEquals("1.23M subscribers", parseProfileMeta(og).followerCount)
    }

    @Test
    fun parseProfileMetaExtractsYouTubeSubscriberJson() {
        // Real ytInitialData emits an `accessibility` block BEFORE
        // `simpleText` — the regex must span it.
        val html = """
            <meta property="og:title" content="BibleProject" />
            var ytInitialData = {"header":{"subscriberCountText":{"accessibility":{"accessibilityData":{"label":"1.23M subscribers"}},"simpleText":"1.23M subscribers"}}};
        """.trimIndent()
        val info = parseProfileMeta(html)
        assertEquals("BibleProject", info.name)
        assertEquals("1.23M subscribers", info.followerCount)
    }

    @Test
    fun parseProfileMetaFallsBackToInteractionCount() {
        val html = """<meta itemprop="interactionCount" content="UserSubscriptions:1234567">"""
        assertEquals("1,234,567 subscribers", parseProfileMeta(html).followerCount)
    }

    @Test
    fun extractFollowerCountParsesVariants() {
        assertEquals("12,345 followers", extractFollowerCount("Listen to John on Spotify. 12,345 followers."))
        assertEquals("500 subscribers", extractFollowerCount("@Handle has 500 subscribers, uploads weekly"))
        assertEquals("3K followers", extractFollowerCount("3K followers and counting"))
        // Singular counts keep their singular wording (verbatim match).
        assertEquals("1 follower", extractFollowerCount("Only 1 follower so far."))
        assertEquals("1 monthly listener", extractFollowerCount("Artist · 1 monthly listener"))
        assertNull(extractFollowerCount("A plain description without any counts."))
        assertNull(extractFollowerCount(null))
        assertNull(extractFollowerCount(""))
    }

    @Test
    fun formatCountAddsThousandsSeparators() {
        assertEquals("0", formatCount(0))
        assertEquals("999", formatCount(999))
        assertEquals("1,234,567", formatCount(1234567))
    }

    // ------------------------------------------------------------------
    // Spotify track artists (og:description scrape)
    // ------------------------------------------------------------------

    @Test
    fun spotifyArtistsFromDescriptionParsesSingleArtist() {
        // "Artist · Album · Song · Year" — the artist is the first segment.
        assertEquals(
            "Rick Astley",
            spotifyArtistsFromDescription(
                "Rick Astley · Whenever You Need Somebody · Song · 1987"
            )
        )
    }

    @Test
    fun spotifyArtistsFromDescriptionParsesCollaboration() {
        // Collaborations are comma-joined before the first separator.
        assertEquals(
            "Shawn Mendes, Camila Cabello",
            spotifyArtistsFromDescription(
                "Shawn Mendes, Camila Cabello · Shawn Mendes (Deluxe) · Song · 2019"
            )
        )
    }

    @Test
    fun spotifyArtistsFromDescriptionRejectsNonTrackShapes() {
        // No " · " separator (e.g. a plain title) → no artists.
        assertNull(spotifyArtistsFromDescription("Never Gonna Give You Up"))
        // A bare artist with no album / song segments isn't the track
        // shape — nothing to separate.
        assertNull(spotifyArtistsFromDescription("Rick Astley"))
        assertNull(spotifyArtistsFromDescription(""))
        assertNull(spotifyArtistsFromDescription("   "))
    }

    @Test
    fun extractSpotifyTrackArtistsReadsOgDescriptionWithEntities() {
        val html = """
            <meta property="og:description" content="Tom &amp; Jerry · Tom &amp; Jerry · Song · 1967" />
        """.trimIndent()
        assertEquals("Tom & Jerry", extractSpotifyTrackArtists(html))
    }

    @Test
    fun extractSpotifyTrackArtistsReturnsNullWithoutOgDescription() {
        assertNull(extractSpotifyTrackArtists("<html><head><title>no meta</title></head></html>"))
        // The plain name="description" meta is NOT the og:description — a
        // Spotify track page always carries og:description, so requiring
        // it keeps non-track pages from leaking a fake artist line.
        assertNull(
            extractSpotifyTrackArtists(
                "<meta name=\"description\" content=\"Listen to Song on Spotify. Song · Rick Astley · 1987\" />"
            )
        )
    }

    @Test
    fun spotifyArtistCacheEvictsOldestBeyondCap() {
        SpotifyArtistCache.clearForTests()
        try {
            repeat(70) { i ->
                SpotifyArtistCache.put("https://open.spotify.com/track/$i", null)
            }
            assertFalse(SpotifyArtistCache.isCached("https://open.spotify.com/track/0"))
            assertFalse(SpotifyArtistCache.isCached("https://open.spotify.com/track/5"))
            assertTrue(SpotifyArtistCache.isCached("https://open.spotify.com/track/69"))
        } finally {
            SpotifyArtistCache.clearForTests()
        }
    }

    @Test
    fun chipTextNeverLongerThanTheSourceTokenIncludesProfiles() {
        val cases = listOf(
            "@youtube:@BibleProject",
            "@youtube:channel/UCdQw4w9WgXcQdQw4w9WgXcQ",
            "@youtube:c/BibleProject",
            "@youtube:user/oldName",
            "@spotify:user:james",
            "@spotify:artist:4cOdonKdQq7vF2eXyQfPqA"
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
