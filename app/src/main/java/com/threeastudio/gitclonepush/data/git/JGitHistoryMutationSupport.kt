package com.threeastudio.gitclonepush.data.git

import com.threeastudio.gitclonepush.core.model.*
import com.threeastudio.gitclonepush.domain.repository.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.CherryPickResult
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ResetCommand
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.lib.RepositoryState
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.revwalk.RevWalk
import java.io.File

private fun conflictPaths(git: Git) = git.status().call().conflicting.sorted()

private fun mutationState(git: Git, kind: HistoryMutationKind, remaining: Int = 0) = HistoryMutationState(
    kind = kind,
    conflictedPaths = conflictPaths(git),
    canContinue = (kind == HistoryMutationKind.REVERTING || kind == HistoryMutationKind.CHERRY_PICKING) && conflictPaths(git).isEmpty(),
    canSkip = kind == HistoryMutationKind.CHERRY_PICKING,
    canAbort = kind != HistoryMutationKind.NONE,
    remainingCount = remaining
)

class JGitRevertSupport(
    private val loader: JGitRepositoryLoader,
    private val coordinator: GitRepositoryOperationCoordinator,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val diagnostics: GitDiagnostics = NoOpGitDiagnostics
) : GitRevertReader, GitRevertMutator {
    override suspend fun readState(repository: LocalRepository): HistoryMutationState = withContext(ioDispatcher) {
        loader.open(repository).use { git ->
            if (git.repository.repositoryState == RepositoryState.REVERTING || git.repository.repositoryState == RepositoryState.REVERTING_RESOLVED) mutationState(git, HistoryMutationKind.REVERTING)
            else HistoryMutationState()
        }
    }

    override suspend fun revert(repository: LocalRepository, commitId: String, mainlineParent: Int?) = mutate(repository) { git ->
        diagnostics.event(GitDiagnosticEvent.REVERT_REQUESTED, safe(repository))
        requireAttachedAndClean(git)
        val commit = resolveCommit(git, commitId)
        if (commit.parentCount > 1) {
            if (mainlineParent == null) throw RevertOperationException(RevertErrorCategory.MAINLINE_PARENT_REQUIRED)
            if (mainlineParent !in 1..commit.parentCount) throw RevertOperationException(RevertErrorCategory.INVALID_MAINLINE_PARENT)
            // JGit 6.10 does not expose a mainline-parent option for RevertCommand.
            throw RevertOperationException(RevertErrorCategory.INVALID_MAINLINE_PARENT)
        }
        diagnostics.event(GitDiagnosticEvent.REVERT_STARTED, safe(repository))
        try {
            git.revert().include(commit).call()
            diagnostics.event(GitDiagnosticEvent.REVERT_SUCCESS, safe(repository))
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) {
            if (git.repository.repositoryState == RepositoryState.REVERTING || git.repository.repositoryState == RepositoryState.REVERTING_RESOLVED) diagnostics.event(GitDiagnosticEvent.REVERT_CONFLICT, safe(repository) + mapOf("conflictCount" to conflictPaths(git).size.toString()))
            else diagnostics.event(GitDiagnosticEvent.REVERT_FAILED, safe(repository) + mapOf("category" to "REVERT_FAILED"))
            throw RevertOperationException(if (git.repository.repositoryState == RepositoryState.REVERTING || git.repository.repositoryState == RepositoryState.REVERTING_RESOLVED) RevertErrorCategory.UNRESOLVED_CONFLICTS else RevertErrorCategory.REVERT_FAILED, error)
        }
    }

    override suspend fun continueRevert(repository: LocalRepository) = mutate(repository) { git ->
        if (git.repository.repositoryState != RepositoryState.REVERTING && git.repository.repositoryState != RepositoryState.REVERTING_RESOLVED) throw RevertOperationException(RevertErrorCategory.NOT_IN_REVERT_STATE)
        if (conflictPaths(git).isNotEmpty()) throw RevertOperationException(RevertErrorCategory.UNRESOLVED_CONFLICTS)
        try { git.commit().setMessage(revertMessage(git)).call(); diagnostics.event(GitDiagnosticEvent.REVERT_CONTINUE_SUCCESS, safe(repository)) }
        catch (error: CancellationException) { throw error }
        catch (error: Exception) { throw RevertOperationException(RevertErrorCategory.REVERT_FAILED, error) }
    }

    override suspend fun abortRevert(repository: LocalRepository) = mutate(repository) { git ->
        if (git.repository.repositoryState != RepositoryState.REVERTING && git.repository.repositoryState != RepositoryState.REVERTING_RESOLVED) throw RevertOperationException(RevertErrorCategory.NOT_IN_REVERT_STATE)
        val head = git.repository.resolve(Constants.ORIG_HEAD) ?: git.repository.resolve(Constants.HEAD) ?: throw RevertOperationException(RevertErrorCategory.REPOSITORY_INVALID)
        git.reset().setMode(ResetCommand.ResetType.MERGE).setRef(head.name).call()
        clearOperationFiles(git, "REVERT_HEAD", "MERGE_MSG", "MERGE_MODE")
        diagnostics.event(GitDiagnosticEvent.REVERT_ABORT_SUCCESS, safe(repository))
    }

    private fun requireAttachedAndClean(git: Git) {
        if (!git.repository.fullBranch.orEmpty().startsWith(Constants.R_HEADS)) throw RevertOperationException(RevertErrorCategory.DETACHED_HEAD)
        if (git.repository.repositoryState != RepositoryState.SAFE) throw RevertOperationException(RevertErrorCategory.MERGE_OR_OTHER_OPERATION_IN_PROGRESS)
        if (!git.status().call().isClean) throw RevertOperationException(RevertErrorCategory.DIRTY_WORKTREE)
    }
    private fun resolveCommit(git: Git, id: String): RevCommit = RevWalk(git.repository).use { walk ->
        val resolved = git.repository.resolve(id) ?: throw RevertOperationException(RevertErrorCategory.REPOSITORY_INVALID)
        walk.parseCommit(resolved)
    }
    private fun revertMessage(git: Git) = File(git.repository.directory, "MERGE_MSG").takeIf { it.isFile }?.readLines()?.firstOrNull { it.isNotBlank() && !it.startsWith("#") } ?: "Revert commit"
    private suspend fun <T> mutate(repository: LocalRepository, block: suspend (Git) -> T): T = withContext(ioDispatcher) { coordinator.withRepositoryLock { try { loader.open(repository).use { block(it) } } catch (e: CancellationException) { throw e } catch (e: RevertOperationException) { throw e } catch (e: Exception) { throw RevertOperationException(RevertErrorCategory.REVERT_FAILED, e) } } }
    private fun safe(repository: LocalRepository) = mapOf("repositoryId" to repository.id)
}

