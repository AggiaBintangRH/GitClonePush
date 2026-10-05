package com.threeastudio.gitclonepush.feature.repository

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.threeastudio.gitclonepush.core.model.CommitRequest
import com.threeastudio.gitclonepush.domain.repository.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class RepositoryViewModel(
    private val repositoryId: String,
    private val localStore: LocalRepositoryStore,
    private val reader: GitRepositoryReader,
    private val mutator: GitRepositoryMutator,
    private val synchronizer: GitRemoteSynchronizer,
    private val identityStore: GitAuthorIdentityStore
) : ViewModel() {
    private val _uiState = MutableStateFlow(RepositoryUiState())
    val uiState: StateFlow<RepositoryUiState> = _uiState.asStateFlow()
    init { refresh() }
    fun refresh() = viewModelScope.launch {
        _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
        try {
            val repository = localStore.listRepositories().firstOrNull { it.id == repositoryId } ?: error("REPOSITORY_004_NOT_CLONED: Clone this repository before opening its workspace.")
            val status = reader.status(repository); val branch = reader.currentBranch(repository); val commits = reader.recentCommits(repository, 20)
            _uiState.value = _uiState.value.copy(isLoading = false, localRepository = repository, status = status, branch = branch, commits = commits)
        } catch (error: Throwable) { _uiState.value = _uiState.value.copy(isLoading = false, errorMessage = error.message ?: "REPOSITORY_005_READ_FAILED: Could not read repository.") }
    }
    fun commit(message: String, push: Boolean) = viewModelScope.launch {
        val repository = _uiState.value.localRepository ?: return@launch
        val identity = identityStore.read() ?: run { _uiState.value = _uiState.value.copy(errorMessage = "GIT_003_IDENTITY_REQUIRED: Configure Git author name and email in Settings."); return@launch }
        if (message.isBlank()) return@launch
        try {
            mutator.stageAll(repository); mutator.commit(repository, CommitRequest(message, identity.name, identity.email))
            if (push) { synchronizer.push(repository); _uiState.value = _uiState.value.copy(operationMessage = "Commit and push completed.") } else _uiState.value = _uiState.value.copy(operationMessage = "Commit completed.")
            refresh()
        } catch (error: Throwable) { _uiState.value = _uiState.value.copy(errorMessage = error.message ?: "GIT_004_COMMIT_FAILED: Commit operation failed.") }
    }
    fun pull() = viewModelScope.launch {
        _uiState.value.localRepository?.let { repository ->
            try { synchronizer.pull(repository); refresh() } catch (error: Throwable) { _uiState.value = _uiState.value.copy(errorMessage = error.message ?: "GIT_005_PULL_FAILED: Pull failed.") }
        }
    }
    fun push() = viewModelScope.launch {
        _uiState.value.localRepository?.let { repository ->
            try { synchronizer.push(repository); refresh() } catch (error: Throwable) { _uiState.value = _uiState.value.copy(errorMessage = error.message ?: "GIT_006_PUSH_FAILED: Push failed.") }
        }
    }
}
