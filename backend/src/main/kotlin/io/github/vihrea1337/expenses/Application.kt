package io.github.vihrea1337.expenses

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.withCharset
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.bearer
import io.ktor.server.auth.principal
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.staticResources
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
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
 * Точка входа. host = "127.0.0.1" — сервер слушает только локально, снаружи до него
 * дотягивается лишь nginx (он раздаёт HTTPS). В интернет порт 8080 не торчит.
 */
fun main() {
    configureDatabase() // подключиться к базе, создать таблицы, мигрировать старые данные
    startBot()          // Telegram-бот в фоне (если есть токен)
    embeddedServer(Netty, port = 8080, host = "127.0.0.1") {
        module()
    }.start(wait = true)
}

/** id текущего (авторизованного) пользователя — из "личности", которую положила проверка токена. */
private fun ApplicationCall.userId(): UUID =
    UUID.fromString(principal<UserIdPrincipal>()!!.name)

fun Application.module() {
    // Плагин: превращает наши классы в JSON и обратно.
    install(ContentNegotiation) { json() }

    // Авторизация по токену: находим пользователя, чей токен пришёл в заголовке
    // "Authorization: Bearer <token>". Нашли — пускаем как этого пользователя; нет — 401.
    install(Authentication) {
        bearer("api-auth") {
            authenticate { credential ->
                val user = UserRepository.findByToken(credential.token)
                if (user != null) UserIdPrincipal(user.id.toString()) else null
            }
        }
    }

    routing {
        // Регистрация — БЕЗ токена (иначе новый пользователь не смог бы завести аккаунт).
        post("/api/register") {
            val body = call.receive<RegisterRequest>()
            val user = UserRepository.create(body.name)
            call.respond(UserResponse(token = user.token, name = user.displayName))
        }

        // Всё остальное /api/* — только с валидным токеном, и в контексте своего пользователя.
        authenticate("api-auth") {
            apiRoutes()
            get("/api/me") {
                val user = UserRepository.findById(call.userId())
                if (user == null) call.respond(HttpStatusCode.Unauthorized)
                else call.respond(MeResponse(name = user.displayName))
            }
        }

        // Открытые: health для мониторинга и веб-страница (данные за ней всё равно под токеном).
        get("/health") {
            call.respond(HealthResponse(status = "ok"))
        }
        staticResources("/", "static")
    }
}

/** REST-ручки трат/бюджета. Каждая работает в контексте текущего пользователя (call.userId()). */
private fun Route.apiRoutes() {
    get("/api/expenses") {
        call.respond(ExpenseRepository.all(call.userId()))
    }

    // Выгрузка всех трат пользователя в CSV-файл (открывается в Excel/Google Таблицах).
    get("/api/expenses.csv") {
        val rows = ExpenseRepository.all(call.userId()).sortedByDescending { it.createdAt }
        // Content-Disposition: attachment — браузер скачает файл, а не покажет в окне.
        call.response.header(HttpHeaders.ContentDisposition, "attachment; filename=\"expenses.csv\"")
        call.respondText(buildCsv(rows), ContentType.Text.CSV.withCharset(Charsets.UTF_8))
    }

    post("/api/expenses") {
        val userId = call.userId()
        val body = call.receive<NewExpense>()
        val saved = ExpenseRepository.add(userId, body)
        Classifier.scheduleClassification(saved.id, saved.category)
        call.respond(saved)
    }

    delete("/api/expenses/{id}") {
        val userId = call.userId()
        val uuid = runCatching { UUID.fromString(call.parameters["id"]) }.getOrNull()
        if (uuid == null) {
            call.respond(HttpStatusCode.BadRequest, "Некорректный id")
            return@delete
        }
        val removed = ExpenseRepository.delete(userId, uuid)
        call.respond(if (removed) HttpStatusCode.NoContent else HttpStatusCode.NotFound)
    }

    put("/api/expenses/{id}") {
        val userId = call.userId()
        val uuid = runCatching { UUID.fromString(call.parameters["id"]) }.getOrNull()
        if (uuid == null) {
            call.respond(HttpStatusCode.BadRequest, "Некорректный id")
            return@put
        }
        val body = call.receive<UpdateExpense>()
        val updated = ExpenseRepository.updateExpense(userId, uuid, body.amount, body.category, body.note, body.categoryGroup)
        if (updated == null) {
            call.respond(HttpStatusCode.NotFound)
            return@put
        }
        if (body.categoryGroup == null) {
            Classifier.scheduleClassification(updated.id, updated.category)
        }
        call.respond(updated)
    }

    get("/api/budget") {
        call.respond(BudgetDto(UserRepository.getBudget(call.userId())))
    }

    put("/api/budget") {
        val userId = call.userId()
        val body = call.receive<BudgetDto>()
        UserRepository.setBudget(userId, body.monthlyBudget)
        call.respond(BudgetDto(UserRepository.getBudget(userId)))
    }

    post("/api/reclassify") {
        call.respond(ReclassifyResult(Classifier.reclassifyPending(call.userId())))
    }
}

