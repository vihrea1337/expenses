package io.github.vihrea1337.expenses

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Тесты разбора строки быстрого ввода. parseExpense — чистая функция (без сети и базы),
 * поэтому её удобно проверять отдельными примерами: подали строку — ожидаем результат.
 */
class ExpenseParserTest {

    @Test
    fun `простой ввод разбирается`() {
        val result = parseExpense("кофе 200")
        assertEquals(200.0, result?.amount)
        assertEquals("кофе", result?.category)
        assertNull(result?.note)
    }

    @Test
    fun `категория из нескольких слов`() {
        val result = parseExpense("такси до дома 350")
        assertEquals(350.0, result?.amount)
        assertEquals("такси до дома", result?.category)
    }

    @Test
    fun `запятая как разделитель дробной части`() {
        assertEquals(150.5, parseExpense("обед 150,5")?.amount)
    }

    @Test
    fun `лишние пробелы игнорируются`() {
        val result = parseExpense("   кофе    200   ")
        assertEquals("кофе", result?.category)
        assertEquals(200.0, result?.amount)
    }

    @Test
    fun `одно слово — это не трата`() {
        assertNull(parseExpense("привет"))
    }

    @Test
    fun `сумма не число — это не трата`() {
        assertNull(parseExpense("кофе дорого"))
    }

    @Test
    fun `ноль и отрицательная сумма отбрасываются`() {
        assertNull(parseExpense("кофе 0"))
        assertNull(parseExpense("кофе -50"))
    }

    @Test
    fun `пустая строка — это не трата`() {
        assertNull(parseExpense(""))
        assertNull(parseExpense("   "))
    }
}
