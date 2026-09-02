package io.github.vihrea1337.expenses.android

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.vihrea1337.expenses.Expense
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

private val PERIODS = listOf("Сегодня", "Неделя", "Месяц", "Всё")

/**
 * Главный экран. Две вкладки внизу: «Траты» (список) и «Аналитика» (кольцо + бюджет).
 * Добавление траты — по кнопке «+» (форма выезжает снизу). Период — общий для обеих вкладок.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExpensesScreen(viewModel: ExpensesViewModel = viewModel(), onLogout: () -> Unit = {}) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Обновляем данные при каждом входе на экран (в т.ч. после смены аккаунта — ViewModel
    // живёт на уровне Activity и переживает выход, поэтому нужен явный рефреш).
    LaunchedEffect(Unit) { viewModel.refresh() }

    var selectedTab by rememberSaveable { mutableIntStateOf(0) }       // 0 — Траты, 1 — Аналитика
    var periodIndex by rememberSaveable { mutableIntStateOf(2) }       // по умолчанию «Месяц»
    var showAddSheet by rememberSaveable { mutableStateOf(false) }     // открыта ли форма добавления
    var showBudgetDialog by rememberSaveable { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Expense?>(null) }         // редактируемая трата

    // Экспорт в CSV: системный диалог «создать документ» даёт uri выбранного файла,
    // в колбэке качаем траты и записываем байты в этот файл (через ContentResolver).
    val context = LocalContext.current
    val csvLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv"),
    ) { uri ->
        if (uri != null) {
            viewModel.exportCsv { bytes ->
                runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } }
            }
        }
    }

    // Производные данные под выбранный период.
    val filtered = state.expenses.filter { inPeriod(it.createdAt, periodIndex) }
    val total = filtered.sumOf { it.amount }
    val breakdown = filtered
        .groupBy { it.categoryGroup ?: "без категории" }
        .map { (group, list) -> group to list.sumOf { it.amount } }
        .sortedByDescending { it.second }
    val trend = buildTrend(filtered, periodIndex)
    val monthSpent = state.expenses.filter { inCurrentMonth(it.createdAt) }.sumOf { it.amount }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(if (selectedTab == 0) "Аналитика" else "Мои траты")
                        // Под заголовком — имя вошедшего аккаунта (сохранено при входе в TokenStore).
                        TokenStore.userName?.let { name ->
                            Text(
                                name,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                actions = { TextButton(onClick = onLogout) { Text("Выйти") } },
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Text("📊") },
                    label = { Text("Аналитика") },
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Text("📋") },
                    label = { Text("Траты") },
                )
            }
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddSheet = true }) {
                Text("+", style = MaterialTheme.typography.headlineMedium)
            }
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            // Общий переключатель периода (виден на обеих вкладках).
            PeriodSelector(
                periodIndex = periodIndex,
                isLoading = state.isLoading,
                onSelect = { periodIndex = it },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            val error = state.error
            if (error != null) {
                Text(
                    error,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                Spacer(Modifier.height(4.dp))
            }

            when (selectedTab) {
                0 -> AnalyticsTab(
                    breakdown = breakdown,
                    trend = trend,
                    total = total,
                    budget = state.monthlyBudget,
                    monthSpent = monthSpent,
                    onEditBudget = { showBudgetDialog = true },
                )
                else -> ListTab(
                    expenses = filtered,
                    total = total,
                    isLoading = state.isLoading,
                    onRefresh = { viewModel.refresh() },
                    onEdit = { editing = it },
                    onDelete = { viewModel.deleteExpense(it) },
                    onExport = { csvLauncher.launch("expenses.csv") },
                )
            }
        }

        // --- Диалоги и шторка ввода (рисуются поверх) ---
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
                onSave = { cat, amt, note, group ->
                    viewModel.editExpense(editingExpense.id, cat, amt, note, group) { editing = null }
                },
            )
        }
        if (showAddSheet) {
            ModalBottomSheet(onDismissRequest = { showAddSheet = false }) {
                AddExpenseSheet(
                    viewModel = viewModel,
                    error = state.error,
                    onDone = { showAddSheet = false },
                )
            }
        }
    }
}

/** Строка кнопок периода (Сегодня/Неделя/Месяц/Всё) + крутилка загрузки. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PeriodSelector(
    periodIndex: Int,
    isLoading: Boolean,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PERIODS.forEachIndexed { index, label ->
            FilterChip(
                selected = periodIndex == index,
                onClick = { onSelect(index) },
                label = { Text(label) },
            )
        }
        if (isLoading) {
            Spacer(Modifier.width(4.dp))
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        }
    }
}

/** Вкладка «Траты»: итог за период + список на весь экран (потяни вниз — обновить). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ListTab(
    expenses: List<Expense>,
    total: Double,
    isLoading: Boolean,
    onRefresh: () -> Unit,
    onEdit: (Expense) -> Unit,
    onDelete: (String) -> Unit,
    onExport: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Всего за период: ${formatAmount(total)} ₽ · ${expenses.size} трат",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onExport) { Text("⭳ CSV") }
        }
        Spacer(Modifier.height(8.dp))
        HorizontalDivider()
        PullToRefreshBox(
            isRefreshing = isLoading,
            onRefresh = onRefresh,
            modifier = Modifier.fillMaxSize(),
        ) {
            LazyColumn(Modifier.fillMaxSize()) {
                if (expenses.isEmpty() && !isLoading) {
                    item {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .padding(top = 32.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("За этот период трат нет. Добавь по кнопке «+».")
                        }
                    }
                } else {
                    items(expenses) { expense ->
                        ExpenseRow(
                            expense = expense,
                            onEdit = { onEdit(expense) },
                            onDelete = { onDelete(expense.id) },
                        )
                    }
                }
            }
        }
    }
}

/** Вкладка «Аналитика»: кольцо по категориям + график по времени + карточка бюджета. */
@Composable
private fun AnalyticsTab(
    breakdown: List<Pair<String, Double>>,
    trend: List<TrendBar>,
    total: Double,
    budget: Double?,
    monthSpent: Double,
    onEditBudget: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        if (breakdown.isEmpty()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("Нет данных за период.")
            }
        } else {
            BreakdownSection(rows = breakdown, total = total)
        }
        if (trend.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            TrendSection(bars = trend)
        }
        Spacer(Modifier.height(12.dp))
        BudgetCard(budget = budget, spent = monthSpent, onEdit = onEditBudget)
        Spacer(Modifier.height(16.dp))
    }
}

