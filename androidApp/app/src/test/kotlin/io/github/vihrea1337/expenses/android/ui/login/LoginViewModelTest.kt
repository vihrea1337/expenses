package io.github.vihrea1337.expenses.android.ui.login

import io.github.vihrea1337.expenses.android.data.AuthRepository
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Before
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Экран входа: какой запрос уходит и что пользователь видит при ошибке. */
@OptIn(ExperimentalCoroutinesApi::class)
class LoginViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val auth = FakeAuth()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `пустой токен — кнопка неактивна, запрос не уходит`() = runTest(dispatcher) {
        val vm = LoginViewModel(auth)
        assertFalse(vm.state.value.canSubmit)
        vm.submit()
        advanceUntilIdle()
        assertTrue(auth.calls.isEmpty())
    }

    @Test
    fun `вход по токену — пробелы по краям обрезаются`() = runTest(dispatcher) {
        val vm = LoginViewModel(auth)
        vm.setToken("  abc123 \n")
        vm.submit()
        advanceUntilIdle()
        assertEquals(listOf("token:abc123"), auth.calls)
        assertFalse(vm.state.value.busy)
        assertNull(vm.state.value.error)
    }

    @Test
    fun `регистрация — имя можно не вводить`() = runTest(dispatcher) {
        val vm = LoginViewModel(auth)
        vm.setRegister(true)
        assertTrue(vm.state.value.canSubmit)
        vm.submit()
        advanceUntilIdle()
        assertEquals(listOf("register:"), auth.calls)
    }

    @Test
    fun `неизвестный токен (401) — подсказка, где взять правильный`() = runTest(dispatcher) {
        auth.failWith = HttpException(Response.error<Unit>(401, "".toResponseBody(null)))
        val vm = LoginViewModel(auth)
        vm.setToken("wrong")
        vm.submit()
        advanceUntilIdle()
        assertTrue(vm.state.value.error!!.startsWith("Такого токена нет"))
        assertFalse(vm.state.value.busy, "после ошибки кнопку снова можно нажать")
    }

    @Test
    fun `нет сети — так и пишем, а при правке токена ошибка уходит`() = runTest(dispatcher) {
        auth.failWith = IOException()
        val vm = LoginViewModel(auth)
        vm.setToken("abc")
        vm.submit()
        advanceUntilIdle()
        assertEquals("Нет связи с сервером", vm.state.value.error)
        vm.setToken("abcd")
        assertNull(vm.state.value.error)
    }

    private class FakeAuth : AuthRepository {
        val calls = mutableListOf<String>()
        var failWith: Exception? = null

        override suspend fun loginWithToken(token: String) {
            calls += "token:$token"
            failWith?.let { throw it }
        }

        override suspend fun register(name: String) {
            calls += "register:$name"
            failWith?.let { throw it }
        }

        override suspend fun logout() = Unit
    }
}
