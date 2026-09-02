package io.github.vihrea1337.expenses

import kotlinx.serialization.Serializable

/**
 * КОНТРАКТ API Expenses — то, что летает между сервером, Android-приложением и (частично)
 * веб-страницей в JSON.
 *
 * Зачем отдельный модуль: раньше эти же классы (трата, ответ на регистрацию, бюджет и т.п.)
 * были описаны дважды — на бэкенде и в Android-приложении. Достаточно было добавить поле
 * на сервере и забыть про приложение, чтобы клиент молча разъехался с сервером. Теперь
 * определение одно, и оба Kotlin-проекта используют именно его: несовпадение полей
 * превращается из ошибки в рантайме в ошибку компиляции.
 *
 * Пакет специально тот же, что у бэкенда, — чтобы серверному коду не пришлось ничего
 * импортировать.
 *
 * Правила для этого файла:
 * - только данные, никакой логики и никаких серверных типов (ни Exposed, ни Ktor);
 * - у необязательных полей — значения по умолчанию, иначе старый клиент не разберёт
 *   ответ нового сервера;
 * - даты и id — строками (ISO-8601 и UUID): так одинаково понятно и Kotlin, и JavaScript.
 *
 * _Веб-страница (`static/index.html`) разбирает JSON сама: чистый JS без сборки —
 * осознанный выбор, поэтому её на этот модуль не перевести._
 */

/** Трата, как её отдаёт сервер (с id и временем создания). */
@Serializable
data class Expense(
    val id: String,
    val amount: Double,
    val category: String,
    val note: String? = null,
    val createdAt: String,
    // Обобщённая категория от ИИ ("еда", ...); null, пока не проставлена.
    val categoryGroup: String? = null,
    // Когда запись последний раз менялась (создание/правка/мягкое удаление) — основа
    // синхронизации офлайн-клиентов (GET /api/expenses/changes?since=). "" — дефолт на случай
    // разбора ответа от версии сервера без этого поля (контракт обязан быть терпим к
    // недостающим полям, даже если на практике такой версии уже не осталось).
    val updatedAt: String = "",
    // Мягкое удаление: true — это "надгробие". Из обычного GET /api/expenses такие не
    // приходят (сервер их фильтрует); появляются только в /api/expenses/changes.
    val deleted: Boolean = false,
)

/** Тело POST /api/expenses: трата, которую клиент ОТПРАВЛЯЕТ на сервер. */
@Serializable
data class NewExpense(
    val amount: Double,
    val category: String,
    // note необязательное: если клиент не прислал это поле в JSON, подставляется null.
    val note: String? = null,
    // Идемпотентность: клиент (Android/веб) сам придумывает UUID для новой траты ДО отправки.
    // Если сеть оборвалась после того, как сервер уже сохранил трату, но до того, как клиент
    // увидел ответ, повторная отправка с тем же id не создаст дубль — сервер вернёт уже
    // сохранённую запись (см. ExpenseRepository.add на бэкенде). null — id назначит сервер
    // (так работает, например, Telegram-бот, для которого идемпотентность не нужна).
    val id: String? = null,
)

/**
 * Тело PUT /api/expenses/{id}: правка существующей траты.
 * categoryGroup = null — категорию переопределит ИИ; иначе ручная правка.
 */
@Serializable
data class UpdateExpense(
    val amount: Double,
    val category: String,
    val note: String? = null,
    val categoryGroup: String? = null,
)

/**
 * Ответ синхронизации (GET /api/expenses/changes?since=&limit=): изменения по возрастанию
 * updatedAt + время сервера. Клиент запоминает serverTime из ответа и в следующий раз
 * присылает его как since — качать всю историю заново не нужно.
 */
@Serializable
data class ExpensesChangesDto(
    val expenses: List<Expense>,
    val serverTime: String,
    /** true — упёрлись в лимит страницы, нужно запросить следующую порцию с новым since. */
    val hasMore: Boolean,
)

/** Месячный бюджет. monthlyBudget = null означает "бюджет не задан". */
@Serializable
data class BudgetDto(
    val monthlyBudget: Double? = null,
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
