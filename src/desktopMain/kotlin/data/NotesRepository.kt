package data

import model.BookBlock
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
import kotlin.io.path.nameWithoutExtension


object NotesRepository {

    private val notesDir: Path = Path.of(
        System.getProperty("user.home"),
        ".bibleapp",
        "notes"
    )

    val notes: List<ParsedNote> by lazy {
        ensureSeeded()
        loadNotes()
    }


    fun loadNote(fileName: String): ParsedNote? {
        ensureSeeded()
        val path = notesDir.resolve(fileName)
        return if (Files.exists(path)) parseNoteFile(path) else null
    }


    fun findByTitle(title: String): ParsedNote? {
        ensureSeeded()
        return listFiles().find { it.title == title }
    }


    fun saveNote(originalFileName: String?, content: String): ParsedNote {
        ensureSeeded()

        val parsedTitle = extractTitle(content)
        val safeFileName = sanitizeFileName(parsedTitle.ifBlank { "note" }) + ".note"
        val targetPath = notesDir.resolve(safeFileName)

        if (!Files.exists(notesDir)) {
            Files.createDirectories(notesDir)
        }

        Files.writeString(targetPath, content.trimEnd() + "\n")

        if (!originalFileName.isNullOrBlank() && originalFileName != safeFileName) {
            val oldPath = notesDir.resolve(originalFileName)
            if (Files.exists(oldPath)) {
                Files.delete(oldPath)
            }
        }

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
        Files.writeString(targetPath, content.trimEnd() + "\n")
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
        return if (Files.exists(path)) {
            runCatching { Files.delete(path) }.isSuccess
        } else {
            false
        }
    }


    /**
     * Creates a new blank note on disk and returns it. The filename is
     * derived from [title] (sanitized) and gets a `-1`, `-2`, … suffix
     * when a note with that name already exists, so every new note owns
     * a unique file. Content is a single `# Title` heading line — the
     * user renames by editing the first line (same as any other note).
     */
    fun createNote(title: String = "Untitled"): ParsedNote {
        // ensureSeeded() guarantees the notes directory exists.
        ensureSeeded()

        val safeBase = sanitizeFileName(title).ifBlank { "untitled" }
        var fileName = "$safeBase.note"
        var counter = 1
        while (Files.exists(notesDir.resolve(fileName))) {
            fileName = "${safeBase}-$counter.note"
            counter++
        }

        val heading = title.trim().ifBlank { "Untitled" }
        Files.writeString(notesDir.resolve(fileName), "# $heading\n")
        return parseNoteFile(notesDir.resolve(fileName))
    }


    fun listFiles(): List<ParsedNote> {
        ensureSeeded()
        return loadNotes()
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
            Files.list(notesDir).use { stream ->
                stream
                    .filter { path -> path.extension == "note" }
                    .map { path ->
                        val mtime = runCatching {
                            Files.getLastModifiedTime(path).toMillis()
                        }.getOrDefault(0L)
                        val size = runCatching { Files.size(path) }.getOrDefault(0L)
                        "${path.fileName}|$mtime|$size"
                    }
                    .sorted()
                    .toList()
            }
        }.getOrElse { emptyList() }
    }


    fun notesForVerse(bookName: String, chapter: Int, verse: Int): List<ParsedNote> {
        return notes.filter { note ->
            note.references.any { ref ->
                ref.book == bookName && ref.chapter == chapter && ref.verse == verse
            }
        }
    }


    fun titlesForVerse(bookName: String, chapter: Int, verse: Int): List<String> {
        return notesForVerse(bookName, chapter, verse)
            .map { it.title }
            .distinct()
    }


    fun referencesForVerse(bookName: String, chapter: Int, verse: Int): List<NoteReference> {
        return notes.flatMap { note ->
            note.references.filter { ref ->
                ref.book == bookName && ref.chapter == chapter && ref.verse == verse
            }.map { ref ->
                ref.copy(noteTitle = note.title)
            }
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
        return Files.list(notesDir).use { stream ->
            stream
                .filter { path -> path.extension == "note" }
                .sorted()
                .map { path -> parseNoteFile(path) }
                .toList()
        }
    }


    private fun parseNoteFile(path: Path): ParsedNote {
        val content = Files.readString(path)
        val lines = content.lines()
        val fileName = path.fileName.toString()
        val fallbackTitle = path.nameWithoutExtension

        val blocks = mutableListOf<NoteBlock>()
        val references = mutableListOf<NoteReference>()
        var title = fallbackTitle

        lines.forEach { rawLine ->
            val line = rawLine.trimEnd()
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

                unorderedListRegex.matches(line) -> {
                    val text = line.replaceFirst(unorderedListRegex, "").trim()
                    blocks.add(ListBlock(ordered = false, text = text))
                }

                orderedListRegex.matches(line) -> {
                    val text = line.replaceFirst(orderedListRegex, "").trim()
                    blocks.add(ListBlock(ordered = true, text = text))
                }

                line.startsWith(">") -> {
                    parseQuotedOrListedLine(line)?.let { blocks.add(it) }
                }

                line.startsWith("\"") -> {
                    parseQuoteLine(line)?.let { blocks.add(it) }
                }

                line.startsWith("$") -> {
                    parseReferenceLine(line, title)?.let { parsed ->
                        blocks.add(parsed.block)
                        parsed.reference?.let(references::add)
                    }
                }

                else -> {
                    blocks.add(ParagraphBlock(line))
                }
            }
        }

        return ParsedNote(
            title = title,
            fileName = fileName,
            content = content,
            blocks = blocks,
            references = references
        )
    }


    private fun parseReferenceLine(
        line: String,
        noteTitle: String
    ): ReferenceParseResult? {
        val match = referenceRegex.matchEntire(line) ?: return null
        val book = match.groupValues[1].trim()
        val chapter = match.groupValues[2].toIntOrNull()
        val verse = match.groupValues[3].toIntOrNull()
        val extra = match.groupValues[4].trim().ifBlank { null }

        return if (chapter != null && verse != null) {
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
        } else {
            ReferenceParseResult(
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
        val match = quoteRegex.matchEntire(line) ?: return null
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
    }


    private fun extractTitle(content: String): String {
        return content.lineSequence()
            .firstOrNull { it.trimStart().startsWith("# ") }
            ?.trimStart()
            ?.removePrefix("# ")
            ?.trim()
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


    private data class ReferenceParseResult(
        val block: NoteBlock,
        val reference: NoteReference?
    )


    private val referenceRegex =
        Regex("^\\\$([^\\\$]+)(?:\\\$(\\d+)\\\$(\\d+)(?:\\s+(.*))?)?$")

    private val quoteRegex =
        Regex("^\"(.+?)\"(?:\\[#([0-9A-Fa-f]{3,8})])?\\s*(.*)$")

    private val unorderedListRegex = Regex("^-\\s+")

    private val orderedListRegex = Regex("^\\d+\\.\\s+")
}
