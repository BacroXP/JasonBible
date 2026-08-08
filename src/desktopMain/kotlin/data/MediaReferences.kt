package data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.awt.Desktop
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets


// ---------------------------------------------------------------------------
// Media references: `@service:content` tokens (e.g. `@youtube:dQw4w9WgXcQ`,
// `@spotify:track:4cOdon…`, `@url:https://example.com/…`) render as
// clickable chips in the note editor — the media counterpart to the
// `$Book$C$V` Bible references. Clicking a chip opens the in-app preview
// panel (oEmbed title/thumbnail) with an "Open in browser" action.
// ---------------------------------------------------------------------------

/**
 * A media service known to the reference system. Each service knows how to
 * turn its `@service:content` payload into a canonical URL and (where a
 * public oEmbed endpoint exists) how to fetch a rich preview for the
 * in-app preview panel. Adding a service is a one-line enum entry plus a
 * pair of small builders.
 */
enum class MediaService(
    /** Lowercase token key used in the `@key:content` syntax. */
    val key: String,
    /** Human-readable name shown in chips, the picker and the preview. */
    val label: String,
    /** Single emoji rendered at the front of every chip. */
    val emoji: String,
    private val urlFor: (String) -> String?,
    private val oEmbedFor: ((String) -> String)? = null
) {
    YOUTUBE("youtube", "YouTube", "\u25B6\uFE0F", ::youtubeUrl, ::youtubeOEmbed),
    VIMEO("vimeo", "Vimeo", "\uD83C\uDFAC", ::vimeoUrl, ::vimeoOEmbed),
    SPOTIFY("spotify", "Spotify", "\uD83C\uDFB5", ::spotifyUrl, ::spotifyOEmbed),
    SOUNDCLOUD("soundcloud", "SoundCloud", "\uD83C\uDFA7", ::soundcloudUrl, ::soundcloudOEmbed),
    LINK("url", "Link", "\uD83D\uDD17", ::linkUrl, null);

    /** Resolve `@key:content` to a canonical, openable URL, or null when
     *  the payload doesn't describe a valid reference for this service. */
    fun buildUrl(content: String): String? = urlFor(content.trim())

    /** Public oEmbed endpoint for a resolved URL, or null when the service
     *  has none (generic links get no rich preview). */
    fun oEmbedUrl(resolvedUrl: String): String? = oEmbedFor?.invoke(resolvedUrl)

    companion object {
        /** Service for a token key (case-insensitive), or null. */
        fun forKey(key: String): MediaService? =
            entries.firstOrNull { it.key.equals(key, ignoreCase = true) }
    }
}


/**
 * An inline media reference token found in running text — e.g. the
 * `@youtube:dQw4w9WgXcQ` inside "Watch @youtube:dQw4w9WgXcQ today".
 * [sourceStart] / [sourceEnd] are UTF-16 char offsets into the scanned
 * string (end exclusive). Only VALID references become tokens: an unknown
 * service key or an unparseable payload stays plain text.
 */
data class MediaReferenceToken(
    val service: MediaService,
    val content: String,
    val sourceStart: Int,
    val sourceEnd: Int
) {
    /** Canonical URL this token points at, or null if it can't be opened. */
    fun resolveUrl(): String? = service.buildUrl(content)

    /**
     * Synthesised chip label — emoji + service name, plus the short id /
     * domain when it fits. The editor displays exactly this text with the
     * whole source token hidden behind it, so the chip is guaranteed NO
     * LONGER than the source token (that invariant keeps the offset
     * mapping exact: every click inside the chip maps back into the
     * token's source range, so tap/hover still resolve).
     */
    fun chipText(): String {
        val url = resolveUrl() ?: return ""
        val base = "${service.emoji} ${service.label}"
        val tokenLen = sourceEnd - sourceStart
        val extra = when {
            service == MediaService.LINK || content.startsWith("http") ->
                " " + domainOf(content)

            service == MediaService.SPOTIFY && content.contains(':') ->
                " " + content.substringBefore(':')

            else -> " " + content
        }
        if (base.length + extra.length <= tokenLen) return base + extra
        val room = tokenLen - base.length
        if (room <= 0) return base
        return base + extra.take(room)
    }
}


