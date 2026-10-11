package io.github.vihrea1337.expenses.android.ui.expenses

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import io.github.vihrea1337.expenses.Expense
import io.github.vihrea1337.expenses.android.ui.common.CATEGORY_GROUPS
import io.github.vihrea1337.expenses.android.ui.common.CategoryBadge
import io.github.vihrea1337.expenses.android.ui.common.formatAmount
import io.github.vihrea1337.expenses.android.ui.common.money
import io.github.vihrea1337.expenses.parseExpense

/**
 * Окно быстрого ввода — главная идея продукта: одна строка «кофе 200», как в Telegram-боте.
 *
 * Строка разбирается ПРЯМО ПРИ НАБОРЕ той же функцией parseExpense из shared/, что и у бота,
 * и под полем видно, что получится: «Кофе — 200 ₽». Кнопка активна, только когда строка
 * разобралась. Частые категории — кнопками: нажал «такси», дописал сумму.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun QuickAddSheet(
    suggestions: List<String>,
    onAdd: (text: String, note: String, tag: String) -> Boolean,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        QuickAddContent(suggestions, onAdd, onDone = onDismiss)
    }
}

/** Содержимое окна отдельно от шторки — так его можно нарисовать в UI-тесте без анимаций. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun QuickAddContent(
    suggestions: List<String>,
    onAdd: (text: String, note: String, tag: String) -> Boolean,
    onDone: () -> Unit,
) {
    // TextFieldValue (а не String) — чтобы после нажатия на подсказку поставить курсор в конец.
    var input by remember { mutableStateOf(TextFieldValue("")) }
    var note by rememberSaveable { mutableStateOf("") }
    var tag by rememberSaveable { mutableStateOf("") }
    var moreOpen by rememberSaveable { mutableStateOf(false) }
    val parsed = parseExpense(input.text)
    val focus = remember { FocusRequester() }

    // Окно открылось — сразу фокус в поле и клавиатура: ввод траты в одно касание.
    LaunchedEffect(Unit) { focus.requestFocus() }

    fun submit() {
        if (onAdd(input.text, note, tag)) onDone()
    }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(bottom = 16.dp)
            .navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Новая трата", style = MaterialTheme.typography.titleLarge)
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            label = { Text("Что и сколько") },
            placeholder = { Text("кофе 200") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focus)
                .testTag("quick_input"),
        )

        // Предпросмотр: что поймёт приложение (и бот) из этой строки.
        if (parsed != null) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.testTag("quick_preview")) {
                CategoryBadge(null, size = 32.dp)
                Spacer(Modifier.width(10.dp))
                Text(
                    parsed.category.replaceFirstChar { it.uppercase() },
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                Text(money(parsed.amount), style = MaterialTheme.typography.titleMedium)
            }
        } else {
            Text(
                if (input.text.isBlank()) "Напишите, на что и сколько: «такси до дома 350»"
                else "Сумма — последним словом: «${input.text.trim()} 200»",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (suggestions.isNotEmpty() && parsed == null) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                suggestions.forEach { category ->
                    AssistChip(
                        onClick = {
                            val text = "$category "
                            input = TextFieldValue(text, selection = TextRange(text.length))
                            focus.requestFocus()
                        },
                        label = { Text(category) },
                    )
                }
            }
        }

        TextButton(onClick = { moreOpen = !moreOpen }) {
            Text(if (moreOpen) "Скрыть заметку и тег" else "Добавить заметку или тег")
        }
        AnimatedVisibility(moreOpen) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("Заметка") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = tag,
                    onValueChange = { tag = it },
                    label = { Text("Тег, например «поездка»") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        Button(
            onClick = ::submit,
            enabled = parsed != null,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .testTag("quick_add_button"),
        ) {
            Text("Добавить")
        }
    }
}

/**
 * Правка траты: название, сумма, заметка, тег и категория ИИ. «Авто» — пусть категорию
 * заново определит ИИ на сервере; любая другая — ручная правка (ИИ её больше не тронет).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun EditExpenseSheet(
    expense: Expense,
    onSave: (category: String, amount: String, note: String, group: String?, tag: String) -> Boolean,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    // remember(expense.id) — поля сбрасываются, если открыли правку другой траты.
    var category by remember(expense.id) { mutableStateOf(expense.category) }
    var amount by remember(expense.id) { mutableStateOf(formatAmount(expense.amount).replace(" ", "")) }
    var note by remember(expense.id) { mutableStateOf(expense.note.orEmpty()) }
    var tag by remember(expense.id) { mutableStateOf(expense.tag.orEmpty()) }
    var group by remember(expense.id) { mutableStateOf(expense.categoryGroup) }
    var error by remember { mutableStateOf<String?>(null) }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 16.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Изменить трату", style = MaterialTheme.typography.titleLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = category,
                    onValueChange = { category = it },
                    label = { Text("На что") },
                    singleLine = true,
                    modifier = Modifier.weight(1.6f),
                )
                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = it },
                    label = { Text("Сумма, ₽") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f),
                )
            }
            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                label = { Text("Заметка") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = tag,
                onValueChange = { tag = it },
                label = { Text("Тег") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text("Категория", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = group == null, onClick = { group = null }, label = { Text("авто (ИИ)") })
                (CATEGORY_GROUPS + "прочее").forEach { g ->
                    FilterChip(
                        selected = group == g,
                        onClick = { group = g },
                        label = { Text(g) },
                        leadingIcon = if (group == g) null else ({ CategoryBadge(g, size = 18.dp) }),
                    )
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        onDelete()
                        onDismiss()
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Outlined.Delete, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Удалить")
                }
                Button(
                    onClick = {
                        if (onSave(category, amount, note, group, tag)) onDismiss()
                        else error = "Нужны название и сумма больше нуля"
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Сохранить")
                }
            }
        }
    }
}
