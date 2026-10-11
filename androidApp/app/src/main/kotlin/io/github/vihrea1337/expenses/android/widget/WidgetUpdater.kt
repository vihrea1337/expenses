package io.github.vihrea1337.expenses.android.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.util.Log
import androidx.glance.appwidget.updateAll
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.vihrea1337.expenses.android.data.ExpensesRepository
import io.github.vihrea1337.expenses.android.data.SessionStore
import io.github.vihrea1337.expenses.android.di.ApplicationScope
import io.github.vihrea1337.expenses.android.ui.common.spentInMonth
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/**
 * Держит виджет в актуальном состоянии.
 *
 * Можно было бы дёргать «обнови виджет» вручную после каждого добавления траты, правки,
 * синхронизации и выхода — и однажды забыть одно из мест. Вместо этого виджет подписан на
 * сами ДАННЫЕ: сумма за месяц, бюджет и факт входа. Что бы их ни поменяло (экран, фоновый
 * WorkManager, выход из аккаунта), виджет перерисуется. debounce — пачка изменений во время
 * синхронизации даёт одну перерисовку, а не двадцать.
 */
@Singleton
class WidgetUpdater @Inject constructor(
    @ApplicationContext private val context: Context,
    private val expenses: ExpensesRepository,
    private val session: SessionStore,
    // Область корутин всего приложения: подписка живёт, пока жив процесс (а пока он жив,
    // данные могут меняться).
    @ApplicationScope private val scope: CoroutineScope,
) {
    @OptIn(FlowPreview::class)
    fun start() {
        scope.launch {
            combine(expenses.observeExpenses(), expenses.budget, session.session) { list, budget, s ->
                Triple(list.spentInMonth(LocalDate.now()), budget, s != null)
            }
                .distinctUntilChanged()
                .drop(1) // первое значение — то, что виджет и так уже показывает
                .debounce(500)
                .collect { if (hasWidgets()) runCatching { ExpensesWidget().updateAll(context) }.onFailure { Log.w("Widget", it) } }
        }
    }

    /** Есть ли виджет хоть на одном рабочем столе — иначе и перерисовывать нечего. */
    private fun hasWidgets(): Boolean =
        AppWidgetManager.getInstance(context)
            .getAppWidgetIds(ComponentName(context, ExpensesWidgetReceiver::class.java))
            .isNotEmpty()

    /**
     * Предложить пользователю добавить виджет на рабочий стол (Android 8+ — наш minSdk): лаунчер сам
     * показывает окно «Добавить виджет?». Вызывается из профиля. false — лаунчер не умеет.
     */
    fun requestPin(): Boolean {
        val manager = AppWidgetManager.getInstance(context)
        if (!manager.isRequestPinAppWidgetSupported) return false
        return manager.requestPinAppWidget(ComponentName(context, ExpensesWidgetReceiver::class.java), null, null)
    }
}
