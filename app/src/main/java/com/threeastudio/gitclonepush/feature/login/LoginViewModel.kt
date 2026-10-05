package com.threeastudio.gitclonepush.feature.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.threeastudio.gitclonepush.domain.repository.AuthRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class LoginViewModel(private val authRepository: AuthRepository) : ViewModel() {
    private val _uiState = MutableStateFlow(LoginUiState())
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    fun continueWithGitHub(onOpenBrowser: (android.net.Uri) -> Unit) {
        if (_uiState.value.isSigningIn) return
        viewModelScope.launch {
            _uiState.value = LoginUiState(isSigningIn = true)
            authRepository.beginLogin().onSuccess { onOpenBrowser(it.authorizationUri); _uiState.value = LoginUiState() }.onFailure { _uiState.value = LoginUiState(errorMessage = it.message ?: "AUTH_010_LOGIN_FAILED: Could not start login.") }
        }
    }
}
