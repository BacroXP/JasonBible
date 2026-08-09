package data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.awt.Desktop
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.LinkedHashMap


// ---------------------------------------------------------------------------
// Media references: `@service:content` tokens (e.g. `@youtube:dQw4w9WgXcQ`,
// `@spotify:track:4cOdon…`, `@url:https://example.com/…`) render as
// clickable chips in the note editor — the media counterpart to the
// `$Book$C$V` Bible references. Clicking a chip opens the in-app preview
// panel (oEmbed title/thumbnail) with an "Open in browser" action.
// ---------------------------------------------------------------------------

/** User-Agent sent on oEmbed / OpenGraph / duration scrapes (some CDNs
 *  reject the default Java UA). Shared with the thumbnail fetcher in
 *  ui.MediaPreviewPopup. */
internal const val APP_USER_AGENT = "BibleApp/1.0"

/** Connect / read timeout in ms for the network scrapes in this file. */
private const val HTTP_TIMEOUT_MILLIS = 5000

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
    private val urlFor: (String) -> String?,
    private val oEmbedFor: ((String) -> String)? = null
) {
    YOUTUBE("youtube", "YouTube", ::youtubeUrl, ::youtubeOEmbed),
    VIMEO("vimeo", "Vimeo", ::vimeoUrl, ::vimeoOEmbed),
    SPOTIFY("spotify", "Spotify", ::spotifyUrl, ::spotifyOEmbed),
    SOUNDCLOUD("soundcloud", "SoundCloud", ::soundcloudUrl, ::soundcloudOEmbed),
    LINK("url", "Link", ::linkUrl, null),
    // Local media files (images / videos / audio) imported into the
    // app's notes media folder and referenced as `@file:media/name.ext`.
    // The payload is the relative path under the notes directory.
    FILE("file", "File", ::fileUrl, null);

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
     * Player URL for in-app playback of this token, or null when the
     * player controller must resolve a stream / fall back to the browser.
     * YouTube / Vimeo / SoundCloud return null: they're played NATIVELY
     * on a direct stream resolved at play time by the bundled yt-dlp
     * (StreamResolver). Spotify returns its official embed URL (its
     * content has no public direct stream), local files their `file://`
     * URI, and generic links / profiles null (they open in the browser).
     */
    fun playerUrl(): String? = when (service) {
        MediaService.YOUTUBE -> null
        MediaService.VIMEO -> null
        MediaService.SOUNDCLOUD -> null
        MediaService.SPOTIFY -> spotifyEmbedUrl(content)
        MediaService.LINK -> null
        // Local files play in the embedded player via a `file://` URI.
        MediaService.FILE -> fileUrl(content)
    }

    /**
     * Absolute on-disk path of an embedded local file (FILE tokens only),
     * or null when the token isn't a FILE reference or the file is gone.
     */
    fun localPath(): String? =
        if (service == MediaService.FILE) {
            NotesRepository.resolveMediaRef(content)?.toString()
        } else {
            null
        }

    /**
     * Synthesised chip label — service name plus the short id / domain
     * when it fits (the media chip fallback used before a title has been
     * fetched). The editor displays exactly this text with the whole
     * source token hidden behind it, so the chip is guaranteed NO LONGER
     * than the source token (that invariant keeps the offset mapping
     * exact: every click inside the chip maps back into the token's
     * source range, so tap/hover still resolve).
     */
    fun chipText(): String {
        if (resolveUrl() == null) return ""
        val base = service.label
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
 *  `@spotify:track:ID`, and `@` for YouTube handles, e.g.
 *  `@youtube:@BibleProject`, but no whitespace). */
private val MEDIA_TOKEN_REGEX = Regex("@([a-zA-Z][a-zA-Z0-9]{0,15}):([^\\s]+)")

private val YOUTUBE_ID = Regex("[A-Za-z0-9_-]{11}")
// YouTube channel ids are `UC` + 22 base64-ish chars (24 total).
private val YOUTUBE_CHANNEL_ID = Regex("UC[A-Za-z0-9_-]{22}")
private val SPOTIFY_ID = Regex("[A-Za-z0-9]{22}")
private val SPOTIFY_TYPES = setOf("track", "album", "playlist", "episode", "artist", "user")

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
// Channel / profile references
//
// A token is a PROFILE (a YouTube channel or a Spotify user / artist)
// rather than a playable item (video / track / album) when its payload
// names a profile page: YouTube `@Handle`, `channel/UC…`, `c/Name`,
// `user/Name` (or the equivalent URLs); Spotify `user:…` / `artist:…`.
// Profile cards render the account's avatar with its name beside it,
// scraped from the page's OpenGraph meta tags — no API keys needed.
// ---------------------------------------------------------------------------

/** True when the token points at a channel / profile page instead of a
 *  playable item. Profiles have no video/track embed, so they render as
 *  an avatar + name card and open in the browser on click. */
val MediaReferenceToken.isProfile: Boolean
    get() = when (service) {
        MediaService.YOUTUBE -> isYouTubeChannelContent(content)
        MediaService.SPOTIFY -> isSpotifyProfileContent(content)
        else -> false
    }

private fun isYouTubeChannelContent(content: String): Boolean {
    if (isHttp(content)) {
        return Regex("youtube\\.com/(@|channel/|c/|user/)").containsMatchIn(content)
    }
    return content.startsWith("@") || content.startsWith("channel/") ||
        content.startsWith("c/") || content.startsWith("user/")
}

private fun isSpotifyProfileContent(content: String): Boolean {
    if (isHttp(content)) {
        return Regex("spotify\\.com/(user|artist)/").containsMatchIn(content)
    }
    val type = content.substringBefore(':')
    return type == "user" || type == "artist"
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
    // Channel / profile payloads: `@Handle`, `channel/UC…`, `c/Name`,
    // `user/Name`. These have no video id, so they must be recognised
    // BEFORE the 11-char id check.
    if (content.startsWith("@")) {
        val handle = content.removePrefix("@").substringBefore('?')
        return handle.takeIf { it.isNotEmpty() && !it.contains(' ') }
            ?.let { "https://www.youtube.com/@$it" }
    }
    if (content.startsWith("channel/")) {
        val id = content.removePrefix("channel/").substringBefore('?')
        return id.takeIf { YOUTUBE_CHANNEL_ID.matches(it) }
            ?.let { "https://www.youtube.com/channel/$it" }
    }
    if (content.startsWith("c/") || content.startsWith("user/")) {
        val path = content.substringBefore('?')
        val name = path.substringAfter('/')
        return name.takeIf { it.isNotEmpty() && !it.contains(' ') }
            ?.let { "https://www.youtube.com/$path" }
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
    // User ids are legacy usernames (not the 22-char media ids), so they
    // get their own, looser shape.
    val valid = if (type == "user") {
        // Legacy Spotify usernames could contain `.` and `-` too.
        id.isNotEmpty() &&
            id.all { it.isLetterOrDigit() || it == '_' || it == '-' || it == '.' }
    } else {
        SPOTIFY_ID.matches(id)
    }
    if (!valid) return null
    return "https://open.spotify.com/$type/$id"
}

private fun soundcloudUrl(content: String): String? {
    if (isHttp(content)) return content.takeIf { it.contains("soundcloud.com") }
    return null
}

private fun fileUrl(content: String): String? {
    val path = NotesRepository.resolveMediaRef(content) ?: return null
    return runCatching { path.toUri().toString() }.getOrNull()
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


// ---------------------------------------------------------------------------
// In-app playback: player URLs
//
// The in-app player plays media one of two ways. YouTube / Vimeo /
// SoundCloud (and any other service with a public direct stream) are
// played NATIVELY by JavaFX MediaPlayer on a stream resolved by the
// bundled yt-dlp binary (see StreamResolver) — no embed URL exists for
// them, so [MediaReferenceToken.playerUrl] returns null and the player
// controller resolves the stream at play time. Spotify's tracks/albums
// have no public direct stream, so it keeps its official embed URL,
// which the player window loads in a WebView. Local files play natively
// from their `file://` URI. [isPlayable] governs which tokens get a play
// button at all.
// ---------------------------------------------------------------------------

/** True when the service has an in-app player (native stream or embed). */
val MediaService.isPlayable: Boolean
    get() = this != MediaService.LINK

private fun spotifyEmbedUrl(content: String): String? {
    val url = spotifyUrl(content) ?: return null
    val match = Regex("spotify\\.com/(track|album|playlist|episode|artist)/([A-Za-z0-9]{22})")
        .find(url)
        ?: return null
    val type = match.groupValues[1]
    val id = match.groupValues[2]
    return "https://open.spotify.com/embed/$type/$id?autoplay=true"
}


/** Host of a URL with a leading `www.` stripped, for chip display. */
internal fun domainOf(url: String): String {
    val host = runCatching { URI(url).host }.getOrNull()
    return host?.removePrefix("www.") ?: url
}


// ---------------------------------------------------------------------------
// Local media files
//
// `@file:media/name.ext` tokens embed files imported into the notes media
// folder. The file's KIND (image / video / audio) is decided purely by
// extension — no metadata probing — so a dropped file is classified and
// displayed (image shown inline, video / audio played in the embedded
// player) without ever reading its title or artist tags.
// ---------------------------------------------------------------------------

/** The three kinds of local media files a note can embed. */
enum class MediaFileKind { IMAGE, VIDEO, AUDIO }

private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "bmp", "webp")
private val VIDEO_EXTENSIONS = setOf("mp4", "webm", "mkv", "mov", "avi", "m4v")
private val AUDIO_EXTENSIONS = setOf("mp3", "wav", "ogg", "flac", "m4a", "aac", "opus")

/** Kind of a local media file by its file name's extension, or null for
 *  unsupported files (documents, archives, …). Case-insensitive. */
internal fun mediaKindFor(name: String): MediaFileKind? {
    val ext = name.substringAfterLast('.', "").lowercase()
    return when (ext) {
        in IMAGE_EXTENSIONS -> MediaFileKind.IMAGE
        in VIDEO_EXTENSIONS -> MediaFileKind.VIDEO
        in AUDIO_EXTENSIONS -> MediaFileKind.AUDIO
        else -> null
    }
}


// ---------------------------------------------------------------------------
// Rich preview (oEmbed) + external opening
// ---------------------------------------------------------------------------

/** Title / thumbnail / author / runtime for a media reference. Title /
 *  thumbnail / author come from the oEmbed endpoint where available
 *  (runtime best-effort: Vimeo reports seconds, SoundCloud milliseconds;
 *  YouTube's oEmbed omits it and is scraped separately by
 *  [fetchYouTubeDurationSeconds]). Spotify's oEmbed omits author_name, so
 *  for Spotify TRACKS the author is the artists scraped from the track
 *  page's og:description instead. All fields nullable — any endpoint may
 *  omit one. */
data class MediaPreviewInfo(
    val title: String? = null,
    val thumbnailUrl: String? = null,
    val author: String? = null,
    val durationSeconds: Long? = null
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
        val author_name: String? = null,
        val duration: Long? = null
    )

    suspend fun fetch(token: MediaReferenceToken): MediaPreviewInfo? {
        val url = token.resolveUrl() ?: return null
        val endpoint = token.service.oEmbedUrl(url) ?: return null
        return withContext(Dispatchers.IO) {
            val info = runCatching {
                val conn = URI(endpoint).toURL().openConnection() as HttpURLConnection
                conn.connectTimeout = HTTP_TIMEOUT_MILLIS
                conn.readTimeout = HTTP_TIMEOUT_MILLIS
                conn.setRequestProperty("User-Agent", APP_USER_AGENT)
                val body = conn.inputStream.use { stream ->
                    readCapped(stream, MAX_OEMBED_BYTES).toString(Charsets.UTF_8)
                }
                val o = json.decodeFromString<OEmbed>(body)
                MediaPreviewInfo(
                    title = o.title,
                    thumbnailUrl = o.thumbnail_url,
                    author = o.author_name,
                    // Vimeo reports the runtime in seconds; SoundCloud in
                    // milliseconds. YouTube's oEmbed omits it entirely.
                    durationSeconds = when (token.service) {
                        MediaService.VIMEO -> o.duration
                        MediaService.SOUNDCLOUD -> o.duration?.let { it / 1000 }
                        else -> null
                    }
                )
            }.getOrNull() ?: return@withContext null
            // Spotify's oEmbed never reports author_name, so the artists
            // that worked on a TRACK are scraped from its page's
            // og:description as a separate best-effort fetch — a failure
            // only hides the artist line, never the whole preview.
            if (token.service == MediaService.SPOTIFY &&
                info.author.isNullOrBlank() &&
                isSpotifyTrackContent(token.content)
            ) {
                return@withContext info.copy(author = scrapeSpotifyTrackArtists(url))
            }
            info
        }
    }
}


/** Matches the embedded `lengthSeconds` field of YouTube's player config
 *  (ytInitialPlayerResponse JSON inside the watch-page HTML). */
private val YOUTUBE_LENGTH_REGEX = Regex("\"lengthSeconds\":\"(\\d+)\"")

/** Largest body the YouTube duration scrape will accept — the watch page
 *  is a megabyte-scale HTML document and the player config sits deep in
 *  it, so this deliberately exceeds the oEmbed cap. */
private const val MAX_SCRAPE_BYTES = 2 * 1024 * 1024


/**
 * Best-effort runtime (seconds) of a YouTube video, scraped from the
 * watch page's embedded player config — the public oEmbed endpoint does
 * not report duration. Returns null on ANY failure (offline, layout
 * change, region redirect) so the badge can degrade gracefully to
 * hidden. Non-YouTube tokens return null immediately.
 */
suspend fun fetchYouTubeDurationSeconds(token: MediaReferenceToken): Long? {
    if (token.service != MediaService.YOUTUBE) return null
    val url = token.resolveUrl() ?: return null
    return withContext(Dispatchers.IO) {
        runCatching {
            val conn = URI(url).toURL().openConnection() as HttpURLConnection
            conn.connectTimeout = HTTP_TIMEOUT_MILLIS
            conn.readTimeout = HTTP_TIMEOUT_MILLIS
            conn.setRequestProperty("User-Agent", APP_USER_AGENT)
            val body = conn.inputStream.use { stream ->
                readCapped(stream, MAX_SCRAPE_BYTES).toString(Charsets.UTF_8)
            }
            YOUTUBE_LENGTH_REGEX.find(body)?.groupValues?.get(1)?.toLongOrNull()
        }.getOrNull()
    }
}


/** Format a runtime in seconds as `m:ss` or `h:mm:ss` (767 → "12:47",
 *  495 → "8:15"). Used for the duration badge on media cards. */
internal fun formatDurationSeconds(totalSeconds: Long): String {
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}


// ---------------------------------------------------------------------------
// Spotify track artists
//
// Spotify's oEmbed response omits author_name entirely, so the artists
// that worked on a track are scraped from the track page's og:description
// meta instead: "Rick Astley · Whenever You Need Somebody · Song · 1987"
// — the artist list is the first segment before the " · " separator,
// comma-joined for collaborations ("Shawn Mendes, Camila Cabello · …").
// Results are cached per resolved URL like the profile cache, so the
// panel / popup / title chips never re-scrape a track already seen this
// session.
// ---------------------------------------------------------------------------

/** Matches Spotify's `<meta property="og:description" content="…">` tag
 *  regardless of attribute order (Spotify emits `property` first). Group
 *  1 is the raw content value. */
private val SPOTIFY_OG_DESC_REGEX = Regex(
    "<meta(?=[^>]*property=[\"']og:description[\"'])" +
        "(?=[^>]*content=[\"']([^\"']*)[\"'])[^>]*>",
    RegexOption.IGNORE_CASE
)

/** Pull the artist list out of a Spotify track description. The
 *  og:description shape is "Artist[, Artist2] · Album · Song · Year", so
 *  the artists are everything before the first " · " separator. Returns
 *  null when the description doesn't carry that shape (a plain title, a
 *  non-track page) so the caller can hide the artist line gracefully.
 *  Extracted so it is testable offline. */
internal fun spotifyArtistsFromDescription(description: String): String? {
    val trimmed = description.trim()
    val separator = trimmed.indexOf(" · ")
    if (separator <= 0) return null
    return trimmed.substring(0, separator).trim().takeIf { it.isNotEmpty() }
}

/** Extract the artist list from a Spotify track page's HTML (the
 *  og:description meta, entity-decoded), or null when the page doesn't
 *  expose one in the expected shape. Testable offline; applied inside
 *  [scrapeSpotifyTrackArtists]. */
internal fun extractSpotifyTrackArtists(html: String): String? =
    SPOTIFY_OG_DESC_REGEX.find(html)
        ?.groupValues?.get(1)
        ?.let { spotifyArtistsFromDescription(decodeHtmlEntities(it)) }

/** True when a Spotify payload names a TRACK (`track:ID` or a bare ID,
 *  which [spotifyUrl] defaults to a track), not an album / playlist /
 *  episode / artist — only tracks get the artists scrape. */
private fun isSpotifyTrackContent(content: String): Boolean {
    if (isHttp(content)) return Regex("spotify\\.com/track/").containsMatchIn(content)
    val type = content.substringBefore(':')
    return type == "track" || !content.contains(':')
}

/** Session cache for scraped Spotify track artists, mirroring
 *  [ProfileInfoCache]: LRU-capped, access-ordered, failed scrapes cached
 *  as null so a dead / offline track isn't re-scraped on every open. */
internal object SpotifyArtistCache {
    private const val MAX_ENTRIES = 64

    private val cache = object : LinkedHashMap<String, String?>(32, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, String?>
        ): Boolean = size > MAX_ENTRIES
    }

    @Synchronized
    fun get(url: String): String? = cache[url]

    /** True when the URL has a cache entry (even a failed-scrape null). */
    @Synchronized
    fun isCached(url: String): Boolean = cache.containsKey(url)

    @Synchronized
    fun put(url: String, artists: String?) {
        cache[url] = artists
    }

    @Synchronized
    internal fun clearForTests() {
        cache.clear()
    }
}

