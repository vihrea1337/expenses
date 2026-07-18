package io.github.vihrea1337.expenses.android.data

import kotlinx.serialization.Serializable

/**
 * Модели данных для обмена с бэкендом (те же поля, что и в JSON сервера).
 *
 * Expense — трата, которую сервер ПРИСЫЛАЕТ (с id и временем создания).
 * NewExpense — трата, которую мы ОТПРАВЛЯЕМ на сервер (без id/времени: их назначает сервер).
 *
 * @Serializable разрешает библиотеке kotlinx.serialization превращать эти классы в JSON и обратно.
 */
@Serializable
data class Expense(
    val id: String,
    val amount: Double,
    val category: String,
    val note: String? = null,
    val createdAt: String,
    // Обобщённая категория от ИИ ("еда", ...); null, пока не проставлена.
    val categoryGroup: String? = null,
)

@Serializable
data class NewExpense(
    val amount: Double,
    val category: String,
    val note: String? = null,
)

/** Месячный бюджет. monthlyBudget = null означает "бюджет не задан". */
@Serializable
data class BudgetDto(
    val monthlyBudget: Double? = null,
)

/**
 * Данные для редактирования траты (тело PUT /api/expenses/{id}).
 * categoryGroup = null — переопределить категорию через ИИ; иначе ручная правка.
 */
@Serializable
data class UpdateExpense(
    val amount: Double,
    val category: String,
    val note: String? = null,
    val categoryGroup: String? = null,
)
