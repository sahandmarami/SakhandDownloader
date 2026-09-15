package com.sakhand.downloadmanager.ui.theme

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * کنترل تم — روشن یا تیره، با ذخیره انتخاب کاربر
 */
object ThemeController {

    private const val PREFS = "settings"
    private const val KEY_LIGHT = "light_theme"

    val isLight = MutableStateFlow(false)

    fun load(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        isLight.value = prefs.getBoolean(KEY_LIGHT, false)
    }

    fun setLight(context: Context, light: Boolean) {
        isLight.value = light
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_LIGHT, light).apply()
    }
}

/**
 * رنگ‌های وابسته به تم — هر صفحه از این‌ها استفاده می‌کند تا در هر دو تم درست دیده شود
 */
@Immutable
data class AppColors(
    val background: Color,
    val surface: Color,
    val card: Color,
    val cardBorder: Color,
    val track: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val isDark: Boolean
)

fun darkAppColors() = AppColors(
    background = BackgroundDark,
    surface = SurfaceDark,
    card = CardDark,
    cardBorder = CardBorder,
    track = TrackGray,
    textPrimary = TextPrimary,
    textSecondary = TextSecondary,
    isDark = true
)

fun lightAppColors() = AppColors(
    background = Color(0xFFF5F6FC),
    surface = Color(0xFFFFFFFF),
    card = Color(0xFFFFFFFF),
    cardBorder = Color(0xFFE3E5F2),
    track = Color(0xFFEAECF6),
    textPrimary = Color(0xFF181C33),
    textSecondary = Color(0xFF676D8C),
    isDark = false
)

val LocalAppColors = staticCompositionLocalOf { darkAppColors() }

/** دسترسی کوتاه در کامپوزیت‌ها: AppTheme.colors.card */
object AppTheme {
    val colors: AppColors
        @Composable get() = LocalAppColors.current
}

private val DarkScheme = darkColorScheme(
    primary = Purple,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF3B2A6B),
    onPrimaryContainer = Color(0xFFE9DFFC),
    secondary = Blue,
    onSecondary = Color.White,
    tertiary = Pink,
    background = BackgroundDark,
    onBackground = TextPrimary,
    surface = SurfaceDark,
    onSurface = TextPrimary,
    surfaceVariant = CardDark,
    onSurfaceVariant = TextSecondary,
    error = AccentRed,
    onError = Color.Black,
    outline = CardBorder,
    outlineVariant = CardBorder
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF6D3AE8),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE9DFFC),
    onPrimaryContainer = Color(0xFF2B1B57),
    secondary = Blue,
    onSecondary = Color.White,
    tertiary = Pink,
    background = lightAppColors().background,
    onBackground = lightAppColors().textPrimary,
    surface = Color.White,
    onSurface = lightAppColors().textPrimary,
    surfaceVariant = Color(0xFFF0F1FA),
    onSurfaceVariant = lightAppColors().textSecondary,
    error = Color(0xFFDC2626),
    onError = Color.White,
    outline = lightAppColors().cardBorder,
    outlineVariant = lightAppColors().cardBorder
)

@Composable
fun SakhandTheme(
    isLight: Boolean,
    content: @Composable () -> Unit
) {
    val palette = if (isLight) lightAppColors() else darkAppColors()
    androidx.compose.runtime.CompositionLocalProvider(LocalAppColors provides palette) {
        MaterialTheme(
            colorScheme = if (isLight) LightScheme else DarkScheme,
            typography = AppTypography,
            content = content
        )
    }
}
