package io.github.vihrea1337.expenses

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.datetime

/**
 * Пользователи. Аккаунт опознаётся по уникальному токену (его клиент шлёт в заголовке
 * Authorization). telegramId связывает аккаунт с пользователем Telegram (для бота).
 * monthlyBudget — месячный бюджет этого пользователя (теперь бюджет у каждого свой).
 */
object Users : Table("users") {
    val id = uuid("id")
    val token = varchar("token", 64).uniqueIndex()
    val displayName = varchar("display_name", 100)
    val telegramId = long("telegram_id").nullable().uniqueIndex()
    val monthlyBudget = decimal("monthly_budget", 12, 2).nullable()
    val createdAt = datetime("created_at")
    override val primaryKey = PrimaryKey(id)
}
