package com.threeastudio.gitclonepush.feature.repository

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.threeastudio.gitclonepush.core.model.RepositoryFileContent
import com.threeastudio.gitclonepush.core.model.RepositoryFileEntry
import com.threeastudio.gitclonepush.domain.repository.RepositoryFileReader
import com.threeastudio.gitclonepush.domain.repository.RepositoryFilesException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import com.threeastudio.gitclonepush.domain.repository.RepositoryFilesErrorCategory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class RepositoryFilesUiState(
    val currentPath: String = "",
    val entries: List<RepositoryFileEntry> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val openedFilePath: String? = null,
    val openedContent: RepositoryFileContent? = null
)

class RepositoryFilesViewModel(
    private val repositoryId: String,
    private val reader: RepositoryFileReader
) : ViewModel() {
    private val _uiState = MutableStateFlow(RepositoryFilesUiState(isLoading = true))
    val uiState: StateFlow<RepositoryFilesUiState> = _uiState.asStateFlow()
    private var readJob: Job? = null
    private var requestId = 0L

    init { refresh() }

    fun openDirectory(path: String) {
        if (path == _uiState.value.currentPath && !_uiState.value.isLoading) return
        load(path)
    }

    fun navigateUp() {
        val current = _uiState.value.currentPath
        if (current.isBlank()) return
        openDirectory(current.substringBeforeLast('/', missingDelimiterValue = ""))
    }

    fun refresh() = load(_uiState.value.currentPath)

    fun openFile(path: String) {
        startRead(_uiState.value.copy(openedFilePath = path, openedContent = null)) {
            val content = reader.readFile(repositoryId, path)
            _uiState.value.copy(openedContent = content)
        }
    }

    private fun load(path: String) {
        startRead(_uiState.value.copy(currentPath = path, entries = emptyList(), openedFilePath = null, openedContent = null)) {
            val entries = reader.listDirectory(repositoryId, path)
            _uiState.value.copy(entries = entries)
        }
    }

    private fun startRead(state: RepositoryFilesUiState, read: suspend () -> RepositoryFilesUiState) {
        val currentRequest = ++requestId
        readJob?.cancel()
        _uiState.value = state.copy(isLoading = true, error = null)
        readJob = viewModelScope.launch {
            try {
                val result = read()
                if (currentRequest == requestId) _uiState.value = result
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                if (currentRequest == requestId) {
                    val category = (error as? RepositoryFilesException)?.category ?: RepositoryFilesErrorCategory.READ_FAILED
                    _uiState.value = _uiState.value.copy(error = category.name)
                }
            } finally {
                if (currentRequest == requestId) _uiState.value = _uiState.value.copy(isLoading = false)
            }
        }
    }
}

class RepositoryFilesViewModelFactory(
    private val repositoryId: String,
    private val reader: RepositoryFileReader
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = RepositoryFilesViewModel(repositoryId, reader) as T
}