class JGitCherryPickSupport(
    private val loader: JGitRepositoryLoader,
    private val coordinator: GitRepositoryOperationCoordinator,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val diagnostics: GitDiagnostics = NoOpGitDiagnostics
) : GitCherryPickReader, GitCherryPickMutator {
    private val remainingByRepository = mutableMapOf<String, List<String>>()
    private val mainlineByRepository = mutableMapOf<String, Map<String, Int>>()

    override suspend fun readState(repository: LocalRepository): HistoryMutationState = withContext(ioDispatcher) {
        loader.open(repository).use { git ->
            if (git.repository.repositoryState == RepositoryState.CHERRY_PICKING || git.repository.repositoryState == RepositoryState.CHERRY_PICKING_RESOLVED) mutationState(git, HistoryMutationKind.CHERRY_PICKING, remainingByRepository[repository.id].orEmpty().size)
            else HistoryMutationState()
        }
    }

    override suspend fun cherryPick(repository: LocalRepository, commitIds: List<String>, mainlineParents: Map<String, Int>) = mutate(repository) { git ->
        diagnostics.event(GitDiagnosticEvent.CHERRY_PICK_REQUESTED, safe(repository))
        if (commitIds.isEmpty()) throw CherryPickOperationException(CherryPickErrorCategory.INVALID_SEQUENCE)
        requireAttachedAndClean(git)
        remainingByRepository[repository.id] = commitIds
        mainlineByRepository[repository.id] = mainlineParents
        diagnostics.event(GitDiagnosticEvent.CHERRY_PICK_SEQUENCE_STARTED, safe(repository) + mapOf("count" to commitIds.size.toString()))
        applySequence(git, repository, mainlineParents)
    }

    override suspend fun continueCherryPick(repository: LocalRepository) = mutate(repository) { git ->
        if (git.repository.repositoryState != RepositoryState.CHERRY_PICKING && git.repository.repositoryState != RepositoryState.CHERRY_PICKING_RESOLVED) throw CherryPickOperationException(CherryPickErrorCategory.NOT_IN_CHERRY_PICK_STATE)
        if (conflictPaths(git).isNotEmpty()) throw CherryPickOperationException(CherryPickErrorCategory.UNRESOLVED_CONFLICTS)
        try {
            git.commit().setMessage(cherryPickMessage(git)).call()
            applySequence(git, repository, mainlineByRepository[repository.id].orEmpty())
            diagnostics.event(GitDiagnosticEvent.CHERRY_PICK_CONTINUE_SUCCESS, safe(repository))
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) {
            diagnostics.event(GitDiagnosticEvent.CHERRY_PICK_FAILED, safe(repository) + mapOf("category" to "CHERRY_PICK_CONTINUE_FAILED"))
            throw error
        }
    }

    override suspend fun skipCherryPick(repository: LocalRepository) = mutate(repository) { git ->
        if (git.repository.repositoryState != RepositoryState.CHERRY_PICKING && git.repository.repositoryState != RepositoryState.CHERRY_PICKING_RESOLVED) throw CherryPickOperationException(CherryPickErrorCategory.NOT_IN_CHERRY_PICK_STATE)
        git.reset().setMode(ResetCommand.ResetType.MERGE).setRef(Constants.HEAD).call()
        clearOperationFiles(git, "CHERRY_PICK_HEAD", "MERGE_MSG")
        applySequence(git, repository, mainlineByRepository[repository.id].orEmpty())
        diagnostics.event(GitDiagnosticEvent.CHERRY_PICK_SKIP_SUCCESS, safe(repository))
    }

    override suspend fun abortCherryPick(repository: LocalRepository) = mutate(repository) { git ->
        if (git.repository.repositoryState != RepositoryState.CHERRY_PICKING && git.repository.repositoryState != RepositoryState.CHERRY_PICKING_RESOLVED) throw CherryPickOperationException(CherryPickErrorCategory.NOT_IN_CHERRY_PICK_STATE)
        git.reset().setMode(ResetCommand.ResetType.MERGE).setRef(Constants.ORIG_HEAD).call()
        clearOperationFiles(git, "CHERRY_PICK_HEAD", "MERGE_MSG")
        remainingByRepository.remove(repository.id)
        mainlineByRepository.remove(repository.id)
        diagnostics.event(GitDiagnosticEvent.CHERRY_PICK_ABORT_SUCCESS, safe(repository))
    }

    private fun applySequence(git: Git, repository: LocalRepository, mainlineParents: Map<String, Int>) {
        val sequence = remainingByRepository[repository.id].orEmpty().toMutableList()
        while (sequence.isNotEmpty()) {
            val id = sequence.removeAt(0)
            val commit = RevWalk(git.repository).use { walk -> walk.parseCommit(git.repository.resolve(id) ?: throw CherryPickOperationException(CherryPickErrorCategory.INVALID_SEQUENCE)) }
            if (commit.parentCount > 1 && mainlineParents[id] == null) throw CherryPickOperationException(CherryPickErrorCategory.MERGE_COMMIT_MAINLINE_REQUIRED)
            val command = git.cherryPick().include(commit)
            mainlineParents[id]?.let(command::setMainlineParentNumber)
            val result = command.call()
            if (result.status == CherryPickResult.CherryPickStatus.CONFLICTING) {
                remainingByRepository[repository.id] = sequence
                diagnostics.event(GitDiagnosticEvent.CHERRY_PICK_CONFLICT, safe(repository) + mapOf("conflictCount" to conflictPaths(git).size.toString()))
                throw CherryPickOperationException(CherryPickErrorCategory.UNRESOLVED_CONFLICTS)
            }
        }
        remainingByRepository.remove(repository.id)
        mainlineByRepository.remove(repository.id)
        diagnostics.event(GitDiagnosticEvent.CHERRY_PICK_SUCCESS, safe(repository))
    }
    private fun requireAttachedAndClean(git: Git) { if (!git.repository.fullBranch.orEmpty().startsWith(Constants.R_HEADS)) throw CherryPickOperationException(CherryPickErrorCategory.DETACHED_HEAD); if (git.repository.repositoryState != RepositoryState.SAFE) throw CherryPickOperationException(CherryPickErrorCategory.OTHER_OPERATION_IN_PROGRESS); if (!git.status().call().isClean) throw CherryPickOperationException(CherryPickErrorCategory.DIRTY_WORKTREE) }
    private fun cherryPickMessage(git: Git) = File(git.repository.directory, "MERGE_MSG").takeIf { it.isFile }?.readLines()?.firstOrNull { it.isNotBlank() && !it.startsWith("#") } ?: "Cherry-pick commit"
    private suspend fun <T> mutate(repository: LocalRepository, block: suspend (Git) -> T): T = withContext(ioDispatcher) { coordinator.withRepositoryLock { try { loader.open(repository).use { block(it) } } catch (e: CancellationException) { throw e } catch (e: CherryPickOperationException) { throw e } catch (e: Exception) { throw CherryPickOperationException(CherryPickErrorCategory.CHERRY_PICK_FAILED, e) } } }
    private fun safe(repository: LocalRepository) = mapOf("repositoryId" to repository.id)
}

