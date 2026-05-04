package com.dpad.messaging.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF90CAF9),
    secondary = Color(0xFF80DEEA),
    tertiary = Color(0xFFF48FB1)
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF1565C0),
    secondary = Color(0xFF00838F),
    tertiary = Color(0xFFAD1457)
)

// Amoled pure black theme
private val AmoledColorScheme = darkColorScheme(
    primary = Color(0xFF90CAF9),
    secondary = Color(0xFF80DEEA),
    tertiary = Color(0xFFF48FB1),
    background = Color(0xFF000000),
    surface = Color(0xFF000000),
    surfaceVariant = Color(0xFF121212)
)

@Composable
fun DpadMessagingTheme(
    themeMode: Int = 0, // 0: System, 1: Light, 2: Dark, 3: Amoled
    customPrimaryColor: Int? = null,
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val darkTheme = when (themeMode) {
        1 -> false
        2, 3 -> true
        else -> isSystemInDarkTheme()
    }
    
    val isAmoled = themeMode == 3

    var colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && customPrimaryColor == null && !isAmoled -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        isAmoled -> AmoledColorScheme
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }
    
    if (customPrimaryColor != null) {
        val customColor = Color(customPrimaryColor)
        colorScheme = colorScheme.copy(
            primary = customColor,
            primaryContainer = customColor.copy(alpha = 0.3f),
            onPrimaryContainer = if (darkTheme) Color.White else Color.Black
        )
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            // Use a visually distinct status bar color so it doesn't blend into
            // the app background on light theme. surfaceVariant is slightly grey
            // on light and dark themes, making the bar always visible.
            val statusBarColor = when {
                isAmoled -> Color(0xFF000000)
                darkTheme -> colorScheme.surface
                else -> colorScheme.surfaceVariant
            }
            window.statusBarColor = statusBarColor.toArgb()
            window.navigationBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}

