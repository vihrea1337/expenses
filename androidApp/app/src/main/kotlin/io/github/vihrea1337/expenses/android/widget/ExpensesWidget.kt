package io.github.vihrea1337.expenses.android.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.material3.ColorProviders
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import io.github.vihrea1337.expenses.android.MainActivity
import io.github.vihrea1337.expenses.android.data.ExpensesRepository
import io.github.vihrea1337.expenses.android.data.SessionStore
import io.github.vihrea1337.expenses.android.ui.common.BudgetStatus
import io.github.vihrea1337.expenses.android.ui.common.money
import io.github.vihrea1337.expenses.android.ui.common.monthName
import io.github.vihrea1337.expenses.android.ui.common.spentInMonth
import io.github.vihrea1337.expenses.android.ui.theme.DarkColors
import io.github.vihrea1337.expenses.android.ui.theme.LightColors
import java.time.LocalDate
import kotlinx.coroutines.flow.first

/**
 * Виджет на рабочий стол: «Октябрь — потрачено 12 340 ₽ из 40 000» с полоской бюджета и
 * кнопкой «+», которая сразу открывает окно быстрого ввода траты.
 *
 * Glance — библиотека Jetpack, где виджет пишется в стиле Compose (Column, Row, Text), а
 * под капотом превращается в RemoteViews — единственное, что умеет рисовать лаунчер:
 * виджет живёт не в нашем процессе, а в процессе рабочего стола. Отсюда ограничения:
 * только свои компоненты Glance (не обычные composable из приложения), никаких Canvas.
 *
 * Данные — из того же Room-кэша, что и у приложения, поэтому виджет работает без сети.
 * Обновляется сам: WidgetUpdater подписан на траты и бюджет (см. ExpensesApp).
 */
class ExpensesWidget : GlanceAppWidget() {

    /**
     * Виджет создаёт система, а не Hilt, поэтому зависимости в конструктор не придут.
     * EntryPoint — «окно» в граф Hilt для таких классов: просим у него готовые объекты.
     */
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Dependencies {
        fun expenses(): ExpensesRepository
        fun session(): SessionStore
    }

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val deps = EntryPointAccessors.fromApplication(context, Dependencies::class.java)
        val expenses = deps.expenses()
        // Первые значения читаем заранее — чтобы виджет сразу показал сумму, а не ноль.
        val initialExpenses = expenses.observeExpenses().first()
        val initialBudget = expenses.budget.first()

        provideContent {
            val list by expenses.observeExpenses().collectAsState(initialExpenses)
            val budget by expenses.budget.collectAsState(initialBudget)
            val session by deps.session().session.collectAsState()
            val today = LocalDate.now()
            GlanceTheme(colors = WidgetColors) {
                WidgetContent(
                    context = context,
                    loggedIn = session != null,
                    month = monthName(today).replaceFirstChar { it.uppercase() },
                    spent = list.spentInMonth(today),
                    budget = budget,
                )
            }
        }
    }

    private companion object {
        // Те же цвета, что у приложения (светлая/тёмная — по теме телефона).
        val WidgetColors = ColorProviders(light = LightColors, dark = DarkColors)
    }
}

@Composable
private fun WidgetContent(context: Context, loggedIn: Boolean, month: String, spent: Double, budget: Double?) {
    val openApp = actionStartActivity(Intent(context, MainActivity::class.java))
    val addExpense = actionStartActivity(
        Intent(context, MainActivity::class.java).setAction(MainActivity.ACTION_ADD_EXPENSE),
    )
    val colors = GlanceTheme.colors

    Column(
        GlanceModifier
            .fillMaxSize()
            .background(colors.widgetBackground)
            .cornerRadius(20.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .clickable(openApp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!loggedIn) {
            Text("Траты", style = TextStyle(color = colors.onSurface, fontSize = 16.sp, fontWeight = FontWeight.Medium))
            Text("Войдите в приложение", style = TextStyle(color = colors.onSurfaceVariant, fontSize = 13.sp))
            return@Column
        }
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(GlanceModifier.defaultWeight()) {
                Text(month, style = TextStyle(color = colors.onSurfaceVariant, fontSize = 13.sp))
                Text(money(spent), style = TextStyle(color = colors.onSurface, fontSize = 24.sp, fontWeight = FontWeight.Bold))
            }
            // Кнопка «+» — круг фирменного цвета. Нажатие — своё действие, не «открыть приложение».
            Box(
                GlanceModifier
                    .size(44.dp)
                    .cornerRadius(22.dp)
                    .background(colors.primary)
                    .clickable(addExpense),
                contentAlignment = Alignment.Center,
            ) {
                Text("+", style = TextStyle(color = colors.onPrimary, fontSize = 26.sp, fontWeight = FontWeight.Bold))
            }
        }
        Spacer(GlanceModifier.height(8.dp))
        if (budget == null) {
            Text("Бюджет на месяц не задан", style = TextStyle(color = colors.onSurfaceVariant, fontSize = 12.sp))
        } else {
            val status = BudgetStatus(budget, spent)
            LinearProgressIndicator(
                progress = status.fraction,
                modifier = GlanceModifier.fillMaxWidth().height(6.dp),
                color = if (status.over) colors.error else colors.primary,
                backgroundColor = colors.surfaceVariant,
            )
            Spacer(GlanceModifier.height(4.dp))
            Text(
                if (status.over) "Перерасход ${money(-status.left)}" else "из ${money(budget)} · осталось ${money(status.left)}",
                style = TextStyle(color = if (status.over) colors.error else colors.onSurfaceVariant, fontSize = 12.sp),
                maxLines = 1,
            )
        }
    }
}

/** Приёмник системных событий виджета (добавили на стол, пора обновить) — отдаёт их Glance. */
class ExpensesWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ExpensesWidget()
}
