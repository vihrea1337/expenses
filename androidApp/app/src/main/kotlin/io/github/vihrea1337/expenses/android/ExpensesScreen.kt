package io.github.vihrea1337.expenses.android

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
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
import androidx.compose.runtime.remember
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
    var showBudgetDialog by rememberSaveable { mutableStateOf(false) }
    // Трата, которую сейчас редактируем (null — диалог правки закрыт).
    var editing by remember { mutableStateOf<Expense?>(null) }

    // Расход за текущий календарный месяц — для сравнения с бюджетом.
    val monthSpent = state.expenses.filter { inCurrentMonth(it.createdAt) }.sumOf { it.amount }

    // Траты за выбранный период; итог и разбивка считаются из них.
    val filtered = state.expenses.filter { inPeriod(it.createdAt, periodIndex) }
    val total = filtered.sumOf { it.amount }
    // Группируем по обобщённой категории от ИИ (если ещё не проставлена — «без категории»).
    val breakdown = filtered
        .groupBy { it.categoryGroup ?: "без категории" }
        .map { (group, list) -> group to list.sumOf { it.amount } }
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

            // --- Бюджет на месяц ---
            Spacer(Modifier.height(12.dp))
            BudgetCard(
                budget = state.monthlyBudget,
                spent = monthSpent,
                onEdit = { showBudgetDialog = true },
            )
            if (showBudgetDialog) {
                BudgetDialog(
                    current = state.monthlyBudget,
                    onDismiss = { showBudgetDialog = false },
                    onSave = { value ->
                        viewModel.setBudget(value)
                        showBudgetDialog = false
                    },
                )
            }
            val editingExpense = editing
            if (editingExpense != null) {
                EditExpenseDialog(
                    expense = editingExpense,
                    onDismiss = { editing = null },
                    onSave = { cat, amt, group ->
                        viewModel.editExpense(editingExpense.id, cat, amt, group) { editing = null }
                    },
                )
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
                                onEdit = { editing = expense },
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

/** Карточка бюджета: лимит, потрачено за месяц, полоска и остаток/перерасход. */
@Composable
private fun BudgetCard(budget: Double?, spent: Double, onEdit: () -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Бюджет на месяц",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onEdit) {
                    Text(if (budget == null) "Задать" else "Изменить")
                }
            }
            if (budget == null) {
                Text(
                    "Не задан. Нажми «Задать», чтобы установить лимит.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                val left = budget - spent
                val over = left < 0
                Text("Потрачено ${formatAmount(spent)} из ${formatAmount(budget)} ₽")
                Spacer(Modifier.height(6.dp))
                BudgetBar(fraction = if (budget > 0) (spent / budget).toFloat() else 0f, over = over)
                Spacer(Modifier.height(6.dp))
                Text(
                    if (over) "Перерасход: ${formatAmount(-left)} ₽" else "Осталось: ${formatAmount(left)} ₽",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (over) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Полоска прогресса бюджета: зелёная в пределах лимита, красная при перерасходе. */
@Composable
private fun BudgetBar(fraction: Float, over: Boolean) {
    val fill = if (over) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Box(
        Modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(fill),
        )
    }
}

/** Диалог ввода бюджета: «Сохранить» задаёт лимит, «Убрать» — сбрасывает. */
@Composable
private fun BudgetDialog(current: Double?, onDismiss: () -> Unit, onSave: (Double?) -> Unit) {
    var text by rememberSaveable { mutableStateOf(current?.let { formatAmount(it) } ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Бюджет на месяц") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("Сумма в ₽") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        },
        confirmButton = {
            TextButton(onClick = {
                val value = text.replace(',', '.').toDoubleOrNull()
                onSave(if (value != null && value > 0) value else null)
            }) { Text("Сохранить") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { onSave(null) }) { Text("Убрать") }
                TextButton(onClick = onDismiss) { Text("Отмена") }
            }
        },
    )
}

/** Диалог правки траты: сумма, категория и категория ИИ («авто» = переопределить ИИ). */
@Composable
private fun EditExpenseDialog(
    expense: Expense,
    onDismiss: () -> Unit,
    onSave: (category: String, amount: String, group: String?) -> Unit,
) {
    // remember(expense.id) — поля сбрасываются, если открыли правку другой траты.
    var category by remember(expense.id) { mutableStateOf(expense.category) }
    var amount by remember(expense.id) { mutableStateOf(formatAmount(expense.amount)) }
    var group by remember(expense.id) { mutableStateOf(expense.categoryGroup ?: "авто") }
    var menuOpen by remember { mutableStateOf(false) }
    val groups = listOf(
        "авто", "еда", "транспорт", "дом", "развлечения",
        "здоровье", "одежда", "связь", "подарки", "прочее",
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Изменить трату") },
        text = {
            Column {
                OutlinedTextField(
                    value = category,
                    onValueChange = { category = it },
                    label = { Text("Категория") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = it },
                    label = { Text("Сумма") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Box {
                    TextButton(onClick = { menuOpen = true }) { Text("Категория ИИ: $group ▾") }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        groups.forEach { g ->
                            DropdownMenuItem(text = { Text(g) }, onClick = { group = g; menuOpen = false })
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(category, amount, if (group == "авто") null else group) }) {
                Text("Сохранить")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

/** Одна строка списка: тап по строке — редактировать, крестик — удалить. */
@Composable
private fun ExpenseRow(expense: Expense, onEdit: () -> Unit, onDelete: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onEdit() }
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(expense.category, style = MaterialTheme.typography.bodyLarge)
            // Дата, а рядом обобщённая категория от ИИ (если уже проставлена).
            val subtitle = expense.createdAt.take(16).replace('T', ' ') +
                (expense.categoryGroup?.let { " · $it" } ?: "")
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            "${formatAmount(expense.amount)} ₽",
            style = MaterialTheme.typography.titleMedium,
        )
        // Видимая подсказка, что трату можно редактировать (то же делает тап по строке).
        TextButton(onClick = onEdit) { Text("✎") }
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

/** Относится ли трата к текущему календарному месяцу (для бюджета). */
private fun inCurrentMonth(createdAt: String): Boolean {
    val date = runCatching { LocalDate.parse(createdAt.take(10)) }.getOrNull() ?: return false
    val now = LocalDate.now()
    return date.year == now.year && date.monthValue == now.monthValue
}

/** 200.0 -> "200", 149.5 -> "149.5" (убираем лишний ".0" у целых сумм). */
private fun formatAmount(value: Double): String =
    if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()
