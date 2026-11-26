package com.jasonbible.phone

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.core.view.WindowInsetsControllerCompat
import data.SettingsManager
import ui.AppColorStyle
import ui.appColorScheme
import ui.appTypography


/**
 * Hosts the app inside the SAME theme the desktop build uses (`ui.AppTheme`):
 * the Pages palette, the serif typography and the Normal / Saturated /
 * Gray / Custom color styles all come from SettingsManager, so changing a
 * colour style in Settings restyles the phone app exactly like the desktop.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val dark = SettingsManager.darkMode
            val style = AppColorStyle.fromKey(SettingsManager.colorStyle)
            val accent = SettingsManager.customAccentColor
            val scheme = appColorScheme(dark, style, accent)

            // Match the system bars to the current Pages background.
            window.statusBarColor = scheme.background.toArgb()
            window.navigationBarColor = scheme.background.toArgb()
            WindowInsetsControllerCompat(window, window.decorView).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }

            MaterialTheme(
                colorScheme = scheme,
                typography = appTypography()
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    PhoneApp()
                }
            }
        }
    }
}
