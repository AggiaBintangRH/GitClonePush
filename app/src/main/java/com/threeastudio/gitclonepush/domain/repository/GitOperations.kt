package com.threeastudio.gitclonepush.domain.repository

import com.threeastudio.gitclonepush.core.model.*
import kotlinx.coroutines.flow.Flow

data class CloneRepositoryRequest(val repository: GitRepository, val destination: String)
interface RepositoryCloner { fun clone(request: CloneRepositoryRequest): Flow<CloneProgress> }
fun interface LocalRepositoryValidator {
    suspend fun validate(repository: LocalRepository): Boolean
}

enum class CloneErrorCategory {
    AUTHENTICATION_REQUIRED,
    AUTHENTICATION_FAILED,
    NETWORK_UNAVAILABLE,
    DESTINATION_CONFLICT,
    STORAGE_FAILURE,
    INVALID_REPOSITORY,
    GIT_TRANSPORT_FAILURE,
    CANCELLED,
    UNKNOWN
}

class CloneOperationException(
    val category: CloneErrorCategory,
    cause: Throwable? = null
) : Exception(category.name, cause)
interface GitRepositoryReader {
    suspend fun status(repository: LocalRepository): RepositoryStatus
    suspend fun currentBranch(repository: LocalRepository): GitBranch
    suspend fun recentCommits(repository: LocalRepository, limit: Int): List<GitCommit>
}
interface GitBranchReader {
    suspend fun listBranches(repository: LocalRepository): List<GitBranchInfo>
}
interface GitBranchMutator {
    suspend fun create(repository: LocalRepository, name: String, startPoint: String = "HEAD", checkout: Boolean = false)
    suspend fun checkout(repository: LocalRepository, name: String)
    suspend fun createTracking(repository: LocalRepository, remoteTrackingRef: String, localName: String, checkout: Boolean = true)
    suspend fun setUpstream(repository: LocalRepository, localName: String, remoteName: String, remoteBranchName: String)
    suspend fun removeUpstream(repository: LocalRepository, localName: String)
    suspend fun rename(repository: LocalRepository, oldName: String, newName: String)
    suspend fun delete(repository: LocalRepository, name: String)
}
enum class BranchErrorCategory {
    INVALID_BRANCH_NAME, BRANCH_ALREADY_EXISTS, BRANCH_NOT_FOUND, REMOTE_BRANCH_NOT_FOUND,
    START_POINT_NOT_FOUND, CHECKOUT_WOULD_OVERWRITE_CHANGES, CANNOT_DELETE_CURRENT_BRANCH,
    BRANCH_NOT_MERGED, DETACHED_HEAD, NO_UPSTREAM, MERGE_IN_PROGRESS, REPOSITORY_INVALID, GIT_OPERATION_FAILED,
    CANCELLED, UNKNOWN
}
class BranchOperationException(val category: BranchErrorCategory, cause: Throwable? = null) : Exception(category.name, cause)
interface GitRepositoryMutator {
    suspend fun stage(repository: LocalRepository, paths: List<String>)
    suspend fun unstage(repository: LocalRepository, paths: List<String>)
    suspend fun stageAll(repository: LocalRepository)
    suspend fun unstageAll(repository: LocalRepository)
    suspend fun commit(repository: LocalRepository, request: CommitRequest): GitCommit
}

enum class GitMutationErrorCategory {
    REPOSITORY_MISSING,
    INVALID_PATH,
    NOTHING_TO_STAGE,
    NOTHING_STAGED,
    AUTHOR_IDENTITY_MISSING,
    COMMIT_MESSAGE_EMPTY,
    UNRESOLVED_CONFLICTS,
    GIT_INDEX_FAILURE,
    GIT_COMMIT_FAILURE,
    FILESYSTEM_FAILURE,
    UNKNOWN
}

