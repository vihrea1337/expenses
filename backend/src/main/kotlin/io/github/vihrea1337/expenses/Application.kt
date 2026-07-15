package io.github.vihrea1337.expenses

import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable

/**
 * Точка входа. Поднимает HTTP-сервер на движке Netty.
 *
 * host = "127.0.0.1" — сервер слушает ТОЛЬКО локальные подключения. На боевом сервере
 * снаружи до него будет дотягиваться только nginx (он стоит спереди и раздаёт HTTPS).
 * Наружу в интернет порт 8080 не торчит — так безопаснее.
 */
fun main() {
    embeddedServer(Netty, port = 8080, host = "127.0.0.1") {
        module()
    }.start(wait = true)
}

/**
 * Настройка приложения: подключаем плагины и описываем маршруты (адреса-"ручки").
 * Вынесено в отдельную функцию, чтобы позже переиспользовать её в тестах.
 */
fun Application.module() {
    // Плагин, который умеет превращать наши классы в JSON при ответе.
    install(ContentNegotiation) {
        json()
    }

    routing {
        // Проверка "жив ли сервер". Открой в браузере http://127.0.0.1:8080/health
        get("/health") {
            call.respond(HealthResponse(status = "ok"))
        }
    }
}

/**
 * Ответ health-проверки. @Serializable — разрешение для kotlinx.serialization
 * автоматически превратить этот класс в JSON вида {"status":"ok"}.
 */
@Serializable
data class HealthResponse(val status: String)
