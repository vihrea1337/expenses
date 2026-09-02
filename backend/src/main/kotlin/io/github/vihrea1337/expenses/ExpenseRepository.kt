package io.github.vihrea1337.expenses

import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.andWhere
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
        updatedAt = row[Expenses.updatedAt].toString(),
        deleted = row[Expenses.deletedAt] != null,
    )

    /** Все (не удалённые) траты пользователя. */
    fun all(userId: UUID): List<Expense> = transaction {
        Expenses.selectAll()
            .where { (Expenses.userId eq userId) and Expenses.deletedAt.isNull() }
            .map(::rowToExpense)
    }

    /**
     * Добавить трату пользователю. Время назначает сервер.
     *
     * Идемпотентность: если клиент прислал свой [NewExpense.id] (валидный UUID) и трата с
     * таким id у этого пользователя уже есть — это повторная отправка (например, retry после
     * обрыва сети), и мы просто возвращаем уже сохранённую запись, не создавая дубль.
     * Если id не пришёл или он не похож на UUID — ведём себя как раньше: сервер сам
     * придумывает новый id (так работает, например, Telegram-бот).
     *
     * `id` — общий первичный ключ таблицы на всех пользователей, а не отдельный на каждого,
     * поэтому теоретическая коллизия (два разных пользователя одновременно "угадали" один и тот
     * же случайный UUID) упадёт с ошибкой уникальности прямо здесь, а не тихо перепутает чужие
     * траты. Вероятность такой коллизии ничтожна (UUID — 122 случайных бита), поэтому отдельно
     * не обрабатываем.
     */
    fun add(userId: UUID, new: NewExpense): Expense = transaction {
        val clientId = new.id?.let { raw -> runCatching { UUID.fromString(raw) }.getOrNull() }

        if (clientId != null) {
            val existing = Expenses.selectAll()
                .where { (Expenses.id eq clientId) and (Expenses.userId eq userId) }
                .firstOrNull()
            if (existing != null) return@transaction rowToExpense(existing)
        }

        val id = clientId ?: UUID.randomUUID()
        val now = LocalDateTime.now()
        Expenses.insert {
            it[Expenses.id] = id
            it[Expenses.amount] = new.amount.toBigDecimal()
            it[Expenses.category] = new.category
            it[Expenses.note] = new.note
            it[Expenses.createdAt] = now
            it[Expenses.updatedAt] = now
            it[Expenses.userId] = userId
        }
        Expense(
            id = id.toString(),
            amount = new.amount,
            category = new.category,
            note = new.note,
            createdAt = now.toString(),
            updatedAt = now.toString(),
        )
    }

    /**
     * Удалить трату пользователя по id (чужую не удалит — есть условие по userId).
     *
     * Мягкое удаление: строка не стирается, а помечается deleted_at + updated_at. Причина —
     * синхронизация: без "надгробия" офлайн-клиент, который не видел удаления, узнать о нём
     * никогда не сможет и "воскресит" трату при следующей синхронизации (см. changesSince).
     * Заметку чистим сразу (свободный текст — самое чувствительное в записи); категорию и
     * сумму оставляем как есть — в отличие от, например, дневниковых записей, здесь они не
     * настолько чувствительны, чтобы усложнять миграцию ради их обнуления.
     *
     * Повторное удаление уже удалённой траты (deletedAt.isNull() в условии) возвращает false —
     * так же, как раньше для несуществующей записи.
     */
    fun delete(userId: UUID, id: UUID): Boolean = transaction {
        val now = LocalDateTime.now()
        Expenses.update({ (Expenses.id eq id) and (Expenses.userId eq userId) and Expenses.deletedAt.isNull() }) {
            it[deletedAt] = now
            it[updatedAt] = now
            it[note] = null
        } > 0
    }

    /** Отредактировать трату пользователя. Вернёт обновлённую трату или null, если её нет (или она удалена). */
    fun updateExpense(
        userId: UUID,
        id: UUID,
        amount: Double,
        category: String,
        note: String?,
        categoryGroup: String?,
    ): Expense? = transaction {
        val changed = Expenses.update({
            (Expenses.id eq id) and (Expenses.userId eq userId) and Expenses.deletedAt.isNull()
        }) {
            it[Expenses.amount] = amount.toBigDecimal()
            it[Expenses.category] = category
            it[Expenses.note] = note
            it[Expenses.categoryGroup] = categoryGroup
            it[Expenses.updatedAt] = LocalDateTime.now()
        }
        if (changed == 0) return@transaction null
        Expenses.selectAll().where { Expenses.id eq id }.first().let(::rowToExpense)
    }

    /** Проставить обобщённую категорию (её вычислил ИИ) конкретной трате по id. */
    fun updateGroup(id: UUID, group: String) = transaction {
        Expenses.update({ (Expenses.id eq id) and Expenses.deletedAt.isNull() }) {
            it[categoryGroup] = group
            it[updatedAt] = LocalDateTime.now()
        }
    }

    /** Траты пользователя без категории (и не удалённые) — пары (id, описание) для переклассификации. */
    fun expensesWithoutGroup(userId: UUID): List<Pair<UUID, String>> = transaction {
        Expenses.selectAll()
            .where { (Expenses.userId eq userId) and Expenses.categoryGroup.isNull() and Expenses.deletedAt.isNull() }
            .map { it[Expenses.id] to it[Expenses.category] }
    }

    /**
     * Что изменилось после момента [since] — основа синхронизации офлайн-клиентов. Отдаёт и
     * обычные траты, и "надгробия" удалённых (`deleted = true` в DTO), по возрастанию
     * updatedAt: клиент запоминает время последней полученной записи и в следующий раз
     * просит только то, что новее.
     */
    fun changesSince(userId: UUID, since: LocalDateTime?, limit: Int): List<Expense> = transaction {
        val q = Expenses.selectAll().where { Expenses.userId eq userId }
        if (since != null) q.andWhere { Expenses.updatedAt greater since }
        q.orderBy(Expenses.updatedAt to SortOrder.ASC)
            .limit(limit)
            .map(::rowToExpense)
    }
}
