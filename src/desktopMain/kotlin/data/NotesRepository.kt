package data

import model.BookBlock
import model.ChapterReferenceBlock
import model.HeadingBlock
import model.ListBlock
import model.NoteBlock
import model.NoteReference
import model.ParsedNote
import model.ParagraphBlock
import model.QuoteBlock
import model.VerseReferenceBlock
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import ui.coloredQuoteRegex
import ui.orderedListRegex
import ui.referenceLineRegex
import ui.stripLeadingMarkers


object NotesRepository {

    private val notesDir: Path = Path.of(
        System.getProperty("user.home"),
        ".bibleapp",
        "notes"
    )

    // Verse-scoped lookups (notesForVerse / referencesForVerse / …) read
    // this cached list. Unlike the lazy `val` it used to be, the cache is
    // invalidated on every write (save / create / delete), so a rename —
    // or any title edit — is immediately reflected in the Bible pane's
    // note chips instead of lingering until restart.
    @Volatile
    private var notesCache: List<ParsedNote>? = null

    val notes: List<ParsedNote>
        get() {
            if (notesCache == null) {
                ensureSeeded()
                notesCache = loadNotes()
            }
            return notesCache!!
        }

    private fun invalidateNotes() {
        notesCache = null
    }


    fun loadNote(fileName: String): ParsedNote? {
        ensureSeeded()
        val path = notesDir.resolve(fileName)
        return if (Files.exists(path)) parseNoteFile(path) else null
    }


    fun findByTitle(title: String): ParsedNote? {
        ensureSeeded()
        val all = listFiles()
        // Exact match first; a case-insensitive fallback keeps `[[title]]`
        // links working when the note's actual title differs in case.
        return all.find { it.title == title }
            ?: all.find { it.title.equals(title, ignoreCase = true) }
    }


    /**
     * Save [content] to a file whose name matches the note's title — the
     * first `# ` heading, sanitized (same scheme as [createNote]). When
     * the title changed and the file's name no longer matches it, the
     * note is RENAMED on disk so the sidebar / file explorer always show
     * the title's name.
     *
     * Safety guarantees that keep sibling notes intact:
     *  - The target name is deduplicated with a `-1`, `-2`, … suffix when
     *    it is already taken by ANOTHER note, so a rename can never
     *    overwrite (destroy) a different note's file.
     *  - The new file is written BEFORE the old one is deleted, so a
     *    failed write can never leave the note lost.
     *  - A title that sanitizes to the current file name (no-op rename,
     *    e.g. only case / spacing changed) stays in place.
     *
     * Returns the parsed note at its (possibly new) path.
     */
    fun saveNote(originalFileName: String?, content: String): ParsedNote {
        ensureSeeded()

        val parsedTitle = extractTitle(content)
        val safeBase = sanitizeFileName(parsedTitle.ifBlank { "note" })

        // The note may live inside a FOLDER ("Study/Name.note") — the
        // rename target must stay in that folder, otherwise a title edit
        // would silently drag the note out of its folder to the root.
        val folderPart = originalFileName?.substringBeforeLast('/', "")
            ?.let { if (it.isEmpty() || it == originalFileName) "" else it }
            .orEmpty()

        // Walk to a free name like createNote does. The file the user is
        // currently editing (originalFileName) is skipped by the loop so
        // a title that already matches its path keeps that path — only a
        // name owned by a DIFFERENT note forces the -1, -2, … suffix.
        var fileName = if (folderPart.isEmpty()) "$safeBase.note" else "$folderPart/$safeBase.note"
        var counter = 1
        while (Files.exists(notesDir.resolve(fileName)) && fileName != originalFileName) {
            fileName = if (folderPart.isEmpty()) {
                "${safeBase}-$counter.note"
            } else {
                "$folderPart/${safeBase}-$counter.note"
            }
            counter++
        }
        val targetPath = notesDir.resolve(fileName)
        Files.createDirectories(targetPath.parent)

        // Write first, then delete the old file — never the reverse.
        Files.writeString(targetPath, content.trimEnd() + "\n")

        if (!originalFileName.isNullOrBlank() && fileName != originalFileName) {
            val oldPath = notesDir.resolve(originalFileName)
            if (Files.exists(oldPath)) {
                Files.delete(oldPath)
            }
        }

        invalidateNotes()
        return parseNoteFile(targetPath)
    }


