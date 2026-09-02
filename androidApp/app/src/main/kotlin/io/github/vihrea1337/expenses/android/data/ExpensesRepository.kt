package io.github.vihrea1337.expenses.android.data

import io.github.vihrea1337.expenses.Expense
import io.github.vihrea1337.expenses.NewExpense
import io.github.vihrea1337.expenses.android.data.local.ExpenseEntity
import io.github.vihrea1337.expenses.android.data.local.ExpensesDatabase
import io.github.vihrea1337.expenses.android.data.local.toEntity
import io.github.vihrea1337.expenses.android.data.local.toExpense
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDateTime
import java.util.UUID

/**
 * Офлайн-first слой данных: экран ЧИТАЕТ только отсюда (из Room, [observeExpenses]), а не
 * напрямую из сети — список доступен мгновенно и даже без связи. Сеть используется только
 * фоном, в [sync], чтобы кэш не расходился с сервером.
 *
 * Что НЕ покрыто (сознательно, чтобы не растягивать задачу): офлайн-редактирование и
 * офлайн-удаление трат — [io.github.vihrea1337.expenses.android.ExpensesViewModel.editExpense]
 * и `.deleteExpense` по-прежнему требуют сети напрямую. Оптимистично (без сети, с ретраем)
 * работает только добавление новой траты — это и была основная боль ("ввёл трату в метро,
 * а она потерялась"), редактирование/удаление случаются реже и почти всегда есть сеть.
 */
object ExpensesRepository {
    private val dao get() = ExpensesDatabase.dao

    fun observeExpenses(): Flow<List<Expense>> =
        dao.observeAll().map { rows -> rows.map { it.toExpense() } }

    /**
     * Синхронизация с сервером: сначала лучшая попытка отправить всё, что накопилось офлайн
     * (одна неудачная трата не должна мешать остальным), потом — подтянуть актуальный список
     * и обновить кэш.
     *
     * Бросает исключение, только если не удалась сама загрузка списка (`GET /api/expenses`) —
     * это единственный надёжный сигнал "мы сейчас без связи" для экрана. Отправка отдельных
     * несинхронизированных трат ошибки наружу не пробрасывает: они просто остаются
     * неотправленными и будут повторены при следующем вызове sync().
     */
    suspend fun sync() {
        pushUnsynced()

        val fromServer = ApiClient.api.getExpenses()
        dao.upsertAll(fromServer.map { it.toEntity(synced = true) })
        // Убрать из кэша то, что сервер больше не знает (удалено с другого клиента) — но не
        // трогать несинхронизированные строки: если пуш выше не удался, они ещё не долетели
        // до сервера, а значит их и не может быть в fromServer. Удалить такую строку здесь
        // означало бы потерять офлайн-трату навсегда.
        dao.pruneMissing(fromServer.map { it.id })
    }

    private suspend fun pushUnsynced() {
        for (pending in dao.unsynced()) {
            try {
                val saved = ApiClient.api.addExpense(
                    NewExpense(
                        amount = pending.amount,
                        category = pending.category,
                        note = pending.note,
                        id = pending.id, // тот же id — сервер узнает повтор и не создаст дубль
                    ),
                )
                dao.upsert(saved.toEntity(synced = true))
            } catch (e: Exception) {
                // Нет сети или сервер недоступен — оставляем synced = false, попробуем снова
                // при следующей синхронизации. Дальше по списку не прерываемся.
            }
        }
    }

    /**
     * Добавить трату немедленно в локальный кэш со своим id (см. [ExpenseEntity.synced] = false).
     * Экран обновится сразу через Flow из [observeExpenses] — до всякой сети. Отправку на
     * сервер берёт на себя ближайший [sync] (его вызывает ViewModel сразу после, и затем
     * при каждом refresh()).
     */
    suspend fun addExpenseOptimistic(amount: Double, category: String, note: String?) {
        dao.upsert(
            ExpenseEntity(
                id = UUID.randomUUID().toString(),
                amount = amount,
                category = category,
                note = note,
                createdAt = LocalDateTime.now().toString(),
                categoryGroup = null,
                synced = false,
            ),
        )
    }
}
