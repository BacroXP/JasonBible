package data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets


private val YT_SEARCH_JSON = Json { ignoreUnknownKeys = true }


// ---------------------------------------------------------------------------
// Media reference autofill — search
//
// While the note editor's caret sits on an `@Phrase` (e.g. `@Josia`), the
// editor searches the web for matching channels / videos and offers them
// as suggestions. Picking one inserts the correct `@youtube:@Handle` or
// `@youtube:videoId` token. The search runs against YouTube's PUBLIC
// results page (the same `ytInitialData` JSON the website renders), so no
// API key is needed; every network call degrades to an empty result list
// on failure (offline, bot-check, layout change), leaving the editor
// untouched. Parsing lives here as pure functions so the suite can test
// it against a fixed fixture without the network.
// ---------------------------------------------------------------------------

/** What a search hit points at — a channel/profile page or a playable
 *  video. The token built from a hit differs accordingly (a channel needs
 *  its `@Handle` / channel id; a video its video id). */
enum class MediaSearchKind {
    CHANNEL,
    VIDEO
}

/** One search hit offered in the editor's media autofill. [tokenContent]
 *  is the `@service:content` payload that resolves to the item, e.g.
 *  `@JosiaQueen` (channel handle) or a YouTube video id. */
data class MediaSearchResult(
    val service: MediaService,
    val kind: MediaSearchKind,
    /** Display title — channel name for a channel, video title otherwise. */
    val title: String,
    /** Secondary line: channel name for a video, subscriber text / kind
     *  for a channel. May be empty. */
    val subtitle: String = "",
    /** Exact token payload to insert after `@service:`. */
    val tokenContent: String
)

/** Maximum number of suggestions offered (the results page lists many
 *  more; the bar shows the best few). */
internal const val MAX_MEDIA_SUGGESTIONS = 6

/** Connect / read timeout for the search scrape. */
private const val SEARCH_TIMEOUT_MILLIS = 5000

/** Largest body the search scrape will accept — results pages are
 *  megabyte-scale HTML like the profile pages. */
private const val MAX_SEARCH_BYTES = 2 * 1024 * 1024


/**
 * Pull the `var ytInitialData = {...};` JSON object out of a YouTube
 * results-page HTML body. Bracket counting is STRING-AWARE (braces inside
 * JSON string values are skipped), so the closing brace is found reliably
 * even when the embedded JSON contains `{` / `}` in text fields. Returns
 * null when the page carries no such assignment (bot-check / consent
 * redirect / layout change). Extracted from [searchYouTube] so it is
 * testable offline.
 */
internal fun extractYTInitialData(body: String): String? {
    var marker = body.indexOf("ytInitialData")
    while (marker != -1) {
        // Must be an assignment, not a string mention like "ytInitialData".
        val before = body.getOrNull(marker - 1)
        if (before == null || before.isWhitespace() || before == '=' || before == '(') {
            val open = body.indexOf('{', marker)
            if (open != -1) {
                var depth = 0
                var inString = false
                var escaped = false
                for (i in open ..< body.length) {
                    val c = body[i]
                    if (inString) {
                        if (escaped) escaped = false
                        else if (c == '\\') escaped = true
                        else if (c == '"') inString = false
                    } else {
                        when (c) {
                            '"' -> inString = true
                            '{' -> depth++
                            '}' -> {
                                depth--
                                if (depth == 0) return body.substring(open, i + 1)
                            }
                        }
                    }
                }
            }
        }
        marker = body.indexOf("ytInitialData", marker + 1)
    }
    return null
}


/** Parse one search hit out of a YouTube `videoRenderer` object. */
private fun parseSearchVideo(video: JsonObject): MediaSearchResult? {
    val id = video["videoId"]?.jsonPrimitive?.contentOrNull ?: return null
    val title = video["title"]?.jsonObject
        ?.get("runs")?.jsonArray?.firstOrNull()?.jsonObject
        ?.get("text")?.jsonPrimitive?.contentOrNull ?: return null
    val channel = video["longBylineText"]?.jsonObject
        ?.get("runs")?.jsonArray?.firstOrNull()?.jsonObject
        ?.get("text")?.jsonPrimitive?.contentOrNull.orEmpty()
    return MediaSearchResult(
        service = MediaService.YOUTUBE,
        kind = MediaSearchKind.VIDEO,
        title = decodeHtmlEntities(title),
        // Channel names can carry HTML entities too ("A &amp; B").
        subtitle = decodeHtmlEntities(channel),
        tokenContent = id
    )
}


