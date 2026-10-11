package io.github.vihrea1337.expenses.android.ui.analytics

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.vihrea1337.expenses.android.ui.common.BudgetBar
import io.github.vihrea1337.expenses.android.ui.common.BudgetStatus
import io.github.vihrea1337.expenses.android.ui.common.CategoryBadge
import io.github.vihrea1337.expenses.android.ui.common.CategoryShare
import io.github.vihrea1337.expenses.android.ui.common.PeriodChips
import io.github.vihrea1337.expenses.android.ui.common.TrendBar
import io.github.vihrea1337.expenses.android.ui.common.categoryColor
import io.github.vihrea1337.expenses.android.ui.common.formatAmount
import io.github.vihrea1337.expenses.android.ui.common.money
import io.github.vihrea1337.expenses.android.ui.common.monthName
import io.github.vihrea1337.expenses.android.ui.common.plural
import kotlin.math.roundToInt

/** Экран «Аналитика»: итоги периода, кольцо по категориям, график по времени, бюджет. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnalyticsScreen(viewModel: AnalyticsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var editBudget by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.messages.collect { snackbar.showSnackbar(it) } }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Аналитика") }) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PeriodChips(state.period, viewModel::setPeriod)

            // Три плитки: сумма, число трат, в среднем в день.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Tile(money(state.total), "потрачено", Modifier.weight(1f))
                Tile(state.count.toString(), plural(state.count, "трата", "траты", "трат"), Modifier.weight(0.7f))
                Tile(money(state.perDay), "в день", Modifier.weight(1f))
            }

            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("По категориям", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(12.dp))
                    if (state.byCategory.isEmpty()) {
                        Text("Нет трат за период", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        Donut(state.byCategory, state.total)
                        Spacer(Modifier.height(16.dp))
                        state.byCategory.forEach { LegendRow(it, state.total) }
                    }
                }
            }

            if (state.trend.isNotEmpty()) {
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) { TrendChart(state.trend) }
                }
            }

            BudgetCard(
                month = monthName(state.today),
                status = state.budgetStatus,
                spent = state.monthSpent,
                onEdit = { editBudget = true },
            )
        }
    }

    if (editBudget) {
        BudgetDialog(
            current = state.budget,
            onDismiss = { editBudget = false },
            onSave = {
                viewModel.setBudget(it)
                editBudget = false
            },
        )
    }
}

@Composable
private fun Tile(value: String, label: String, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(12.dp),
    ) {
        Text(value, style = MaterialTheme.typography.titleMedium, maxLines = 1)
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * Кольцевая диаграмма, нарисованная на Canvas вручную (без библиотек графиков): каждый
 * сектор — дуга drawArc длиной в долю категории. При появлении кольцо «дорисовывается»
 * по кругу (Animatable от 0 до 1 за 0,7 с) — заодно видно, что данные обновились.
 */
@Composable
private fun Donut(rows: List<CategoryShare>, total: Double) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(rows) {
        progress.snapTo(0f)
        progress.animateTo(1f, tween(durationMillis = 700))
    }
    // Цвета считаем заранее: внутри Canvas (это не composable) categoryColor(group) не вызвать.
    val colors = rows.map { categoryColor(it.group) }
    val summary = rows.joinToString { "${it.group} ${formatAmount(it.amount)} рублей" }

    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Canvas(
            Modifier
                .size(190.dp)
                // Для TalkBack (экранного диктора): что нарисовано, словами.
                .semantics { contentDescription = "Траты по категориям: $summary" },
        ) {
            val strokeWidth = 30.dp.toPx()
            val diameter = size.minDimension - strokeWidth
            val topLeft = Offset((size.width - diameter) / 2f, (size.height - diameter) / 2f)
            val gap = if (rows.size > 1) 1.5f else 0f // тонкий зазор между секторами
            var start = -90f // начинаем сверху
            rows.forEachIndexed { i, row ->
                val sweep = if (total > 0) (row.amount / total * 360.0).toFloat() * progress.value else 0f
                drawArc(
                    color = colors[i],
                    startAngle = start,
                    sweepAngle = (sweep - gap).coerceAtLeast(0f),
                    useCenter = false,
                    topLeft = topLeft,
                    size = Size(diameter, diameter),
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Butt),
                )
                start += sweep
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(money(total), style = MaterialTheme.typography.titleLarge)
            Text("всего", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun LegendRow(row: CategoryShare, total: Double) {
    val percent = if (total > 0) (row.amount / total * 100).roundToInt() else 0
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CategoryBadge(row.group, size = 32.dp)
        Spacer(Modifier.width(12.dp))
        Text(row.group.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text("$percent%", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(12.dp))
        Text(money(row.amount), style = MaterialTheme.typography.titleSmall)
    }
}

/**
 * График по времени: столбики (одна серия — сумма за день или месяц), привязаны к нижней
 * линии. Высота — доля от самого «дорогого» интервала. Подписи — не под каждым столбиком
 * (иначе сольются), а примерно восемь штук.
 */
@Composable
private fun TrendChart(bars: List<TrendBar>) {
    val accent = MaterialTheme.colorScheme.primary
    val maxAmount = bars.maxOf { it.amount }.coerceAtLeast(1.0)
    val peak = bars.maxByOrNull { it.amount }
    val step = ((bars.size + 7) / 8).coerceAtLeast(1)

    Text("По времени", style = MaterialTheme.typography.titleMedium)
    if (peak != null && peak.amount > 0) {
        Text(
            "Пик: ${money(peak.amount)} (${peak.label})",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Spacer(Modifier.height(12.dp))
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
                    // Пустой день — тонкая черта, а не пустота: видно, что день был.
                    .fillMaxHeight(fraction.coerceAtLeast(0.02f))
                    .clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
                    .background(if (bar.amount > 0) accent else MaterialTheme.colorScheme.surfaceVariant),
            )
        }
    }
    Spacer(Modifier.height(4.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
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

@Composable
private fun BudgetCard(month: String, status: BudgetStatus?, spent: Double, onEdit: () -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Бюджет на $month", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = onEdit) { Text(if (status == null) "Задать" else "Изменить") }
            }
            if (status == null) {
                Text(
                    "Не задан. Лимит на месяц видно в приложении, на виджете и на сайте.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text("Потрачено ${money(spent)} из ${money(status.budget)}")
                BudgetBar(status)
                Text(
                    if (status.over) "Перерасход: ${money(-status.left)}" else "Осталось: ${money(status.left)}",
                    color = if (status.over) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Диалог бюджета: «Сохранить» задаёт лимит, «Убрать» — сбрасывает. */
@Composable
private fun BudgetDialog(current: Double?, onDismiss: () -> Unit, onSave: (Double?) -> Unit) {
    var text by rememberSaveable { mutableStateOf(current?.let { formatAmount(it).replace(" ", "") } ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Бюджет на месяц") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("Сумма, ₽") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            )
        },
        confirmButton = {
            TextButton(onClick = {
                val value = text.replace(',', '.').replace(" ", "").toDoubleOrNull()
                onSave(value?.takeIf { it > 0 })
            }) { Text("Сохранить") }
        },
        dismissButton = {
            Row {
                if (current != null) TextButton(onClick = { onSave(null) }) { Text("Убрать") }
                TextButton(onClick = onDismiss) { Text("Отмена") }
            }
        },
    )
}
