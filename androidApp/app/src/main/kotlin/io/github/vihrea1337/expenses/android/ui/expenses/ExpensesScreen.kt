package io.github.vihrea1337.expenses.android.ui.expenses

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.vihrea1337.expenses.Expense
import io.github.vihrea1337.expenses.android.ui.common.BudgetBar
import io.github.vihrea1337.expenses.android.ui.common.BudgetStatus
import io.github.vihrea1337.expenses.android.ui.common.CategoryBadge
import io.github.vihrea1337.expenses.android.ui.common.PeriodChips
import io.github.vihrea1337.expenses.android.ui.common.dayTitle
import io.github.vihrea1337.expenses.android.ui.common.money
import io.github.vihrea1337.expenses.android.ui.common.monthName
import io.github.vihrea1337.expenses.android.ui.common.plural
import io.github.vihrea1337.expenses.android.ui.common.timeOf
import java.time.LocalDate
import kotlinx.coroutines.launch

/**
 * Экран «Траты»: карточка месяца с бюджетом, период, поиск и список по дням.
 * Добавление — кнопка «Трата» (окно быстрого ввода), правка — нажатие на трату,
 * удаление — свайп влево с «Отменить» в снекбаре.
 *
 * [openAddSheet] — пришли с виджета или ярлыка «Добавить трату»: окно ввода открывается сразу.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExpensesScreen(
    openAddSheet: Boolean,
    onAddSheetShown: () -> Unit,
    viewModel: ExpensesViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var showAdd by rememberSaveable { mutableStateOf(false) }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Expense?>(null) }

    LaunchedEffect(openAddSheet) {
        if (openAddSheet) {
            showAdd = true
            onAddSheetShown()
        }
    }

    // Разовые события из ViewModel: сообщения и «Удалено · Отменить».
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is ExpensesEvent.Message -> scope.launch { snackbar.showSnackbar(event.text) }
                is ExpensesEvent.Deleted -> scope.launch {
                    val result = snackbar.showSnackbar(
                        message = "Удалено: ${event.label}",
                        actionLabel = "Отменить",
                        duration = SnackbarDuration.Short,
                    )
                    if (result == SnackbarResult.ActionPerformed) viewModel.undoDelete(event.id)
                    else viewModel.commitDelete(event.id)
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Траты") },
                actions = {
                    IconButton(onClick = {
                        searchOpen = !searchOpen
                        if (!searchOpen) viewModel.setQuery("")
                    }) {
                        Icon(if (searchOpen) Icons.Outlined.Close else Icons.Outlined.Search, contentDescription = "Поиск")
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showAdd = true },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("Трата") },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.isRefreshing,
            onRefresh = viewModel::refresh,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            LazyColumn(
                Modifier
                    .fillMaxSize()
                    .testTag("expenses_list"),
                // Снизу — место под кнопку «Трата», чтобы она не закрывала последнюю трату.
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item(key = "month") { MonthCard(state.today, state.monthSpent, state.budget) }
                item(key = "search") {
                    AnimatedVisibility(searchOpen) {
                        OutlinedTextField(
                            value = state.query,
                            onValueChange = viewModel::setQuery,
                            placeholder = { Text("Категория, заметка или тег") },
                            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                item(key = "period") {
                    Column {
                        PeriodChips(state.period, viewModel::setPeriod)
                        Text(
                            "${money(state.periodTotal)} · ${state.periodCount} ${plural(state.periodCount, "трата", "траты", "трат")}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (!state.loading && state.sections.isEmpty()) {
                    item(key = "empty") {
                        EmptyState(searching = state.query.isNotBlank())
                    }
                }
                state.sections.forEach { section ->
                    item(key = "day-${section.date}") {
                        DayHeader(dayTitle(section.date, state.today), section.total)
                    }
                    items(section.items, key = { it.id }) { expense ->
                        SwipeToDelete(onDelete = { viewModel.delete(expense) }) {
                            ExpenseRow(expense, onClick = { editing = expense })
                        }
                    }
                }
            }
        }
    }

    if (showAdd) {
        QuickAddSheet(
            suggestions = state.frequentCategories,
            onAdd = viewModel::addFromText,
            onDismiss = { showAdd = false },
        )
    }
    editing?.let { expense ->
        EditExpenseSheet(
            expense = expense,
            onSave = { category, amount, note, group, tag -> viewModel.edit(expense.id, category, amount, note, group, tag) },
            onDelete = { viewModel.delete(expense) },
            onDismiss = { editing = null },
        )
    }
}

/** Карточка текущего месяца: сколько потрачено и полоска бюджета. */
@Composable
private fun MonthCard(today: LocalDate, spent: Double, budget: BudgetStatus?) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "Потрачено за ${monthName(today)}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(money(spent), style = MaterialTheme.typography.headlineMedium)
            if (budget != null) {
                BudgetBar(budget)
                Text(
                    if (budget.over) "Перерасход ${money(-budget.left)} — бюджет ${money(budget.budget)}"
                    else "Осталось ${money(budget.left)} из ${money(budget.budget)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (budget.over) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    "Бюджет на месяц можно задать во вкладке «Аналитика»",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun DayHeader(title: String, total: Double) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 12.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        Text(money(total), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * Одна трата: значок категории, название, подпись (время · категория ИИ · #тег · заметка)
 * и сумма. internal — чтобы UI-тест (androidTest) мог нарисовать её отдельно.
 */
@Composable
internal fun ExpenseRow(expense: Expense, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CategoryBadge(expense.categoryGroup)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                expense.category.replaceFirstChar { it.uppercase() },
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (expense.categoryGroup == null) {
                // Категорию проставляет ИИ на сервере — пока её нет, честно так и пишем.
                Text(
                    "${timeOf(expense.createdAt)} · ИИ определяет категорию…",
                    style = MaterialTheme.typography.bodySmall,
                    fontStyle = FontStyle.Italic,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                val subtitle = listOfNotNull(
                    timeOf(expense.createdAt),
                    expense.categoryGroup,
                    expense.tag?.takeIf { it.isNotBlank() }?.let { "#$it" },
                    expense.note?.takeIf { it.isNotBlank() },
                ).joinToString(" · ")
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(money(expense.amount), style = MaterialTheme.typography.titleMedium)
    }
}

/**
 * Свайп влево — удалить. Под строкой при свайпе проступает красный фон со значком корзины.
 * Сама трата удаляется не сразу: ViewModel прячет её и показывает «Отменить».
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeToDelete(onDelete: () -> Unit, content: @Composable () -> Unit) {
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) onDelete()
            value == SwipeToDismissBoxValue.EndToStart
        },
    )
    SwipeToDismissBox(
        state = state,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            Box(
                Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.errorContainer)
                    .padding(horizontal = 20.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Icon(Icons.Outlined.Delete, contentDescription = "Удалить", tint = MaterialTheme.colorScheme.onErrorContainer)
            }
        },
    ) { content() }
}

@Composable
private fun EmptyState(searching: Boolean) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            if (searching) "Ничего не нашлось" else "За этот период трат нет",
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            if (searching) "Попробуйте другое слово или период «Всё»" else "Нажмите «Трата» и напишите, например, «кофе 200»",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
