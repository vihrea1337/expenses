package io.github.vihrea1337.expenses

import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.LocalDateTime
import java.util.UUID

/**
 * Вся работа с таблицей expenses. Каждая операция ограничена конкретным пользователем
 * (userId) — так один пользователь не видит и не может трогать траты другого.
 */
object ExpenseRepository {

    private fun rowToExpense(row: ResultRow) = Expense(
        id = row[Expenses.id].toString(),
        amount = row[Expenses.amount].toDouble(),
        category = row[Expenses.category],
        note = row[Expenses.note],
        createdAt = row[Expenses.createdAt].toString(),
        categoryGroup = row[Expenses.categoryGroup],
    )

    /** Все траты пользователя. */
    fun all(userId: UUID): List<Expense> = transaction {
        Expenses.selectAll().where { Expenses.userId eq userId }.map(::rowToExpense)
    }

    /** Добавить трату пользователю. Сервер сам назначает id и время. */
    fun add(userId: UUID, new: NewExpense): Expense {
        val id = UUID.randomUUID()
        val now = LocalDateTime.now()
        transaction {
            Expenses.insert {
                it[Expenses.id] = id
                it[Expenses.amount] = new.amount.toBigDecimal()
                it[Expenses.category] = new.category
                it[Expenses.note] = new.note
                it[Expenses.createdAt] = now
                it[Expenses.userId] = userId
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

    /** Удалить трату пользователя по id (чужую не удалит — есть условие по userId). */
    fun delete(userId: UUID, id: UUID): Boolean = transaction {
        Expenses.deleteWhere { (Expenses.id eq id) and (Expenses.userId eq userId) } > 0
    }

    /** Отредактировать трату пользователя. Вернёт обновлённую трату или null, если её нет. */
    fun updateExpense(
        userId: UUID,
        id: UUID,
        amount: Double,
        category: String,
        note: String?,
        categoryGroup: String?,
    ): Expense? = transaction {
        val changed = Expenses.update({ (Expenses.id eq id) and (Expenses.userId eq userId) }) {
            it[Expenses.amount] = amount.toBigDecimal()
            it[Expenses.category] = category
            it[Expenses.note] = note
            it[Expenses.categoryGroup] = categoryGroup
        }
        if (changed == 0) return@transaction null
        Expenses.selectAll().where { Expenses.id eq id }.first().let(::rowToExpense)
    }

    /** Проставить обобщённую категорию (её вычислил ИИ) конкретной трате по id. */
    fun updateGroup(id: UUID, group: String) = transaction {
        Expenses.update({ Expenses.id eq id }) {
            it[categoryGroup] = group
        }
    }

    /** Траты пользователя без категории — пары (id, описание) для переклассификации. */
    fun expensesWithoutGroup(userId: UUID): List<Pair<UUID, String>> = transaction {
        Expenses.selectAll()
            .where { (Expenses.userId eq userId) and Expenses.categoryGroup.isNull() }
            .map { it[Expenses.id] to it[Expenses.category] }
    }
}
