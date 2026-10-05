package com.threeastudio.gitclonepush.core.model

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

data class AuthenticatedUser(val id: Long, val login: String, val displayName: String?, val avatarUrl: String?)

sealed interface AuthState {
    data object Loading : AuthState
    data object Unauthenticated : AuthState
    data class Authenticated(val user: AuthenticatedUser) : AuthState
}

data class LocalRepository(val id: String, val owner: String, val name: String, val directoryPath: String, val remoteUrl: String)
data class GitBranch(val name: String, val isCurrent: Boolean)
data class GitCommit(val message: String, val hash: String, val author: String, val time: String)
data class RepositoryStatus(val changes: List<FileChange>, val clean: Boolean)
data class CommitRequest(val message: String, val authorName: String, val authorEmail: String)
data class GitRemote(val url: String)
data class GitCredentials(val username: String, val password: String)
data class GitAuthorIdentity(val name: String, val email: String)

sealed interface CloneProgress {
    data object Preparing : CloneProgress
    data class Receiving(val current: Long, val total: Long?, val percent: Int?) : CloneProgress
    data class Completed(val localRepository: LocalRepository) : CloneProgress
}

sealed interface PullResult {
    data object AlreadyUpToDate : PullResult
    data class Updated(val commits: Int) : PullResult
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

enum class FileChangeStatus { MODIFIED, ADDED, DELETED, RENAMED }

data class FileChange(val path: String, val status: FileChangeStatus)
data class RepositoryFile(val path: String, val isDirectory: Boolean)
data class Commit(val message: String, val hash: String, val author: String, val time: String)
