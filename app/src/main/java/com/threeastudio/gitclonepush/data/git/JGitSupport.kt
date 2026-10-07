package com.threeastudio.gitclonepush.data.git

import com.threeastudio.gitclonepush.core.model.*
import com.threeastudio.gitclonepush.core.security.SecureTokenStore
import com.threeastudio.gitclonepush.domain.repository.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.errors.GitAPIException
import org.eclipse.jgit.api.errors.TransportException
import org.eclipse.jgit.api.errors.NoHeadException
import org.eclipse.jgit.api.errors.RefNotFoundException
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.lib.ProgressMonitor
import org.eclipse.jgit.transport.CredentialsProvider
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.transport.RemoteRefUpdate
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import org.eclipse.jgit.revwalk.RevWalk
import java.io.File
import java.io.IOException
import java.net.URI
import java.nio.file.FileSystemException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class JGitCredentials(private val tokenStore: SecureTokenStore) : GitCredentialProvider {
    override suspend fun credentialsFor(remote: GitRemote): GitCredentials =
        tokenStore.read()?.accessToken?.takeIf { it.isNotBlank() }?.let { GitCredentials("x-access-token", it) }
            ?: throw CloneOperationException(CloneErrorCategory.AUTHENTICATION_REQUIRED)
}

class JGitRepositoryCloner(
    private val store: LocalRepositoryStore,
    private val credentials: GitCredentialProvider,
    private val validator: LocalRepositoryValidator = JGitLocalRepositoryValidator(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val diagnostics: GitDiagnostics = NoOpGitDiagnostics
) : RepositoryCloner {
    override fun clone(request: CloneRepositoryRequest): Flow<CloneProgress> = channelFlow {
        val coroutineContext = currentCoroutineContext()
        withContext(ioDispatcher) {
            val repositoryId = request.repository.id
            diagnostics.event(GitDiagnosticEvent.CLONE_REQUESTED, mapOf("repositoryId" to repositoryId))
            val destination = prepareDestination(request)
            var createdByAttempt = false
            var marker: File? = null
            try {
                diagnostics.event(GitDiagnosticEvent.CLONE_LOCAL_CHECK_STARTED, mapOf("repositoryId" to repositoryId))
                val existing = localRepository(request)
                if (destination.exists() && validator.validate(existing)) {
                    store.save(existing)
                    diagnostics.event(GitDiagnosticEvent.CLONE_ALREADY_EXISTS, mapOf("repositoryId" to repositoryId))
                    trySend(CloneProgress.Completed(existing))
                    return@withContext
                }
                if (destination.exists() && destination.listFiles()?.isNotEmpty() == true) {
                    throw CloneOperationException(CloneErrorCategory.DESTINATION_CONFLICT)
                }
                if (!destination.exists()) {
                    if (!destination.mkdir()) throw CloneOperationException(CloneErrorCategory.DESTINATION_CONFLICT)
                    createdByAttempt = true
                }
                val attemptMarker = File(destination.parentFile, ".${destination.name}.${java.util.UUID.randomUUID()}.clone-in-progress")
                if (!attemptMarker.createNewFile()) {
                    throw CloneOperationException(CloneErrorCategory.STORAGE_FAILURE)
                }
                marker = attemptMarker

                trySend(CloneProgress.Preparing)
                val auth = try {
                    credentials.credentialsFor(GitRemote(request.repository.cloneUrl))
                } catch (error: CloneOperationException) {
                    diagnostics.event(GitDiagnosticEvent.CLONE_REMOTE_AUTH_REQUIRED, mapOf("tokenPresent" to "false"))
                    throw error
                }
                diagnostics.event(GitDiagnosticEvent.CLONE_REMOTE_AUTH_REQUIRED, mapOf("tokenPresent" to "true"))
                diagnostics.event(GitDiagnosticEvent.CLONE_STARTED, mapOf("repositoryId" to repositoryId))

                val monitor = cloneProgressMonitor(repositoryId, coroutineContext) { progress ->
                    trySend(progress)
                }
                val git = Git.cloneRepository()
                    .setURI(request.repository.cloneUrl)
                    .setDirectory(destination)
                    .setCredentialsProvider(UsernamePasswordCredentialsProvider(auth.username, auth.password))
                    .setProgressMonitor(monitor)
                    .call()
                try {
                    val local = localRepository(request)
                    if (!validator.validate(local)) {
                        throw CloneOperationException(CloneErrorCategory.INVALID_REPOSITORY)
                    }
                    diagnostics.event(GitDiagnosticEvent.CLONE_LOCAL_VALIDATION_SUCCESS, mapOf("repositoryId" to repositoryId))
                    store.save(local)
                    diagnostics.event(GitDiagnosticEvent.CLONE_METADATA_SAVED, mapOf("repositoryId" to repositoryId))
                    marker.delete()
                    diagnostics.event(GitDiagnosticEvent.CLONE_COMPLETED, mapOf("repositoryId" to repositoryId))
                    trySend(CloneProgress.Completed(local))
                } finally {
                    git.close()
                }
            } catch (error: CancellationException) {
                diagnostics.event(GitDiagnosticEvent.CLONE_CANCELLED, mapOf("repositoryId" to repositoryId))
                cleanupPartialClone(destination, marker, createdByAttempt, repositoryId)
                throw error
            } catch (error: CloneOperationException) {
                diagnostics.event(GitDiagnosticEvent.CLONE_FAILED, mapOf("repositoryId" to repositoryId, "category" to error.category.name))
                cleanupPartialClone(destination, marker, createdByAttempt, repositoryId)
                throw error
            } catch (error: Exception) {
                val mapped = mapFailure(error)
                diagnostics.event(GitDiagnosticEvent.CLONE_FAILED, mapOf("repositoryId" to repositoryId, "category" to mapped.category.name))
                cleanupPartialClone(destination, marker, createdByAttempt, repositoryId)
                throw mapped
            }
        }
    }

    private fun prepareDestination(request: CloneRepositoryRequest): File {
        val managed = runCatching { store.repositoryDirectory(request.repository.id).canonicalFile }
            .getOrElse { throw CloneOperationException(CloneErrorCategory.STORAGE_FAILURE, it) }
        val requested = runCatching { File(request.destination).canonicalFile }
            .getOrElse { throw CloneOperationException(CloneErrorCategory.DESTINATION_CONFLICT, it) }
        if (requested != managed || File(request.destination).absoluteFile != requested) throw CloneOperationException(CloneErrorCategory.DESTINATION_CONFLICT)
        if (requested.exists() && !requested.isDirectory) throw CloneOperationException(CloneErrorCategory.DESTINATION_CONFLICT)
        val parent = requested.parentFile
        if (parent == null || (!parent.exists() && !parent.mkdirs())) throw CloneOperationException(CloneErrorCategory.STORAGE_FAILURE)
        return requested
    }

    private fun localRepository(request: CloneRepositoryRequest) = LocalRepository(
        id = request.repository.id,
        owner = request.repository.owner,
        name = request.repository.name,
        directoryPath = File(request.destination).canonicalPath,
        remoteUrl = request.repository.cloneUrl
    )

    private fun cloneProgressMonitor(
        repositoryId: String,
        coroutineContext: kotlin.coroutines.CoroutineContext,
        emit: (CloneProgress) -> Unit
    ) = object : ProgressMonitor {
        private var phase = "receiving"
        private var total = 0
        private var completed = 0

        override fun start(totalTasks: Int) = Unit
        override fun beginTask(title: String?, totalWork: Int) {
            phase = "receiving"
            total = totalWork
            completed = 0
        }
        override fun update(completed: Int) {
            this.completed += completed
            val percent = total.takeIf { it > 0 }?.let { (this.completed * 100 / it).coerceIn(0, 100) }
            diagnostics.event(
                GitDiagnosticEvent.CLONE_PROGRESS,
                buildMap {
                    put("repositoryId", repositoryId)
                    put("phase", phase)
                    percent?.let { put("percent", it.toString()) }
                }
            )
            emit(CloneProgress.Receiving(this.completed.toLong(), total.toLong().takeIf { it > 0 }, percent))
        }
        override fun endTask() = Unit
        override fun isCancelled() = !coroutineContext.isActive
        override fun showDuration(enabled: Boolean) = Unit
    }

    private fun cleanupPartialClone(destination: File, marker: File?, createdByAttempt: Boolean, repositoryId: String) {
        if (!createdByAttempt && marker == null) return
        diagnostics.event(GitDiagnosticEvent.CLONE_PARTIAL_CLEANUP_STARTED, mapOf("repositoryId" to repositoryId))
        val cleaned = runCatching {
            if (createdByAttempt) deleteOwnedClone(destination)
            marker?.delete()
            (!createdByAttempt || !destination.exists()) && marker?.exists() != true
        }.getOrDefault(false)
        if (cleaned) diagnostics.event(GitDiagnosticEvent.CLONE_PARTIAL_CLEANUP_SUCCESS, mapOf("repositoryId" to repositoryId))
    }

    private fun mapFailure(error: Exception) = CloneOperationException(
        category = when (error) {
            is TransportException -> CloneErrorCategory.GIT_TRANSPORT_FAILURE
            is FileSystemException -> CloneErrorCategory.STORAGE_FAILURE
            is IOException -> CloneErrorCategory.NETWORK_UNAVAILABLE
            is GitAPIException -> CloneErrorCategory.GIT_TRANSPORT_FAILURE
            else -> CloneErrorCategory.UNKNOWN
        },
        cause = error
    )
}

class JGitLocalRepositoryValidator(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : LocalRepositoryValidator {
    override suspend fun validate(repository: LocalRepository): Boolean = withContext(ioDispatcher) {
        runCatching {
            Git.open(File(repository.directoryPath)).use { git ->
                val localRepository = git.repository
                !localRepository.isBare && localRepository.workTree.isDirectory &&
                    cleanGithubRemote(localRepository.config.getString("remote", "origin", "url"))
            }
        }.getOrDefault(false)
    }

    private fun cleanGithubRemote(url: String?): Boolean = runCatching {
        val uri = URI(url ?: return false)
        uri.scheme.equals("https", ignoreCase = true) &&
            uri.host.equals("github.com", ignoreCase = true) &&
            uri.userInfo == null &&
            uri.path.orEmpty().startsWith("/") &&
            uri.path.orEmpty().endsWith(".git")
    }.getOrDefault(false)
}

class JGitRepositoryLoader {
    fun open(repository: LocalRepository): Git = Git.open(File(repository.directoryPath))
}

class JGitRepositoryReader(
    private val loader: JGitRepositoryLoader,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : GitRepositoryReader {
    override suspend fun status(repository: LocalRepository): RepositoryStatus = kotlinx.coroutines.withContext(ioDispatcher) {
        loader.open(repository).use { git ->
            val status = git.status().call()
            val paths = linkedSetOf<String>().apply {
                addAll(status.conflicting)
                addAll(status.added)
                addAll(status.changed)
                addAll(status.removed)
                addAll(status.modified)
                addAll(status.missing)
                addAll(status.untracked)
            }
            val changes = paths.map { path ->
                val index = when {
                    path in status.conflicting -> GitFileState.CONFLICTED
                    path in status.added -> GitFileState.ADDED
                    path in status.changed -> GitFileState.MODIFIED
                    path in status.removed -> GitFileState.DELETED
                    else -> null
                }
                val workTree = when {
                    path in status.conflicting -> GitFileState.CONFLICTED
                    path in status.modified -> GitFileState.MODIFIED
                    path in status.missing -> GitFileState.DELETED
                    path in status.untracked -> GitFileState.UNTRACKED
                    else -> null
                }
                FileChange(path, displayStatus(index, workTree), index, workTree)
            }.sortedBy { it.path }
            RepositoryStatus(changes, changes.isEmpty())
        }
    }

    private fun displayStatus(index: GitFileState?, workTree: GitFileState?): FileChangeStatus = when {
        index == GitFileState.CONFLICTED || workTree == GitFileState.CONFLICTED -> FileChangeStatus.CONFLICTED
        index == GitFileState.DELETED || workTree == GitFileState.DELETED -> FileChangeStatus.DELETED
        index == GitFileState.ADDED || workTree == GitFileState.UNTRACKED -> FileChangeStatus.ADDED
        else -> FileChangeStatus.MODIFIED
    }
    override suspend fun currentBranch(repository: LocalRepository): GitBranch = kotlinx.coroutines.withContext(ioDispatcher) { loader.open(repository).use { GitBranch(it.repository.branch, true) } }
    override suspend fun recentCommits(repository: LocalRepository, limit: Int): List<GitCommit> = kotlinx.coroutines.withContext(ioDispatcher) { loader.open(repository).use { git -> git.log().setMaxCount(limit).call().map { GitCommit(it.shortMessage, ObjectId.toString(it.id).take(7), it.authorIdent.name, DateTimeFormatter.ISO_LOCAL_DATE.format(Instant.ofEpochSecond(it.commitTime.toLong()).atZone(ZoneId.systemDefault()))) } } }
}

class JGitRepositoryMutator(
    private val loader: JGitRepositoryLoader,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val diagnostics: GitDiagnostics = NoOpGitDiagnostics,
    private val coordinator: GitRepositoryOperationCoordinator = GitRepositoryOperationCoordinator()
) : GitRepositoryMutator {
    private val mutationMutex = Mutex()

    override suspend fun stage(repository: LocalRepository, paths: List<String>) = mutate(repository, "stage") {
        val safePaths = paths.distinct().also(::validatePaths)
        diagnostics.event(GitDiagnosticEvent.STAGE_STARTED)
        loader.open(repository).use { git ->
            safePaths.forEach { path ->
                if (File(git.repository.workTree, path).exists()) git.add().addFilepattern(path).call()
                else git.rm().addFilepattern(path).call()
            }
        }
        diagnostics.event(GitDiagnosticEvent.STAGE_SUCCESS)
    }

    override suspend fun unstage(repository: LocalRepository, paths: List<String>) = mutate(repository, "unstage") {
        val safePaths = paths.distinct().also(::validatePaths)
        diagnostics.event(GitDiagnosticEvent.UNSTAGE_STARTED)
        loader.open(repository).use { git -> resetPaths(git.repository, safePaths) }
        diagnostics.event(GitDiagnosticEvent.UNSTAGE_SUCCESS)
    }

    override suspend fun stageAll(repository: LocalRepository) = mutate(repository, "stageAll") {
        diagnostics.event(GitDiagnosticEvent.STAGE_ALL_STARTED)
        loader.open(repository).use { git ->
            git.add().addFilepattern(".").call()
            git.add().setUpdate(true).addFilepattern(".").call()
        }
        diagnostics.event(GitDiagnosticEvent.STAGE_ALL_SUCCESS)
    }

    override suspend fun unstageAll(repository: LocalRepository) = mutate(repository, "unstageAll") {
        diagnostics.event(GitDiagnosticEvent.UNSTAGE_ALL_STARTED)
        loader.open(repository).use { git ->
            try {
                git.reset().setMode(org.eclipse.jgit.api.ResetCommand.ResetType.MIXED).call()
            } catch (_: NoHeadException) {
                clearIndex(git.repository)
            } catch (_: RefNotFoundException) {
                clearIndex(git.repository)
            }
        }
        diagnostics.event(GitDiagnosticEvent.UNSTAGE_ALL_SUCCESS)
    }

    override suspend fun commit(repository: LocalRepository, request: CommitRequest): GitCommit = withContext(ioDispatcher) {
        coordinator.withRepositoryLock {
            mutationMutex.withLock {
            if (request.message.trim().isBlank()) throw GitMutationException(GitMutationErrorCategory.COMMIT_MESSAGE_EMPTY)
            if (request.authorName.trim().isBlank() || request.authorEmail.trim().isBlank()) throw GitMutationException(GitMutationErrorCategory.AUTHOR_IDENTITY_MISSING)
            diagnostics.event(GitDiagnosticEvent.COMMIT_STARTED)
            loader.open(repository).use { git ->
                if (git.repository.repositoryState != org.eclipse.jgit.lib.RepositoryState.SAFE) {
                    throw GitMutationException(GitMutationErrorCategory.UNRESOLVED_CONFLICTS)
                }
                val status = git.status().call()
                if (status.conflicting.isNotEmpty()) throw GitMutationException(GitMutationErrorCategory.UNRESOLVED_CONFLICTS)
                if (status.added.isEmpty() && status.changed.isEmpty() && status.removed.isEmpty()) {
                    throw GitMutationException(GitMutationErrorCategory.NOTHING_STAGED)
                }
                try {
                    val commit = git.commit()
                        .setMessage(request.message.trim())
                        .setAuthor(PersonIdent(request.authorName.trim(), request.authorEmail.trim()))
                        .setCommitter(PersonIdent(request.authorName.trim(), request.authorEmail.trim()))
                        .call()
                    GitCommit(commit.shortMessage, ObjectId.toString(commit.id).take(7), request.authorName.trim(), "now")
                } catch (error: GitMutationException) {
                    throw error
                } catch (error: Exception) {
                    throw GitMutationException(GitMutationErrorCategory.GIT_COMMIT_FAILURE, error)
                }
            }
            }
        }
    }

    private suspend fun <T> mutate(repository: LocalRepository, operation: String, action: suspend () -> T): T = withContext(ioDispatcher) {
        coordinator.withRepositoryLock {
            mutationMutex.withLock {
            try {
                if (operation == "stage" || operation == "unstage") {
                    diagnostics.event(if (operation == "stage") GitDiagnosticEvent.STAGE_REQUESTED else GitDiagnosticEvent.UNSTAGE_REQUESTED)
                }
                action()
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: GitMutationException) {
                diagnostics.event(failureEvent(operation), mapOf("category" to error.category.name))
                throw error
            } catch (error: Exception) {
                val mapped = GitMutationException(GitMutationErrorCategory.GIT_INDEX_FAILURE, error)
                diagnostics.event(failureEvent(operation), mapOf("category" to mapped.category.name))
                throw mapped
            }
            }
        }
    }

    private fun failureEvent(operation: String): GitDiagnosticEvent = when (operation) {
        "stage" -> GitDiagnosticEvent.STAGE_FAILED
        "stageAll" -> GitDiagnosticEvent.STAGE_ALL_FAILED
        "unstage" -> GitDiagnosticEvent.UNSTAGE_FAILED
        else -> GitDiagnosticEvent.UNSTAGE_ALL_FAILED
    }

    private fun validatePaths(paths: List<String>) {
        if (paths.isEmpty()) throw GitMutationException(GitMutationErrorCategory.INVALID_PATH)
        if (paths.any { path ->
                path.isBlank() || path.startsWith('/') || path.startsWith('\\') ||
                    path.split('/').any { segment -> segment.isBlank() || segment == "." || segment == ".." || segment == ".git" }
            }) throw GitMutationException(GitMutationErrorCategory.INVALID_PATH)
    }

    private fun resetPaths(repository: Repository, paths: List<String>) {
        try {
            val command = Git(repository).reset()
            paths.forEach(command::addPath)
            command.call()
        } catch (_: NoHeadException) {
            removeFromIndex(repository, paths)
        } catch (_: RefNotFoundException) {
            removeFromIndex(repository, paths)
        }
    }

    private fun clearIndex(repository: Repository) = removeFromIndex(repository, emptyList())

    private fun removeFromIndex(repository: Repository, paths: List<String>) {
        val cache = repository.lockDirCache()
        try {
            val remaining = (0 until cache.entryCount).map(cache::getEntry).filter { paths.isNotEmpty() && it.pathString !in paths }
            val builder = cache.builder()
            remaining.forEach(builder::add)
            builder.commit()
        } finally {
            cache.unlock()
        }
    }
}

class GitRepositoryOperationCoordinator {
    private val mutex = Mutex()

    suspend fun <T> withRepositoryLock(block: suspend () -> T): T = mutex.withLock { block() }
}

class JGitRemoteSynchronizer(
    private val loader: JGitRepositoryLoader,
    private val credentials: GitCredentialProvider,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val diagnostics: GitDiagnostics = NoOpGitDiagnostics,
    private val coordinator: GitRepositoryOperationCoordinator = GitRepositoryOperationCoordinator(),
    private val allowLocalFileTransport: Boolean = false
) : GitRemoteSynchronizer {
    private val freshRepositories = mutableSetOf<String>()

    override suspend fun fetch(repository: LocalRepository) = withRemoteLock(repository) {
        diagnostics.event(GitDiagnosticEvent.FETCH_REQUESTED, safeRepository(repository))
        try {
            withGit(repository) { git ->
                diagnostics.event(GitDiagnosticEvent.FETCH_STARTED, safeRepository(repository))
                fetchLocked(git, repository)
            }
            freshRepositories += repository.directoryPath
            diagnostics.event(GitDiagnosticEvent.FETCH_SUCCESS, safeRepository(repository))
        } catch (error: CancellationException) {
            throw error
        } catch (error: RemoteSyncException) {
            diagnostics.event(GitDiagnosticEvent.FETCH_FAILED, safeFailure(repository, error))
            throw error
        } catch (error: Exception) {
            val mapped = mapFailure(error)
            diagnostics.event(GitDiagnosticEvent.FETCH_FAILED, safeFailure(repository, mapped))
            throw mapped
        }
    }

    override suspend fun readState(repository: LocalRepository): RemoteSyncState = withRemoteLock(repository) {
        withContext(ioDispatcher) {
            diagnostics.event(GitDiagnosticEvent.REMOTE_STATE_REFRESH_STARTED, safeRepository(repository))
            try {
                withGitBlocking(repository) { git -> readStateLocked(git, repository) }.also { state ->
                    diagnostics.event(
                        GitDiagnosticEvent.REMOTE_STATE_REFRESH_SUCCESS,
                        safeRepository(repository) + mapOf(
                            "branchPresent" to (state.branch != null).toString(),
                            "upstreamPresent" to state.hasUpstream.toString(),
                            "ahead" to (state.ahead ?: 0).toString(),
                            "behind" to (state.behind ?: 0).toString(),
                            "freshness" to state.freshness.name
                        )
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: RemoteSyncException) {
                throw error
            } catch (error: Exception) {
                throw mapFailure(error)
            }
        }
    }

    override suspend fun pull(repository: LocalRepository): PullResult = withRemoteLock(repository) {
        diagnostics.event(GitDiagnosticEvent.PULL_REQUESTED, safeRepository(repository))
        try {
            withGit(repository) { git ->
                diagnostics.event(GitDiagnosticEvent.PULL_STARTED, safeRepository(repository))
                val before = readStateLocked(git, repository)
                requirePullable(before)
                val status = git.status().call()
                if (!status.isClean) throw RemoteSyncException(RemoteSyncErrorCategory.LOCAL_CHANGES_WOULD_BE_OVERWRITTEN)
                fetchLocked(git, repository)
                val afterFetch = readStateLocked(git, repository).copy(freshness = RemoteStateFreshness.FRESH)
                val ahead = afterFetch.ahead ?: 0
                val behind = afterFetch.behind ?: 0
                when {
                    ahead > 0 && behind > 0 -> throw RemoteSyncException(RemoteSyncErrorCategory.DIVERGED)
                    behind == 0 -> {
                        diagnostics.event(GitDiagnosticEvent.PULL_UP_TO_DATE, safeRepository(repository))
                        PullResult.AlreadyUpToDate
                    }
                    else -> {
                        val upstream = afterFetch.upstream ?: throw RemoteSyncException(RemoteSyncErrorCategory.NO_UPSTREAM)
                        git.reset().setMode(org.eclipse.jgit.api.ResetCommand.ResetType.HARD).setRef(upstream).call()
                        diagnostics.event(GitDiagnosticEvent.PULL_FAST_FORWARD_SUCCESS, safeRepository(repository) + mapOf("behind" to behind.toString()))
                        PullResult.Updated(behind)
                    }
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: RemoteSyncException) {
            diagnostics.event(GitDiagnosticEvent.PULL_FAILED, safeFailure(repository, error))
            throw error
        } catch (error: Exception) {
            val mapped = mapFailure(error)
            diagnostics.event(GitDiagnosticEvent.PULL_FAILED, safeFailure(repository, mapped))
            throw mapped
        }
    }

    override suspend fun push(repository: LocalRepository): PushResult = withRemoteLock(repository) {
        diagnostics.event(GitDiagnosticEvent.PUSH_REQUESTED, safeRepository(repository))
        try {
            withGit(repository) { git ->
                diagnostics.event(GitDiagnosticEvent.PUSH_STARTED, safeRepository(repository))
                fetchLocked(git, repository)
                val state = readStateLocked(git, repository)
                requirePushable(state)
                if ((state.ahead ?: 0) > 0 && (state.behind ?: 0) > 0) {
                    throw RemoteSyncException(RemoteSyncErrorCategory.PUSH_REJECTED_NON_FAST_FORWARD)
                }
                val remote = remoteConfig(git.repository, state)
                val provider = credentials.provider(remote.url)
                val localRef = "refs/heads/${state.branch}"
                val remoteBranch = state.upstream
                    ?.removePrefix("refs/remotes/${remote.name}/")
                    ?.takeIf { it != state.upstream }
                    ?: throw RemoteSyncException(RemoteSyncErrorCategory.NO_UPSTREAM)
                val remoteRef = "refs/heads/$remoteBranch"
                val result = git.push()
                    .setRemote(remote.name)
                    .setRefSpecs(RefSpec("$localRef:$remoteRef"))
                    .setCredentialsProvider(provider)
                    .call()
                val updates = result.flatMap { it.remoteUpdates }
                val rejected = updates.firstOrNull { it.status == RemoteRefUpdate.Status.REJECTED_NONFASTFORWARD }
                if (rejected != null) throw RemoteSyncException(RemoteSyncErrorCategory.PUSH_REJECTED_NON_FAST_FORWARD)
                if (updates.any { it.status == RemoteRefUpdate.Status.REJECTED_OTHER_REASON || it.status == RemoteRefUpdate.Status.REJECTED_NODELETE }) {
                    throw RemoteSyncException(RemoteSyncErrorCategory.REMOTE_REJECTED)
                }
                updates.filter { it.status == RemoteRefUpdate.Status.OK || it.status == RemoteRefUpdate.Status.UP_TO_DATE }
                    .forEach { update ->
                        val trackingRef = state.upstream ?: return@forEach
                        git.repository.updateRef(trackingRef).apply {
                            setNewObjectId(update.newObjectId)
                        }.update()
                    }
                diagnostics.event(GitDiagnosticEvent.PUSH_SUCCESS, safeRepository(repository))
                if ((state.ahead ?: 0) == 0) PushResult.AlreadyUpToDate else PushResult.Pushed
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: RemoteSyncException) {
            if (error.category == RemoteSyncErrorCategory.PUSH_REJECTED_NON_FAST_FORWARD) diagnostics.event(GitDiagnosticEvent.PUSH_REJECTED, safeFailure(repository, error))
            else diagnostics.event(GitDiagnosticEvent.PUSH_FAILED, safeFailure(repository, error))
            throw error
        } catch (error: Exception) {
            val mapped = mapFailure(error)
            diagnostics.event(GitDiagnosticEvent.PUSH_FAILED, safeFailure(repository, mapped))
            throw mapped
        }
    }

    private suspend fun <T> withRemoteLock(repository: LocalRepository, block: suspend () -> T): T = withContext(ioDispatcher) {
        coordinator.withRepositoryLock(block)
    }

    private suspend fun <T> withGit(repository: LocalRepository, block: suspend (Git) -> T): T = withContext(ioDispatcher) {
        loader.open(repository).use { git -> block(git) }
    }

    private fun <T> withGitBlocking(repository: LocalRepository, block: (Git) -> T): T = loader.open(repository).use(block)

    private suspend fun fetchLocked(git: Git, repository: LocalRepository) {
        if (git.repository.repositoryState != org.eclipse.jgit.lib.RepositoryState.SAFE) {
            throw RemoteSyncException(RemoteSyncErrorCategory.CONFLICT_STATE)
        }
        val state = readStateLocked(git, repository)
        val remote = remoteConfig(git.repository, state)
        git.fetch().setRemote(remote.name).setCredentialsProvider(credentials.provider(remote.url)).call()
        freshRepositories += repository.directoryPath
    }

    private fun readStateLocked(git: Git, repository: LocalRepository): RemoteSyncState {
        val localRepository = git.repository
        val fullBranch = localRepository.fullBranch
        val detached = fullBranch == null || !fullBranch.startsWith("refs/heads/")
        val branch = fullBranch?.removePrefix("refs/heads/")?.takeIf { !detached }
        val remoteName = branch?.let { localRepository.config.getString("branch", it, "remote") }
        val upstream = branch?.let { configuredTrackingBranch(localRepository, it) }
        val hasRemote = localRepository.config.getSubsections("remote").isNotEmpty()
        if (detached) return RemoteSyncState(branch = null, isDetachedHead = true, hasRemote = hasRemote, freshness = freshness(repository))
        if (upstream == null || remoteName == null || remoteName == ".") {
            return RemoteSyncState(branch = branch, isDetachedHead = false, hasRemote = hasRemote, freshness = freshness(repository))
        }
        val localId = localRepository.resolve("HEAD")
        val upstreamId = localRepository.resolve(upstream)
        val counts = if (localId != null && upstreamId != null) ancestryCounts(localRepository, localId, upstreamId) else 0 to 0
        return RemoteSyncState(branch, upstream, counts.first, counts.second, freshness(repository), false, true, hasRemote)
    }

    private fun ancestryCounts(repository: Repository, local: ObjectId, upstream: ObjectId): Pair<Int, Int> =
        countExclusive(repository, local, upstream) to countExclusive(repository, upstream, local)

    private fun countExclusive(repository: Repository, start: ObjectId, stop: ObjectId): Int = RevWalk(repository).use { walk ->
        walk.markStart(walk.parseCommit(start))
        walk.markUninteresting(walk.parseCommit(stop))
        walk.count()
    }

    private data class RemoteConfig(val name: String, val url: String)

    private fun remoteConfig(repository: Repository, state: RemoteSyncState): RemoteConfig {
        val name = state.upstream?.substringAfter("refs/remotes/")?.substringBefore('/')
            ?: state.branch?.let { repository.config.getString("branch", it, "remote") }
            ?: repository.config.getSubsections("remote").firstOrNull()
            ?: throw RemoteSyncException(RemoteSyncErrorCategory.NO_REMOTE)
        val url = repository.config.getString("remote", name, "url")
            ?: throw RemoteSyncException(RemoteSyncErrorCategory.NO_REMOTE)
        ensureCleanRemoteUrl(url, allowLocalFileTransport)
        return RemoteConfig(name, url)
    }

    private fun requirePullable(state: RemoteSyncState) {
        if (state.isDetachedHead) throw RemoteSyncException(RemoteSyncErrorCategory.DETACHED_HEAD)
        if (!state.hasUpstream) throw RemoteSyncException(RemoteSyncErrorCategory.NO_UPSTREAM)
    }

    private fun requirePushable(state: RemoteSyncState) = requirePullable(state)

    private fun ensureCleanRemoteUrl(url: String, allowLocalFileTransport: Boolean) {
        val uri = runCatching { URI(url) }.getOrElse { throw RemoteSyncException(RemoteSyncErrorCategory.REMOTE_NOT_FOUND) }
        val githubHttps = uri.scheme.equals("https", true) && uri.host.equals("github.com", true)
        val localTestTransport = allowLocalFileTransport && uri.scheme.equals("file", true) && uri.host == null
        if ((!githubHttps && !localTestTransport) || uri.userInfo != null) {
            throw RemoteSyncException(RemoteSyncErrorCategory.REMOTE_NOT_FOUND)
        }
    }

    private fun freshness(repository: LocalRepository) = if (repository.directoryPath in freshRepositories) RemoteStateFreshness.FRESH else RemoteStateFreshness.UNKNOWN

    private fun safeRepository(repository: LocalRepository) = mapOf("repositoryId" to repository.id)
    private fun safeFailure(repository: LocalRepository, error: RemoteSyncException) = safeRepository(repository) + mapOf("category" to error.category.name)

    private suspend fun GitCredentialProvider.provider(remote: String): CredentialsProvider {
        val value = credentialsFor(GitRemote(remote))
        return UsernamePasswordCredentialsProvider(value.username, value.password)
    }

    private fun mapFailure(error: Exception): RemoteSyncException = when (error) {
        is RemoteSyncException -> error
        is TransportException -> RemoteSyncException(RemoteSyncErrorCategory.TRANSPORT_FAILURE, error)
        is IOException -> RemoteSyncException(RemoteSyncErrorCategory.NETWORK_UNAVAILABLE, error)
        is NoHeadException -> RemoteSyncException(RemoteSyncErrorCategory.DETACHED_HEAD, error)
        else -> RemoteSyncException(RemoteSyncErrorCategory.UNKNOWN, error)
    }
}
