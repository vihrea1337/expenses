package io.github.vihrea1337.expenses.android.sync

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.vihrea1337.expenses.android.data.ExpensesRepository
import io.github.vihrea1337.expenses.android.data.SessionStore
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Фоновая синхронизация трат: отправить офлайн-очередь и забрать изменения с сервера.
 *
 * В версии 1.x синхронизация запускалась только из экрана (при открытии, по «потяни вниз»
 * и после добавления траты). Трата, записанная в метро без сети, уезжала на сервер, лишь
 * когда пользователь снова открывал приложение, — а бот и сайт её до тех пор не видели.
 * WorkManager — системный планировщик Android: задача с условием «есть сеть» выполнится
 * сама, как только сеть появится, даже если приложение закрыто или телефон перезагрузили.
 *
 * @HiltWorker + @AssistedInject: Context и WorkerParameters даёт WorkManager, а остальное
 * (репозиторий) — Hilt. Фабрику подключает ExpensesApp (Configuration.Provider).
 */
@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val expenses: ExpensesRepository,
    private val session: SessionStore,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (session.token == null) return Result.success() // вышли из аккаунта — синхронизировать нечего
        return try {
            expenses.sync()
            Result.success()
        } catch (e: Exception) {
            Log.w("ExpensesSync", "Синхронизация не удалась (попытка $runAttemptCount)", e)
            // Сеть «есть», но сервер недоступен — WorkManager повторит позже сам,
            // с растущей паузой (backoff). После 5 попыток сдаёмся до следующего запуска.
            if (runAttemptCount < 5) Result.retry() else Result.failure()
        }
    }
}

/**
 * Постановка задач синхронизации в WorkManager — в одном месте, чтобы не дублировать условия.
 * Интерфейс — чтобы в юнит-тестах ViewModel проверить «синхронизация запрошена» без WorkManager.
 */
interface SyncScheduler {
    /** Синхронизировать «как только будет сеть» (сразу, если сеть есть). */
    fun syncSoon()

    /** Периодическая синхронизация — подтянуть траты, добавленные в боте или на сайте. */
    fun schedulePeriodic()

    /** При выходе из аккаунта — ничего не синхронизируем от чужого имени. */
    fun cancelAll()
}

@Singleton
class WorkManagerSyncScheduler @Inject constructor(@ApplicationContext private val context: Context) : SyncScheduler {

    private val workManager get() = WorkManager.getInstance(context)

    private val needsNetwork = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    /** REPLACE с уникальным именем: десять трат подряд — одна задача, а не десять. */
    override fun syncSoon() {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(needsNetwork)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .build()
        workManager.enqueueUniqueWork(ONE_TIME, ExistingWorkPolicy.REPLACE, request)
    }

    /** Раз в 3 часа. KEEP — не перезапускать отсчёт при каждом старте приложения. */
    override fun schedulePeriodic() {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(3, TimeUnit.HOURS)
            .setConstraints(needsNetwork)
            .build()
        workManager.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    override fun cancelAll() {
        workManager.cancelUniqueWork(ONE_TIME)
        workManager.cancelUniqueWork(PERIODIC)
    }

    private companion object {
        const val ONE_TIME = "expenses-sync-now"
        const val PERIODIC = "expenses-sync-periodic"
    }
}
