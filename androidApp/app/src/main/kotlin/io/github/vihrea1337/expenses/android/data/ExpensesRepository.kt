package io.github.vihrea1337.expenses.android.data

import io.github.vihrea1337.expenses.Expense
import io.github.vihrea1337.expenses.NewExpense
import io.github.vihrea1337.expenses.UpdateExpense
import io.github.vihrea1337.expenses.android.data.local.ExpenseDao
import io.github.vihrea1337.expenses.android.data.local.ExpenseEntity
import io.github.vihrea1337.expenses.android.data.local.ExpensesDatabase
import io.github.vihrea1337.expenses.android.data.local.toEntity
import io.github.vihrea1337.expenses.android.data.local.toExpense
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDateTime
import java.util.UUID

/**
 * Офлайн-first слой данных: экран ЧИТАЕТ только отсюда (из Room, [observeExpenses]), а не
 * напрямую из сети — список доступен мгновенно и даже без связи. Сеть используется только
 * фоном, в [sync], чтобы кэш не расходился с сервером.
 *
 * Полный цикл офлайн-очереди (2026-09-02): добавление, редактирование И удаление трат все
 * работают офлайн — записываются в Room немедленно, реальная отправка на сервер (POST/PUT/
 * DELETE) идёт при следующем [sync]. Синхронизация — двусторонняя, по протоколу "что
 * изменилось после момента X" (`GET /api/expenses/changes`), с разрешением конфликтов по
 * `updatedAt`: если во время synchronization выясняется, что на сервере есть более свежая
 * версия той же траты (её успели поменять с другого устройства/клиента), она побеждает, а
 * наша неотправленная локальная правка отбрасывается — см. [applyServerChange].
 */
object ExpensesRepository {
    private val dao: ExpenseDao get() = ExpensesDatabase.dao

    // Гарантирует, что два одновременных refresh() (например, двойной тап "Обновить") не
    // погонят две параллельные синхронизации: вторая просто дождётся первой. Без этого
    // операции пусть и не портили бы данные (POST идемпотентен по id, DELETE безопасен
    // повторно), но зря дублировали бы сетевые запросы.
    private val syncMutex = Mutex()

    fun observeExpenses(): Flow<List<Expense>> =
        dao.observeAll().map { rows -> rows.map { it.toExpense() } }

    /**
     * Синхронизация с сервером: сначала лучшая попытка отправить всё, что накопилось офлайн
     * (удаления → правки → новые траты — в этом порядке, чтобы не тратить сеть на PUT для
     * записи, которую следом всё равно удалят), потом — забрать изменения с сервера дельтой.
     *
     * Каждый шаг отправки — лучшая попытка: одна неудавшаяся операция не прерывает ни
     * остальные операции этого же шага, ни синхронизацию в целом. Она просто остаётся
     * неотправленной и будет повторена при следующем вызове sync().
     *
     * Бросает исключение, только если не удалась сама загрузка изменений с сервера — это
     * единственный надёжный сигнал "мы сейчас без связи" для экрана.
     */
    suspend fun sync() = syncMutex.withLock {
        pushPendingDeletes()
        pushDirty()
        pushUnsynced()
        pullChanges()
    }

    /** Добавить трату немедленно в локальный кэш со своим id — экран обновится через Flow. */
    suspend fun addExpenseOptimistic(amount: Double, category: String, note: String?) {
        val now = LocalDateTime.now().toString()
        dao.upsert(
            ExpenseEntity(
                id = UUID.randomUUID().toString(),
                amount = amount,
                category = category,
                note = note,
                createdAt = now,
                updatedAt = now,
                categoryGroup = null,
                synced = false,
            ),
        )
    }

    /**
     * Отредактировать трату немедленно в кэше. Если сервер её ещё не видел (synced = false) —
     * правка просто меняет то, что уйдёт вместе с ещё не отправленным POST, без отдельного PUT.
     * Если уже видел — помечаем dirty, чтобы [pushDirty] отправил PUT при следующей sync().
     */
    suspend fun editExpenseOptimistic(id: String, amount: Double, category: String, note: String?, categoryGroup: String?) {
        val existing = dao.getById(id) ?: return
        dao.upsert(
            existing.copy(
                amount = amount,
                category = category,
                note = note,
                categoryGroup = categoryGroup,
                updatedAt = LocalDateTime.now().toString(),
                dirty = existing.synced,
            ),
        )
    }

    /**
     * Удалить трату немедленно (пропадает из [observeExpenses] благодаря pendingDelete в
     * ExpenseDao.observeAll). Если сервер её никогда не видел — отправлять DELETE незачем,
     * убираем локально сразу; иначе ждём отправки при следующей sync().
     */
    suspend fun deleteExpenseOptimistic(id: String) {
        val existing = dao.getById(id) ?: return
        if (!existing.synced) {
            dao.deleteById(id)
        } else {
            dao.markPendingDelete(id)
        }
    }

