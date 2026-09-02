package io.github.vihrea1337.expenses

import java.time.Duration
import java.time.LocalDateTime
import kotlin.concurrent.thread

/**
 * Периодическая чистка старых "надгробий" (мягко удалённых трат, см. ExpenseRepository.delete).
 * Без неё deleted_at-строки копились бы в базе вечно — после того, как офлайн-клиенты успели
 * узнать об удалении через синхронизацию, хранить их дальше незачем.
 *
 * Работает так же, как Telegram-бот (startBot/TelegramBot.kt): отдельный демон-поток
 * (isDaemon = true — сам по себе не держит процесс живым, погаснет вместе с сервером), а не
 * корутина — вся работа здесь синхронный SQL через Exposed, без сетевых вызовов, так что
 * никакой suspend-логики не нужно.
 */
object TombstoneCleaner {

    /**
     * Сколько хранить надгробие после удаления, прежде чем стереть окончательно.
     *
     * 90 дней — с большим запасом покрывает даже сильно заброшенное устройство: если
     * офлайн-клиент не выходил на связь почти три месяца, у него к этому моменту устареет
     * не только курсор синхронизации, но и сам bearer-токен доверия (пользователь наверняка
     * переустановит/перелогинится) — то есть полноценная пересинхронизация с нуля в этом
     * сценарии и так предстоит, а значит бесконечно хранить надгробия ради него незачем.
     */
    val RETENTION: Duration = Duration.ofDays(90)

    /** Как часто проверять. Раз в сутки достаточно — это фоновая уборка, не что-то срочное. */
    private val CHECK_INTERVAL: Duration = Duration.ofHours(24)

    /** Запустить периодическую чистку в фоне. Первая проверка — сразу при старте, не через сутки. */
    fun start() {
        thread(isDaemon = true, name = "tombstone-cleaner") {
            while (true) {
                try {
                    val removed = purgeOld()
                    if (removed > 0) println("TombstoneCleaner: окончательно стёрто надгробий: $removed")
                } catch (e: Exception) {
                    println("TombstoneCleaner: ошибка очистки — ${e.message}")
                }
                Thread.sleep(CHECK_INTERVAL.toMillis())
            }
        }
    }

    /** Стереть надгробия старше [RETENTION]. Возвращает, сколько строк удалено физически. */
    fun purgeOld(now: LocalDateTime = LocalDateTime.now()): Int =
        ExpenseRepository.deleteTombstonesOlderThan(now.minus(RETENTION))

    /**
     * Подлежит ли надгробие с временем удаления [deletedAt] окончательному стиранию — чистая
     * функция, тестируется без базы. Реальный SQL-запрос
     * ([ExpenseRepository.deleteTombstonesOlderThan]) реализует ровно то же условие прямо в
     * WHERE (эффективнее, чем построчная фильтрация в Kotlin на большой таблице) — при правке
     * одного стоит перепроверить и второе.
     */
    fun isDueForPurge(deletedAt: LocalDateTime?, now: LocalDateTime, retention: Duration = RETENTION): Boolean {
        if (deletedAt == null) return false // не удалена — не надгробие, трогать нечего
        return deletedAt.isBefore(now.minus(retention))
    }
}
