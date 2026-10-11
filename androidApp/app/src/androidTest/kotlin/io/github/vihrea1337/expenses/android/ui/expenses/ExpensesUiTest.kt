package io.github.vihrea1337.expenses.android.ui.expenses

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.vihrea1337.expenses.Expense
import io.github.vihrea1337.expenses.android.ui.theme.ExpensesTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Неразрывный пробел: им formatAmount/money разделяют тысячи и рубли (не рвётся переносом). */
private const val NBSP = " "

/**
 * UI-тесты Compose — идут на эмуляторе или телефоне:
 *   ./gradlew :app:connectedDebugAndroidTest
 * Рисуем отдельные кусочки экрана (без Hilt, сети и базы) и «нажимаем» на них как пользователь.
 * Элементы находим по тексту или по testTag (метки в коде экрана: quick_input и т.п.).
 */
@RunWith(AndroidJUnit4::class)
class ExpensesUiTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun быстрый_ввод_показывает_предпросмотр_и_включает_кнопку() {
        var added: String? = null
        compose.setContent {
            ExpensesTheme {
                QuickAddContent(suggestions = emptyList(), onAdd = { text, _, _ -> added = text; true }, onDone = {})
            }
        }
        // Пустое поле — кнопка неактивна, предпросмотра нет.
        compose.onNodeWithTag("quick_add_button").assertIsNotEnabled()

        compose.onNodeWithTag("quick_input").performTextInput("кофе 200")
        compose.onNodeWithTag("quick_preview").assertIsDisplayed()
        compose.onNodeWithText("Кофе").assertIsDisplayed()
        compose.onNodeWithText("200${NBSP}₽").assertIsDisplayed()
        compose.onNodeWithTag("quick_add_button").assertIsEnabled().performClick()
        assertEquals("кофе 200", added)
    }

    @Test
    fun строка_без_суммы_подсказывает_формат() {
        compose.setContent {
            ExpensesTheme {
                QuickAddContent(suggestions = emptyList(), onAdd = { _, _, _ -> true }, onDone = {})
            }
        }
        compose.onNodeWithTag("quick_input").performTextInput("кофе")
        compose.onNodeWithText("Сумма — последним словом: «кофе 200»").assertIsDisplayed()
        compose.onNodeWithTag("quick_add_button").assertIsNotEnabled()
    }

    @Test
    fun подсказка_частой_категории_подставляется_в_поле() {
        compose.setContent {
            ExpensesTheme {
                QuickAddContent(suggestions = listOf("такси"), onAdd = { _, _, _ -> true }, onDone = {})
            }
        }
        compose.onNodeWithText("такси").performClick()
        compose.onNodeWithTag("quick_input").performTextInput("350")
        compose.onNodeWithText("Такси").assertIsDisplayed()
        compose.onNodeWithText("350${NBSP}₽").assertIsDisplayed()
    }

    @Test
    fun строка_траты_показывает_сумму_категорию_ИИ_и_тег() {
        var clicked = false
        compose.setContent {
            ExpensesTheme {
                ExpenseRow(
                    Expense(
                        id = "1", amount = 12340.0, category = "ноутбук", createdAt = "2026-10-11T09:30:00",
                        categoryGroup = "техника", tag = "работа",
                    ),
                    onClick = { clicked = true },
                )
            }
        }
        compose.onNodeWithText("Ноутбук").assertIsDisplayed()
        compose.onNodeWithText("12${NBSP}340${NBSP}₽").assertIsDisplayed() // неразрывный пробел, как в formatAmount
        compose.onNodeWithText("09:30 · техника · #работа").assertIsDisplayed().performClick()
        assertTrue(clicked)
    }

    @Test
    fun пока_нет_категории_ИИ_так_и_написано() {
        compose.setContent {
            ExpensesTheme {
                ExpenseRow(Expense(id = "1", amount = 200.0, category = "кофе", createdAt = "2026-10-11T08:05:00"), onClick = {})
            }
        }
        compose.onNodeWithText("08:05 · ИИ определяет категорию…").assertIsDisplayed()
    }
}
