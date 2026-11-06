package com.x.carlauncher.ui.theme

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Automotive Themes for XCarOS.
 */
enum class AppTheme(
    val id: String,
    val displayName: String,
    val subtitle: String,
    val previewColors: List<Color>
) {
    ORIGINAL(
        id = "original",
        displayName = "Azul Cyber (Original)",
        subtitle = "Azul profundo con acentos cian y zafiro",
        previewColors = listOf(Color(0xFF060B19), Color(0xFF0D1B3A), Color(0xFF1D4ED8), Color(0xFF38BDF8))
    ),
    NEGRO_OSCURO(
        id = "negro_oscuro",
        displayName = "Negro Oscuro (OLED)",
        subtitle = "Negro absoluto para pantallas OLED con titanio",
        previewColors = listOf(Color(0xFF000000), Color(0xFF101318), Color(0xFF2563EB), Color(0xFF38BDF8))
    ),
    GRIS(
        id = "gris",
        displayName = "Gris Titanio",
        subtitle = "Antracita y grafito con texturas metalicas",
        previewColors = listOf(Color(0xFF13161B), Color(0xFF222832), Color(0xFF475569), Color(0xFF94A3B8))
    ),
    BLANCO(
        id = "blanco",
        displayName = "Blanco Platino",
        subtitle = "Alto contraste de lujo para maxima visibilidad diurna",
        previewColors = listOf(Color(0xFFF1F5F9), Color(0xFFFFFFFF), Color(0xFF2563EB), Color(0xFF0F172A))
    ),
    ROSA(
        id = "rosa",
        displayName = "Rosa Cyberpunk",
        subtitle = "Oscuro profundo con acentos neon magenta",
        previewColors = listOf(Color(0xFF0F0611), Color(0xFF240E26), Color(0xFFEC4899), Color(0xFFF472B6))
    ),
    MORADO(
        id = "morado",
        displayName = "Morado Nebulosa",
        subtitle = "Purpura espacial con reflejos amatista",
        previewColors = listOf(Color(0xFF0A0616), Color(0xFF1D123B), Color(0xFF8B5CF6), Color(0xFFA78BFA))
    )
}

/**
 * Palette data class providing theme colors for every launcher element.
 */
data class CarThemeColors(
    val theme: AppTheme,
    val background: Color,
    val surface: Color,
    val surfaceDeep: Color,
    val border: Color,
    val primary: Color,
    val primaryVariant: Color,
    val secondary: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val dockGradient: List<Color>,
    val musicGradientStart: Color,
    val musicGradientEnd: Color,
    val lyricsGradientStart: Color,
    val lyricsGradientEnd: Color,
    val isDark: Boolean = true
)

object ThemeManager {
    private const val PREFS_NAME = "x_theme_prefs"
    private const val KEY_THEME = "selected_theme_id"

    private val _currentTheme = MutableStateFlow(AppTheme.ORIGINAL)
    val currentTheme: StateFlow<AppTheme> = _currentTheme.asStateFlow()

    fun initialize(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedId = prefs.getString(KEY_THEME, AppTheme.ORIGINAL.id)
        val theme = AppTheme.values().find { it.id == savedId } ?: AppTheme.ORIGINAL
        _currentTheme.value = theme
    }

