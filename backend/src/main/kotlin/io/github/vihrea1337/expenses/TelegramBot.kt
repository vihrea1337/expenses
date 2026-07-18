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
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
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

/** Прочитать токен из secrets.properties (ключ bot.token). Нет файла/ключа — вернёт null. */
private fun readTokenFromFile(): String? {
    val file = File("secrets.properties")
    if (!file.exists()) return null
    return Properties()
        .apply { file.inputStream().use { load(it) } }
        .getProperty("bot.token")
        ?.trim()
        ?.ifEmpty { null }
}

/**
 * Запуск бота. Токен — из переменной окружения BOT_TOKEN или из secrets.properties
 * (файл в .gitignore — в git не попадёт). Если токена нигде нет — бот не запускается,
 * но сам сервер продолжает работать.
 */
fun startBot() {
    // Токен ищем в двух местах по порядку:
    //   1) переменная окружения BOT_TOKEN — так задаём токен на боевом сервере (в systemd);
    //   2) файл secrets.properties (ключ bot.token) — удобно локально при разработке.
    // Если нигде нет — бот просто не запускается, а сам сервер работает как обычно.
    val token = System.getenv("BOT_TOKEN")?.trim()?.ifEmpty { null }
        ?: readTokenFromFile()
    if (token.isNullOrEmpty()) {
        println("Бот НЕ запущен: нет токена (ни BOT_TOKEN, ни bot.token в secrets.properties)")
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

                // Готовим ответ (команда или запись траты) и отправляем его.
                val reply = handleText(text)
                sendMessage(client, base, message.chat.id, reply)
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

// Формат даты в ответах бота: "18.07 04:27".
private val dateFmt = DateTimeFormatter.ofPattern("dd.MM HH:mm")

/**
 * Формирует ответ бота на сообщение пользователя:
 *  - известная команда (/help, /list, /total или русское слово) — справка / список / сумма;
 *  - иначе пытаемся разобрать текст как трату ("кофе 200") и записать её в базу.
 */
private fun handleText(text: String): String {
    val trimmed = text.trim()
    val lower = trimmed.lowercase()

    // Бюджет: "/budget" или "бюджет" — показать статус; "бюджет 30000" — задать лимит.
    if (lower == "/budget" || lower == "бюджет") return budgetStatusText()
    if (lower.startsWith("бюджет ") || lower.startsWith("/budget ")) {
        val value = trimmed.substringAfter(' ').trim().replace(',', '.').toDoubleOrNull()
        return if (value != null && value > 0) {
            ExpenseRepository.setBudget(value)
            "✅ Бюджет на месяц: ${formatMoney(value)} ₽"
        } else {
            "Не понял сумму бюджета. Пример: бюджет 30000"
        }
    }

    return when (lower) {
        "/start", "/help", "помощь", "старт" -> helpText()
        "/list", "список", "траты" -> listText()
        "/total", "итого", "сумма", "сколько" -> totalText()
        "/stats", "статистика", "категории" -> statsText()
        else -> {
            val new = parseExpense(trimmed)
            if (new == null) {
                "Не понял 🤔 Напиши категорию и сумму, например: кофе 200\n(справка — /help)"
            } else {
                val saved = ExpenseRepository.add(new)
                // Обобщённую категорию проставит ИИ в фоне.
                Classifier.scheduleClassification(saved.id, saved.category)
                "✅ Записал: ${saved.category} — ${formatMoney(saved.amount)} ₽"
            }
        }
    }
}

/** Текст справки (/help, /start). */
private fun helpText(): String = """
    Привет! Я записываю твои траты 💸

    Просто напиши категорию и сумму, например:
    кофе 200
    такси до дома 350

    Команды:
    /list — последние траты
    /total — сколько потрачено
    /stats — траты по категориям
    /budget — бюджет на месяц (задать: бюджет 30000)
    /help — эта справка
""".trimIndent()

/** Список последних (до 10) трат. */
private fun listText(): String {
    val all = ExpenseRepository.all().sortedByDescending { it.createdAt }
    if (all.isEmpty()) return "Пока трат нет. Напиши, например: кофе 200"
    val shown = all.take(10)
    val lines = shown.joinToString("\n") { e ->
        "• ${e.category} — ${formatMoney(e.amount)} ₽ (${formatDate(e.createdAt)})"
    }
    val tail = if (all.size > shown.size) "\n… показаны ${shown.size} из ${all.size}" else ""
    return "Последние траты:\n$lines$tail"
}

/** Сумма всех трат и отдельно за сегодня. */
private fun totalText(): String {
    val all = ExpenseRepository.all()
    if (all.isEmpty()) return "Пока трат нет. Напиши, например: кофе 200"
    val total = all.sumOf { it.amount }
    val today = LocalDate.now()
    val todayTotal = all
        .filter { runCatching { LocalDate.parse(it.createdAt.take(10)) == today }.getOrDefault(false) }
        .sumOf { it.amount }
    return "Всего: ${formatMoney(total)} ₽ за ${all.size} трат.\nСегодня: ${formatMoney(todayTotal)} ₽"
}

/** Разбивка трат по обобщённым категориям (от ИИ) с суммой и долей в процентах. */
private fun statsText(): String {
    val all = ExpenseRepository.all()
    if (all.isEmpty()) return "Пока трат нет. Напиши, например: кофе 200"
    val total = all.sumOf { it.amount }
    val byGroup = all
        .groupBy { it.categoryGroup ?: "без категории" }
        .map { (group, list) -> group to list.sumOf { it.amount } }
        .sortedByDescending { it.second }
        .take(10)
    val lines = byGroup.joinToString("\n") { (group, sum) ->
        val percent = if (total > 0) Math.round(sum / total * 100) else 0
        "• $group — ${formatMoney(sum)} ₽ ($percent%)"
    }
    return "Траты по категориям (всего ${formatMoney(total)} ₽):\n$lines"
}

/** Статус месячного бюджета: лимит, потрачено в этом месяце, остаток/перерасход. */
private fun budgetStatusText(): String {
    val budget = ExpenseRepository.getBudget()
        ?: return "Бюджет на месяц не задан. Задай так: бюджет 30000"
    val spent = currentMonthSpent()
    val left = budget - spent
    val tail = if (left >= 0) {
        "Осталось: ${formatMoney(left)} ₽"
    } else {
        "Перерасход: ${formatMoney(-left)} ₽ ⚠️"
    }
    return "Бюджет на месяц: ${formatMoney(budget)} ₽\n" +
        "Потрачено в этом месяце: ${formatMoney(spent)} ₽\n$tail"
}

/** Сумма трат за текущий календарный месяц (с 1-го числа). */
private fun currentMonthSpent(): Double {
    val now = LocalDate.now()
    return ExpenseRepository.all()
        .filter {
            runCatching {
                val d = LocalDate.parse(it.createdAt.take(10))
                d.year == now.year && d.monthValue == now.monthValue
            }.getOrDefault(false)
        }
        .sumOf { it.amount }
}

/** 200.0 -> "200", 149.5 -> "149.5" (убираем лишний ".0"). */
private fun formatMoney(v: Double): String =
    if (v % 1.0 == 0.0) v.toLong().toString() else v.toString()

/** ISO-строка времени -> "18.07 04:27". */
private fun formatDate(iso: String): String =
    runCatching { LocalDateTime.parse(iso).format(dateFmt) }.getOrDefault(iso.take(16))
