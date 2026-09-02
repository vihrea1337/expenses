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
import kotlin.test.assertTrue

/**
 * Тесты локального кэша трат (Room) на JVM через Robolectric — без эмулятора и телефона.
 * Проверяем именно то, от чего зависит офлайн-режим: несинхронизированные траты не теряются
 * при синхронизации, а подтверждённые сервером — обновляются и вычищаются, если пропали.
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

    private fun entity(id: String, synced: Boolean, amount: Double = 100.0) = ExpenseEntity(
        id = id,
        amount = amount,
        category = "кофе",
        note = null,
        createdAt = "2026-09-02T10:00:00",
        categoryGroup = null,
        synced = synced,
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
    fun `unsynced возвращает только неотправленные траты`() = runTest {
        dao.upsert(entity("e1", synced = true))
        dao.upsert(entity("e2", synced = false))
        dao.upsert(entity("e3", synced = false))

        val pending = dao.unsynced()

        assertEquals(setOf("e2", "e3"), pending.map { it.id }.toSet())
    }

    @Test
    fun `pruneMissing удаляет пропавшие на сервере синхронизированные траты`() = runTest {
        dao.upsert(entity("e1", synced = true))
        dao.upsert(entity("e2", synced = true))

        dao.pruneMissing(keepIds = listOf("e1")) // сервер знает только про e1 — e2 удалили с другого клиента

        val list = dao.observeAll().first()
        assertEquals(listOf("e1"), list.map { it.id })
    }

    @Test
    fun `pruneMissing НЕ трогает несинхронизированные траты, даже если их нет в keepIds`() = runTest {
        // Это ключевая гарантия офлайн-режима: трата, добавленная без сети и ещё не
        // отправленная, не должна исчезать при синхронизации только из-за того, что сервер
        // о ней пока не знает.
        dao.upsert(entity("offline-1", synced = false))

        dao.pruneMissing(keepIds = emptyList()) // сервер вообще ничего не вернул (например, список пуст)

        val list = dao.observeAll().first()
        assertEquals(listOf("offline-1"), list.map { it.id }, "неотправленная трата не должна пропасть")
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
        )
        val roundTripped = original.toEntity(synced = true).toExpense()
        assertEquals(original, roundTripped)
    }
}
