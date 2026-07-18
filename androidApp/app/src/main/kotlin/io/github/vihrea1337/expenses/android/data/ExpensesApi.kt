package io.github.vihrea1337.expenses.android.data

import io.github.vihrea1337.expenses.android.BuildConfig
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

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
     * DELETE /api/expenses/{id} — удалить трату по id.
     * Response<Unit> — нам важен только статус ответа (204 = удалено), тело сервер не шлёт.
     */
    @DELETE("api/expenses/{id}")
    suspend fun deleteExpense(@Path("id") id: String): Response<Unit>
}

/**
 * Единая точка создания клиента Retrofit. by lazy — создаём его один раз при первом обращении.
 */
object ApiClient {
    // Адрес нашего бэкенда на VPS. Должен оканчиваться на "/".
    // Если позже переедем на другой адрес — меняем только эту строку.
    private const val BASE_URL = "https://sashlevhealth.duckdns.org/"

    val api: ExpensesApi by lazy {
        // ignoreUnknownKeys = true — если сервер пришлёт лишние поля, не падаем.
        val json = Json { ignoreUnknownKeys = true }
        val contentType = "application/json".toMediaType()

        // OkHttp-клиент с "перехватчиком" (interceptor): он вклинивается в каждый исходящий
        // запрос и добавляет заголовок Authorization с токеном, чтобы сервер нас пустил.
        val httpClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val builder = chain.request().newBuilder()
                if (BuildConfig.API_TOKEN.isNotEmpty()) {
                    builder.addHeader("Authorization", "Bearer ${BuildConfig.API_TOKEN}")
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