    /**
     * Save the given content to the SAME file the user is currently
     * editing, regardless of any title change. Unlike [saveNote] this
     * never renames or deletes other files — the on-disk path stays put
     * so the user can keep working in the same note even after rewriting
     * its title.
     */
    fun saveNoteInPlace(originalFileName: String?, content: String): ParsedNote {
        ensureSeeded()
        val actualName = originalFileName?.takeIf { it.isNotBlank() } ?: "note.note"
        val targetPath = notesDir.resolve(actualName)
        if (!Files.exists(notesDir)) {
            Files.createDirectories(notesDir)
        }
        Files.createDirectories(targetPath.parent)
        Files.writeString(targetPath, content.trimEnd() + "\n")
        invalidateNotes()
        return parseNoteFile(targetPath)
    }


    /**
     * Permanently deletes the note file from disk. Returns true when the
     * file existed and was removed; false when it was already gone or
     * the delete failed (e.g. the file is locked by another process).
     */
    fun deleteNote(fileName: String): Boolean {
        ensureSeeded()
        val path = notesDir.resolve(fileName)
        val deleted = if (Files.exists(path)) {
            runCatching { Files.delete(path) }.isSuccess
        } else {
            false
        }
        if (deleted) invalidateNotes()
        return deleted
    }


    /**
     * Creates a new blank note on disk and returns it. The filename is
     * derived from [title] (sanitized) and gets a `-1`, `-2`, … suffix
     * when a note with that name already exists, so every new note owns
     * a unique file. Content is a single `# Title` heading line — the
     * user renames by editing the first line (same as any other note).
     */
    fun createNote(title: String = "Untitled", folder: String = ""): ParsedNote {
        // ensureSeeded() guarantees the notes directory exists.
        ensureSeeded()

        val dir = if (folder.isBlank()) notesDir else notesDir.resolve(folder)
        Files.createDirectories(dir)

        val safeBase = sanitizeFileName(title).ifBlank { "untitled" }
        var fileName = "$safeBase.note"
        var counter = 1
        while (Files.exists(dir.resolve(fileName))) {
            fileName = "${safeBase}-$counter.note"
            counter++
        }

        val heading = title.trim().ifBlank { "Untitled" }
        Files.writeString(dir.resolve(fileName), "# $heading\n")
        invalidateNotes()
        return parseNoteFile(dir.resolve(fileName))
    }


    fun listFiles(): List<ParsedNote> {
        ensureSeeded()
        return loadNotes()
    }


    // ------------------------------------------------------------------
    // Imported media files (`@file:` references)
    // ------------------------------------------------------------------

    /** The subfolder holding media files imported into notes. */
    fun mediaDir(): Path = notesDir.resolve("media")

    /**
     * Resolve a `@file:` reference payload (e.g. `media/photo-abc123.jpg`)
     * to its absolute on-disk path, or null when the file doesn't exist.
     * Absolute payloads resolve directly (Path.resolve semantics);
     * relative ones resolve against the notes directory.
     */
    fun resolveMediaRef(ref: String): Path? =
        notesDir.resolve(ref).takeIf { Files.exists(it) && Files.isRegularFile(it) }

    /**
     * Copy a local media file (image / video / audio) into the notes
     * media folder under a unique sanitized name and return the
     * `media/<name>` reference to embed as `@file:…`, or null when the
     * file isn't a supported media type or can't be copied.
     */
    fun importMediaFile(source: Path): String? {
        if (!Files.isRegularFile(source)) return null
        val name = source.fileName.toString()
        if (mediaKindFor(name) == null) return null
        val ext = name.substringAfterLast('.', "").lowercase()
        val base = name.removeSuffix(".$ext")
            .replace(Regex("""[^\p{L}\p{Nd}._-]+"""), "-")
            .trim('-')
            .ifBlank { "media" }
        // Timestamp suffix keeps every drop unique even when the same
        // file is imported twice.
        val targetName = "$base-${System.currentTimeMillis()}.$ext"
        val target = mediaDir().resolve(targetName)
        return runCatching {
            Files.createDirectories(mediaDir())
            Files.copy(source, target)
            "media/$targetName"
        }.getOrNull()
    }


