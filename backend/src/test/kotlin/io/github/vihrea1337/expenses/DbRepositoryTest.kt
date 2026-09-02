package io.github.vihrea1337.expenses

import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.LocalDateTime
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Интеграционные тесты репозиториев на встроенной базе H2 «в памяти» (настоящий SQL,
 * но без Postgres/Docker). Главное, что проверяем — изоляция между пользователями:
 * один аккаунт не видит и не может трогать данные другого.
 */
class DbRepositoryTest {

    companion object {
        // Подключаемся один раз; DB_CLOSE_DELAY=-1 держит базу живой между обращениями.
        private val db by lazy {
            Database.connect("jdbc:h2:mem:expenses_test;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
        }
    }

    @BeforeTest
    fun setup() {
        db // гарантируем подключение
        transaction {
            SchemaUtils.create(Expenses, Settings, Users) // CREATE TABLE IF NOT EXISTS
            // Чистим таблицы перед каждым тестом — тесты не влияют друг на друга.
            Expenses.deleteAll()
            Settings.deleteAll()
            Users.deleteAll()
        }
    }

    @Test
    fun `создание пользователя и поиск по токену`() {
        val user = UserRepository.create("Аня")
        assertEquals("Аня", user.displayName)
        assertEquals(user.id, UserRepository.findByToken(user.token)?.id)
        assertNull(UserRepository.findByToken("несуществующий-токен"))
    }

    @Test
    fun `траты изолированы между пользователями`() {
        val a = UserRepository.create("A")
        val b = UserRepository.create("B")
        ExpenseRepository.add(a.id, NewExpense(amount = 100.0, category = "кофе"))
        assertEquals(1, ExpenseRepository.all(a.id).size)
        assertEquals(0, ExpenseRepository.all(b.id).size, "B не должен видеть траты A")
    }

    @Test
    fun `чужую трату удалить нельзя, свою — можно`() {
        val a = UserRepository.create("A")
        val b = UserRepository.create("B")
        val e = ExpenseRepository.add(a.id, NewExpense(amount = 100.0, category = "кофе"))
        val eid = UUID.fromString(e.id)

        assertFalse(ExpenseRepository.delete(b.id, eid), "B не должен удалить трату A")
        assertEquals(1, ExpenseRepository.all(a.id).size)
        assertTrue(ExpenseRepository.delete(a.id, eid), "A должен удалить свою трату")
        assertEquals(0, ExpenseRepository.all(a.id).size)
    }

    @Test
    fun `правка меняет поля своей траты и не трогает чужую`() {
        val a = UserRepository.create("A")
        val b = UserRepository.create("B")
        val e = ExpenseRepository.add(a.id, NewExpense(amount = 100.0, category = "кофе", note = "зерно"))
        val eid = UUID.fromString(e.id)

        val upd = ExpenseRepository.updateExpense(a.id, eid, 150.0, "чай", "утро", "напитки", "поездка")
        assertNotNull(upd)
        assertEquals(150.0, upd.amount)
        assertEquals("чай", upd.category)
        assertEquals("утро", upd.note)
        assertEquals("напитки", upd.categoryGroup)
        assertEquals("поездка", upd.tag)

        assertNull(
            ExpenseRepository.updateExpense(b.id, eid, 1.0, "x", null, null, null),
            "B не должен отредактировать трату A",
        )
    }

    @Test
    fun `бюджет у каждого пользователя свой`() {
        val a = UserRepository.create("A")
        val b = UserRepository.create("B")
        assertNull(UserRepository.getBudget(a.id))

        UserRepository.setBudget(a.id, 30000.0)
        assertEquals(30000.0, UserRepository.getBudget(a.id))
        assertNull(UserRepository.getBudget(b.id), "бюджет A не должен влиять на B")

        UserRepository.setBudget(a.id, null) // сброс
        assertNull(UserRepository.getBudget(a.id))
    }

    @Test
    fun `findOrCreateByTelegram второй раз находит тот же аккаунт`() {
        val first = UserRepository.findOrCreateByTelegram(555L, "Петя")
        val second = UserRepository.findOrCreateByTelegram(555L, "Петя-другое-имя")
        assertEquals(first.id, second.id, "по одному telegram_id — один аккаунт")
        assertEquals(555L, first.telegramId)
    }

    @Test
    fun `expensesWithoutGroup — только без категории и только свои`() {
        val a = UserRepository.create("A")
        val b = UserRepository.create("B")
        ExpenseRepository.add(a.id, NewExpense(amount = 100.0, category = "кофе")) // без группы
        val withGroup = ExpenseRepository.add(a.id, NewExpense(amount = 50.0, category = "такси"))
        ExpenseRepository.updateGroup(UUID.fromString(withGroup.id), "транспорт")
        ExpenseRepository.add(b.id, NewExpense(amount = 10.0, category = "хлеб")) // чужая, без группы

        val pending = ExpenseRepository.expensesWithoutGroup(a.id)
        assertEquals(1, pending.size)
        assertEquals("кофе", pending.first().second)
    }

    @Test
    fun `повторная отправка того же id не создаёт дубль`() {
        val a = UserRepository.create("A")
        val clientId = UUID.randomUUID().toString()

        val first = ExpenseRepository.add(a.id, NewExpense(amount = 100.0, category = "кофе", id = clientId))
        // Тот же id, но данные "изменились" в пути (например, клиент собрал запрос заново) —
        // важно, что от повтора выигрывает то, что уже сохранено, а не новые данные.
        val retry = ExpenseRepository.add(a.id, NewExpense(amount = 999.0, category = "другое", id = clientId))

        assertEquals(first.id, retry.id)
        assertEquals(first.amount, retry.amount, "повтор не должен перезаписать сумму")
        assertEquals(1, ExpenseRepository.all(a.id).size, "должна остаться одна запись, а не две")
    }

    @Test
    fun `некорректный id игнорируется — сервер сам назначает новый`() {
        val a = UserRepository.create("A")
        val saved = ExpenseRepository.add(a.id, NewExpense(amount = 100.0, category = "кофе", id = "не-uuid"))
        assertNotNull(UUID.fromString(saved.id), "сервер должен подставить свой валидный UUID")
    }

    @Test
    fun `мягкое удаление скрывает трату из списка, но не стирает её из базы`() {
        val a = UserRepository.create("A")
        val e = ExpenseRepository.add(a.id, NewExpense(amount = 100.0, category = "кофе", note = "зерно"))
        val eid = UUID.fromString(e.id)

        assertTrue(ExpenseRepository.delete(a.id, eid))
        assertEquals(0, ExpenseRepository.all(a.id).size, "удалённая трата не должна быть в обычном списке")

        val tombstone = ExpenseRepository.changesSince(a.id, since = null, limit = 100).first { it.id == e.id }
        assertTrue(tombstone.deleted, "надгробие должно прийти с deleted = true")
        assertNull(tombstone.note, "заметка должна быть очищена при удалении")
    }

    @Test
    fun `повторное удаление уже удалённой траты возвращает false`() {
        val a = UserRepository.create("A")
        val e = ExpenseRepository.add(a.id, NewExpense(amount = 100.0, category = "кофе"))
        val eid = UUID.fromString(e.id)
        assertTrue(ExpenseRepository.delete(a.id, eid))
        assertFalse(ExpenseRepository.delete(a.id, eid), "нельзя удалить уже удалённую трату повторно")
    }

    @Test
    fun `нельзя отредактировать удалённую трату`() {
        val a = UserRepository.create("A")
        val e = ExpenseRepository.add(a.id, NewExpense(amount = 100.0, category = "кофе"))
        val eid = UUID.fromString(e.id)
        ExpenseRepository.delete(a.id, eid)
        assertNull(ExpenseRepository.updateExpense(a.id, eid, 1.0, "x", null, null, null))
    }

    @Test
    fun `expensesWithoutGroup не включает удалённые траты`() {
        val a = UserRepository.create("A")
        val e = ExpenseRepository.add(a.id, NewExpense(amount = 100.0, category = "кофе"))
        ExpenseRepository.delete(a.id, UUID.fromString(e.id))
        assertEquals(0, ExpenseRepository.expensesWithoutGroup(a.id).size)
    }

    /** Строки updatedAt сравниваем как настоящие моменты времени — как строки они ненадёжны
     *  (LocalDateTime.toString() не дополняет дробную часть секунд нулями одинаковой длины). */
    private fun String.asDateTime() = LocalDateTime.parse(this)

    @Test
    fun `updateExpense обновляет updatedAt`() {
        val a = UserRepository.create("A")
        val e = ExpenseRepository.add(a.id, NewExpense(amount = 100.0, category = "кофе"))
        Thread.sleep(5)
        val updated = ExpenseRepository.updateExpense(a.id, UUID.fromString(e.id), 200.0, "чай", null, null, null)
        assertNotNull(updated)
        assertTrue(
            updated.updatedAt.asDateTime().isAfter(e.updatedAt.asDateTime()),
            "updatedAt должен смениться при правке",
        )
    }

    @Test
    fun `changesSince без since отдаёт всю историю пользователя, и только его`() {
        val a = UserRepository.create("A")
        val b = UserRepository.create("B")
        ExpenseRepository.add(a.id, NewExpense(amount = 100.0, category = "кофе"))
        ExpenseRepository.add(a.id, NewExpense(amount = 50.0, category = "такси"))
        ExpenseRepository.add(b.id, NewExpense(amount = 10.0, category = "чужое"))

        assertEquals(2, ExpenseRepository.changesSince(a.id, since = null, limit = 100).size)
    }

    @Test
    fun `changesSince уважает limit`() {
        val a = UserRepository.create("A")
        repeat(3) { ExpenseRepository.add(a.id, NewExpense(amount = 1.0, category = "x")) }
        assertEquals(2, ExpenseRepository.changesSince(a.id, since = null, limit = 2).size)
    }

    @Test
    fun `changesSince отдаёт только изменённое после since, включая надгробия более старых трат`() {
        val a = UserRepository.create("A")
        val e1 = ExpenseRepository.add(a.id, NewExpense(amount = 100.0, category = "кофе"))
        Thread.sleep(5)
        val since = LocalDateTime.now() // курсор сразу после первой траты
        Thread.sleep(5)
        val e2 = ExpenseRepository.add(a.id, NewExpense(amount = 50.0, category = "такси"))
        // e1 создана ДО since, но мы её удаляем ПОСЛЕ — значит updated_at у неё новее since,
        // и надгробие обязано попасть в выдачу, иначе офлайн-клиент не узнает об удалении.
        ExpenseRepository.delete(a.id, UUID.fromString(e1.id))

        val changes = ExpenseRepository.changesSince(a.id, since, limit = 100)
        val byId = changes.associateBy { it.id }

        assertEquals(2, changes.size, "и новая трата, и надгробие старой — обе новее since")
        assertTrue(byId[e2.id]?.deleted == false)
        assertTrue(byId[e1.id]?.deleted == true, "надгробие удалённой (хоть и старой) траты должно попасть в изменения")
    }

    @Test
    fun `add сохраняет тег, all без фильтра отдаёт всё`() {
        val a = UserRepository.create("A")
        val e = ExpenseRepository.add(a.id, NewExpense(amount = 100.0, category = "кофе", tag = "поездка"))
        assertEquals("поездка", e.tag)
        assertEquals(1, ExpenseRepository.all(a.id).size)
    }

    @Test
    fun `all с tag фильтрует без учёта регистра и только свои`() {
        val a = UserRepository.create("A")
        val b = UserRepository.create("B")
        ExpenseRepository.add(a.id, NewExpense(amount = 100.0, category = "кофе", tag = "поездка"))
        ExpenseRepository.add(a.id, NewExpense(amount = 50.0, category = "такси", tag = "Поездка"))
        ExpenseRepository.add(a.id, NewExpense(amount = 30.0, category = "хлеб")) // без тега
        ExpenseRepository.add(b.id, NewExpense(amount = 10.0, category = "чужое", tag = "поездка"))

        val trip = ExpenseRepository.all(a.id, tag = "поездка")
        assertEquals(2, trip.size, "разный регистр тега («поездка»/«Поездка») — один и тот же тег")

        val tripUpper = ExpenseRepository.all(a.id, tag = "ПОЕЗДКА")
        assertEquals(2, tripUpper.size, "фильтр тоже не чувствителен к регистру запроса")

        assertEquals(3, ExpenseRepository.all(a.id).size, "без фильтра — все свои траты")
        assertEquals(0, ExpenseRepository.all(a.id, tag = "нет такого").size)
    }

    @Test
    fun `удалённая с тегом трата не попадает в фильтр по тегу`() {
        val a = UserRepository.create("A")
        val e = ExpenseRepository.add(a.id, NewExpense(amount = 100.0, category = "кофе", tag = "поездка"))
        ExpenseRepository.delete(a.id, UUID.fromString(e.id))
        assertEquals(0, ExpenseRepository.all(a.id, tag = "поездка").size)
    }

    @Test
    fun `deleteTombstonesOlderThan стирает только старые надгробия, не трогая свежие и живые траты`() {
        val a = UserRepository.create("A")
        val old = ExpenseRepository.add(a.id, NewExpense(amount = 1.0, category = "старое"))
        val fresh = ExpenseRepository.add(a.id, NewExpense(amount = 2.0, category = "свежее"))
        val alive = ExpenseRepository.add(a.id, NewExpense(amount = 3.0, category = "живое"))

        ExpenseRepository.delete(a.id, UUID.fromString(old.id))
        ExpenseRepository.delete(a.id, UUID.fromString(fresh.id))
        // "old" искусственно состарим напрямую в базе — delete() всегда ставит deleted_at = now().
        transaction {
            Expenses.update({ Expenses.id eq UUID.fromString(old.id) }) {
                it[deletedAt] = LocalDateTime.now().minusDays(100)
            }
        }

        val removed = ExpenseRepository.deleteTombstonesOlderThan(LocalDateTime.now().minusDays(90))
        assertEquals(1, removed, "стереть должны ровно одно надгробие — старое")

        val remainingIds = transaction { Expenses.selectAll().map { it[Expenses.id].toString() } }.toSet()
        assertTrue(fresh.id in remainingIds, "свежее надгробие (моложе порога) остаётся")
        assertTrue(alive.id in remainingIds, "неудалённая трата остаётся в любом случае")
        assertTrue(old.id !in remainingIds, "старое надгробие стёрто физически")
    }

    @Test
    fun `linkTelegram переносит траты и telegram_id на целевой аккаунт`() {
        val owner = UserRepository.create("Владелец") // аккаунт приложения/веба
        val bot = UserRepository.findOrCreateByTelegram(777L, "Бот-аккаунт")
        ExpenseRepository.add(bot.id, NewExpense(amount = 100.0, category = "кофе"))

        UserRepository.linkTelegram(fromUserId = bot.id, toUserId = owner.id, telegramId = 777L)

        assertEquals(1, ExpenseRepository.all(owner.id).size, "трата переехала к владельцу")
        assertNull(UserRepository.findById(bot.id), "старый бот-аккаунт удалён")
        assertEquals(777L, UserRepository.findById(owner.id)?.telegramId)
        assertEquals(
            owner.id,
            UserRepository.findOrCreateByTelegram(777L, "x").id,
            "теперь бот работает с аккаунтом владельца",
        )
    }

    @Test
    fun `bootstrapOwnerAndMigrate привязывает бесхозные траты к владельцу`() {
        // Старая трата без владельца (user_id IS NULL) — как до перехода на аккаунты.
        val now = LocalDateTime.now()
        transaction {
            Expenses.insert {
                it[id] = UUID.randomUUID()
                it[amount] = 100.toBigDecimal()
                it[category] = "старое"
                it[createdAt] = now
                it[updatedAt] = now
            }
        }
        UserRepository.bootstrapOwnerAndMigrate("owner-token-abc")

        val owner = UserRepository.findByToken("owner-token-abc")
        assertNotNull(owner)
        assertEquals("Владелец", owner.displayName)
        assertEquals(1, ExpenseRepository.all(owner.id).size, "бесхозная трата привязана к владельцу")
    }
}