/** A `@service:content` token: service key directly after `@`, then a
 *  colon, then the non-space payload (may itself contain colons, e.g.
 *  `@spotify:track:ID`, but no whitespace or further `@`). */
private val MEDIA_TOKEN_REGEX = Regex("@([a-zA-Z][a-zA-Z0-9]{0,15}):([^\\s@]+)")

private val YOUTUBE_ID = Regex("[A-Za-z0-9_-]{11}")
private val SPOTIFY_ID = Regex("[A-Za-z0-9]{22}")
private val SPOTIFY_TYPES = setOf("track", "album", "playlist", "episode", "artist")

// Sentence-ending punctuation that is NOT part of the reference. Unlike the
// Bible tokenizer we must allow `:` (Spotify type prefixes) and `-`/`_`
// (YouTube ids) INSIDE content, so only trailing punctuation is stripped.
private val TRAILING_PUNCTUATION =
    ",.;:!?)]}\"'»«„“”".toCharArray().toSet()


/**
 * Find `@service:content` tokens embedded anywhere in [content] — inline
 * in a sentence ("Watch @youtube:dQw4w9WgXcQ today") or as a whole line.
 * A token ends at whitespace; trailing sentence punctuation is stripped
 * from the payload ("@youtube:ID." → id "ID"). Only tokens for known
 * services whose payload resolves to a real URL are returned.
 */
fun findMediaReferenceTokens(content: String): List<MediaReferenceToken> {
    val result = mutableListOf<MediaReferenceToken>()
    for (match in MEDIA_TOKEN_REGEX.findAll(content)) {
        val service = MediaService.forKey(match.groupValues[1]) ?: continue
        val raw = match.groupValues[2].trimEnd(*TRAILING_PUNCTUATION.toCharArray())
        if (raw.isEmpty()) continue
        if (service.buildUrl(raw) == null) continue
        val start = match.range.first
        // Recompute the end so stripped trailing punctuation stays outside
        // the token (it belongs to the surrounding sentence).
        val end = start + 1 + match.groupValues[1].length + 1 + raw.length
        result.add(MediaReferenceToken(service, raw, start, end))
    }
    return result
}


// ---------------------------------------------------------------------------
// Per-service URL builders
// ---------------------------------------------------------------------------

private fun isHttp(url: String) = url.startsWith("http://") || url.startsWith("https://")

private fun youtubeUrl(content: String): String? {
    if (isHttp(content)) {
        return content.takeIf {
            it.contains("youtube.com") || it.contains("youtu.be")
        }
    }
    // `id` or `id?t=…` — keep any query params (e.g. a timestamp).
    val q = content.indexOf('?')
    val id = if (q == -1) content else content.substring(0, q)
    if (!YOUTUBE_ID.matches(id)) return null
    val suffix = if (q != -1) "&" + content.substring(q + 1) else ""
    return "https://www.youtube.com/watch?v=$id$suffix"
}

private fun vimeoUrl(content: String): String? {
    if (isHttp(content)) return content.takeIf { it.contains("vimeo.com") }
    return content.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
        ?.let { "https://vimeo.com/$it" }
}

private fun spotifyUrl(content: String): String? {
    if (isHttp(content)) return content.takeIf { it.contains("spotify.com") }
    val (type, id) = if (content.contains(':')) {
        content.substringBefore(':') to content.substringAfter(':')
    } else {
        "track" to content
    }
    if (type !in SPOTIFY_TYPES) return null
    if (!SPOTIFY_ID.matches(id)) return null
    return "https://open.spotify.com/$type/$id"
}

private fun soundcloudUrl(content: String): String? {
    if (isHttp(content)) return content.takeIf { it.contains("soundcloud.com") }
    return null
}

private fun linkUrl(content: String): String? {
    if (!isHttp(content)) return null
    return runCatching { URI(content) }.getOrNull()?.takeIf { it.host != null }?.toString()
        ?: content
}


