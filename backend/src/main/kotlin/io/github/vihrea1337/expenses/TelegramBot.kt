package io.github.vihrea1337.expenses

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.Properties
import kotlin.concurrent.thread

/*
 * Модели ответа Telegram. Telegram присылает JSON с ДЕСЯТКАМИ полей, а нам нужны единицы.
 * Мы описываем только нужные; всё остальное игнорируется (см. ignoreUnknownKeys ниже).
 */

@Serializable
data class TgResponse(val ok: Boolean, val result: List<TgUpdate> = emptyList())

@Serializable
data class TgUpdate(
    // В JSON поле называется "update_id" (с подчёркиванием), а в Kotlin принято "updateId".
    // @SerialName связывает имя из JSON с нашим именем поля.
    @SerialName("update_id") val updateId: Long,
    val message: TgMessage? = null,
)

@Serializable
data class TgMessage(val chat: TgChat, val text: String? = null)

@Serializable
data class TgChat(val id: Long)

/**
 * Запуск бота. Читает токен из secrets.properties (файл в .gitignore — в git не попадёт).
 * Если файла или токена нет — бот не запускается, но сам сервер продолжает работать.
 */
fun startBot() {
    val file = File("secrets.properties")
    if (!file.exists()) {
        println("Бот НЕ запущен: нет файла secrets.properties (создай его и впиши bot.token=...)")
        return
    }
    val token = Properties()
        .apply { file.inputStream().use { load(it) } }
        .getProperty("bot.token")
        ?.trim()
    if (token.isNullOrEmpty()) {
        println("Бот НЕ запущен: в secrets.properties пустой ключ bot.token")
        return
    }

    // Отдельный фоновый поток ("дорожка"), чтобы вечный опрос бота не блокировал HTTP-сервер.
    // isDaemon = true — поток сам по себе не держит программу живой: остановим сервер — погаснет и бот.
    thread(isDaemon = true, name = "telegram-bot") {
        runBlocking { botLoop(token) }
    }
    println("Бот запущен (long-polling).")
}

/**
 * Главный цикл бота (long-polling): бесконечно спрашиваем Telegram про новые сообщения
 * и на каждое отвечаем. Пока это "эхо" — повторяем присланный текст обратно.
 */
private suspend fun botLoop(token: String) {
    // HTTP-клиент — то, чем наш сервер САМ делает запросы к Telegram.
    val client = HttpClient(CIO) {
        // Разрешаем клиенту разбирать JSON-ответы Telegram в наши классы (TgResponse и т.д.).
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
        // Долгий опрос ждёт до 30с; даём запас по времени, чтобы клиент не оборвал ожидание раньше.
        install(HttpTimeout) { requestTimeoutMillis = 60_000 }
    }

    val base = "https://api.telegram.org/bot$token"
    // offset — "с какого сообщения продолжать". Указываем последний_update_id + 1, и Telegram
    // больше не присылает уже обработанные сообщения.
    var offset = 0L

    println("Бот: начинаю опрос Telegram…")
    while (true) {
        try {
            // getUpdates = "дай новые сообщения". timeout=30 включает long-polling:
            // Telegram держит ответ до 30 секунд, пока не появится сообщение
            // (это экономнее, чем долбить его короткими запросами каждую секунду).
            val response: TgResponse = client.get("$base/getUpdates") {
                parameter("offset", offset)
                parameter("timeout", 30)
            }.body()

            for (update in response.result) {
                // Сдвигаем offset, чтобы это сообщение больше не пришло повторно.
                offset = update.updateId + 1

                val message = update.message ?: continue // не обычное сообщение — пропускаем
                val text = message.text ?: continue       // без текста (стикер/фото) — пропускаем

                // Разбираем текст в трату. Если формат непонятен — parseExpense вернёт null.
                val new = parseExpense(text)
                if (new == null) {
                    sendMessage(
                        client, base, message.chat.id,
                        "Не понял 🤔 Формат: категория и сумма, например: кофе 200",
                    )
                } else {
                    // Пишем в ту же базу, что и REST API (через общий ExpenseRepository).
                    val saved = ExpenseRepository.add(new)
                    sendMessage(
                        client, base, message.chat.id,
                        "Записал: ${saved.category} — ${saved.amount} ₽",
                    )
                }
            }
        } catch (e: Exception) {
            // Сеть моргнула или Telegram недоступен — не падаем, ждём и пробуем снова.
            println("Бот: ошибка опроса — ${e.message}; повтор через 3с")
            delay(3000)
        }
    }
}

/** Отправка текстового сообщения в чат: chat_id — кому, text — что. */
private suspend fun sendMessage(client: HttpClient, base: String, chatId: Long, text: String) {
    // parameter(...) сам корректно кодирует текст (пробелы, кириллицу) для URL.
    client.get("$base/sendMessage") {
        parameter("chat_id", chatId)
        parameter("text", text)
    }
}
