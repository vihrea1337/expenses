package io.github.vihrea1337.expenses.android.ui.login

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.vihrea1337.expenses.android.data.AuthRepository
import io.github.vihrea1337.expenses.android.ui.common.humanMessage
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import retrofit2.HttpException

/** Состояние экрана входа. */
data class LoginUiState(
    val register: Boolean = false,
    val token: String = "",
    val name: String = "",
    val busy: Boolean = false,
    val error: String? = null,
) {
    val canSubmit: Boolean get() = !busy && (register || token.isNotBlank())
}

/**
 * Вход по токену или создание аккаунта. После успеха ничего не делаем сами: сессия
 * сохранена, навигация подписана на неё и сама откроет главный экран.
 */
@HiltViewModel
class LoginViewModel @Inject constructor(private val auth: AuthRepository) : ViewModel() {
    private val _state = MutableStateFlow(LoginUiState())
    val state: StateFlow<LoginUiState> = _state.asStateFlow()

    fun setRegister(value: Boolean) = _state.update { it.copy(register = value, error = null) }
    fun setToken(value: String) = _state.update { it.copy(token = value, error = null) }
    fun setName(value: String) = _state.update { it.copy(name = value) }

    fun submit() {
        val s = _state.value
        if (!s.canSubmit) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            try {
                if (s.register) auth.register(s.name.trim()) else auth.loginWithToken(s.token.trim())
                _state.update { it.copy(busy = false) }
            } catch (e: Exception) {
                val message = if (e is HttpException && e.code() == 401) {
                    // Сервер не знает такого токена — чаще всего скопировали не целиком.
                    "Такого токена нет. Проверьте, что он скопирован целиком (в боте — команда /token)"
                } else {
                    e.humanMessage()
                }
                _state.update { it.copy(busy = false, error = message) }
            }
        }
    }
}

@Composable
fun LoginScreen(viewModel: LoginViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(
        Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        Box(
            Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center,
        ) {
            Text("₽", color = MaterialTheme.colorScheme.onPrimary, fontSize = 32.sp)
        }
        Text("Траты", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Учёт расходов быстрым вводом: «кофе 200» — и готово. Приложение, Telegram-бот и сайт видят общие траты.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = !state.register,
                onClick = { viewModel.setRegister(false) },
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
            ) { Text("Есть токен") }
            SegmentedButton(
                selected = state.register,
                onClick = { viewModel.setRegister(true) },
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
            ) { Text("Новый аккаунт") }
        }

        if (state.register) {
            OutlinedTextField(
                value = state.name,
                onValueChange = viewModel::setName,
                label = { Text("Как вас называть") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { viewModel.submit() }),
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "Сервер выдаст токен — он и есть ваш аккаунт. Его можно будет скопировать в профиле.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            OutlinedTextField(
                value = state.token,
                onValueChange = viewModel::setToken,
                label = { Text("Токен доступа") },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { viewModel.submit() }),
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "Токен есть в профиле приложения на другом телефоне, на сайте или в Telegram-боте по команде /token.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        state.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }

        Button(
            onClick = viewModel::submit,
            enabled = state.canSubmit,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
        ) {
            if (state.busy) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
            } else {
                Text(if (state.register) "Создать аккаунт" else "Войти", textAlign = TextAlign.Center)
            }
        }
    }
}
