package com.threeastudio.gitclonepush.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.threeastudio.gitclonepush.core.model.GitAuthorIdentity
import com.threeastudio.gitclonepush.domain.repository.GitAuthorIdentityStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SettingsUiState(val authorName: String = "", val authorEmail: String = "", val saved: Boolean = false)
class SettingsViewModel(private val store: GitAuthorIdentityStore) : ViewModel() {
    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()
    init { viewModelScope.launch { store.read()?.let { _uiState.value = SettingsUiState(it.name, it.email) } } }
    fun updateName(value: String) { _uiState.value = _uiState.value.copy(authorName = value, saved = false) }
    fun updateEmail(value: String) { _uiState.value = _uiState.value.copy(authorEmail = value, saved = false) }
    fun save() = viewModelScope.launch { val state = _uiState.value; if (state.authorName.isNotBlank() && state.authorEmail.isNotBlank()) { store.write(GitAuthorIdentity(state.authorName, state.authorEmail)); _uiState.value = state.copy(saved = true) } }
}
