package data

import java.io.File
import java.net.JarURLConnection


/**
 * Discovers the bundled SWORD-format Bible modules at runtime — under
 * `src/desktopMain/resources/bible/<Lang>-<name>/`, one module per
 * `*.json` file — so dropping a new module into the resource folder
 * makes it appear in the Bible picker without code changes. Works from
 * both the dev classpath (file:// URLs) and the packaged JAR (jar://
 * URLs).
 *
 * Also builds a cross-language book-name → book-number index from the
 * `Extras/books_*.json` lists, so references written in one language
 * (e.g. `$Lukas` in a German note) keep resolving after the user
 * switches to a Bible in another language ("Luke").
 */
object BibleCatalog {

    data class BibleEntry(
        /** Unique id, taken from the module file name (e.g. "luther_1912"). */
        val moduleId: String,
        /** Human-readable name from the module metadata (e.g. "Luther Bible (1912)"). */
        val displayName: String,
        /** Language display name derived from the folder (e.g. "German"). */
        val languageName: String,
        /** Raw resource folder (e.g. "DE-German"). */
        val languageFolder: String,
        /** Classpath path (e.g. "bible/DE-German/luther_1912.json"). */
        val resourcePath: String
    )

    val entries: List<BibleEntry> by lazy { discover() }

    val languages: List<String> by lazy {
        entries.map { it.languageName }.distinct().sorted()
    }

    /**
     * Module used when the saved translation id no longer exists (the
     * user swapped out files). Prefers the German Luther, then any
     * German module, then the alphabetically first module.
     */
    val defaultId: String by lazy {
        val preferred = listOf("luther_1912", "luther", "schlachter")
        preferred.firstOrNull { id -> entries.any { it.moduleId == id } }
            ?: entries.firstOrNull { it.languageFolder.startsWith("DE-") }?.moduleId
            ?: entries.firstOrNull()?.moduleId
            ?: ""
    }

    fun entryFor(moduleId: String): BibleEntry? =
        entries.find { it.moduleId == moduleId }

    fun entriesForLanguage(languageName: String): List<BibleEntry> =
        entries.filter { it.languageName == languageName }

    /**
     * First-run / stale-settings repair: if the saved translation id no
     * longer exists in the catalog, point it at [defaultId] (and set the
     * language to match). Called once at app startup, before any
     * composition, so the picker and the loaded Bible always agree.
     */
    fun normalizeSavedTranslation() {
        if (entryFor(SettingsManager.translation) != null) return
        if (defaultId.isEmpty()) return
        val entry = entryFor(defaultId) ?: return
        SettingsManager.translation = entry.moduleId
        SettingsManager.language = entry.languageName
    }

    /**
     * Book-name → book-number index from every `Extras/books_*.json`.
     * Each entry contributes its full name, short name and the two
     * "matching" alias fields, all lower-cased. Used to resolve
     * references across languages and to compare book identity between
     * a note's name and the active Bible's name.
     */
    val nameToBookNumber: Map<String, Int> by lazy {
        val map = HashMap<String, Int>()
        for (path in listResourceJson("bible/Extras")) {
            if (!path.substringAfterLast('/').startsWith("books_")) continue
            val books = JsonLoader.loadBookList(path) ?: continue
            for (book in books) {
                putAlias(map, book.name, book.id)
                putAlias(map, book.shortname, book.id)
                putAlias(map, book.matching1, book.id)
                putAlias(map, book.matching2, book.id)
            }
        }
        map
    }

    private fun putAlias(map: MutableMap<String, Int>, name: String?, id: Int) {
        val key = name?.trim()?.lowercase() ?: return
        if (key.isNotEmpty()) map.putIfAbsent(key, id)
    }


    // ------------------------------------------------------------------
    // Resource scanning (dev classpath + packaged JAR)
    // ------------------------------------------------------------------

