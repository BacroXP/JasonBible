package platform

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path


/**
 * Everything the shared (desktop-derived) code needs from the Android
 * runtime: the application context, the private storage roots, the APK's
 * bundled assets, the clipboard and a way to hand a URL or a downloaded
 * APK to the system.
 *
 * [init] runs once from the Application subclass, before any repository
 * or settings object is touched, so every lazy `filesDir` read below is
 * safe.
 */
object AppPlatform {

    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    val context: Context
        get() = appContext ?: error("AppPlatform.init() was not called")

    /** Private, backed-up app storage — replaces `~/.bibleapp`. */
    val filesDir: File get() = context.filesDir

    /** Non-backup scratch space for downloaded installers and sounds. */
    val cacheDir: File get() = context.cacheDir

    // ------------------------------------------------------------------
    // Bundled resources (the desktop build reads these off the classpath;
    // on Android the same files ship uncompressed inside the APK).
    // ------------------------------------------------------------------

    fun openAsset(path: String): InputStream? =
        runCatching { context.assets.open(path) }.getOrNull()

    fun readAssetText(path: String): String? =
        openAsset(path)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }

    /** File names inside an asset directory (non-recursive). */
    fun listAssets(dir: String): List<String> =
        runCatching { context.assets.list(dir)?.sorted().orEmpty() }
            .getOrDefault(emptyList())

    // ------------------------------------------------------------------
    // System integration
    // ------------------------------------------------------------------

    fun copyToClipboard(label: String, text: String) {
        runCatching {
            val manager = context.getSystemService(Context.CLIPBOARD_SERVICE)
                as ClipboardManager
            manager.setPrimaryClip(ClipData.newPlainText(label, text))
        }
    }

    fun readClipboard(): String? = runCatching {
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE)
            as ClipboardManager
        manager.primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.coerceToText(context)
            ?.toString()
    }.getOrNull()

    fun openUrl(url: String) {
        if (url.isBlank()) return
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    fun toast(message: String) {
        runCatching {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Hands a downloaded APK to the system package installer. Android
     * replaces the app in place when the signing key matches, and keeps
     * all app data (notes, settings) — the same guarantee the desktop
     * `dpkg -i` path gives.
     */
    fun installApk(file: File): Boolean = runCatching {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.updates",
            file
        )
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    }.getOrDefault(false)
}


// ---------------------------------------------------------------------------
// java.nio.file helpers available on every supported Android version
// (Files.readString / Files.writeString only exist from API 33).
// ---------------------------------------------------------------------------

fun Path.readTextCompat(): String = String(Files.readAllBytes(this), Charsets.UTF_8)
