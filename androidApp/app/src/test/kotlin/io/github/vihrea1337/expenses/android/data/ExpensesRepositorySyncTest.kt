package io.github.vihrea1337.expenses.android.data

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.vihrea1337.expenses.BudgetDto
import io.github.vihrea1337.expenses.Expense
import io.github.vihrea1337.expenses.ExpensesChangesDto
import io.github.vihrea1337.expenses.MeResponse
import io.github.vihrea1337.expenses.NewExpense
import io.github.vihrea1337.expenses.RegisterRequest
import io.github.vihrea1337.expenses.UpdateExpense
import io.github.vihrea1337.expenses.UserResponse
import io.github.vihrea1337.expenses.android.data.local.ExpensesDatabase
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.Response
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Синхронизация целиком: настоящая база Room (в памяти, через Robolectric) + настоящий
 * ExpensesRepositoryImpl + ПОДДЕЛЬНЫЙ сервер [FakeServer] — он хранит траты в обычной
 * Map и отвечает так же, как наш бэкенд (POST идемпотентен по id, changes отдаёт дельту).
 *
 * В версии 1.x так проверить было нельзя: репозиторий был `object` и сам ходил в
 * глобальные ApiClient/ExpensesDatabase. В 2.0 всё приходит в конструктор — подменяй.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ExpensesRepositorySyncTest {
    private lateinit var db: ExpensesDatabase
    private lateinit var server: FakeServer
    private lateinit var settings: MemorySettings
    private lateinit var repo: ExpensesRepositoryImpl

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), ExpensesDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        server = FakeServer()
        settings = MemorySettings()
        repo = ExpensesRepositoryImpl(server, db.expenseDao(), settings)
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `офлайн-трата видна сразу, а при sync уходит на сервер с тем же id`() = runTest {
        server.online = false
        repo.add(200.0, "кофе", null, "утро")
        val local = repo.observeExpenses().first().single()
        assertEquals(1, repo.observeUnsentCount().first())
        assertFailsWith<IOException> { repo.sync() } // сети нет — sync честно падает

        server.online = true
        repo.sync()
        assertEquals(local.id, server.expenses.values.single().id, "тот же id — сервер не создаст дубль при повторе")
        assertEquals("утро", server.expenses.values.single().tag)
        assertEquals(0, repo.observeUnsentCount().first())
    }

    @Test
    fun `трата, добавленная в боте, приходит при sync, курсор сохраняется`() = runTest {
        server.put(Expense(id = "bot-1", amount = 350.0, category = "такси", createdAt = "2026-10-11T09:00:00", updatedAt = "2026-10-11T09:00:00"))
        repo.sync()
        assertEquals(listOf("bot-1"), repo.observeExpenses().first().map { it.id })
        assertEquals(server.now, settings.cursor)

        // Второй sync просит только изменения ПОСЛЕ курсора — дельту, а не всю историю.
        repo.sync()
        assertEquals(server.now, server.lastSince)
    }

    @Test
    fun `удаление на сервере (надгробие) убирает трату локально`() = runTest {
        server.put(Expense(id = "e1", amount = 100.0, category = "кофе", createdAt = "2026-10-11T09:00:00", updatedAt = "2026-10-11T09:00:00"))
        repo.sync()
        server.put(server.expenses.getValue("e1").copy(deleted = true, updatedAt = "2026-10-11T23:00:00"))
        repo.sync()
        assertTrue(repo.observeExpenses().first().isEmpty())
    }

    @Test
    fun `локальная правка и удаление уходят на сервер PUT и DELETE`() = runTest {
        server.put(Expense(id = "a", amount = 100.0, category = "кофе", createdAt = "2026-10-11T09:00:00", updatedAt = "2026-10-11T09:00:00"))
        server.put(Expense(id = "b", amount = 50.0, category = "чай", createdAt = "2026-10-11T09:00:00", updatedAt = "2026-10-11T09:00:00"))
        repo.sync()

        repo.edit("a", 120.0, "латте", null, "еда", null)
        repo.delete("b")
        assertEquals(listOf("a"), repo.observeExpenses().first().map { it.id }, "удалённая сразу пропала с экрана")
        repo.sync()

        assertEquals(120.0, server.expenses.getValue("a").amount)
        assertEquals("латте", server.expenses.getValue("a").category)
        assertTrue(server.expenses.getValue("b").deleted)
        assertNull(db.expenseDao().getById("b"), "после подтверждения сервером строка удалена физически")
    }

    @Test
    fun `трату удалили до отправки — на сервер вообще ничего не уходит`() = runTest {
        server.online = false
        repo.add(10.0, "ошибка", null, null)
        repo.delete(repo.observeExpenses().first().single().id)
        server.online = true
        repo.sync()
        assertTrue(server.expenses.isEmpty())
        assertEquals(0, server.writeRequests)
    }

    @Test
    fun `конфликт — свежая правка с другого устройства побеждает старую локальную`() = runTest {
        server.put(Expense(id = "a", amount = 100.0, category = "кофе", createdAt = "2026-10-11T09:00:00", updatedAt = "2026-10-11T09:00:00"))
        repo.sync()
        server.online = false
        repo.edit("a", 111.0, "кофе", null, null, null) // наша правка — «сейчас» по часам телефона...
        // ...но на сервере правка ещё новее (условно из будущего относительно нашей).
        server.put(server.expenses.getValue("a").copy(amount = 999.0, updatedAt = "2999-01-01T00:00:00"))
        server.online = true
        server.rejectWrites = true // PUT не проходит — проверяем именно правило pull'а
        repo.sync()
        assertEquals(999.0, repo.observeExpenses().first().single().amount)
    }

    @Test
    fun `бюджет приходит с sync и задаётся сразу на сервере`() = runTest {
        server.budget = 30_000.0
        repo.sync()
        assertEquals(30_000.0, repo.budget.first())
        repo.setBudget(25_000.0)
        assertEquals(25_000.0, server.budget)
        assertEquals(25_000.0, repo.budget.first())
    }

    @Test
    fun `clearLocalData стирает траты и настройки прежнего аккаунта`() = runTest {
        server.put(Expense(id = "a", amount = 1.0, category = "x", createdAt = "2026-10-11T09:00:00", updatedAt = "2026-10-11T09:00:00"))
        server.budget = 5.0
        repo.sync()
        repo.clearLocalData()
        assertTrue(repo.observeExpenses().first().isEmpty())
        assertNull(settings.cursor)
        assertNull(repo.budget.first())
    }

    // --- Фейки ---------------------------------------------------------------------------

    /** Сервер в памяти. Время — счётчик-строка, растущая с каждой записью. */
    private class FakeServer : ExpensesApi {
        val expenses = linkedMapOf<String, Expense>()
        var budget: Double? = null
        var online = true
        var rejectWrites = false
        var lastSince: String? = null
        var writeRequests = 0
        private var tick = 0
        val now: String get() = "2026-10-11T12:%02d:00".format(tick)

        fun put(e: Expense) {
            tick++
            expenses[e.id] = e
        }

        private fun check() {
            if (!online) throw IOException("нет сети")
        }

        private fun write() {
            check()
            writeRequests++
            if (rejectWrites) throw IOException("сервер отказал")
        }

        override suspend fun getExpensesChanges(since: String?, limit: Int): ExpensesChangesDto {
            check()
            lastSince = since
            val changed = expenses.values.filter { since == null || it.updatedAt > since }.sortedBy { it.updatedAt }
            return ExpensesChangesDto(changed, serverTime = now, hasMore = false)
        }

        override suspend fun addExpense(body: NewExpense): Expense {
            write()
            val id = body.id ?: "srv-${expenses.size}"
            expenses[id]?.let { return it } // повтор — тот же ответ, без дубля
            tick++
            val e = Expense(id = id, amount = body.amount, category = body.category, note = body.note, createdAt = now, updatedAt = now, tag = body.tag)
            expenses[id] = e
            return e
        }

        override suspend fun deleteExpense(id: String): Response<Unit> {
            write()
            val e = expenses[id] ?: return Response.error(404, "".toResponseBody(null))
            tick++
            expenses[id] = e.copy(deleted = true, updatedAt = now)
            return Response.success(204, Unit)
        }

        override suspend fun editExpense(id: String, body: UpdateExpense): Expense {
            write()
            tick++
            val e = expenses.getValue(id).copy(
                amount = body.amount,
                category = body.category,
                note = body.note,
                categoryGroup = body.categoryGroup,
                tag = body.tag,
                updatedAt = now,
            )
            expenses[id] = e
            return e
        }

        override suspend fun getBudget(): BudgetDto {
            check()
            return BudgetDto(budget)
        }

        override suspend fun setBudget(body: BudgetDto): BudgetDto {
            write()
            budget = body.monthlyBudget
            return BudgetDto(budget)
        }

        override suspend fun register(body: RegisterRequest): UserResponse = error("не нужен")
        override suspend fun me(authorization: String?): MeResponse = error("не нужен")
        override suspend fun exportCsv(): ResponseBody = error("не нужен")
    }

    private class MemorySettings : SettingsStore {
        var cursor: String? = null
        private val budgetFlow = MutableStateFlow<Double?>(null)
        override suspend fun syncCursor() = cursor
        override suspend fun saveSyncCursor(value: String) {
            cursor = value
        }
        override val budget: Flow<Double?> = budgetFlow
        override suspend fun saveBudget(value: Double?) {
            budgetFlow.value = value
        }
        override suspend fun clear() {
            cursor = null
            budgetFlow.value = null
        }
    }
}
