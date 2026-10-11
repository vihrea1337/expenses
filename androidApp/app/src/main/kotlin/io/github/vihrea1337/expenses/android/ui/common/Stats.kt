package io.github.vihrea1337.expenses.android.ui.common

import io.github.vihrea1337.expenses.Expense
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter

// Расчёты для экранов и виджета — ЧИСТЫЕ функции: на вход список трат и «сегодня», на
// выход числа. Без Compose, Android и текущего времени внутри (today передаётся явно) —
// поэтому они тестируются обычными юнит-тестами за миллисекунды (StatsTest). В версии 1.x
// эти же расчёты жили private-функциями внутри экрана, и проверить их было нельзя.

/** Период для списка и аналитики. Неделя — последние 7 дней, месяц — последние 30. */
enum class Period(val label: String) {
    TODAY("Сегодня"),
    WEEK("Неделя"),
    MONTH("30 дней"),
    ALL("Всё"),
}

/** Дата траты (createdAt приходит строкой «2026-10-11T09:30:00»). null — не разобрали. */
fun Expense.date(): LocalDate? = runCatching { LocalDate.parse(createdAt.take(10)) }.getOrNull()

/** Траты, попавшие в период. Трату с неразборчивой датой не теряем — показываем всегда. */
fun List<Expense>.inPeriod(period: Period, today: LocalDate): List<Expense> = filter { e ->
    val date = e.date() ?: return@filter true
    when (period) {
        Period.TODAY -> date == today
        Period.WEEK -> !date.isBefore(today.minusDays(6))
        Period.MONTH -> !date.isBefore(today.minusDays(29))
        Period.ALL -> true
    }
}

/** Потрачено в ТЕКУЩЕМ календарном месяце — для бюджета (он задаётся на месяц). */
fun List<Expense>.spentInMonth(today: LocalDate): Double {
    val month = YearMonth.from(today)
    return filter { e -> e.date()?.let { YearMonth.from(it) } == month }.sumOf { it.amount }
}

/** Бюджет на месяц: сколько потрачено, сколько осталось и какая доля израсходована. */
data class BudgetStatus(val budget: Double, val spent: Double) {
    val left: Double get() = budget - spent
    val over: Boolean get() = spent > budget
    /** 0..1 для полоски прогресса (при перерасходе — полная полоса). */
    val fraction: Float get() = if (budget > 0) (spent / budget).toFloat().coerceIn(0f, 1f) else 0f
}

/** Доля одной категории в разбивке. */
data class CategoryShare(val group: String, val amount: Double)

/** Сумма по обобщённым категориям ИИ («еда», «транспорт»…), самые крупные — первыми. */
fun List<Expense>.byCategory(): List<CategoryShare> =
    groupBy { it.categoryGroup ?: UNCATEGORIZED }
        .map { (group, list) -> CategoryShare(group, list.sumOf { it.amount }) }
        .sortedByDescending { it.amount }

const val UNCATEGORIZED = "без категории"

/** Один столбик графика «по времени»: подпись (день/месяц) и сумма за него. */
data class TrendBar(val label: String, val amount: Double)

private val MONTH_FMT = DateTimeFormatter.ofPattern("MM.yy")

/**
 * Разбивка трат по времени для графика. Для недели и 30 дней — по дням (7 или 30
 * столбиков, включая дни без трат = 0). Для «Всё» — по месяцам (иначе столбиков было бы
 * слишком много). Для «Сегодня» график не строим (один день — тренда нет) → пустой список.
 */
fun List<Expense>.trend(period: Period, today: LocalDate): List<TrendBar> {
    if (period == Period.TODAY) return emptyList()
    val dated = mapNotNull { e -> e.date()?.let { it to e.amount } }
    if (dated.isEmpty()) return emptyList()

    if (period == Period.ALL) {
        val sums = dated.groupBy { YearMonth.from(it.first) }.mapValues { (_, v) -> v.sumOf { it.second } }
        val bars = mutableListOf<TrendBar>()
        var month = sums.keys.min()
        val last = YearMonth.from(today)
        while (!month.isAfter(last)) {
            bars += TrendBar(month.format(MONTH_FMT), sums[month] ?: 0.0)
            month = month.plusMonths(1)
        }
        return bars
    }

    val days = if (period == Period.WEEK) 7 else 30
    val start = today.minusDays((days - 1).toLong())
    val sums = dated.filter { !it.first.isBefore(start) }.groupBy { it.first }.mapValues { (_, v) -> v.sumOf { it.second } }
    return (0 until days).map { i ->
        val day = start.plusDays(i.toLong())
        TrendBar(day.dayOfMonth.toString(), sums[day] ?: 0.0)
    }
}

/** Группа списка: день, сумма за день и траты этого дня. */
data class DaySection(val date: LocalDate?, val total: Double, val items: List<Expense>)

/** Траты по дням, свежие сверху; внутри дня — по времени, свежие сверху. */
fun List<Expense>.groupByDay(): List<DaySection> =
    sortedByDescending { it.createdAt }
        .groupBy { it.date() }
        .map { (date, items) -> DaySection(date, items.sumOf { it.amount }, items) }

/**
 * Частые категории — для кнопок-подсказок в окне быстрого ввода («кофе», «такси»…):
 * чаще встречавшиеся первыми, при равенстве — недавние. Регистр не различаем.
 */
fun List<Expense>.frequentCategories(limit: Int = 6): List<String> =
    sortedByDescending { it.createdAt }
        .groupBy { it.category.trim().lowercase() }
        .filterKeys { it.isNotBlank() }
        .entries
        .sortedByDescending { it.value.size } // сортировка устойчивая: при равенстве остаётся порядок «недавние первыми»
        .take(limit)
        .map { it.key }
