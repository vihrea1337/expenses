package io.github.vihrea1337.expenses

import java.time.Duration
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Чистая логика "что подлежит окончательному стиранию" ([TombstoneCleaner.isDueForPurge]) —
 * без базы, обычный JVM-тест. Реальный SQL-запрос (ExpenseRepository.deleteTombstonesOlderThan)
 * проверен отдельно интеграционным тестом в DbRepositoryTest.
 */
class TombstoneCleanerTest {
    private val now = LocalDateTime.parse("2026-09-02T12:00:00")
    private val retention = Duration.ofDays(90)

    @Test
    fun `не удалённая запись никогда не подлежит удалению`() {
        assertFalse(TombstoneCleaner.isDueForPurge(deletedAt = null, now = now, retention = retention))
    }

    @Test
    fun `надгробие старше срока хранения подлежит удалению`() {
        val deletedAt = now.minus(retention).minusDays(1)
        assertTrue(TombstoneCleaner.isDueForPurge(deletedAt, now, retention))
    }

    @Test
    fun `надгробие младше срока хранения не трогаем`() {
        val deletedAt = now.minus(retention).plusDays(1)
        assertFalse(TombstoneCleaner.isDueForPurge(deletedAt, now, retention))
    }

    @Test
    fun `ровно на границе срока хранения — ещё не трогаем (строгое сравнение)`() {
        // deletedAt == now - retention: isBefore(now - retention) для самого себя — false.
        val deletedAt = now.minus(retention)
        assertFalse(TombstoneCleaner.isDueForPurge(deletedAt, now, retention))
    }

    @Test
    fun `RETENTION по умолчанию — 90 дней`() {
        assertTrue(TombstoneCleaner.RETENTION == Duration.ofDays(90))
    }
}
