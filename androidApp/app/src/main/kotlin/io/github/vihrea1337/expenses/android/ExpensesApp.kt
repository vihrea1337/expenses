package io.github.vihrea1337.expenses.android

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import io.github.vihrea1337.expenses.android.data.SessionStore
import io.github.vihrea1337.expenses.android.sync.SyncScheduler
import io.github.vihrea1337.expenses.android.widget.WidgetUpdater
import javax.inject.Inject

/**
 * Класс приложения. @HiltAndroidApp — точка входа Hilt: отсюда он строит граф
 * зависимостей для всех экранов, воркеров и виджета.
 *
 * Configuration.Provider — WorkManager создаёт воркеры через фабрику Hilt, поэтому
 * SyncWorker может получать репозиторий в конструктор.
 */
@HiltAndroidApp
class ExpensesApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var session: SessionStore
    @Inject lateinit var syncScheduler: SyncScheduler
    @Inject lateinit var widgetUpdater: WidgetUpdater

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        // Вошедшему пользователю — периодическая синхронизация (KEEP: не сбрасывает
        // уже запланированную) и разовая сразу: вдруг в очереди лежат офлайн-траты.
        if (session.token != null) {
            syncScheduler.schedulePeriodic()
            syncScheduler.syncSoon()
        }
        // Виджет на рабочем столе перерисовывается, когда меняются траты или бюджет —
        // откуда бы ни пришло изменение (экран, фоновая синхронизация, выход из аккаунта).
        widgetUpdater.start()
    }
}
