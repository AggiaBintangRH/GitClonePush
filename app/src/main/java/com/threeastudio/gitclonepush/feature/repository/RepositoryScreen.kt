@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.threeastudio.gitclonepush.feature.repository

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.testTag
import com.threeastudio.gitclonepush.core.designsystem.components.InlineError
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.threeastudio.gitclonepush.core.designsystem.components.GitTopAppBar
import com.threeastudio.gitclonepush.core.model.GitMutationState
import com.threeastudio.gitclonepush.core.model.RemoteOperationState
import com.threeastudio.gitclonepush.core.designsystem.components.EmptyState
import com.threeastudio.gitclonepush.core.designsystem.components.LoadingState
import com.threeastudio.gitclonepush.core.designsystem.components.LoadingOverlay
import com.threeastudio.gitclonepush.core.designsystem.components.SectionCard
import com.threeastudio.gitclonepush.core.designsystem.components.GitStateChip
import com.threeastudio.gitclonepush.domain.repository.*
import com.threeastudio.gitclonepush.domain.repository.RepositoryFileReader
import com.threeastudio.gitclonepush.data.git.GitDiagnostics
import com.threeastudio.gitclonepush.data.git.NoOpGitDiagnostics

@Composable
fun RepositoryScreen(repositoryId: String, onBack: () -> Unit, localStore: LocalRepositoryStore, reader: GitRepositoryReader, mutator: GitRepositoryMutator, synchronizer: GitRemoteSynchronizer, identityReader: GitAuthorIdentityReader, diagnostics: GitDiagnostics = NoOpGitDiagnostics, branchReader: GitBranchReader? = null, branchMutator: GitBranchMutator? = null, mergeReader: GitMergeReader? = null, mergeMutator: GitMergeMutator? = null, rebaseReader: GitRebaseReader? = null, rebaseMutator: GitRebaseMutator? = null, revertReader: GitRevertReader? = null, revertMutator: GitRevertMutator? = null, cherryPickReader: GitCherryPickReader? = null, cherryPickMutator: GitCherryPickMutator? = null, stashReader: GitStashReader? = null, stashMutator: GitStashMutator? = null, onOpenHistory: () -> Unit = {}, onOpenDiff: (com.threeastudio.gitclonepush.core.model.DiffScope, String) -> Unit = { _, _ -> }, fileReader: RepositoryFileReader? = null, openInFiles: OpenRepositoryInFiles? = null, localRepositoryRemover: LocalRepositoryRemover? = null, onCloneRemoved: () -> Unit = {}, viewModel: RepositoryViewModel = viewModel(factory = RepositoryViewModelFactory(repositoryId, localStore, reader, mutator, synchronizer, identityReader, diagnostics, branchReader, branchMutator, mergeReader, mergeMutator, rebaseReader, rebaseMutator, revertReader, revertMutator, cherryPickReader, cherryPickMutator, stashReader, stashMutator, localRepositoryRemover))) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var showAdvanced by rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    var showRepositorySettings by remember { androidx.compose.runtime.mutableStateOf(false) }
    var confirmRemoveClone by remember { androidx.compose.runtime.mutableStateOf(false) }
    var openFilesError by remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
    var isOpeningFiles by remember { androidx.compose.runtime.mutableStateOf(false) }
    val screenScope = rememberCoroutineScope()
    val repositoryName = state.localRepository?.name ?: "Repository"
    val tabs = listOf("Changes", "Files", "Commits")
    val filesViewModel = rememberWorkspaceFilesViewModel(repositoryId, fileReader, selectedTab == 1, state.branch?.name to state.status.changes)
    val filesState = filesViewModel?.uiState?.collectAsStateWithLifecycle()?.value
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshStatus()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(state.cloneRemoved) {
        if (state.cloneRemoved) onCloneRemoved()
    }
    Scaffold(topBar = { GitTopAppBar(repositoryName, onBack, showBack = true, onActionClick = { showRepositorySettings = true }, actionContentDescription = "Repository settings", actionIcon = Icons.Default.Settings) }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()
                .testTag("repository-workspace"),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item(key = "workspace-tools") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    OutlinedButton(onClick = {
                        openFilesError = null
                        isOpeningFiles = true
                        screenScope.launch {
                            try {
                                val result = openInFiles?.open(repositoryId)
                                    ?: Result.failure(OpenInFilesException(OpenInFilesErrorCategory.PROVIDER_UNAVAILABLE))
                                if (result.isFailure) {
                                    openFilesError = when ((result.exceptionOrNull() as? OpenInFilesException)?.category) {
                                        OpenInFilesErrorCategory.NO_FILE_MANAGER_AVAILABLE -> "No compatible file manager is available on this device."
                                        OpenInFilesErrorCategory.REPOSITORY_NOT_FOUND -> "Repository files are not available locally yet."
                                        OpenInFilesErrorCategory.DOCUMENT_UNAVAILABLE -> "The saved repository folder is unavailable. Check file access or whether it was moved."
                                        else -> "Could not open this repository in Files."
                                    }
                                }
                            } catch (error: kotlinx.coroutines.CancellationException) {
                                throw error
                            } catch (_: Exception) {
                                openFilesError = "Could not open repository files. Check folder access and try again."
                            } finally {
                                isOpeningFiles = false
                            }
                        }
                }, enabled = state.localRepository != null && !isOpeningFiles && !state.isRemovingClone, modifier = Modifier.weight(1f)) { Text(if (isOpeningFiles) "Opening files…" else "Open in Files") }
                    IconButton(onClick = viewModel::refreshStatus) { androidx.compose.material3.Icon(Icons.Default.Refresh, "Refresh repository status") }
                    IconButton(onClick = onOpenHistory) { androidx.compose.material3.Icon(Icons.Default.History, "Open commit history") }
                }
            }
            openFilesError?.let { message -> item(key = "open-files-error") { InlineError(message) } }
            state.removeCloneError?.let { message -> item(key = "remove-clone-error") { InlineError(message) } }
            state.errorMessage?.let { message -> item(key = "workspace-error") { InlineError(message) } }
            if (state.isLoading) item(key = "workspace-loading") { LoadingState("Loading repository…") }
            item(key = "workspace-summary") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(repositoryName, style = MaterialTheme.typography.titleLarge)
                        Text(
                            "${state.branch?.name ?: "Unknown branch"} · ${state.status.changes.size} changed files",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    GitStateChip(if (state.remoteState.isDetachedHead) "Detached HEAD" else state.remoteState.branch ?: state.branch?.name ?: "Unknown")
                }
            }
            item(key = "workspace-remote") {
                SectionCard("Remote") {
                    RemoteSyncSection(state, viewModel::fetch, viewModel::pull, viewModel::push)
                }
            }
            item(key = "workspace-advanced-toggle") {
                OutlinedButton(onClick = { showAdvanced = !showAdvanced }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (showAdvanced) "Hide advanced actions" else "Advanced actions")
                    Spacer(Modifier.width(8.dp))
                    Icon(if (showAdvanced) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
                }
            }
            if (showAdvanced || state.mergeState.repositoryState != com.threeastudio.gitclonepush.core.model.MergeRepositoryState.SAFE ||
                state.rebaseState.operationState != com.threeastudio.gitclonepush.core.model.RebaseOperationState.IDLE ||
                state.historyMutation.kind != com.threeastudio.gitclonepush.core.model.HistoryMutationKind.NONE
            ) {
                item(key = "advanced-branches") {
                    SectionCard("Branches") {
                        BranchSection(
                            state = state,
                            onRefresh = viewModel::refreshBranches,
                            onCreate = { viewModel.createBranch(checkout = false) },
                            onCreateAndCheckout = { viewModel.createBranch(checkout = true) },
                            onCheckout = viewModel::checkoutBranch,
                            onTrack = viewModel::createTrackingBranch,
                            onRename = viewModel::renameBranch,
                            onDelete = viewModel::deleteBranch,
                            onSelect = viewModel::selectBranch,
                            onBranchInput = viewModel::updateBranchInput,
                            onRenameInput = viewModel::updateBranchRenameInput
                        )
                    }
                }
                item(key = "advanced-merge") {
                    SectionCard("Merge") {
                        MergeSection(
                            state, viewModel::updateMergeSourceBranch, viewModel::mergeSelectedBranch,
                            viewModel::useOurs, viewModel::useTheirs, viewModel::continueMerge,
                            viewModel::abortMerge, viewModel::updateCommitMessage
                        )
                    }
                }
                item(key = "advanced-rebase") {
                    SectionCard("Rebase") {
                        RebaseSection(
                            state = state,
                            onSelect = viewModel::updateRebaseTargetBranch,
                            onStart = viewModel::startRebase,
                            onContinue = viewModel::continueRebase,
                            onSkip = viewModel::skipRebase,
                            onAbort = viewModel::abortRebase,
                            onPrepareInteractive = viewModel::prepareInteractivePlan,
                            onInteractiveAction = viewModel::updateInteractiveAction,
                            onMoveInteractive = viewModel::moveInteractiveItem,
                            onInteractiveMessage = viewModel::updateInteractiveMessage,
                            onStartInteractive = viewModel::startInteractiveRebase
                        )
                    }
                }
                if (state.historyMutation.kind != com.threeastudio.gitclonepush.core.model.HistoryMutationKind.NONE) {
                    item(key = "advanced-operation") {
                        SectionCard("In progress") {
                            HistoryMutationSection(state, viewModel::continueRevert, viewModel::abortRevert,
                                viewModel::continueCherryPick, viewModel::skipCherryPick, viewModel::abortCherryPick)
                        }
                    }
                }
                item(key = "advanced-stash") {
                    SectionCard("Stash") {
                        StashSection(state, viewModel::updateStashMessage, viewModel::setStashIncludeUntracked,
                            viewModel::createStash, viewModel::applyStash, viewModel::popStash, viewModel::dropStash)
                    }
                }
            }
            item(key = "workspace-tabs") {
                TabRow(selectedTab) {
                    tabs.forEachIndexed { index, tab ->
                        Tab(selectedTab == index, { selectedTab = index }, text = { Text(tab) })
                    }
                }
            }
            when (selectedTab) {
                0 -> repositoryChanges(
                    changes = state.status.changes,
                    selectedPaths = state.selectedPaths,
                    mutationState = state.mutationState,
                    message = state.commitMessage,
                    onMessageChange = viewModel::updateCommitMessage,
                    onToggleSelection = viewModel::togglePathSelection,
                    onStage = viewModel::stage,
                    onUnstage = viewModel::unstage,
                    onStageSelected = viewModel::stageSelected,
                    onUnstageSelected = viewModel::unstageSelected,
                    onStageAll = viewModel::stageAll,
                    onUnstageAll = viewModel::unstageAll,
                    onCommit = viewModel::commit,
                    onViewDiff = { path -> onOpenDiff(com.threeastudio.gitclonepush.core.model.DiffScope.UNSTAGED, path) },
                    onViewStagedDiff = { path -> onOpenDiff(com.threeastudio.gitclonepush.core.model.DiffScope.STAGED, path) }
                )
                1 -> if (filesViewModel != null && filesState != null) {
                    repositoryFiles(filesState, filesViewModel::navigateUp, filesViewModel::refresh,
                        filesViewModel::openDirectory, filesViewModel::openFile)
                } else {
                    item(key = "files-unavailable") { EmptyState("Repository file browser is unavailable.") }
                }
                else -> repositoryCommits(state.commits, viewModel::revertCommit, viewModel::cherryPickCommits)
            }
        }
    }
    LoadingOverlay(state.progressMessage ?: if (isOpeningFiles) "Opening repository in Files…" else if (filesState?.isLoading == true) "Loading repository files…" else null)
    if (showRepositorySettings) {
        AlertDialog(
            onDismissRequest = { if (!state.isRemovingClone) showRepositorySettings = false },
            title = { Text("Repository settings") },
            text = { Text("Local folder:\n${state.localRepository?.directoryPath.orEmpty()}") },
            dismissButton = { TextButton(onClick = { showRepositorySettings = false }) { Text("Close") } },
            confirmButton = {
                TextButton(
                    onClick = { confirmRemoveClone = true; showRepositorySettings = false },
                    enabled = localRepositoryRemover != null && !state.isRemovingClone
                ) { Text(if (state.isRemovingClone) "Removing…" else "Remove clone") }
            }
        )
    }
    if (confirmRemoveClone) {
        AlertDialog(
            onDismissRequest = { if (!state.isRemovingClone) confirmRemoveClone = false },
            title = { Text("Remove local clone?") },
            text = { Text("This permanently deletes the local repository folder, including uncommitted files and commits that have not been pushed. The repository on GitHub is unchanged.") },
            dismissButton = { TextButton(onClick = { confirmRemoveClone = false }) { Text("Cancel") } },
            confirmButton = {
                Button(onClick = { confirmRemoveClone = false; viewModel.removeClone() }, enabled = !state.isRemovingClone) { Text("Remove clone") }
            }
        )
    }
}

