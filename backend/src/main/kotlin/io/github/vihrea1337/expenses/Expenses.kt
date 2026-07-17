package io.github.vihrea1337.expenses
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.datetime

object Expenses : Table("expenses") {
    val id = uuid("id")
    val amount = decimal("amount", 12, 2)
    val category = varchar("category", 50)
    val createdAt = datetime("created_at")
    val note = varchar("note", 255).nullable()
    override val primaryKey = PrimaryKey(id)
}