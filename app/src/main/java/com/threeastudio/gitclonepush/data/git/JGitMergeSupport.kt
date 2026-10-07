package com.threeastudio.gitclonepush.data.git

import com.threeastudio.gitclonepush.core.model.*
import com.threeastudio.gitclonepush.domain.repository.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.MergeCommand
import org.eclipse.jgit.api.errors.CheckoutConflictException
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.lib.RepositoryState
import java.io.ByteArrayOutputStream
import java.io.File

class JGitMergeSupport(
    private val loader: JGitRepositoryLoader,
    private val coordinator: GitRepositoryOperationCoordinator,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val diagnostics: GitDiagnostics = NoOpGitDiagnostics,
    private val identityReader: GitAuthorIdentityReader? = null
) : GitMergeReader, GitMergeMutator {

    override suspend fun readState(repository: LocalRepository): MergeState = withContext(ioDispatcher) {
        diagnostics.event(GitDiagnosticEvent.MERGE_STATE_REFRESH_STARTED, safe(repository))
        loader.open(repository).use { git -> stateOf(git).also { state ->
            diagnostics.event(GitDiagnosticEvent.MERGE_STATE_REFRESH_SUCCESS, safe(repository) + mapOf(
                "state" to state.repositoryState.name,
                "conflictCount" to state.conflictedFiles.size.toString()
            ))
        }}
    }

    override suspend fun readConflictVersion(repository: LocalRepository, path: String, version: ConflictVersion): ConflictVersionContent = withContext(ioDispatcher) {
        validatePath(path)
        diagnostics.event(GitDiagnosticEvent.CONFLICT_VERSION_REQUESTED, safe(repository))
        loader.open(repository).use { git ->
            val stage = when (version) { ConflictVersion.BASE -> 1; ConflictVersion.OURS -> 2; ConflictVersion.THEIRS -> 3 }
            val entry = findStageEntry(git, path, stage)
            val result = entry?.let { readContent(git, it.objectId) } ?: ConflictVersionContent(false, false)
            diagnostics.event(GitDiagnosticEvent.CONFLICT_VERSION_REQUESTED, safe(repository) + mapOf("versionPresent" to result.available.toString()))
            result
        }
    }

    override suspend fun merge(repository: LocalRepository, sourceBranch: String, author: GitAuthorIdentity?): MergeResult = mutate(repository) {
        diagnostics.event(GitDiagnosticEvent.MERGE_REQUESTED, safe(repository))
        validateBranchName(sourceBranch)
        val current = it.repository.fullBranch
        if (current == null || !current.startsWith(Constants.R_HEADS)) throw MergeOperationException(MergeErrorCategory.DETACHED_HEAD)
        if (it.repository.repositoryState != RepositoryState.SAFE) throw MergeOperationException(MergeErrorCategory.MERGE_IN_PROGRESS)
        val source = it.repository.findRef("refs/heads/$sourceBranch") ?: throw MergeOperationException(MergeErrorCategory.SOURCE_NOT_FOUND)
        if (!it.status().call().isClean) throw MergeOperationException(MergeErrorCategory.DIRTY_WORKTREE)
        diagnostics.event(GitDiagnosticEvent.MERGE_STARTED, safe(repository))
        val effectiveAuthor = author ?: identityReader?.read()
        effectiveAuthor?.let { identity ->
            if (identity.name.isBlank() || identity.email.isBlank()) throw MergeOperationException(MergeErrorCategory.AUTHOR_IDENTITY_MISSING)
            it.repository.config.setString("user", null, "name", identity.name.trim())
            it.repository.config.setString("user", null, "email", identity.email.trim())
            it.repository.config.save()
        }
        val command = it.merge().include(source).setFastForward(MergeCommand.FastForwardMode.FF)
        val result = try { command.call() } catch (error: org.eclipse.jgit.api.errors.NoFilepatternException) { throw MergeOperationException(MergeErrorCategory.GIT_OPERATION_FAILED, error) }
        val state = stateOf(it, sourceBranch)
        when (result.mergeStatus) {
            org.eclipse.jgit.api.MergeResult.MergeStatus.ALREADY_UP_TO_DATE -> { diagnostics.event(GitDiagnosticEvent.MERGE_ALREADY_UP_TO_DATE, safe(repository)); MergeResult.AlreadyUpToDate }
            org.eclipse.jgit.api.MergeResult.MergeStatus.FAST_FORWARD -> { diagnostics.event(GitDiagnosticEvent.MERGE_FAST_FORWARD_SUCCESS, safe(repository)); MergeResult.FastForward }
            org.eclipse.jgit.api.MergeResult.MergeStatus.CONFLICTING -> { diagnostics.event(GitDiagnosticEvent.MERGE_CONFLICT, safe(repository) + mapOf("conflictCount" to state.conflictedFiles.size.toString())); MergeResult.Conflicted(state.copy(operationState = MergeOperationState.CONFLICTED)) }
            else -> { diagnostics.event(GitDiagnosticEvent.MERGE_SUCCESS, safe(repository)); MergeResult.Merged }
        }
    }

    override suspend fun useOurs(repository: LocalRepository, path: String) { checkoutVersion(repository, path, org.eclipse.jgit.api.CheckoutCommand.Stage.OURS, GitDiagnosticEvent.CONFLICT_USE_OURS) }
    override suspend fun useTheirs(repository: LocalRepository, path: String) { checkoutVersion(repository, path, org.eclipse.jgit.api.CheckoutCommand.Stage.THEIRS, GitDiagnosticEvent.CONFLICT_USE_THEIRS) }

    override suspend fun continueMerge(repository: LocalRepository, request: CommitRequest): GitCommit = mutate(repository) {
        diagnostics.event(GitDiagnosticEvent.MERGE_CONTINUE_REQUESTED, safe(repository))
        if (it.repository.repositoryState == RepositoryState.SAFE) throw MergeOperationException(MergeErrorCategory.MERGE_IN_PROGRESS)
        if (request.message.trim().isBlank()) throw MergeOperationException(MergeErrorCategory.COMMIT_MESSAGE_EMPTY)
        if (request.authorName.trim().isBlank() || request.authorEmail.trim().isBlank()) throw MergeOperationException(MergeErrorCategory.AUTHOR_IDENTITY_MISSING)
        val status = it.status().call()
        if (status.conflicting.isNotEmpty()) throw MergeOperationException(MergeErrorCategory.UNRESOLVED_CONFLICTS)
        try {
            val commit = it.commit().setMessage(request.message.trim()).setAuthor(PersonIdent(request.authorName.trim(), request.authorEmail.trim())).setCommitter(PersonIdent(request.authorName.trim(), request.authorEmail.trim())).call()
            diagnostics.event(GitDiagnosticEvent.MERGE_CONTINUE_SUCCESS, safe(repository))
            GitCommit(commit.shortMessage, commit.name.take(7), request.authorName.trim(), "now")
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) { diagnostics.event(GitDiagnosticEvent.MERGE_CONTINUE_FAILED, safe(repository) + mapOf("category" to MergeErrorCategory.GIT_OPERATION_FAILED.name)); throw MergeOperationException(MergeErrorCategory.GIT_OPERATION_FAILED, error) }
    }

    override suspend fun abortMerge(repository: LocalRepository) = mutate(repository) {
        diagnostics.event(GitDiagnosticEvent.MERGE_ABORT_REQUESTED, safe(repository))
        if (it.repository.repositoryState == RepositoryState.SAFE) throw MergeOperationException(MergeErrorCategory.MERGE_IN_PROGRESS)
        try {
            val head = it.repository.resolve(Constants.ORIG_HEAD)
                ?: it.repository.resolve(Constants.HEAD)
                ?: throw MergeOperationException(MergeErrorCategory.GIT_OPERATION_FAILED)
            it.reset().setMode(org.eclipse.jgit.api.ResetCommand.ResetType.HARD).setRef(head.name).call()
            listOf("MERGE_HEAD", "MERGE_MSG", "MERGE_MODE").forEach { file -> File(it.repository.directory, file).delete() }
            diagnostics.event(GitDiagnosticEvent.MERGE_ABORT_SUCCESS, safe(repository))
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) { diagnostics.event(GitDiagnosticEvent.MERGE_ABORT_FAILED, safe(repository) + mapOf("category" to MergeErrorCategory.GIT_OPERATION_FAILED.name)); throw MergeOperationException(MergeErrorCategory.GIT_OPERATION_FAILED, error) }
    }

    private suspend fun checkoutVersion(repository: LocalRepository, path: String, stage: org.eclipse.jgit.api.CheckoutCommand.Stage, event: GitDiagnosticEvent) = mutate(repository) {
        validatePath(path)
        diagnostics.event(event, safe(repository))
        try { it.checkout().setStage(stage).addPath(path).call() }
        catch (error: CheckoutConflictException) { throw MergeOperationException(MergeErrorCategory.GIT_OPERATION_FAILED, error) }
    }

    private suspend fun <T> mutate(repository: LocalRepository, action: suspend (Git) -> T): T = withContext(ioDispatcher) {
        coordinator.withRepositoryLock {
            try { loader.open(repository).use { action(it) } }
            catch (error: CancellationException) { throw error }
            catch (error: MergeOperationException) { diagnostics.event(GitDiagnosticEvent.MERGE_FAILED, safe(repository) + mapOf("category" to error.category.name)); throw error }
            catch (error: Exception) { val mapped = MergeOperationException(MergeErrorCategory.GIT_OPERATION_FAILED, error); diagnostics.event(GitDiagnosticEvent.MERGE_FAILED, safe(repository) + mapOf("category" to mapped.category.name)); throw mapped }
        }
    }

    private fun stateOf(git: Git, sourceBranch: String? = null): MergeState {
        val repositoryState = when (git.repository.repositoryState) {
            RepositoryState.MERGING -> MergeRepositoryState.MERGING
            RepositoryState.MERGING_RESOLVED -> MergeRepositoryState.MERGING_RESOLVED
            else -> MergeRepositoryState.SAFE
        }
        val paths = git.status().call().conflicting.sorted().map { path ->
            val entries = (1..3).mapNotNull { stage -> findStageEntry(git, path, stage) }
            ConflictFile(path, entries.any { it.stage == 1 }, entries.any { it.stage == 2 }, entries.any { it.stage == 3 }, ConflictResolutionState.UNRESOLVED)
        }
        val source = sourceBranch ?: git.repository.readMergeHeads()?.firstOrNull()?.name?.take(7)
        return MergeState(
            operationState = if (repositoryState == MergeRepositoryState.SAFE) MergeOperationState.IDLE else if (paths.isEmpty()) MergeOperationState.CONTINUING else MergeOperationState.CONFLICTED,
            repositoryState = repositoryState,
            sourceBranch = source,
            conflictedFiles = paths,
            canContinue = repositoryState != MergeRepositoryState.SAFE && paths.isEmpty(),
            canAbort = repositoryState != MergeRepositoryState.SAFE
        )
    }

    private fun readContent(git: Git, objectId: org.eclipse.jgit.lib.AnyObjectId): ConflictVersionContent {
        val loader = git.repository.open(objectId)
        return loader.openStream().use { stream ->
            val output = ByteArrayOutputStream(PREVIEW_LIMIT)
            val buffer = ByteArray(1024)
            var total = 0
            var binary = false
            while (total <= PREVIEW_LIMIT) {
                val count = stream.read(buffer)
                if (count <= 0) break
                if (buffer.copyOf(count).any { it.toInt() == 0 }) binary = true
                val remaining = PREVIEW_LIMIT - total
                if (remaining > 0) output.write(buffer, 0, minOf(count, remaining))
                total += count
                if (total > PREVIEW_LIMIT) break
            }
            val bytes = output.toByteArray()
            return@use ConflictVersionContent(true, binary, if (binary) null else bytes.toString(Charsets.UTF_8), total > PREVIEW_LIMIT)
        }
    }

    private fun findStageEntry(git: Git, path: String, stage: Int): org.eclipse.jgit.dircache.DirCacheEntry? {
        val cache = git.repository.readDirCache()
        return (0 until cache.entryCount).asSequence()
            .map(cache::getEntry)
            .firstOrNull { it.pathString == path && it.stage == stage }
    }

    private fun validatePath(path: String) {
        if (path.isBlank() || path.startsWith("/") || path.contains('\\') || path.split('/').any { it.isBlank() || it == "." || it == ".." || it == ".git" }) throw MergeOperationException(MergeErrorCategory.INVALID_PATH)
    }
    private fun validateBranchName(name: String) { if (name.isBlank() || name.startsWith("refs/") || !org.eclipse.jgit.lib.Repository.isValidRefName("refs/heads/$name")) throw MergeOperationException(MergeErrorCategory.SOURCE_NOT_FOUND) }
    private fun safe(repository: LocalRepository) = mapOf("repositoryId" to repository.id)
    private companion object { const val PREVIEW_LIMIT = 8192 }
}
