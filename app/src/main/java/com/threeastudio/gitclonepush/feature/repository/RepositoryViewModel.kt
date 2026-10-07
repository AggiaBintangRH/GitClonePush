package com.threeastudio.gitclonepush.feature.repository

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.threeastudio.gitclonepush.core.model.CommitRequest
import com.threeastudio.gitclonepush.core.model.GitMutationState
import com.threeastudio.gitclonepush.core.model.RemoteOperationState
import com.threeastudio.gitclonepush.core.model.PullResult
import com.threeastudio.gitclonepush.core.model.PushResult
import com.threeastudio.gitclonepush.core.model.RemoteSyncState
import com.threeastudio.gitclonepush.core.model.BranchOperationState
import com.threeastudio.gitclonepush.core.model.GitBranchInfo
import com.threeastudio.gitclonepush.core.model.MergeOperationState
import com.threeastudio.gitclonepush.core.model.MergeResult
import com.threeastudio.gitclonepush.core.model.MergeState
import com.threeastudio.gitclonepush.core.model.ConflictVersion
import com.threeastudio.gitclonepush.core.model.RebaseOperationState
import com.threeastudio.gitclonepush.core.model.RebaseResult
import com.threeastudio.gitclonepush.core.model.RebaseState
import com.threeastudio.gitclonepush.core.model.InteractiveRebaseItem
import com.threeastudio.gitclonepush.core.model.HistoryMutationKind
import com.threeastudio.gitclonepush.core.model.HistoryMutationState
import com.threeastudio.gitclonepush.data.git.GitDiagnosticEvent
import com.threeastudio.gitclonepush.data.git.GitDiagnostics
import com.threeastudio.gitclonepush.data.git.NoOpGitDiagnostics
import com.threeastudio.gitclonepush.domain.repository.CloneErrorCategory
import com.threeastudio.gitclonepush.domain.repository.CloneOperationException
import com.threeastudio.gitclonepush.domain.repository.GitAuthorIdentityReader
import com.threeastudio.gitclonepush.domain.repository.GitMutationErrorCategory
import com.threeastudio.gitclonepush.domain.repository.GitMutationException
import com.threeastudio.gitclonepush.domain.repository.GitRemoteSynchronizer
import com.threeastudio.gitclonepush.domain.repository.RemoteSyncErrorCategory
import com.threeastudio.gitclonepush.domain.repository.RemoteSyncException
import com.threeastudio.gitclonepush.domain.repository.GitBranchReader
import com.threeastudio.gitclonepush.domain.repository.GitBranchMutator
import com.threeastudio.gitclonepush.domain.repository.BranchErrorCategory
import com.threeastudio.gitclonepush.domain.repository.BranchOperationException
import com.threeastudio.gitclonepush.domain.repository.GitRepositoryMutator
import com.threeastudio.gitclonepush.domain.repository.GitRepositoryReader
import com.threeastudio.gitclonepush.domain.repository.GitMergeReader
import com.threeastudio.gitclonepush.domain.repository.GitMergeMutator
import com.threeastudio.gitclonepush.domain.repository.MergeErrorCategory
import com.threeastudio.gitclonepush.domain.repository.MergeOperationException
import com.threeastudio.gitclonepush.domain.repository.GitRebaseReader
import com.threeastudio.gitclonepush.domain.repository.GitRebaseMutator
import com.threeastudio.gitclonepush.domain.repository.RebaseErrorCategory
import com.threeastudio.gitclonepush.domain.repository.RebaseOperationException
import com.threeastudio.gitclonepush.domain.repository.GitRevertReader
import com.threeastudio.gitclonepush.domain.repository.GitRevertMutator
import com.threeastudio.gitclonepush.domain.repository.RevertErrorCategory
import com.threeastudio.gitclonepush.domain.repository.RevertOperationException
import com.threeastudio.gitclonepush.domain.repository.GitCherryPickReader
import com.threeastudio.gitclonepush.domain.repository.GitCherryPickMutator
import com.threeastudio.gitclonepush.domain.repository.CherryPickErrorCategory
import com.threeastudio.gitclonepush.domain.repository.CherryPickOperationException
import com.threeastudio.gitclonepush.domain.repository.GitStashReader
import com.threeastudio.gitclonepush.domain.repository.GitStashMutator
import com.threeastudio.gitclonepush.domain.repository.StashErrorCategory
import com.threeastudio.gitclonepush.domain.repository.StashOperationException
import com.threeastudio.gitclonepush.domain.repository.LocalRepositoryStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class RepositoryViewModel(
    private val repositoryId: String,
    private val localStore: LocalRepositoryStore,
    private val reader: GitRepositoryReader,
    private val mutator: GitRepositoryMutator,
    private val synchronizer: GitRemoteSynchronizer,
    private val identityReader: GitAuthorIdentityReader,
    private val diagnostics: GitDiagnostics = NoOpGitDiagnostics,
    private val branchReader: GitBranchReader? = null,
    private val branchMutator: GitBranchMutator? = null,
    private val mergeReader: GitMergeReader? = null,
    private val mergeMutator: GitMergeMutator? = null,
    private val rebaseReader: GitRebaseReader? = null,
    private val rebaseMutator: GitRebaseMutator? = null,
    private val revertReader: GitRevertReader? = null,
    private val revertMutator: GitRevertMutator? = null,
    private val cherryPickReader: GitCherryPickReader? = null,
    private val cherryPickMutator: GitCherryPickMutator? = null,
    private val stashReader: GitStashReader? = null,
    private val stashMutator: GitStashMutator? = null,
    private val localRepositoryRemover: com.threeastudio.gitclonepush.domain.repository.LocalRepositoryRemover? = null
) : ViewModel() {
    private val _uiState = MutableStateFlow(RepositoryUiState())
    val uiState: StateFlow<RepositoryUiState> = _uiState.asStateFlow()
    private var refreshJob: Job? = null
    private var mutationJob: Job? = null
    private var remoteJob: Job? = null
    private var branchJob: Job? = null
    private var rebaseJob: Job? = null
    private var historyJob: Job? = null
    private var mergeJob: Job? = null

    init { refresh() }

    fun removeClone() {
        val remover = localRepositoryRemover ?: return
        val repository = _uiState.value.localRepository ?: return
        if (hasBusyOperation() || _uiState.value.isRemovingClone) return
        _uiState.value = _uiState.value.copy(isRemovingClone = true, removeCloneError = null)
        viewModelScope.launch {
            try {
                remover.remove(repository.id)
                _uiState.value = _uiState.value.copy(isRemovingClone = false, cloneRemoved = true)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                _uiState.value = _uiState.value.copy(isRemovingClone = false, removeCloneError = "The local clone could not be removed safely.")
            } finally {
                _uiState.value = _uiState.value.copy(isRemovingClone = false)
            }
        }
    }

    fun refresh() {
        if (_uiState.value.isRemovingClone || _uiState.value.cloneRemoved) return
        if (hasBusyOperation()) return
        refreshJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
            diagnostics.event(GitDiagnosticEvent.WORKSPACE_OPEN_STARTED, mapOf("repositoryId" to repositoryId))
            try {
                val repository = localStore.listRepositories().firstOrNull { it.id == repositoryId }
                    ?: throw CloneOperationException(CloneErrorCategory.INVALID_REPOSITORY)
                val status = reader.status(repository)
                val branch = reader.currentBranch(repository)
                val commits = reader.recentCommits(repository, 20)
                val remoteState = runCatchingCancellable { synchronizer.readState(repository) }.getOrDefault(RemoteSyncState())
                val branches = runCatchingCancellable { branchReader?.listBranches(repository).orEmpty() }.getOrDefault(emptyList())
                val mergeState = runCatchingCancellable { mergeReader?.readState(repository) ?: MergeState() }.getOrDefault(MergeState())
                val rebaseState = runCatchingCancellable { rebaseReader?.readState(repository) ?: RebaseState() }.getOrDefault(RebaseState())
                val historyMutation = runCatchingCancellable { revertReader?.readState(repository)?.takeIf { it.kind != HistoryMutationKind.NONE } ?: cherryPickReader?.readState(repository)?.takeIf { it.kind != HistoryMutationKind.NONE } ?: HistoryMutationState() }.getOrDefault(HistoryMutationState())
                val stashes = runCatchingCancellable { stashReader?.list(repository).orEmpty() }.getOrDefault(emptyList())
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    localRepository = repository,
                    status = status,
                    branch = branch,
                    commits = commits,
                    remoteState = remoteState,
                    branches = branches,
                    mergeState = mergeState,
                    rebaseState = rebaseState,
                    historyMutation = historyMutation,
                    stashes = stashes,
                    selectedPaths = _uiState.value.selectedPaths.intersect(status.changes.map { it.path }.toSet())
                )
                diagnostics.event(GitDiagnosticEvent.WORKSPACE_OPEN_SUCCESS, mapOf("repositoryId" to repositoryId))
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                _uiState.value = _uiState.value.copy(isLoading = false, errorMessage = "This local repository could not be opened.")
            } finally {
                _uiState.value = _uiState.value.copy(isLoading = false)
                refreshJob = null
            }
        }
    }

    fun refreshStatus() {
        if (_uiState.value.isRemovingClone || _uiState.value.cloneRemoved) return
        if (hasBusyOperation()) return
        val repository = _uiState.value.localRepository ?: return
        refreshJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isRefreshingStatus = true, errorMessage = null)
            try {
                refreshStatusNow(repository)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                _uiState.value = _uiState.value.copy(errorMessage = "GIT_STATUS_001_REFRESH_FAILED: Repository status could not be refreshed.")
                diagnostics.event(GitDiagnosticEvent.STATUS_REFRESH_FAILED, mapOf("repositoryId" to repositoryId, "category" to safeCategory(error)))
            } finally {
                _uiState.value = _uiState.value.copy(isRefreshingStatus = false)
                refreshJob = null
            }
        }
    }

    fun updateCommitMessage(message: String) {
        _uiState.value = _uiState.value.copy(commitMessage = message, errorMessage = null)
    }

    fun togglePathSelection(path: String) {
        if (_uiState.value.mutationState != GitMutationState.IDLE) return
        val selected = _uiState.value.selectedPaths.toMutableSet()
        if (!selected.add(path)) selected.remove(path)
        _uiState.value = _uiState.value.copy(selectedPaths = selected)
    }

    fun stage(path: String) = runMutation(GitMutationState.STAGING) { repository -> mutator.stage(repository, listOf(path)) }

    fun stageSelected() {
        val paths = _uiState.value.selectedPaths.toList()
        if (paths.isEmpty()) return showMutationError(GitMutationException(GitMutationErrorCategory.INVALID_PATH))
        runMutation(GitMutationState.STAGING) { repository -> mutator.stage(repository, paths) }
    }

    fun stageAll() = runMutation(GitMutationState.STAGING) { repository -> mutator.stageAll(repository) }

    fun unstage(path: String) = runMutation(GitMutationState.UNSTAGING) { repository -> mutator.unstage(repository, listOf(path)) }

    fun unstageSelected() {
        val paths = _uiState.value.selectedPaths.toList()
        if (paths.isEmpty()) return showMutationError(GitMutationException(GitMutationErrorCategory.INVALID_PATH))
        runMutation(GitMutationState.UNSTAGING) { repository -> mutator.unstage(repository, paths) }
    }

    fun unstageAll() = runMutation(GitMutationState.UNSTAGING) { repository -> mutator.unstageAll(repository) }

    fun commit() {
        if (_uiState.value.isRemovingClone || _uiState.value.cloneRemoved) return
        val snapshot = _uiState.value
        if (isHistoryActive()) return showMutationError(GitMutationException(GitMutationErrorCategory.UNRESOLVED_CONFLICTS))
        if (isRebaseActive()) {
            return showMutationError(GitMutationException(GitMutationErrorCategory.UNRESOLVED_CONFLICTS))
        }
        if (snapshot.mergeState.repositoryState != com.threeastudio.gitclonepush.core.model.MergeRepositoryState.SAFE) {
            return showMutationError(GitMutationException(GitMutationErrorCategory.UNRESOLVED_CONFLICTS))
        }
        val message = snapshot.commitMessage.trim()
        if (message.isBlank()) return showMutationError(GitMutationException(GitMutationErrorCategory.COMMIT_MESSAGE_EMPTY))
        if (hasBusyOperation()) return
        mutationJob = viewModelScope.launch {
            diagnostics.event(GitDiagnosticEvent.COMMIT_REQUESTED, mapOf("repositoryId" to repositoryId))
            val repository = snapshot.localRepository ?: run {
                showMutationError(GitMutationException(GitMutationErrorCategory.REPOSITORY_MISSING))
                return@launch
            }
            _uiState.value = _uiState.value.copy(mutationState = GitMutationState.COMMITTING, errorMessage = null)
            try {
                val identity = identityReader.read() ?: throw GitMutationException(GitMutationErrorCategory.AUTHOR_IDENTITY_MISSING)
                mutator.commit(repository, CommitRequest(message, identity.name, identity.email))
                refreshStatusNow(repository)
                _uiState.value = _uiState.value.copy(mutationState = GitMutationState.IDLE, commitMessage = "", selectedPaths = emptySet(), operationMessage = "Commit created locally.")
                diagnostics.event(GitDiagnosticEvent.COMMIT_SUCCESS, mapOf("repositoryId" to repositoryId, "shortCommitIdPresent" to "true"))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                diagnostics.event(GitDiagnosticEvent.COMMIT_FAILED, mapOf("repositoryId" to repositoryId, "category" to safeCategory(error)))
                showMutationError(error)
            } finally {
                _uiState.value = _uiState.value.copy(mutationState = GitMutationState.IDLE)
                mutationJob = null
            }
        }
    }

    fun fetch() = runRemoteOperation(RemoteOperationState.FETCHING) { repository ->
        if (isMergeActive()) throw RemoteSyncException(RemoteSyncErrorCategory.CONFLICT_STATE)
        synchronizer.fetch(repository)
        refreshAfterRemote(repository)
        _uiState.value = _uiState.value.copy(operationMessage = "Fetched latest remote state.")
    }

    fun pull() = runRemoteOperation(RemoteOperationState.PULLING) { repository ->
        if (isMergeActive()) throw RemoteSyncException(RemoteSyncErrorCategory.CONFLICT_STATE)
        when (val result = synchronizer.pull(repository)) {
            PullResult.AlreadyUpToDate -> _uiState.value = _uiState.value.copy(operationMessage = "Already up to date.")
            is PullResult.Updated -> _uiState.value = _uiState.value.copy(operationMessage = "Pulled ${result.commits} commits.")
            else -> Unit
        }
        refreshAfterRemote(repository)
    }

    fun push() = runRemoteOperation(RemoteOperationState.PUSHING) { repository ->
        if (isMergeActive()) throw RemoteSyncException(RemoteSyncErrorCategory.CONFLICT_STATE)
        when (synchronizer.push(repository)) {
            PushResult.AlreadyUpToDate -> _uiState.value = _uiState.value.copy(operationMessage = "Already up to date.")
            PushResult.Pushed -> _uiState.value = _uiState.value.copy(operationMessage = "Pushed successfully.")
        }
        refreshAfterRemote(repository)
    }

    fun updateBranchInput(value: String) { _uiState.value = _uiState.value.copy(branchInput = value, errorMessage = null) }
    fun updateBranchRenameInput(value: String) { _uiState.value = _uiState.value.copy(branchRenameInput = value, errorMessage = null) }
    fun selectBranch(name: String?) { _uiState.value = _uiState.value.copy(selectedBranchName = name) }
    fun refreshBranches() = runBranchOperation(BranchOperationState.REFRESHING) { repository ->
        synchronizer.fetch(repository)
        val branches = branchReader?.listBranches(repository).orEmpty()
        _uiState.value = _uiState.value.copy(branches = branches)
    }
    fun createBranch(checkout: Boolean = false) {
        val name = _uiState.value.branchInput.trim()
        runBranchOperation(BranchOperationState.CREATING) { repository ->
            branchMutator?.create(repository, name, checkout = checkout)
        }
    }
    fun checkoutBranch(name: String) = runBranchOperation(BranchOperationState.CHECKING_OUT) { repository -> branchMutator?.checkout(repository, name) }
    fun createTrackingBranch(remoteRef: String, localName: String) = runBranchOperation(BranchOperationState.CHECKING_OUT) { repository -> branchMutator?.createTracking(repository, remoteRef, localName, true) }
    fun setUpstream(localName: String, remoteName: String, remoteBranchName: String) = runBranchOperation(BranchOperationState.SETTING_UPSTREAM) { repository -> branchMutator?.setUpstream(repository, localName, remoteName, remoteBranchName) }
    fun removeUpstream(localName: String) = runBranchOperation(BranchOperationState.SETTING_UPSTREAM) { repository -> branchMutator?.removeUpstream(repository, localName) }
    fun renameBranch(oldName: String) {
        val newName = _uiState.value.branchRenameInput.trim()
        runBranchOperation(BranchOperationState.RENAMING) { repository -> branchMutator?.rename(repository, oldName, newName) }
    }
    fun deleteBranch(name: String) = runBranchOperation(BranchOperationState.DELETING) { repository -> branchMutator?.delete(repository, name) }
    fun recoverDetachedHead(checkout: Boolean = true) {
        val name = _uiState.value.branchInput.trim()
        runBranchOperation(BranchOperationState.CREATING) { repository -> branchMutator?.create(repository, name, "HEAD", checkout) }
    }

    fun updateMergeSourceBranch(value: String?) { _uiState.value = _uiState.value.copy(mergeSourceBranch = value, errorMessage = null) }

    fun mergeSelectedBranch() {
        val source = _uiState.value.mergeSourceBranch?.trim().orEmpty()
        if (hasBusyOperation()) return
        mergeJob = viewModelScope.launch {
            if (mergeReader == null || mergeMutator == null) return@launch showMergeError(MergeOperationException(MergeErrorCategory.REPOSITORY_INVALID))
            if (source.isBlank()) return@launch showMergeError(MergeOperationException(MergeErrorCategory.SOURCE_NOT_FOUND))
            if (isMergeActive() || isRebaseActive() || isHistoryActive()) return@launch showMergeError(MergeOperationException(MergeErrorCategory.MERGE_IN_PROGRESS))
            val repository = _uiState.value.localRepository ?: return@launch showMergeError(MergeOperationException(MergeErrorCategory.REPOSITORY_INVALID))
            _uiState.value = _uiState.value.copy(mergeState = _uiState.value.mergeState.copy(operationState = MergeOperationState.MERGING), activeOperationMessage = "Merging branch…", errorMessage = null)
            try {
                val author = identityReader.read()
                when (val result = mergeMutator.merge(repository, source, author)) {
                    MergeResult.AlreadyUpToDate -> _uiState.value = _uiState.value.copy(operationMessage = "Already up to date.")
                    MergeResult.FastForward -> _uiState.value = _uiState.value.copy(operationMessage = "Fast-forward merge completed.")
                    MergeResult.Merged -> _uiState.value = _uiState.value.copy(operationMessage = "Merge completed.")
                    is MergeResult.Conflicted -> _uiState.value = _uiState.value.copy(mergeState = result.state, operationMessage = "Resolve conflicts, then continue the merge.")
                }
                refreshWorkspace(repository)
            } catch (error: CancellationException) { throw error }
            catch (error: Throwable) { showMergeError(error); runCatchingCancellable { refreshMergeState(repository) } }
            finally { _uiState.value = _uiState.value.copy(activeOperationMessage = null); mergeJob = null }
        }
    }

    fun useOurs(path: String) = runMergeMutation("Applying our conflict version…") { repository -> mergeMutator?.useOurs(repository, path) }
    fun useTheirs(path: String) = runMergeMutation("Applying their conflict version…") { repository -> mergeMutator?.useTheirs(repository, path) }

    fun continueMerge() {
        runMergeMutation("Continuing merge…") { repository ->
            val message = _uiState.value.commitMessage.trim()
            val identity = identityReader.read() ?: throw MergeOperationException(MergeErrorCategory.AUTHOR_IDENTITY_MISSING)
            if (message.isBlank()) throw MergeOperationException(MergeErrorCategory.COMMIT_MESSAGE_EMPTY)
            mergeMutator?.continueMerge(repository, CommitRequest(message, identity.name, identity.email))
            _uiState.value = _uiState.value.copy(commitMessage = "", operationMessage = "Merge commit created locally.")
        }
    }

    fun abortMerge() = runMergeMutation("Aborting merge…") { repository ->
        mergeMutator?.abortMerge(repository)
        _uiState.value = _uiState.value.copy(operationMessage = "Merge aborted.")
    }

    fun updateRebaseTargetBranch(value: String?) { _uiState.value = _uiState.value.copy(rebaseTargetBranch = value, errorMessage = null) }
    fun prepareInteractivePlan() {
        _uiState.value = _uiState.value.copy(
            interactivePlan = _uiState.value.commits.asReversed().map {
                InteractiveRebaseItem(it.hash, it.hash.take(7), it.message)
            }
        )
    }
    fun updateInteractiveAction(index: Int, action: com.threeastudio.gitclonepush.core.model.RebaseAction) {
        val plan = _uiState.value.interactivePlan.toMutableList()
        if (index !in plan.indices) return
        plan[index] = plan[index].copy(action = action)
        _uiState.value = _uiState.value.copy(interactivePlan = plan)
    }
    fun moveInteractiveItem(index: Int, direction: Int) {
        val target = index + direction
        val plan = _uiState.value.interactivePlan.toMutableList()
        if (index !in plan.indices || target !in plan.indices) return
        val item = plan.removeAt(index)
        plan.add(target, item)
        _uiState.value = _uiState.value.copy(interactivePlan = plan)
    }
    fun updateInteractiveMessage(index: Int, message: String) {
        val plan = _uiState.value.interactivePlan.toMutableList()
        if (index !in plan.indices) return
        plan[index] = plan[index].copy(editedMessage = message)
        _uiState.value = _uiState.value.copy(interactivePlan = plan)
    }
    fun startInteractiveRebase() = runRebaseOperation(RebaseOperationState.RUNNING) { repository ->
        rebaseMutator?.interactive(repository, _uiState.value.interactivePlan)
    }

    fun revertCommit(commitId: String) = runHistoryMutation("Reverting commit…") { repository -> revertMutator?.revert(repository, commitId) }
    fun continueRevert() = runHistoryMutation("Continuing revert…") { repository -> revertMutator?.continueRevert(repository) }
    fun abortRevert() = runHistoryMutation("Aborting revert…") { repository -> revertMutator?.abortRevert(repository) }
    fun cherryPickCommits(commitIds: List<String>) = runHistoryMutation("Cherry-picking commits…") { repository -> cherryPickMutator?.cherryPick(repository, commitIds) }
    fun continueCherryPick() = runHistoryMutation("Continuing cherry-pick…") { repository -> cherryPickMutator?.continueCherryPick(repository) }
    fun skipCherryPick() = runHistoryMutation("Skipping cherry-pick…") { repository -> cherryPickMutator?.skipCherryPick(repository) }
    fun abortCherryPick() = runHistoryMutation("Aborting cherry-pick…") { repository -> cherryPickMutator?.abortCherryPick(repository) }
    fun updateStashMessage(value: String) { _uiState.value = _uiState.value.copy(stashMessage = value) }
    fun setStashIncludeUntracked(value: Boolean) { _uiState.value = _uiState.value.copy(stashIncludeUntracked = value) }
    fun createStash() = runHistoryMutation("Creating stash…") { repository -> stashMutator?.create(repository, _uiState.value.stashMessage.trim().takeIf { it.isNotBlank() }, _uiState.value.stashIncludeUntracked) }
    fun applyStash(index: Int) = runHistoryMutation("Applying stash…") { repository -> stashMutator?.apply(repository, index) }
    fun popStash(index: Int) = runHistoryMutation("Popping stash…") { repository -> stashMutator?.pop(repository, index) }
    fun dropStash(index: Int) = runHistoryMutation("Dropping stash…") { repository -> stashMutator?.drop(repository, index) }
    fun startRebase() = runRebaseOperation(RebaseOperationState.RUNNING) { repository ->
        val target = _uiState.value.rebaseTargetBranch?.trim().orEmpty()
        if (target.isBlank()) throw RebaseOperationException(RebaseErrorCategory.TARGET_BRANCH_NOT_FOUND)
        rebaseMutator?.start(repository, target)
    }
    fun continueRebase() = runRebaseOperation(RebaseOperationState.CONTINUING) { repository -> rebaseMutator?.continueRebase(repository) }
    fun skipRebase() = runRebaseOperation(RebaseOperationState.SKIPPING) { repository -> rebaseMutator?.skipCommit(repository) }
    fun abortRebase() = runRebaseOperation(RebaseOperationState.ABORTING) { repository -> rebaseMutator?.abort(repository) }
    fun startInteractiveRebase(plan: List<InteractiveRebaseItem>) = runRebaseOperation(RebaseOperationState.RUNNING) { repository -> rebaseMutator?.interactive(repository, plan) }

    private fun runMergeMutation(message: String, operation: suspend (com.threeastudio.gitclonepush.core.model.LocalRepository) -> Unit) {
        if (mergeMutator == null || hasBusyOperation() || isRebaseActive() || isHistoryActive()) return
        val repository = _uiState.value.localRepository ?: return showMergeError(MergeOperationException(MergeErrorCategory.REPOSITORY_INVALID))
        mergeJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(activeOperationMessage = message, errorMessage = null)
            try { operation(repository); refreshWorkspace(repository) }
            catch (error: CancellationException) { throw error }
            catch (error: Throwable) { showMergeError(error); runCatchingCancellable { refreshMergeState(repository) } }
            finally { _uiState.value = _uiState.value.copy(activeOperationMessage = null); mergeJob = null }
        }
    }

    private fun runBranchOperation(state: BranchOperationState, operation: suspend (com.threeastudio.gitclonepush.core.model.LocalRepository) -> Unit) {
        if (_uiState.value.isRemovingClone || _uiState.value.cloneRemoved) return
        if (isMergeActive() || isRebaseActive() || isHistoryActive()) return showBranchError(BranchOperationException(BranchErrorCategory.GIT_OPERATION_FAILED))
        if (branchReader == null || branchMutator == null) return showBranchError(BranchOperationException(BranchErrorCategory.REPOSITORY_INVALID))
        if (hasBusyOperation()) return
        val repository = _uiState.value.localRepository ?: return showBranchError(BranchOperationException(BranchErrorCategory.REPOSITORY_INVALID))
        branchJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(branchOperationState = state, errorMessage = null)
            try {
                operation(repository)
                val branches = branchReader.listBranches(repository)
                val status = reader.status(repository)
                val branch = reader.currentBranch(repository)
                val commits = reader.recentCommits(repository, 20)
                val remoteState = synchronizer.readState(repository)
                _uiState.value = _uiState.value.copy(branches = branches, branch = branch, status = status, commits = commits, remoteState = remoteState, branchInput = "", branchRenameInput = "", selectedBranchName = null, operationMessage = "Branch state refreshed.", errorMessage = null)
            } catch (error: CancellationException) { throw error }
            catch (error: Throwable) { showBranchError(error) }
            finally {
                _uiState.value = _uiState.value.copy(branchOperationState = BranchOperationState.IDLE)
                branchJob = null
            }
        }
    }

    private fun runRemoteOperation(state: RemoteOperationState, operation: suspend (com.threeastudio.gitclonepush.core.model.LocalRepository) -> Unit) {
        if (_uiState.value.isRemovingClone || _uiState.value.cloneRemoved) return
        if (hasBusyOperation() || isRebaseActive() || isHistoryActive()) return showRemoteError(RemoteSyncException(RemoteSyncErrorCategory.CONFLICT_STATE))
        val repository = _uiState.value.localRepository ?: return showRemoteError(RemoteSyncException(RemoteSyncErrorCategory.REPOSITORY_INVALID))
        remoteJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(remoteOperationState = state, errorMessage = null, operationMessage = null)
            try {
                operation(repository)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                showRemoteError(error)
            } finally {
                _uiState.value = _uiState.value.copy(remoteOperationState = RemoteOperationState.IDLE)
                remoteJob = null
            }
        }
    }

    private suspend fun refreshAfterRemote(repository: com.threeastudio.gitclonepush.core.model.LocalRepository) {
        val status = reader.status(repository)
        val branch = reader.currentBranch(repository)
        val commits = reader.recentCommits(repository, 20)
        val remoteState = synchronizer.readState(repository)
        val branches = runCatchingCancellable { branchReader?.listBranches(repository).orEmpty() }.getOrDefault(_uiState.value.branches)
        val mergeState = runCatchingCancellable { mergeReader?.readState(repository) ?: _uiState.value.mergeState }.getOrDefault(_uiState.value.mergeState)
        val rebaseState = runCatchingCancellable { rebaseReader?.readState(repository) ?: _uiState.value.rebaseState }.getOrDefault(_uiState.value.rebaseState)
        _uiState.value = _uiState.value.copy(
            status = status,
            branch = branch,
            commits = commits,
            remoteState = remoteState,
            branches = branches,
            mergeState = mergeState,
            rebaseState = rebaseState,
            selectedPaths = _uiState.value.selectedPaths.intersect(status.changes.map { it.path }.toSet()),
            errorMessage = null
        )
    }

    private fun runMutation(state: GitMutationState, operation: suspend (com.threeastudio.gitclonepush.core.model.LocalRepository) -> Unit) {
        if (_uiState.value.isRemovingClone || _uiState.value.cloneRemoved) return
        if (hasBusyOperation() || isRebaseActive()) return showMutationError(GitMutationException(GitMutationErrorCategory.UNRESOLVED_CONFLICTS))
        val repository = _uiState.value.localRepository ?: return showMutationError(GitMutationException(GitMutationErrorCategory.REPOSITORY_MISSING))
        mutationJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(mutationState = state, errorMessage = null)
            try {
                operation(repository)
                refreshStatusNow(repository)
                _uiState.value = _uiState.value.copy(mutationState = GitMutationState.IDLE, selectedPaths = emptySet())
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                showMutationError(error)
            } finally {
                _uiState.value = _uiState.value.copy(mutationState = GitMutationState.IDLE)
                mutationJob = null
            }
        }
    }

    private suspend fun refreshStatusNow(repository: com.threeastudio.gitclonepush.core.model.LocalRepository) {
        diagnostics.event(GitDiagnosticEvent.STATUS_REFRESH_STARTED, mapOf("repositoryId" to repositoryId))
        val status = reader.status(repository)
        val remoteState = runCatchingCancellable { synchronizer.readState(repository) }.getOrDefault(_uiState.value.remoteState)
        val rebaseState = runCatchingCancellable { rebaseReader?.readState(repository) ?: _uiState.value.rebaseState }.getOrDefault(_uiState.value.rebaseState)
        _uiState.value = _uiState.value.copy(status = status, remoteState = remoteState, rebaseState = rebaseState, selectedPaths = _uiState.value.selectedPaths.intersect(status.changes.map { it.path }.toSet()), errorMessage = null)
        diagnostics.event(GitDiagnosticEvent.STATUS_REFRESH_SUCCESS, mapOf("repositoryId" to repositoryId, "changedCount" to status.changes.size.toString()))
    }

    private fun showMutationError(error: Throwable) {
        val category = (error as? GitMutationException)?.category ?: GitMutationErrorCategory.UNKNOWN
        diagnostics.event(GitDiagnosticEvent.COMMIT_VALIDATION_FAILED, mapOf("repositoryId" to repositoryId, "category" to category.name))
        _uiState.value = _uiState.value.copy(errorMessage = mutationErrorMessage(category))
    }

    private fun showMergeError(error: Throwable) {
        val category = (error as? MergeOperationException)?.category ?: MergeErrorCategory.UNKNOWN
        _uiState.value = _uiState.value.copy(errorMessage = when (category) {
            MergeErrorCategory.SOURCE_NOT_FOUND -> "Select an existing local branch to merge."
            MergeErrorCategory.DETACHED_HEAD -> "Cannot merge while HEAD is detached."
            MergeErrorCategory.DIRTY_WORKTREE -> "Commit or stash local changes before merging."
            MergeErrorCategory.MERGE_IN_PROGRESS -> "A merge is already in progress."
            MergeErrorCategory.UNRESOLVED_CONFLICTS -> "Resolve and stage every conflict before continuing."
            MergeErrorCategory.AUTHOR_IDENTITY_MISSING -> "Your GitHub commit identity is unavailable. Please sign in again."
            MergeErrorCategory.COMMIT_MESSAGE_EMPTY -> "Enter a merge commit message."
            MergeErrorCategory.INVALID_PATH -> "The selected conflict path is invalid."
            MergeErrorCategory.REPOSITORY_INVALID -> "This local repository is unavailable."
            MergeErrorCategory.CANCELLED -> "Merge operation cancelled."
            MergeErrorCategory.GIT_OPERATION_FAILED, MergeErrorCategory.UNKNOWN -> "The merge operation could not be completed."
        })
    }

    private fun runRebaseOperation(state: RebaseOperationState, operation: suspend (com.threeastudio.gitclonepush.core.model.LocalRepository) -> Unit) {
        if (rebaseMutator == null || hasBusyOperation() || rebaseJob?.isActive == true || isMergeActive() || isHistoryActive()) return
        val repository = _uiState.value.localRepository ?: return showRebaseError(RebaseOperationException(RebaseErrorCategory.REPOSITORY_INVALID))
        rebaseJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(rebaseState = _uiState.value.rebaseState.copy(operationState = state), activeOperationMessage = "Processing rebase…", errorMessage = null)
            try { operation(repository); refreshWorkspace(repository) }
            catch (error: CancellationException) { throw error }
            catch (error: Throwable) { showRebaseError(error); runCatchingCancellable { refreshRebaseState(repository) } }
            finally { _uiState.value = _uiState.value.copy(activeOperationMessage = null); rebaseJob = null }
        }
    }

    private fun runHistoryMutation(message: String, operation: suspend (com.threeastudio.gitclonepush.core.model.LocalRepository) -> Unit) {
        if (hasBusyOperation() || isMergeActive() || isRebaseActive()) return
        val repository = _uiState.value.localRepository ?: return
        historyJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(errorMessage = null, activeOperationMessage = message)
            try { operation(repository); refreshWorkspace(repository); _uiState.value = _uiState.value.copy(stashMessage = "") }
            catch (error: CancellationException) { throw error }
            catch (error: Throwable) { _uiState.value = _uiState.value.copy(errorMessage = historyErrorMessage(error)); runCatchingCancellable { refreshWorkspace(repository) } }
            finally { _uiState.value = _uiState.value.copy(activeOperationMessage = null); historyJob = null }
        }
    }

    private fun historyErrorMessage(error: Throwable): String = when (error) {
        is RevertOperationException -> when (error.category) {
            RevertErrorCategory.DIRTY_WORKTREE -> "Commit or stash local changes before reverting."
            RevertErrorCategory.MAINLINE_PARENT_REQUIRED -> "Select a mainline parent to revert a merge commit."
            RevertErrorCategory.INVALID_MAINLINE_PARENT -> "The selected merge mainline is not supported by this Git engine."
            RevertErrorCategory.UNRESOLVED_CONFLICTS -> "Resolve and stage every revert conflict before continuing."
            else -> "The revert operation could not be completed."
        }
        is CherryPickOperationException -> when (error.category) {
            CherryPickErrorCategory.DIRTY_WORKTREE -> "Commit or stash local changes before cherry-picking."
            CherryPickErrorCategory.MERGE_COMMIT_MAINLINE_REQUIRED -> "Select a mainline parent to cherry-pick a merge commit."
            CherryPickErrorCategory.UNRESOLVED_CONFLICTS -> "Resolve and stage every cherry-pick conflict before continuing."
            else -> "The cherry-pick operation could not be completed."
        }
        is StashOperationException -> when (error.category) {
            StashErrorCategory.NOTHING_TO_STASH -> "There are no changes to stash."
            StashErrorCategory.APPLY_BLOCKED_BY_LOCAL_CHANGES -> "Commit or stash current changes before applying another stash."
            StashErrorCategory.STASH_CONFLICT -> "Resolve and stage the stash conflict before continuing."
            else -> "The stash operation could not be completed."
        }
        else -> "The local history operation could not be completed."
    }

    private fun showRebaseError(error: Throwable) {
        val category = (error as? RebaseOperationException)?.category ?: RebaseErrorCategory.UNKNOWN
        _uiState.value = _uiState.value.copy(errorMessage = when (category) {
            RebaseErrorCategory.DETACHED_HEAD -> "Cannot rebase while HEAD is detached."
            RebaseErrorCategory.REBASE_BLOCKED_BY_LOCAL_CHANGES -> "Commit local changes before rebasing."
            RebaseErrorCategory.REBASE_ALREADY_IN_PROGRESS -> "A rebase is already in progress."
            RebaseErrorCategory.MERGE_IN_PROGRESS -> "Finish or abort the current merge first."
            RebaseErrorCategory.TARGET_BRANCH_NOT_FOUND -> "Select an existing target branch."
            RebaseErrorCategory.SAME_BRANCH -> "Choose a different target branch."
            RebaseErrorCategory.PUBLISHED_HISTORY_REWRITE_BLOCKED -> "Published history cannot be rewritten safely."
            RebaseErrorCategory.REMOTE_STATE_NEEDS_REFRESH -> "Fetch before making a history-safety decision."
            RebaseErrorCategory.UNRESOLVED_CONFLICTS -> "Resolve and stage all conflicts before continuing."
            RebaseErrorCategory.NOT_IN_REBASE_STATE -> "No rebase is currently active."
            RebaseErrorCategory.INVALID_INTERACTIVE_PLAN, RebaseErrorCategory.INVALID_REWORD_MESSAGE -> "The interactive rebase plan is invalid."
            RebaseErrorCategory.REPOSITORY_INVALID -> "This local repository is unavailable."
            RebaseErrorCategory.CANCELLED -> "Rebase cancelled."
            else -> "The rebase operation could not be completed."
        })
    }

    private fun showRemoteError(error: Throwable) {
        val category = (error as? RemoteSyncException)?.category ?: RemoteSyncErrorCategory.UNKNOWN
        _uiState.value = _uiState.value.copy(errorMessage = remoteErrorMessage(category))
    }

    private fun showBranchError(error: Throwable) {
        val category = (error as? BranchOperationException)?.category ?: BranchErrorCategory.UNKNOWN
        _uiState.value = _uiState.value.copy(errorMessage = when (category) {
            BranchErrorCategory.INVALID_BRANCH_NAME -> "Branch name is invalid."
            BranchErrorCategory.BRANCH_ALREADY_EXISTS -> "A branch with that name already exists."
            BranchErrorCategory.BRANCH_NOT_FOUND -> "Branch was not found."
            BranchErrorCategory.REMOTE_BRANCH_NOT_FOUND -> "Remote-tracking branch was not found."
            BranchErrorCategory.START_POINT_NOT_FOUND -> "The branch start point was not found."
            BranchErrorCategory.CHECKOUT_WOULD_OVERWRITE_CHANGES -> "Local changes would be overwritten by checkout."
            BranchErrorCategory.CANNOT_DELETE_CURRENT_BRANCH -> "Cannot delete the currently checked-out branch."
            BranchErrorCategory.BRANCH_NOT_MERGED -> "Branch contains commits that are not merged."
            BranchErrorCategory.DETACHED_HEAD -> "Repository is in detached HEAD state."
            BranchErrorCategory.NO_UPSTREAM -> "No upstream branch is configured."
            BranchErrorCategory.MERGE_IN_PROGRESS -> "Finish or abort the current merge before changing branches."
            BranchErrorCategory.REPOSITORY_INVALID -> "This local repository is unavailable."
            BranchErrorCategory.CANCELLED -> "Branch operation cancelled."
            BranchErrorCategory.GIT_OPERATION_FAILED, BranchErrorCategory.UNKNOWN -> "The branch operation could not be completed."
        })
    }

    private fun remoteErrorMessage(category: RemoteSyncErrorCategory): String = when (category) {
        RemoteSyncErrorCategory.AUTHENTICATION_REQUIRED -> "Authentication is required for this remote operation."
        RemoteSyncErrorCategory.AUTHENTICATION_FAILED -> "GitHub rejected the stored authentication."
        RemoteSyncErrorCategory.NETWORK_UNAVAILABLE, RemoteSyncErrorCategory.TRANSPORT_FAILURE -> "The remote repository could not be reached."
        RemoteSyncErrorCategory.REMOTE_NOT_FOUND, RemoteSyncErrorCategory.NO_REMOTE -> "No valid GitHub remote is configured."
        RemoteSyncErrorCategory.NO_UPSTREAM -> "No upstream branch is configured."
        RemoteSyncErrorCategory.DETACHED_HEAD -> "This repository is in detached HEAD state."
        RemoteSyncErrorCategory.LOCAL_CHANGES_WOULD_BE_OVERWRITTEN -> "Cannot pull because local changes would be overwritten."
        RemoteSyncErrorCategory.DIVERGED -> "Branch histories have diverged. Merge locally before pushing."
        RemoteSyncErrorCategory.PUSH_REJECTED_NON_FAST_FORWARD -> "Push rejected because the remote contains newer commits."
        RemoteSyncErrorCategory.REMOTE_REJECTED -> "The remote rejected the push."
        RemoteSyncErrorCategory.CONFLICT_STATE -> "Resolve repository conflicts before synchronizing."
        RemoteSyncErrorCategory.REPOSITORY_INVALID -> "This local repository is unavailable."
        RemoteSyncErrorCategory.CANCELLED -> "Remote operation cancelled."
        RemoteSyncErrorCategory.UNKNOWN -> "The remote Git operation could not be completed."
    }

    private fun mutationErrorMessage(category: GitMutationErrorCategory): String = when (category) {
        GitMutationErrorCategory.INVALID_PATH -> "Select a valid repository file."
        GitMutationErrorCategory.NOTHING_TO_STAGE -> "No changes to stage."
        GitMutationErrorCategory.NOTHING_STAGED -> "No staged changes to commit."
        GitMutationErrorCategory.AUTHOR_IDENTITY_MISSING -> "Your GitHub commit identity is unavailable. Please sign in again."
        GitMutationErrorCategory.COMMIT_MESSAGE_EMPTY -> "Enter a commit message."
        GitMutationErrorCategory.UNRESOLVED_CONFLICTS -> "Resolve all conflicts before committing."
        GitMutationErrorCategory.REPOSITORY_MISSING -> "This local repository is unavailable."
        GitMutationErrorCategory.GIT_INDEX_FAILURE -> "The Git index could not be updated."
        GitMutationErrorCategory.GIT_COMMIT_FAILURE -> "The local commit could not be created."
        GitMutationErrorCategory.FILESYSTEM_FAILURE -> "The repository files could not be accessed."
        GitMutationErrorCategory.UNKNOWN -> "The Git operation could not be completed."
    }

    private fun safeCategory(error: Throwable): String = (error as? GitMutationException)?.category?.name ?: error::class.simpleName.orEmpty()

    private fun isMergeActive() = _uiState.value.mergeState.repositoryState != com.threeastudio.gitclonepush.core.model.MergeRepositoryState.SAFE
    private fun isRebaseActive() = _uiState.value.rebaseState.operationState != RebaseOperationState.IDLE
    private fun isHistoryActive() = _uiState.value.historyMutation.kind != HistoryMutationKind.NONE
    private fun hasBusyOperation() = _uiState.value.isRemovingClone || _uiState.value.cloneRemoved || mutationJob?.isActive == true || refreshJob?.isActive == true || remoteJob?.isActive == true || branchJob?.isActive == true || rebaseJob?.isActive == true || historyJob?.isActive == true || mergeJob?.isActive == true

    private inline fun <T> runCatchingCancellable(block: () -> T): Result<T> = try {
        Result.success(block())
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        Result.failure(error)
    }
    private suspend fun refreshMergeState(repository: com.threeastudio.gitclonepush.core.model.LocalRepository) {
        _uiState.value = _uiState.value.copy(mergeState = mergeReader?.readState(repository) ?: MergeState())
    }
    private suspend fun refreshRebaseState(repository: com.threeastudio.gitclonepush.core.model.LocalRepository) { _uiState.value = _uiState.value.copy(rebaseState = rebaseReader?.readState(repository) ?: RebaseState()) }
    private suspend fun refreshWorkspace(repository: com.threeastudio.gitclonepush.core.model.LocalRepository) {
        val status = reader.status(repository)
        val branch = reader.currentBranch(repository)
        val commits = reader.recentCommits(repository, 20)
        _uiState.value = _uiState.value.copy(status = status, branch = branch, commits = commits, mergeState = mergeReader?.readState(repository) ?: MergeState(), rebaseState = rebaseReader?.readState(repository) ?: RebaseState(), historyMutation = revertReader?.readState(repository)?.takeIf { it.kind != HistoryMutationKind.NONE } ?: cherryPickReader?.readState(repository)?.takeIf { it.kind != HistoryMutationKind.NONE } ?: HistoryMutationState(), stashes = stashReader?.list(repository).orEmpty(), selectedPaths = _uiState.value.selectedPaths.intersect(status.changes.map { it.path }.toSet()))
    }
}
