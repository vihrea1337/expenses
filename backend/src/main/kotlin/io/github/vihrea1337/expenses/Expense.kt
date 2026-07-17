package io.github.vihrea1337.expenses
import kotlinx.serialization.Serializable

@Serializable
data class Expense(val id: String,
                   val amount: Double,
                   val category: String,
                   val note: String?,
                   val createdAt: String)