/** Best-effort artist list of a Spotify track, scraped from the track
 *  page's og:description (see [spotifyArtistsFromDescription]). Returns
 *  null on ANY failure (offline, layout change, region redirect).
 *  Results are cached in [SpotifyArtistCache] per resolved URL. */
private suspend fun scrapeSpotifyTrackArtists(url: String): String? {
    if (SpotifyArtistCache.isCached(url)) return SpotifyArtistCache.get(url)
    val fetched = withContext(Dispatchers.IO) {
        runCatching {
            val conn = URI(url).toURL().openConnection() as HttpURLConnection
            conn.connectTimeout = HTTP_TIMEOUT_MILLIS
            conn.readTimeout = HTTP_TIMEOUT_MILLIS
            conn.setRequestProperty("User-Agent", APP_USER_AGENT)
            val body = conn.inputStream.use { stream ->
                readCapped(stream, MAX_PROFILE_BYTES).toString(Charsets.UTF_8)
            }
            extractSpotifyTrackArtists(body)
        }.getOrNull()
    }
    SpotifyArtistCache.put(url, fetched)
    return fetched
}


// ---------------------------------------------------------------------------
// Channel / profile scraping
// ---------------------------------------------------------------------------

/** Name + avatar + follower/subscriber count + verification badge of a
 *  channel / profile page, scraped from its OpenGraph meta tags
 *  (og:title / og:image) and the page's embedded count / badge markers.
 *  All fields nullable — a page may omit any of them and the card
 *  degrades to the icon + content fallback. */
