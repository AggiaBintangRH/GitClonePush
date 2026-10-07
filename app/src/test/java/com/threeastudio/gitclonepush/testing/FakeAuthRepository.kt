package com.threeastudio.gitclonepush.testing

import android.net.Uri
import com.threeastudio.gitclonepush.core.model.AuthState
import com.threeastudio.gitclonepush.domain.repository.AuthLaunchRequest
import com.threeastudio.gitclonepush.domain.repository.AuthRepository
import kotlinx.coroutines.flow.MutableStateFlow

class FakeAuthRepository(initialState: AuthState = AuthState.Unauthenticated) : AuthRepository {
    val state = MutableStateFlow(initialState)
    var logoutCalls = 0
    var logoutError: Exception? = null
    override fun observeAuthState() = state
    override suspend fun beginLogin(): Result<AuthLaunchRequest> = Result.failure(UnsupportedOperationException())
    override suspend fun completeLogin(callbackUri: Uri): Result<Unit> = Result.failure(UnsupportedOperationException())
    override suspend fun cancelLogin() = Unit
    override suspend fun logout() {
        logoutCalls++
        logoutError?.let { throw it }
        state.value = AuthState.Unauthenticated
    }
}
