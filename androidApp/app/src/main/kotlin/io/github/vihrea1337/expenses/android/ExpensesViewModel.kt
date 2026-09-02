package io.github.vihrea1337.expenses.android

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.vihrea1337.expenses.BudgetDto
import io.github.vihrea1337.expenses.Expense
import io.github.vihrea1337.expenses.UpdateExpense
import io.github.vihrea1337.expenses.android.data.ApiClient
import io.github.vihrea1337.expenses.android.data.ExpensesRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Всё состояние экрана в одном объекте:
 *  expenses — список трат для показа (приходит из локального кэша, см. init);
 *  isLoading — идёт ли сейчас синхронизация с сервером (крутилка, блокировка кнопки);
 *  error — текст ошибки, если синхронизация не удалась (иначе null). Список при этом
 *          НЕ пропадает — это лишь предупреждение поверх уже показанных сохранённых данных.
 */
data class ExpensesUiState(
    val expenses: List<Expense> = emptyList(),
    val monthlyBudget: Double? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
)

/**
 * ViewModel — "мозг" экрана: хранит состояние, переживает поворот экрана.
 * Экран (Compose) только рисует то, что здесь лежит, и зовёт эти функции по нажатиям.
 *
 * Офлайн-first: список трат ЧИТАЕТСЯ из локального кэша (Room, через
 * [ExpensesRepository.observeExpenses]) — работает и без сети. Сеть используется только
 * фоном, чтобы кэш не расходился с сервером ([ExpensesRepository.sync]). Подробнее о том,
 * что офлайн покрыто, а что нет — см. комментарий у ExpensesRepository.
 */
class ExpensesViewModel : ViewModel() {

    private val _state = MutableStateFlow(ExpensesUiState())
    val state: StateFlow<ExpensesUiState> = _state.asStateFlow()

    init {
        // Список — реактивно из кэша: любое изменение таблицы (синхронизация, оптимистичное
        // добавление) сразу долетает до экрана, без ручных перезапросов.
        viewModelScope.launch {
            ExpensesRepository.observeExpenses().collect { list ->
                _state.update { it.copy(expenses = list.sortedByDescending { e -> e.createdAt }) }
            }
        }
        refresh()
    }

    /** Синхронизировать кэш с сервером (отправить неотправленное + подтянуть свежий список). */
    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            try {
                ExpensesRepository.sync()
                val budget = ApiClient.api.getBudget().monthlyBudget
                _state.update { it.copy(monthlyBudget = budget, isLoading = false) }
            } catch (e: Exception) {
                // Кэш уже показан подпиской выше и никуда не пропадает — просто предупреждаем,
                // что свежих данных с сервера сейчас нет.
                _state.update { it.copy(isLoading = false, error = "Нет связи с сервером — показаны сохранённые данные") }
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
     * onSuccess вызовется сразу (оптимистично, до всякой сети) — экран очистит поля ввода
     * и увидит новую трату в списке мгновенно, даже без связи.
     */
    fun addExpense(category: String, amountText: String, note: String?, onSuccess: () -> Unit) {
        // Запятую тоже принимаем как разделитель дробной части (150,5 -> 150.5).
        val amount = amountText.replace(',', '.').toDoubleOrNull()
        if (category.isBlank() || amount == null || amount <= 0) {
            _state.update { it.copy(error = "Введите категорию и сумму больше нуля") }
            return
        }
        viewModelScope.launch {
            ExpensesRepository.addExpenseOptimistic(
                amount = amount,
                category = category.trim(),
                // Пустую заметку не сохраняем (null, а не "").
                note = note?.trim()?.ifBlank { null },
            )
            onSuccess()
            refresh()     // отправить на сервер сразу, если сеть есть; если нет — уйдёт позже
            refreshSoon() // и ещё раз через пару секунд — подтянуть ИИ-категорию
        }
    }

    /**
     * Отредактировать трату. group = null — категорию переопределит ИИ; иначе ручная правка.
     * onSuccess закроет диалог. Требует сети — офлайн-редактирование не реализовано
     * (см. комментарий у ExpensesRepository, почему это сознательный выбор).
     */
    fun editExpense(id: String, category: String, amountText: String, note: String?, group: String?, onSuccess: () -> Unit) {
        val amount = amountText.replace(',', '.').toDoubleOrNull()
        if (category.isBlank() || amount == null || amount <= 0) {
            _state.update { it.copy(error = "Введите категорию и сумму больше нуля") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            try {
                ApiClient.api.editExpense(id, UpdateExpense(amount, category.trim(), note?.trim()?.ifBlank { null }, group))
                onSuccess()
                refresh()
                if (group == null) refreshSoon() // авто-категория проставится в фоне — подтянем позже
            } catch (e: Exception) {
                _state.update { it.copy(isLoading = false, error = e.message ?: "Ошибка сети") }
            }
        }
    }

    /**
     * Повторно обновить через пару секунд. Нужно, потому что обобщённую категорию сервер
     * проставляет в фоне (ИИ), и к первому refresh она может быть ещё не готова.
     */
    private fun refreshSoon() {
        viewModelScope.launch {
            delay(3000)
            refresh()
        }
    }

    /**
     * Скачать все траты в CSV. Сеть — в фоне (корутина), затем onReady отдаёт готовые байты
     * экрану, который записывает их в выбранный пользователем файл. Так ViewModel не зависит
     * от Context/файловой системы (это забота экрана).
     */
    fun exportCsv(onReady: (ByteArray) -> Unit) {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            try {
                val bytes = ApiClient.api.exportCsv().bytes()
                _state.update { it.copy(isLoading = false) }
                onReady(bytes)
            } catch (e: Exception) {
                _state.update { it.copy(isLoading = false, error = e.message ?: "Ошибка экспорта") }
            }
        }
    }

    /**
     * Удалить трату по id и обновить список. Требует сети — офлайн-удаление не реализовано
     * (см. комментарий у ExpensesRepository).
     */
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
