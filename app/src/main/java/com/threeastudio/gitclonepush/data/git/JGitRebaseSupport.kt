package com.threeastudio.gitclonepush.data.git

import com.threeastudio.gitclonepush.core.model.*
import com.threeastudio.gitclonepush.domain.repository.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.RebaseCommand
import org.eclipse.jgit.api.RebaseResult as JGitRebaseResult
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.RebaseTodoLine
import org.eclipse.jgit.lib.RepositoryState
import org.eclipse.jgit.revwalk.RevWalk
import java.io.File

class JGitRebaseSupport(
    private val loader: JGitRepositoryLoader,
    private val coordinator: GitRepositoryOperationCoordinator,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val diagnostics: GitDiagnostics = NoOpGitDiagnostics,
    private val remoteSynchronizer: GitRemoteSynchronizer? = null
) : GitRebaseReader, GitRebaseMutator {
    override suspend fun readState(repository: LocalRepository): RebaseState = withContext(ioDispatcher) {
        diagnostics.event(GitDiagnosticEvent.REBASE_STATE_REFRESH_STARTED, safe(repository))
        loader.open(repository).use { git -> stateOf(git).also { state -> diagnostics.event(GitDiagnosticEvent.REBASE_STATE_REFRESH_SUCCESS, safe(repository) + mapOf("state" to state.operationState.name, "conflictCount" to state.conflictedPaths.size.toString())) } }
    }

    override suspend fun start(repository: LocalRepository, ontoBranch: String): RebaseResult {
        validatePublicationSafety(repository, ontoBranch = ontoBranch)
        return mutate(repository) {
        diagnostics.event(GitDiagnosticEvent.REBASE_REQUESTED, safe(repository))
        validateStart(it, repository, ontoBranch)
        diagnostics.event(GitDiagnosticEvent.REBASE_STARTED, safe(repository))
        mapResult(it, it.rebase().setUpstream("refs/heads/$ontoBranch").call())
        }
    }

    override suspend fun continueRebase(repository: LocalRepository): RebaseResult = mutate(repository) {
        diagnostics.event(GitDiagnosticEvent.REBASE_CONTINUE_REQUESTED, safe(repository))
        requireActive(it)
        requireNoConflicts(it)
        val result = mapResult(it, it.rebase().setOperation(RebaseCommand.Operation.CONTINUE).call())
        diagnostics.event(GitDiagnosticEvent.REBASE_CONTINUE_SUCCESS, safe(repository))
        result
    }

    override suspend fun skipCommit(repository: LocalRepository): RebaseResult = mutate(repository) {
        diagnostics.event(GitDiagnosticEvent.REBASE_SKIP_REQUESTED, safe(repository))
        requireActive(it)
        val result = mapResult(it, it.rebase().setOperation(RebaseCommand.Operation.SKIP).call())
        diagnostics.event(GitDiagnosticEvent.REBASE_SKIP_SUCCESS, safe(repository))
        result
    }

    override suspend fun abort(repository: LocalRepository) = mutate(repository) {
        diagnostics.event(GitDiagnosticEvent.REBASE_ABORT_REQUESTED, safe(repository))
        requireActive(it)
        it.rebase().setOperation(RebaseCommand.Operation.ABORT).call()
        diagnostics.event(GitDiagnosticEvent.REBASE_ABORT_SUCCESS, safe(repository))
    }

    override suspend fun interactive(repository: LocalRepository, plan: List<InteractiveRebaseItem>): RebaseResult {
        validatePublicationSafety(repository, planCommitIds = plan.map { it.commitId })
        return mutate(repository) {
        diagnostics.event(GitDiagnosticEvent.INTERACTIVE_REBASE_PLAN_CREATED, safe(repository) + mapOf("planCount" to plan.size.toString()))
        validatePlan(plan)
        validateInteractiveStart(it, repository, plan)
        val first = it.repository.resolve(plan.first().commitId) ?: throw RebaseOperationException(RebaseErrorCategory.INVALID_INTERACTIVE_PLAN)
        val base = RevWalk(it.repository).use { walk -> walk.parseCommit(first).parents.firstOrNull()?.id }
            ?: throw RebaseOperationException(RebaseErrorCategory.INVALID_INTERACTIVE_PLAN)
        diagnostics.event(GitDiagnosticEvent.INTERACTIVE_REBASE_STARTED, safe(repository))
        val handler = interactiveHandler(plan)
        try {
            mapResult(it, it.rebase().setUpstream(base).runInteractively(handler).call())
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) { diagnostics.event(GitDiagnosticEvent.INTERACTIVE_REBASE_FAILED, safe(repository) + mapOf("category" to RebaseErrorCategory.REBASE_FAILED.name)); throw RebaseOperationException(RebaseErrorCategory.REBASE_FAILED, error) }
        }
    }

    private suspend fun validateStart(git: Git, repository: LocalRepository, ontoBranch: String) {
        if (git.repository.repositoryState != RepositoryState.SAFE) {
            throw RebaseOperationException(if (git.repository.repositoryState.isRebasing) RebaseErrorCategory.REBASE_ALREADY_IN_PROGRESS else RebaseErrorCategory.MERGE_IN_PROGRESS)
        }
        if (!git.repository.fullBranch.orEmpty().startsWith(Constants.R_HEADS)) throw RebaseOperationException(RebaseErrorCategory.DETACHED_HEAD)
        val current = git.repository.branch
        if (current == ontoBranch) throw RebaseOperationException(RebaseErrorCategory.SAME_BRANCH)
        if (git.repository.findRef("refs/heads/$ontoBranch") == null) throw RebaseOperationException(RebaseErrorCategory.TARGET_BRANCH_NOT_FOUND)
        if (!git.status().call().isClean) throw RebaseOperationException(RebaseErrorCategory.REBASE_BLOCKED_BY_LOCAL_CHANGES)
    }

    private suspend fun validateInteractiveStart(git: Git, repository: LocalRepository, plan: List<InteractiveRebaseItem>) {
        if (git.repository.repositoryState != RepositoryState.SAFE) throw RebaseOperationException(RebaseErrorCategory.REBASE_ALREADY_IN_PROGRESS)
        if (!git.repository.fullBranch.orEmpty().startsWith(Constants.R_HEADS)) throw RebaseOperationException(RebaseErrorCategory.DETACHED_HEAD)
        if (!git.status().call().isClean) throw RebaseOperationException(RebaseErrorCategory.REBASE_BLOCKED_BY_LOCAL_CHANGES)
        if (plan.any { git.repository.resolve(it.commitId) == null }) throw RebaseOperationException(RebaseErrorCategory.INVALID_INTERACTIVE_PLAN)
    }

    private suspend fun validatePublicationSafety(
        repository: LocalRepository,
        ontoBranch: String? = null,
        planCommitIds: List<String> = emptyList()
    ) {
        val state = remoteSynchronizer?.readState(repository) ?: return
        if (!state.hasUpstream) return
        if (state.freshness != RemoteStateFreshness.FRESH) throw RebaseOperationException(RebaseErrorCategory.REMOTE_STATE_NEEDS_REFRESH)
        if ((state.behind ?: 0) != 0) throw RebaseOperationException(RebaseErrorCategory.PUBLISHED_HISTORY_REWRITE_BLOCKED)
        loader.open(repository).use { git ->
            val upstreamId = state.upstream?.let { git.repository.resolve(it) }
                ?: throw RebaseOperationException(RebaseErrorCategory.REMOTE_STATE_NEEDS_REFRESH)
            val upstream = RevWalk(git.repository).use { walk -> walk.parseCommit(upstreamId) }
            val candidateIds = if (ontoBranch != null) {
                val currentId = git.repository.resolve("HEAD")
                    ?: throw RebaseOperationException(RebaseErrorCategory.REPOSITORY_INVALID)
                val targetId = git.repository.resolve("refs/heads/$ontoBranch")
                    ?: throw RebaseOperationException(RebaseErrorCategory.TARGET_BRANCH_NOT_FOUND)
                RevWalk(git.repository).use { walk ->
                    walk.markStart(walk.parseCommit(currentId))
                    walk.markUninteresting(walk.parseCommit(targetId))
                    walk.toList().map { it.id }
                }
            } else {
                planCommitIds.map { git.repository.resolve(it) ?: throw RebaseOperationException(RebaseErrorCategory.INVALID_INTERACTIVE_PLAN) }
            }
            RevWalk(git.repository).use { walk ->
                candidateIds.forEach { id ->
                    if (walk.isMergedInto(walk.parseCommit(id), upstream)) {
                        throw RebaseOperationException(RebaseErrorCategory.PUBLISHED_HISTORY_REWRITE_BLOCKED)
                    }
                }
            }
        }
    }

    private fun validatePlan(plan: List<InteractiveRebaseItem>) {
        if (plan.isEmpty() || plan.map { it.commitId }.distinct().size != plan.size) throw RebaseOperationException(RebaseErrorCategory.INVALID_INTERACTIVE_PLAN)
        if (plan.first().action == RebaseAction.SQUASH || plan.first().action == RebaseAction.FIXUP) throw RebaseOperationException(RebaseErrorCategory.INVALID_INTERACTIVE_PLAN)
        if (plan.any { it.action == RebaseAction.REWORD && it.editedMessage.isNullOrBlank() }) throw RebaseOperationException(RebaseErrorCategory.INVALID_REWORD_MESSAGE)
        if (plan.any { it.action == RebaseAction.SQUASH && it.editedMessage != null && it.editedMessage.isBlank() }) throw RebaseOperationException(RebaseErrorCategory.INVALID_REWORD_MESSAGE)
    }

    private fun interactiveHandler(plan: List<InteractiveRebaseItem>) = object : RebaseCommand.InteractiveHandler {
        override fun prepareSteps(steps: MutableList<RebaseTodoLine>) {
            val byId = plan.associateBy { it.commitId.take(7) }
            steps.forEach { step ->
                val item = byId.entries.firstOrNull { entry -> step.commit.name().startsWith(entry.key) }?.value ?: return@forEach
                step.setAction(when (item.action) {
                    RebaseAction.PICK -> RebaseTodoLine.Action.PICK
                    RebaseAction.REWORD -> RebaseTodoLine.Action.REWORD
                    RebaseAction.SQUASH -> RebaseTodoLine.Action.SQUASH
                    RebaseAction.FIXUP -> RebaseTodoLine.Action.FIXUP
                    RebaseAction.DROP -> RebaseTodoLine.Action.COMMENT
                })
            }
            val order = plan.mapIndexed { index, item -> item.commitId.take(7) to index }.toMap()
            steps.sortBy { order.entries.firstOrNull { entry -> it.commit.name().startsWith(entry.key) }?.value ?: Int.MAX_VALUE }
        }
        override fun modifyCommitMessage(message: String): String {
            return plan.firstOrNull { it.editedMessage != null && message.startsWith(it.message) }?.editedMessage ?: message
        }
    }

    private fun requireActive(git: Git) { if (!git.repository.repositoryState.isRebasing) throw RebaseOperationException(RebaseErrorCategory.NOT_IN_REBASE_STATE) }
    private fun requireNoConflicts(git: Git) { if (git.status().call().conflicting.isNotEmpty()) throw RebaseOperationException(RebaseErrorCategory.UNRESOLVED_CONFLICTS) }

    private fun stateOf(git: Git): RebaseState {
        val rebasing = git.repository.repositoryState.isRebasing
        val conflicts = git.status().call().conflicting.sorted()
        val current = git.repository.fullBranch?.removePrefix(Constants.R_HEADS)
        val gitDir = git.repository.directory
        val onto = readFirstLine(File(gitDir, "rebase-merge/onto")) ?: readFirstLine(File(gitDir, "rebase-apply/onto"))
        val headName = readFirstLine(File(gitDir, "rebase-merge/head-name"))?.removePrefix(Constants.R_HEADS)
        val stopped = readFirstLine(File(gitDir, "rebase-merge/stopped-sha")) ?: readFirstLine(File(gitDir, "rebase-apply/stopped-sha")) ?: readFirstLine(File(gitDir, "REBASE_HEAD"))
        val state = when {
            !rebasing -> RebaseOperationState.IDLE
            conflicts.isNotEmpty() -> RebaseOperationState.CONFLICTED
            git.repository.repositoryState == RepositoryState.REBASING_INTERACTIVE -> RebaseOperationState.RUNNING
            else -> RebaseOperationState.RUNNING
        }
        return RebaseState(state, headName ?: current, onto, stopped, conflicts, rebasing && conflicts.isEmpty(), rebasing, rebasing)
    }

    private fun readFirstLine(file: File): String? = file.takeIf { it.isFile }?.useLines { it.firstOrNull()?.trim()?.takeIf(String::isNotBlank) }
    private suspend fun <T> mutate(repository: LocalRepository, action: suspend (Git) -> T): T = withContext(ioDispatcher) {
        coordinator.withRepositoryLock {
            try { loader.open(repository).use { action(it) } }
            catch (error: CancellationException) { throw error }
            catch (error: RebaseOperationException) { diagnostics.event(GitDiagnosticEvent.REBASE_VALIDATION_FAILED, safe(repository) + mapOf("category" to error.category.name)); throw error }
            catch (error: Exception) { diagnostics.event(GitDiagnosticEvent.REBASE_FAILED, safe(repository) + mapOf("category" to RebaseErrorCategory.REBASE_FAILED.name)); throw RebaseOperationException(RebaseErrorCategory.REBASE_FAILED, error) }
        }
    }

    private fun mapResult(git: Git, result: JGitRebaseResult): com.threeastudio.gitclonepush.core.model.RebaseResult {
        return when (result.status) {
            JGitRebaseResult.Status.UP_TO_DATE, JGitRebaseResult.Status.NOTHING_TO_COMMIT -> com.threeastudio.gitclonepush.core.model.RebaseResult.AlreadyUpToDate
            JGitRebaseResult.Status.FAST_FORWARD -> com.threeastudio.gitclonepush.core.model.RebaseResult.FastForward
            JGitRebaseResult.Status.OK -> com.threeastudio.gitclonepush.core.model.RebaseResult.Rebased
            JGitRebaseResult.Status.CONFLICTS -> com.threeastudio.gitclonepush.core.model.RebaseResult.Conflicted(stateAfter(git, result))
            JGitRebaseResult.Status.STOPPED, JGitRebaseResult.Status.EDIT -> {
                val state = stateAfter(git, result)
                if (state.conflictedPaths.isNotEmpty()) com.threeastudio.gitclonepush.core.model.RebaseResult.Conflicted(state)
                else com.threeastudio.gitclonepush.core.model.RebaseResult.Stopped(state)
            }
            else -> throw RebaseOperationException(RebaseErrorCategory.REBASE_FAILED)
        }
    }
    private fun stateAfter(git: Git, result: JGitRebaseResult): RebaseState {
        val conflicts = git.status().call().conflicting.sorted().ifEmpty { result.conflicts.orEmpty() }
        return RebaseState(
            operationState = if (conflicts.isEmpty()) RebaseOperationState.STOPPED_FOR_EDIT else RebaseOperationState.CONFLICTED,
            currentCommit = result.currentCommit?.name,
            conflictedPaths = conflicts,
            canContinue = conflicts.isEmpty(),
            canSkip = true,
            canAbort = true
        )
    }
    private fun safe(repository: LocalRepository) = mapOf("repositoryId" to repository.id)
}
