package io.github.vihrea1337.expenses.android

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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.vihrea1337.expenses.android.data.Expense

/**
 * Главный (и единственный) экран: форма добавления траты, общая сумма и список трат.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExpensesScreen(viewModel: ExpensesViewModel = viewModel()) {
    // Подписываемся на состояние из ViewModel: как только оно меняется — экран перерисуется.
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Поля ввода живут в самом экране. rememberSaveable — переживают поворот экрана.
    var category by rememberSaveable { mutableStateOf("") }
    var amount by rememberSaveable { mutableStateOf("") }

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

            // Сообщение об ошибке, если есть.
            val error = state.error
            if (error != null) {
                Spacer(Modifier.height(8.dp))
                Text(error, color = MaterialTheme.colorScheme.error)
            }

            // --- Итог + кнопка обновления ---
            Spacer(Modifier.height(16.dp))
            val total = state.expenses.sumOf { it.amount }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Всего: ${formatAmount(total)} ₽",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        "${state.expenses.size} трат",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (state.isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                TextButton(onClick = { viewModel.refresh() }) { Text("Обновить") }
            }
            HorizontalDivider()

            // --- Список трат с "потяни вниз, чтобы обновить" ---
            PullToRefreshBox(
                isRefreshing = state.isLoading,
                onRefresh = { viewModel.refresh() },
                modifier = Modifier.fillMaxSize(),
            ) {
                LazyColumn(Modifier.fillMaxSize()) {
                    if (state.expenses.isEmpty() && !state.isLoading) {
                        item {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(top = 48.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text("Пока трат нет. Добавь первую!")
                            }
                        }
                    } else {
                        items(state.expenses) { expense ->
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
            // createdAt приходит как "2026-07-18T03:49:13.278" — берём первые 16 символов и
            // заменяем 'T' на пробел, получаем "2026-07-18 03:49".
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

/** 200.0 -> "200", 149.5 -> "149.5" (убираем лишний ".0" у целых сумм). */
private fun formatAmount(value: Double): String =
    if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()
