package data

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption


/**
 * `Files.readString` / `Files.writeString` are Java 11 additions that only
 * reached Android in API 33; the app supports API 26+. These equivalents
 * keep the repository code byte-identical otherwise (UTF-8 both ways, and
 * the same "create parent directories" behaviour the desktop writes
 * relied on elsewhere).
 */
object FilesCompat {

    fun readString(path: Path): String =
        String(Files.readAllBytes(path), Charsets.UTF_8)

    fun writeString(
        path: Path,
        text: String,
        vararg options: StandardOpenOption
    ): Path {
        path.parent?.let { Files.createDirectories(it) }
        val flags = if (options.isEmpty()) {
            arrayOf(
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
            )
        } else {
            options
        }
        return Files.write(path, text.toByteArray(Charsets.UTF_8), *flags)
    }
}
