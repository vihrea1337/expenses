package io.github.vihrea1337.expenses.android.ui.common

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Категориальная палитра (референс dataviz-гайда, провалидирована): 8 оттенков в
// фиксированном порядке, отдельно под светлую и тёмную поверхность. Цвет закреплён ЗА
// категорией (по сущности, не по величине): «еда» синяя и в кольце, и в списке, и завтра.
// «прочее», «без категории» и всё вне списка — серый.

/** Категории, которые проставляет ИИ на сервере (см. Classifier.kt в backend). */
val CATEGORY_GROUPS = listOf(
    "еда", "транспорт", "дом", "развлечения", "здоровье", "одежда", "связь", "подарки",
)

private val HUES_LIGHT = listOf(
    Color(0xFF2A78D6), Color(0xFF008300), Color(0xFFE87BA4), Color(0xFFEDA100),
    Color(0xFF1BAF7A), Color(0xFFEB6834), Color(0xFF4A3AA7), Color(0xFFE34948),
)
private val HUES_DARK = listOf(
    Color(0xFF3987E5), Color(0xFF2E9E2E), Color(0xFFD55181), Color(0xFFC98500),
    Color(0xFF199E70), Color(0xFFD95926), Color(0xFF9085E9), Color(0xFFE66767),
)
private val GRAY_LIGHT = Color(0xFF9AA0A6)
private val GRAY_DARK = Color(0xFF6B7280)

/** Фиксированный цвет категории для светлой или тёмной темы. */
fun categoryColor(group: String?, dark: Boolean): Color {
    val index = CATEGORY_GROUPS.indexOf(group)
    return when {
        index >= 0 && dark -> HUES_DARK[index]
        index >= 0 -> HUES_LIGHT[index]
        dark -> GRAY_DARK
        else -> GRAY_LIGHT
    }
}

/** То же для Compose — тема берётся из системы. */
@Composable
fun categoryColor(group: String?): Color = categoryColor(group, isSystemInDarkTheme())
