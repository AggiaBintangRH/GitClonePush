package com.threeastudio.gitclonepush.feature.repositories

import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.ui.platform.testTag
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.threeastudio.gitclonepush.core.designsystem.components.EmptyState
import com.threeastudio.gitclonepush.core.designsystem.components.ErrorState
import com.threeastudio.gitclonepush.core.designsystem.components.GitTopAppBar
import com.threeastudio.gitclonepush.core.designsystem.components.LoadingState
import com.threeastudio.gitclonepush.core.designsystem.components.RepositoryCard
import com.threeastudio.gitclonepush.core.model.CloneStatus
import com.threeastudio.gitclonepush.data.github.RepositoryDiagnostics
import com.threeastudio.gitclonepush.domain.repository.LocalRepositoryStore
import com.threeastudio.gitclonepush.domain.repository.LocalRepositoryValidator
import com.threeastudio.gitclonepush.domain.repository.RepositoryCloner
import com.threeastudio.gitclonepush.domain.repository.RepositoryRepository
import com.threeastudio.gitclonepush.domain.repository.CloneDestinationSelector

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RepositoryListScreen(
    username: String,
    onRepositoryClick: (String) -> Unit,
    onSettingsClick: () -> Unit,
    repositoryRepository: RepositoryRepository,
    repositoryCloner: RepositoryCloner,
    localRepositoryStore: LocalRepositoryStore,
    diagnostics: RepositoryDiagnostics,
    localRepositoryValidator: LocalRepositoryValidator,
    cloneDestinationSelector: CloneDestinationSelector? = null,
    viewModel: RepositoryListViewModel = viewModel(
        factory = RepositoryListViewModelFactory(repositoryRepository, repositoryCloner, localRepositoryStore, diagnostics, localRepositoryValidator, cloneDestinationSelector)
    )
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val errorMessage = state.errorMessage
    val snackbarHostState = remember { SnackbarHostState() }
    val refreshState = rememberPullToRefreshState()
    val scope = rememberCoroutineScope()
    val pickCloneFolder = rememberCloneFolderPicker(
        onFolderSelected = { id, uri -> viewModel.clone(id, uri) },
        onError = { message -> scope.launch { snackbarHostState.showSnackbar(message) } }
    )
    var showAddRepository by remember { androidx.compose.runtime.mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(state.refreshErrorMessage) {
        state.refreshErrorMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeRefreshError()
        }
    }

    Scaffold(
        topBar = {
            GitTopAppBar(
                "Repositories",
                onSettingsClick = onSettingsClick,
                onActionClick = { showAddRepository = true },
                actionContentDescription = "Add repository"
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.isRefreshing,
            onRefresh = viewModel::refresh,
            state = refreshState,
            modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize().testTag("repository-list"),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item(key = "repository-search") {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.AccountCircle, username.ifBlank { "GitHub user" }, modifier = Modifier.padding(top = 14.dp), tint = MaterialTheme.colorScheme.primary)
                        OutlinedTextField(
                            value = state.query,
                            onValueChange = viewModel::updateQuery,
                            modifier = Modifier.weight(1f),
                            placeholder = { Text("Search repositories") },
                            leadingIcon = { Icon(Icons.Default.Search, null) },
                            singleLine = true
                        )
                    }
                }
                item(key = "repository-filters") {
                    Row(Modifier.padding(bottom = 4.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        RepositoryFilter.entries.forEach { filter ->
                            FilterChip(
                                selected = state.filter == filter,
                                onClick = { viewModel.selectFilter(filter) },
                                label = { Text(filter.name.lowercase().replaceFirstChar { it.uppercase() }) }
                            )
                        }
                    }
                }
                when {
                    state.isInitialLoading -> item(key = "repositories-loading") { LoadingState("Loading repositories…") }
                    errorMessage != null -> item(key = "repositories-error") { ErrorState(errorMessage, viewModel::retryInitialLoad) }
                    state.visibleRepositories.isNotEmpty() || state.activeClones.isNotEmpty() -> {
                        items(state.activeClones + state.visibleRepositories, key = { it.id }) { repository ->
                            val cloneState = state.cloneStates[repository.id]
                                ?: com.threeastudio.gitclonepush.core.model.RepositoryCloneState()
                            RepositoryCard(
                                repository = repository,
                                cloneState = cloneState,
                                onClick = {
                                    if (cloneState.status == CloneStatus.CLONED) onRepositoryClick(repository.id)
                                },
                                onCloneClick = { pickCloneFolder(repository.id) }
                            )
                        }
                    }
                    else -> item(key = "repositories-empty") {
                        EmptyState(
                            when {
                                state.cloneStates.values.none { it.status == CloneStatus.CLONED } ->
                                    "No cloned repositories yet. Use + to choose a repository and a destination folder."
                                state.repositories.isEmpty() -> "No repositories found."
                                else -> "No repositories match your search."
                            }
                        )
                    }
                }
            }
        }
    }
    if (showAddRepository) {
        AddRepositoryDialog(
            repositories = state.repositories.filter { state.cloneStates[it.id]?.status !in setOf(CloneStatus.CLONED, CloneStatus.CLONING) },
            onClone = { repositoryId ->
                pickCloneFolder(repositoryId)
                showAddRepository = false
            },
            onDismiss = { showAddRepository = false }
        )
    }
}

@Composable
private fun AddRepositoryDialog(
    repositories: List<com.threeastudio.gitclonepush.core.model.GitRepository>,
    onClone: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var query by rememberSaveable { androidx.compose.runtime.mutableStateOf("") }
    val matches = remember(query, repositories) {
        repositories.filter { "${it.owner}/${it.name}".contains(query, ignoreCase = true) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add repository") },
        text = {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp).testTag("add-repository-list"),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (repositories.isEmpty()) {
                    item { Text("All available repositories are already cloned.") }
                } else {
                    item {
                        Text("Choose a repository, then select a parent folder. A folder with the repository name will be created there.")
                    }
                    item {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Search GitHub repositories") },
                            singleLine = true
                        )
                    }
                    if (matches.isEmpty()) item { Text("No repositories match your search.") }
                    items(matches, key = { it.id }) { repository ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text(repository.name, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "${repository.owner} · ${if (repository.visibility == com.threeastudio.gitclonepush.core.model.RepositoryVisibility.PRIVATE) "Private" else "Public"}",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            Button(onClick = { onClone(repository.id) }) { Text("Clone") }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

class RepositoryListViewModelFactory(
    private val repositoryRepository: RepositoryRepository,
    private val repositoryCloner: RepositoryCloner,
    private val localRepositoryStore: LocalRepositoryStore,
    private val diagnostics: RepositoryDiagnostics,
    private val localRepositoryValidator: LocalRepositoryValidator,
    private val cloneDestinationSelector: CloneDestinationSelector? = null
) : androidx.lifecycle.ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
        RepositoryListViewModel(repositoryRepository, repositoryCloner, localRepositoryStore, diagnostics, localRepositoryValidator, cloneDestinationSelector) as T
}
