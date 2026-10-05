package com.threeastudio.gitclonepush.feature.repositories

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.threeastudio.gitclonepush.core.model.CloneStatus
import com.threeastudio.gitclonepush.core.model.RepositoryCloneState
import com.threeastudio.gitclonepush.domain.repository.RepositoryRepository
import com.threeastudio.gitclonepush.domain.repository.RepositoryCloner
import com.threeastudio.gitclonepush.domain.repository.CloneRepositoryRequest
import com.threeastudio.gitclonepush.domain.repository.LocalRepositoryStore
import com.threeastudio.gitclonepush.core.model.CloneProgress
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class RepositoryListViewModel(private val repositoryRepository: RepositoryRepository, private val repositoryCloner: RepositoryCloner, private val localRepositoryStore: LocalRepositoryStore) : ViewModel() {
    private val _uiState = MutableStateFlow(RepositoryListUiState())
    val uiState: StateFlow<RepositoryListUiState> = _uiState.asStateFlow()

    init { loadRepositories() }

    fun updateQuery(query: String) { _uiState.value = _uiState.value.copy(query = query) }
    fun selectFilter(filter: RepositoryFilter) { _uiState.value = _uiState.value.copy(filter = filter) }

    fun clone(repositoryId: String) {
        val current = _uiState.value.cloneStates[repositoryId]
        if (current?.status == CloneStatus.CLONING || current?.status == CloneStatus.CLONED) return
        viewModelScope.launch {
            val repository = _uiState.value.repositories.firstOrNull { it.id == repositoryId } ?: return@launch
            try {
                repositoryCloner.clone(CloneRepositoryRequest(repository, localRepositoryStore.repositoryDirectory(repository.id).absolutePath)).collect { progress ->
                    when (progress) {
                        CloneProgress.Preparing -> updateClone(repositoryId, RepositoryCloneState(CloneStatus.CLONING, 0))
                        is CloneProgress.Receiving -> updateClone(repositoryId, RepositoryCloneState(CloneStatus.CLONING, progress.percent ?: 0))
                        is CloneProgress.Completed -> updateClone(repositoryId, RepositoryCloneState(CloneStatus.CLONED, 100))
                    }
                }
            } catch (error: Throwable) { updateClone(repositoryId, RepositoryCloneState(CloneStatus.ERROR, errorMessage = error.message ?: "CLONE_002_FAILED: Clone failed.")) }
        }
    }

    private fun loadRepositories() = viewModelScope.launch {
        _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
        runCatching { repositoryRepository.getRepositories() }
            .onSuccess { _uiState.value = _uiState.value.copy(isLoading = false, repositories = it) }
            .onFailure { _uiState.value = _uiState.value.copy(isLoading = false, errorMessage = "REPOSITORY_001_LOAD_FAILED: Could not load repositories.") }
    }

    private fun updateClone(id: String, state: RepositoryCloneState) {
        _uiState.value = _uiState.value.copy(cloneStates = _uiState.value.cloneStates + (id to state))
    }
}
