package com.threeastudio.gitclonepush.feature.repository

import com.threeastudio.gitclonepush.core.model.*

data class RepositoryUiState(
    val isLoading: Boolean = true,
    val isRefreshingStatus: Boolean = false,
    val activeOperationMessage: String? = null,
    val localRepository: LocalRepository? = null,
    val branch: GitBranch? = null,
    val status: RepositoryStatus = RepositoryStatus(emptyList(), true),
    val commits: List<GitCommit> = emptyList(),
    val selectedPaths: Set<String> = emptySet(),
    val commitMessage: String = "",
    val mutationState: GitMutationState = GitMutationState.IDLE,
    val remoteState: RemoteSyncState = RemoteSyncState(),
    val remoteOperationState: RemoteOperationState = RemoteOperationState.IDLE,
    val branches: List<GitBranchInfo> = emptyList(),
    val branchOperationState: BranchOperationState = BranchOperationState.IDLE,
    val branchInput: String = "",
    val branchRenameInput: String = "",
    val selectedBranchName: String? = null,
    val mergeSourceBranch: String? = null,
    val mergeState: MergeState = MergeState(),
    val rebaseState: RebaseState = RebaseState(),
    val rebaseTargetBranch: String? = null,
    val interactivePlan: List<InteractiveRebaseItem> = emptyList(),
    val historyMutation: HistoryMutationState = HistoryMutationState(),
    val stashes: List<GitStashEntry> = emptyList(),
    val stashMessage: String = "",
    val stashIncludeUntracked: Boolean = false,
    val operationMessage: String? = null,
    val errorMessage: String? = null,
    val isRemovingClone: Boolean = false,
    val cloneRemoved: Boolean = false,
    val removeCloneError: String? = null
) {
    val progressMessage: String? get() = when {
        isRemovingClone -> "Removing local clone…"
        isLoading -> "Loading repository workspace…"
        isRefreshingStatus -> "Refreshing Git status…"
        activeOperationMessage != null -> activeOperationMessage
        mutationState != GitMutationState.IDLE -> when (mutationState) {
            GitMutationState.STAGING -> "Staging changes…"
            GitMutationState.UNSTAGING -> "Unstaging changes…"
            else -> "Creating commit…"
        }
        remoteOperationState != RemoteOperationState.IDLE -> when (remoteOperationState) {
            RemoteOperationState.FETCHING -> "Fetching remote changes…"
            RemoteOperationState.PULLING -> "Pulling remote changes…"
            else -> "Pushing commits to remote…"
        }
        branchOperationState != BranchOperationState.IDLE -> "Updating branches…"
        else -> null
    }
}
