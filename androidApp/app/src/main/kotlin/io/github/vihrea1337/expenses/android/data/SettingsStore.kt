package io.github.vihrea1337.expenses.android.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Делегат создаёт ОДИН DataStore на файл на весь процесс. Два DataStore на один файл
 * DataStore запрещает (падает с ошибкой), поэтому он объявлен на верхнем уровне файла,
 * а не внутри класса.
 */
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "expenses_settings")

/**
 * Небольшие настройки, которые нужны в фоне и не нужны синхронно:
 *  - курсор синхронизации — с какого момента просить изменения у сервера;
 *  - месячный бюджет — копия с сервера, чтобы аналитика и виджет показывали его без сети.
 *
 * DataStore вместо SharedPreferences: чтение и запись идут в фоне (не блокируют главный
 * поток), а значения — Flow: виджет и экран аналитики подписаны на бюджет и обновляются
 * сами, когда синхронизация его поменяла.
 *
 * Интерфейс — чтобы в тестах синхронизации подставить простую реализацию в памяти.
 */
interface SettingsStore {
    suspend fun syncCursor(): String?
    suspend fun saveSyncCursor(value: String)
    val budget: Flow<Double?>
    suspend fun saveBudget(value: Double?)
    suspend fun clear()
}

@Singleton
class DataStoreSettings @Inject constructor(@ApplicationContext private val context: Context) : SettingsStore {

    override suspend fun syncCursor(): String? = context.dataStore.data.first()[KEY_CURSOR]

    override suspend fun saveSyncCursor(value: String) {
        context.dataStore.edit { it[KEY_CURSOR] = value }
    }

    override val budget: Flow<Double?> = context.dataStore.data.map { it[KEY_BUDGET] }

    override suspend fun saveBudget(value: Double?) {
        context.dataStore.edit { if (value == null) it.remove(KEY_BUDGET) else it[KEY_BUDGET] = value }
    }

    /** При выходе из аккаунта: курсор и бюджет относятся к прежнему пользователю. */
    override suspend fun clear() {
        context.dataStore.edit { it.clear() }
    }

    private companion object {
        val KEY_CURSOR = stringPreferencesKey("last_synced_at")
        val KEY_BUDGET = doublePreferencesKey("monthly_budget")
    }
}
