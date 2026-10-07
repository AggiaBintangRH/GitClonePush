package com.threeastudio.gitclonepush.feature.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.threeastudio.gitclonepush.BuildConfig
import com.threeastudio.gitclonepush.data.auth.safeAuthFailureCategory
import com.threeastudio.gitclonepush.domain.repository.AuthRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class LoginViewModel(private val authRepository: AuthRepository) : ViewModel() {
    private val _uiState = MutableStateFlow(LoginUiState())
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()
    private val transactionMutex = Mutex()

    fun continueWithGitHub(onOpenBrowser: (android.net.Uri) -> Unit) {
        if (_uiState.value.isSigningIn) return
        _uiState.value = LoginUiState(phase = LoginPhase.PREPARING)
        viewModelScope.launch {
            transactionMutex.withLock {
                try {
                    val launch = authRepository.beginLogin().getOrThrow()
                    onOpenBrowser(launch.authorizationUri)
                    _uiState.value = LoginUiState(phase = LoginPhase.AWAITING_AUTHORIZATION)
                } catch (error: CancellationException) {
                    _uiState.value = LoginUiState()
                    throw error
                } catch (error: Exception) {
                    showLoginFailure(error)
                }
            }
        }
    }

    fun completeLogin(callbackUri: android.net.Uri): Deferred<Result<Unit>> = viewModelScope.async {
        transactionMutex.withLock {
            _uiState.value = LoginUiState(phase = LoginPhase.COMPLETING)
            try {
                val result = authRepository.completeLogin(callbackUri)
                result.exceptionOrNull()?.let { if (it is CancellationException) throw it }
                if (result.isSuccess) _uiState.value = LoginUiState()
                else showLoginFailure(result.exceptionOrNull())
                result
            } catch (error: CancellationException) {
                _uiState.value = LoginUiState()
                throw error
            } catch (error: Exception) {
                showLoginFailure(error)
                Result.failure(error)
            }
        }
    }

    fun cancelLogin() {
        if (_uiState.value.phase != LoginPhase.AWAITING_AUTHORIZATION) return
        _uiState.value = LoginUiState(phase = LoginPhase.CANCELLING)
        viewModelScope.launch {
            transactionMutex.withLock {
                try {
                    authRepository.cancelLogin()
                    _uiState.value = LoginUiState(errorMessage = "GitHub sign-in was cancelled. You can try again.")
                } catch (error: CancellationException) {
                    _uiState.value = LoginUiState()
                    throw error
                } catch (error: Exception) { showLoginFailure(error) }
            }
        }
    }

    fun showLoginFailure(error: Throwable?) {
        _uiState.value = LoginUiState(errorMessage = loginFailureMessage(error))
    }

    fun clearLoginError() {
        if (_uiState.value.errorMessage != null) _uiState.value = LoginUiState()
    }

    private fun loginFailureMessage(error: Throwable?): String {
        val diagnostic = safeAuthFailureCategory(error)
        val reason = when {
            error is android.content.ActivityNotFoundException -> "No browser is available. Install or enable a browser, then try again."
            diagnostic == "AUTH_001_APPLICATION_CONFIG_MISSING" || diagnostic == "AUTH_011_BACKEND_CONFIG_MISSING" -> "GitHub sign-in is not configured in this app build. Contact the developer."
            diagnostic == "AUTH_005_PROVIDER_REJECTED" -> "GitHub authorization was cancelled or denied. Please try again."
            diagnostic in setOf("AUTH_002_NO_PENDING_LOGIN", "AUTH_003_MISSING_STATE", "AUTH_004_STATE_MISMATCH", "AUTH_006_CODE_MISSING") -> "The sign-in response expired or could not be validated. Start sign-in again."
            diagnostic.contains("TRANSPORT") || error is java.io.IOException -> "Unable to connect. Check your internet connection and try again."
            diagnostic.startsWith("AUTH_012_BACKEND_HTTP_") -> "The sign-in server rejected the request (HTTP ${diagnostic.substringAfterLast('_')}). Please try again."
            diagnostic.startsWith("AUTH_009_PROFILE_HTTP_") -> "Your GitHub profile could not be loaded (HTTP ${diagnostic.substringAfterLast('_')}). Please sign in again."
            diagnostic == "AUTH_015_TOKEN_STORE_FAILED" -> "The session could not be saved securely. Please try again."
            diagnostic.contains("PARSE") || diagnostic.contains("TOKEN_MISSING") -> "The sign-in service returned an invalid response. Please try again later."
            else -> "Sign-in could not finish. Please try again."
        }
        return if (BuildConfig.DEBUG) {
            "GitHub sign-in could not be completed. $reason\nDiagnostic: $diagnostic"
        } else {
            "GitHub sign-in could not be completed. $reason"
        }
    }
}
