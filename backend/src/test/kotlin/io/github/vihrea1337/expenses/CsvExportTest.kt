package io.github.vihrea1337.expenses

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Тесты сборки CSV-файла (функция buildCsv). Проверяем формат, от которого зависит,
 * откроется ли файл корректно в Excel: метку кодировки (BOM), разделитель «;»,
 * экранирование спецсимволов, формат суммы и обработку пустых полей.
 */
class CsvExportTest {

    private fun expense(
        amount: Double,
        category: String,
        note: String? = null,
        createdAt: String = "2026-07-18T04:27:31",
        group: String? = null,
        tag: String? = null,
    ) = Expense(
        id = "id",
        amount = amount,
        category = category,
        note = note,
        createdAt = createdAt,
        categoryGroup = group,
        tag = tag,
    )

    @Test
    fun `файл начинается с BOM и строки заголовка`() {
        val csv = buildCsv(emptyList())
        assertTrue(csv.startsWith("﻿"), "нет BOM в начале файла")
        val firstLine = csv.split("\r\n").first()
        assertEquals("﻿Дата;Категория;Сумма;Категория ИИ;Тег;Заметка", firstLine)
    }

    @Test
    fun `простая трата — поля по порядку, дата укорочена, целая сумма без точки`() {
        val csv = buildCsv(listOf(expense(amount = 200.0, category = "кофе", group = "еда", tag = "поездка")))
        val line = csv.split("\r\n")[1]
        assertEquals("2026-07-18 04:27;кофе;200;еда;поездка;", line)
    }

    @Test
    fun `дробная сумма сохраняет точку, пустые поля пустые`() {
        val csv = buildCsv(listOf(expense(amount = 149.5, category = "обед")))
        val line = csv.split("\r\n")[1]
        // categoryGroup, tag и note не заданы → три пустых поля в конце.
        assertEquals("2026-07-18 04:27;обед;149.5;;;", line)
    }

    @Test
    fun `точка с запятой в поле оборачивается в кавычки`() {
        val csv = buildCsv(listOf(expense(amount = 100.0, category = "еда; напитки", note = "обед")))
        val line = csv.split("\r\n")[1]
        assertEquals("2026-07-18 04:27;\"еда; напитки\";100;;;обед", line)
    }

    @Test
    fun `кавычки внутри поля удваиваются`() {
        val csv = buildCsv(listOf(expense(amount = 100.0, category = "кафе", note = "он \"молодец\"", group = "еда")))
        val line = csv.split("\r\n")[1]
        assertEquals("2026-07-18 04:27;кафе;100;еда;;\"он \"\"молодец\"\"\"", line)
    }

    @Test
    fun `каждая строка заканчивается CRLF`() {
        val csv = buildCsv(listOf(expense(amount = 10.0, category = "a")))
        // Заголовок + одна трата + завершающий CRLF → 3 части при split, последняя пустая.
        val parts = csv.split("\r\n")
        assertEquals(3, parts.size)
        assertEquals("", parts.last())
    }
}
