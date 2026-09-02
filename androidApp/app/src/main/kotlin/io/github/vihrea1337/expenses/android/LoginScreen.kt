package io.github.vihrea1337.expenses.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.vihrea1337.expenses.RegisterRequest
import io.github.vihrea1337.expenses.android.data.ApiClient
import kotlinx.coroutines.launch

/**
 * Экран входа: вход по существующему токену или регистрация нового аккаунта.
 * onLoggedIn вызывается после успешного входа/регистрации (токен уже сохранён в TokenStore).
 */
@Composable
fun LoginScreen(onLoggedIn: () -> Unit) {
    var token by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(
        Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Учёт расходов", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(24.dp))

        // --- Вход по токену ---
        Text("Вход по токену", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = token,
            onValueChange = { token = it },
            label = { Text("Токен доступа") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = {
                scope.launch {
                    busy = true
                    error = null
                    val t = token.trim()
                    TokenStore.save(t, null)
                    try {
                        val me = ApiClient.api.me() // проверяем токен
                        TokenStore.save(t, me.name)
                        onLoggedIn()
                    } catch (e: Exception) {
                        TokenStore.clear()
                        error = "Неверный токен"
                    }
                    busy = false
                }
            },
            enabled = !busy && token.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Войти")
        }

        Spacer(Modifier.height(32.dp))

        // --- Регистрация ---
        Text("Или создать новый аккаунт", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Имя") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = {
                scope.launch {
                    busy = true
                    error = null
                    try {
                        val user = ApiClient.api.register(
                            RegisterRequest(name.trim().ifBlank { "Пользователь" }),
                        )
                        TokenStore.save(user.token, user.name)
                        onLoggedIn()
                    } catch (e: Exception) {
                        error = "Не удалось зарегистрироваться: ${e.message}"
                    }
                    busy = false
                }
            },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Создать аккаунт")
        }

        if (error != null) {
            Spacer(Modifier.height(12.dp))
            Text(error!!, color = MaterialTheme.colorScheme.error)
        }
    }
}
