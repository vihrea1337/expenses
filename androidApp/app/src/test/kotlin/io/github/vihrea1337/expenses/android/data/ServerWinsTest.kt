package io.github.vihrea1337.expenses.android.data

import io.github.vihrea1337.expenses.android.data.local.ExpenseEntity
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Чистая логика разрешения конфликтов синхронизации ([serverWins]) — без Room и без сети,
 * обычный JVM-тест.
 */
class ServerWinsTest {

    private fun entity(
        updatedAt: String,
        synced: Boolean = true,
        dirty: Boolean = false,
        pendingDelete: Boolean = false,
    ) = ExpenseEntity(
        id = "e1",
        amount = 100.0,
        category = "кофе",
        note = null,
        createdAt = "2026-09-02T10:00:00",
        updatedAt = updatedAt,
        categoryGroup = null,
        synced = synced,
        dirty = dirty,
        pendingDelete = pendingDelete,
    )

    @Test
    fun `новой локальной строки нет — сервер побеждает тривиально`() {
        assertTrue(serverWins(serverUpdatedAt = "2026-09-02T10:00:00", existing = null))
    }

    @Test
    fun `локальная строка полностью синхронизирована — сервер побеждает`() {
        val existing = entity(updatedAt = "2026-09-02T09:00:00", synced = true, dirty = false, pendingDelete = false)
        assertTrue(serverWins(serverUpdatedAt = "2026-09-02T08:00:00", existing = existing), "нет неотправленной работы — сервер как минимум не хуже")
    }

    @Test
    fun `неотправленная правка новее сервера — локальная версия остаётся`() {
        val existing = entity(updatedAt = "2026-09-02T10:00:00", synced = true, dirty = true)
        assertFalse(serverWins(serverUpdatedAt = "2026-09-02T09:00:00", existing = existing))
    }

    @Test
    fun `сервер новее неотправленной правки — сервер побеждает`() {
        val existing = entity(updatedAt = "2026-09-02T09:00:00", synced = true, dirty = true)
        assertTrue(serverWins(serverUpdatedAt = "2026-09-02T10:00:00", existing = existing))
    }

    @Test
    fun `неотправленное удаление новее сервера — локальное удаление остаётся в силе`() {
        val existing = entity(updatedAt = "2026-09-02T10:00:00", synced = true, pendingDelete = true)
        assertFalse(serverWins(serverUpdatedAt = "2026-09-02T09:00:00", existing = existing))
    }

    @Test
    fun `сервер новее неотправленного удаления — отменяем локальное удаление`() {
        val existing = entity(updatedAt = "2026-09-02T09:00:00", synced = true, pendingDelete = true)
        assertTrue(serverWins(serverUpdatedAt = "2026-09-02T10:00:00", existing = existing))
    }

    @Test
    fun `ещё не отправленное добавление новее сервера — остаётся локальным`() {
        // synced = false в принципе не должно встретиться в ответе changes для этого же id
        // (сервер физически не может знать о трате, которую мы ему ещё не отправили), но
        // функция всё равно должна вести себя безопасно, если это вдруг произойдёт.
        val existing = entity(updatedAt = "2026-09-02T10:00:00", synced = false)
        assertFalse(serverWins(serverUpdatedAt = "2026-09-02T09:00:00", existing = existing))
    }

    @Test
    fun `сравнение учитывает разную длину дробной части секунд, а не только текст`() {
        // "10:00:00.5" короче "10:00:00.450" как строка, но как момент времени — позже.
        // Наивное строковое сравнение перепутало бы победителя.
        val existing = entity(updatedAt = "2026-09-02T10:00:00.450", synced = true, dirty = true)
        assertTrue(
            serverWins(serverUpdatedAt = "2026-09-02T10:00:00.5", existing = existing),
            "10:00:00.5 (пол секунды) должно считаться позже, чем 10:00:00.450",
        )
    }
}
