package io.github.vihrea1337.expenses.android.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * База Room — локальный кэш трат на телефоне. Room по этой аннотации сам генерирует код
 * (через KSP). Создаёт базу Hilt (см. di/AppModule.provideDatabase) — один экземпляр на
 * приложение; раньше рядом был `object ExpensesDatabase` с ручным `init(context)`.
 *
 * Версии схемы: 2 добавила dirty/pendingDelete/updatedAt, 3 — tag (обе 2026-09-02).
 * Миграции — разрушающие (fallbackToDestructiveMigration в AppModule): это кэш сервера,
 * после пересоздания всё заново придёт через /api/expenses/changes. Компромисс назван
 * честно: если в момент обновления схемы на телефоне лежали НЕОТПРАВЛЕННЫЕ офлайн-траты,
 * они потеряются. При следующем изменении схемы стоит написать настоящую Migration(3, 4).
 */
@Database(entities = [ExpenseEntity::class], version = 3, exportSchema = false)
abstract class ExpensesDatabase : RoomDatabase() {
    abstract fun expenseDao(): ExpenseDao
}
