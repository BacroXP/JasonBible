package ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.decodeToImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.dp


/**
 * Loads a bundled classpath image (e.g. `icons/Icon.png`) as a Painter —
 * the non-deprecated replacement for the removed
 * `painterResource(resourcePath)` classpath overload.
 */
@Composable
internal fun rememberClasspathPainter(path: String): Painter {
    val bytes = remember(path) {
        val stream = checkNotNull(SplashScreen::class.java.getResourceAsStream("/$path")) {
            "Missing bundled resource /$path"
        }
        stream.use { it.readBytes() }
    }
    return remember(path) { BitmapPainter(bytes.decodeToImageBitmap()) }
}


/**
 * Startup splash shown while the app warms up its data layer (audio
 * pools, Bible module catalog, cross-language book-name index) off the
 * UI thread. [progress] is a 0f..1f fraction that advances as each
 * init stage completes, so the user sees the app actually opening
 * instead of a blank/black window during the (first) module scan.
 */
@Composable
fun SplashScreen(progress: Float) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Image(
                painter = rememberClasspathPainter("icons/Icon.png"),
                contentDescription = null,
                modifier = Modifier.size(96.dp)
            )

            Text(
                text = "Bible App",
                style = MaterialTheme.typography.headlineMedium
            )

            // Determinate bar so startup progress is visible; `heightIn`
            // style rounding keeps the track from looking flat.
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .width(280.dp)
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
            )

            Text(
                text = statusLabel(progress),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}


/** Human-readable caption for the current startup stage. */
private fun statusLabel(progress: Float): String = when {
    progress < 0.3f -> "Preparing…"
    progress < 0.6f -> "Loading Bible translations…"
    else -> "Indexing book names…"
}
