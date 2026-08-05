import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
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
        title = "Bible App"
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
