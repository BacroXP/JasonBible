package ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import data.BibleCatalog
import data.SoundEvent
import data.SoundManager
import data.TranslationDownloads
import kotlinx.coroutines.launch

/**
 * Panel state that outlives the composable.
 *
 * The settings screen rebuilds its section list whenever the filter changes,
 * which disposes and recreates this panel. Keeping the ticks, the fetched
 * catalogue and the running download here means a keystroke in the settings
 * search no longer clears the selection or re-requests the manifest.
 */
private object TranslationsPanel {
    var available by mutableStateOf<List<TranslationDownloads.RemoteTranslation>>(emptyList())
    var fetched by mutableStateOf(false)
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var selected by mutableStateOf<Set<String>>(emptySet())
    var message by mutableStateOf<String?>(null)
    var busy by mutableStateOf(false)
    var progress by mutableStateOf<Float?>(null)

    /** Fetches the catalogue once per app run. */
    suspend fun ensureLoaded() {
        if (fetched || loading) return
        loading = true
        error = null
        TranslationDownloads.available()
            .onSuccess {
                available = it
                fetched = true
            }
            .onFailure {
                error = it.message ?: "Could not reach the download server."
            }
        loading = false
    }
}


/**
 * Settings → More translations.
 *
 * The APK ships with KJV and the Hebrew WLC; everything else is offered
 * here. The user ticks the modules they want and presses one button, which
 * downloads them in sequence (each is verified by sha256 before it is
 * activated). Installed modules can be removed again, and the picker picks
 * them up immediately thanks to [BibleCatalog.refresh].
 */
@Composable
fun TranslationsManagerSetting() {
    val scope = rememberCoroutineScope()
    var installed by remember { mutableStateOf(TranslationDownloads.installed()) }

    val available = TranslationsPanel.available
    val loading = TranslationsPanel.loading
    val error = TranslationsPanel.error
    val selected = TranslationsPanel.selected
    val message = TranslationsPanel.message
    val busy = TranslationsPanel.busy
    val progress = TranslationsPanel.progress

    LaunchedEffect(Unit) { TranslationsPanel.ensureLoaded() }

    val installedIds = installed.map { it.id }.toSet()
    val downloadSize = available
        .filter { it.id in selected && it.id !in installedIds }
        .sumOf { it.size }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = "The app ships with the King James Version and the Hebrew " +
                "Westminster Leningrad Codex. Tick any other translation and " +
                "download it once — it then works fully offline.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (installed.isNotEmpty()) {
            Text(
                text = "Downloaded",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
            installed.forEach { entry ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = entry.name.ifBlank { entry.id },
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = listOf(entry.language, formatSize(entry.size))
                                .filter { it.isNotBlank() }
                                .joinToString(" · "),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    TextButton(
                        enabled = !busy,
                        onClick = {
                            SoundManager.play(SoundEvent.Click)
                            TranslationDownloads.remove(entry.id)
                            BibleCatalog.refresh()
                            installed = TranslationDownloads.installed()
                            TranslationsPanel.message =
                                "${entry.name.ifBlank { entry.id }} removed."
                        }
                    ) {
                        Text("Remove")
                    }
                }
            }
            HorizontalDivider()
        }

        when {
            loading -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                CircularProgressIndicator(Modifier.height(18.dp))
                Text(
                    "Loading the translation list…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            error != null -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = error ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
                TextButton(onClick = {
                    scope.launch {
                        TranslationsPanel.fetched = false
                        TranslationsPanel.ensureLoaded()
                    }
                }) { Text("Try again") }
            }

            else -> {
                val downloadable = available.filter { it.id !in installedIds }
                if (downloadable.isEmpty()) {
                    Text(
                        "Every translation in the catalogue is installed.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                var lastLanguage: String? = null
                downloadable.forEach { entry ->
                    if (entry.language != lastLanguage) {
                        lastLanguage = entry.language
                        Text(
                            text = entry.language,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Checkbox(
                            checked = entry.id in selected,
                            enabled = !busy,
                            onCheckedChange = { checked ->
                                SoundManager.play(SoundEvent.Click)
                                TranslationsPanel.selected =
                                    if (checked) selected + entry.id else selected - entry.id
                            }
                        )
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = entry.name.ifBlank { entry.id },
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = formatSize(entry.size),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Button(
                    enabled = !busy && selected.isNotEmpty(),
                    onClick = {
                        val wanted = available.filter {
                            it.id in selected && it.id !in installedIds
                        }
                        if (wanted.isEmpty()) return@Button
                        SoundManager.play(SoundEvent.Click)
                        TranslationsPanel.busy = true
                        TranslationsPanel.message = null
                        TranslationsPanel.progress = 0f
                        scope.launch {
                            var done = 0
                            var failed: String? = null
                            for (entry in wanted) {
                                val result = TranslationDownloads.download(entry) { fraction ->
                                    TranslationsPanel.progress = (done + fraction) / wanted.size
                                }
                                if (result.isSuccess) {
                                    done++
                                } else {
                                    failed = result.exceptionOrNull()?.message
                                    break
                                }
                            }
                            TranslationsPanel.progress = null
                            TranslationsPanel.busy = false
                            installed = TranslationDownloads.installed()
                            if (done > 0) {
                                BibleCatalog.refresh()
                                TranslationsPanel.selected =
                                    selected - wanted.take(done).map { it.id }.toSet()
                            }
                            TranslationsPanel.message =
                                if (failed != null && done == 0) {
                                    "Download failed: $failed"
                                } else if (failed != null) {
                                    "$done translation(s) installed, then stopped: $failed"
                                } else {
                                    "$done translation(s) installed — open the picker to switch."
                                }
                        }
                    }
                ) {
                    Text(
                        if (busy) "Downloading…"
                        else if (selected.isEmpty()) "Download selected"
                        else "Download selected (${formatSize(downloadSize)})"
                    )
                }

                progress?.let { fraction ->
                    LinearProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        message?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(2.dp))
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes <= 0 -> ""
    bytes >= 1_000_000L -> "%.1f MB".format(bytes / 1_000_000.0)
    else -> "%d KB".format(bytes / 1_000L)
}
