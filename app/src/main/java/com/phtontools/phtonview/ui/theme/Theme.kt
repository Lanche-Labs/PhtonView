package com.phtontools.phtonview.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkColorScheme = darkColorScheme(
    primary = Primary40,
    onPrimary = Primary100,
    primaryContainer = SurfaceVariantDark,
    onPrimaryContainer = Primary80,
    secondary = Secondary40,
    onSecondary = Primary100,
    secondaryContainer = SurfaceVariantDark,
    onSecondaryContainer = Secondary80,
    tertiary = Tertiary40,
    onTertiary = Primary100,
    tertiaryContainer = SurfaceVariantDark,
    onTertiaryContainer = Tertiary80,
    background = BackgroundDark,
    onBackground = OnSurfaceDark,
    surface = SurfaceDark,
    onSurface = OnSurfaceDark,
    surfaceVariant = SurfaceVariantDark,
    onSurfaceVariant = OnSurfaceVariantDark,
    outline = OutlineDark,
    error = AccentRed,
    onError = Primary100
)

private val LightColorScheme = lightColorScheme(
    primary = Primary40,
    onPrimary = Primary100,
    primaryContainer = SurfaceVariantLight,
    onPrimaryContainer = Primary40,
    secondary = Secondary40,
    onSecondary = Primary100,
    secondaryContainer = SurfaceVariantLight,
    onSecondaryContainer = Secondary40,
    tertiary = Tertiary40,
    onTertiary = Primary100,
    tertiaryContainer = SurfaceVariantLight,
    onTertiaryContainer = Tertiary40,
    background = BackgroundLight,
    onBackground = OnSurfaceLight,
    surface = SurfaceLight,
    onSurface = OnSurfaceLight,
    surfaceVariant = SurfaceVariantLight,
    onSurfaceVariant = OnSurfaceVariantLight,
    outline = OutlineLight,
    error = AccentRed,
    onError = Primary100
)

@Composable
fun PhtonViewTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            window.navigationBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
            WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
