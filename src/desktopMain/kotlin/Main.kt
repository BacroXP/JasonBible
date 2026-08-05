import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.ui.window.application
import data.SettingsManager
import data.SoundManager
import navigation.Navigation

fun main() = application {
    SoundManager.init()
    val fullScreen = SettingsManager.fullScreen
    val windowState = rememberWindowState(
        placement = if (fullScreen) {
            WindowPlacement.Fullscreen
        } else {
            WindowPlacement.Floating
        }
    )

    SideEffect {
        windowState.placement = if (fullScreen) {
            WindowPlacement.Fullscreen
        } else {
            WindowPlacement.Floating
        }
    }

    Window(
        state = windowState,
        onCloseRequest = ::exitApplication,
        title = "Bible App",
        // Window / taskbar icon on Linux & Windows. Loaded from the bundled
        // `src/desktopMain/resources/icons/Icon.png` (512px, generated from
        // the 1254px master via Icon-256.png / Icon-512.png siblings); on
        // macOS the Dock icon is set via `iconFile` in build.gradle.kts
        // instead (per-window icons are not supported there).
        icon = painterResource("icons/Icon.png")
    ) {

        MaterialTheme(
            colorScheme = if (SettingsManager.darkMode) {
                darkColorScheme()
            } else {
                lightColorScheme()
            }
        ) {

            Surface(
                modifier = Modifier.fillMaxSize()
            ) {

                Navigation(
                    quit = ::exitApplication
                )

            }
        }
    }
}
