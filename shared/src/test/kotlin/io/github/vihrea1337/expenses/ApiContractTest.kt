package io.github.vihrea1337.expenses

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Страховка контракта: имена полей в JSON — часть договора с уже установленным Android-
 * приложением (и веб-страницей, которая разбирает JSON вручную на чистом JS). Переименование
 * поля в Kotlin молча ломает старого клиента, поэтому оно должно ронять этот тест.
 */
class ApiContractTest {
    private val json = Json { ignoreUnknownKeys = true }

    // encodeDefaults = true — чтобы увидеть в JSON и те поля, что равны значениям по
    // умолчанию: проверяем именно НАЗВАНИЯ полей. Сам сервер их не всегда шлёт, а null
    // по умолчанию клиенты подставляют сами.
    private val jsonWithDefaults = Json { encodeDefaults = true }

    @Test
    fun `имена полей траты не менялись`() {
        val expense = Expense(
            id = "e1",
            amount = 200.0,
            category = "кофе",
            note = "с собой",
            createdAt = "2026-08-12T10:00:00",
            categoryGroup = "еда",
        )

        val text = jsonWithDefaults.encodeToString(Expense.serializer(), expense)

        for (field in listOf(
            "\"id\"", "\"amount\"", "\"category\"", "\"note\"", "\"createdAt\"", "\"categoryGroup\"",
            "\"updatedAt\"", "\"deleted\"", "\"tag\"",
        )) {
            assertTrue(text.contains(field), "поле $field пропало из JSON: $text")
        }
    }

    @Test
    fun `старый ответ сервера без tag разбирается — tag становится null`() {
        val fromOldServer = """{"id":"e1","amount":100.0,"category":"кофе","createdAt":"2026-08-12T10:00:00"}"""
        val expense = json.decodeFromString(Expense.serializer(), fromOldServer)
        assertNull(expense.tag)
    }

    @Test
    fun `новая трата без id разбирается — id назначит сервер`() {
        // Так шлёт Telegram-бот и старое приложение, ещё не знающее про идемпотентность.
        val fromOldClient = """{"amount":150.0,"category":"такси"}"""
        val new = json.decodeFromString(NewExpense.serializer(), fromOldClient)

        assertEquals(150.0, new.amount)
        assertEquals("такси", new.category)
        assertNull(new.id)
        assertNull(new.note)
    }

    @Test
    fun `новая трата с client-side id сериализуется как ждёт сервер`() {
        val new = NewExpense(amount = 200.0, category = "кофе", note = null, id = "abc-123")
        val text = json.encodeToString(NewExpense.serializer(), new)
        assertTrue(text.contains("\"id\":\"abc-123\""), "id должен уйти в JSON как есть: $text")
    }

    @Test
    fun `бюджет без значения разбирается как null, а не падает`() {
        val budget = json.decodeFromString(BudgetDto.serializer(), "{}")
        assertNull(budget.monthlyBudget)
    }

    @Test
    fun `лишнее поле от нового сервера не ломает старого клиента`() {
        val fromFutureServer = """
            {"id":"e1","amount":100.0,"category":"кофе","createdAt":"2026-08-12T10:00:00",
             "совершенноНовоеПоле":42}
        """.trimIndent()

        val expense = json.decodeFromString(Expense.serializer(), fromFutureServer)

        assertEquals("e1", expense.id)
        assertNull(expense.note)
        assertNull(expense.categoryGroup)
    }
}
