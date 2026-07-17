package io.github.vihrea1337.expenses
import kotlinx.serialization.Serializable

@Serializable
data class NewExpense(val amount: Double,
                      val category: String,
                        val note: String?)