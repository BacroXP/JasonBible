package com.jasonbible.phone

import java.io.File

/** A `.note` file with its display title (first non-blank line). */
data class NoteFile(val file: File, val title: String)


/**
 * Plain-text note storage in the app's private files directory
 * (filesDir/notes, one .note file per note). The note format is the same
 * as the desktop app's: markdown-ish text whose first `# ` heading names it.
 */
class NotesStore(private val dir: File) {

    init {
        dir.mkdirs()
    }

    fun list(): List<NoteFile> =
        (dir.listFiles { file -> file.isFile && file.extension == "note" } ?: emptyArray())
            .sortedByDescending { it.lastModified() }
            .map { NoteFile(it, titleOf(it)) }

    fun read(file: File): String =
        runCatching { file.readText() }.getOrDefault("")

    /** Saves into [file], or creates a new file from the note's title. */
    fun save(file: File?, text: String): File {
        val target = file ?: uniqueFile(text)
        runCatching { target.writeText(text) }
        return target
    }

    fun delete(file: File) {
        runCatching { file.delete() }
    }

    private fun uniqueFile(text: String): File {
        val base = sanitize(titleOfText(text))
        var candidate = File(dir, "$base.note")
        var index = 2
        while (candidate.exists()) {
            candidate = File(dir, "$base-$index.note")
            index++
        }
        return candidate
    }

    private fun titleOf(file: File): String {
        val text = read(file)
        return titleOfText(text).ifBlank { file.nameWithoutExtension }
    }

    private fun titleOfText(text: String): String =
        text.lineSequence()
            .map { it.trim().removePrefix("#").trim() }
            .firstOrNull { it.isNotEmpty() }
            .orEmpty()
            .take(60)

    private fun sanitize(title: String): String {
        val cleaned = title.replace(Regex("[\\\\/:*?\"<>|]"), "-").trim()
        return cleaned.ifBlank { "Note" }.take(60)
    }
}
