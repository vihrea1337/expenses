package io.github.vihrea1337.expenses.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import dagger.hilt.android.AndroidEntryPoint
import io.github.vihrea1337.expenses.android.data.SessionStore
import io.github.vihrea1337.expenses.android.ui.navigation.AppNavHost
import io.github.vihrea1337.expenses.android.ui.theme.ExpensesTheme
import javax.inject.Inject

/**
 * Единственная Activity — всё остальное экраны Compose внутри неё (single-activity).
 * @AndroidEntryPoint — Hilt подставляет зависимости в поля с @Inject.
 *
 * Сюда же приходят нажатия на виджет «+» и на ярлык «Добавить трату» (действие
 * [ACTION_ADD_EXPENSE]) — тогда сразу открывается окно быстрого ввода.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var session: SessionStore

    /** Открыть окно новой траты (пришли с виджета или ярлыка). */
    private val openAddSheet = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen() // до super.onCreate — так требует SplashScreen API
        super.onCreate(savedInstanceState)
        enableEdgeToEdge() // рисуем под системными панелями, отступы задаёт Scaffold
        // savedInstanceState != null — Activity пересоздана (поворот экрана): окно уже открывали.
        if (savedInstanceState == null) handleIntent(intent)

        setContent {
            ExpensesTheme {
                // Surface задаёт фон и цвет текста по умолчанию из темы — без него текст без
                // явного цвета был бы чёрным и в тёмной теме.
                Surface(color = MaterialTheme.colorScheme.background) {
                    AppNavHost(
                        session = session,
                        openAddSheet = openAddSheet.value,
                        onAddSheetShown = { openAddSheet.value = false },
                    )
                }
            }
        }
    }

    /** Приложение уже открыто (launchMode=singleTop), а пользователь нажал «+» на виджете. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == ACTION_ADD_EXPENSE) openAddSheet.value = true
    }

    companion object {
        /** Действие «добавить трату» — его шлют виджет и ярлык (см. res/xml/shortcuts.xml). */
        const val ACTION_ADD_EXPENSE = "io.github.vihrea1337.expenses.ADD_EXPENSE"
    }
}
