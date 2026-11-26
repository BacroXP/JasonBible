package data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import platform.AppPlatform


// ---------------------------------------------------------------------------
// Self-update against the project's GitHub Releases
//
// Checks the `releases/latest` endpoint (GitHub only serves the newest
// PUBLISHED, NON-prerelease release there, so "a newer stable version" is
// exactly what it answers) and, on request, downloads the installer for
// this OS and hands it to a small detached script that waits for this
// process to exit, installs the package, and starts the app again.
//
// Failure at every step is non-fatal: the home screen simply shows no
// update (or opens the release page) and the app keeps running.
// ---------------------------------------------------------------------------


/** Newest release of the project, as far as this platform is concerned.
 *  [assetUrl] is null when the release carries no installer this OS can
 *  run (e.g. a Windows-only release), in which case the caller opens
 *  [pageUrl] instead of trying to install. */
data class AppRelease(
    val version: String,
    val pageUrl: String,
    val assetName: String?,
    val assetUrl: String?
)


object AppUpdater {

    /**
     * Version of THIS build. Must match `packageVersion` in
     * build.gradle.kts (which also has to match the git tag `v<version>`)
     * — that constant is what the installed package reports, and it is
     * what the update check compares against.
     */
    const val CURRENT_VERSION = "2.3.0"

    private const val OWNER = "BacroXP"
    private const val REPO = "JasonBible"

    private const val LATEST_RELEASE_API =
        "https://api.github.com/repos/$OWNER/$REPO/releases/latest"

    /** Fallback target when the API gives no usable `html_url`. */
    private const val RELEASES_PAGE = "https://github.com/$OWNER/$REPO/releases/latest"

    private const val METADATA_TIMEOUT_MILLIS = 5000

    /** Read timeout for the installer download (per 64 KB chunk, so a
     *  slow line never trips it mid-download). */
    private const val DOWNLOAD_READ_TIMEOUT_MILLIS = 30_000

    /** Cap on the release JSON (the real document is a few KB). */
    private const val MAX_METADATA_BYTES = 1024 * 1024

    /** Cap on a downloaded installer (the .deb is ~360 MB). */
    private const val MAX_INSTALLER_BYTES = 800L * 1024 * 1024

    private val UPDATE_JSON = Json { ignoreUnknownKeys = true }

    // Session cache: the check runs at most once per app launch no matter
    // how often the user returns to the home screen (the GitHub API allows
    // only 60 unauthenticated requests per hour).
    private val checkLock = Any()
    private var checked = false
    private var cachedRelease: AppRelease? = null


    /** Installer file extension this OS can install. */
    private fun installerSuffix(): String = when (osKey()) {
        "windows" -> ".msi"
        "mac" -> ".dmg"
        else -> ".apk"
    }


    private fun osKey(): String {
        val os = System.getProperty("os.name").lowercase()
        return when {
            os.contains("win") -> "windows"
            os.contains("mac") -> "mac"
            else -> "linux"
        }
    }


    /**
     * The newest stable release when it is NEWER than [CURRENT_VERSION],
     * otherwise null (already current, offline, rate-limited, malformed
     * response — all indistinguishable from "no update", which just means
     * no [Update] field appears). Runs off the main thread; the result is
     * cached for the rest of the session.
     */
    suspend fun checkForUpdate(): AppRelease? {
        synchronized(checkLock) {
            if (checked) return cachedRelease
        }
        val release = withContext(Dispatchers.IO) {
            runCatching {
                val body = fetchText(LATEST_RELEASE_API, MAX_METADATA_BYTES)
                body?.let { parseLatestRelease(it, CURRENT_VERSION, installerSuffix()) }
            }.getOrNull()
        }
        synchronized(checkLock) {
            checked = true
            cachedRelease = release
        }
        return release
    }


    // ------------------------------------------------------------------
    // Release metadata
    // ------------------------------------------------------------------

    /**
     * Parse the `releases/latest` JSON and return the release when its tag
     * is newer than [currentVersion]. [assetSuffix] picks this platform's
     * installer (".deb" / ".msi" / ".dmg"). Pure — no network — so the
     * suite can test it against a captured fixture.
     */
    internal fun parseLatestRelease(
        jsonText: String,
        currentVersion: String = CURRENT_VERSION,
        assetSuffix: String = installerSuffix()
    ): AppRelease? {
        val root = runCatching {
            UPDATE_JSON.parseToJsonElement(jsonText).jsonObject
        }.getOrNull() ?: return null

        val tag = root["tag_name"]?.jsonPrimitive?.contentOrNull ?: return null
        if (!isNewerVersion(tag, currentVersion)) return null

        val pageUrl = root["html_url"]?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.isNotBlank() }
            ?: RELEASES_PAGE

        val asset = (root["assets"] as? JsonArray)
            ?.mapNotNull { it as? JsonObject }
            ?.mapNotNull { obj ->
                val name = obj["name"]?.jsonPrimitive?.contentOrNull
                    ?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val url = obj["browser_download_url"]?.jsonPrimitive
                    ?.contentOrNull?.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                name to url
            }
            ?.firstOrNull { (name, _) ->
                name.endsWith(assetSuffix, ignoreCase = true)
            }

