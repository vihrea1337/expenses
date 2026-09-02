package io.github.vihrea1337.expenses.android.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/** Класс базы, который Room генерирует по аннотации. Наружу отдаём его через object ниже. */
@Database(entities = [ExpenseEntity::class], version = 2, exportSchema = false)
abstract class RoomExpensesDatabase : RoomDatabase() {
    abstract fun expenseDao(): ExpenseDao
}

/**
 * Доступ к базе — как [io.github.vihrea1337.expenses.android.TokenStore]: один объект на
 * процесс, инициализируется контекстом при старте приложения ([init] вызывает MainActivity).
 * Полноценного DI (Hilt и т.п.) в проекте нет и пока не нужно — велосипед был бы неоправдан
 * ради одной таблицы.
 */
object ExpensesDatabase {
    @Volatile private var db: RoomExpensesDatabase? = null

    fun init(context: Context) {
        if (db != null) return
        db = Room.databaseBuilder(
            context.applicationContext,
            RoomExpensesDatabase::class.java,
            "expenses.db",
        )
            // version 2 добавил dirty/pendingDelete/updatedAt (2026-09-02). Настоящая миграция
            // избыточна: это чистый кэш сервера, а приложение ещё ни разу не ставилось на
            // реальный телефон — терять внутри него нечего, при следующем запуске всё
            // перекачается заново через /api/expenses/changes. Если это когда-нибудь
            // изменится (на устройствах появятся настоящие несинхронизированные данные),
            // destructive-миграцию нужно будет заменить на настоящую (Migration(1, 2) {...}).
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()
    }

    val dao: ExpenseDao
        get() = db?.expenseDao()
            ?: error("ExpensesDatabase.init(context) не был вызван — см. MainActivity.onCreate")

    /**
     * Стереть весь локальный кэш — вызывается при выходе из аккаунта. Без этого траты
     * предыдущего пользователя (и его неотправленные офлайн-правки/удаления, если такие были)
     * остались бы на диске и могли бы утечь следующему аккаунту, который войдёт на этом же
     * телефоне. Если к моменту выхода были неотправленные изменения — они теряются: это
     * сознательный компромисс безопасности (не показывать чужие данные важнее, чем сохранить
     * правки при выходе без синхронизации).
     */
    suspend fun clearAll() {
        db?.clearAllTables()
    }
}
