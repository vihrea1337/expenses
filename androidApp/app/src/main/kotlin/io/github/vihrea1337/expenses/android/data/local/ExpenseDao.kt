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
     * таблицы, без ручных перезапросов. `pendingDelete = 0` — трата, помеченная на удаление,
     * пропадает из списка сразу (оптимистично), хотя физически строка ещё в базе — ждёт,
     * пока синхронизация подтвердит удаление на сервере.
     */
    @Query("SELECT * FROM expenses WHERE pendingDelete = 0 ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<ExpenseEntity>>

    @Query("SELECT * FROM expenses WHERE id = :id")
    suspend fun getById(id: String): ExpenseEntity?

    /** Траты, ещё не подтверждённые сервером — их нужно ОТПРАВИТЬ (POST) при синхронизации. */
    @Query("SELECT * FROM expenses WHERE synced = 0 AND pendingDelete = 0")
    suspend fun unsynced(): List<ExpenseEntity>

    /** Траты, отредактированные офлайн после того, как сервер их уже знал — ждут PUT. */
    @Query("SELECT * FROM expenses WHERE synced = 1 AND dirty = 1 AND pendingDelete = 0")
    suspend fun dirtyRows(): List<ExpenseEntity>

    /** Траты, помеченные на удаление — ждут DELETE. */
    @Query("SELECT * FROM expenses WHERE pendingDelete = 1")
    suspend fun pendingDeletes(): List<ExpenseEntity>

    /** REPLACE по id: и добавление новой траты, и обновление уже существующей (одна и та же операция). */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(expense: ExpenseEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(expenses: List<ExpenseEntity>)

    /** Пометить трату на удаление (не стирает строку — см. ExpenseEntity.pendingDelete). */
    @Query("UPDATE expenses SET pendingDelete = 1 WHERE id = :id")
    suspend fun markPendingDelete(id: String)

    @Query("DELETE FROM expenses WHERE id = :id")
    suspend fun deleteById(id: String)
}
