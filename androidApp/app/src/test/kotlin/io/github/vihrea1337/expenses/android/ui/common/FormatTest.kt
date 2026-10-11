package io.github.vihrea1337.expenses.android.ui.common

import java.io.IOException
import java.net.SocketTimeoutException
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.HttpException
import retrofit2.Response

/** Форматирование чисел, дат и ошибок (Format.kt) — то, что пользователь видит буквально. */
class FormatTest {
    private val nbsp = ' ' // неразрывный пробел — разделитель тысяч

    @Test
    fun `formatAmount — пробел между тысячами, запятая, без лишних нулей`() {
        assertEquals("12${nbsp}340", formatAmount(12340.0))
        assertEquals("149,5", formatAmount(149.5))
        assertEquals("0", formatAmount(0.0))
        assertEquals("1${nbsp}000${nbsp}000", formatAmount(1_000_000.0))
        assertEquals("200${nbsp}₽", money(200.0))
    }

    @Test
    fun `dayTitle — сегодня, вчера, день недели и год для прошлых лет`() {
        val today = LocalDate.of(2026, 10, 11) // воскресенье
        assertEquals("Сегодня", dayTitle(today, today))
        assertEquals("Вчера", dayTitle(today.minusDays(1), today))
        assertEquals("Пт, 9 октября", dayTitle(LocalDate.of(2026, 10, 9), today))
        assertEquals("Ср, 31 декабря 2025", dayTitle(LocalDate.of(2025, 12, 31), today))
        assertEquals("Без даты", dayTitle(null, today))
    }

    @Test
    fun `monthName и timeOf`() {
        assertEquals("октябрь", monthName(LocalDate.of(2026, 10, 1)))
        assertEquals("09:30", timeOf("2026-10-11T09:30:12"))
    }

    @Test
    fun `plural — русские склонения, включая 11-14`() {
        fun p(n: Int) = plural(n, "трата", "траты", "трат")
        assertEquals("трата", p(1))
        assertEquals("траты", p(3))
        assertEquals("трат", p(5))
        assertEquals("трат", p(11))
        assertEquals("трат", p(14))
        assertEquals("трата", p(21))
        assertEquals("траты", p(102))
    }

    @Test
    fun `humanMessage — понятные слова вместо HTTP 401 Unauthorized`() {
        fun http(code: Int) = HttpException(Response.error<Unit>(code, "".toResponseBody(null)))
        assertEquals("Токен не подошёл — войдите заново", http(401).humanMessage())
        assertEquals("Сервер временно недоступен (502)", http(502).humanMessage())
        assertEquals("Ошибка сервера (400)", http(400).humanMessage())
        assertEquals("Нет связи с сервером", IOException("boom").humanMessage())
        // SocketTimeoutException — тоже IOException, но проверяется раньше и даёт своё сообщение.
        assertEquals("Сервер долго не отвечает — попробуйте ещё раз", SocketTimeoutException().humanMessage())
    }
}