    private suspend fun pushPendingDeletes() {
        for (row in dao.pendingDeletes()) {
            try {
                val response = ApiClient.api.deleteExpense(row.id)
                // 404 — сервер уже не знает об этой трате (например, удалили с другого
                // клиента раньше нас); наша цель всё равно достигнута.
                if (response.isSuccessful || response.code() == 404) {
                    dao.deleteById(row.id)
                }
                // Любой другой код (5xx и т.п.) — оставляем pendingDelete, повторим позже.
            } catch (e: Exception) {
                // Нет сети — оставляем как есть.
            }
        }
    }

    private suspend fun pushDirty() {
        for (row in dao.dirtyRows()) {
            try {
                val saved = ApiClient.api.editExpense(
                    row.id,
                    UpdateExpense(amount = row.amount, category = row.category, note = row.note, categoryGroup = row.categoryGroup),
                )
                dao.upsert(saved.toEntity(synced = true))
            } catch (e: Exception) {
                // Нет сети или сервер отказал — остаётся dirty = true, попробуем снова.
            }
        }
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
                // Нет сети — оставляем synced = false, попробуем снова при следующей sync().
            }
        }
    }

    /**
     * Дельта-синхронизация: тянем страницы изменений начиная с сохранённого курсора, пока
     * сервер не скажет hasMore = false, и запоминаем новый курсор для следующего раза.
     *
     * Курсор ДЛЯ СЛЕДУЮЩЕЙ СТРАНИЦЫ внутри этого же вызова — updatedAt последней полученной
     * записи, а НЕ serverTime ответа: сервер формирует serverTime уже ПОСЛЕ выполнения
     * запроса, и теоретически что-то может записаться в базу ровно в этот момент — с
     * serverTime в качестве курсора такая запись выпала бы из выдачи навсегда. Курсор ДЛЯ
     * СЛЕДУЮЩЕГО ВЫЗОВА sync() (сохраняем после того, как весь цикл страниц закончен) —
     * наоборот, serverTime последней страницы: он безопасен и в вырожденном случае, когда
     * страница вообще пустая (тогда взять курсор из "последней записи" попросту не из чего).
     */
    private suspend fun pullChanges() {
        var since = SyncCursorStore.lastSyncedAt
        var lastServerTime: String? = since
        var hasMore = true
        while (hasMore) {
            val page = ApiClient.api.getExpensesChanges(since = since)
            for (item in page.expenses) {
                applyServerChange(item)
            }
            lastServerTime = page.serverTime
            since = page.expenses.lastOrNull()?.updatedAt ?: page.serverTime
            hasMore = page.hasMore
        }
        lastServerTime?.let { SyncCursorStore.save(it) }
    }

    /**
     * Применить одно изменение с сервера (обычную трату или надгробие) к локальному кэшу,
     * учитывая, что у нас может быть неотправленное локальное изменение той же траты —
     * решение "чья версия побеждает" вынесено в чистую функцию [serverWins] (тестируется
     * отдельно, без Room и сети).
     */
    private suspend fun applyServerChange(item: Expense) {
        val existing = dao.getById(item.id)
        if (!serverWins(item.updatedAt, existing)) {
            // Наше неотправленное изменение (правка/удаление/ещё не отправленное добавление)
            // новее серверной версии — оставляем как есть, отправим сами при следующей sync().
            return
        }

        if (item.deleted) {
            dao.deleteById(item.id)
        } else {
            // upsert полностью заменяет строку (REPLACE) — это и есть "сервер выиграл конфликт":
            // synced=true, dirty=false, pendingDelete=false сбрасываются по умолчанию в toEntity.
            dao.upsert(item.toEntity(synced = true))
        }
    }
}

/**
 * Чистое правило разрешения конфликта синхронизации: должна ли версия с сервера
 * (её updatedAt — [serverUpdatedAt]) заменить собой то, что лежит в кэше ([existing])?
 *
 * - Если локальной строки ещё нет — да, тривиально (нечего сравнивать).
 * - Если локальная строка полностью синхронизирована (нет неотправленной правки/удаления/
 *   добавления) — да, сервер как минимум не хуже, перезаписывать безопасно.
 * - Если же есть неотправленное локальное изменение — побеждает более свежее по `updatedAt`:
 *   если сервер новее (кто-то успел поменять эту же трату раньше нас, например с другого
 *   устройства) — сервер побеждает и наша неотправленная правка отбрасывается; иначе наша
 *   правка новее и должна остаться — отправим её сами при следующей синхронизации.
 *
 * Сравниваем как настоящие моменты времени, а не строки: `LocalDateTime.toString()` не
 * дополняет дробную часть секунды нулями одинаковой длины ("...10", "...1", вовсе без дроби
 * для круглой секунды), поэтому лексикографическое сравнение строк временами даёт неверный
 * порядок.
 */
internal fun serverWins(serverUpdatedAt: String, existing: ExpenseEntity?): Boolean {
    if (existing == null) return true
    val hasUnsentLocalWork = existing.pendingDelete || !existing.synced || existing.dirty
    if (!hasUnsentLocalWork) return true
    return parseUpdatedAt(serverUpdatedAt).isAfter(parseUpdatedAt(existing.updatedAt))
}

private fun parseUpdatedAt(value: String): LocalDateTime =
    runCatching { LocalDateTime.parse(value) }.getOrDefault(LocalDateTime.MIN)
