package io.github.vihrea1337.expenses.android

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.vihrea1337.expenses.android.data.ApiClient
import io.github.vihrea1337.expenses.android.data.BudgetDto
import io.github.vihrea1337.expenses.android.data.Expense
import io.github.vihrea1337.expenses.android.data.NewExpense
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Всё состояние экрана в одном объекте:
 *  expenses — список трат для показа;
 *  isLoading — идёт ли сейчас запрос к серверу (крутилка, блокировка кнопки);
 *  error — текст ошибки, если что-то пошло не так (иначе null).
 */
data class ExpensesUiState(
    val expenses: List<Expense> = emptyList(),
    val monthlyBudget: Double? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
)

/**
 * ViewModel — "мозг" экрана: хранит состояние и ходит в сеть, переживает поворот экрана.
 * Экран (Compose) только рисует то, что здесь лежит, и зовёт эти функции по нажатиям.
 */
class ExpensesViewModel : ViewModel() {

    private val _state = MutableStateFlow(ExpensesUiState())
    val state: StateFlow<ExpensesUiState> = _state.asStateFlow()

    // При создании ViewModel сразу загружаем список.
    init {
        refresh()
    }

    /** Перезагрузить список трат с сервера. */
    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            try {
                val list = ApiClient.api.getExpenses()
                val budget = ApiClient.api.getBudget().monthlyBudget
                // Свежие траты — сверху (сортируем по времени создания по убыванию).
                _state.update {
                    it.copy(
                        expenses = list.sortedByDescending { e -> e.createdAt },
                        monthlyBudget = budget,
                        isLoading = false,
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(isLoading = false, error = e.message ?: "Ошибка сети") }
            }
        }
    }

    /** Задать (value > 0) или сбросить (value = null) месячный бюджет. */
    fun setBudget(value: Double?) {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            try {
                ApiClient.api.setBudget(BudgetDto(value))
                refresh()
            } catch (e: Exception) {
                _state.update { it.copy(isLoading = false, error = e.message ?: "Ошибка сети") }
            }
        }
    }

    /**
     * Добавить трату. category — категория, amountText — сумма как её ввёл пользователь (строка).
     * onSuccess вызовется после успешной отправки (экран очистит поля ввода).
     */
    fun addExpense(category: String, amountText: String, onSuccess: () -> Unit) {
        // Запятую тоже принимаем как разделитель дробной части (150,5 -> 150.5).
        val amount = amountText.replace(',', '.').toDoubleOrNull()
        if (category.isBlank() || amount == null || amount <= 0) {
            _state.update { it.copy(error = "Введите категорию и сумму больше нуля") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            try {
                ApiClient.api.addExpense(
                    NewExpense(amount = amount, category = category.trim(), note = null),
                )
                onSuccess()
                refresh() // сразу обновляем список, чтобы увидеть новую трату
            } catch (e: Exception) {
                _state.update { it.copy(isLoading = false, error = e.message ?: "Ошибка сети") }
            }
        }
    }

    /** Удалить трату по id и обновить список. */
    fun deleteExpense(id: String) {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            try {
                val response = ApiClient.api.deleteExpense(id)
                if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code()}")
                refresh()
            } catch (e: Exception) {
                _state.update { it.copy(isLoading = false, error = e.message ?: "Ошибка сети") }
            }
        }
    }
}
