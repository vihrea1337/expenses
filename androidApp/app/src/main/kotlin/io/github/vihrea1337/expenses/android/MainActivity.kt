package io.github.vihrea1337.expenses.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import io.github.vihrea1337.expenses.android.data.SyncCursorStore
import io.github.vihrea1337.expenses.android.data.local.ExpensesDatabase
import kotlinx.coroutines.launch

/**
 * Единственная Activity. Показывает экран входа, пока нет токена, иначе — экран трат.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        TokenStore.init(applicationContext) // загрузить сохранённый токен
        ExpensesDatabase.init(applicationContext) // открыть локальный кэш трат (офлайн-first)
        SyncCursorStore.init(applicationContext) // курсор дельта-синхронизации
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    var loggedIn by rememberSaveable { mutableStateOf(TokenStore.token != null) }
                    if (loggedIn) {
                        ExpensesScreen(
                            onLogout = {
                                TokenStore.clear()
                                // Кэш и курсор синхронизации — тоже: иначе следующий вошедший
                                // на этом же телефоне аккаунт увидел бы чужие траты. Если были
                                // неотправленные офлайн-правки — они теряются (сознательный
                                // компромисс, см. ExpensesDatabase.clearAll).
                                lifecycleScope.launch { ExpensesDatabase.clearAll() }
                                SyncCursorStore.clear()
                                loggedIn = false
                            },
                        )
                    } else {
                        LoginScreen(onLoggedIn = { loggedIn = true })
                    }
                }
            }
        }
    }
}
