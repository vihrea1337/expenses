package io.github.vihrea1337.expenses
import kotlinx.serialization.Serializable

@Serializable
data class NewExpense(val amount: Double,
                      val category: String,
                      // note необязательное: если клиент не прислал это поле в JSON,
                      // подставляется null. Без "= null" сервер считал бы поле обязательным
                      // и отвечал 400, когда приложение отправляет трату без заметки.
                      val note: String? = null)