// ---------------------------------------------------------------------------
// oEmbed preview endpoints (all public, no API keys)
// ---------------------------------------------------------------------------

private fun urlEncode(url: String): String =
    URLEncoder.encode(url, StandardCharsets.UTF_8.name())

private fun youtubeOEmbed(url: String) =
    "https://www.youtube.com/oembed?url=${urlEncode(url)}&format=json"

private fun vimeoOEmbed(url: String) =
    "https://vimeo.com/api/oembed.json?url=${urlEncode(url)}"

private fun spotifyOEmbed(url: String) =
    "https://open.spotify.com/oembed?url=${urlEncode(url)}"

private fun soundcloudOEmbed(url: String) =
    "https://soundcloud.com/oembed?format=json&url=${urlEncode(url)}"


/** Host of a URL with a leading `www.` stripped, for chip display. */
private fun domainOf(url: String): String {
    val host = runCatching { URI(url).host }.getOrNull()
    return host?.removePrefix("www.") ?: url
}


// ---------------------------------------------------------------------------
// Rich preview (oEmbed) + external opening
// ---------------------------------------------------------------------------

/** Title / thumbnail / author for a media reference, from its oEmbed
 *  endpoint. All fields nullable — any endpoint may omit one. */
data class MediaPreviewInfo(
    val title: String? = null,
    val thumbnailUrl: String? = null,
    val author: String? = null
)


/**
 * Read an input stream up to [maxBytes], stopping early once the cap is
 * reached. Shared by the oEmbed fetch and the thumbnail fetch so neither
 * can balloon memory on a misbehaving endpoint.
 */
internal fun readCapped(input: java.io.InputStream, maxBytes: Int): ByteArray {
    val buffer = java.io.ByteArrayOutputStream()
    val chunk = ByteArray(8192)
    var total = 0
    while (total < maxBytes) {
        val n = input.read(chunk)
        if (n <= 0) break
        buffer.write(chunk, 0, n)
        total += n
    }
    return buffer.toByteArray()
}

/** Largest body the preview fetcher will accept (oEmbed responses are a
 *  few KB; thumbnails are capped by their own reader). */
private const val MAX_OEMBED_BYTES = 256 * 1024


/**
 * Fetch a rich preview for a media token over the network. Runs on
 * [Dispatchers.IO]; returns null on ANY failure (offline, unknown link,
 * malformed response) so the preview panel can degrade gracefully to a
 * plain "no preview available" card.
 */
object MediaPreviewFetcher {

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class OEmbed(
        val title: String? = null,
        val thumbnail_url: String? = null,
        val author_name: String? = null
    )

    suspend fun fetch(token: MediaReferenceToken): MediaPreviewInfo? {
        val url = token.resolveUrl() ?: return null
        val endpoint = token.service.oEmbedUrl(url) ?: return null
        return withContext(Dispatchers.IO) {
            runCatching {
                val conn = URL(endpoint).openConnection() as HttpURLConnection
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                conn.setRequestProperty("User-Agent", "BibleApp/1.0")
                val body = conn.inputStream.use { stream ->
                    readCapped(stream, MAX_OEMBED_BYTES).toString(Charsets.UTF_8)
                }
                val o = json.decodeFromString<OEmbed>(body)
                MediaPreviewInfo(
                    title = o.title,
                    thumbnailUrl = o.thumbnail_url,
                    author = o.author_name
                )
            }.getOrNull()
        }
    }
}


/**
 * Open a URL in the default OS browser. Falls back to `xdg-open` on
 * systems where AWT desktop browsing is unavailable; swallows failures so
 * a broken link can never crash the app.
 */
fun openExternalUrl(url: String) {
    if (url.isBlank()) return
    runCatching {
        val uri = URI(url)
        if (Desktop.isDesktopSupported() &&
            Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)
        ) {
            Desktop.getDesktop().browse(uri)
        } else {
            ProcessBuilder("xdg-open", url).start()
        }
    }
}