data class MediaProfileInfo(
    val name: String? = null,
    val avatarUrl: String? = null,
    /** Follower / subscriber count text (e.g. "1.23M subscribers",
     *  "2.4M monthly listeners", "1,234,567 followers"), or null when
     *  the page doesn't expose one. */
    val followerCount: String? = null,
    /** Verification badge text ("Official Artist" / "Verified"), or
     *  null when the page carries no verification marker (YouTube only —
     *  Spotify artist pages expose no static marker). Rendered by the
     *  profile card as a small "✓ …" chip. */
    val badge: String? = null
)

/** Matches a `<meta property="og:title|og:image" content="…">` tag
 *  regardless of attribute order (YouTube emits `property` first,
 *  Spotify sometimes `content` first). Group 1 is the property name,
 *  group 2 the raw content value. */
private val OG_TAG_REGEX = Regex(
    "<meta(?=[^>]*property=[\"']og:(title|image)[\"'])" +
        "(?=[^>]*content=[\"']([^\"']*)[\"'])[^>]*>",
    RegexOption.IGNORE_CASE
)

/** Largest body the profile scrape will accept — profile pages are
 *  megabyte-scale HTML like the YouTube watch page. */
private const val MAX_PROFILE_BYTES = 2 * 1024 * 1024

/** Replace the common HTML entities in scraped meta content. */
internal fun decodeHtmlEntities(text: String): String {
    val out = StringBuilder(text.length)
    var i = 0
    while (i < text.length) {
        val amp = text.indexOf('&', i)
        if (amp == -1) {
            out.append(text, i, text.length)
            break
        }
        out.append(text, i, amp)
        val semi = text.indexOf(';', amp)
        // No terminator, or an implausibly long entity name: keep the `&`.
        if (semi == -1 || semi - amp > 12) {
            out.append('&')
            i = amp + 1
            continue
        }
        val name = text.substring(amp + 1, semi)
        val decoded: String? = when (name) {
            "amp" -> "&"
            "lt" -> "<"
            "gt" -> ">"
            "quot" -> "\""
            "apos" -> "'"
            "nbsp" -> " "
            else -> when {
                name.startsWith("#x") || name.startsWith("#X") ->
                    name.substring(2).toIntOrNull(16)?.let { Char(it).toString() }

                name.startsWith("#") ->
                    name.substring(1).toIntOrNull()?.let { Char(it).toString() }

                else -> null
            }
        }
        if (decoded != null) {
            out.append(decoded)
            i = semi + 1
        } else {
            out.append('&')
            i = amp + 1
        }
    }
    return out.toString()
}


