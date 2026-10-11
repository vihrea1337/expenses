package io.github.vihrea1337.expenses.android.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Фирменный зелёный Expenses — тот же, что на сайте (--accent: #2e7d32) и на значке,
// чтобы приложение, сайт и виджет выглядели одним продуктом.
private val Brand = Color(0xFF2E7D32)
private val BrandDark = Color(0xFF7BC67E)

/** Светлая схема. internal — её же берёт виджет на рабочем столе (widget/ExpensesWidget). */
internal val LightColors = lightColorScheme(
    primary = Brand,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFC8E6C9),
    onPrimaryContainer = Color(0xFF0B3D0F),
    secondary = Color(0xFF55624F),
    secondaryContainer = Color(0xFFDDE6D6),
    onSecondaryContainer = Color(0xFF131F10),
    tertiary = Color(0xFF2A78D6),
    background = Color(0xFFF5F7F4),
    surface = Color(0xFFF5F7F4),
    surfaceVariant = Color(0xFFE1E6DC),
    onSurfaceVariant = Color(0xFF5A6356),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color.White,
    surfaceContainer = Color(0xFFEEF1EB),
    surfaceContainerHigh = Color(0xFFE8ECE4),
    outlineVariant = Color(0xFFD7DDD2),
    error = Color(0xFFC0392B),
)

internal val DarkColors = darkColorScheme(
    primary = BrandDark,
    onPrimary = Color(0xFF07390C),
    primaryContainer = Color(0xFF1E5622),
    onPrimaryContainer = Color(0xFFC8E6C9),
    secondary = Color(0xFFBCCBB4),
    secondaryContainer = Color(0xFF3B4636),
    onSecondaryContainer = Color(0xFFD9E7D0),
    tertiary = Color(0xFF8AB4F8),
    background = Color(0xFF111411),
    surface = Color(0xFF111411),
    surfaceVariant = Color(0xFF2A3127),
    onSurfaceVariant = Color(0xFFA9B3A4),
    surfaceContainerLowest = Color(0xFF0C0F0C),
    surfaceContainerLow = Color(0xFF191D18),
    surfaceContainer = Color(0xFF1D221C),
    surfaceContainerHigh = Color(0xFF272D25),
    outlineVariant = Color(0xFF3A4237),
    error = Color(0xFFFF8A80),
)

private val AppTypography = Typography().let { base ->
    base.copy(
        headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
        bodyMedium = TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
    )
}

/**
 * Тема приложения: светлая/тёмная по системной настройке.
 *
 * dynamicColor — «Material You» (Android 12+): цвета из обоев телефона. По умолчанию
 * выключен: у продукта узнаваемый фирменный зелёный, а не случайный цвет обоев.
 */
@Composable
fun ExpensesTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colors = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colors, typography = AppTypography, content = content)
}