class GitMutationException(
    val category: GitMutationErrorCategory,
    cause: Throwable? = null
) : Exception(category.name, cause)
interface GitRemoteSynchronizer {
    suspend fun fetch(repository: LocalRepository)
    suspend fun pull(repository: LocalRepository): PullResult
    suspend fun push(repository: LocalRepository): PushResult
    suspend fun readState(repository: LocalRepository): RemoteSyncState = RemoteSyncState()
}

enum class RemoteSyncErrorCategory {
    AUTHENTICATION_REQUIRED, AUTHENTICATION_FAILED, NETWORK_UNAVAILABLE,
    REMOTE_NOT_FOUND, NO_REMOTE, NO_UPSTREAM, DETACHED_HEAD,
    LOCAL_CHANGES_WOULD_BE_OVERWRITTEN, DIVERGED, PUSH_REJECTED_NON_FAST_FORWARD,
    REMOTE_REJECTED, TRANSPORT_FAILURE, CONFLICT_STATE, REPOSITORY_INVALID,
    CANCELLED, UNKNOWN
}

class RemoteSyncException(
    val category: RemoteSyncErrorCategory,
    cause: Throwable? = null
) : Exception(category.name, cause)
interface LocalRepositoryStore {
    fun repositoryDirectory(repositoryId: String): java.io.File
    suspend fun listRepositories(): List<LocalRepository>
    suspend fun save(repository: LocalRepository)
}

/** Removes only a repository clone owned by the application. */
interface LocalRepositoryRemover {
    suspend fun remove(repositoryId: String)
}
interface GitCredentialProvider { suspend fun credentialsFor(remote: GitRemote): GitCredentials }

interface GitMergeReader {
    suspend fun readState(repository: LocalRepository): MergeState
    suspend fun readConflictVersion(repository: LocalRepository, path: String, version: ConflictVersion): ConflictVersionContent
}

interface GitMergeMutator {
    suspend fun merge(repository: LocalRepository, sourceBranch: String, author: GitAuthorIdentity? = null): MergeResult
    suspend fun useOurs(repository: LocalRepository, path: String)
    suspend fun useTheirs(repository: LocalRepository, path: String)
    suspend fun continueMerge(repository: LocalRepository, request: CommitRequest): GitCommit
    suspend fun abortMerge(repository: LocalRepository)
}

enum class MergeErrorCategory {
    SOURCE_NOT_FOUND, DETACHED_HEAD, DIRTY_WORKTREE, MERGE_IN_PROGRESS,
    UNRESOLVED_CONFLICTS, AUTHOR_IDENTITY_MISSING, COMMIT_MESSAGE_EMPTY,
    REPOSITORY_INVALID, INVALID_PATH, GIT_OPERATION_FAILED, CANCELLED, UNKNOWN
}

class MergeOperationException(val category: MergeErrorCategory, cause: Throwable? = null) : Exception(category.name, cause)

interface GitHistoryReader {
    suspend fun loadPage(repository: LocalRepository, skip: Int, limit: Int): GitHistoryPage
    suspend fun loadDetail(repository: LocalRepository, commitId: String): GitCommitDetail
}

interface GitDiffReader {
    suspend fun read(repository: LocalRepository, request: DiffRequest): DiffResult
}

enum class HistoryDiffErrorCategory { REPOSITORY_INVALID, COMMIT_NOT_FOUND, PARENT_NOT_FOUND, PATH_NOT_FOUND, DIFF_TOO_LARGE, BINARY_UNSUPPORTED, READ_FAILED, CANCELLED, UNKNOWN }
class HistoryDiffException(val category: HistoryDiffErrorCategory, cause: Throwable? = null) : Exception(category.name, cause)

