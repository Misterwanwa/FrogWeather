package com.frogweather.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val AeroLightColors = lightColors(
    primary = Color(0xFF006994),        // bluish tone reminiscent of Vista
    primaryVariant = Color(0xFF004C6D),
    secondary = Color(0xFF00B4D8),
    background = Color(0xFFE3F2FD),
    surface = Color.White.copy(alpha = 0.7f),
    onPrimary = Color.White,
    onSecondary = Color.Black,
    onBackground = Color.Black,
    onSurface = Color.Black
)

private val AeroDarkColors = darkColors(
    primary = Color(0xFF81D4FA),
    primaryVariant = Color(0xFF4BA3C7),
    secondary = Color(0xFF29B6F6),
    background = Color(0xFF121212),
    surface = Color.White.copy(alpha = 0.12f)
)

@Composable
fun FrogWeatherTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colors = if (darkTheme) AeroDarkColors else AeroLightColors

    MaterialTheme(
        colors = colors,
        typography = Typography(),
        shapes = Shapes(),
        content = content
    )
}