@Composable
private fun RemoteSyncSection(
    state: RepositoryUiState,
    onFetch: () -> Unit,
    onPull: () -> Unit,
    onPush: () -> Unit
) {
    val remote = state.remoteState
    val busy = state.remoteOperationState != RemoteOperationState.IDLE || state.mutationState != GitMutationState.IDLE
    val relationship = when {
        remote.isDetachedHead -> "Detached HEAD"
        !remote.hasUpstream -> "No upstream"
        remote.ahead == 0 && remote.behind == 0 -> "Up to date"
        else -> "Ahead ${remote.ahead ?: 0} / Behind ${remote.behind ?: 0}"
    }
    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Text("Remote · $relationship")
        Text(remote.upstream ?: "No tracking branch", modifier = Modifier.padding(bottom = 4.dp))
        state.operationMessage?.let { Text(it, color = androidx.compose.material3.MaterialTheme.colorScheme.primary, style = androidx.compose.material3.MaterialTheme.typography.bodySmall) }
        if (!remote.hasUpstream && !remote.isDetachedHead) {
            Text("Pull unavailable: configure an upstream branch first.", style = androidx.compose.material3.MaterialTheme.typography.bodySmall, color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant)
        }
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onFetch, enabled = !busy && remote.hasRemote) {
                Text(if (state.remoteOperationState == RemoteOperationState.FETCHING) "Fetching…" else "Fetch")
            }
            Button(onClick = onPull, enabled = !busy && remote.hasUpstream && !remote.isDetachedHead) {
                Text(if (state.remoteOperationState == RemoteOperationState.PULLING) "Pulling…" else "Pull")
            }
            Button(onClick = onPush, enabled = !busy && remote.hasUpstream && !remote.isDetachedHead) {
                Text(if (state.remoteOperationState == RemoteOperationState.PUSHING) "Pushing…" else "Push")
            }
        }
    }
}