interface GitRebaseReader { suspend fun readState(repository: LocalRepository): RebaseState }
interface GitRebaseMutator {
    suspend fun start(repository: LocalRepository, ontoBranch: String): RebaseResult
    suspend fun continueRebase(repository: LocalRepository): RebaseResult
    suspend fun skipCommit(repository: LocalRepository): RebaseResult
    suspend fun abort(repository: LocalRepository)
    suspend fun interactive(repository: LocalRepository, plan: List<InteractiveRebaseItem>): RebaseResult
}
enum class RebaseErrorCategory {
    DETACHED_HEAD, REBASE_BLOCKED_BY_LOCAL_CHANGES, REBASE_ALREADY_IN_PROGRESS,
    MERGE_IN_PROGRESS, TARGET_BRANCH_NOT_FOUND, SAME_BRANCH, PUBLISHED_HISTORY_REWRITE_BLOCKED,
    REMOTE_STATE_NEEDS_REFRESH, UNRESOLVED_CONFLICTS, NOT_IN_REBASE_STATE,
    INVALID_INTERACTIVE_PLAN, INVALID_REWORD_MESSAGE, REBASE_FAILED, CONTINUE_FAILED,
    SKIP_FAILED, ABORT_FAILED, REPOSITORY_INVALID, CANCELLED, UNKNOWN
}
class RebaseOperationException(val category: RebaseErrorCategory, cause: Throwable? = null) : Exception(category.name, cause)

interface GitRevertReader { suspend fun readState(repository: LocalRepository): HistoryMutationState }
interface GitRevertMutator {
    suspend fun revert(repository: LocalRepository, commitId: String, mainlineParent: Int? = null)
    suspend fun continueRevert(repository: LocalRepository)
    suspend fun abortRevert(repository: LocalRepository)
}
enum class RevertErrorCategory { DIRTY_WORKTREE, DETACHED_HEAD, MAINLINE_PARENT_REQUIRED, INVALID_MAINLINE_PARENT, REVERT_IN_PROGRESS, MERGE_OR_OTHER_OPERATION_IN_PROGRESS, UNRESOLVED_CONFLICTS, NOT_IN_REVERT_STATE, REPOSITORY_INVALID, REVERT_FAILED, CANCELLED, UNKNOWN }
class RevertOperationException(val category: RevertErrorCategory, cause: Throwable? = null) : Exception(category.name, cause)

interface GitCherryPickReader { suspend fun readState(repository: LocalRepository): HistoryMutationState }
interface GitCherryPickMutator {
    suspend fun cherryPick(repository: LocalRepository, commitIds: List<String>, mainlineParents: Map<String, Int> = emptyMap())
    suspend fun continueCherryPick(repository: LocalRepository)
    suspend fun skipCherryPick(repository: LocalRepository)
    suspend fun abortCherryPick(repository: LocalRepository)
}
enum class CherryPickErrorCategory { DIRTY_WORKTREE, DETACHED_HEAD, MERGE_COMMIT_MAINLINE_REQUIRED, INVALID_MAINLINE_PARENT, CHERRY_PICK_IN_PROGRESS, OTHER_OPERATION_IN_PROGRESS, UNRESOLVED_CONFLICTS, NOT_IN_CHERRY_PICK_STATE, INVALID_SEQUENCE, REPOSITORY_INVALID, CHERRY_PICK_FAILED, CANCELLED, UNKNOWN }
class CherryPickOperationException(val category: CherryPickErrorCategory, cause: Throwable? = null) : Exception(category.name, cause)

interface GitStashReader { suspend fun list(repository: LocalRepository): List<GitStashEntry> }
interface GitStashMutator {
    suspend fun create(repository: LocalRepository, message: String?, includeUntracked: Boolean): GitStashEntry
    suspend fun apply(repository: LocalRepository, index: Int)
    suspend fun pop(repository: LocalRepository, index: Int)
    suspend fun drop(repository: LocalRepository, index: Int)
}
enum class StashErrorCategory { NOTHING_TO_STASH, STASH_NOT_FOUND, APPLY_BLOCKED_BY_LOCAL_CHANGES, STASH_CONFLICT, OPERATION_IN_PROGRESS, REPOSITORY_INVALID, STASH_FAILED, CANCELLED, UNKNOWN }
class StashOperationException(val category: StashErrorCategory, cause: Throwable? = null) : Exception(category.name, cause)
