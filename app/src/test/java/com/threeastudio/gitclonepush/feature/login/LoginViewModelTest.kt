package com.threeastudio.gitclonepush.feature.login

import android.net.Uri
import com.threeastudio.gitclonepush.core.model.AuthState
import com.threeastudio.gitclonepush.domain.repository.AuthLaunchRequest
import com.threeastudio.gitclonepush.domain.repository.AuthRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

class LoginViewModelTest {
    @Test
    fun loginFailureRemainsVisibleUntilCleared() {
        val viewModel = LoginViewModel(FakeAuthRepository())

        viewModel.showLoginFailure(IllegalStateException("AUTH_012_BACKEND_HTTP_502: rejected"))

        assertTrue(viewModel.uiState.value.errorMessage.orEmpty().contains("GitHub sign-in could not be completed"))
        viewModel.clearLoginError()
        assertTrue(viewModel.uiState.value.errorMessage == null)
    }

    private class FakeAuthRepository : AuthRepository {
        private val state = MutableStateFlow<AuthState>(AuthState.Unauthenticated)

        override fun observeAuthState(): Flow<AuthState> = state
        override suspend fun beginLogin(): Result<AuthLaunchRequest> = Result.failure(UnsupportedOperationException())
        override suspend fun completeLogin(callbackUri: Uri): Result<Unit> = Result.failure(UnsupportedOperationException())
        override suspend fun cancelLogin() = Unit
        override suspend fun logout() = Unit
    }
}