    /**
     * All `*.json` files directly inside [resourceDir], as classpath
     * paths. Non-recursive — used for the Extras book lists.
     */
    private fun listResourceJson(resourceDir: String): List<String> {
        val classLoader = object {}.javaClass.classLoader
        val result = mutableListOf<String>()
        val urls = classLoader.getResources(resourceDir)
        while (urls.hasMoreElements()) {
            val url = urls.nextElement()
            when (url.protocol) {
                "file" -> {
                    val dir = File(url.toURI())
                    if (dir.isDirectory) {
                        dir.listFiles()?.forEach { file ->
                            if (file.isFile && file.extension.equals("json", true)) {
                                result.add("$resourceDir/${file.name}")
                            }
                        }
                    }
                }
                "jar" -> {
                    val jarFile = (url.openConnection() as JarURLConnection).jarFile
                    val prefix = "$resourceDir/"
                    val depth = resourceDir.count { it == '/' } + 1
                    jarFile.use {
                        it.entries().asSequence().forEach { entry ->
                            if (!entry.isDirectory &&
                                entry.name.startsWith(prefix) &&
                                entry.name.count { c -> c == '/' } == depth &&
                                entry.name.endsWith(".json")
                            ) {
                                result.add(entry.name)
                            }
                        }
                    }
                }
            }
        }
        return result.distinct().sorted()
    }

    /**
     * Walk `bible/<Lang>-<name>/<module>.json` two levels deep and build
     * one [BibleEntry] per module, reading each module's metadata name
     * (prefix-only, so even huge files cost almost nothing to list).
     */
    private fun discover(): List<BibleEntry> {
        val classLoader = object {}.javaClass.classLoader
        val result = mutableListOf<BibleEntry>()
        val urls = classLoader.getResources("bible")
        while (urls.hasMoreElements()) {
            val url = urls.nextElement()
            when (url.protocol) {
                "file" -> {
                    val root = File(url.toURI())
                    root.listFiles { file -> file.isDirectory }?.forEach { langDir ->
                        val folderName = langDir.name
                        if (!folderName.contains('-') || folderName == "Extras") return@forEach
                        val languageName = folderName.substringAfter('-').replace('_', ' ')
                        langDir.listFiles { file ->
                            file.isFile && file.extension.equals("json", true)
                        }?.forEach { moduleFile ->
                            val moduleId = moduleFile.nameWithoutExtension
                            val path = "bible/$folderName/$moduleId.json"
                            result.add(
                                BibleEntry(
                                    moduleId = moduleId,
                                    displayName = JsonLoader.loadMetadataName(path) ?: moduleId,
                                    languageName = languageName,
                                    languageFolder = folderName,
                                    resourcePath = path
                                )
                            )
                        }
                    }
                }
                "jar" -> {
                    val jarFile = (url.openConnection() as JarURLConnection).jarFile
                    val prefix = "bible/"
                    jarFile.use {
                        it.entries().asSequence().forEach { entry ->
                            if (entry.isDirectory ||
                                !entry.name.startsWith(prefix) ||
                                entry.name.count { c -> c == '/' } != 2 ||
                                !entry.name.endsWith(".json")
                            ) return@forEach
                            val folderName = entry.name.removePrefix(prefix).substringBefore('/')
                            if (!folderName.contains('-') || folderName == "Extras") return@forEach
                            val moduleId = entry.name.substringAfterLast('/').removeSuffix(".json")
                            val languageName = folderName.substringAfter('-').replace('_', ' ')
                            result.add(
                                BibleEntry(
                                    moduleId = moduleId,
                                    displayName = JsonLoader.loadMetadataName(entry.name) ?: moduleId,
                                    languageName = languageName,
                                    languageFolder = folderName,
                                    resourcePath = entry.name
                                )
                            )
                        }
                    }
                }
            }
        }
        return result
            .distinctBy { it.resourcePath }
            .sortedBy { it.languageName + "|" + it.displayName.lowercase() }
    }
}
