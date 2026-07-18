package io.github.vihrea1337.expenses

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.Properties
import java.util.UUID

/*
 * Модели запроса/ответа к Groq (OpenAI-совместимый формат chat completions).
 * Описываем только нужные поля.
 */
@Serializable
private data class GroqRequest(
    val model: String,
    val messages: List<GroqMessage>,
    val temperature: Double = 0.0,
    @SerialName("max_tokens") val maxTokens: Int = 10,
)

@Serializable
private data class GroqMessage(val role: String, val content: String)

@Serializable
private data class GroqResponse(val choices: List<GroqChoice> = emptyList())

@Serializable
private data class GroqChoice(val message: GroqMessage)

/**
 * Классификатор трат: по описанию ("булочка") определяет обобщённую категорию ("еда")
 * с помощью ИИ-сервиса Groq. Работает в фоне: трата сохраняется сразу, а категория
 * проставляется чуть позже отдельной корутиной. Если ключа нет или Groq недоступен —
 * трата просто остаётся без категории (позже можно переклассифицировать).
 */
object Classifier {

    // Фиксированный список категорий: ИИ обязан выбрать ровно одну из них — так аналитика
    // остаётся единообразной (не "еда"/"питание"/"продукты" вперемешку).
    private val categories = listOf(
        "еда", "транспорт", "дом", "развлечения",
        "здоровье", "одежда", "связь", "подарки", "прочее",
    )

    // Ключ и модель Groq: сначала из переменных окружения (боевой сервер),
    // иначе из secrets.properties (удобно для локальных тестов).
    private val apiKey: String =
        (System.getenv("GROQ_API_KEY")?.trim()?.ifEmpty { null } ?: readSecret("groq.api.key")).orEmpty()
    private val model: String =
        System.getenv("GROQ_MODEL")?.trim()?.ifEmpty { null } ?: readSecret("groq.model") ?: "openai/gpt-oss-20b"

    /** Включён ли классификатор (есть ли ключ). Если нет — все траты остаются без категории. */
    val enabled: Boolean = apiKey.isNotEmpty()

    // Отдельная "дорожка" для фоновой классификации, чтобы не блокировать основной поток.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val client = HttpClient(CIO) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        install(HttpTimeout) { requestTimeoutMillis = 15_000 }
    }

    private val systemPrompt = """
        Ты классификатор личных расходов. По краткому описанию траты определи её категорию.
        Ответь РОВНО одним словом строго из этого списка: ${categories.joinToString(", ")}.
        Никаких пояснений, только одно слово из списка.
    """.trimIndent()

    init {
        if (enabled) {
            println("Классификатор ИИ включён (модель $model).")
        } else {
            println("Классификатор ИИ выключен: нет GROQ_API_KEY — траты будут без категории.")
        }
    }

    /**
     * Запланировать классификацию траты в фоне (не ждём результат).
     * idString — id только что созданной траты, text — её описание (категория-текст).
     */
    fun scheduleClassification(idString: String, text: String) {
        if (!enabled) return
        val id = runCatching { UUID.fromString(idString) }.getOrNull() ?: return
        scope.launch {
            val group = classify(text)
            if (group != null) ExpenseRepository.updateGroup(id, group)
        }
    }

    /**
     * Переклассифицировать все траты без категории (запускается в фоне).
     * Возвращает, сколько трат взято в обработку. Между запросами — пауза (щадим лимиты Groq).
     */
    fun reclassifyPending(): Int {
        if (!enabled) return 0
        val pending = ExpenseRepository.expensesWithoutGroup()
        scope.launch {
            for ((id, text) in pending) {
                val group = classify(text) ?: continue
                ExpenseRepository.updateGroup(id, group)
                delay(300)
            }
        }
        return pending.size
    }

    /**
     * Спросить у Groq категорию для описания. Возвращает одну из categories,
     * либо "прочее", если ответ не распознан, либо null при ошибке сети/сервиса.
     */
    private suspend fun classify(text: String): String? {
        if (!enabled) return null
        return try {
            val response: GroqResponse =
                client.post("https://api.groq.com/openai/v1/chat/completions") {
                    header(HttpHeaders.Authorization, "Bearer $apiKey")
                    contentType(ContentType.Application.Json)
                    setBody(
                        GroqRequest(
                            model = model,
                            messages = listOf(
                                GroqMessage("system", systemPrompt),
                                GroqMessage("user", text),
                            ),
                        ),
                    )
                }.body()
            val raw = response.choices.firstOrNull()?.message?.content?.trim()?.lowercase()
                ?: return null
            // Находим, какая из наших категорий встретилась в ответе; иначе — "прочее".
            categories.firstOrNull { raw.contains(it) } ?: "прочее"
        } catch (e: Exception) {
            println("Классификатор: ошибка запроса к Groq — ${e.message}")
            null
        }
    }

    /** Прочитать значение из secrets.properties (для локальных тестов). */
    private fun readSecret(key: String): String? {
        val file = File("secrets.properties")
        if (!file.exists()) return null
        return Properties().apply { file.inputStream().use { load(it) } }
            .getProperty(key)?.trim()?.ifEmpty { null }
    }
}
