package io.github.vihrea1337.expenses
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.datetime

object Expenses : Table("expenses") {
    val id = uuid("id")
    val amount = decimal("amount", 12, 2)
    val category = varchar("category", 50)
    val createdAt = datetime("created_at")
    // Момент последнего изменения строки (создание/правка/мягкое удаление) — основа
    // синхронизации офлайн-клиентов (GET /api/expenses/changes?since=). NOT NULL: на старых
    // базах колонка появляется миграцией в configureDatabase() с бэкфиллом = created_at,
    // так что к моменту, когда код её реально читает, значение уже гарантированно есть.
    val updatedAt = datetime("updated_at")
    val note = varchar("note", 255).nullable()
    // Обобщённая категория ("еда", "транспорт", ...), которую проставляет ИИ в фоне.
    // nullable: сразу после создания пусто, заполняется чуть позже (или остаётся пустым, если ИИ недоступен).
    val categoryGroup = varchar("category_group", 50).nullable()
    // Чья это трата. nullable ради миграции старой таблицы; у новых трат всегда заполнено.
    val userId = uuid("user_id").nullable()
    // Мягкое удаление ("надгробие"): строку не стираем физически, иначе офлайн-клиент,
    // не видевший удаления, "воскресит" трату при следующей синхронизации. NULL — не удалена.
    val deletedAt = datetime("deleted_at").nullable()
    override val primaryKey = PrimaryKey(id)
}