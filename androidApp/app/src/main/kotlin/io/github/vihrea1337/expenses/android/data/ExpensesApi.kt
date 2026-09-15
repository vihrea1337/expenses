package io.github.vihrea1337.expenses.android.data

import io.github.vihrea1337.expenses.BudgetDto
import io.github.vihrea1337.expenses.Expense
import io.github.vihrea1337.expenses.ExpensesChangesDto
import io.github.vihrea1337.expenses.MeResponse
import io.github.vihrea1337.expenses.NewExpense
import io.github.vihrea1337.expenses.RegisterRequest
import io.github.vihrea1337.expenses.UpdateExpense
import io.github.vihrea1337.expenses.UserResponse
import io.github.vihrea1337.expenses.android.TokenStore
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Описание "ручек" нашего REST API. Retrofit по этому интерфейсу сам сгенерирует
 * рабочий код HTTP-запросов. suspend — функции асинхронные (вызываются из корутины).
 */
interface ExpensesApi {
    /** GET /api/expenses — получить список всех трат. */
    @GET("api/expenses")
    suspend fun getExpenses(): List<Expense>

    /** POST /api/expenses — добавить трату; в тело кладём NewExpense, сервер вернёт готовую Expense. */
    @POST("api/expenses")
    suspend fun addExpense(@Body body: NewExpense): Expense

    /**
     * GET /api/expenses/changes — что изменилось после момента [since] (null — вся история),
     * включая мягко удалённые (`deleted = true`). Основа офлайн-синхронизации: полный список
     * не гоняем, только дельту.
     */
    @GET("api/expenses/changes")
    suspend fun getExpensesChanges(@Query("since") since: String?, @Query("limit") limit: Int = 200): ExpensesChangesDto

    /**
     * DELETE /api/expenses/{id} — удалить трату по id.
     * Response<Unit> — нам важен только статус ответа (204 = удалено), тело сервер не шлёт.
     */
    @DELETE("api/expenses/{id}")
    suspend fun deleteExpense(@Path("id") id: String): Response<Unit>

    /** PUT /api/expenses/{id} — отредактировать трату (сумма/категория/категория ИИ). */
    @PUT("api/expenses/{id}")
    suspend fun editExpense(@Path("id") id: String, @Body body: UpdateExpense): Expense

    /** GET /api/budget — прочитать месячный бюджет (monthlyBudget = null, если не задан). */
    @GET("api/budget")
    suspend fun getBudget(): BudgetDto

    /** PUT /api/budget — задать/сбросить месячный бюджет. */
    @PUT("api/budget")
    suspend fun setBudget(@Body body: BudgetDto): BudgetDto

    /** POST /api/register — создать аккаунт (без токена), получить токен доступа. */
    @POST("api/register")
    suspend fun register(@Body body: RegisterRequest): UserResponse

    /** GET /api/me — проверить токен и узнать имя текущего пользователя. */
    @GET("api/me")
    suspend fun me(): MeResponse

    /** GET /api/expenses.csv — выгрузка всех трат в CSV. ResponseBody — сырые байты файла. */
    @GET("api/expenses.csv")
    suspend fun exportCsv(): ResponseBody
}

/**
 * Единая точка создания клиента Retrofit. by lazy — создаём его один раз при первом обращении.
 */
object ApiClient {
    // Адрес нашего бэкенда. Должен оканчиваться на "/".
    // Переезд на новый сервер (2026-09-15): свой nginx на стандартном 443, без
    // отдельного порта. Если переедем снова — меняем только эту строку.
    private const val BASE_URL = "https://vihreaexpenses.duckdns.org/"

    val api: ExpensesApi by lazy {
        // ignoreUnknownKeys = true — если сервер пришлёт лишние поля, не падаем.
        val json = Json { ignoreUnknownKeys = true }
        val contentType = "application/json".toMediaType()

        // OkHttp-клиент с "перехватчиком" (interceptor): он вклинивается в каждый исходящий
        // запрос и добавляет заголовок Authorization с токеном, чтобы сервер нас пустил.
        val httpClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val builder = chain.request().newBuilder()
                // Токен текущего пользователя (после входа/регистрации). Для /api/register его нет — ок.
                val token = TokenStore.token
                if (!token.isNullOrEmpty()) {
                    builder.addHeader("Authorization", "Bearer $token")
                }
                chain.proceed(builder.build())
            }
            .build()

        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(httpClient)
            .addConverterFactory(json.asConverterFactory(contentType))
            .build()
            .create(ExpensesApi::class.java)
    }
}
