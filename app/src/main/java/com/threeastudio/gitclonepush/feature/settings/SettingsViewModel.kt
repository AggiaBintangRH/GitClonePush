package com.threeastudio.gitclonepush.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.threeastudio.gitclonepush.domain.repository.AuthRepository
import com.threeastudio.gitclonepush.domain.repository.GitAuthorIdentityReader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

data class SettingsUiState(
    val authorName: String = "",
    val authorEmail: String = "",
    val isLoadingIdentity: Boolean = true,
    val isSigningOut: Boolean = false,
    val errorMessage: String? = null
)

class SettingsViewModel(
    private val identityReader: GitAuthorIdentityReader,
    private val authRepository: AuthRepository
) : ViewModel() {
    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            identityReader.observe().collect { identity ->
                _uiState.value = _uiState.value.copy(
                    authorName = identity?.name.orEmpty(),
                    authorEmail = identity?.email.orEmpty(),
                    isLoadingIdentity = false
                )
            }
        }
    }

    fun signOut() {
        if (_uiState.value.isSigningOut) return
        _uiState.value = _uiState.value.copy(isSigningOut = true, errorMessage = null)
        viewModelScope.launch {
            try {
                authRepository.logout()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                _uiState.value = _uiState.value.copy(errorMessage = "Could not sign out. Please try again.")
            } finally {
                _uiState.value = _uiState.value.copy(isSigningOut = false)
            }
        }
    }
}