        return AppRelease(
            version = normalizeVersion(tag),
            pageUrl = pageUrl,
            assetName = asset?.first,
            assetUrl = asset?.second
        )
    }


    /** "v2.3.0" / "V2.3.0" → "2.3.0". */
    internal fun normalizeVersion(tag: String): String =
        tag.trim().removePrefix("v").removePrefix("V")


    /**
     * True when [candidate] is a strictly newer dotted version than
     * [current] ("2.3.0" > "2.2.1", "2.2.10" > "2.2.9"). Missing parts
     * count as 0, and a non-numeric suffix ("2.3.0-beta") is ignored past
     * its numeric prefix — the caller only ever compares stable tags.
     */
    internal fun isNewerVersion(
        candidate: String,
        current: String = CURRENT_VERSION
    ): Boolean {
        val left = versionParts(candidate)
        val right = versionParts(current)
        for (index in 0 until maxOf(left.size, right.size)) {
            val a = left.getOrElse(index) { 0 }
            val b = right.getOrElse(index) { 0 }
            if (a != b) return a > b
        }
        return false
    }


    private fun versionParts(version: String): List<Int> =
        normalizeVersion(version)
            .split('.', '-', '+')
            .map { part -> part.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }


    // ------------------------------------------------------------------
    // Networking
    // ------------------------------------------------------------------

    private fun openConnection(
        url: String,
        readTimeoutMillis: Int = METADATA_TIMEOUT_MILLIS
    ): HttpURLConnection =
        (URI(url).toURL().openConnection() as HttpURLConnection).apply {
            connectTimeout = METADATA_TIMEOUT_MILLIS
            readTimeout = readTimeoutMillis
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", APP_USER_AGENT)
            setRequestProperty("Accept", "application/vnd.github+json")
        }


    private fun fetchText(url: String, maxBytes: Int): String? = runCatching {
        val conn = openConnection(url)
        try {
            if (conn.responseCode !in 200..299) return null
            conn.inputStream.use { stream ->
                readCapped(stream, maxBytes).toString(Charsets.UTF_8)
            }
        } finally {
            conn.disconnect()
        }
    }.getOrNull()


    /** Download [url] into `~/.bibleapp/updates/`, returning the written
     *  file or null on any failure. A `.part` file is used so a partial
     *  download is never mistaken for a complete installer. [onProgress]
     *  receives the percentage as it rises (called only when the whole
     *  number changes, and with -1 while the server reports no size). */
    private fun download(
        url: String,
        fileName: String,
        onProgress: (Int) -> Unit
    ): Path? = runCatching {
        val dir = Path.of(AppPlatform.cacheDir.absolutePath, "updates")
        Files.createDirectories(dir)
        val safeName = fileName.substringAfterLast('/').ifBlank { "update.bin" }
        val target = dir.resolve(safeName)
        val partial = dir.resolve("$safeName.part")

        val conn = openConnection(url, DOWNLOAD_READ_TIMEOUT_MILLIS)
        try {
            if (conn.responseCode !in 200..299) return null
            val expected = conn.contentLengthLong
            conn.inputStream.use { input ->
                Files.newOutputStream(
                    partial,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE
                ).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var readSoFar = 0L
                    var lastPercent = Int.MIN_VALUE
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        readSoFar += read
                        if (readSoFar > MAX_INSTALLER_BYTES) {
                            error("Installer exceeds the size cap")
                        }
                        output.write(buffer, 0, read)
                        val percent = if (expected > 0L) {
                            ((readSoFar * 100) / expected).toInt().coerceIn(0, 100)
                        } else {
                            -1
                        }
                        if (percent != lastPercent) {
                            lastPercent = percent
                            onProgress(percent)
                        }
                    }
                }
            }
        } finally {
            conn.disconnect()
        }

        if (Files.size(partial) <= 0L) return null
        onProgress(100)
        Files.move(
            partial,
            target,
            StandardCopyOption.REPLACE_EXISTING
        )
        target
    }.getOrNull()


    // ------------------------------------------------------------------
    // Install (Android)
    // ------------------------------------------------------------------

    /**
     * Downloads the release APK and hands it to the system package
     * installer. Android replaces the app in place when the signing key
     * matches, keeping notes and settings. Returns false when the download
     * or the installer launch fails; the caller then just opens
     * [AppRelease.pageUrl] instead.
     */
    suspend fun installAndRestart(
        release: AppRelease,
        onProgress: (Int) -> Unit = {}
    ): Boolean {
        val assetUrl = release.assetUrl ?: return false
        val assetName = release.assetName ?: return false
        val installer = withContext(Dispatchers.IO) {
            runCatching { download(assetUrl, assetName, onProgress) }.getOrNull()
        } ?: return false
        return withContext(Dispatchers.IO) { AppPlatform.installApk(installer.toFile()) }
    }
}
