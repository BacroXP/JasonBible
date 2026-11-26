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
        else -> ".deb"
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


    /**
     * Download [release]'s installer and start the detached install +
     * relaunch script. Returns true when the script is running, meaning
     * the caller MUST exit the app promptly so the installer can replace
     * its files and start the new build; false when the update could not
     * be staged (no asset for this OS, a dev-mode build that has no
     * launcher to restart, download failure) — the caller should then
     * just send the user to [AppRelease.pageUrl].
     */
    suspend fun installAndRestart(
        release: AppRelease,
        onProgress: (Int) -> Unit = {}
    ): Boolean {
        val assetUrl = release.assetUrl ?: return false
        val assetName = release.assetName ?: return false
        // Only a jpackage-packaged build can replace itself and be
        // restarted by path; a `./gradlew run` build has no launcher.
        val launcher = launcherPath() ?: return false
        val updater = withContext(Dispatchers.IO) {
            runCatching {
                val installer = download(assetUrl, assetName, onProgress)
                    ?: return@runCatching null
                stageScript(installer, launcher)
            }.getOrNull()
        } ?: return false
        return withContext(Dispatchers.IO) { startDetached(updater) }
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
        val dir = Path.of(
            System.getProperty("user.home"),
            ".bibleapp",
            "updates"
        )
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
    // Install + relaunch
    // ------------------------------------------------------------------

    /**
     * Path of the launcher that started this process, for relaunching
     * after the update. jpackage's native launchers export their own path
     * as `jpackage.app-path`; when that is missing the process command is
     * used as long as it is not a plain `java` binary. Returns null for
     * unpackaged (dev / IDE) runs, which cannot be updated in place.
     */
    private fun launcherPath(): String? {
        System.getProperty("jpackage.app-path")
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }

        val command = ProcessHandle.current().info().command().orElse(null)
            ?: return null
        val name = Path.of(command).fileName?.toString()?.lowercase() ?: return null
        return if (name.startsWith("java")) null else command
    }


    /** Quoting for /bin/sh single-argument paths. */
    private fun shQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"


    /**
     * Write the platform's install+restart script into the updates
     * directory and return its path. The script waits for THIS process to
     * exit before touching the installation — the app's own files are
     * what the package manager replaces (and an MSI refuses to run while
     * the app it upgrades is up).
     */
    private fun stageScript(installer: Path, launcher: String): Path {
        val dir = installer.parent
        val pid = ProcessHandle.current().pid()
        val body = when (osKey()) {
            "windows" -> windowsScript(pid, installer, launcher)
            "mac" -> macScript(pid, installer, launcher)
            else -> linuxScript(pid, installer, launcher)
        }
        val isWindows = osKey() == "windows"
        val script = dir.resolve(if (isWindows) "update.bat" else "update.sh")
        val text = if (isWindows) body.replace("\n", "\r\n") else body
        Files.writeString(
            script,
            text,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE
        )
        if (!isWindows) script.toFile().setExecutable(true, true)
        return script
    }


    private fun linuxScript(pid: Long, installer: Path, launcher: String): String =
        """
        #!/bin/sh
        # Staged by BibleApp: wait for the running app to exit, install the
        # downloaded .deb (via the polkit prompt when available), then start
        # the new build.
        while kill -0 $pid 2>/dev/null; do sleep 0.3; done
        if command -v pkexec >/dev/null 2>&1 && pkexec dpkg -i ${shQuote(installer.toString())}; then
            :
        elif command -v xdg-open >/dev/null 2>&1; then
            # No polkit agent (or the user cancelled): hand the package to
            # the desktop's installer instead of leaving it half-applied.
            xdg-open ${shQuote(installer.toString())}
        fi
        exec ${shQuote(launcher)}
        """.trimIndent() + "\n"


    private fun macScript(pid: Long, installer: Path, launcher: String): String {
        val quotedInstaller = shQuote(installer.toString())
        val mount = "/tmp/bibleapp-update-$pid"
        // jpackage's launcher lives at <bundle>.app/Contents/MacOS/<name>,
        // so the bundle to replace is everything before /Contents/MacOS.
        val bundle = launcher
            .takeIf { it.contains("/Contents/MacOS/") }
            ?.substringBefore("/Contents/MacOS/")

        val install = if (bundle == null) {
            "open $quotedInstaller"
        } else {
            listOf(
                "SRC=\$(ls -d \"\$MOUNT\"/*.app 2>/dev/null | head -1)",
                "if [ -n \"\$SRC\" ]; then",
                "    rm -rf ${shQuote(bundle)}",
                "    ditto \"\$SRC\" ${shQuote(bundle)}",
                "fi",
                "hdiutil detach -quiet \"\$MOUNT\" >/dev/null 2>&1",
                "open ${shQuote(bundle)}"
            ).joinToString("\n")
        }

        return listOf(
            "#!/bin/sh",
            "# Staged by BibleApp: wait for the running app to exit, copy the",
            "# new .app out of the downloaded disk image over the current",
            "# bundle, then reopen it.",
            "while kill -0 $pid 2>/dev/null; do sleep 0.3; done",
            "MOUNT=${shQuote(mount)}",
            "if hdiutil attach -nobrowse -quiet -mountpoint \"\$MOUNT\" $quotedInstaller; then",
            install,
            "else",
            "    open $quotedInstaller",
            "fi",
            ""
        ).joinToString("\n")
    }


    private fun windowsScript(pid: Long, installer: Path, launcher: String): String =
        """
        @echo off
        rem Staged by BibleApp: wait for the running app to exit, run the
        rem MSI upgrade, then start the new build.
        :wait
        tasklist /FI "PID eq $pid" /NH 2>nul | find "$pid" >nul
        if not errorlevel 1 goto wait
        msiexec /i "${installer}" /qb /norestart
        start "" "$launcher"
        """.trimIndent() + "\n"


    /** Start [script] so it outlives this process, logging to
     *  `updates/update.log`. */
    private fun startDetached(script: Path): Boolean = runCatching {
        val log = script.parent.resolve("update.log").toFile()
        val command = if (osKey() == "windows") {
            listOf("cmd.exe", "/c", script.toString())
        } else {
            listOf("/bin/sh", script.toString())
        }
        ProcessBuilder(command)
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.appendTo(log))
            .start()
        true
    }.getOrDefault(false)
}
