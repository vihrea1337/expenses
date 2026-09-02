package io.github.vihrea1337.expenses.android.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import io.github.vihrea1337.expenses.Expense

/**
 * Строка локального кэша трат (SQLite через Room). Почти повторяет [Expense] из общего
 * модуля shared/ — так и должно быть, это просто её "плоское" хранимое представление.
 *
 * [synced] — ключевое поле для офлайн-режима: false значит "эта трата создана на телефоне,
 * но ещё не подтверждена сервером" (либо только что добавлена офлайн, либо отправка на
 * сервер сорвалась по сети). Такие строки при синхронизации не удаляются и повторно
 * отправляются — иначе трата, добавленная без связи, потерялась бы бесследно.
 */
@Entity(tableName = "expenses")
data class ExpenseEntity(
    @PrimaryKey val id: String,
    val amount: Double,
    val category: String,
    val note: String?,
    val createdAt: String,
    val categoryGroup: String?,
    val synced: Boolean,
)

/** Строка кэша → модель для экрана. */
fun ExpenseEntity.toExpense() = Expense(
    id = id,
    amount = amount,
    category = category,
    note = note,
    createdAt = createdAt,
    categoryGroup = categoryGroup,
)

/** Ответ сервера → строка кэша, подтверждённая (synced = true). */
fun Expense.toEntity(synced: Boolean) = ExpenseEntity(
    id = id,
    amount = amount,
    category = category,
    note = note,
    createdAt = createdAt,
    categoryGroup = categoryGroup,
    synced = synced,
)
