package io.github.vihrea1337.expenses.android.data

import io.github.vihrea1337.expenses.BudgetDto
import io.github.vihrea1337.expenses.Expense
import io.github.vihrea1337.expenses.ExpensesChangesDto
import io.github.vihrea1337.expenses.MeResponse
import io.github.vihrea1337.expenses.NewExpense
import io.github.vihrea1337.expenses.RegisterRequest
import io.github.vihrea1337.expenses.UpdateExpense
import io.github.vihrea1337.expenses.UserResponse
import io.github.vihrea1337.expenses.android.BuildConfig
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Описание "ручек" нашего REST API. Retrofit по этому интерфейсу сам сгенерирует
 * рабочий код HTTP-запросов. suspend — функции асинхронные (вызываются из корутины).
 *
 * Сам клиент (адрес сервера, токен в заголовке, таймауты) настраивается в di/AppModule —
 * раньше это был `object ApiClient` рядом, теперь его создаёт Hilt.
 */
interface ExpensesApi {
    /**
     * GET /api/expenses/changes — что изменилось после момента [since] (null — вся история),
     * включая мягко удалённые (`deleted = true`). Основа офлайн-синхронизации: полный список
     * не гоняем, только дельту.
     */
    @GET("api/expenses/changes")
    suspend fun getExpensesChanges(@Query("since") since: String?, @Query("limit") limit: Int = 200): ExpensesChangesDto

    /** POST /api/expenses — добавить трату; в тело кладём NewExpense, сервер вернёт готовую Expense. */
    @POST("api/expenses")
    suspend fun addExpense(@Body body: NewExpense): Expense

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

    /**
     * GET /api/me — проверить токен и узнать имя текущего пользователя.
     * [authorization] — заголовок явно, для проверки токена ДО входа (в сессии его ещё нет);
     * null — Retrofit заголовок не добавит, и токен подставит перехватчик из сессии.
     */
    @GET("api/me")
    suspend fun me(@Header("Authorization") authorization: String? = null): MeResponse

    /** GET /api/expenses.csv — выгрузка всех трат в CSV. ResponseBody — сырые байты файла. */
    @GET("api/expenses.csv")
    suspend fun exportCsv(): ResponseBody

    companion object {
        // Адрес бэкенда — из настроек сборки (см. app/build.gradle.kts): release всегда
        // смотрит на боевой сервер, debug можно направить на локальный. Оканчивается на "/".
        val BASE_URL: String = BuildConfig.BASE_URL
    }
}
