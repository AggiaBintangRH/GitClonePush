package com.threeastudio.gitclonepush.core.model

import java.time.Instant

enum class RepositoryVisibility { PUBLIC, PRIVATE }
enum class ThemeMode { SYSTEM, LIGHT, DARK }

data class GitRepository(
    val id: String,
    val name: String,
    val owner: String,
    val visibility: RepositoryVisibility,
    val language: String?,
    val defaultBranch: String,
    val updatedAt: String,
    val cloneUrl: String = ""
)

data class AuthenticatedUser(
    val id: Long,
    val login: String,
    val displayName: String?,
    val avatarUrl: String?,
    val email: String? = null
)

sealed interface AuthState {
    data object Loading : AuthState
    data object Unauthenticated : AuthState
    data class Authenticated(val user: AuthenticatedUser) : AuthState
}

data class LocalRepository(
    val id: String,
    val owner: String,
    val name: String,
    val directoryPath: String,
    val remoteUrl: String,
    val parentTreeUri: String? = null
)
data class GitBranch(val name: String, val isCurrent: Boolean)
data class GitCommit(val message: String, val hash: String, val author: String, val time: String)
data class RepositoryStatus(val changes: List<FileChange>, val clean: Boolean)
data class CommitRequest(val message: String, val authorName: String, val authorEmail: String)
data class GitRemote(val url: String)
data class GitCredentials(val username: String, val password: String)
data class GitAuthorIdentity(val name: String, val email: String)
enum class RemoteOperationState { IDLE, FETCHING, PULLING, PUSHING }
enum class RemoteStateFreshness { UNKNOWN, STALE, FRESH }
data class RemoteSyncState(
    val branch: String? = null,
    val upstream: String? = null,
    val ahead: Int? = null,
    val behind: Int? = null,
    val freshness: RemoteStateFreshness = RemoteStateFreshness.UNKNOWN,
    val isDetachedHead: Boolean = false,
    val hasUpstream: Boolean = false,
    val hasRemote: Boolean = false
)
enum class BranchKind { LOCAL, REMOTE }
data class GitBranchInfo(
    val name: String,
    val fullRef: String,
    val kind: BranchKind,
    val isCurrent: Boolean,
    val upstream: String? = null,
    val remoteName: String? = null,
    val remoteBranchName: String? = null
)
enum class GitMutationState { IDLE, STAGING, UNSTAGING, COMMITTING }
enum class BranchOperationState { IDLE, LOADING, CREATING, CHECKING_OUT, RENAMING, DELETING, SETTING_UPSTREAM, REFRESHING }

sealed interface CloneProgress {
    data object Preparing : CloneProgress
    data class Receiving(val current: Long, val total: Long?, val percent: Int?) : CloneProgress
    data class Completed(val localRepository: LocalRepository) : CloneProgress
}

sealed interface PullResult {
    data object AlreadyUpToDate : PullResult
    data class Updated(val commits: Int) : PullResult
    data object Diverged : PullResult
    data object NoUpstream : PullResult
    data object DetachedHead : PullResult
    data object LocalChangesWouldBeOverwritten : PullResult
    data class Conflict(val paths: List<String>) : PullResult
}

sealed interface PushResult {
    data object Pushed : PushResult
    data object AlreadyUpToDate : PushResult
}

enum class CloneStatus { NOT_CLONED, CLONING, CLONED, ERROR }

data class RepositoryCloneState(
    val status: CloneStatus = CloneStatus.NOT_CLONED,
    val progress: Int = 0,
    val errorMessage: String? = null
)

enum class FileChangeStatus { MODIFIED, ADDED, DELETED, RENAMED, CONFLICTED }

enum class GitFileState { UNTRACKED, ADDED, MODIFIED, DELETED, RENAMED, CONFLICTED }

data class FileChange(
    val path: String,
    val status: FileChangeStatus,
    val indexState: GitFileState? = null,
    val workTreeState: GitFileState? = null
)
data class RepositoryFile(val path: String, val isDirectory: Boolean)

sealed interface RepositoryFileEntry {
    val name: String
    val relativePath: String

    data class Directory(
        override val name: String,
        override val relativePath: String
    ) : RepositoryFileEntry

    data class File(
        override val name: String,
        override val relativePath: String,
        val sizeBytes: Long,
        val isBinary: Boolean
    ) : RepositoryFileEntry
}

sealed interface RepositoryFileContent {
    data class Text(val text: String, val truncated: Boolean) : RepositoryFileContent
    data class Binary(val sizeBytes: Long) : RepositoryFileContent
}
data class Commit(val message: String, val hash: String, val author: String, val time: String)

