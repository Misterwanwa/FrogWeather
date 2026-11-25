package com.frogweather.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.Colors
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Shapes
import androidx.compose.material.Typography
import androidx.compose.material.darkColors
import androidx.compose.material.lightColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.frogweather.WeatherType
import com.frogweather.data.AppThemeId

@Immutable
data class AppThemePalette(
    val themeId: AppThemeId,
    val materialColors: Colors,
    val sunnyGradient: List<Color>,
    val rainyGradient: List<Color>,
    val cloudyGradient: List<Color>,
    val defaultGradient: List<Color>,
    val nightGradient: List<Color>,
    val screenBackground: Color,
    val topBarBackground: Color,
    val navBarBackground: Color,
    val cardSurface: Color,
    val tileSurface: Color,
    val tileSurfaceElevated: Color,
    val accent: Color,
    val accentSecondary: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val dividerColor: Color,
    val previewSwatches: List<Color>
) {
    fun gradientForWeather(weather: WeatherType?, isNight: Boolean = false): List<Color> {
        if (isNight) return nightGradient
        return when (weather) {
            WeatherType.SUNNY -> sunnyGradient
            WeatherType.RAINY, WeatherType.THUNDERSTORM -> rainyGradient
            WeatherType.CLOUDY, WeatherType.SNOW -> cloudyGradient
            else -> defaultGradient
        }
    }
}

