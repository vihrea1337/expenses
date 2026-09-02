package io.github.vihrea1337.expenses.android.data

import android.content.Context

/**
 * Курсор синхронизации — момент времени (serverTime из последнего ответа
 * `GET /api/expenses/changes`), с которого нужно запросить изменения в следующий раз.
 * Хранится так же, как токен в [io.github.vihrea1337.expenses.android.TokenStore]:
 * в SharedPreferences (переживает перезапуск) и в памяти (быстрый доступ).
 */
object SyncCursorStore {
    private const val PREFS = "expenses_sync_prefs"
    private const val KEY_SINCE = "last_synced_at"

    private var prefs: android.content.SharedPreferences? = null

    var lastSyncedAt: String? = null
        private set

    fun init(context: Context) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        lastSyncedAt = p.getString(KEY_SINCE, null)
    }

    fun save(value: String) {
        lastSyncedAt = value
        prefs?.edit()?.putString(KEY_SINCE, value)?.apply()
    }

    /** Сбросить курсор — вызывается вместе с очисткой кэша при выходе из аккаунта. */
    fun clear() {
        lastSyncedAt = null
        prefs?.edit()?.remove(KEY_SINCE)?.apply()
    }
}
