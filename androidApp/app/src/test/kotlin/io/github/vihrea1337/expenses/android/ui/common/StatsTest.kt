package io.github.vihrea1337.expenses.android.ui.common

import io.github.vihrea1337.expenses.Expense
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Расчёты для экранов и виджета (Stats.kt) — чистые функции, поэтому тесты обычные JVM:
 * без эмулятора, Robolectric и даже без Compose. «Сегодня» передаём явно — тест не зависит
 * от того, в какой день его запустили.
 */
class StatsTest {
    private val today = LocalDate.of(2026, 10, 11)

    private fun e(
        date: String,
        amount: Double,
        category: String = "кофе",
        group: String? = null,
        time: String = "10:00:00",
    ) = Expense(
        id = "$date-$category-$amount-$time",
        amount = amount,
        category = category,
        createdAt = "${date}T$time",
        categoryGroup = group,
    )

    @Test
    fun `inPeriod — сегодня, неделя (7 дней включая сегодня), 30 дней и всё`() {
        val list = listOf(
            e("2026-10-11", 1.0),
            e("2026-10-05", 2.0), // ровно 6 дней назад — ещё в неделе
            e("2026-10-04", 3.0), // 7 дней назад — уже нет
            e("2026-09-12", 4.0), // 29 дней назад — ещё в «30 днях»
            e("2026-09-11", 5.0), // 30 дней назад — уже нет
        )
        assertEquals(listOf(1.0), list.inPeriod(Period.TODAY, today).map { it.amount })
        assertEquals(listOf(1.0, 2.0), list.inPeriod(Period.WEEK, today).map { it.amount })
        assertEquals(listOf(1.0, 2.0, 3.0, 4.0), list.inPeriod(Period.MONTH, today).map { it.amount })
        assertEquals(5, list.inPeriod(Period.ALL, today).size)
    }

    @Test
    fun `трата с неразборчивой датой не теряется ни в одном периоде`() {
        val broken = Expense(id = "x", amount = 10.0, category = "кофе", createdAt = "вчера")
        Period.entries.forEach { p ->
            assertEquals(1, listOf(broken).inPeriod(p, today).size, "период $p")
        }
    }

    @Test
    fun `spentInMonth считает только текущий календарный месяц`() {
        val list = listOf(e("2026-10-01", 100.0), e("2026-10-11", 50.0), e("2026-09-30", 999.0))
        assertEquals(150.0, list.spentInMonth(today))
    }

    @Test
    fun `BudgetStatus — остаток, перерасход и доля для полоски`() {
        val ok = BudgetStatus(budget = 1000.0, spent = 250.0)
        assertEquals(750.0, ok.left)
        assertFalse(ok.over)
        assertEquals(0.25f, ok.fraction)

        val over = BudgetStatus(budget = 1000.0, spent = 1500.0)
        assertTrue(over.over)
        assertEquals(-500.0, over.left)
        assertEquals(1f, over.fraction, "при перерасходе полоса полная, а не 150%")

        assertEquals(0f, BudgetStatus(budget = 0.0, spent = 10.0).fraction, "нулевой бюджет — без деления на ноль")
    }

    @Test
    fun `byCategory — суммы по категориям ИИ, крупные первыми, без категории отдельно`() {
        val list = listOf(
            e("2026-10-11", 100.0, group = "еда"),
            e("2026-10-11", 300.0, category = "такси", group = "транспорт"),
            e("2026-10-10", 150.0, group = "еда"),
            e("2026-10-10", 20.0, category = "что-то", group = null),
        )
        assertEquals(
            listOf(CategoryShare("транспорт", 300.0), CategoryShare("еда", 250.0), CategoryShare(UNCATEGORIZED, 20.0)),
            list.byCategory(),
        )
    }

    @Test
    fun `trend за неделю — 7 столбиков, дни без трат нулевые`() {
        val bars = listOf(e("2026-10-11", 100.0), e("2026-10-11", 50.0), e("2026-10-07", 30.0)).trend(Period.WEEK, today)
        assertEquals(7, bars.size)
        assertEquals("5", bars.first().label) // 5 октября — первый день окна
        assertEquals("11", bars.last().label)
        assertEquals(150.0, bars.last().amount)
        assertEquals(30.0, bars.first { it.label == "7" }.amount)
        assertEquals(0.0, bars.first { it.label == "6" }.amount)
    }

    @Test
    fun `trend за всё время — по месяцам, включая пустые, до текущего`() {
        val bars = listOf(e("2026-07-15", 10.0), e("2026-09-01", 20.0)).trend(Period.ALL, today)
        assertEquals(listOf("07.26", "08.26", "09.26", "10.26"), bars.map { it.label })
        assertEquals(listOf(10.0, 0.0, 20.0, 0.0), bars.map { it.amount })
    }

    @Test
    fun `trend за сегодня не строится`() {
        assertEquals(emptyList(), listOf(e("2026-10-11", 1.0)).trend(Period.TODAY, today))
    }

    @Test
    fun `groupByDay — свежие дни сверху, внутри дня свежие сверху, сумма дня`() {
        val sections = listOf(
            e("2026-10-10", 10.0, time = "09:00:00"),
            e("2026-10-11", 20.0, time = "08:00:00"),
            e("2026-10-11", 30.0, time = "18:00:00"),
        ).groupByDay()
        assertEquals(listOf(LocalDate.of(2026, 10, 11), LocalDate.of(2026, 10, 10)), sections.map { it.date })
        assertEquals(50.0, sections.first().total)
        assertEquals(listOf(30.0, 20.0), sections.first().items.map { it.amount })
    }

    @Test
    fun `frequentCategories — частые первыми, регистр не важен, при равенстве недавние`() {
        val list = listOf(
            e("2026-10-01", 1.0, category = "Кофе"),
            e("2026-10-02", 1.0, category = "кофе"),
            e("2026-10-03", 1.0, category = "такси"),
            e("2026-10-09", 1.0, category = "обед"),
        )
        assertEquals(listOf("кофе", "обед", "такси"), list.frequentCategories())
        assertEquals(listOf("кофе"), list.frequentCategories(limit = 1))
    }
}
