package com.jasonbible.phone

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.view.WindowInsetsControllerCompat

private val Navy = Color(0xFF0B1B3A)
private val DeepNavy = Color(0xFF071228)
private val Gold = Color(0xFFF2C14E)
private val Cream = Color(0xFFF7F1E3)
private val Muted = Color(0xFFB9C2D8)

private val JasonBibleColors = darkColorScheme(
    primary = Gold,
    onPrimary = DeepNavy,
    primaryContainer = Gold,
    onPrimaryContainer = DeepNavy,
    secondary = Gold,
    onSecondary = DeepNavy,
    background = Navy,
    onBackground = Cream,
    surface = Navy,
    onSurface = Cream,
    surfaceVariant = Color(0xFF13294D),
    onSurfaceVariant = Muted,
    outline = Color(0xFF2A4270),
    outlineVariant = Color(0xFF1B3158)
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Navy.toArgb()
        window.navigationBarColor = Navy.toArgb()
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        setContent {
            MaterialTheme(colorScheme = JasonBibleColors) {
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
