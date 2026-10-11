package io.github.vihrea1337.expenses.android.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PieChart
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Receipt
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import io.github.vihrea1337.expenses.android.data.SessionStore
import io.github.vihrea1337.expenses.android.ui.analytics.AnalyticsScreen
import io.github.vihrea1337.expenses.android.ui.expenses.ExpensesScreen
import io.github.vihrea1337.expenses.android.ui.login.LoginScreen
import io.github.vihrea1337.expenses.android.ui.profile.ProfileScreen
import kotlinx.serialization.Serializable

// Маршруты навигации — обычные @Serializable-объекты (типобезопасная навигация
// Navigation 2.8+): опечатку в маршруте поймает компилятор, а не пользователь.
@Serializable data object LoginRoute
@Serializable data object HomeRoute

/** Вкладки нижней навигации главного экрана. */
private enum class Tab(val title: String, val icon: ImageVector) {
    EXPENSES("Траты", Icons.Outlined.Receipt),
    ANALYTICS("Аналитика", Icons.Outlined.PieChart),
    PROFILE("Профиль", Icons.Outlined.Person),
}

/**
 * Граф навигации приложения.
 *
 * Вход/выход управляется ДАННЫМИ, а не кнопками: граф подписан на сессию. Нет сессии
 * (нажали «Выйти» или сервер ответил 401 — токен отозван) → экран входа, история экранов
 * очищается, чтобы «Назад» не вернул в чужие данные. Появилась сессия → главный экран.
 */
@Composable
fun AppNavHost(session: SessionStore, openAddSheet: Boolean, onAddSheetShown: () -> Unit) {
    val nav = rememberNavController()
    val current by session.session.collectAsStateWithLifecycle()
    val loggedIn = current != null

    LaunchedEffect(loggedIn) {
        val target: Any = if (loggedIn) HomeRoute else LoginRoute
        nav.navigate(target) {
            popUpTo(0) { inclusive = true } // убрать из истории всё, что было до этого
            launchSingleTop = true
        }
    }

    NavHost(navController = nav, startDestination = if (loggedIn) HomeRoute else LoginRoute) {
        composable<LoginRoute> { LoginScreen() }
        composable<HomeRoute> { HomeScreen(openAddSheet, onAddSheetShown) }
    }
}

/**
 * Главный экран с нижней навигацией. Выбранная вкладка — rememberSaveable: переживает
 * поворот экрана. Пришли с виджета или ярлыка «Добавить трату» — переключаемся на «Траты»,
 * там откроется окно ввода.
 */
@Composable
private fun HomeScreen(openAddSheet: Boolean, onAddSheetShown: () -> Unit) {
    var tab by rememberSaveable { mutableStateOf(Tab.EXPENSES) }
    LaunchedEffect(openAddSheet) { if (openAddSheet) tab = Tab.EXPENSES }

    Scaffold(
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { item ->
                    NavigationBarItem(
                        selected = tab == item,
                        onClick = { tab = item },
                        icon = { Icon(item.icon, contentDescription = null) },
                        label = { Text(item.title) },
                    )
                }
            }
        },
    ) { padding ->
        // Только нижний отступ (под панель вкладок): верхний под статус-бар добавит
        // шапка (TopAppBar) каждой вкладки — иначе он применился бы дважды.
        Box(
            Modifier
                .fillMaxSize()
                .padding(bottom = padding.calculateBottomPadding()),
        ) {
            when (tab) {
                Tab.EXPENSES -> ExpensesScreen(openAddSheet = openAddSheet, onAddSheetShown = onAddSheetShown)
                Tab.ANALYTICS -> AnalyticsScreen()
                Tab.PROFILE -> ProfileScreen()
            }
        }
    }
}
