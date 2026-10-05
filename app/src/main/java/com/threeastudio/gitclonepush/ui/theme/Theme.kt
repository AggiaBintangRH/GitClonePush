package com.threeastudio.gitclonepush.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import com.threeastudio.gitclonepush.core.model.ThemeMode

private val DarkColorScheme = darkColorScheme(
    primary = BlueDark,
    secondary = Color(0xFFB8C7E8),
    tertiary = Color(0xFF96D5B9),
    background = Color(0xFF101418),
    surface = Color(0xFF101418)
)

private val LightColorScheme = lightColorScheme(
    primary = Blue,
    secondary = Slate,
    tertiary = Green,
    background = Color(0xFFF8FAFC),
    surface = Color(0xFFF8FAFC)
)

@Composable
fun GitClonePushTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    // Dynamic color is available on Android 12+
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val resolvedDarkTheme = when (themeMode) { ThemeMode.DARK -> true; ThemeMode.LIGHT -> false; ThemeMode.SYSTEM -> darkTheme }
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (resolvedDarkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        resolvedDarkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
