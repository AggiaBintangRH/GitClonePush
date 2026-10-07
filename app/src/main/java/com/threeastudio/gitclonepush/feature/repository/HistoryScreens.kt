package com.threeastudio.gitclonepush.feature.repository

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedButton
import com.threeastudio.gitclonepush.core.designsystem.components.SectionCard
import com.threeastudio.gitclonepush.core.designsystem.components.EmptyState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.threeastudio.gitclonepush.core.designsystem.components.ErrorState
import com.threeastudio.gitclonepush.core.designsystem.components.GitTopAppBar
import com.threeastudio.gitclonepush.core.designsystem.components.LoadingState
import com.threeastudio.gitclonepush.core.model.*
import com.threeastudio.gitclonepush.domain.repository.*
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@Composable
fun HistoryScreen(repositoryId: String, onBack: () -> Unit, onCommit: (String) -> Unit, localStore: LocalRepositoryStore, reader: GitHistoryReader, viewModel: HistoryViewModel = viewModel(factory = HistoryViewModelFactory(repositoryId, localStore, reader))) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    Scaffold(topBar = { GitTopAppBar("History", onBack, showBack = true) }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            when {
                state.isLoading -> LoadingState("Loading history…")
                state.errorMessage != null && state.commits.isEmpty() -> ErrorState(state.errorMessage ?: "History error")
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(state.commits, key = { it.id }) { commit ->
                        OutlinedCard(Modifier.fillMaxWidth().clickable { onCommit(commit.id) }) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    commit.message.lineSequence().firstOrNull().orEmpty(),
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    "${commit.shortId} · ${commit.authorName.orEmpty()} · ${formatTime(commit.authoredAt)}${if (commit.parentCount > 1) " · Merge" else ""}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    if (state.errorMessage != null) item { ErrorState(state.errorMessage.orEmpty()) }
                    if (state.hasMore) item {
                        if (state.isLoadingMore) {
                            LoadingState()
                        } else {
                            Button(onClick = viewModel::loadMore, modifier = Modifier.fillMaxWidth()) {
                                Text("Load more")
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatTime(value: java.time.Instant) = DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneId.systemDefault()).format(value)

data class CommitDetailUiState(val isLoading: Boolean = true, val detail: GitCommitDetail? = null, val errorMessage: String? = null)
class CommitDetailViewModel(private val repositoryId: String, private val commitId: String, private val localStore: LocalRepositoryStore, private val reader: GitHistoryReader) : androidx.lifecycle.ViewModel() {
    private val _uiState = kotlinx.coroutines.flow.MutableStateFlow(CommitDetailUiState())
    val uiState: kotlinx.coroutines.flow.StateFlow<CommitDetailUiState> = _uiState.asStateFlow()
    init { viewModelScope.launch { try { val repo = localStore.listRepositories().first { it.id == repositoryId }; _uiState.value = CommitDetailUiState(false, reader.loadDetail(repo, commitId)) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Throwable) { _uiState.value = CommitDetailUiState(false, errorMessage = "Commit detail could not be loaded.") } } }
}
class CommitDetailViewModelFactory(private val repositoryId: String, private val commitId: String, private val localStore: LocalRepositoryStore, private val reader: GitHistoryReader) : androidx.lifecycle.ViewModelProvider.Factory { @Suppress("UNCHECKED_CAST") override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T = CommitDetailViewModel(repositoryId, commitId, localStore, reader) as T }

@Composable
fun CommitDetailScreen(repositoryId: String, commitId: String, onBack: () -> Unit, onFile: (String, String?) -> Unit, localStore: LocalRepositoryStore, reader: GitHistoryReader, viewModel: CommitDetailViewModel = viewModel(factory = CommitDetailViewModelFactory(repositoryId, commitId, localStore, reader))) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    Scaffold(topBar = { GitTopAppBar("Commit detail", onBack, showBack = true) }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            when {
                state.isLoading -> LoadingState("Loading commit…")
                state.detail == null -> ErrorState(state.errorMessage ?: "Commit detail error")
                else -> state.detail?.let { detail ->
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        item {
                            SectionCard(detail.shortId) {
                                Text(detail.message, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "Author: ${detail.authorName.orEmpty()} <${detail.authorEmail.orEmpty()}>",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    "Parents: ${if (detail.parents.isEmpty()) "Root commit" else detail.parents.joinToString().plus(if (detail.parents.size > 1) " (diff is against first parent)" else "")}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        items(detail.changedFiles, key = { it.path }) { file ->
                            OutlinedButton(onClick = { onFile(file.path, detail.diffParentId) }, modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(file.path, style = MaterialTheme.typography.bodyLarge)
                                    Text(
                                        "${file.changeType} · +${file.additions ?: "?"} -${file.deletions ?: "?"}${if (file.isBinary) " · Binary" else ""}",
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DiffScreen(title: String, onBack: () -> Unit, localStore: LocalRepositoryStore, reader: GitDiffReader, repositoryId: String, request: DiffRequest, viewModel: DiffViewModel = viewModel(factory = DiffViewModelFactory(repositoryId, localStore, reader, request))) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    Scaffold(topBar = { GitTopAppBar(title, onBack, showBack = true) }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            when {
                state.isLoading -> LoadingState("Loading diff…")
                state.errorMessage != null -> ErrorState(state.errorMessage ?: "Diff error")
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (state.result?.files.isNullOrEmpty()) item { EmptyState("No differences to display.") }
                    items(state.result?.files.orEmpty(), key = { "${it.oldPath}:${it.newPath}" }) { file -> DiffFile(file) }
                }
            }
        }
    }
}

@Composable
private fun DiffFile(file: FileDiff) {
    SectionCard("${file.changeType} · ${file.newPath ?: file.oldPath.orEmpty()}") {
        if (file.isBinary) Text("Binary file changed")
        else file.hunks.forEach { hunk ->
            Text(
                "@@ -${hunk.oldStart},${hunk.oldCount} +${hunk.newStart},${hunk.newCount} @@",
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
            Column(Modifier.horizontalScroll(rememberScrollState())) {
                hunk.lines.forEach { line ->
                    val prefix = when (line.type) {
                        DiffLineType.ADDED -> "+"
                        DiffLineType.REMOVED -> "-"
                        DiffLineType.CONTEXT -> " "
                    }
                    Text(
                        prefix + line.text,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodyMedium,
                        color = when (line.type) {
                            DiffLineType.ADDED -> MaterialTheme.colorScheme.tertiary
                            DiffLineType.REMOVED -> MaterialTheme.colorScheme.error
                            DiffLineType.CONTEXT -> MaterialTheme.colorScheme.onSurface
                        }
                    )
                }
            }
        }
        if (file.truncated) Text("Diff truncated safely.", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

data class DiffUiState(val isLoading: Boolean = true, val result: DiffResult? = null, val errorMessage: String? = null)
class DiffViewModel(private val repositoryId: String, private val localStore: LocalRepositoryStore, private val reader: GitDiffReader, private val request: DiffRequest) : androidx.lifecycle.ViewModel() {
    private val _uiState = kotlinx.coroutines.flow.MutableStateFlow(DiffUiState())
    val uiState: kotlinx.coroutines.flow.StateFlow<DiffUiState> = _uiState.asStateFlow()
    init { viewModelScope.launch { try { val repo = localStore.listRepositories().first { it.id == repositoryId }; _uiState.value = DiffUiState(false, reader.read(repo, request)) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Throwable) { _uiState.value = DiffUiState(false, errorMessage = "The diff could not be loaded.") } } }
}
class DiffViewModelFactory(private val repositoryId: String, private val localStore: LocalRepositoryStore, private val reader: GitDiffReader, private val request: DiffRequest) : androidx.lifecycle.ViewModelProvider.Factory { @Suppress("UNCHECKED_CAST") override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T = DiffViewModel(repositoryId, localStore, reader, request) as T }