    /**
     * Cheap fingerprint of the notes directory: one `name|mtime|size`
     * string per `.note` file, sorted. Used by the UI to detect external
     * file-system changes (added / removed / renamed / edited notes) by
     * comparing signatures instead of re-parsing every note on each poll.
     * Returns an empty list when the directory is missing.
     */
    fun fileSignatures(): List<String> {
        if (!Files.exists(notesDir)) return emptyList()
        return runCatching {
            Files.walk(notesDir).use { stream ->
                stream
                    .filter { path -> Files.isRegularFile(path) && path.extension == "note" }
                    .map { path ->
                        val mtime = runCatching {
                            Files.getLastModifiedTime(path).toMillis()
                        }.getOrDefault(0L)
                        val size = runCatching { Files.size(path) }.getOrDefault(0L)
                        // Relative path so moves / folder changes are seen.
                        val rel = notesDir.relativize(path).toString().replace('\\', '/')
                        "$rel|$mtime|$size"
                    }
                    .sorted()
                    .toList()
            }
        }.getOrElse { emptyList() }
    }


    fun notesForVerse(bookName: String, chapter: Int, verse: Int): List<ParsedNote> {
        val targetNumber = BibleRepository.bookNumberFor(bookName) ?: return emptyList()
        return notes.filter { note ->
            note.references.any { matchesVerse(it, targetNumber, chapter, verse) }
        }
    }


    fun titlesForVerse(bookName: String, chapter: Int, verse: Int): List<String> {
        return notesForVerse(bookName, chapter, verse)
            .map { it.title }
            .distinct()
    }


    /**
     * One matching line of one note, as produced by [searchNotes].
     * [lineIndex] is the 0-based line number within the note's content
     * (used to scroll the editor to the match); [lineText] is the trimmed
     * raw line for the preview.
     */
    data class NoteSearchHit(
        val note: ParsedNote,
        val lineIndex: Int,
        val lineText: String
    )

    /** Cap on how many global-search hits are returned. */
    private const val MAX_NOTE_SEARCH_HITS = 100

