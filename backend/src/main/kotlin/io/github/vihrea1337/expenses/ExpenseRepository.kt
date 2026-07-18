package io.github.vihrea1337.expenses

import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.LocalDateTime
import java.util.UUID

/**
 * Вся работа с таблицей expenses собрана в одном месте.
 *
 * Зачем: трату добавляют ДВА клиента — REST-эндпоинт (для Android) и Telegram-бот.
 * Чтобы не дублировать одинаковый код записи в двух местах, оба зовут одни и те же
 * функции отсюда. Это правило "не повторяйся" (DRY): логика в одном месте — правишь
 * её один раз, и меняется везде.
 */
object ExpenseRepository {

    /** Достать все траты из базы (для GET /api/expenses). */
    fun all(): List<Expense> = transaction {
        Expenses.selectAll().map { row ->
            Expense(
                id = row[Expenses.id].toString(),
                amount = row[Expenses.amount].toDouble(),
                category = row[Expenses.category],
                note = row[Expenses.note],
                createdAt = row[Expenses.createdAt].toString(),
            )
        }
    }

    /**
     * Добавить трату. На вход — NewExpense (без id и времени: их присылает клиент не сам).
     * Сервер САМ назначает уникальный id и текущее время, кладёт строку в базу и возвращает
     * полноценную Expense (уже с id и createdAt) — её и покажем клиенту.
     */
    fun add(new: NewExpense): Expense {
        val id = UUID.randomUUID()
        val now = LocalDateTime.now()
        transaction {
            Expenses.insert {
                it[Expenses.id] = id
                it[Expenses.amount] = new.amount.toBigDecimal()
                it[Expenses.category] = new.category
                it[Expenses.note] = new.note
                it[Expenses.createdAt] = now
            }
        }
        return Expense(
            id = id.toString(),
            amount = new.amount,
            category = new.category,
            note = new.note,
            createdAt = now.toString(),
        )
    }
}
