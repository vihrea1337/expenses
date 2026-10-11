package io.github.vihrea1337.expenses.android.ui.expenses

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.vihrea1337.expenses.Expense
import io.github.vihrea1337.expenses.android.data.ExpensesRepository
import io.github.vihrea1337.expenses.android.di.ApplicationScope
import io.github.vihrea1337.expenses.android.sync.SyncScheduler
import io.github.vihrea1337.expenses.android.ui.common.BudgetStatus
import io.github.vihrea1337.expenses.android.ui.common.DaySection
import io.github.vihrea1337.expenses.android.ui.common.Period
import io.github.vihrea1337.expenses.android.ui.common.frequentCategories
import io.github.vihrea1337.expenses.android.ui.common.groupByDay
import io.github.vihrea1337.expenses.android.ui.common.humanMessage
import io.github.vihrea1337.expenses.android.ui.common.inPeriod
import io.github.vihrea1337.expenses.android.ui.common.spentInMonth
import io.github.vihrea1337.expenses.parseExpense
import java.time.Clock
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Всё, что рисует экран трат, — одним неизменяемым объектом. */
data class ExpensesUiState(
    val loading: Boolean = true,
    val period: Period = Period.MONTH,
    val query: String = "",
    /** Траты за период (с учётом поиска), разложенные по дням. */
    val sections: List<DaySection> = emptyList(),
    val periodTotal: Double = 0.0,
    val periodCount: Int = 0,
    /** Сколько потрачено в текущем месяце и бюджет (null — бюджет не задан). */
    val monthSpent: Double = 0.0,
    val budget: BudgetStatus? = null,
    /** Подсказки в окне быстрого ввода: частые категории. */
    val frequentCategories: List<String> = emptyList(),
    val isRefreshing: Boolean = false,
    /** Сегодняшняя дата — экран подписывает дни («Сегодня», «Вчера») относительно неё. */
    val today: LocalDate = LocalDate.now(),
)

/** Разовые события для экрана: показать сообщение или «Удалено · Отменить». */
sealed interface ExpensesEvent {
    data class Message(val text: String) : ExpensesEvent
    data class Deleted(val id: String, val label: String) : ExpensesEvent
}

/**
 * ViewModel экрана трат. Экран (Compose) только рисует [state] и передаёт сюда нажатия.
 *
 * Состояние СОБИРАЕТСЯ из потоков (combine), а не перекладывается вручную: траты из Room,
 * бюджет, выбранный период, строка поиска и «скрытые» (только что удалённые) траты. Любое
 * изменение любого из них — и состояние пересчитано. Нельзя забыть обновить, например,
 * сумму за период после удаления траты: она считается из тех же данных, что и список.
 *
 * События ([events]) — Channel, а не часть состояния: «показать снекбар» должно
 * случиться ОДИН раз. Будь оно в состоянии, после поворота экрана снекбар показался бы снова.
 */
