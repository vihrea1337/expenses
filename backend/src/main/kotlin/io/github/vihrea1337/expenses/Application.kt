package io.github.vihrea1337.expenses

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction

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

    routing {
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

        // Проверка "жив ли сервер". Открой в браузере http://127.0.0.1:8080/health
        get("/health") {
            call.respond(HealthResponse(status = "ok"))
        }
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
        // jdbcUrl — адрес базы. Формат: jdbc:postgresql://<хост>:<порт>/<имя_базы>.
        // localhost:5432 — наш Docker-контейнер expenses-pg; expenses — имя базы внутри него.
        jdbcUrl = "jdbc:postgresql://localhost:5432/expenses"
        // Явно указываем драйвер PostgreSQL (класс, который умеет говорить именно с Postgres).
        driverClassName = "org.postgresql.Driver"
        // Логин и пароль. Пока это локальные dev-значения (postgres / dev).
        // Позже, на боевом сервере, вынесем их в переменные окружения, а не в код.
        username = "postgres"
        password = "dev"
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
        SchemaUtils.create(Expenses)
    }
}

/**
 * Ответ health-проверки. @Serializable — разрешение для kotlinx.serialization
 * автоматически превратить этот класс в JSON вида {"status":"ok"}.
 */
@Serializable
data class HealthResponse(val status: String)
