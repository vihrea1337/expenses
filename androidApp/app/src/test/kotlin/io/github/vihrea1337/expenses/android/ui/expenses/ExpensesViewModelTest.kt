package io.github.vihrea1337.expenses.android.ui.expenses

import app.cash.turbine.test
import io.github.vihrea1337.expenses.Expense
import io.github.vihrea1337.expenses.android.data.ExpensesRepository
import io.github.vihrea1337.expenses.android.sync.SyncScheduler
import io.github.vihrea1337.expenses.android.ui.common.Period
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Юнит-тесты ViewModel экрана трат — без Android, сети и базы.
 *
 * Именно ради таких тестов в 2.0 зависимости ViewModel стали ИНТЕРФЕЙСАМИ, которые
 * приходят в конструктор (Hilt): здесь вместо настоящего репозитория — [FakeRepository]
 * (траты в обычном списке в памяти), вместо WorkManager — [FakeScheduler] (просто считает
 * вызовы), вместо системных часов — фиксированные (Clock.fixed): «сегодня» всегда 11.10.2026.
 *
 * viewModelScope работает на Dispatchers.Main, которого на JVM нет, — подменяем его
 * тестовым диспетчером (setMain). Тестовый диспетчер ещё и управляет «виртуальным
 * временем»: delay(3000) внутри ViewModel не ждёт 3 настоящие секунды.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ExpensesViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val clock = Clock.fixed(Instant.parse("2026-10-11T12:00:00Z"), ZoneOffset.UTC)

    private lateinit var repo: FakeRepository
    private lateinit var scheduler: FakeScheduler

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        repo = FakeRepository()
        scheduler = FakeScheduler()
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun e(id: String, date: String, amount: Double, category: String = "кофе", note: String? = null) =
        Expense(id = id, amount = amount, category = category, note = note, createdAt = "${date}T10:00:00")

    /** Создать ViewModel и подписаться на state — stateIn(WhileSubscribed) без подписчика не считает. */
    private fun TestScope.createVm(): ExpensesViewModel {
        val vm = ExpensesViewModel(repo, scheduler, clock, backgroundScope)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        return vm
    }

    @Test
    fun `состояние — траты за период по дням, сумма месяца и бюджет`() = runTest(dispatcher) {
        repo.items.value = listOf(
            e("1", "2026-10-11", 200.0),
            e("2", "2026-10-10", 300.0, category = "такси"),
            e("3", "2026-08-01", 1000.0), // старше 30 дней и не в этом месяце
        )
        repo.budgetFlow.value = 1000.0
        val vm = createVm()
        advanceUntilIdle()

        val s = vm.state.value
        assertFalse(s.loading)
        assertEquals(2, s.periodCount)
        assertEquals(500.0, s.periodTotal)
        assertEquals(2, s.sections.size, "два дня — две группы")
        assertEquals(500.0, s.monthSpent)
        assertEquals(500.0, s.budget?.left)

        vm.setPeriod(Period.ALL)
        advanceUntilIdle()
        assertEquals(3, vm.state.value.periodCount)
    }

    @Test
    fun `поиск — по категории и заметке, без учёта регистра`() = runTest(dispatcher) {
        repo.items.value = listOf(
            e("1", "2026-10-11", 200.0, category = "кофе"),
            e("2", "2026-10-11", 300.0, category = "такси", note = "до Дома"),
        )
        val vm = createVm()
        vm.setQuery("дом")
        advanceUntilIdle()
        assertEquals(listOf("2"), vm.state.value.sections.flatMap { it.items }.map { it.id })
    }

    @Test
    fun `быстрый ввод — строка разбирается как в боте, трата уходит в репозиторий и на сервер`() = runTest(dispatcher) {
        val vm = createVm()
        advanceUntilIdle()
        val syncsBefore = repo.syncCalls

        assertTrue(vm.addFromText("Такси до дома 350", note = " ", tag = "#поездка"))
        advanceUntilIdle()

        val added = repo.items.value.single()
        assertEquals(350.0, added.amount)
        assertEquals("Такси до дома", added.category)
        assertEquals(null, added.note, "пустая заметка не сохраняется пробелом")
        assertEquals("поездка", added.tag, "решётку в теге убираем")
        // Синхронизация сразу + повтор через 3 с (ждём категорию от ИИ).
        assertEquals(2, repo.syncCalls - syncsBefore)
        assertEquals(0, scheduler.syncSoonCalls, "сеть есть — WorkManager не нужен")
    }

    @Test
    fun `быстрый ввод — без суммы не добавляет`() = runTest(dispatcher) {
        val vm = createVm()
        assertFalse(vm.addFromText("просто кофе", "", ""))
        advanceUntilIdle()
        assertTrue(repo.items.value.isEmpty())
    }

    @Test
    fun `нет сети — трата сохраняется, отправку поручаем WorkManager`() = runTest(dispatcher) {
        repo.syncFails = true
        val vm = createVm()
        vm.events.test {
            advanceUntilIdle()
            // Первая синхронизация при открытии экрана не удалась — экран предупреждает.
            assertTrue((awaitItem() as ExpensesEvent.Message).text.startsWith("Нет связи с сервером"))

            vm.addFromText("кофе 200", "", "")
            advanceUntilIdle()
            assertEquals(1, repo.items.value.size, "трата не потерялась — лежит в локальной базе")
            assertEquals(1, scheduler.syncSoonCalls)
        }
    }

    @Test
    fun `удаление с отменой — трата прячется и возвращается, в базе не трогается`() = runTest(dispatcher) {
        val coffee = e("1", "2026-10-11", 200.0)
        repo.items.value = listOf(coffee)
        val vm = createVm()
        advanceUntilIdle()

        vm.events.test {
            vm.delete(coffee)
            advanceUntilIdle()
            assertEquals(ExpensesEvent.Deleted("1", "кофе"), awaitItem())
            assertEquals(0, vm.state.value.periodCount, "сразу пропала со списка")

            vm.undoDelete("1")
            advanceUntilIdle()
            assertEquals(1, vm.state.value.periodCount, "после «Отменить» вернулась")
            vm.commitDelete("1") // снекбар закрылся уже после отмены — удалять нечего
            advanceUntilIdle()
            assertTrue(repo.deleted.isEmpty())
        }
    }

    @Test
    fun `удаление без отмены — после закрытия снекбара удаляется по-настоящему`() = runTest(dispatcher) {
        val coffee = e("1", "2026-10-11", 200.0)
        repo.items.value = listOf(coffee)
        val vm = createVm()
        vm.delete(coffee)
        vm.commitDelete("1")
        advanceUntilIdle()
        assertEquals(listOf("1"), repo.deleted)
        assertEquals(0, vm.state.value.periodCount)
    }

    @Test
    fun `правка — неверная сумма отклоняется, запятая в сумме понимается`() = runTest(dispatcher) {
        repo.items.value = listOf(e("1", "2026-10-11", 200.0))
        val vm = createVm()
        assertFalse(vm.edit("1", "кофе", "abc", "", null, ""))
        assertFalse(vm.edit("1", " ", "100", "", null, ""))
        assertFalse(vm.edit("1", "кофе", "0", "", null, ""))

        assertTrue(vm.edit("1", "латте", "249,5", "", "еда", ""))
        advanceUntilIdle()
        val edited = repo.items.value.single()
        assertEquals(249.5, edited.amount)
        assertEquals("латте", edited.category)
        assertEquals("еда", edited.categoryGroup)
    }

    // --- Фейки ---------------------------------------------------------------------------

    /** Репозиторий в памяти: список трат в StateFlow, считает вызовы sync(). */
    private class FakeRepository : ExpensesRepository {
        val items = MutableStateFlow(emptyList<Expense>())
        val budgetFlow = MutableStateFlow<Double?>(null)
        val deleted = mutableListOf<String>()
        var syncCalls = 0
        var syncFails = false
        private var nextId = 100

        override fun observeExpenses(): Flow<List<Expense>> = items
        override fun observeUnsentCount(): Flow<Int> = items.map { 0 }
        override val budget: Flow<Double?> = budgetFlow

        override suspend fun sync() {
            syncCalls++
            if (syncFails) throw IOException("нет сети")
        }

        override suspend fun add(amount: Double, category: String, note: String?, tag: String?) {
            items.value += Expense(
                id = (nextId++).toString(), amount = amount, category = category, note = note,
                createdAt = "2026-10-11T12:00:00", tag = tag,
            )
        }

        override suspend fun edit(id: String, amount: Double, category: String, note: String?, categoryGroup: String?, tag: String?) {
            items.value = items.value.map {
                if (it.id == id) it.copy(amount = amount, category = category, note = note, categoryGroup = categoryGroup, tag = tag) else it
            }
        }

        override suspend fun delete(id: String) {
            deleted += id
            items.value = items.value.filterNot { it.id == id }
        }

        override suspend fun setBudget(value: Double?) {
            budgetFlow.value = value
        }

        override suspend fun exportCsv(): ByteArray = ByteArray(0)
        override suspend fun clearLocalData() {
            items.value = emptyList()
        }
    }

    private class FakeScheduler : SyncScheduler {
        var syncSoonCalls = 0
        override fun syncSoon() {
            syncSoonCalls++
        }
        override fun schedulePeriodic() = Unit
        override fun cancelAll() = Unit
    }
}