/** Matches a `<meta property="og:description"|name="description" …>`
 *  tag regardless of attribute order (YouTube emits `property` first,
 *  Spotify `name` first). Group 1 is the raw content value. */
private val DESCRIPTION_META_REGEX = Regex(
    "<meta(?=[^>]*(?:property|name)=[\"'](?:og:)?description[\"'])" +
        "(?=[^>]*content=[\"']([^\"']*)[\"'])[^>]*>",
    RegexOption.IGNORE_CASE
)

/** A follower phrase inside a page description: a number (optionally
 *  K / M / B-suffixed or spelled-out thousand/million/billion) followed
 *  by subscribers / followers / listeners — e.g. "2.4M monthly
 *  listeners", "1.23M subscribers", "12,345 followers". Group 1 is the
 *  number, group 2 the unit word. */
private val FOLLOWER_PHRASE_REGEX = Regex(
    "\\b(\\d[\\d.,\\s]*(?:[KMB]|\\s*(?:thousand|million|billion))?)\\s*" +
        "(subscribers?|followers?|monthly listeners?|listeners?)\\b",
    RegexOption.IGNORE_CASE
)

/** Matches the `subscriberCountText` field of YouTube's embedded
 *  ytInitialData JSON. The object carries an `accessibility` block
 *  BEFORE `simpleText` on real pages
 *  (`{"accessibility":{...},"simpleText":"1.23M subscribers"}`), so the
 *  lazy `.*?` spans whatever comes first; it stops at the FIRST
 *  `simpleText` inside the object, which is the count. */
