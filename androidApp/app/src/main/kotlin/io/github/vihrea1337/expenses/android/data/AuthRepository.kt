package io.github.vihrea1337.expenses.android.data

import io.github.vihrea1337.expenses.RegisterRequest
import io.github.vihrea1337.expenses.android.sync.SyncScheduler
import javax.inject.Inject
import javax.inject.Singleton

/** Вход по токену, регистрация, выход. */
interface AuthRepository {
    /** Войти по уже существующему токену (его выдаёт бот командой /token или другой телефон). */
    suspend fun loginWithToken(token: String)

    /** Создать новый аккаунт — сервер выдаст токен. */
    suspend fun register(name: String)

    suspend fun logout()
}

/**
 * В Expenses нет паролей: токен = аккаунт (так устроено с v1.0 — см. docs/architecture.md).
 * Один и тот же токен можно ввести в приложении на другом телефоне, на сайте, а бот
 * привязывается к нему командой /link — и все видят общие траты.
 */
@Singleton
class AuthRepositoryImpl @Inject constructor(
    private val api: ExpensesApi,
    private val session: SessionStore,
    private val expenses: ExpensesRepository,
    private val syncScheduler: SyncScheduler,
) : AuthRepository {

    override suspend fun loginWithToken(token: String) {
        // Токен передаём заголовком явно: в сессии его ещё нет (сохраняем только проверенный).
        // Перехватчик OkHttp видит, что Authorization уже есть, и второй не добавляет.
        val me = api.me(authorization = "Bearer $token")
        startSession(token, me.name)
    }

    override suspend fun register(name: String) {
        val user = api.register(RegisterRequest(name.ifBlank { "Пользователь" }))
        startSession(user.token, user.name)
    }

    /**
     * Перед новой сессией стираем локальный кэш: если до этого на телефоне был другой
     * аккаунт (или прошлая сессия кончилась по 401), его траты не должны мелькнуть в списке.
     */
    private suspend fun startSession(token: String, name: String) {
        expenses.clearLocalData()
        session.save(token, name)
        syncScheduler.schedulePeriodic()
        syncScheduler.syncSoon()
    }

    /**
     * Выход. Неотправленные офлайн-траты при этом теряются — сознательный компромисс
     * безопасности: не показать следующему аккаунту чужие данные важнее (экран выхода
     * предупреждает об этом).
     */
    override suspend fun logout() {
        syncScheduler.cancelAll()
        expenses.clearLocalData()
        session.clear()
    }
}