private val AeroLightColors = lightColors(
    primary = Color(0xFF006994),
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

fun paletteForTheme(themeId: AppThemeId, darkTheme: Boolean = true): AppThemePalette = when (themeId) {
    AppThemeId.AERO_CLASSIC -> AppThemePalette(
        themeId = themeId,
        materialColors = if (darkTheme) AeroDarkColors else AeroLightColors,
        sunnyGradient = listOf(Color(0xFF4A90E2), Color(0xFF87CEEB)),
        rainyGradient = listOf(Color(0xFF4B79A1), Color(0xFF283E51)),
        cloudyGradient = listOf(Color(0xFF757F9A), Color(0xFFD7DDE8)),
        defaultGradient = listOf(Color(0xFF616161), Color(0xFF9E9E9E)),
        nightGradient = listOf(Color(0xFF0F2027), Color(0xFF203A43), Color(0xFF2C5364)),
        screenBackground = Color(0xFF111A24),
        topBarBackground = Color(0xFF1C2733),
        navBarBackground = Color(0xFF141E28),
        cardSurface = Color.White.copy(alpha = 0.12f),
        tileSurface = Color(0xFF1C2733),
        tileSurfaceElevated = Color(0xFF2A3A4A),
        accent = Color(0xFF4FC3F7),
        accentSecondary = Color(0xFF00B4D8),
        textPrimary = Color.White,
        textSecondary = Color.White.copy(alpha = 0.72f),
        dividerColor = Color.White.copy(alpha = 0.12f),
        previewSwatches = listOf(Color(0xFF4A90E2), Color(0xFF1C2733), Color(0xFF4FC3F7))
    )

    AppThemeId.FROST_GLASS -> AppThemePalette(
        themeId = themeId,
        materialColors = darkColors(
            primary = Color(0xFF80DEEA),
            primaryVariant = Color(0xFF4BACB8),
            secondary = Color(0xFF74B9FF),
            background = Color(0xFF0E1824),
            surface = Color(0xFF172638)
        ),
        sunnyGradient = listOf(Color(0xFF2B5876), Color(0xFF74B9FF)),
        rainyGradient = listOf(Color(0xFF1F3B4D), Color(0xFF3B6978)),
        cloudyGradient = listOf(Color(0xFF4B6584), Color(0xFFA5B1C2)),
        defaultGradient = listOf(Color(0xFF2C3E50), Color(0xFF5D6D7E)),
        nightGradient = listOf(Color(0xFF0A1420), Color(0xFF1B3A57)),
        screenBackground = Color(0xFF0E1824),
        topBarBackground = Color(0xFF142232),
        navBarBackground = Color(0xFF101C2B),
        cardSurface = Color(0xFFE0F7FA).copy(alpha = 0.12f),
        tileSurface = Color(0xFF172638),
        tileSurfaceElevated = Color(0xFF223750),
        accent = Color(0xFF80DEEA),
        accentSecondary = Color(0xFF74B9FF),
        textPrimary = Color(0xFFF2FAFF),
        textSecondary = Color(0xFFD6EEF8).copy(alpha = 0.74f),
        dividerColor = Color(0xFF80DEEA).copy(alpha = 0.16f),
        previewSwatches = listOf(Color(0xFF2B5876), Color(0xFF172638), Color(0xFF80DEEA))
    )

    AppThemeId.SOLAR_WARM -> AppThemePalette(
        themeId = themeId,
        materialColors = darkColors(
            primary = Color(0xFFFFB74D),
            primaryVariant = Color(0xFFC88719),
            secondary = Color(0xFFFF8A65),
            background = Color(0xFF1E1310),
            surface = Color(0xFF2D1E1A)
        ),
        sunnyGradient = listOf(Color(0xFFE65100), Color(0xFFFFB74D)),
        rainyGradient = listOf(Color(0xFF4E342E), Color(0xFF3E2723)),
        cloudyGradient = listOf(Color(0xFF5D4037), Color(0xFF8D6E63)),
        defaultGradient = listOf(Color(0xFF4A332D), Color(0xFF7B5E57)),
        nightGradient = listOf(Color(0xFF1F120E), Color(0xFF43231C)),
        screenBackground = Color(0xFF1E1310),
        topBarBackground = Color(0xFF2A1B16),
        navBarBackground = Color(0xFF221512),
        cardSurface = Color(0xFFFFE0B2).copy(alpha = 0.12f),
        tileSurface = Color(0xFF2D1E1A),
        tileSurfaceElevated = Color(0xFF3E2A24),
        accent = Color(0xFFFFB74D),
        accentSecondary = Color(0xFFFF8A65),
        textPrimary = Color(0xFFFFF8F2),
        textSecondary = Color(0xFFFFE0B2).copy(alpha = 0.76f),
        dividerColor = Color(0xFFFFB74D).copy(alpha = 0.16f),
        previewSwatches = listOf(Color(0xFFE65100), Color(0xFF2D1E1A), Color(0xFFFFB74D))
    )

    AppThemeId.FOREST_CANOPY -> AppThemePalette(
        themeId = themeId,
        materialColors = darkColors(
            primary = Color(0xFF81C784),
            primaryVariant = Color(0xFF519657),
            secondary = Color(0xFF4DB6AC),
            background = Color(0xFF0F1D17),
            surface = Color(0xFF172921)
        ),
        sunnyGradient = listOf(Color(0xFF1B5E20), Color(0xFF66BB6A)),
        rainyGradient = listOf(Color(0xFF263238), Color(0xFF455A64)),
        cloudyGradient = listOf(Color(0xFF37474F), Color(0xFF78909C)),
        defaultGradient = listOf(Color(0xFF2E4D3B), Color(0xFF557A62)),
        nightGradient = listOf(Color(0xFF0B1913), Color(0xFF1B3B2B)),
        screenBackground = Color(0xFF0F1D17),
        topBarBackground = Color(0xFF15271F),
        navBarBackground = Color(0xFF102019),
        cardSurface = Color(0xFFC8E6C9).copy(alpha = 0.12f),
        tileSurface = Color(0xFF172921),
        tileSurfaceElevated = Color(0xFF223C30),
        accent = Color(0xFF81C784),
        accentSecondary = Color(0xFF4DB6AC),
        textPrimary = Color(0xFFF3FBF4),
        textSecondary = Color(0xFFC8E6C9).copy(alpha = 0.76f),
        dividerColor = Color(0xFF81C784).copy(alpha = 0.16f),
        previewSwatches = listOf(Color(0xFF1B5E20), Color(0xFF172921), Color(0xFF81C784))
    )

    AppThemeId.NIGHT_AURORA -> AppThemePalette(
        themeId = themeId,
        materialColors = darkColors(
            primary = Color(0xFFB388FF),
            primaryVariant = Color(0xFF805ACB),
            secondary = Color(0xFF64FFDA),
            background = Color(0xFF0E0D20),
            surface = Color(0xFF181633)
        ),
        sunnyGradient = listOf(Color(0xFF1A1446), Color(0xFF4A148C)),
        rainyGradient = listOf(Color(0xFF0D1B2A), Color(0xFF1B263B)),
        cloudyGradient = listOf(Color(0xFF24243E), Color(0xFF4B4B7A)),
        defaultGradient = listOf(Color(0xFF191932), Color(0xFF343466)),
        nightGradient = listOf(Color(0xFF070714), Color(0xFF19143B), Color(0xFF0F3D3E)),
        screenBackground = Color(0xFF0E0D20),
        topBarBackground = Color(0xFF15132C),
        navBarBackground = Color(0xFF100E24),
        cardSurface = Color(0xFFEDE7F6).copy(alpha = 0.11f),
        tileSurface = Color(0xFF181633),
        tileSurfaceElevated = Color(0xFF26234C),
        accent = Color(0xFFB388FF),
        accentSecondary = Color(0xFF64FFDA),
        textPrimary = Color(0xFFF7F4FF),
        textSecondary = Color(0xFFD1C4E9).copy(alpha = 0.76f),
        dividerColor = Color(0xFFB388FF).copy(alpha = 0.18f),
        previewSwatches = listOf(Color(0xFF1A1446), Color(0xFF181633), Color(0xFFB388FF))
    )
}

val LocalAppThemePalette = staticCompositionLocalOf {
    paletteForTheme(AppThemeId.AERO_CLASSIC, darkTheme = true)
}

object FrogTheme {
    val palette: AppThemePalette
        @Composable get() = LocalAppThemePalette.current
}

@Composable
fun FrogWeatherTheme(
    themeId: AppThemeId = AppThemeId.AERO_CLASSIC,
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val palette = paletteForTheme(themeId, darkTheme)

    CompositionLocalProvider(LocalAppThemePalette provides palette) {
        MaterialTheme(
            colors = palette.materialColors,
            typography = Typography(),
            shapes = Shapes(),
            content = content
        )
    }
}