private val YT_SUBSCRIBER_TEXT_REGEX = Regex(
    "\"subscriberCountText\"\\s*:\\s*\\{.*?\"simpleText\"\\s*:\\s*\"([^\"]+)\"",
    RegexOption.DOT_MATCHES_ALL
)

/** Matches YouTube's raw `<meta itemprop="interactionCount"
 *  content="UserSubscriptions:1234567">` count (no attribute-order
 *  constraint — the number is unique enough on its own). */
private val USER_SUBSCRIPTIONS_REGEX = Regex("UserSubscriptions:(\\d+)")


// Verification markers inside a channel page. YouTube carries the badge
// twice: as a `BADGE_STYLE_TYPE_VERIFIED[_ARTIST]` style string in the
// embedded ytInitialData JSON and as a `badge-style-type-verified…` CSS
// class in the no-JS HTML fallback. ARTIST is checked first because its
// marker contains "VERIFIED" as a substring of "VERIFIED_ARTIST".
private const val YT_ARTIST_BADGE_MARKER = "BADGE_STYLE_TYPE_VERIFIED_ARTIST"
private const val YT_VERIFIED_BADGE_MARKER = "BADGE_STYLE_TYPE_VERIFIED"
private const val YT_ARTIST_BADGE_CLASS = "badge-style-type-verified-artist"
private const val YT_VERIFIED_BADGE_CLASS = "badge-style-type-verified"

