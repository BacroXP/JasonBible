package data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import platform.AppPlatform
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * On-demand Bible modules.
 *
 * The APK bundles only KJV and the Hebrew WLC; every other translation is
 * published as a release asset and listed in a small manifest. Settings →
 * Translations lets the user tick the ones they want and download them in
 * one go. Installed modules live in `filesDir/translations/` and are merged
 * into [BibleCatalog], so a downloaded Bible behaves exactly like a bundled
 * one (picker, search, references, export).
 */
object TranslationDownloads {

    /** Rolling release asset produced by the Translations release workflow. */
    const val MANIFEST_URL =
        "https://github.com/BacroXP/JasonBible/releases/download/translations-latest/manifest.json"

    @Serializable
    data class RemoteTranslation(
        val id: String = "",
        val languageFolder: String = "",
        val language: String = "",
        val name: String = "",
        val size: Long = 0,
        val sha256: String = "",
        val url: String = ""
    )

    @Serializable
    data class InstalledTranslation(
        val id: String = "",
        val language: String = "",
        val name: String = "",
        val size: Long = 0
    )

    @Serializable
    private data class Manifest(
        val version: Int = 1,
        val translations: List<RemoteTranslation> = emptyList()
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val indexLock = Any()

    private fun root(): File = File(AppPlatform.filesDir, "translations").apply { mkdirs() }

    private fun indexPath(): File = File(root(), "index.json")

    /** The bookkeeping file; never a translation. */
    private const val INDEX_NAME = "index.json"

    /** Path of an installed module, or null when it isn't downloaded. */
    fun fileFor(moduleId: String): File? {
        if (moduleId.isBlank() || moduleId == "index") return null
        return File(root(), "$moduleId.json").takeIf { it.isFile }
    }

    fun isInstalled(moduleId: String): Boolean = fileFor(moduleId) != null

    /** Installed modules, from the index (with a directory scan as a fallback). */
    fun installed(): List<InstalledTranslation> = synchronized(indexLock) {
        val known = readIndex()
        val present = root().listFiles { file -> file.isFile && file.name.endsWith(".json") }
            .orEmpty()
            .map { it.name }
            .filter { it != INDEX_NAME }
            .map { it.removeSuffix(".json") }
        val merged = known.filter { it.id in present }.toMutableList()
        present.filter { id -> merged.none { it.id == id } }.forEach { id ->
            merged.add(InstalledTranslation(id = id, language = "", name = id))
        }
        merged
    }

    /** Fetches the catalogue of translations that can be downloaded. */
    suspend fun available(): Result<List<RemoteTranslation>> = withContext(Dispatchers.IO) {
        runCatching {
            val text = httpText(MANIFEST_URL)
            json.decodeFromString(Manifest.serializer(), text).translations
                .filter { it.id.isNotBlank() && it.url.isNotBlank() }
                .sortedWith(compareBy({ it.language.lowercase() }, { it.name.lowercase() }))
        }
    }

    /**
     * Downloads one module to private storage, verifying its sha256 before
     * it becomes visible to the catalog. Progress is reported on the main
     * thread in 0..1 steps.
     */
    suspend fun download(
        entry: RemoteTranslation,
        onProgress: (Float) -> Unit = {}
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            require(entry.url.startsWith("https://") || entry.url.startsWith("http://")) {
                "No download URL"
            }
            require(entry.id.isNotBlank()) { "Missing translation id" }

            val target = File(root(), "${entry.id}.json")
            val temp = File(root(), "${entry.id}.part")
            val connection = (URL(entry.url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 60_000
                instanceFollowRedirects = true
            }
            try {
                if (connection.responseCode !in 200..299) {
                    error("Download failed (HTTP ${connection.responseCode})")
                }
                val total = connection.contentLengthLong.takeIf { it > 0 } ?: entry.size
                var copied = 0L
                var lastReport = 0L
                connection.inputStream.use { input ->
                    temp.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                            copied += read
                            if (total > 0 && copied - lastReport >= 256 * 1024) {
                                lastReport = copied
                                val fraction = (copied.toFloat() / total).coerceIn(0f, 1f)
                                withContext(Dispatchers.Main) { onProgress(fraction) }
                            }
                        }
                    }
                }
                if (entry.sha256.isNotBlank() &&
                    !sha256(temp).equals(entry.sha256, ignoreCase = true)
                ) {
                    temp.delete()
                    error("Download was corrupted (checksum mismatch)")
                }
                if (target.exists()) target.delete()
                if (!temp.renameTo(target)) error("Could not store the translation")
                rememberInstalled(entry)
                withContext(Dispatchers.Main) { onProgress(1f) }
                target
            } finally {
                connection.disconnect()
            }
        }
    }

    /** Deletes an installed module and forgets it in the index. */
    fun remove(moduleId: String): Boolean = synchronized(indexLock) {
        fileFor(moduleId)?.delete() == true
        val remaining = readIndex().filterNot { it.id == moduleId }
        writeIndex(remaining)
        true
    }

    private fun rememberInstalled(entry: RemoteTranslation) = synchronized(indexLock) {
        val current = readIndex().filterNot { it.id == entry.id }
        writeIndex(
            current + InstalledTranslation(
                id = entry.id,
                language = entry.language,
                name = entry.name.ifBlank { entry.id },
                size = entry.size
            )
        )
    }

    private fun readIndex(): List<InstalledTranslation> {
        val file = indexPath()
        if (!file.isFile) return emptyList()
        return runCatching {
            json.decodeFromString(
                ListSerializer(InstalledTranslation.serializer()),
                file.readText()
            )
        }.getOrDefault(emptyList())
    }

    private fun writeIndex(entries: List<InstalledTranslation>) {
        runCatching {
            indexPath().writeText(
                json.encodeToString(
                    ListSerializer(InstalledTranslation.serializer()),
                    entries.sortedBy { it.id }
                )
            )
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun httpText(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/json")
        }
        try {
            if (connection.responseCode !in 200..299) {
                error("Could not load the translation list (HTTP ${connection.responseCode})")
            }
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }
}
