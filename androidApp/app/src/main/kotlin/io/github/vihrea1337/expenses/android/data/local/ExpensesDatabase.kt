package io.github.vihrea1337.expenses.android.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/** Класс базы, который Room генерирует по аннотации. Наружу отдаём его через object ниже. */
@Database(entities = [ExpenseEntity::class], version = 1, exportSchema = false)
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
        ).build()
    }

    val dao: ExpenseDao
        get() = db?.expenseDao()
            ?: error("ExpensesDatabase.init(context) не был вызван — см. MainActivity.onCreate")
}
