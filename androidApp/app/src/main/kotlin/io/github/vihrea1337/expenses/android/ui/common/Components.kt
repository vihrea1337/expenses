package io.github.vihrea1337.expenses.android.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CardGiftcard
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.Checkroom
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LocalHospital
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Значок категории ИИ. null — категория ещё не определена (ИИ на сервере работает). */
fun categoryIcon(group: String?): ImageVector = when (group) {
    null -> Icons.Outlined.AutoAwesome
    "еда" -> Icons.Outlined.Restaurant
    "транспорт" -> Icons.Outlined.DirectionsCar
    "дом" -> Icons.Outlined.Home
    "развлечения" -> Icons.Outlined.Movie
    "здоровье" -> Icons.Outlined.LocalHospital
    "одежда" -> Icons.Outlined.Checkroom
    "связь" -> Icons.Outlined.PhoneAndroid
    "подарки" -> Icons.Outlined.CardGiftcard
    else -> Icons.Outlined.Category
}

/** Кружок со значком категории её цвета — в списке трат и в легенде аналитики. */
@Composable
fun CategoryBadge(group: String?, size: Dp = 40.dp) {
    val color = categoryColor(group)
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(color.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(categoryIcon(group), contentDescription = group, tint = color, modifier = Modifier.size(size * 0.5f))
    }
}

/** Ряд переключателей периода (Сегодня / Неделя / 30 дней / Всё). Прокручивается на узких экранах. */
@Composable
fun PeriodChips(selected: Period, onSelect: (Period) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Period.entries.forEach { p ->
            FilterChip(selected = p == selected, onClick = { onSelect(p) }, label = { Text(p.label) })
        }
    }
}

/** Полоска бюджета: цвет темы в пределах лимита, «ошибка» при перерасходе. */
@Composable
fun BudgetBar(status: BudgetStatus, modifier: Modifier = Modifier, height: Dp = 8.dp) {
    val fill: Color = if (status.over) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(height / 2))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Box(
            Modifier
                .fillMaxWidth(status.fraction)
                .height(height)
                .clip(RoundedCornerShape(height / 2))
                .background(fill),
        )
    }
}
