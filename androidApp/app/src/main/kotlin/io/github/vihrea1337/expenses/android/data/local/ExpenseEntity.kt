package io.github.vihrea1337.expenses.android.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import io.github.vihrea1337.expenses.Expense

/**
 * Строка локального кэша трат (SQLite через Room). Почти повторяет [Expense] из общего
 * модуля shared/ — так и должно быть, это просто её "плоское" хранимое представление, плюс
 * три флага, которыми живёт офлайн-очередь:
 *
 * - [synced] = false — трата создана на телефоне, сервер о ней ещё не знает (ждёт `POST`).
 *   Пока не отправлена, редактирование просто меняет эти же поля — уйдёт вместе с POST.
 * - [dirty] = true — трата УЖЕ была на сервере ([synced] = true), но её отредактировали
 *   офлайн (или отправка правки не прошла) — ждёт `PUT`.
 * - [pendingDelete] = true — трату удалили, пока сервер не подтвердил удаление — ждёт `DELETE`.
 *   Такие строки не физически удаляются сразу: если удалить их из базы немедленно, некому
 *   будет напомнить синхронизации отправить DELETE. [io.github.vihrea1337.expenses.android.data.local.ExpenseDao.observeAll]
 *   их не показывает — экран не отличит от настоящего удаления.
 *
 * [updatedAt] — момент последнего ЛОКАЛЬНОГО изменения (создание/офлайн-правка/пометка на
 * удаление) или момент, подтверждённый сервером. Используется для разрешения конфликтов:
 * если во время синхронизации выясняется, что у сервера есть более свежая версия этой же
 * траты (например, её успели поменять с другого устройства), более новая по updatedAt
 * версия побеждает — см. ExpensesRepository.applyServerChange.
 */
@Entity(tableName = "expenses")
data class ExpenseEntity(
    @PrimaryKey val id: String,
    val amount: Double,
    val category: String,
    val note: String?,
    val createdAt: String,
    val updatedAt: String,
    val categoryGroup: String?,
    val synced: Boolean,
    val dirty: Boolean = false,
    val pendingDelete: Boolean = false,
)

/** Строка кэша → модель для экрана. */
fun ExpenseEntity.toExpense() = Expense(
    id = id,
    amount = amount,
    category = category,
    note = note,
    createdAt = createdAt,
    categoryGroup = categoryGroup,
    updatedAt = updatedAt,
)

/**
 * Ответ сервера → строка кэша, подтверждённая ([synced] = true, [dirty] и [pendingDelete] —
 * false: раз сервер это прислал, значит наша версия ему уже соответствует).
 */
fun Expense.toEntity(synced: Boolean) = ExpenseEntity(
    id = id,
    amount = amount,
    category = category,
    note = note,
    createdAt = createdAt,
    updatedAt = updatedAt,
    categoryGroup = categoryGroup,
    synced = synced,
)