@HiltViewModel
class ExpensesViewModel @Inject constructor(
    private val repository: ExpensesRepository,
    private val syncScheduler: SyncScheduler,
    private val clock: Clock,
    @ApplicationScope private val appScope: CoroutineScope,
) : ViewModel() {

    private val period = MutableStateFlow(Period.MONTH)
    private val query = MutableStateFlow("")
    private val refreshing = MutableStateFlow(false)

    /**
     * Удалённые, но ещё не «окончательно»: пока висит снекбар «Отменить», трата уже пропала
     * со списка, но в базе её ещё не трогали. Отмена — просто убрать id отсюда.
     */
    private val hidden = MutableStateFlow(emptySet<String>())

    private val _events = Channel<ExpensesEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    val state: StateFlow<ExpensesUiState> = combine(
        repository.observeExpenses(),
        repository.budget,
        period,
        query,
        hidden,
    ) { all, budget, p, q, h ->
        val today = LocalDate.now(clock)
        val visible = all.filterNot { it.id in h }
        val inPeriod = visible.inPeriod(p, today).filter { it.matches(q) }
        val monthSpent = visible.spentInMonth(today)
        ExpensesUiState(
            loading = false,
            period = p,
            query = q,
            sections = inPeriod.groupByDay(),
            periodTotal = inPeriod.sumOf { it.amount },
            periodCount = inPeriod.size,
            monthSpent = monthSpent,
            budget = budget?.let { BudgetStatus(it, monthSpent) },
            frequentCategories = visible.frequentCategories(),
            today = today,
        )
    }
        .combine(refreshing) { s, r -> s.copy(isRefreshing = r) }
        // WhileSubscribed(5000): пока экран виден — считаем; свернули больше чем на 5 с —
        // подписку на базу отпускаем. 5 секунд — чтобы поворот экрана не перезапускал всё.
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ExpensesUiState())

    init {
        refresh()
    }

    fun setPeriod(value: Period) = period.update { value }
    fun setQuery(value: String) = query.update { value }

    /** «Потяни вниз»: отправить офлайн-траты и забрать свежие (из бота, с сайта). */
    fun refresh() {
        viewModelScope.launch {
            refreshing.value = true
            try {
                repository.sync()
            } catch (e: Exception) {
                // Список из кэша остаётся на экране — просто предупреждаем.
                _events.send(ExpensesEvent.Message("${e.humanMessage()} — показаны сохранённые траты"))
            } finally {
                refreshing.value = false
            }
        }
    }

    /**
     * Быстрый ввод: «кофе 200» или «такси до дома 350». Разбор — та же функция parseExpense
     * из shared/, что и у Telegram-бота. false — строку разобрать не удалось (окно ввода
     * остаётся открытым и подсказывает формат).
     */
    fun addFromText(text: String, note: String, tag: String): Boolean {
        val parsed = parseExpense(text) ?: return false
        viewModelScope.launch {
            repository.add(
                amount = parsed.amount,
                category = parsed.category.trim(),
                note = note.trim().ifBlank { null },
                tag = tag.trim().removePrefix("#").ifBlank { null },
            )
            syncAfterChange(waitForAi = true)
        }
        return true
    }

    /** Правка траты. group = null — категорию заново определит ИИ на сервере. */
    fun edit(id: String, category: String, amountText: String, note: String, group: String?, tag: String): Boolean {
        val amount = amountText.replace(',', '.').replace(" ", "").toDoubleOrNull()
        if (category.isBlank() || amount == null || amount <= 0) return false
        viewModelScope.launch {
            repository.edit(
                id = id,
                amount = amount,
                category = category.trim(),
                note = note.trim().ifBlank { null },
                categoryGroup = group,
                tag = tag.trim().removePrefix("#").ifBlank { null },
            )
            syncAfterChange(waitForAi = group == null)
        }
        return true
    }

    /** Удаление с возможностью отмены: трата сразу пропадает из списка, но в базе — пока нет. */
    fun delete(expense: Expense) {
        hidden.update { it + expense.id }
        viewModelScope.launch { _events.send(ExpensesEvent.Deleted(expense.id, expense.category)) }
    }

    /** Нажали «Отменить» — трата возвращается на место. */
    fun undoDelete(id: String) = hidden.update { it - id }

    /** Снекбар закрылся без отмены — теперь удаляем по-настоящему (офлайн-очередь). */
    fun commitDelete(id: String) {
        if (id !in hidden.value) return
        viewModelScope.launch {
            repository.delete(id)
            hidden.update { it - id }
            syncAfterChange(waitForAi = false)
        }
    }

    /**
     * После изменения — сразу отправить на сервер. Сеть есть: синхронизируем здесь же, и,
     * если ждём категорию от ИИ, ещё раз через 3 секунды (сервер проставляет её в фоне).
     * Сети нет: поручаем WorkManager — он отправит, как только сеть появится, даже если
     * приложение к тому времени закроют.
     */
    private suspend fun syncAfterChange(waitForAi: Boolean) {
        val sent = runCatching { repository.sync() }.isSuccess
        if (!sent) {
            syncScheduler.syncSoon()
            return
        }
        if (waitForAi) {
            delay(3_000)
            runCatching { repository.sync() }
        }
    }

    /**
     * Экран закрыли, пока висел снекбар «Отменить», — удаление всё равно должно случиться
     * (пользователь его не отменял). viewModelScope в этот момент уже отменён, поэтому
     * запись в базу идёт в области всего приложения (appScope), а отправку на сервер
     * берёт на себя WorkManager.
     */
    override fun onCleared() {
        val pending = hidden.value
        if (pending.isEmpty()) return
        appScope.launch {
            pending.forEach { repository.delete(it) }
            syncScheduler.syncSoon()
        }
    }
}

/** Поиск по тратам: категория, заметка, тег и категория ИИ, без учёта регистра. */
private fun Expense.matches(query: String): Boolean {
    val q = query.trim()
    if (q.isEmpty()) return true
    return listOfNotNull(category, note, tag, categoryGroup).any { it.contains(q, ignoreCase = true) }
}