/** Verification badge text for a channel page body, or null when the
 *  page carries no marker (unverified channel, or a non-YouTube profile
 *  that happens to be scraped here). Extracted so it is testable offline.
 *  NOTE: the marker strings are YouTube-internal and rare, so scanning
 *  the whole body is safe in practice — but a page could in theory embed
 *  a DIFFERENT entity's badge (e.g. a featured channel) inside its
 *  ytInitialData; scoping the scan to the channel header would make it
 *  exact at the cost of layout fragility. */
internal fun detectProfileBadge(html: String): String? = when {
    html.contains(YT_ARTIST_BADGE_MARKER) || html.contains(YT_ARTIST_BADGE_CLASS) ->
        "Official Artist"

    html.contains(YT_VERIFIED_BADGE_MARKER) || html.contains(YT_VERIFIED_BADGE_CLASS) ->
        "Verified"

    else -> null
}


/** Format a raw follower count with thousands separators
 *  (1234567 → "1,234,567"). */
internal fun formatCount(n: Long): String =
    n.toString().reversed().chunked(3).joinToString(",").reversed()


/** Service suffixes profile pages append to their og:title — YouTube
 *  emits "ChannelName - YouTube" — that are stripped from the displayed
 *  name so the card shows the bare channel / artist name. Case-insensitive
 *  and only matched at the very end, so a channel genuinely named
 *  "Foo - YouTube" loses just the decoration. */
private val PROFILE_TITLE_SUFFIXES = listOf(
    " - YouTube",
    " - Spotify",
    " - Vimeo",
    " - SoundCloud"
)


/** Remove a trailing service suffix from a scraped profile name
 *  ("Josia Queen - YouTube" → "Josia Queen"). Extracted so it is
 *  testable offline; applied inside [parseProfileMeta] so every
 *  consumer (panel cards, preview popup, cache) sees the clean name. */
internal fun cleanProfileName(raw: String): String {
    var cleaned = raw.trim()
    for (suffix in PROFILE_TITLE_SUFFIXES) {
        if (cleaned.length > suffix.length &&
            cleaned.endsWith(suffix, ignoreCase = true)
        ) {
            cleaned = cleaned.dropLast(suffix.length).trimEnd()
            break
        }
    }
    return cleaned
}


