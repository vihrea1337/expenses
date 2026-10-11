package io.github.vihrea1337.expenses.android.data

import io.github.vihrea1337.expenses.BudgetDto
import io.github.vihrea1337.expenses.Expense
import io.github.vihrea1337.expenses.NewExpense
import io.github.vihrea1337.expenses.UpdateExpense
import io.github.vihrea1337.expenses.android.data.local.ExpenseDao
import io.github.vihrea1337.expenses.android.data.local.ExpenseEntity
import io.github.vihrea1337.expenses.android.data.local.toEntity
import io.github.vihrea1337.expenses.android.data.local.toExpense
import java.time.LocalDateTime
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Траты и бюджет. Экраны и виджет знают только этот ИНТЕРФЕЙС — не знают ни про Room, ни
 * про Retrofit. Поэтому в юнит-тестах ViewModel вместо него подставляется простой фейк
 * без сети и базы (см. src/test), а связку интерфейс → реализация задаёт RepositoryModule.
 */
interface ExpensesRepository {
    /** Траты из локального кэша — работает и без сети; Flow сам приходит заново при изменениях. */
    fun observeExpenses(): Flow<List<Expense>>

    /** Сколько изменений ждёт отправки на сервер (чтобы предупредить при выходе). */
    fun observeUnsentCount(): Flow<Int>

    /** Месячный бюджет (копия с сервера, null — не задан). */
    val budget: Flow<Double?>

    /** Отправить накопленное офлайн и забрать изменения с сервера. Бросает, если сети нет. */
    suspend fun sync()

    suspend fun add(amount: Double, category: String, note: String?, tag: String?)
    suspend fun edit(id: String, amount: Double, category: String, note: String?, categoryGroup: String?, tag: String?)
    suspend fun delete(id: String)

    /** Задать бюджет — сразу на сервере (это настройка, а не трата: офлайн-очередь не нужна). */
    suspend fun setBudget(value: Double?)

    /** Все траты в CSV (байты файла) — делает сервер. */
    suspend fun exportCsv(): ByteArray

    /** Стереть локальный кэш — при выходе из аккаунта и перед входом в другой. */
    suspend fun clearLocalData()
}

/**
 * Офлайн-first реализация: экран ЧИТАЕТ только из Room ([observeExpenses]), а не напрямую из
 * сети — список доступен мгновенно и даже без связи. Сеть используется только в [sync].
 *
 * Полный цикл офлайн-очереди: добавление, редактирование И удаление трат работают офлайн —
 * записываются в Room немедленно, реальная отправка на сервер (POST/PUT/DELETE) идёт при
 * следующем [sync]. Синхронизация — двусторонняя, по протоколу "что изменилось после момента
 * X" (`GET /api/expenses/changes`), с разрешением конфликтов по `updatedAt`: если на сервере
 * есть более свежая версия той же траты (её успели поменять с другого устройства или в боте),
 * она побеждает, а наша неотправленная локальная правка отбрасывается — см. [serverWins].
 *
 * В версии 2.0 логика синхронизации не менялась — она переехала из `object ExpensesRepository`
 * (глобальный объект, ходил в глобальные ApiClient/ExpensesDatabase/SyncCursorStore) в класс,
 * которому всё нужное передаётся в конструктор. Это и позволило покрыть её тестом с
 * поддельным сервером (ExpensesRepositorySyncTest).
 */
@Singleton
class ExpensesRepositoryImpl @Inject constructor(
    private val api: ExpensesApi,
    private val dao: ExpenseDao,
    private val settings: SettingsStore,
) : ExpensesRepository {

    // Гарантирует, что две одновременные синхронизации (экран + фоновый WorkManager) не
    // пойдут параллельно: вторая просто дождётся первой. Без этого операции пусть и не
    // портили бы данные (POST идемпотентен по id, DELETE безопасен повторно), но зря
    // дублировали бы сетевые запросы.
    private val syncMutex = Mutex()

    override fun observeExpenses(): Flow<List<Expense>> =
        dao.observeAll().map { rows -> rows.map { it.toExpense() } }

    override fun observeUnsentCount(): Flow<Int> = dao.observeUnsentCount()

    override val budget: Flow<Double?> get() = settings.budget

    /**
     * Сначала лучшая попытка отправить всё, что накопилось офлайн (удаления → правки → новые
     * траты — в этом порядке, чтобы не тратить сеть на PUT для записи, которую следом всё
     * равно удалят), потом — забрать изменения с сервера дельтой и свежий бюджет.
     *
     * Каждый шаг отправки — лучшая попытка: одна неудавшаяся операция не прерывает остальные.
     * Она просто остаётся неотправленной и будет повторена при следующем вызове sync().
     * Исключение летит, только если не удалась загрузка изменений — это надёжный сигнал
     * "мы сейчас без связи" для экрана и для WorkManager (он повторит позже сам).
     */
    override suspend fun sync() = syncMutex.withLock {
        pushPendingDeletes()
        pushDirty()
        pushUnsynced()
        pullChanges()
        settings.saveBudget(api.getBudget().monthlyBudget)
    }

    /** Добавить трату немедленно в локальный кэш со своим id — экран обновится через Flow. */
    override suspend fun add(amount: Double, category: String, note: String?, tag: String?) {
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
                tag = tag,
                synced = false,
            ),
        )
    }

    /**
     * Отредактировать трату немедленно в кэше. Если сервер её ещё не видел (synced = false) —
     * правка просто меняет то, что уйдёт вместе с ещё не отправленным POST, без отдельного PUT.
     * Если уже видел — помечаем dirty, чтобы [pushDirty] отправил PUT при следующей sync().
     */
    override suspend fun edit(
        id: String,
        amount: Double,
        category: String,
        note: String?,
        categoryGroup: String?,
        tag: String?,
    ) {
        val existing = dao.getById(id) ?: return
        dao.upsert(
            existing.copy(
                amount = amount,
                category = category,
                note = note,
                categoryGroup = categoryGroup,
                tag = tag,
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
    override suspend fun delete(id: String) {
        val existing = dao.getById(id) ?: return
        if (!existing.synced) {
            dao.deleteById(id)
        } else {
            dao.markPendingDelete(id)
        }
    }

    override suspend fun setBudget(value: Double?) {
        val saved = api.setBudget(BudgetDto(value))
        settings.saveBudget(saved.monthlyBudget)
    }

    override suspend fun exportCsv(): ByteArray = api.exportCsv().bytes()

    override suspend fun clearLocalData() {
        dao.deleteAll()
        settings.clear()
    }

    private suspend fun pushPendingDeletes() {
        for (row in dao.pendingDeletes()) {
            try {
                val response = api.deleteExpense(row.id)
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
                val saved = api.editExpense(
                    row.id,
                    UpdateExpense(
                        amount = row.amount,
                        category = row.category,
                        note = row.note,
                        categoryGroup = row.categoryGroup,
                        tag = row.tag,
                    ),
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
                val saved = api.addExpense(
                    NewExpense(
                        amount = pending.amount,
                        category = pending.category,
                        note = pending.note,
                        id = pending.id, // тот же id — сервер узнает повтор и не создаст дубль
                        tag = pending.tag,
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
        var since = settings.syncCursor()
        var lastServerTime: String? = since
        var hasMore = true
        while (hasMore) {
            val page = api.getExpensesChanges(since = since)
            for (item in page.expenses) {
                applyServerChange(item)
            }
            lastServerTime = page.serverTime
            since = page.expenses.lastOrNull()?.updatedAt ?: page.serverTime
            hasMore = page.hasMore
        }
        lastServerTime?.let { settings.saveSyncCursor(it) }
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
