package io.github.vihrea1337.expenses.android.data

import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST

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
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .addConverterFactory(json.asConverterFactory(contentType))
            .build()
            .create(ExpensesApi::class.java)
    }
}
