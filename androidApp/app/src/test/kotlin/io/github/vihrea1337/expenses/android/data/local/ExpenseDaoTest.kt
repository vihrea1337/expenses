package io.github.vihrea1337.expenses.android.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Тесты локального кэша трат (Room) на JVM через Robolectric — без эмулятора и телефона.
 * Проверяем именно то, от чего зависит офлайн-очередь: неотправленные добавления/правки/
 * удаления видны нужным выборкам и не теряются, а помеченные на удаление сразу пропадают
 * из списка на экране, хоть физически строка ещё в базе.
 */
@RunWith(RobolectricTestRunner::class)
class ExpenseDaoTest {
    private lateinit var db: RoomExpensesDatabase
    private lateinit var dao: ExpenseDao

    @Before
    fun setup() {
        // inMemoryDatabaseBuilder — база живёт только в памяти процесса теста, без файла на диске.
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), RoomExpensesDatabase::class.java)
            .allowMainThreadQueries() // тест синхронный — отдельный поток для БД тут не нужен
            .build()
        dao = db.expenseDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun entity(
        id: String,
        synced: Boolean,
        amount: Double = 100.0,
        dirty: Boolean = false,
        pendingDelete: Boolean = false,
        tag: String? = null,
    ) = ExpenseEntity(
        id = id,
        amount = amount,
        category = "кофе",
        note = null,
        createdAt = "2026-09-02T10:00:00",
        updatedAt = "2026-09-02T10:00:00",
        categoryGroup = null,
        tag = tag,
        synced = synced,
        dirty = dirty,
        pendingDelete = pendingDelete,
    )

    @Test
    fun `upsert и observeAll видят добавленную трату`() = runTest {
        dao.upsert(entity("e1", synced = true))
        val list = dao.observeAll().first()
        assertEquals(1, list.size)
        assertEquals("e1", list.first().id)
    }

    @Test
    fun `upsert с тем же id заменяет строку, а не дублирует`() = runTest {
        dao.upsert(entity("e1", synced = false, amount = 100.0))
        dao.upsert(entity("e1", synced = true, amount = 100.0)) // сервер подтвердил ту же трату
        val list = dao.observeAll().first()
        assertEquals(1, list.size, "должна остаться одна строка, а не две")
        assertTrue(list.first().synced, "после подтверждения сервером synced должен стать true")
    }

    @Test
    fun `getById находит строку по id, а для отсутствующей отдаёт null`() = runTest {
        dao.upsert(entity("e1", synced = true))
        assertEquals("e1", dao.getById("e1")?.id)
        assertNull(dao.getById("нет-такого"))
    }

    @Test
    fun `unsynced возвращает только неотправленные добавления`() = runTest {
        dao.upsert(entity("e1", synced = true))
        dao.upsert(entity("e2", synced = false))
        dao.upsert(entity("e3", synced = false))

        val pending = dao.unsynced()

        assertEquals(setOf("e2", "e3"), pending.map { it.id }.toSet())
    }

    @Test
    fun `unsynced не включает то, что уже помечено на удаление`() = runTest {
        // Трату добавили офлайн, а потом сразу же (тоже офлайн) передумали — не нужно
        // сначала слать POST, а следом DELETE: раз до сервера она ещё не долетела,
        // отправлять вообще нечего (см. ExpensesRepository.deleteExpenseOptimistic).
        dao.upsert(entity("e1", synced = false, pendingDelete = true))
        assertEquals(emptyList(), dao.unsynced())
    }

    @Test
    fun `dirtyRows возвращает только отредактированные после подтверждения сервером`() = runTest {
        dao.upsert(entity("e1", synced = true, dirty = false))
        dao.upsert(entity("e2", synced = true, dirty = true))
        dao.upsert(entity("e3", synced = false, dirty = false)) // ещё не отправленное добавление — не "dirty"

        assertEquals(listOf("e2"), dao.dirtyRows().map { it.id })
    }

    @Test
    fun `pendingDeletes возвращает помеченные на удаление`() = runTest {
        dao.upsert(entity("e1", synced = true))
        dao.upsert(entity("e2", synced = true))
        dao.markPendingDelete("e2")

        assertEquals(listOf("e2"), dao.pendingDeletes().map { it.id })
    }

    @Test
    fun `помеченная на удаление трата пропадает из observeAll сразу, хотя физически ещё в базе`() = runTest {
        dao.upsert(entity("e1", synced = true))
        dao.markPendingDelete("e1")

        assertEquals(emptyList(), dao.observeAll().first(), "оптимистичное удаление — сразу не видно на экране")
        assertTrue(dao.pendingDeletes().isNotEmpty(), "но строка ещё в базе — ждёт отправки DELETE")
    }

    @Test
    fun `deleteById физически убирает строку`() = runTest {
        dao.upsert(entity("e1", synced = true))
        dao.deleteById("e1")
        assertNull(dao.getById("e1"))
    }

    @Test
    fun `toEntity и toExpense переносят все поля без потерь`() {
        val original = io.github.vihrea1337.expenses.Expense(
            id = "e1",
            amount = 250.5,
            category = "такси",
            note = "до дома",
            createdAt = "2026-09-02T10:00:00",
            categoryGroup = "транспорт",
            updatedAt = "2026-09-02T10:05:00",
            tag = "поездка",
        )
        val roundTripped = original.toEntity(synced = true).toExpense()
        assertEquals(original, roundTripped)
    }
}
