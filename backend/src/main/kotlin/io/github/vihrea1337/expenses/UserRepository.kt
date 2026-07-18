package io.github.vihrea1337.expenses

import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.LocalDateTime
import java.util.UUID

/** Пользователь (аккаунт). token — его "ключ доступа". */
data class User(
    val id: UUID,
    val token: String,
    val displayName: String,
    val telegramId: Long?,
    val monthlyBudget: Double?,
)

/**
 * Работа с пользователями: создание, поиск по токену/Telegram, бюджет и миграция старых данных.
 */
object UserRepository {

    private fun rowToUser(row: ResultRow) = User(
        id = row[Users.id],
        token = row[Users.token],
        displayName = row[Users.displayName],
        telegramId = row[Users.telegramId],
        monthlyBudget = row[Users.monthlyBudget]?.toDouble(),
    )

    /** Найти пользователя по его токену (для проверки авторизации). */
    fun findByToken(token: String): User? = transaction {
        Users.selectAll().where { Users.token eq token }.firstOrNull()?.let(::rowToUser)
    }

    /** Найти пользователя по id. */
    fun findById(id: UUID): User? = transaction {
        Users.selectAll().where { Users.id eq id }.firstOrNull()?.let(::rowToUser)
    }

    /** Создать нового пользователя (регистрация). Токен генерируется случайным. */
    fun create(displayName: String): User = transaction {
        val id = UUID.randomUUID()
        val token = UUID.randomUUID().toString()
        val name = displayName.ifBlank { "Пользователь" }.take(100)
        Users.insert {
            it[Users.id] = id
            it[Users.token] = token
            it[Users.displayName] = name
            it[Users.createdAt] = LocalDateTime.now()
        }
        User(id, token, name, null, null)
    }

    /** Найти пользователя по Telegram-id или завести нового (для бота). */
    fun findOrCreateByTelegram(telegramId: Long, displayName: String): User = transaction {
        val existing = Users.selectAll().where { Users.telegramId eq telegramId }.firstOrNull()
        if (existing != null) {
            rowToUser(existing)
        } else {
            val id = UUID.randomUUID()
            val token = UUID.randomUUID().toString()
            val name = displayName.ifBlank { "Пользователь" }.take(100)
            Users.insert {
                it[Users.id] = id
                it[Users.token] = token
                it[Users.displayName] = name
                it[Users.telegramId] = telegramId
                it[Users.createdAt] = LocalDateTime.now()
            }
            User(id, token, name, telegramId, null)
        }
    }

    /** Месячный бюджет пользователя (null — не задан). */
    fun getBudget(userId: UUID): Double? = transaction {
        Users.selectAll().where { Users.id eq userId }
            .firstOrNull()?.get(Users.monthlyBudget)?.toDouble()
    }

    /** Задать (>0) или сбросить (null/≤0) бюджет пользователя. */
    fun setBudget(userId: UUID, value: Double?) = transaction {
        Users.update({ Users.id eq userId }) {
            it[monthlyBudget] = if (value != null && value > 0) value.toBigDecimal() else null
        }
    }

    /**
     * Привязать Telegram к целевому аккаунту (toUserId): переносим траты со старого
     * (авто-созданного бот-)аккаунта fromUserId на целевой, удаляем старый (это освобождает
     * уникальный telegram_id), и ставим telegram_id целевому. Так бот начинает работать
     * с тем же аккаунтом, что приложение/веб.
     */
    fun linkTelegram(fromUserId: UUID, toUserId: UUID, telegramId: Long) = transaction {
        Expenses.update({ Expenses.userId eq fromUserId }) {
            it[Expenses.userId] = toUserId
        }
        Users.deleteWhere { Users.id eq fromUserId }
        Users.update({ Users.id eq toUserId }) {
            it[Users.telegramId] = telegramId
        }
    }

    /**
     * Бутстрап "владельца" и миграция старых данных (одноразово при старте).
     * Если задан ownerToken (переменная API_TOKEN) — создаём/находим пользователя с этим токеном,
     * привязываем к нему все бесхозные (user_id IS NULL) траты и переносим глобальный бюджет.
     * Так после перехода на аккаунты твои старые данные остаются доступны по прежнему токену.
     */
    fun bootstrapOwnerAndMigrate(ownerToken: String?) = transaction {
        if (ownerToken.isNullOrEmpty()) return@transaction
        val owner = Users.selectAll().where { Users.token eq ownerToken }.firstOrNull()?.let(::rowToUser)
            ?: run {
                val id = UUID.randomUUID()
                Users.insert {
                    it[Users.id] = id
                    it[Users.token] = ownerToken
                    it[Users.displayName] = "Владелец"
                    it[Users.createdAt] = LocalDateTime.now()
                }
                User(id, ownerToken, "Владелец", null, null)
            }
        // Привязать все траты без владельца к нему.
        Expenses.update({ Expenses.userId.isNull() }) {
            it[Expenses.userId] = owner.id
        }
        // Перенести старый глобальный бюджет (из settings) в бюджет владельца.
        val globalBudget = Settings.selectAll().where { Settings.key eq "monthly_budget" }
            .firstOrNull()?.get(Settings.value)?.toDoubleOrNull()
        if (globalBudget != null && owner.monthlyBudget == null) {
            Users.update({ Users.id eq owner.id }) { it[monthlyBudget] = globalBudget.toBigDecimal() }
            Settings.deleteWhere { Settings.key eq "monthly_budget" }
        }
    }
}