/**
 * Собрать CSV из списка трат. Разделитель — «;» (так русский Excel открывает файл сразу по столбцам).
 * В начало добавляем BOM (﻿), иначе Excel на Windows покажет кириллицу «кракозябрами».
 */
private fun buildCsv(rows: List<Expense>): String {
    val sb = StringBuilder()
    sb.append('﻿') // BOM — метка кодировки UTF-8 для Excel
    sb.append("Дата;Категория;Сумма;Категория ИИ;Заметка\r\n")
    for (e in rows) {
        val amount = if (e.amount % 1.0 == 0.0) e.amount.toLong().toString() else e.amount.toString()
        val fields = listOf(
            e.createdAt.take(16).replace('T', ' '), // "2026-07-18 04:27"
            e.category,
            amount,
            e.categoryGroup ?: "",
            e.note ?: "",
        )
        sb.append(fields.joinToString(";") { csvField(it) }).append("\r\n")
    }
    return sb.toString()
}

/** Экранировать поле CSV: если внутри «;», кавычка или перенос строки — обернуть в кавычки. */
private fun csvField(value: String): String =
    if (value.any { it == ';' || it == '"' || it == '\n' || it == '\r' }) {
        "\"" + value.replace("\"", "\"\"") + "\""
    } else {
        value
    }

/**
 * Подключение к базе PostgreSQL, создание таблиц и миграция старых данных под аккаунты.
 */
fun configureDatabase() {
    val config = HikariConfig().apply {
        jdbcUrl = System.getenv("DB_URL") ?: "jdbc:postgresql://localhost:5432/expenses"
        driverClassName = "org.postgresql.Driver"
        username = System.getenv("DB_USER") ?: "postgres"
        password = System.getenv("DB_PASSWORD") ?: "dev"
        maximumPoolSize = 5
    }
    val dataSource = HikariDataSource(config)
    Database.connect(dataSource)

    transaction {
        SchemaUtils.create(Expenses, Settings, Users)
        // Новые столбцы для существующей таблицы expenses (create их не добавляет).
        exec("ALTER TABLE expenses ADD COLUMN IF NOT EXISTS category_group VARCHAR(50)")
        exec("ALTER TABLE expenses ADD COLUMN IF NOT EXISTS user_id UUID")
    }
    // Бутстрап "владельца" по API_TOKEN и привязка к нему старых трат/бюджета.
    UserRepository.bootstrapOwnerAndMigrate(System.getenv("API_TOKEN")?.trim())
}

@Serializable
data class HealthResponse(val status: String)

@Serializable
data class BudgetDto(val monthlyBudget: Double? = null)

@Serializable
data class ReclassifyResult(val pending: Int)

/** Тело PUT /api/expenses/{id}: categoryGroup = null → переопределить ИИ, иначе ручная категория. */
@Serializable
data class UpdateExpense(
    val amount: Double,
    val category: String,
    val note: String? = null,
    val categoryGroup: String? = null,
)

/** Тело регистрации: имя пользователя. */
@Serializable
data class RegisterRequest(val name: String = "")

/** Ответ регистрации: токен доступа и имя. */
@Serializable
data class UserResponse(val token: String, val name: String)

/** Ответ /api/me: кто я. */
@Serializable
data class MeResponse(val name: String)
