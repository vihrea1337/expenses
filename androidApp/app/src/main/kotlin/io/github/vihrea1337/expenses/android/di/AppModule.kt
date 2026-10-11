package io.github.vihrea1337.expenses.android.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.github.vihrea1337.expenses.android.data.ExpensesApi
import io.github.vihrea1337.expenses.android.data.SessionStore
import io.github.vihrea1337.expenses.android.data.local.ExpenseDao
import io.github.vihrea1337.expenses.android.data.local.ExpensesDatabase
import java.time.Clock
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/**
 * Hilt-модуль: «рецепты», как создавать объекты, которые нельзя просто пометить @Inject
 * (классы из чужих библиотек: OkHttp, Retrofit, Room).
 *
 * @InstallIn(SingletonComponent) + @Singleton — один экземпляр на всё приложение.
 * Свои классы (SessionStore, репозитории) Hilt умеет создавать сам по @Inject-конструктору —
 * им рецепты здесь не нужны.
 *
 * Раньше всё это жило в глобальных объектах (`object ApiClient`, `object ExpensesDatabase`)
 * с ручным `init(context)` в Activity. Минусы того подхода: порядок инициализации надо было
 * помнить самому, а подменить базу или сеть в тестах было нельзя.
 */
/** Метка (qualifier) для области корутин приложения — чтобы Hilt не спутал её с другой CoroutineScope. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    /** Часы: экраны берут «сегодня» отсюда, а тесты подставляют фиксированную дату. */
    @Provides
    fun provideClock(): Clock = Clock.systemDefaultZone()

    /**
     * Область корутин на всё время жизни приложения — для работы, которая должна
     * пережить экран (дописать удаление, когда экран уже закрыт; следить за данными для
     * виджета). SupervisorJob — сбой одной задачи не отменяет остальные.
     */
    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Provides
    @Singleton
    fun provideJson(): Json = Json { ignoreUnknownKeys = true } // лишние поля от сервера не роняют разбор

    @Provides
    @Singleton
    fun provideOkHttp(session: SessionStore): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            // Перехватчик — код, через который проходит КАЖДЫЙ запрос и ответ.
            // 1) Добавляем токен (для /api/register его ещё нет — тогда просто не добавляем;
            //    при проверке токена на входе заголовок уже задан явно — второй не добавляем).
            val request = chain.request()
            val token = session.token
            val authorized = if (token.isNullOrEmpty() || request.header("Authorization") != null) request else {
                request.newBuilder().addHeader("Authorization", "Bearer $token").build()
            }
            val response = chain.proceed(authorized)
            // 2) 401 на запросе с токеном ИЗ СЕССИИ — токен отозван (аккаунт удалён на
            //    сервере). Стираем сессию: навигация подписана на неё и сама откроет экран
            //    входа. Иначе пользователь видел бы непонятные ошибки на всех экранах.
            if (response.code == 401 && authorized !== request) session.clear()
            response
        }
        .build()

    @Provides
    @Singleton
    fun provideApi(client: OkHttpClient, json: Json): ExpensesApi = Retrofit.Builder()
        .baseUrl(ExpensesApi.BASE_URL)
        .client(client)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(ExpensesApi::class.java)

    /** Настоящий файл SQLite на телефоне — офлайн-кэш трат. */
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): ExpensesDatabase =
        Room.databaseBuilder(context, ExpensesDatabase::class.java, "expenses.db")
            // Кэш сервера: при смене схемы проще пересоздать и скачать заново
            // (компромисс описан у ExpensesDatabase).
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()

    @Provides
    fun provideDao(db: ExpensesDatabase): ExpenseDao = db.expenseDao()
}
