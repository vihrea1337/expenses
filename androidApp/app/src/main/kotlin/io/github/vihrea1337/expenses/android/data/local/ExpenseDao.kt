package io.github.vihrea1337.expenses.android.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ExpenseDao {
    /**
     * Flow — экран подписывается один раз и дальше сам перерисовывается при любом изменении
     * таблицы (добавили трату, синхронизация подтянула свежие данные и т.д.), без ручных
     * перезапросов.
     */
    @Query("SELECT * FROM expenses ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<ExpenseEntity>>

    /** Траты, ещё не подтверждённые сервером — их нужно (пере)отправить при синхронизации. */
    @Query("SELECT * FROM expenses WHERE synced = 0")
    suspend fun unsynced(): List<ExpenseEntity>

    /** REPLACE по id: и добавление новой траты, и обновление уже существующей (одна и та же операция). */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(expense: ExpenseEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(expenses: List<ExpenseEntity>)

    /**
     * Убрать из кэша траты, которых больше нет на сервере (удалены с другого клиента),
     * НЕ трогая ещё не отправленные (synced = 0) — иначе офлайн-трата исчезла бы, толком
     * не долетев до сервера.
     */
    @Query("DELETE FROM expenses WHERE synced = 1 AND id NOT IN (:keepIds)")
    suspend fun pruneMissing(keepIds: List<String>)

    @Query("DELETE FROM expenses WHERE id = :id")
    suspend fun deleteById(id: String)
}
