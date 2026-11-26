package data



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
    // Resource scanning (APK assets)
    // ------------------------------------------------------------------

    /**
     * All `*.json` files directly inside [resourceDir], as asset paths.
     * Non-recursive — used for the Extras book lists.
     */
    private fun listResourceJson(resourceDir: String): List<String> =
        platform.AppPlatform.listAssets(resourceDir)
            .filter { it.endsWith(".json", ignoreCase = true) }
            .map { "$resourceDir/$it" }

    /**
     * Walk `bible/<Lang>-<name>/<module>.json` two levels deep through the
     * APK's assets and build one [BibleEntry] per module, reading each
     * module's metadata name (prefix-only, so even huge files cost almost
     * nothing to list). Dropping a module into
     * `src/main/assets/bible/<Lang>/` still makes it appear in the picker
     * without code changes, exactly like the desktop resource folder.
     */
    private fun discover(): List<BibleEntry> {
        val result = mutableListOf<BibleEntry>()
        platform.AppPlatform.listAssets("bible").forEach { folderName ->
            if (!folderName.contains('-') || folderName == "Extras") return@forEach
            val languageName = folderName.substringAfter('-').replace('_', ' ')
            platform.AppPlatform.listAssets("bible/$folderName")
                .filter { it.endsWith(".json", ignoreCase = true) }
                .forEach { fileName ->
                    val moduleId = fileName.removeSuffix(".json")
                    val path = "bible/$folderName/$fileName"
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
        return result
            .distinctBy { it.resourcePath }
            .sortedBy { it.languageName + "|" + it.displayName.lowercase() }
    }
}