    /**
     * Full-text search across every note file (Ctrl+Shift+F in the
     * editor, and the global Ctrl+F search). Scans each note's raw
     * content line by line — case-insensitive unless [matchCase], and
     * whole-word when [wholeWord] (so "day" doesn't match "today") —
     * returning up to [MAX_NOTE_SEARCH_HITS] matching lines in note
     * order. Lines that match are reported even inside reference /
     * quote / list blocks, since the raw content is what the user reads.
     *
     * NOTE: reads fresh from disk via [listFiles] (not the cached
     * [notes] list) so notes created / renamed / deleted during the
     * session appear in the results immediately — same freshness model
     * as the sidebar.
     */
    fun searchNotes(
        query: String,
        matchCase: Boolean = false,
        wholeWord: Boolean = false
    ): List<NoteSearchHit> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val out = ArrayList<NoteSearchHit>(32)
        for (note in listFiles()) {
            val lines = note.content.lines()
            for (index in lines.indices) {
                if (wholeWord) {
                    if (findMatchIn(lines[index], q, 0, matchCase = matchCase, wholeWord = true) == -1) continue
                } else if (!lines[index].contains(q, ignoreCase = !matchCase)) {
                    continue
                }
                out.add(
                    NoteSearchHit(
                        note = note,
                        lineIndex = index,
                        lineText = lines[index].trim()
                    )
                )
                if (out.size >= MAX_NOTE_SEARCH_HITS) return out
            }
        }
        return out
    }

    /**
     * True when [ref] points at the same verse as (bookName, chapter,
     * verse) — case-insensitive AND cross-language: a German `$Lukas`
     * ref still matches the English "Luke" verse panel (both resolve to
     * the same canonical book number).
     */
    private fun matchesVerse(ref: NoteReference, targetNumber: Int, chapter: Int, verse: Int): Boolean {
        val refNumber = BibleRepository.bookNumberFor(ref.book)
        return refNumber != null && refNumber == targetNumber &&
            ref.chapter == chapter &&
            ref.verse == verse
    }

    fun referencesForVerse(bookName: String, chapter: Int, verse: Int): List<NoteReference> {
        val targetNumber = BibleRepository.bookNumberFor(bookName) ?: return emptyList()
        return notes.flatMap { note ->
            note.references
                .filter { matchesVerse(it, targetNumber, chapter, verse) }
                .map { ref -> ref.copy(noteTitle = note.title) }
        }
    }


    private fun ensureSeeded() {
        if (!Files.exists(notesDir)) {
            Files.createDirectories(notesDir)
        }

        // Seed the bundled sample notes exactly ONCE. If the user later
        // deletes every note we must not resurrect the sample on the next
        // repository access — hence the persisted notesInitialized flag
        // instead of the historical "seed when the folder is empty" check
        // (which would re-seed after a full deletion).
        if (!SettingsManager.notesInitialized) {
            seedBundledNotes()
            SettingsManager.notesInitialized = true
        }
    }


    private fun seedBundledNotes() {
        val classLoader = javaClass.classLoader
        val resourceUrl = classLoader.getResource("notes")
        val sourceDir = Path.of("src", "desktopMain", "resources", "notes")

        val candidates = mutableListOf<Path>()
        if (resourceUrl != null) {
            runCatching { Path.of(resourceUrl.toURI()) }.getOrNull()?.let { candidates.add(it) }
        }
        if (Files.exists(sourceDir)) {
            candidates.add(sourceDir)
        }

        candidates.distinct().forEach { dir ->
            Files.list(dir).use { stream ->
                stream.filter { path -> path.extension == "note" }.forEach { path ->
                    val target = notesDir.resolve(path.fileName.toString())
                    if (!Files.exists(target)) {
                        Files.copy(path, target)
                    }
                }
            }
        }
    }


    private fun loadNotes(): List<ParsedNote> {
        if (!Files.exists(notesDir)) return emptyList()
        // Recursive walk so notes inside FOLDERS (subdirectories) appear
        // in the sidebar too; the media subfolder contains no .note files
        // and is skipped by the extension filter.
        return Files.walk(notesDir).use { stream ->
            stream
                .filter { path -> Files.isRegularFile(path) && path.extension == "note" }
                .sorted()
                .map { path -> parseNoteFile(path) }
                .toList()
        }
    }


    private fun parseNoteFile(path: Path): ParsedNote {
        val content = Files.readString(path)
        val lines = content.lines()
        // fileName is the path RELATIVE to the notes root ("Folder/Name.note"
        // inside a folder, "Name.note" at the root), so folders work and
        // every note still has exactly one unique key.
        val relative = notesDir.relativize(path).toString().replace('\\', '/')
        val fileName = relative
        val folder = relative.substringBeforeLast('/', "")
            .let { if (it == relative) "" else it }
        val fallbackTitle = path.fileName.toString().substringBeforeLast('.')

        val blocks = mutableListOf<NoteBlock>()
        val references = mutableListOf<NoteReference>()
        // Titles of other notes linked via `[[Title]]` in this note's text.
        val links = mutableListOf<String>()
        var title = fallbackTitle

        lines.forEach { rawLine ->
            // Hidden alignment / direction markers (\u200B, \u2060, RLM,
            // LRM) are editor-only styling: strip them before classifying
            // so a centered `\u200B# Title` still parses as a heading and
            // its marker never leaks into the block text.
            val line = stripLeadingMarkers(rawLine.trimEnd())
            if (line.isBlank()) return@forEach

            when {
                line.startsWith("# ") -> {
                    val text = line.removePrefix("# ").trim()
                    title = text
                    blocks.add(HeadingBlock(1, text))
                }

                line.startsWith("## ") -> {
                    blocks.add(HeadingBlock(2, line.removePrefix("## ").trim()))
                }

                unorderedListRegex.containsMatchIn(line) -> {
                    val text = line.replaceFirst(unorderedListRegex, "").trim()
                    blocks.add(ListBlock(ordered = false, text = text))
                }

                orderedListRegex.containsMatchIn(line) -> {
                    val text = line.replaceFirst(orderedListRegex, "").trim()
                    blocks.add(ListBlock(ordered = true, text = text))
                }

                line.startsWith(">") -> {
                    parseQuotedOrListedLine(line)?.let { blocks.add(it) }
                }

                line.startsWith("\"") -> {
                    parseQuoteLine(line)?.let { block ->
                        blocks.add(block)
                        // Colored quotes can carry a reference behind the
                        // text (e.g. `"verse"[#hex] $Book&C&V`, written by
                        // the citation autocomplete) — feed its verse tokens
                        // into the verse-scoped "Referenced notes" panel
                        // exactly like inline references in paragraphs.
                        findReferenceTokens(block.text).forEach { token ->
                            if (token.chapter != null && token.verse != null) {
                                references.add(
                                    NoteReference(
                                        noteTitle = title,
                                        book = token.book.trim(),
                                        chapter = token.chapter,
                                        verse = token.verse
                                    )
                                )
                            }
                        }
                        findNoteLinks(block.text).forEach(links::add)
                    }
                }

                line.startsWith("$") -> {
                    parseReferenceLine(line, title)?.let { parsed ->
                        blocks.add(parsed.block)
                        parsed.reference?.let(references::add)
                    }
                }

                else -> {
                    blocks.add(ParagraphBlock(line))
                    // Inline references embedded in a sentence (e.g.
                    // "Read $Lukas$3$16 today", ending at a space) feed
                    // the verse-scoped "Referenced notes" panel. The
                    // editor renders the chips via
                    // NoteVisualTransformation; here we only extract the
                    // verse-level tokens (book-only / chapter-only tokens
                    // have no verse, so no NoteReference is created — the
                    // model is verse-scoped).
                    findReferenceTokens(line).forEach { token ->
                        if (token.chapter != null && token.verse != null) {
                            references.add(
                                NoteReference(
                                    noteTitle = title,
                                    book = token.book.trim(),
                                    chapter = token.chapter,
                                    verse = token.verse
                                )
                            )
                        }
                    }
                    // Note-to-note links: `[[Title]]` inside a paragraph
                    // (rendered as a clickable chip in the editor; the
                    // title is the link target).
                    findNoteLinks(line).forEach(links::add)
                }
            }
        }

        return ParsedNote(
            title = title,
            fileName = fileName,
            content = content,
            blocks = blocks,
            references = references,
            links = links.distinct(),
            folder = folder
        )
    }


    /** Titles linked from [line] via `[[Title]]` markers. */
    private fun findNoteLinks(line: String): List<String> =
        NOTE_LINK_REGEX.findAll(line).map { it.groupValues[1].trim() }.toList()

    private val NOTE_LINK_REGEX = Regex("\\[\\[([^\\]]+)\\]\\]")


    private fun parseReferenceLine(
        line: String,
        noteTitle: String
    ): ReferenceParseResult? {
        val match = referenceLineRegex.matchEntire(line) ?: return null
        val book = match.groupValues[1].trim()
        val chapter = match.groupValues[2].toIntOrNull()
        val verse = match.groupValues[3].toIntOrNull()
        // Group 5 is the trailing label — group 4 is the optional
        // verse-range suffix, which the note model doesn't track.
        val extra = match.groupValues[5].trim().ifBlank { null }

        return when {
            chapter != null && verse != null -> {
                val reference = NoteReference(
                    noteTitle = noteTitle,
                    book = book,
                    chapter = chapter,
                    verse = verse,
                    label = extra
                )

                ReferenceParseResult(
                    block = VerseReferenceBlock(book, chapter, verse, extra),
                    reference = reference
                )
            }

            chapter != null -> ReferenceParseResult(
                block = ChapterReferenceBlock(book, chapter),
                reference = null
            )

            else -> ReferenceParseResult(
                block = BookBlock(book),
                reference = null
            )
        }
    }


    private fun parseQuotedOrListedLine(line: String): NoteBlock? {
        val depth = line.takeWhile { it == '>' }.length.coerceAtLeast(1)
        val content = line.drop(depth).trimStart()

        return when {
            content.startsWith(".") -> ListBlock(
                ordered = false,
                text = content.removePrefix(".").trim()
            )

            content.startsWith("#") -> ListBlock(
                ordered = true,
                text = content.removePrefix("#").trim()
            )

            else -> QuoteBlock(
                depth = depth,
                text = content
            )
        }
    }


    private fun parseQuoteLine(line: String): QuoteBlock? {
        val match = coloredQuoteRegex.matchEntire(line) ?: return null
        val text = match.groupValues[1].trim()
        val color = match.groupValues[2].trim().ifBlank { null }
        val trailing = match.groupValues[3].trim()

        // No auto-inserted space — the editor currently renders
        // `text + trailing` with no separator (the decorative `"`
        // and `[#hex]` markers are stripped from the displayed text
        // by the COLORED_QUOTE branch in NotesScreen's
        // NoteVisualTransformation). Inserting a space here would put
        // the read view out of sync with what the user sees while
        // editing. Any literal spaces between the closing `""[#hex]`
        // and trailing content in the source are preserved because
        // the regex captures them via `\s*(.*)`.
        val finalText = text + trailing
        return QuoteBlock(depth = 0, text = finalText, colorHex = color)
    }    private fun extractTitle(content: String): String {
        return content.lineSequence()
            .firstOrNull { stripLeadingMarkers(it.trimStart()).startsWith("# ") }
            ?.let { stripLeadingMarkers(it.trimStart()).removePrefix("# ").trim() }
            .orEmpty()
    }


    private fun sanitizeFileName(input: String): String {
        return input
            .trim()
            .lowercase()
            .replace(Regex("""[^\p{L}\p{Nd}]+"""), "-")
            .trim('-')
            .ifBlank { "note" }
    }


    // ------------------------------------------------------------------
    // Folders
    // ------------------------------------------------------------------

    /**
     * Every notes subfolder (relative path with `/` separators, deepest
     * first is NOT guaranteed — callers sort as they like). The media
     * folder is internal and excluded.
     */
    fun folders(): List<String> {
        if (!Files.exists(notesDir)) return emptyList()
        val mediaName = mediaDir().fileName.toString()
        return Files.walk(notesDir).use { stream ->
            stream
                .filter { path ->
                    Files.isDirectory(path) && path != notesDir &&
                        path.fileName.toString() != mediaName
                }
                .map { path ->
                    notesDir.relativize(path).toString().replace('\\', '/')
                }
                .sorted()
                .toList()
        }
    }

    /** Notes directly inside [folder] ("" = the root). */
    fun notesInFolder(folder: String): List<ParsedNote> =
        listFiles().filter { it.folder == folder }

    /**
     * A folder path is valid when it stays inside the notes root: no
     * leading `/`, no OS separators, no `..` segments, no double
     * separators. `/` alone is the documented nesting separator.
     */
    private fun isValidFolderPath(folder: String): Boolean {
        if (folder.isBlank() || folder.startsWith("/") || folder.contains("\\")) return false
        val segments = folder.split('/')
        return segments.none { it.isEmpty() || it == "." || it == ".." }
    }

    /** Create a folder (relative path, may contain `/` for nesting). */
    fun createFolder(folder: String): Boolean {
        if (!isValidFolderPath(folder)) return false
        val target = notesDir.resolve(folder)
        return runCatching {
            Files.createDirectories(target)
            true
        }.getOrDefault(false)
    }

    /** Rename a folder, moving its notes along. Returns false when the
     *  target name is taken or the source doesn't exist. */
    fun renameFolder(oldName: String, newName: String): Boolean {
        if (oldName.isBlank() || newName.isBlank() || oldName == newName) return false
        if (!isValidFolderPath(newName)) return false
        val source = notesDir.resolve(oldName)
        val target = notesDir.resolve(newName)
        if (!Files.isDirectory(source) || Files.exists(target)) return false
        return runCatching { Files.move(source, target); true }.getOrDefault(false)
    }

    /** Delete a folder (only when EMPTY — notes must be moved first). */
    fun deleteFolder(folder: String): Boolean {
        val target = notesDir.resolve(folder)
        if (!Files.isDirectory(target)) return false
        return runCatching { Files.delete(target); true }.getOrDefault(false)
    }

    /**
     * Move a note into [targetFolder] ("" = the root), keeping a unique
     * file name. Returns the note's new relative fileName, or null when
     * the note doesn't exist.
     */
    fun moveNote(fileName: String, targetFolder: String): String? {
        val source = notesDir.resolve(fileName)
        if (!Files.exists(source)) return null
        val currentFolder = fileName.substringBeforeLast('/', "")
            .let { if (it == fileName) "" else it }
        // Moving into the folder it already lives in is a no-op — avoids
        // a same-path Files.move (which can throw and surface as a bogus
        // "could not move" error).
        if (currentFolder == targetFolder) return fileName
        val baseName = fileName.substringAfterLast('/')
        var candidate = if (targetFolder.isBlank()) baseName else "$targetFolder/$baseName"
        var counter = 1
        while (Files.exists(notesDir.resolve(candidate))) {
            val stem = baseName.substringBeforeLast('.')
            val ext = baseName.substringAfterLast('.', "note")
            candidate = if (targetFolder.isBlank()) {
                "$stem-$counter.$ext"
            } else {
                "$targetFolder/$stem-$counter.$ext"
            }
            counter++
        }
        val target = notesDir.resolve(candidate)
        return runCatching {
            Files.createDirectories(target.parent)
            Files.move(source, target)
            invalidateNotes()
            candidate
        }.getOrNull()
    }


    // ------------------------------------------------------------------
    // Import
    // ------------------------------------------------------------------

    /** Result of importing one note file. */
    data class ImportResult(
        val fileName: String?,
        val title: String,
        val error: String? = null
    )

    /**
     * Import a .note / .txt / .md file as a NEW note — never overwrites an
     * existing note (a `-1`, `-2`, … suffix keeps every import unique, so
     * an import is always undoable by deleting the created file). The
     * file name becomes the title when the content has no `# ` heading.
     * Returns the created note's file name, or an error message.
     */
    fun importNote(source: Path): ImportResult {
        ensureSeeded()
        if (!Files.isRegularFile(source)) {
            return ImportResult(null, source.fileName.toString(), "Not a file")
        }
        val ext = source.extension.lowercase()
        val raw = runCatching { Files.readString(source) }
            .getOrElse { return ImportResult(null, source.fileName.toString(), "Unreadable file") }
        if (raw.isBlank()) {
            return ImportResult(null, source.fileName.toString(), "File is empty")
        }

        val content = when (ext) {
            "note" -> raw
            // Plain text / Markdown: keep the content, deriving a title
            // from the first `# ` heading or the file name.
            else -> {
                val firstHeading = raw.lineSequence()
                    .firstOrNull { it.trimStart().startsWith("# ") }
                    ?.trimStart()
                    ?.removePrefix("# ")
                    ?.trim()
                val title = firstHeading ?: source.fileName.toString().substringBeforeLast('.')
                "# $title\n\n" + raw.trim()
            }
        }

        val parsedTitle = extractTitle(content)
        val safeBase = sanitizeFileName(parsedTitle.ifBlank { source.fileName.toString() })
        var fileName = "$safeBase.note"
        var counter = 1
        while (Files.exists(notesDir.resolve(fileName))) {
            fileName = "${safeBase}-$counter.note"
            counter++
        }
        return runCatching {
            Files.writeString(notesDir.resolve(fileName), content.trimEnd() + "\n")
            invalidateNotes()
            ImportResult(fileName, parsedTitle)
        }.getOrElse { e ->
            ImportResult(null, parsedTitle, e.message ?: "Write failed")
        }
    }


    private data class ReferenceParseResult(
        val block: NoteBlock,
        val reference: NoteReference?
    )


    // `- ` bullet markers have no UI-side counterpart (the editor matches
    // a literal `- `), so this one stays local to the parser.
    private val unorderedListRegex = Regex("^-\\s+")
}
