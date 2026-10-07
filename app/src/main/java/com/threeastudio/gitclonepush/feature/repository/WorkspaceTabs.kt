@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.threeastudio.gitclonepush.feature.repository

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.threeastudio.gitclonepush.core.designsystem.components.CommitListItem
import com.threeastudio.gitclonepush.core.designsystem.components.EmptyState
import com.threeastudio.gitclonepush.core.designsystem.components.ErrorState
import com.threeastudio.gitclonepush.core.designsystem.components.FileChangeItem
import com.threeastudio.gitclonepush.core.designsystem.components.FileTreeItem
import com.threeastudio.gitclonepush.core.designsystem.components.LoadingState
import com.threeastudio.gitclonepush.core.designsystem.components.SectionCard
import com.threeastudio.gitclonepush.core.model.Commit
import com.threeastudio.gitclonepush.core.model.FileChange
import com.threeastudio.gitclonepush.core.model.GitCommit
import com.threeastudio.gitclonepush.core.model.GitMutationState
import com.threeastudio.gitclonepush.core.model.RepositoryFile
import com.threeastudio.gitclonepush.core.model.RepositoryFileContent
import com.threeastudio.gitclonepush.core.model.RepositoryFileEntry
import com.threeastudio.gitclonepush.domain.repository.RepositoryFileReader

// Each tab contributes items to the workspace's single vertical list.
internal fun LazyListScope.repositoryChanges(
    changes: List<FileChange>,
    selectedPaths: Set<String>,
    mutationState: GitMutationState,
    message: String,
    onMessageChange: (String) -> Unit,
    onToggleSelection: (String) -> Unit,
    onStage: (String) -> Unit,
    onUnstage: (String) -> Unit,
    onStageSelected: () -> Unit,
    onUnstageSelected: () -> Unit,
    onStageAll: () -> Unit,
    onUnstageAll: () -> Unit,
    onCommit: () -> Unit,
    onViewDiff: (String) -> Unit,
    onViewStagedDiff: (String) -> Unit
) {
    val busy = mutationState != GitMutationState.IDLE
    item(key = "change-actions") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onStageAll, enabled = !busy) {
                Text(if (mutationState == GitMutationState.STAGING) "Staging…" else "Stage all")
            }
            TextButton(onClick = onUnstageAll, enabled = !busy) {
                Text(if (mutationState == GitMutationState.UNSTAGING) "Unstaging…" else "Unstage all")
            }
            if (selectedPaths.isNotEmpty()) {
                TextButton(onClick = onStageSelected, enabled = !busy) { Text("Stage selected") }
                TextButton(onClick = onUnstageSelected, enabled = !busy) { Text("Unstage selected") }
            }
        }
    }
    if (changes.isEmpty()) item(key = "changes-empty") { EmptyState("Working tree is clean.") }
    items(changes, key = { "change:${it.path}" }) { change ->
        SectionCard(title = if (change.indexState != null) "Staged changes" else "Working tree") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(selectedPaths.contains(change.path), { onToggleSelection(change.path) }, enabled = !busy)
                FileChangeItem(change, Modifier.weight(1f))
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (change.workTreeState != null) {
                    TextButton(onClick = { onStage(change.path) }, enabled = !busy) { Text("Stage") }
                    TextButton(onClick = { onViewDiff(change.path) }, enabled = !busy) { Text("View diff") }
                }
                if (change.indexState != null) {
                    TextButton(onClick = { onUnstage(change.path) }, enabled = !busy) { Text("Unstage") }
                    TextButton(onClick = { onViewStagedDiff(change.path) }, enabled = !busy) { Text("Staged diff") }
                }
            }
        }
    }
    item(key = "commit-form") {
        SectionCard("Create commit") {
            OutlinedTextField(
                value = message,
                onValueChange = onMessageChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Commit message") },
                minLines = 2,
                maxLines = 5,
                enabled = !busy
            )
            Button(onClick = onCommit, enabled = message.isNotBlank() && !busy, modifier = Modifier.fillMaxWidth()) {
                Text(if (mutationState == GitMutationState.COMMITTING) "Committing…" else "Commit staged changes")
            }
        }
    }
}

internal fun LazyListScope.repositoryCommits(
    commits: List<GitCommit>,
    onRevert: (String) -> Unit,
    onCherryPick: (List<String>) -> Unit
) {
    if (commits.isEmpty()) item(key = "commits-empty") { EmptyState("No commits yet.") }
    items(commits, key = { "commit:${it.hash}" }) { commit ->
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            CommitListItem(Commit(commit.message, commit.hash, commit.author, commit.time))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { onRevert(commit.hash) }) { Text("Revert") }
                TextButton(onClick = { onCherryPick(listOf(commit.hash)) }) { Text("Cherry-pick") }
            }
        }
    }
}

@Composable
internal fun rememberWorkspaceFilesViewModel(
    repositoryId: String,
    reader: RepositoryFileReader?,
    visible: Boolean,
    refreshKey: Any
): RepositoryFilesViewModel? {
    if (!visible || reader == null) return null
    val filesViewModel: RepositoryFilesViewModel = viewModel(
        key = "files:$repositoryId",
        factory = RepositoryFilesViewModelFactory(repositoryId, reader)
    )
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, filesViewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) filesViewModel.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(refreshKey) { filesViewModel.refresh() }
    return filesViewModel
}

internal fun LazyListScope.repositoryFiles(
    state: RepositoryFilesUiState,
    onNavigateUp: () -> Unit,
    onRefresh: () -> Unit,
    onDirectory: (String) -> Unit,
    onFile: (String) -> Unit
) {
    item(key = "files-navigation") {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(state.currentPath.ifBlank { "/" }, style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onNavigateUp, enabled = state.currentPath.isNotBlank()) { Text("Up") }
                TextButton(onClick = onRefresh) { Text("Refresh") }
            }
        }
    }
    state.error?.let { error ->
        item(key = "files-error") { ErrorState("Unable to open this path: ${error.lowercase().replace('_', ' ')}", onRefresh) }
    }
    when {
        state.isLoading && state.entries.isEmpty() -> item(key = "files-loading") { LoadingState("Loading files…") }
        !state.isLoading && state.entries.isEmpty() && state.error == null -> item(key = "files-empty") { EmptyState("Directory is empty.") }
    }
    items(state.entries, key = { "file:${it.relativePath}" }) { entry ->
        TextButton(
            onClick = {
                when (entry) {
                    is RepositoryFileEntry.Directory -> onDirectory(entry.relativePath)
                    is RepositoryFileEntry.File -> onFile(entry.relativePath)
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            FileTreeItem(RepositoryFile(entry.relativePath, entry is RepositoryFileEntry.Directory), Modifier.weight(1f))
            if (entry is RepositoryFileEntry.File) {
                Text(if (entry.isBinary) "Binary" else formatFileSize(entry.sizeBytes), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
    state.openedFilePath?.let { path ->
        item(key = "file-preview") {
            SectionCard(path) {
                when (val content = state.openedContent) {
                    is RepositoryFileContent.Text -> {
                        Text(
                            content.text,
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            fontFamily = FontFamily.Monospace
                        )
                        if (content.truncated) Text("Preview truncated safely.", style = MaterialTheme.typography.bodySmall)
                    }
                    is RepositoryFileContent.Binary -> Text("Binary file · ${formatFileSize(content.sizeBytes)}")
                    null -> Unit
                }
            }
        }
    }
}

private fun formatFileSize(size: Long): String = when {
    size < 1024 -> "$size B"
    size < 1024 * 1024 -> "${size / 1024} KB"
    else -> "${size / (1024 * 1024)} MB"
}
