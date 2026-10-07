package com.threeastudio.gitclonepush.feature.repositories

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.threeastudio.gitclonepush.core.model.CloneProgress
import com.threeastudio.gitclonepush.core.model.CloneStatus
import com.threeastudio.gitclonepush.core.model.RepositoryCloneState
import com.threeastudio.gitclonepush.data.github.NoOpRepositoryDiagnostics
import com.threeastudio.gitclonepush.data.github.RepositoryDiagnosticEvent
import com.threeastudio.gitclonepush.data.github.RepositoryDiagnostics
import com.threeastudio.gitclonepush.domain.repository.CloneRepositoryRequest
import com.threeastudio.gitclonepush.domain.repository.CloneErrorCategory
import com.threeastudio.gitclonepush.domain.repository.CloneOperationException
import com.threeastudio.gitclonepush.domain.repository.LocalRepositoryStore
import com.threeastudio.gitclonepush.domain.repository.LocalRepositoryValidator
import com.threeastudio.gitclonepush.domain.repository.RepositoryCloner
import com.threeastudio.gitclonepush.domain.repository.RepositoryDataException
import com.threeastudio.gitclonepush.domain.repository.RepositoryErrorCategory
import com.threeastudio.gitclonepush.domain.repository.CloneDestinationSelector
import com.threeastudio.gitclonepush.domain.repository.CloneDestinationException
import com.threeastudio.gitclonepush.domain.repository.CloneDestinationError
import com.threeastudio.gitclonepush.domain.usecase.LoadRepositoriesUseCase
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class RepositoryListViewModel(
    repositoryRepository: com.threeastudio.gitclonepush.domain.repository.RepositoryRepository,
    private val repositoryCloner: RepositoryCloner,
    private val localRepositoryStore: LocalRepositoryStore,
    private val diagnostics: RepositoryDiagnostics = NoOpRepositoryDiagnostics,
    private val localRepositoryValidator: LocalRepositoryValidator = LocalRepositoryValidator { true },
    private val cloneDestinationSelector: CloneDestinationSelector? = null
) : ViewModel() {
    private val loadRepositories = LoadRepositoriesUseCase(repositoryRepository, localRepositoryStore, localRepositoryValidator)
    private val _uiState = MutableStateFlow(RepositoryListUiState())
    val uiState: StateFlow<RepositoryListUiState> = _uiState.asStateFlow()
    private var loadJob: Job? = null
    private val activeCloneIds = mutableSetOf<String>()

    init { load(initial = true) }

    fun updateQuery(query: String) { _uiState.value = _uiState.value.copy(query = query) }
    fun selectFilter(filter: RepositoryFilter) { _uiState.value = _uiState.value.copy(filter = filter) }

    fun refresh() {
        val state = _uiState.value
        if (state.isInitialLoading || state.isRefreshing || loadJob?.isActive == true) return
        load(initial = false)
    }

    fun consumeRefreshError() { _uiState.value = _uiState.value.copy(refreshErrorMessage = null) }

    fun retryInitialLoad() {
        if (!_uiState.value.isInitialLoading) load(initial = true)
    }

    fun clone(repositoryId: String, parentTreeUri: String? = null) {
        val current = _uiState.value.cloneStates[repositoryId]
        if (current?.status == CloneStatus.CLONING || current?.status == CloneStatus.CLONED) return
        if (!activeCloneIds.add(repositoryId)) return
        viewModelScope.launch {
            try {
                // Activity-result delivery can precede the catalog load after process recreation.
                if (_uiState.value.isInitialLoading) loadJob?.join()
                if (_uiState.value.cloneStates[repositoryId]?.status == CloneStatus.CLONED) return@launch
                val repository = _uiState.value.repositories.firstOrNull { it.id == repositoryId }
                    ?: throw CloneOperationException(CloneErrorCategory.INVALID_REPOSITORY)
                updateClone(repositoryId, RepositoryCloneState(CloneStatus.CLONING, 0))
                val destination = if (cloneDestinationSelector != null) {
                    val selected = parentTreeUri ?: throw CloneDestinationException(CloneDestinationError.INVALID_FOLDER)
                    cloneDestinationSelector.select(repository, selected)
                } else {
                    localRepositoryStore.repositoryDirectory(repository.id).absolutePath
                }
                repositoryCloner.clone(
                    CloneRepositoryRequest(repository, destination)
                ).collect { progress ->
                    when (progress) {
                        CloneProgress.Preparing -> updateClone(repositoryId, RepositoryCloneState(CloneStatus.CLONING, 0))
                        is CloneProgress.Receiving -> updateClone(repositoryId, RepositoryCloneState(CloneStatus.CLONING, progress.percent ?: 0))
                        is CloneProgress.Completed -> updateClone(repositoryId, RepositoryCloneState(CloneStatus.CLONED, 100))
                    }
                }
            } catch (error: CancellationException) {
                updateClone(repositoryId, RepositoryCloneState(CloneStatus.NOT_CLONED))
                throw error
            } catch (error: Throwable) {
                updateClone(repositoryId, RepositoryCloneState(CloneStatus.ERROR, errorMessage = cloneErrorMessage(error)))
            } finally {
                activeCloneIds.remove(repositoryId)
            }
        }
    }

    private fun load(initial: Boolean) {
        loadJob?.cancel()
        _uiState.value = _uiState.value.copy(
            isInitialLoading = initial,
            isRefreshing = !initial,
            errorMessage = if (initial) null else _uiState.value.errorMessage,
            refreshErrorMessage = null
        )
        diagnostics.event(
            if (initial) RepositoryDiagnosticEvent.REPO_INITIAL_LOAD_STARTED else RepositoryDiagnosticEvent.REPO_REFRESH_STARTED
        )
        loadJob = viewModelScope.launch {
            runCatching { loadRepositories() }
                .onSuccess { result ->
                    val nextCloneStates = reconcileCloneStates(result.clonedRepositoryIds)
                    diagnostics.event(
                        RepositoryDiagnosticEvent.REPO_LOCAL_STATE_RECONCILED,
                        mapOf("remoteCount" to result.repositories.size.toString(), "clonedCount" to result.clonedRepositoryIds.size.toString())
                    )
                    _uiState.value = _uiState.value.copy(
                        isInitialLoading = false,
                        isRefreshing = false,
                        repositories = result.repositories,
                        cloneStates = nextCloneStates,
                        errorMessage = null,
                        refreshErrorMessage = null
                    )
                    if (!initial) diagnostics.event(RepositoryDiagnosticEvent.REPO_REFRESH_SUCCESS, mapOf("total" to result.repositories.size.toString()))
                }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    val message = repositoryErrorMessage(error)
                    if (initial) {
                        _uiState.value = _uiState.value.copy(isInitialLoading = false, isRefreshing = false, errorMessage = message)
                    } else {
                        _uiState.value = _uiState.value.copy(isRefreshing = false, refreshErrorMessage = message)
                        diagnostics.event(RepositoryDiagnosticEvent.REPO_REFRESH_FAILED, mapOf("category" to repositoryErrorCategory(error)))
                    }
                }
        }
    }

    private fun reconcileCloneStates(clonedIds: Set<String>): Map<String, RepositoryCloneState> =
        _uiState.value.cloneStates.toMutableMap().apply {
            _uiState.value.repositories.forEach { repository ->
                if (repository.id in clonedIds) {
                    this[repository.id] = RepositoryCloneState(CloneStatus.CLONED, 100)
                } else if (this[repository.id]?.status == CloneStatus.CLONED) {
                    this[repository.id] = RepositoryCloneState(CloneStatus.NOT_CLONED)
                }
            }
            clonedIds.forEach { id -> this[id] = RepositoryCloneState(CloneStatus.CLONED, 100) }
        }

    private fun repositoryErrorCategory(error: Throwable): String =
        (error as? RepositoryDataException)?.category?.name ?: RepositoryErrorCategory.UNKNOWN.name

    private fun repositoryErrorMessage(error: Throwable): String = when ((error as? RepositoryDataException)?.category) {
        RepositoryErrorCategory.AUTHENTICATION_REQUIRED -> "Your GitHub session has expired. Please sign in again."
        RepositoryErrorCategory.NETWORK_UNAVAILABLE -> "Could not reach GitHub. Check your internet connection."
        RepositoryErrorCategory.RATE_LIMITED -> "GitHub rate limit reached. Try again later."
        RepositoryErrorCategory.API_FAILURE -> "GitHub could not load your repositories right now."
        RepositoryErrorCategory.INVALID_RESPONSE -> "GitHub returned an invalid repository response."
        else -> "Could not load repositories."
    }

    private fun updateClone(id: String, state: RepositoryCloneState) {
        _uiState.value = _uiState.value.copy(cloneStates = _uiState.value.cloneStates + (id to state))
    }

    private fun cloneErrorMessage(error: Throwable): String {
        if (error is CloneDestinationException) return when (error.category) {
            CloneDestinationError.STORAGE_ACCESS_REQUIRED -> "Allow file access in Android Settings before cloning to this folder."
            CloneDestinationError.UNSUPPORTED_LOCATION -> "Choose a local device or SD card folder. Cloud folders cannot be used for a Git working tree."
            CloneDestinationError.INVALID_FOLDER -> "Choose a regular folder such as Documents/GitRepositories."
            CloneDestinationError.DESTINATION_EXISTS -> "A folder with this repository name already exists. Choose a different parent folder."
            CloneDestinationError.STORAGE_UNAVAILABLE -> "This folder is unavailable or cannot be written. Choose another local folder."
        }
        return when ((error as? CloneOperationException)?.category) {
        CloneErrorCategory.AUTHENTICATION_REQUIRED -> "Sign in to GitHub before cloning this repository."
        CloneErrorCategory.AUTHENTICATION_FAILED -> "GitHub rejected the credentials for this repository."
        CloneErrorCategory.NETWORK_UNAVAILABLE -> "Could not reach GitHub. Check your internet connection."
        CloneErrorCategory.DESTINATION_CONFLICT -> "This repository destination already contains unrelated files."
        CloneErrorCategory.STORAGE_FAILURE -> "The repository could not be stored on this device."
        CloneErrorCategory.INVALID_REPOSITORY -> "This repository is unavailable or could not be validated. Refresh the list and try again."
        CloneErrorCategory.GIT_TRANSPORT_FAILURE -> "GitHub could not complete the clone operation."
        CloneErrorCategory.CANCELLED -> "Clone cancelled."
        else -> "Could not clone this repository."
        }
    }
}
