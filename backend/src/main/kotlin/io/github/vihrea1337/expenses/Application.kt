package io.github.vihrea1337.expenses

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.bearer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.staticResources
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID

/**
 * Точка входа. Поднимает HTTP-сервер на движке Netty.
 *
 * host = "127.0.0.1" — сервер слушает ТОЛЬКО локальные подключения. На боевом сервере
 * снаружи до него будет дотягиваться только nginx (он стоит спереди и раздаёт HTTPS).
 * Наружу в интернет порт 8080 не торчит — так безопаснее.
 */
fun main() {
    // 1. Первым делом подключаемся к базе. Её используют И HTTP-эндпоинты, И Telegram-бот,
    //    поэтому подключение должно быть готово ДО того, как хоть кто-то начнёт писать в базу.
    configureDatabase()

    // 2. Запускаем Telegram-бота (моторчик) в фоне. Если токена нет — просто не стартует,
    //    а сервер поднимается как обычно. К этому моменту база уже подключена — бот может писать.
    startBot()

    // 3. Поднимаем HTTP-сервер и ждём (wait = true — main не завершается, сервер работает).
    embeddedServer(Netty, port = 8080, host = "127.0.0.1") {
        module()
    }.start(wait = true)
}

/**
 * Настройка приложения: подключаем плагины и описываем маршруты (адреса-"ручки").
 * Вынесено в отдельную функцию, чтобы позже переиспользовать её в тестах.
 */
fun Application.module() {
    // Плагин, который умеет превращать наши классы в JSON при ответе (и обратно при приёме).
    install(ContentNegotiation) {
        json()
    }

    // Токен для защиты API берём из переменной окружения API_TOKEN.
    // Если он задан — все ручки /api/* требуют заголовок "Authorization: Bearer <token>".
    // Если не задан (например, локальная разработка) — API открыт, но предупреждаем в лог.
    val apiToken = System.getenv("API_TOKEN")?.trim().orEmpty()
    val authEnabled = apiToken.isNotEmpty()
    if (authEnabled) {
        install(Authentication) {
            // "api-auth" — имя нашей схемы проверки; bearer = токен в заголовке Authorization.
            bearer("api-auth") {
                authenticate { credential ->
                    // Пришедший токен совпал с нашим? Пускаем (возвращаем "личность").
                    // Иначе null → Ktor сам ответит 401 Unauthorized.
                    if (credential.token == apiToken) UserIdPrincipal("api") else null
                }
            }
        }
    } else {
        println("ВНИМАНИЕ: API_TOKEN не задан — REST API работает БЕЗ авторизации (ок для локали).")
    }

    routing {
        // Ручки /api/* — под проверкой токена, если он задан; иначе открыто.
        if (authEnabled) {
            authenticate("api-auth") { apiRoutes() }
        } else {
            apiRoutes()
        }

        // /health и веб-страница остаются открытыми: health — для мониторинга;
        // "/" — это только HTML-каркас, а данные за ним всё равно защищены токеном.
        get("/health") {
            call.respond(HealthResponse(status = "ok"))
        }
        staticResources("/", "static")
    }
}

/**
 * REST-ручки для работы с тратами. Вынесены в отдельную функцию, чтобы подключать их
 * и внутри authenticate { } (с проверкой токена), и без неё (локальная разработка).
 */
