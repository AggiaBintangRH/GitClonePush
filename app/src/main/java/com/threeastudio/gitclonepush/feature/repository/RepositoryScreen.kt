package com.threeastudio.gitclonepush.feature.repository

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.threeastudio.gitclonepush.core.designsystem.components.CommitListItem
import com.threeastudio.gitclonepush.core.designsystem.components.FileChangeItem
import com.threeastudio.gitclonepush.core.designsystem.components.FileTreeItem
import com.threeastudio.gitclonepush.core.designsystem.components.GitTopAppBar
import com.threeastudio.gitclonepush.core.model.Commit
import com.threeastudio.gitclonepush.core.model.FileChange
import com.threeastudio.gitclonepush.core.model.FileChangeStatus
import com.threeastudio.gitclonepush.core.model.RepositoryFile
import com.threeastudio.gitclonepush.core.designsystem.components.EmptyState
import com.threeastudio.gitclonepush.core.designsystem.components.ErrorState
import com.threeastudio.gitclonepush.core.designsystem.components.LoadingState
import com.threeastudio.gitclonepush.domain.repository.*

@Composable
fun RepositoryScreen(repositoryId: String, onBack: () -> Unit, localStore: LocalRepositoryStore, reader: GitRepositoryReader, mutator: GitRepositoryMutator, synchronizer: GitRemoteSynchronizer, identityStore: GitAuthorIdentityStore, viewModel: RepositoryViewModel = viewModel(factory = RepositoryViewModelFactory(repositoryId, localStore, reader, mutator, synchronizer, identityStore))) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var selectedTab by remember { mutableIntStateOf(0) }
    var commitMessage by remember { mutableStateOf("") }
    val repositoryName = state.localRepository?.name ?: if (repositoryId == "repo-1") "diarization-engine" else repositoryId
    val tabs = listOf("Changes", "Files", "Commits")
    Scaffold(topBar = { GitTopAppBar(repositoryName, onBack, showBack = true) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp)) {
            when { state.isLoading -> LoadingState("Loading repository…"); state.errorMessage != null -> ErrorState(state.errorMessage ?: "Repository error"); else -> Unit }
            Text("${state.branch?.name ?: "main"}  ·  ${state.status.changes.size} changed files", modifier = Modifier.padding(bottom = 12.dp))
            TabRow(selectedTab) { tabs.forEachIndexed { index, tab -> Tab(selectedTab == index, { selectedTab = index }, text = { Text(tab) }) } }
            when (selectedTab) {
                0 -> ChangesTab(state.status.changes, commitMessage, { commitMessage = it }, { viewModel.commit(commitMessage, false) }, { viewModel.commit(commitMessage, true) }, viewModel::pull, viewModel::push)
                1 -> FilesTab()
                else -> CommitsTab(state.commits)
            }
        }
    }
}

@Composable private fun ChangesTab(changes: List<FileChange>, message: String, onMessageChange: (String) -> Unit, onCommit: () -> Unit, onCommitAndPush: () -> Unit, onPull: () -> Unit, onPush: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        LazyColumn(Modifier.weight(1f, false)) { items(changes) { FileChangeItem(it) } }
        OutlinedTextField(message, onMessageChange, Modifier.fillMaxWidth(), label = { Text("Commit message") }, singleLine = true)
        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) { Button(onCommit, enabled = message.isNotBlank(), modifier = Modifier.weight(1f)) { Text("Commit") }; Button(onCommitAndPush, enabled = message.isNotBlank(), modifier = Modifier.weight(1f)) { Text("Commit & Push") } }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) { Button(onPull, modifier = Modifier.weight(1f)) { Text("Pull") }; Button(onPush, modifier = Modifier.weight(1f)) { Text("Push") } }
    }
}
@Composable private fun FilesTab() { LazyColumn { items(listOf(RepositoryFile(".github/", true), RepositoryFile("src/", true), RepositoryFile("README.md", false), RepositoryFile("LICENSE", false), RepositoryFile("build.gradle.kts", false))) { file -> FileTreeItem(file) } } }
@Composable private fun CommitsTab(commits: List<com.threeastudio.gitclonepush.core.model.GitCommit>) { LazyColumn { items(commits) { commit -> CommitListItem(Commit(commit.message, commit.hash, commit.author, commit.time)) } } }

class RepositoryViewModelFactory(private val repositoryId: String, private val localStore: LocalRepositoryStore, private val reader: GitRepositoryReader, private val mutator: GitRepositoryMutator, private val synchronizer: GitRemoteSynchronizer, private val identityStore: GitAuthorIdentityStore) : androidx.lifecycle.ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST") override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T = RepositoryViewModel(repositoryId, localStore, reader, mutator, synchronizer, identityStore) as T
}
