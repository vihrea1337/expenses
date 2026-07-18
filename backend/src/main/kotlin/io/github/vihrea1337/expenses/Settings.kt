package io.github.vihrea1337.expenses

import org.jetbrains.exposed.sql.Table

/**
 * Простая таблица "ключ → значение" для настроек приложения.
 * Пока хранит одну настройку — месячный бюджет (ключ "monthly_budget").
 * Значение держим строкой, чтобы таблица годилась под любые будущие настройки.
 */
object Settings : Table("settings") {
    val key = varchar("key", 64)
    val value = varchar("value", 255)
    override val primaryKey = PrimaryKey(key)
}