/** Форма добавления траты (в шторке снизу). После успешного добавления шторка закрывается. */
@Composable
private fun AddExpenseSheet(viewModel: ExpensesViewModel, error: String?, onDone: () -> Unit) {
    var category by rememberSaveable { mutableStateOf("") }
    var amount by rememberSaveable { mutableStateOf("") }
    var note by rememberSaveable { mutableStateOf("") }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .padding(bottom = 24.dp),
    ) {
        Text("Новая трата", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(12.dp))
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
        OutlinedTextField(
            value = note,
            onValueChange = { note = it },
            label = { Text("Заметка (необязательно)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        if (error != null) {
            Spacer(Modifier.height(8.dp))
            Text(error, color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = { viewModel.addExpense(category, amount, note) { onDone() } },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Добавить трату")
        }
    }
}

// Категориальная палитра (референс dataviz-гайда, провалидирована): 8 хуёв в фиксированном
// порядке, отдельно под светлую и тёмную поверхность. Цвет закреплён ЗА категорией (по сущности,
// не по величине). «прочее», «без категории» и всё вне списка — серый.
private val CATEGORY_ORDER = listOf(
    "еда", "транспорт", "дом", "развлечения", "здоровье", "одежда", "связь", "подарки",
)
private val CATEGORY_HUES_LIGHT = listOf(
    Color(0xFF2A78D6), Color(0xFF008300), Color(0xFFE87BA4), Color(0xFFEDA100),
    Color(0xFF1BAF7A), Color(0xFFEB6834), Color(0xFF4A3AA7), Color(0xFFE34948),
)
private val CATEGORY_HUES_DARK = listOf(
    Color(0xFF3987E5), Color(0xFF008300), Color(0xFFD55181), Color(0xFFC98500),
    Color(0xFF199E70), Color(0xFFD95926), Color(0xFF9085E9), Color(0xFFE66767),
)
private val CATEGORY_GRAY_LIGHT = Color(0xFF9AA0A6)
private val CATEGORY_GRAY_DARK = Color(0xFF6B7280)

/** Фиксированный цвет категории. */
private fun categoryColor(group: String, dark: Boolean): Color {
    val index = CATEGORY_ORDER.indexOf(group)
    return when {
        index >= 0 && dark -> CATEGORY_HUES_DARK[index]
        index >= 0 -> CATEGORY_HUES_LIGHT[index]
        dark -> CATEGORY_GRAY_DARK
        else -> CATEGORY_GRAY_LIGHT
    }
}

/** Разбивка по категориям: кольцевая (donut) диаграмма + легенда (сумма и доля). */
@Composable
private fun BreakdownSection(rows: List<Pair<String, Double>>, total: Double) {
    val dark = isSystemInDarkTheme()
    Column(Modifier.padding(vertical = 8.dp)) {
        Text("По категориям", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(12.dp))

        // Кольцо: каждый сектор — доля категории; в центре — общая сумма.
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(170.dp)) {
                val strokeWidth = 34.dp.toPx()
                val diameter = size.minDimension - strokeWidth
                val topLeft = Offset((size.width - diameter) / 2f, (size.height - diameter) / 2f)
                val arcSize = Size(diameter, diameter)
                var startAngle = -90f // начинаем сверху
                rows.forEach { (group, sum) ->
                    val sweep = if (total > 0) (sum / total * 360.0).toFloat() else 0f
                    drawArc(
                        color = categoryColor(group, dark),
                        startAngle = startAngle,
                        sweepAngle = sweep,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = Stroke(width = strokeWidth, cap = StrokeCap.Butt),
                    )
                    startAngle += sweep
                }
            }
            Text("${formatAmount(total)} ₽", style = MaterialTheme.typography.titleMedium)
        }

        Spacer(Modifier.height(12.dp))

        // Легенда: цветной маркер + название + сумма и доля (личность не только цветом).
        rows.forEach { (group, sum) ->
            val percent = if (total > 0) (sum / total * 100).roundToInt() else 0
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(12.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(categoryColor(group, dark)),
                )
                Spacer(Modifier.width(8.dp))
                Text(group, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Text(
                    "${formatAmount(sum)} ₽ · $percent%",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Один столбик графика «по времени»: подпись (день/месяц) и сумма за него. */
private data class TrendBar(val label: String, val amount: Double)

/**
 * График трат по времени: столбики (одна серия — сумма за интервал), привязаны к нижней линии.
 * Высота столбика — доля от максимального интервала. Легенда не нужна: заголовок называет серию.
 */
@Composable
private fun TrendSection(bars: List<TrendBar>) {
    if (bars.isEmpty()) return
    val accent = MaterialTheme.colorScheme.primary
    val maxAmount = bars.maxOf { it.amount }.coerceAtLeast(1.0)
    val peak = bars.maxByOrNull { it.amount }
    // Подписи оси показываем не для каждого столбика (иначе сольются), а через шаг ≈ 8 штук.
    val step = ((bars.size + 7) / 8).coerceAtLeast(1)

    Column(Modifier.padding(vertical = 8.dp)) {
        Text("По времени", style = MaterialTheme.typography.titleSmall)
        if (peak != null && peak.amount > 0) {
            Text(
                "Пик: ${formatAmount(peak.amount)} ₽ (${peak.label})",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(12.dp))
        // Ряд столбиков: высота = доля суммы от максимума, дно выровнено по нижней линии.
        Row(
            Modifier
                .fillMaxWidth()
                .height(120.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            bars.forEach { bar ->
                val fraction = (bar.amount / maxAmount).toFloat().coerceIn(0f, 1f)
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight(fraction)
                        .clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
                        .background(accent),
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        // Подписи под столбиками (каждая step-я).
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            bars.forEachIndexed { i, bar ->
                Text(
                    if (i % step == 0) bar.label else "",
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                )
            }
        }
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
            }) {
                Text("Сохранить")
            }
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
    onSave: (category: String, amount: String, note: String?, group: String?) -> Unit,
) {
    // remember(expense.id) — поля сбрасываются, если открыли правку другой траты.
    var category by remember(expense.id) { mutableStateOf(expense.category) }
    var amount by remember(expense.id) { mutableStateOf(formatAmount(expense.amount)) }
    var note by remember(expense.id) { mutableStateOf(expense.note ?: "") }
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
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("Заметка (необязательно)") },
                    singleLine = true,
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
            TextButton(onClick = { onSave(category, amount, note, if (group == "авто") null else group) }) {
                Text("Сохранить")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

/** Одна строка списка: тап по строке или ✎ — редактировать, ✕ — удалить. */
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
            // Дата, рядом обобщённая категория от ИИ (если проставлена) и заметка (если есть).
            val subtitle = expense.createdAt.take(16).replace('T', ' ') +
                (expense.categoryGroup?.let { " · $it" } ?: "") +
                (expense.note?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "")
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
        TextButton(onClick = onEdit) { Text("✎") }
        TextButton(onClick = onDelete) { Text("✕") }
    }
    HorizontalDivider()
}

/** Попадает ли трата в выбранный период. Неделя = последние 7 дней, месяц = последние 30 дней. */
private fun inPeriod(createdAt: String, periodIndex: Int): Boolean {
    if (periodIndex == 3) return true // «Всё»
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

// Формат подписи месяца на графике «Всё»: "07.25".
private val MONTH_FMT = DateTimeFormatter.ofPattern("MM.yy")

/**
 * Разбивка трат по времени для графика. Для «Недели»/«Месяца» — по дням (7 или 30 столбиков,
 * включая дни без трат = 0). Для «Всё» — по месяцам (иначе столбиков было бы слишком много).
 * Для «Сегодня» график не строим (один день — тренда нет) → пустой список.
 */
private fun buildTrend(expenses: List<Expense>, periodIndex: Int): List<TrendBar> {
    if (periodIndex == 0) return emptyList()
    val today = LocalDate.now()
    // Пара (дата, сумма) для каждой траты с распознанной датой.
    val dated = expenses.mapNotNull { e ->
        runCatching { LocalDate.parse(e.createdAt.take(10)) }.getOrNull()?.let { it to e.amount }
    }
    if (dated.isEmpty()) return emptyList()

    // «Всё» — по календарным месяцам, от первого месяца с тратами до текущего.
    if (periodIndex == 3) {
        val sums = dated.groupBy { YearMonth.from(it.first) }
            .mapValues { entry -> entry.value.sumOf { it.second } }
        val bars = mutableListOf<TrendBar>()
        var month = sums.keys.min()
        val last = YearMonth.from(today)
        while (!month.isAfter(last)) {
            bars += TrendBar(month.format(MONTH_FMT), sums[month] ?: 0.0)
            month = month.plusMonths(1)
        }
        return bars
    }

    // «Неделя»/«Месяц» — по дням: непрерывный ряд последних 7 или 30 дней.
    val days = if (periodIndex == 1) 7 else 30
    val start = today.minusDays((days - 1).toLong())
    val sums = dated.filter { !it.first.isBefore(start) }
        .groupBy { it.first }
        .mapValues { entry -> entry.value.sumOf { it.second } }
    return (0 until days).map { i ->
        val day = start.plusDays(i.toLong())
        TrendBar(day.dayOfMonth.toString(), sums[day] ?: 0.0)
    }
}

/** 200.0 -> "200", 149.5 -> "149.5" (убираем лишний ".0" у целых сумм). */
private fun formatAmount(value: Double): String =
    if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()