private fun Route.apiRoutes() {
    // Список всех трат. Вся работа с базой — внутри ExpenseRepository.
    get("/api/expenses") {
        call.respond(ExpenseRepository.all())
    }

    // Добавить трату. Тело запроса (JSON) превращается в NewExpense,
    // репозиторий кладёт его в базу и возвращает уже полноценную запись с id и временем.
    post("/api/expenses") {
        val body = call.receive<NewExpense>()
        val saved = ExpenseRepository.add(body)
        call.respond(saved)
    }

    // Удалить трату по id: DELETE /api/expenses/<id>.
    // {id} в пути — переменная, её значение достаём через call.parameters["id"].
    delete("/api/expenses/{id}") {
        val idParam = call.parameters["id"]
        // id должен быть корректным UUID; если нет — 400 (неверный запрос).
        val uuid = runCatching { UUID.fromString(idParam) }.getOrNull()
        if (uuid == null) {
            call.respond(HttpStatusCode.BadRequest, "Некорректный id")
            return@delete
        }
        val removed = ExpenseRepository.delete(uuid)
        // 204 No Content — удалили; 404 — траты с таким id не было.
        call.respond(if (removed) HttpStatusCode.NoContent else HttpStatusCode.NotFound)
    }

    // Прочитать месячный бюджет. monthlyBudget = null, если не задан.
    get("/api/budget") {
        call.respond(BudgetDto(ExpenseRepository.getBudget()))
    }

    // Задать (или сбросить) месячный бюджет. В теле — { "monthlyBudget": 30000 } или null.
    put("/api/budget") {
        val body = call.receive<BudgetDto>()
        ExpenseRepository.setBudget(body.monthlyBudget)
        call.respond(BudgetDto(ExpenseRepository.getBudget()))
    }
}

/**
 * Подключение к базе данных PostgreSQL и создание таблиц.
 *
 * Вызывается один раз при старте (из main). От Ktor не зависит — это обычная функция,
 * поэтому её легко переиспользовать (например, в тестах или отдельном скрипте).
 */
fun configureDatabase() {
    // 1. Настройки подключения. HikariConfig — это "анкета" для пула соединений:
    //    куда подключаться, под кем, с каким паролем.
    val config = HikariConfig().apply {
        // Адрес базы, логин и пароль берём из переменных окружения, а если их нет —
        // используем локальные dev-значения. Зачем так: ОДИН и тот же jar работает
        // и на моём ПК (localhost/postgres/dev), и на боевом сервере (там переменные
        // DB_URL/DB_USER/DB_PASSWORD задаёт systemd) — пароль в код не зашит.
        // System.getenv("ИМЯ") ?: "значение_по_умолчанию" — "взять переменную, а если её нет — вот это".
        //
        // jdbcUrl — адрес базы. Формат: jdbc:postgresql://<хост>:<порт>/<имя_базы>.
        jdbcUrl = System.getenv("DB_URL") ?: "jdbc:postgresql://localhost:5432/expenses"
        // Явно указываем драйвер PostgreSQL (класс, который умеет говорить именно с Postgres).
        driverClassName = "org.postgresql.Driver"
        username = System.getenv("DB_USER") ?: "postgres"
        password = System.getenv("DB_PASSWORD") ?: "dev"
        // Сколько максимум одновременных соединений держать в пуле. 5 для разработки хватает.
        maximumPoolSize = 5
    }

    // 2. По этой анкете HikariCP создаёт сам пул — набор готовых к работе соединений.
    val dataSource = HikariDataSource(config)

    // 3. Отдаём пул в Exposed. Теперь все запросы Exposed будут ходить в нашу базу через него.
    Database.connect(dataSource)

    // 4. Создаём таблицы. SchemaUtils.create читает "чертёж" Expenses (из Expenses.kt)
    //    и выполняет CREATE TABLE IF NOT EXISTS — то есть создаёт таблицу, только если её ещё нет
    //    (повторный запуск сервера ничего не сломает). transaction { } — обязательная обёртка:
    //    любые обращения к базе в Exposed выполняются внутри транзакции.
    transaction {
        SchemaUtils.create(Expenses, Settings)
    }
}

/**
 * Ответ health-проверки. @Serializable — разрешение для kotlinx.serialization
 * автоматически превратить этот класс в JSON вида {"status":"ok"}.
 */
@Serializable
data class HealthResponse(val status: String)

/**
 * Месячный бюджет для обмена по JSON. monthlyBudget = null означает "бюджет не задан".
 */
@Serializable
data class BudgetDto(val monthlyBudget: Double? = null)
