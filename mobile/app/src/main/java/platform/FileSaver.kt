package platform

import android.content.ContentValues
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.nio.file.Path


/**
 * Writes an exported document into the device's public Downloads folder
 * (`Download/BibleApp/`), where any file manager can find it — the
 * Android equivalent of the desktop's save dialog. Returns a display
 * [Path] for the banner, or null when the write failed.
 */
fun saveToDownloads(displayName: String, mimeType: String, bytes: ByteArray): Path? {
    val context = AppPlatform.context
    val safeName = displayName.replace(Regex("[/\\\\]"), "-").ifBlank { "export" }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, safeName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(
                MediaStore.MediaColumns.RELATIVE_PATH,
                "${Environment.DIRECTORY_DOWNLOADS}/BibleApp"
            )
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = runCatching {
            resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        }.getOrNull() ?: return null
        val written = runCatching {
            resolver.openOutputStream(uri)?.use { it.write(bytes) } != null
        }.getOrDefault(false)
        if (!written) {
            runCatching { resolver.delete(uri, null, null) }
            return null
        }
        runCatching {
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }
        return downloadsPath(safeName)
    }

    // API 26–28: no scoped storage, write straight to the Downloads dir.
    val dir = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
        "BibleApp"
    )
    if (!dir.exists() && !dir.mkdirs()) return null
    val target = File(dir, safeName)
    return runCatching {
        target.writeBytes(bytes)
        target.toPath()
    }.getOrNull()
}


private fun downloadsPath(name: String): Path = Path.of(
    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        .absolutePath,
    "BibleApp",
    name
)
