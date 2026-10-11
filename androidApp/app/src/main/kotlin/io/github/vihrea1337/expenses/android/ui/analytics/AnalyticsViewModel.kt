package io.github.vihrea1337.expenses.android.ui.analytics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.vihrea1337.expenses.android.data.ExpensesRepository
import io.github.vihrea1337.expenses.android.ui.common.BudgetStatus
import io.github.vihrea1337.expenses.android.ui.common.CategoryShare
import io.github.vihrea1337.expenses.android.ui.common.Period
import io.github.vihrea1337.expenses.android.ui.common.TrendBar
import io.github.vihrea1337.expenses.android.ui.common.byCategory
import io.github.vihrea1337.expenses.android.ui.common.date
import io.github.vihrea1337.expenses.android.ui.common.humanMessage
import io.github.vihrea1337.expenses.android.ui.common.inPeriod
import io.github.vihrea1337.expenses.android.ui.common.spentInMonth
import io.github.vihrea1337.expenses.android.ui.common.trend
import java.time.Clock
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AnalyticsUiState(
    val period: Period = Period.MONTH,
    val total: Double = 0.0,
    val count: Int = 0,
    /** Средняя трата в день за период (для «Всё» — с первой траты). */
    val perDay: Double = 0.0,
    val byCategory: List<CategoryShare> = emptyList(),
    val trend: List<TrendBar> = emptyList(),
    val monthSpent: Double = 0.0,
    val budget: Double? = null,
    val today: LocalDate = LocalDate.now(),
) {
    val budgetStatus: BudgetStatus? get() = budget?.let { BudgetStatus(it, monthSpent) }
}

/** Аналитика: всё считается из того же кэша трат чистыми функциями из ui/common/Stats.kt. */
@HiltViewModel
class AnalyticsViewModel @Inject constructor(
    private val repository: ExpensesRepository,
    private val clock: Clock,
) : ViewModel() {

    private val period = MutableStateFlow(Period.MONTH)

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    val state: StateFlow<AnalyticsUiState> = combine(repository.observeExpenses(), repository.budget, period) { all, budget, p ->
        val today = LocalDate.now(clock)
        val list = all.inPeriod(p, today)
        val total = list.sumOf { it.amount }
        val days = when (p) {
            Period.TODAY -> 1L
            Period.WEEK -> 7L
            Period.MONTH -> 30L
            Period.ALL -> list.mapNotNull { it.date() }.minOrNull()?.let { ChronoUnit.DAYS.between(it, today) + 1 } ?: 1L
        }
        AnalyticsUiState(
            period = p,
            total = total,
            count = list.size,
            perDay = total / days.coerceAtLeast(1),
            byCategory = list.byCategory(),
            trend = list.trend(p, today),
            monthSpent = all.spentInMonth(today),
            budget = budget,
            today = today,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AnalyticsUiState())

    fun setPeriod(value: Period) = period.update { value }

    /** Бюджет — настройка на сервере (её видят и сайт, и бот), поэтому нужна сеть. */
    fun setBudget(value: Double?) {
        viewModelScope.launch {
            try {
                repository.setBudget(value)
                _messages.send(if (value == null) "Бюджет убран" else "Бюджет сохранён")
            } catch (e: Exception) {
                _messages.send("Бюджет не сохранён: ${e.humanMessage()}")
            }
        }
    }
}