/**
 * Pull a follower / subscriber phrase out of a page's description text
 * (og:description or the plain `name="description"` meta), e.g.
 * "2.4M monthly listeners" or "1.23M subscribers". Returns null when
 * the description carries no such phrase — the caller falls back to the
 * page's embedded count markers.
 */
internal fun extractFollowerCount(description: String?): String? {
    if (description.isNullOrBlank()) return null
    // Return the matched phrase VERBATIM — re-synthesising it with
    // hardcoded plurals would turn "1 follower" into "1 followers".
    return FOLLOWER_PHRASE_REGEX.find(description)?.value?.trim()
}


/** Parse the name + avatar + follower count out of a profile page's
 *  HTML. Name / avatar come from the OpenGraph meta tags
 *  (og:title / og:image), order-independent for `property` / `content`;
 *  avatar URLs from YouTube sometimes carry `\u0026` escapes, which are
 *  un-escaped here. The follower count is best-effort, in this order:
 *  1) a count phrase in the page description (og:description or the
 *     plain description meta — Spotify artists advertise "Artist · 2.4M
 *     monthly listeners" there),
 *  2) YouTube's embedded `subscriberCountText` JSON,
 *  3) YouTube's raw `UserSubscriptions:N` interactionCount meta.
 *  Missing fields are left null. Extracted from [fetchProfileInfo] so
 *  the scraping logic is testable offline. */
internal fun parseProfileMeta(html: String): MediaProfileInfo {
    var name: String? = null
    var avatar: String? = null
    for (match in OG_TAG_REGEX.findAll(html)) {
        val property = match.groupValues[1].lowercase()
        val content = decodeHtmlEntities(match.groupValues[2]).trim()
        when (property) {
            "title" -> if (name == null) name = cleanProfileName(content)
            "image" -> if (avatar == null) {
                avatar = content.replace("\\u0026", "&")
            }
        }
        if (name != null && avatar != null) break
    }
    var description: String? = null
    for (match in DESCRIPTION_META_REGEX.findAll(html)) {
        val content = decodeHtmlEntities(match.groupValues[1]).trim()
        if (content.isNotEmpty()) {
            description = content
            break
        }
    }
    var followers = extractFollowerCount(description)
    if (followers == null) {
        followers = YT_SUBSCRIBER_TEXT_REGEX.find(html)
            ?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
    }
    if (followers == null) {
        followers = USER_SUBSCRIPTIONS_REGEX.find(html)
            ?.groupValues?.get(1)?.toLongOrNull()
            ?.let { "${formatCount(it)} subscribers" }
    }
    return MediaProfileInfo(
        name = name,
        avatarUrl = avatar,
        followerCount = followers,
        badge = detectProfileBadge(html)
    )
}


// ---------------------------------------------------------------------------
// Profile info cache
//
// Scraping a channel / profile page is a megabyte-scale network fetch, so
// results are cached per resolved URL for the app session: re-opening a
// note (or hovering the same profile again) shows the previously scraped
// name / avatar / follower count / badge INSTANTLY instead of re-fetching
// the page. Profile data changes rarely (names and avatars are stable;
// follower counts drift slowly), so session-scoped freshness is a fair
// trade-off — the cache is in memory only and clears on restart. Failed
// scrapes are cached as null too, so a dead / offline link isn't
// hammered on every re-open. LRU-capped (access-ordered) so a session
// touching many profiles can't grow without bound.
// ---------------------------------------------------------------------------
internal object ProfileInfoCache {
    private const val MAX_ENTRIES = 64

    // Access-ordered (true) so reads refresh recency — that is what makes
    // removeEldestEntry evict the LEAST-recently-USED entry, not merely
    // the oldest inserted one.
    private val cache = object : LinkedHashMap<String, MediaProfileInfo?>(32, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, MediaProfileInfo?>
        ): Boolean = size > MAX_ENTRIES
    }

    // fetchProfileInfo is a suspend function callable from any coroutine
    // context, so the map is guarded against concurrent mutation even
    // though every current caller happens to run on the main dispatcher.
    @Synchronized
    fun get(url: String): MediaProfileInfo? = cache[url]

    /** True when the URL has a cache entry (even a failed-scrape null). */
    @Synchronized
    fun isCached(url: String): Boolean = cache.containsKey(url)

    @Synchronized
    fun put(url: String, info: MediaProfileInfo?) {
        cache[url] = info
    }

    @Synchronized
    internal fun clearForTests() {
        cache.clear()
    }
}


