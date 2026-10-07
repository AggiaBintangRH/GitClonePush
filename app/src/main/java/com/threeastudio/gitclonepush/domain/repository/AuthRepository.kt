package com.threeastudio.gitclonepush.domain.repository

import android.net.Uri
import com.threeastudio.gitclonepush.core.model.AuthState
import kotlinx.coroutines.flow.Flow

data class AuthLaunchRequest(val authorizationUri: Uri)
interface AuthRepository {
    fun observeAuthState(): Flow<AuthState>
    suspend fun beginLogin(): Result<AuthLaunchRequest>
    suspend fun completeLogin(callbackUri: Uri): Result<Unit>
    /** Abandons only the pending OAuth transaction, never an authenticated session. */
    suspend fun cancelLogin()
    suspend fun logout()
}
