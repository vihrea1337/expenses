package io.github.vihrea1337.expenses.android.ui.common

import java.io.IOException
import java.net.SocketTimeoutException
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import retrofit2.HttpException

private val RU = Locale.forLanguageTag("ru")

/**
 * Разделители заданы явно, а не берутся из локали телефона: так «12 340» выглядит
 * одинаково на любом устройстве и в тестах (в разных версиях Java/Android русская
 * группировка — то обычный неразрывный пробел, то «узкий»).
 */
private val AMOUNT_FORMAT = DecimalFormat("#,##0.##", DecimalFormatSymbols(RU).apply {
    groupingSeparator = ' ' // неразрывный пробел — число не разорвётся переносом строки
    decimalSeparator = ','
})

/** 12340.0 → «12 340», 149.5 → «149,5». */
fun formatAmount(value: Double): String = AMOUNT_FORMAT.format(value)

/** Сумма с валютой: «12 340 ₽». */
fun money(value: Double): String = formatAmount(value) + " ₽"

/** Заголовок дня в списке: «Сегодня», «Вчера» или «Пт, 10 октября». */
fun dayTitle(date: LocalDate?, today: LocalDate): String = when (date) {
    null -> "Без даты"
    today -> "Сегодня"
    today.minusDays(1) -> "Вчера"
    else -> {
        val weekday = date.dayOfWeek.getDisplayName(TextStyle.SHORT, RU).replaceFirstChar { it.uppercase() }
        val pattern = if (date.year == today.year) "d MMMM" else "d MMMM yyyy"
        "$weekday, ${date.format(DateTimeFormatter.ofPattern(pattern, RU))}"
    }
}

/** «октябрь» — для подписей «Потрачено в октябре» (именительный падеж месяца). */
fun monthName(date: LocalDate): String =
    date.month.getDisplayName(TextStyle.FULL_STANDALONE, RU)

/** Время траты «09:30» из createdAt («2026-10-11T09:30:12»). */
fun timeOf(createdAt: String): String = createdAt.drop(11).take(5)

/**
 * Сообщение об ошибке человеческим языком. Retrofit на ответ 4xx/5xx бросает HttpException
 * с текстом вида «HTTP 400 Bad Request» — сам по себе он ничего не объясняет пользователю.
 */
fun Throwable.humanMessage(): String = when (this) {
    is SocketTimeoutException -> "Сервер долго не отвечает — попробуйте ещё раз"
    is IOException -> "Нет связи с сервером"
    is HttpException -> when (code()) {
        401 -> "Токен не подошёл — войдите заново"
        in 500..599 -> "Сервер временно недоступен (${code()})"
        else -> "Ошибка сервера (${code()})"
    }
    else -> message ?: "Неизвестная ошибка"
}

/** Склонение: plural(3, "трата", "траты", "трат") → «траты». */
fun plural(n: Int, one: String, few: String, many: String): String {
    val mod100 = n % 100
    val mod10 = n % 10
    return when {
        mod100 in 11..14 -> many
        mod10 == 1 -> one
        mod10 in 2..4 -> few
        else -> many
    }
}