    fun setTheme(theme: AppTheme, context: Context) {
        _currentTheme.value = theme
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_THEME, theme.id)
            .apply()
    }

    fun getColors(theme: AppTheme): CarThemeColors {
        return when (theme) {
            AppTheme.ORIGINAL -> CarThemeColors(
                theme = theme,
                background = Color(0xFF060B19),
                surface = Color(0xFF0D1B3A),
                surfaceDeep = Color(0xFF091326),
                border = Color(0xFF1E3A8A),
                primary = Color(0xFF1D4ED8),
                primaryVariant = Color(0xFF1E3A8A),
                secondary = Color(0xFF38BDF8),
                textPrimary = Color.White,
                textSecondary = Color(0xFFB0BEC5),
                textTertiary = Color(0xFF607D8B),
                dockGradient = listOf(Color(0xFF0F172A), Color(0xFF080D1A), Color(0xFF0B1120)),
                musicGradientStart = Color(0xFF3B0764),
                musicGradientEnd = Color(0xFF1E1B4B),
                lyricsGradientStart = Color(0xFF1E1035),
                lyricsGradientEnd = Color(0xFF091326),
                isDark = true
            )
            AppTheme.NEGRO_OSCURO -> CarThemeColors(
                theme = theme,
                background = Color(0xFF000000),
                surface = Color(0xFF101318),
                surfaceDeep = Color(0xFF07090C),
                border = Color(0xFF232A36),
                primary = Color(0xFF2563EB),
                primaryVariant = Color(0xFF151D2A),
                secondary = Color(0xFF38BDF8),
                textPrimary = Color.White,
                textSecondary = Color(0xFF94A3B8),
                textTertiary = Color(0xFF64748B),
                dockGradient = listOf(Color(0xFF0E1117), Color(0xFF000000), Color(0xFF07090C)),
                musicGradientStart = Color(0xFF181B24),
                musicGradientEnd = Color(0xFF0A0C10),
                lyricsGradientStart = Color(0xFF131720),
                lyricsGradientEnd = Color(0xFF06080A),
                isDark = true
            )
            AppTheme.GRIS -> CarThemeColors(
                theme = theme,
                background = Color(0xFF13161B),
                surface = Color(0xFF222832),
                surfaceDeep = Color(0xFF181C23),
                border = Color(0xFF3E4A5B),
                primary = Color(0xFF475569),
                primaryVariant = Color(0xFF2D3543),
                secondary = Color(0xFF38BDF8),
                textPrimary = Color(0xFFF8FAFC),
                textSecondary = Color(0xFFCBD5E1),
                textTertiary = Color(0xFF94A3B8),
                dockGradient = listOf(Color(0xFF1D222B), Color(0xFF111418), Color(0xFF161A21)),
                musicGradientStart = Color(0xFF2C3442),
                musicGradientEnd = Color(0xFF191E27),
                lyricsGradientStart = Color(0xFF252D3A),
                lyricsGradientEnd = Color(0xFF151921),
                isDark = true
            )
            AppTheme.BLANCO -> CarThemeColors(
                theme = theme,
                background = Color(0xFFF1F5F9),
                surface = Color(0xFFFFFFFF),
                surfaceDeep = Color(0xFFE2E8F0),
                border = Color(0xFFCBD5E1),
                primary = Color(0xFF2563EB),
                primaryVariant = Color(0xFFDBEAFE),
                secondary = Color(0xFF0284C7),
                textPrimary = Color(0xFF0F172A),
                textSecondary = Color(0xFF475569),
                textTertiary = Color(0xFF64748B),
                dockGradient = listOf(Color(0xFFE2E8F0), Color(0xFFCBD5E1), Color(0xFFE2E8F0)),
                musicGradientStart = Color(0xFFEDE9FE),
                musicGradientEnd = Color(0xFFDBEAFE),
                lyricsGradientStart = Color(0xFFF3E8FF),
                lyricsGradientEnd = Color(0xFFE2E8F0),
                isDark = false
            )
            AppTheme.ROSA -> CarThemeColors(
                theme = theme,
                background = Color(0xFF0F0611),
                surface = Color(0xFF240E26),
                surfaceDeep = Color(0xFF150817),
                border = Color(0xFF701A58),
                primary = Color(0xFFEC4899),
                primaryVariant = Color(0xFF4A1238),
                secondary = Color(0xFFF472B6),
                textPrimary = Color.White,
                textSecondary = Color(0xFFFBCFE8),
                textTertiary = Color(0xFFDB2777),
                dockGradient = listOf(Color(0xFF1D0C20), Color(0xFF0C040E), Color(0xFF160818)),
                musicGradientStart = Color(0xFF5A123C),
                musicGradientEnd = Color(0xFF260619),
                lyricsGradientStart = Color(0xFF420D2C),
                lyricsGradientEnd = Color(0xFF180512),
                isDark = true
            )
            AppTheme.MORADO -> CarThemeColors(
                theme = theme,
                background = Color(0xFF0A0616),
                surface = Color(0xFF1D123B),
                surfaceDeep = Color(0xFF110A24),
                border = Color(0xFF5B21B6),
                primary = Color(0xFF8B5CF6),
                primaryVariant = Color(0xFF3B186E),
                secondary = Color(0xFFA78BFA),
                textPrimary = Color.White,
                textSecondary = Color(0xFFDDD6FE),
                textTertiary = Color(0xFF7C3AED),
                dockGradient = listOf(Color(0xFF1A0F35), Color(0xFF0A0616), Color(0xFF130B27)),
                musicGradientStart = Color(0xFF4C1D95),
                musicGradientEnd = Color(0xFF1E0B3B),
                lyricsGradientStart = Color(0xFF3B1278),
                lyricsGradientEnd = Color(0xFF130726),
                isDark = true
            )
        }
    }
}

val LocalCarThemeColors = staticCompositionLocalOf { ThemeManager.getColors(AppTheme.ORIGINAL) }

object XTheme {
    val colors: CarThemeColors
        @Composable
        get() = LocalCarThemeColors.current
}