class RepositoryViewModelFactory(private val repositoryId: String, private val localStore: LocalRepositoryStore, private val reader: GitRepositoryReader, private val mutator: GitRepositoryMutator, private val synchronizer: GitRemoteSynchronizer, private val identityReader: GitAuthorIdentityReader, private val diagnostics: GitDiagnostics = NoOpGitDiagnostics, private val branchReader: GitBranchReader? = null, private val branchMutator: GitBranchMutator? = null, private val mergeReader: GitMergeReader? = null, private val mergeMutator: GitMergeMutator? = null, private val rebaseReader: GitRebaseReader? = null, private val rebaseMutator: GitRebaseMutator? = null, private val revertReader: GitRevertReader? = null, private val revertMutator: GitRevertMutator? = null, private val cherryPickReader: GitCherryPickReader? = null, private val cherryPickMutator: GitCherryPickMutator? = null, private val stashReader: GitStashReader? = null, private val stashMutator: GitStashMutator? = null, private val localRepositoryRemover: LocalRepositoryRemover? = null) : androidx.lifecycle.ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST") override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T = RepositoryViewModel(repositoryId, localStore, reader, mutator, synchronizer, identityReader, diagnostics, branchReader, branchMutator, mergeReader, mergeMutator, rebaseReader, rebaseMutator, revertReader, revertMutator, cherryPickReader, cherryPickMutator, stashReader, stashMutator, localRepositoryRemover) as T
}
