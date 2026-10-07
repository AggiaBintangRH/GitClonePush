package com.threeastudio.gitclonepush.feature.repository

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.threeastudio.gitclonepush.core.model.GitCommitSummary
import com.threeastudio.gitclonepush.domain.repository.GitHistoryReader
import com.threeastudio.gitclonepush.domain.repository.LocalRepositoryStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class HistoryUiState(
    val isLoading: Boolean = true,
    val isLoadingMore: Boolean = false,
    val commits: List<GitCommitSummary> = emptyList(),
    val hasMore: Boolean = false,
    val errorMessage: String? = null
)

class HistoryViewModel(private val repositoryId: String, private val localStore: LocalRepositoryStore, private val reader: GitHistoryReader) : ViewModel() {
    private val _uiState = MutableStateFlow(HistoryUiState())
    val uiState: StateFlow<HistoryUiState> = _uiState.asStateFlow()
    private var loadJob: kotlinx.coroutines.Job? = null
    init { load() }

    fun load() {
        if (loadJob?.isActive == true) return
        loadJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
            try {
                val repository = localStore.listRepositories().firstOrNull { it.id == repositoryId } ?: error("repository")
                val page = reader.loadPage(repository, 0, PAGE_SIZE)
                _uiState.value = HistoryUiState(false, false, page.commits, page.hasMore)
            } catch (error: CancellationException) { throw error }
            catch (_: Throwable) { _uiState.value = _uiState.value.copy(isLoading = false, errorMessage = "Commit history could not be loaded.") }
            finally { loadJob = null }
        }
    }

    fun loadMore() {
        val snapshot = _uiState.value
        if (loadJob?.isActive == true || snapshot.isLoading || snapshot.isLoadingMore || !snapshot.hasMore) return
        loadJob = viewModelScope.launch {
            _uiState.value = snapshot.copy(isLoadingMore = true, errorMessage = null)
            try {
                val repository = localStore.listRepositories().firstOrNull { it.id == repositoryId } ?: error("repository")
                val page = reader.loadPage(repository, snapshot.commits.size, PAGE_SIZE)
                val known = snapshot.commits.mapTo(mutableSetOf()) { it.id }
                _uiState.value = _uiState.value.copy(isLoadingMore = false, commits = _uiState.value.commits + page.commits.filter { known.add(it.id) }, hasMore = page.hasMore)
            } catch (error: CancellationException) { throw error }
            catch (_: Throwable) { _uiState.value = _uiState.value.copy(isLoadingMore = false, errorMessage = "More history could not be loaded.") }
            finally { loadJob = null }
        }
    }
    private companion object { const val PAGE_SIZE = 50 }
}

class HistoryViewModelFactory(private val repositoryId: String, private val localStore: LocalRepositoryStore, private val reader: GitHistoryReader) : androidx.lifecycle.ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = HistoryViewModel(repositoryId, localStore, reader) as T
}