class JGitStashSupport(
    private val loader: JGitRepositoryLoader,
    private val coordinator: GitRepositoryOperationCoordinator,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val diagnostics: GitDiagnostics = NoOpGitDiagnostics
) : GitStashReader, GitStashMutator {
    override suspend fun list(repository: LocalRepository): List<GitStashEntry> = withContext(ioDispatcher) { loader.open(repository).use { git -> git.stashList().call().toList().mapIndexed { index, commit -> GitStashEntry(index, commit.name, commit.name.take(7), commit.shortMessage, commit.authorIdent?.whenAsInstant?.toString()) }.also { diagnostics.event(GitDiagnosticEvent.STASH_LIST_SUCCESS, mapOf("repositoryId" to repository.id, "count" to it.size.toString())) } } }
    override suspend fun create(repository: LocalRepository, message: String?, includeUntracked: Boolean): GitStashEntry = mutate(repository) { git ->
        diagnostics.event(GitDiagnosticEvent.STASH_CREATE_REQUESTED, safe(repository))
        if (git.repository.repositoryState != RepositoryState.SAFE) throw StashOperationException(StashErrorCategory.OPERATION_IN_PROGRESS)
        val commit = git.stashCreate().setIncludeUntracked(includeUntracked).setWorkingDirectoryMessage(message ?: "").call() ?: throw StashOperationException(StashErrorCategory.NOTHING_TO_STASH)
        diagnostics.event(GitDiagnosticEvent.STASH_CREATE_SUCCESS, safe(repository))
        GitStashEntry(0, commit.name, commit.name.take(7), commit.shortMessage, commit.authorIdent?.whenAsInstant?.toString())
    }
    override suspend fun apply(repository: LocalRepository, index: Int) = mutate(repository) {
        diagnostics.event(GitDiagnosticEvent.STASH_APPLY_REQUESTED, safe(repository))
        applyStash(it, repository, index, false)
    }
    override suspend fun pop(repository: LocalRepository, index: Int) = mutate(repository) {
        diagnostics.event(GitDiagnosticEvent.STASH_POP_REQUESTED, safe(repository))
        applyStash(it, repository, index, true)
    }
    override suspend fun drop(repository: LocalRepository, index: Int) = mutate(repository) { git -> if (index < 0 || git.stashList().call().drop(index).firstOrNull() == null) throw StashOperationException(StashErrorCategory.STASH_NOT_FOUND); git.stashDrop().setStashRef(index).call(); diagnostics.event(GitDiagnosticEvent.STASH_DROP_SUCCESS, safe(repository)) }
    private fun applyStash(git: Git, repository: LocalRepository, index: Int, pop: Boolean) {
        if (index < 0 || git.stashList().call().drop(index).firstOrNull() == null) throw StashOperationException(StashErrorCategory.STASH_NOT_FOUND)
        if (!git.status().call().isClean) throw StashOperationException(StashErrorCategory.APPLY_BLOCKED_BY_LOCAL_CHANGES)
        try {
            git.stashApply().setStashRef("stash@{$index}").setRestoreIndex(true).call()
            if (pop) {
                git.stashDrop().setStashRef(index).call()
                diagnostics.event(GitDiagnosticEvent.STASH_POP_SUCCESS, safe(repository))
            } else {
                diagnostics.event(GitDiagnosticEvent.STASH_APPLY_SUCCESS, safe(repository))
            }
        }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            if (conflictPaths(git).isNotEmpty()) {
                diagnostics.event(if (pop) GitDiagnosticEvent.STASH_POP_CONFLICT else GitDiagnosticEvent.STASH_APPLY_CONFLICT, safe(repository) + mapOf("conflictCount" to conflictPaths(git).size.toString()))
                throw StashOperationException(StashErrorCategory.STASH_CONFLICT, e)
            }
            diagnostics.event(if (pop) GitDiagnosticEvent.STASH_POP_FAILED else GitDiagnosticEvent.STASH_APPLY_FAILED, safe(repository) + mapOf("category" to "STASH_FAILED"))
            throw StashOperationException(StashErrorCategory.STASH_FAILED, e)
        }
    }
    private suspend fun <T> mutate(repository: LocalRepository, block: suspend (Git) -> T): T = withContext(ioDispatcher) { coordinator.withRepositoryLock { try { loader.open(repository).use { block(it) } } catch (e: CancellationException) { throw e } catch (e: StashOperationException) { throw e } catch (e: Exception) { throw StashOperationException(StashErrorCategory.STASH_FAILED, e) } } }
    private fun safe(repository: LocalRepository) = mapOf("repositoryId" to repository.id)
}

private fun clearOperationFiles(git: Git, vararg names: String) { names.forEach { File(git.repository.directory, it).delete() } }
