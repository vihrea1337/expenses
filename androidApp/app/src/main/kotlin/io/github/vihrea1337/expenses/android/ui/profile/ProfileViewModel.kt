package io.github.vihrea1337.expenses.android.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.vihrea1337.expenses.android.data.AuthRepository
import io.github.vihrea1337.expenses.android.data.ExpensesRepository
import io.github.vihrea1337.expenses.android.data.SessionStore
import io.github.vihrea1337.expenses.android.ui.common.humanMessage
import io.github.vihrea1337.expenses.android.widget.WidgetUpdater
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ProfileUiState(
    val name: String = "",
    val token: String = "",
    val expenseCount: Int = 0,
    /** Есть траты, которые ещё не уехали на сервер, — предупредить при выходе. */
    val hasUnsent: Boolean = false,
)

@HiltViewModel
class ProfileViewModel @Inject constructor(
    session: SessionStore,
    private val auth: AuthRepository,
    private val expenses: ExpensesRepository,
    private val widgetUpdater: WidgetUpdater,
) : ViewModel() {

    val state: StateFlow<ProfileUiState> = combine(
        session.session,
        expenses.observeExpenses(),
        expenses.observeUnsentCount(),
    ) { s, list, unsent ->
        ProfileUiState(name = s?.name.orEmpty(), token = s?.token.orEmpty(), expenseCount = list.size, hasUnsent = unsent > 0)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProfileUiState())

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    /** Предложить добавить виджет — окно «Добавить на главный экран?» покажет сам лаунчер. */
    fun pinWidget() {
        if (!widgetUpdater.requestPin()) {
            viewModelScope.launch {
                _messages.send("Лаунчер не умеет добавлять виджеты из приложения — удерживайте палец на рабочем столе → «Виджеты» → «Траты»")
            }
        }
    }

    /**
     * Выгрузка CSV: байты качает репозиторий, а записывает в выбранный файл экран
     * ([write] — ему нужен Context; ViewModel про Context знать не должна).
     */
    fun exportCsv(write: (ByteArray) -> Boolean) {
        viewModelScope.launch {
            val message = try {
                if (write(expenses.exportCsv())) "Файл сохранён" else "Не удалось записать файл"
            } catch (e: Exception) {
                "Выгрузка не удалась: ${e.humanMessage()}"
            }
            _messages.send(message)
        }
    }

    /** Выход: навигация подписана на сессию и сама откроет экран входа. */
    fun logout() {
        viewModelScope.launch { auth.logout() }
    }
}
