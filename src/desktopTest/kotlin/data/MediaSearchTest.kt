package data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue


/**
 * Tests for the media-autofill search parsing — [extractYTInitialData]
 * (string-aware JSON extraction from a results-page body) and
 * [parseYouTubeSearchResults] (walking the ytInitialData document into
 * channel / video hits). Both are pure functions, exercised with
 * hand-built fixtures so the suite never needs the network.
 */
class MediaSearchTest {

    // ------------------------------------------------------------------
    // extractYTInitialData
    // ------------------------------------------------------------------

    @Test
    fun extractsBalancedJsonFromBody() {
        val body = "<html><script>var ytInitialData = " +
            "{\"contents\": {\"a\": \"x{brace}y\"}};</script></html>"
        assertEquals(
            "{\"contents\": {\"a\": \"x{brace}y\"}}",
            extractYTInitialData(body)
        )
    }

    @Test
    fun returnsNullWhenNoAssignment() {
        assertNull(extractYTInitialData("<html><body>nothing here</body></html>"))
    }

    @Test
    fun ignoresStringMentionsOfTheMarker() {
        // "ytInitialData" inside a string is not an assignment — the scan
        // must skip it and find the real one.
        val body = "<script>var x = \"ytInitialData\";</script>" +
            "<script>var ytInitialData = {\"k\": 1};</script>"
        assertEquals("{\"k\": 1}", extractYTInitialData(body))
    }

    @Test
    fun returnsNullOnUnbalancedJson() {
        val body = "<script>var ytInitialData = {\"a\": 1;</script>"
        assertNull(extractYTInitialData(body))
    }

    // ------------------------------------------------------------------
    // parseYouTubeSearchResults
    // ------------------------------------------------------------------

    private fun videoItem(id: String, title: String, channel: String = "Some Channel") =
        """{"videoRenderer": {"videoId": "$id",
            |"title": {"runs": [{"text": "$title"}]},
            |"longBylineText": {"runs": [{"text": "$channel"}]}}}""".trimMargin()

    private fun channelItem(id: String, name: String, handle: String? = null) =
        """{"channelRenderer": {"channelId": "$id",
            |"title": {"simpleText": "$name"},
            |"navigationEndpoint": {"browseEndpoint": {"canonicalBaseUrl": ${if (handle != null) "\"$handle\"" else "null"}}},
            |"subscriberCountText": {"simpleText": "1.2M subscribers"}}}""".trimMargin()

    private fun wrap(vararg items: String): String =
        """{"contents": {"twoColumnSearchResultsRenderer": {
            |"primaryContents": {"sectionListRenderer": {"contents": [
            |{"itemSectionRenderer": {"contents": [
            |${items.joinToString(",\n")}
            |]}}]}}}}}""".trimMargin()

    @Test
    fun parsesVideosAndChannels() {
        val json = wrap(
            videoItem("dQw4w9WgXcQ", "Never Gonna Give You Up", "Rick Astley"),
            channelItem("UCuAXFkgsw1L7xaCfnd5JJOw", "Josia Queen", "/@JosiaQueen")
        )
        val results = parseYouTubeSearchResults(json)
        assertEquals(2, results.size)

        val video = results[0]
        assertEquals(MediaService.YOUTUBE, video.service)
        assertEquals(MediaSearchKind.VIDEO, video.kind)
        assertEquals("Never Gonna Give You Up", video.title)
        assertEquals("Rick Astley", video.subtitle)
        assertEquals("dQw4w9WgXcQ", video.tokenContent)

        val channel = results[1]
        assertEquals(MediaSearchKind.CHANNEL, channel.kind)
        assertEquals("Josia Queen", channel.title)
        assertEquals("1.2M subscribers", channel.subtitle)
        assertEquals("@JosiaQueen", channel.tokenContent)
    }

    @Test
    fun channelFallsBackToChannelIdWithoutHandle() {
        val json = wrap(channelItem("UCuAXFkgsw1L7xaCfnd5JJOw", "No Handle", handle = null))
        val results = parseYouTubeSearchResults(json)
        assertEquals(1, results.size)
        assertEquals("channel/UCuAXFkgsw1L7xaCfnd5JJOw", results[0].tokenContent)
    }

    @Test
    fun skipsUnknownItemTypes() {
        val json = wrap(
            """{"shelfRenderer": {"title": {"runs": [{"text": "Shorts"}]}}}""",
            videoItem("aaaaaaaaaaa", "Real Video", "Channel X")
        )
        val results = parseYouTubeSearchResults(json)
        assertEquals(1, results.size)
        assertEquals("Real Video", results[0].title)
    }

    @Test
    fun capsAtMaxSuggestions() {
        val items = (1..12).map { n ->
            videoItem("id%011d".format(n), "Video $n")
        }
        val results = parseYouTubeSearchResults(wrap(*items.toTypedArray()))
        assertEquals(MAX_MEDIA_SUGGESTIONS, results.size)
    }

    @Test
    fun returnsEmptyForGarbageInput() {
        assertTrue(parseYouTubeSearchResults("not json").isEmpty())
        assertTrue(parseYouTubeSearchResults("""{"contents": {}}""").isEmpty())
        assertTrue(parseYouTubeSearchResults("").isEmpty())
    }

    @Test
    fun decodesHtmlEntitiesInTitles() {
        val json = wrap(videoItem("bbbbbbbbbbb", "Tom &amp; Jerry — Best of"))
        val results = parseYouTubeSearchResults(json)
        assertEquals(1, results.size)
        assertEquals("Tom & Jerry — Best of", results[0].title)
    }
}
