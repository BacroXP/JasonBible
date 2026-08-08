import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.ui.window.application
import data.BibleCatalog
import data.SettingsManager
import data.SoundEvent
import data.SoundManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import navigation.Navigation
import ui.SplashScreen

// Long enough for the splash to register as "the app is opening" even
// when the warm-up below finishes in a few frames (which is the common
// case on a warm cache). If init genuinely takes longer, the delay is
// skipped and the app proceeds the moment the work completes.
private const val MIN_SPLASH_MILLIS = 900L

fun main() = application {
    // Single exit path for the window's ✕ and the in-app Quit action:
    // tear down the JavaFX media-player toolkit first (its thread is
    // non-daemon, so the JVM would hang without Platform.exit), then
    // leave the Compose application.
    fun quitApp() {
        ui.MediaPlayerController.dispose()
        exitApplication()
    }

    val windowState = rememberWindowState(
        placement = if (SettingsManager.fullScreen) {
            // "Standard" fullscreen: a maximized window with the normal
            // OS frame/title bar — not a borderless takeover of the
            // whole display (WindowPlacement.Fullscreen).
            WindowPlacement.Maximized
        } else {
            WindowPlacement.Floating
        }
    )

    // App-level keyboard shortcuts (the global Ctrl+F search) are handled
    // at the WINDOW level because `Modifier.onPreviewKeyEvent` only fires
    // while some node inside the window has focus — with nothing focused
    // (just viewing a whole chapter, a chapter list, the main menu, or the
    // editor before a click) the modifier would never see the key and
    // Ctrl+F would appear dead until the user clicked something. The
    // Window-level handler fires for EVERY key in this window regardless
    // of focus. `Navigation` registers its combined handler once it
    // composes; until then (splash) a no-op default is active.
    var windowKeyHandler by remember {
        mutableStateOf<(KeyEvent) -> Boolean>({ false })
    }

    Window(
        state = windowState,
        onCloseRequest = { quitApp() },
        title = "Bible App",
        // Window / taskbar icon on Linux & Windows. Loaded from the bundled
        // `src/desktopMain/resources/icons/Icon.png` (512px master; the
        // Icon-256.png / Icon-512.png siblings and the icon.ico / icon.icns
        // installer icons are generated from it); on macOS the Dock icon is
        // set via `iconFile` in build.gradle.kts instead (per-window icons
        // are not supported there).
        icon = painterResource("icons/Icon.png"),
        onPreviewKeyEvent = { event -> windowKeyHandler(event) }
    ) {

        // Setting `placement` at window creation is ignored by some
        // window managers (notably Wayland compositors running the app
        // through XWayland), which leaves a floating window on startup
        // despite the fullscreen setting. Re-apply the placement once
        // the window has actually been mapped so it lands maximized.
        LaunchedEffect(Unit) {
            if (SettingsManager.fullScreen) {
                delay(200)
                windowState.placement = WindowPlacement.Maximized
            }
        }

        // The color scheme follows the persisted color style (Normal /
        // Saturated / Gray / Custom) as well as the dark-mode toggle.
        // ui.AppColorStyle resolves the style key and derives a tonal
        // palette for the non-default styles, so changing either setting
        // re-themes the whole window instantly.
        MaterialTheme(
            colorScheme = ui.appColorScheme(
                dark = SettingsManager.darkMode,
                style = ui.AppColorStyle.fromKey(SettingsManager.colorStyle),
                customAccent = SettingsManager.customAccentColor
            )
        ) {

            Surface(
                modifier = Modifier.fillMaxSize()
            ) {

                // The window opens immediately; a splash with a progress
                // bar is shown while the one-time startup work (audio
                // pools, module catalog scan, cross-language alias index)
                // runs on a background thread. Previously this work ran
                // synchronously BEFORE the window existed, so the app
                // looked frozen during the scan of ~90 Bible modules.
                var appReady by remember { mutableStateOf(false) }
                var startupProgress by remember { mutableStateOf(0f) }

                LaunchedEffect(Unit) {
                    val startedAt = System.currentTimeMillis()
                    withContext(Dispatchers.Default) {
                        startupProgress = 0.05f
                        SoundManager.init()
                        // Point the persisted translation/language at a
                        // module that actually exists (first run, or the
                        // user swapped out the Bible files) and warm the
                        // module catalog so switching Bibles never stalls
                        // a composition.
                        startupProgress = 0.3f
                        BibleCatalog.normalizeSavedTranslation() // triggers the catalog scan
                        startupProgress = 0.6f
                        BibleCatalog.nameToBookNumber            // cross-language alias index
                        startupProgress = 1f
                    }
                    val elapsed = System.currentTimeMillis() - startedAt
                    if (elapsed < MIN_SPLASH_MILLIS) {
                        delay(MIN_SPLASH_MILLIS - elapsed)
                    }
                    appReady = true
                    // Boot chime, timed with the splash → main-UI handoff.
                    // The clips are guaranteed loaded (SoundManager.init
                    // ran in the background block above); SoundManager
                    // honours the mute toggle and master volume, and is a
                    // silent no-op if audio is unavailable.
                    SoundManager.play(SoundEvent.Boot)
                }

                if (appReady) {
                    Navigation(
                        quit = { quitApp() },
                        registerWindowKeyHandler = { handler ->
                            windowKeyHandler = handler
                        }
                    )
                } else {
                    SplashScreen(progress = startupProgress)
                }

                // TEMPORARY DEBUG HOOK (will be reverted): auto-play media
                // tokens on startup when -Ddebug.autoplay="a|b|c" is set.
                LaunchedEffect(appReady) {
                    if (!appReady) return@LaunchedEffect
                    val spec = System.getProperty("debug.autoplay") ?: return@LaunchedEffect
                    val tokens = spec.split('|').mapNotNull { piece ->
                        val service = data.MediaService.forKey(piece.substringBefore(':')) ?: return@mapNotNull null
                        val content = piece.substringAfter(':', "").ifBlank { return@mapNotNull null }
                        data.MediaReferenceToken(service, content, 0, 0)
                    }
                    delay(1500)
                    tokens.forEachIndexed { index, token ->
                        System.err.println("[DEBUG-AUTOPLAY] playing #$index ${token.service} ${token.content}")
                        ui.MediaPlayerController.play(token, "Debug autoplay #$index")
                        delay(15000)
                    }
                }


            }
        }
    }
}