/**
 * Best-effort name + avatar of a channel / profile token, scraped from
 * the page's OpenGraph meta tags. Returns null on ANY failure (offline,
 * layout change, region redirect) and immediately for non-profile
 * tokens, so the card can degrade gracefully. Results are cached in
 * [ProfileInfoCache] per resolved URL, so re-opening a note never
 * re-scrapes a profile that was already fetched this session.
 */
suspend fun fetchProfileInfo(token: MediaReferenceToken): MediaProfileInfo? {
    if (!token.isProfile) return null
    val url = token.resolveUrl() ?: return null
    // Session cache: serve the previous scrape (or a cached failure)
    // without touching the network.
    if (ProfileInfoCache.isCached(url)) return ProfileInfoCache.get(url)
    val fetched = withContext(Dispatchers.IO) {
        runCatching {
            val conn = URI(url).toURL().openConnection() as HttpURLConnection
            conn.connectTimeout = HTTP_TIMEOUT_MILLIS
            conn.readTimeout = HTTP_TIMEOUT_MILLIS
            conn.setRequestProperty("User-Agent", APP_USER_AGENT)
            val body = conn.inputStream.use { stream ->
                readCapped(stream, MAX_PROFILE_BYTES).toString(Charsets.UTF_8)
            }
            parseProfileMeta(body)
        }.getOrNull()
    }
    ProfileInfoCache.put(url, fetched)
    return fetched
}


// ---------------------------------------------------------------------------
// Media title cache
//
// The editor renders media chips as the media's TITLE ("Never Gonna Give
// You Up" instead of "▶️ YouTube dQw4w9WgXcQ") once the oEmbed title has
// been fetched. Titles are cached per resolved URL for the app session,
// mirroring [ProfileInfoCache]: re-opening a note (or typing the same
// token again) shows the cached title INSTANTLY instead of re-fetching.
// Titles change rarely, so session-scoped freshness is a fair trade-off;
// failed fetches are cached as null too, so an offline / dead link isn't
// hammered on every re-filter. LRU-capped like the profile cache.
// ---------------------------------------------------------------------------
internal object MediaTitleCache {
    private const val MAX_ENTRIES = 128

    private val cache = object : LinkedHashMap<String, String?>(32, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, String?>
        ): Boolean = size > MAX_ENTRIES
    }

    // fetchMediaTitle is a suspend function callable from any coroutine
    // context, so the map is guarded against concurrent mutation even
    // though every current caller happens to run on the main dispatcher.
    @Synchronized
    fun get(url: String): String? = cache[url]

    /** True when the URL has a cache entry (even a failed-fetch null). */
    @Synchronized
    fun isCached(url: String): Boolean = cache.containsKey(url)

    @Synchronized
    fun put(url: String, title: String?) {
        cache[url] = title
    }

    @Synchronized
    internal fun clearForTests() {
        cache.clear()
    }
}


/**
 * Best-effort display title of a media token: the media's oEmbed title
 * for playable items, the profile's scraped name for channels / artists,
 * and the file name for local media files (which carry no metadata
 * title). Generic links return null — their chip keeps the domain, which
 * IS the meaningful label for a bare URL. Returns null on ANY failure
 * (offline, unknown link, malformed response) so the chip can degrade
 * gracefully to [MediaReferenceToken.chipText]. Results are cached in
 * [MediaTitleCache] per resolved URL, so the editor re-filtering on
 * every keystroke never re-fetches a title that is already known.
 */
suspend fun fetchMediaTitle(token: MediaReferenceToken): String? {
    val url = token.resolveUrl() ?: return null
    // Session cache: serve the previous fetch (or a cached failure)
    // without touching the network.
    if (MediaTitleCache.isCached(url)) return MediaTitleCache.get(url)
    val title = when {
        // Local files have no metadata title — the file name is the label.
        token.service == MediaService.FILE ->
            token.content.substringAfterLast('/').ifBlank { null }

        // Channels / artists: reuse the (already cached) scraped name.
        token.isProfile -> fetchProfileInfo(token)?.name

        // Generic links have no oEmbed title; the domain label suffices.
        token.service == MediaService.LINK -> null
        else -> {
            val info = MediaPreviewFetcher.fetch(token)
            val title = info?.title
            // Spotify chips carry the artists too ("Song — Artist1,
            // Artist2") — a bare track name is often ambiguous, and
            // Spotify's oEmbed author is empty, so this is the scraped
            // list from [MediaPreviewFetcher.fetch].
            if (token.service == MediaService.SPOTIFY &&
                title != null &&
                !info.author.isNullOrBlank()
            ) {
                "$title — ${info.author}"
            } else {
                title
            }
        }
    }
    MediaTitleCache.put(url, title)
    return title
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