enum class GitChangeType { ADD, MODIFY, DELETE, RENAME, COPY }
data class GitCommitSummary(
    val id: String,
    val shortId: String,
    val message: String,
    val authorName: String?,
    val authorEmail: String?,
    val authoredAt: Instant,
    val parentCount: Int
)
data class GitChangedFile(
    val path: String,
    val oldPath: String? = null,
    val changeType: GitChangeType,
    val additions: Int?,
    val deletions: Int?,
    val isBinary: Boolean
)
data class GitCommitDetail(
    val id: String,
    val shortId: String,
    val message: String,
    val authorName: String?,
    val authorEmail: String?,
    val authoredAt: Instant,
    val parents: List<String>,
    val changedFiles: List<GitChangedFile>,
    val diffParentId: String?
)
data class GitHistoryPage(val commits: List<GitCommitSummary>, val hasMore: Boolean)
enum class DiffScope { COMMIT, UNSTAGED, STAGED }
data class DiffRequest(val scope: DiffScope, val commitId: String? = null, val path: String? = null, val parentId: String? = null)
enum class DiffLineType { CONTEXT, ADDED, REMOVED }
data class DiffLine(val type: DiffLineType, val text: String)
data class DiffHunk(val oldStart: Int, val oldCount: Int, val newStart: Int, val newCount: Int, val lines: List<DiffLine>)
data class FileDiff(
    val oldPath: String?,
    val newPath: String?,
    val changeType: GitChangeType,
    val hunks: List<DiffHunk>,
    val isBinary: Boolean,
    val truncated: Boolean,
    val additions: Int? = null,
    val deletions: Int? = null
)
data class DiffResult(val files: List<FileDiff>, val truncated: Boolean)

enum class RebaseOperationState { IDLE, RUNNING, CONFLICTED, STOPPED_FOR_EDIT, CONTINUING, SKIPPING, ABORTING }
enum class RebaseResultState { ALREADY_UP_TO_DATE, FAST_FORWARD, REBASED, CONFLICTED, STOPPED, FAILED }
data class RebaseState(
    val operationState: RebaseOperationState = RebaseOperationState.IDLE,
    val currentBranch: String? = null,
    val ontoBranch: String? = null,
    val currentCommit: String? = null,
    val conflictedPaths: List<String> = emptyList(),
    val canContinue: Boolean = false,
    val canSkip: Boolean = false,
    val canAbort: Boolean = false
)
sealed interface RebaseResult {
    data object AlreadyUpToDate : RebaseResult
    data object FastForward : RebaseResult
    data object Rebased : RebaseResult
    data class Conflicted(val state: RebaseState) : RebaseResult
    data class Stopped(val state: RebaseState) : RebaseResult
}
enum class RebaseAction { PICK, REWORD, SQUASH, FIXUP, DROP }
data class InteractiveRebaseItem(
    val commitId: String,
    val shortId: String,
    val message: String,
    val action: RebaseAction = RebaseAction.PICK,
    val editedMessage: String? = null
)

enum class HistoryMutationKind { NONE, REVERTING, CHERRY_PICKING, STASH_APPLYING }
data class HistoryMutationState(
    val kind: HistoryMutationKind = HistoryMutationKind.NONE,
    val conflictedPaths: List<String> = emptyList(),
    val canContinue: Boolean = false,
    val canSkip: Boolean = false,
    val canAbort: Boolean = false,
    val remainingCount: Int = 0
)
data class GitStashEntry(val index: Int, val commitId: String, val shortId: String, val message: String?, val createdAt: String? = null)

enum class MergeOperationState { IDLE, MERGING, CONFLICTED, CONTINUING, ABORTING }
enum class MergeRepositoryState { SAFE, MERGING, MERGING_RESOLVED }
enum class ConflictResolutionState { UNRESOLVED, STAGED }
enum class ConflictVersion { BASE, OURS, THEIRS }

data class ConflictFile(
    val path: String,
    val basePresent: Boolean,
    val oursPresent: Boolean,
    val theirsPresent: Boolean,
    val resolutionState: ConflictResolutionState = ConflictResolutionState.UNRESOLVED
)

data class MergeState(
    val operationState: MergeOperationState = MergeOperationState.IDLE,
    val repositoryState: MergeRepositoryState = MergeRepositoryState.SAFE,
    val sourceBranch: String? = null,
    val conflictedFiles: List<ConflictFile> = emptyList(),
    val canContinue: Boolean = false,
    val canAbort: Boolean = false
)

sealed interface MergeResult {
    data object AlreadyUpToDate : MergeResult
    data object FastForward : MergeResult
    data object Merged : MergeResult
    data class Conflicted(val state: MergeState) : MergeResult
}

data class ConflictVersionContent(
    val available: Boolean,
    val isBinary: Boolean,
    val preview: String? = null,
    val truncated: Boolean = false
)
