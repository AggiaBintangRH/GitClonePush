package com.threeastudio.gitclonepush.feature.repositories

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.threeastudio.gitclonepush.core.designsystem.components.EmptyState
import com.threeastudio.gitclonepush.core.designsystem.components.ErrorState
import com.threeastudio.gitclonepush.core.designsystem.components.GitTopAppBar
import com.threeastudio.gitclonepush.core.designsystem.components.LoadingState
import com.threeastudio.gitclonepush.core.designsystem.components.RepositoryCard

@Composable
fun RepositoryListScreen(username: String, onRepositoryClick: (String) -> Unit, onSettingsClick: () -> Unit, repositoryRepository: com.threeastudio.gitclonepush.domain.repository.RepositoryRepository, repositoryCloner: com.threeastudio.gitclonepush.domain.repository.RepositoryCloner, localRepositoryStore: com.threeastudio.gitclonepush.domain.repository.LocalRepositoryStore, viewModel: RepositoryListViewModel = viewModel(factory = RepositoryListViewModelFactory(repositoryRepository, repositoryCloner, localRepositoryStore))) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val errorMessage = state.errorMessage
    Scaffold(topBar = { GitTopAppBar("Repositories", onSettingsClick) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp)) {
            androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.AccountCircle, username.ifBlank { "GitHub user" }, modifier = Modifier.padding(top = 14.dp))
                OutlinedTextField(state.query, viewModel::updateQuery, Modifier.weight(1f), placeholder = { Text("Search repositories") }, leadingIcon = { Icon(Icons.Default.Search, null) }, singleLine = true)
            }
            androidx.compose.foundation.layout.Row(Modifier.padding(vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RepositoryFilter.entries.forEach { filter -> FilterChip(selected = state.filter == filter, onClick = { viewModel.selectFilter(filter) }, label = { Text(filter.name.lowercase().replaceFirstChar { it.uppercase() }) }) }
            }
            when {
                state.isLoading -> LoadingState("Loading repositories…")
                errorMessage != null -> ErrorState(errorMessage ?: "Unable to load repositories.")
                state.visibleRepositories.isEmpty() -> EmptyState("No repositories match your search.")
                else -> LazyColumn(contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { items(state.visibleRepositories, key = { it.id }) { repository -> RepositoryCard(repository, state.cloneStates[repository.id] ?: com.threeastudio.gitclonepush.core.model.RepositoryCloneState(), { onRepositoryClick(repository.id) }, { viewModel.clone(repository.id) }) } }
            }
        }
    }
}

class RepositoryListViewModelFactory(private val repositoryRepository: com.threeastudio.gitclonepush.domain.repository.RepositoryRepository, private val repositoryCloner: com.threeastudio.gitclonepush.domain.repository.RepositoryCloner, private val localRepositoryStore: com.threeastudio.gitclonepush.domain.repository.LocalRepositoryStore) : androidx.lifecycle.ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST") override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T = RepositoryListViewModel(repositoryRepository, repositoryCloner, localRepositoryStore) as T
}
