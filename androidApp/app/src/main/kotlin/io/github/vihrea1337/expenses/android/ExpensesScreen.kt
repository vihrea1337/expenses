package io.github.vihrea1337.expenses.android

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.vihrea1337.expenses.android.data.Expense
import java.time.LocalDate
import kotlin.math.roundToInt

private val PERIODS = listOf("Сегодня", "Неделя", "Месяц", "Всё")

/**
 * Главный экран: форма добавления, переключатель периода, разбивка по категориям и список трат.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExpensesScreen(viewModel: ExpensesViewModel = viewModel()) {
    // Подписываемся на состояние из ViewModel: как только оно меняется — экран перерисуется.
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Поля ввода и выбранный период живут в экране. rememberSaveable — переживают поворот.
    var category by rememberSaveable { mutableStateOf("") }
    var amount by rememberSaveable { mutableStateOf("") }
    var periodIndex by rememberSaveable { mutableIntStateOf(2) } // по умолчанию "Месяц"

    // Траты за выбранный период; итог и разбивка считаются из них.
    val filtered = state.expenses.filter { inPeriod(it.createdAt, periodIndex) }
    val total = filtered.sumOf { it.amount }
    val breakdown = filtered
        .groupBy { it.category }
        .map { (cat, list) -> cat to list.sumOf { it.amount } }
        .sortedByDescending { it.second }
        .take(8)

    Scaffold(
        topBar = { TopAppBar(title = { Text("Мои траты") }) },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .padding(16.dp)
                .fillMaxSize(),
        ) {
            // --- Форма добавления ---
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = category,
                    onValueChange = { category = it },
                    label = { Text("Категория") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = it },
                    label = { Text("Сумма") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.width(120.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    viewModel.addExpense(category, amount) {
                        category = ""
                        amount = ""
                    }
                },
                enabled = !state.isLoading,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Добавить трату")
            }

            val error = state.error
            if (error != null) {
                Spacer(Modifier.height(8.dp))
                Text(error, color = MaterialTheme.colorScheme.error)
            }

            // --- Переключатель периода ---
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PERIODS.forEachIndexed { index, label ->
                    FilterChip(
                        selected = periodIndex == index,
                        onClick = { periodIndex = index },
                        label = { Text(label) },
                    )
                }
                if (state.isLoading) {
                    Spacer(Modifier.width(4.dp))
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                }
            }

            // --- Итог + список с "потяни вниз, чтобы обновить" ---
            Spacer(Modifier.height(12.dp))
            PullToRefreshBox(
                isRefreshing = state.isLoading,
                onRefresh = { viewModel.refresh() },
                modifier = Modifier.fillMaxSize(),
            ) {
                LazyColumn(Modifier.fillMaxSize()) {
                    item {
                        Text(
                            "Всего за период: ${formatAmount(total)} ₽ · ${filtered.size} трат",
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                    if (breakdown.isNotEmpty()) {
                        item { BreakdownSection(rows = breakdown, total = total) }
                    }
                    item {
                        Spacer(Modifier.height(8.dp))
                        HorizontalDivider()
                    }
                    if (filtered.isEmpty() && !state.isLoading) {
                        item {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(top = 32.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text("За этот период трат нет.")
                            }
                        }
                    } else {
                        items(filtered) { expense ->
                            ExpenseRow(
                                expense = expense,
                                onDelete = { viewModel.deleteExpense(expense.id) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Разбивка по категориям: строка "категория — сумма · доля%" с полоской. */
@Composable
private fun BreakdownSection(rows: List<Pair<String, Double>>, total: Double) {
    Column(Modifier.padding(vertical = 8.dp)) {
        Text("По категориям", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(4.dp))
        rows.forEach { (cat, sum) ->
            val fraction = if (total > 0) (sum / total).toFloat() else 0f
            val percent = (fraction * 100).roundToInt()
            Column(Modifier.padding(vertical = 6.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(cat, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "${formatAmount(sum)} ₽ · $percent%",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(4.dp))
                CategoryBar(fraction)
            }
        }
    }
}

/** Тонкая полоска-индикатор доли категории. */
@Composable
private fun CategoryBar(fraction: Float) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(MaterialTheme.colorScheme.primary),
        )
    }
}

/** Одна строка списка: слева категория и дата, справа сумма и кнопка удаления. */
@Composable
private fun ExpenseRow(expense: Expense, onDelete: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(expense.category, style = MaterialTheme.typography.bodyLarge)
            Text(
                expense.createdAt.take(16).replace('T', ' '),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            "${formatAmount(expense.amount)} ₽",
            style = MaterialTheme.typography.titleMedium,
        )
        TextButton(onClick = onDelete) { Text("✕") }
    }
    HorizontalDivider()
}

/** Попадает ли трата в выбранный период. Неделя = последние 7 дней, месяц = последние 30 дней. */
private fun inPeriod(createdAt: String, periodIndex: Int): Boolean {
    if (periodIndex == 3) return true // "Всё"
    val date = runCatching { LocalDate.parse(createdAt.take(10)) }.getOrNull() ?: return true
    val today = LocalDate.now()
    return when (periodIndex) {
        0 -> date == today
        1 -> !date.isBefore(today.minusDays(6))
        2 -> !date.isBefore(today.minusDays(29))
        else -> true
    }
}

/** 200.0 -> "200", 149.5 -> "149.5" (убираем лишний ".0" у целых сумм). */
private fun formatAmount(value: Double): String =
    if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()
