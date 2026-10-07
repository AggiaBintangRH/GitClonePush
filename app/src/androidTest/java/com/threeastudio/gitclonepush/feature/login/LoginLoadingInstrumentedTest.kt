package com.threeastudio.gitclonepush.feature.login

import android.content.ActivityNotFoundException
import android.net.Uri
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.threeastudio.gitclonepush.core.model.AuthState
import com.threeastudio.gitclonepush.core.model.AuthenticatedUser
import com.threeastudio.gitclonepush.domain.repository.AuthLaunchRequest
import com.threeastudio.gitclonepush.domain.repository.AuthRepository
import com.threeastudio.gitclonepush.ui.theme.GitClonePushTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LoginLoadingInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var model: LoginViewModel
    private val dark = mutableStateOf(false)

    @After fun cleanUp() { compose.runOnUiThread { if (::model.isInitialized) model.viewModelScope.cancel() } }

    @Test
    fun loadingRemainsThroughBrowserAndCallbackAndDisappearsOnSuccess() {
        val auth = showLogin()
        compose.onNodeWithText("Continue with GitHub").performClick()
        compose.onNodeWithTag("loading-overlay").assertIsDisplayed()
        compose.onNodeWithText("Cancel sign-in").assertIsDisplayed()
        compose.runOnIdle { model.continueWithGitHub {}; dark.value = true }
        assertEquals(1, auth.beginCalls)
        compose.runOnUiThread { model.completeLogin(CALLBACK) }
        compose.onNodeWithText("Completing GitHub sign-in and loading your profile…").assertIsDisplayed()
        compose.onNodeWithText("Cancel sign-in").assertDoesNotExist()
        compose.runOnIdle { dark.value = false }
        assertEquals(1, auth.completeCalls)
        compose.runOnIdle { auth.completion.complete(Result.success(Unit)) }
        compose.onNodeWithTag("loading-overlay").assertDoesNotExist()
        assertTrue(auth.state.value is AuthState.Authenticated)
        assertEquals(LoginPhase.IDLE, model.uiState.value.phase)
    }

    @Test
    fun callbackFailureShowsSafeReasonPersistsThroughRecompositionAndRetryClearsIt() {
        val auth = showLogin()
        compose.onNodeWithText("Continue with GitHub").performClick()
        compose.runOnUiThread { model.completeLogin(CALLBACK) }
        compose.runOnIdle { auth.completion.complete(Result.failure(IllegalStateException("AUTH_012_BACKEND_HTTP_503: PRIVATE_RESPONSE_VALUE"))) }
        compose.onNodeWithTag("loading-overlay").assertDoesNotExist()
        compose.onNodeWithText("HTTP 503", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("PRIVATE_RESPONSE_VALUE", substring = true).assertDoesNotExist()
        compose.runOnIdle { dark.value = true }
        compose.onNodeWithText("HTTP 503", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Continue with GitHub").performScrollTo().performClick()
        compose.onNodeWithText("HTTP 503", substring = true).assertDoesNotExist()
        assertEquals(2, auth.beginCalls)
    }

    @Test
    fun cancellationClearsOnlyPendingSignInAndNeverLogsOutTheAccount() {
        val auth = showLogin()
        compose.onNodeWithText("Continue with GitHub").performClick()
        compose.onNodeWithText("Cancel sign-in").performClick()
        compose.onNodeWithTag("loading-overlay").assertDoesNotExist()
        compose.onNodeWithText("GitHub sign-in was cancelled. You can try again.").performScrollTo().assertIsDisplayed()
        assertEquals(1, auth.cancelCalls)
        assertEquals(0, auth.logoutCalls)
    }

    @Test
    fun browserLaunchFailureIsControlledAndNeverLeavesSpinnerStuck() {
        showLogin { throw ActivityNotFoundException() }
        compose.onNodeWithText("Continue with GitHub").performClick()
        compose.onNodeWithTag("loading-overlay").assertDoesNotExist()
        compose.onNodeWithText("No browser is available", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Continue with GitHub").performScrollTo().assertIsEnabled()
    }

    @Test
    fun callbackCancellationIsNotConvertedIntoOrdinaryLoginFailure() {
        val auth = showLogin()
        lateinit var result: Deferred<Result<Unit>>
        compose.runOnUiThread { result = model.completeLogin(CALLBACK) }
        compose.runOnIdle { auth.completion.complete(Result.failure(CancellationException("fixture cancellation"))) }
        compose.waitForIdle()
        assertTrue(result.isCancelled)
        assertNull(model.uiState.value.errorMessage)
        compose.onNodeWithTag("loading-overlay").assertDoesNotExist()
    }

    private fun showLogin(onBrowser: (Uri) -> Unit = {}): PendingAuthRepository {
        val auth = PendingAuthRepository()
        compose.runOnUiThread { model = LoginViewModel(auth) }
        compose.setContent {
            GitClonePushTheme(darkTheme = dark.value, dynamicColor = false) {
                Surface { LoginScreen(onBrowser, model) }
            }
        }
        return auth
    }

    private class PendingAuthRepository : AuthRepository {
        val state = MutableStateFlow<AuthState>(AuthState.Unauthenticated)
        val completion = CompletableDeferred<Result<Unit>>()
        var beginCalls = 0
        var completeCalls = 0
        var cancelCalls = 0
        var logoutCalls = 0
        override fun observeAuthState() = state
        override suspend fun beginLogin(): Result<AuthLaunchRequest> {
            beginCalls++
            return Result.success(AuthLaunchRequest(Uri.parse("https://example.invalid/authorize")))
        }
        override suspend fun completeLogin(callbackUri: Uri): Result<Unit> {
            completeCalls++
            return completion.await().also {
                if (it.isSuccess) state.value = AuthState.Authenticated(AuthenticatedUser(1, "demo", "Demo", null))
            }
        }
        override suspend fun cancelLogin() { cancelCalls++ }
        override suspend fun logout() { logoutCalls++ }
    }

    private companion object { val CALLBACK: Uri = Uri.parse("gitclonepush://oauth/callback") }
}
