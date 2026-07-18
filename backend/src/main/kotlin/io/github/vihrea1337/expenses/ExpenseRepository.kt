package io.github.vihrea1337.expenses

import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.upsert
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
                categoryGroup = row[Expenses.categoryGroup],
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

    /**
     * Удалить трату по id. Возвращает true, если строка была найдена и удалена,
     * false — если траты с таким id нет. deleteWhere возвращает число удалённых строк.
     */
    fun delete(id: UUID): Boolean = transaction {
        Expenses.deleteWhere { Expenses.id eq id } > 0
    }

    // Ключ настройки месячного бюджета в таблице Settings.
    private const val BUDGET_KEY = "monthly_budget"

    /** Прочитать месячный бюджет. null — если он не задан. */
    fun getBudget(): Double? = transaction {
        Settings.selectAll().where { Settings.key eq BUDGET_KEY }
            .firstOrNull()
            ?.get(Settings.value)
            ?.toDoubleOrNull()
    }

    /**
     * Задать месячный бюджет. Если значение null или ≤ 0 — считаем, что бюджет сброшен,
     * и удаляем настройку. upsert = "вставить или обновить, если ключ уже есть".
     */
    fun setBudget(value: Double?) = transaction {
        if (value == null || value <= 0) {
            Settings.deleteWhere { Settings.key eq BUDGET_KEY }
        } else {
            Settings.upsert {
                it[Settings.key] = BUDGET_KEY
                it[Settings.value] = value.toString()
            }
        }
    }

    /** Проставить обобщённую категорию (её вычислил ИИ) конкретной трате. */
    fun updateGroup(id: UUID, group: String) = transaction {
        Expenses.update({ Expenses.id eq id }) {
            it[categoryGroup] = group
        }
    }

    /** Все траты, у которых категория ещё не проставлена — пары (id, описание). */
    fun expensesWithoutGroup(): List<Pair<UUID, String>> = transaction {
        Expenses.selectAll().where { Expenses.categoryGroup.isNull() }
            .map { it[Expenses.id] to it[Expenses.category] }
    }
}
