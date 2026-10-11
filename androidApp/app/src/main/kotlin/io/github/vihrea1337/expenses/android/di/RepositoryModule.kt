package io.github.vihrea1337.expenses.android.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.vihrea1337.expenses.android.data.AuthRepository
import io.github.vihrea1337.expenses.android.data.AuthRepositoryImpl
import io.github.vihrea1337.expenses.android.data.DataStoreSettings
import io.github.vihrea1337.expenses.android.data.ExpensesRepository
import io.github.vihrea1337.expenses.android.data.ExpensesRepositoryImpl
import io.github.vihrea1337.expenses.android.data.SettingsStore
import io.github.vihrea1337.expenses.android.sync.SyncScheduler
import io.github.vihrea1337.expenses.android.sync.WorkManagerSyncScheduler

/**
 * Связка «интерфейс → реализация». Экраны просят у Hilt ExpensesRepository (интерфейс),
 * а получают ExpensesRepositoryImpl. @Binds — короче, чем @Provides: Hilt сам создаёт
 * реализацию по её @Inject-конструктору, здесь только указываем, что ею подменять.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {
    @Binds abstract fun expenses(impl: ExpensesRepositoryImpl): ExpensesRepository
    @Binds abstract fun auth(impl: AuthRepositoryImpl): AuthRepository
    @Binds abstract fun settings(impl: DataStoreSettings): SettingsStore
    @Binds abstract fun syncScheduler(impl: WorkManagerSyncScheduler): SyncScheduler
}