/** Parse one search hit out of a YouTube `channelRenderer` object. The
 *  token payload prefers the canonical `@Handle` (from
 *  `navigationEndpoint.browseEndpoint.canonicalBaseUrl`) and falls back
 *  to `channel/<id>` when the page omits it. */
private fun parseSearchChannel(channel: JsonObject): MediaSearchResult? {
    val id = channel["channelId"]?.jsonPrimitive?.contentOrNull ?: return null
    val title = channel["title"]?.jsonObject
        ?.get("simpleText")?.jsonPrimitive?.contentOrNull
        ?: channel["title"]?.jsonObject?.get("runs")?.jsonArray
            ?.firstOrNull()?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull
        ?: return null
    val handle = channel["navigationEndpoint"]?.jsonObject
        ?.get("browseEndpoint")?.jsonObject
        ?.get("canonicalBaseUrl")?.jsonPrimitive?.contentOrNull
    val subscribers = channel["subscriberCountText"]?.jsonObject
        ?.get("simpleText")?.jsonPrimitive?.contentOrNull.orEmpty()
    return MediaSearchResult(
        service = MediaService.YOUTUBE,
        kind = MediaSearchKind.CHANNEL,
        title = decodeHtmlEntities(title),
        subtitle = subscribers.ifBlank { "Channel" },
        tokenContent = handle?.removePrefix("/")?.takeIf { it.isNotEmpty() }
            ?: "channel/$id"
    )
}


/**
 * Walk a YouTube search `ytInitialData` JSON document and extract the
 * channel / video hits from its `itemSectionRenderer` contents. Unknown
 * item types (shelves, continuations, shorts carousels, …) are skipped;
 * malformed entries degrade to being skipped too. Capped at
 * [MAX_MEDIA_SUGGESTIONS] so a huge page can never flood the editor.
 * Pure — no network — so it is fully testable offline.
 */
internal fun parseYouTubeSearchResults(jsonText: String): List<MediaSearchResult> {
    val results = mutableListOf<MediaSearchResult>()
    runCatching {
        val root = YT_SEARCH_JSON
            .parseToJsonElement(jsonText).jsonObject
        val sections = root["contents"]?.jsonObject
            ?.get("twoColumnSearchResultsRenderer")?.jsonObject
            ?.get("primaryContents")?.jsonObject
            ?.get("sectionListRenderer")?.jsonObject
            ?.get("contents") as? JsonArray ?: return@runCatching
        for (section in sections) {
            val items = section.jsonObject["itemSectionRenderer"]?.jsonObject
                ?.get("contents") as? JsonArray ?: continue
            for (item in items) {
                val obj = item.jsonObject
                val hit = when {
                    obj["videoRenderer"] != null -> parseSearchVideo(obj["videoRenderer"]!!.jsonObject)
                    obj["channelRenderer"] != null -> parseSearchChannel(obj["channelRenderer"]!!.jsonObject)
                    else -> null
                }
                if (hit != null) {
                    results.add(hit)
                    if (results.size >= MAX_MEDIA_SUGGESTIONS) return results
                }
            }
        }
    }
    return results
}


/**
 * Search YouTube for [query] and return matching channels / videos. Runs
 * on [Dispatchers.IO]; returns an EMPTY list on ANY failure (offline,
 * bot-check page, consent redirect, malformed response) so the editor's
 * autofill bar simply doesn't appear. No API key — the public results
 * page is scraped, exactly like the existing profile-page scrapes.
 */
suspend fun searchYouTube(query: String): List<MediaSearchResult> {
    val trimmed = query.trim()
    if (trimmed.length < 2) return emptyList()
    return withContext(Dispatchers.IO) {
        val url = "https://www.youtube.com/results?search_query=" +
            URLEncoder.encode(trimmed, StandardCharsets.UTF_8.name())
        runCatching {
            val conn = URI(url).toURL().openConnection() as HttpURLConnection
            conn.connectTimeout = SEARCH_TIMEOUT_MILLIS
            conn.readTimeout = SEARCH_TIMEOUT_MILLIS
            conn.setRequestProperty("User-Agent", APP_USER_AGENT)
            val body = conn.inputStream.use { stream ->
                readCapped(stream, MAX_SEARCH_BYTES).toString(Charsets.UTF_8)
            }
            val json = extractYTInitialData(body) ?: return@runCatching emptyList()
            parseYouTubeSearchResults(json)
        }.getOrElse { emptyList() }
    }
}
