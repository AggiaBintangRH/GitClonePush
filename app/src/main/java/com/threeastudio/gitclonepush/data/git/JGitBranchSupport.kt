package com.threeastudio.gitclonepush.data.git

import com.threeastudio.gitclonepush.core.model.BranchKind
import com.threeastudio.gitclonepush.core.model.GitBranchInfo
import com.threeastudio.gitclonepush.core.model.LocalRepository
import com.threeastudio.gitclonepush.data.git.GitDiagnosticEvent.*
import com.threeastudio.gitclonepush.domain.repository.BranchErrorCategory
import com.threeastudio.gitclonepush.domain.repository.BranchOperationException
import com.threeastudio.gitclonepush.domain.repository.GitBranchMutator
import com.threeastudio.gitclonepush.domain.repository.GitBranchReader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.CreateBranchCommand
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.errors.CheckoutConflictException
import org.eclipse.jgit.api.errors.GitAPIException
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevWalk
import java.io.File

internal fun configuredTrackingBranch(repository: Repository, branchName: String): String? {
    val remote = repository.config.getString("branch", branchName, "remote") ?: return null
    val merge = repository.config.getString("branch", branchName, "merge") ?: return null
    return if (remote == ".") merge else "refs/remotes/$remote/${merge.removePrefix("refs/heads/")}"
}

class JGitBranchReader(
    private val loader: JGitRepositoryLoader,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val diagnostics: GitDiagnostics = NoOpGitDiagnostics
) : GitBranchReader {
    override suspend fun listBranches(repository: LocalRepository): List<GitBranchInfo> = withContext(ioDispatcher) {
        diagnostics.event(BRANCH_LIST_STARTED, mapOf("repositoryId" to repository.id))
        try {
            loader.open(repository).use { git ->
                val current = git.repository.fullBranch
                val result = git.branchList().setListMode(org.eclipse.jgit.api.ListBranchCommand.ListMode.ALL).call()
                    .mapNotNull { ref -> mapRef(git.repository, ref.name, current) }
                    .sortedWith(compareBy<GitBranchInfo>({ it.kind != BranchKind.LOCAL }, { !it.isCurrent }, { it.name }))
                diagnostics.event(BRANCH_LIST_SUCCESS, mapOf("repositoryId" to repository.id, "localCount" to result.count { it.kind == BranchKind.LOCAL }.toString(), "remoteCount" to result.count { it.kind == BranchKind.REMOTE }.toString()))
                result
            }
        } catch (error: CancellationException) { throw error }
        catch (error: BranchOperationException) { throw error }
        catch (error: Exception) { throw BranchOperationException(BranchErrorCategory.GIT_OPERATION_FAILED, error) }
    }

    private fun mapRef(repository: Repository, fullRef: String, current: String?): GitBranchInfo? {
        return when {
            fullRef.startsWith(Constants.R_HEADS) -> {
                val name = fullRef.removePrefix(Constants.R_HEADS)
                val tracking = configuredTrackingBranch(repository, name)
                GitBranchInfo(name, fullRef, BranchKind.LOCAL, current == fullRef, tracking,
                    tracking?.removePrefix(Constants.R_REMOTES)?.substringBefore('/')?.takeIf { it != tracking },
                    tracking?.let { value -> value.removePrefix(Constants.R_REMOTES).substringAfter('/', "").takeIf { it.isNotBlank() } })
            }
            fullRef.startsWith(Constants.R_REMOTES) && !fullRef.endsWith("/HEAD") -> {
                val name = fullRef.removePrefix(Constants.R_REMOTES)
                val remote = name.substringBefore('/')
                val branch = name.substringAfter('/', "")
                if (branch.isBlank()) null else GitBranchInfo(name, fullRef, BranchKind.REMOTE, false, remoteName = remote, remoteBranchName = branch)
            }
            else -> null
        }
    }
}

class JGitBranchMutator(
    private val loader: JGitRepositoryLoader,
    private val coordinator: GitRepositoryOperationCoordinator,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val diagnostics: GitDiagnostics = NoOpGitDiagnostics
) : GitBranchMutator {
    override suspend fun create(repository: LocalRepository, name: String, startPoint: String, checkout: Boolean) = mutate(repository, BRANCH_CREATE_REQUESTED) {
        validateName(name)
        if (gitRef(it, "refs/heads/$name") != null) throw BranchOperationException(BranchErrorCategory.BRANCH_ALREADY_EXISTS)
        if (it.repository.resolve(startPoint) == null) throw BranchOperationException(BranchErrorCategory.START_POINT_NOT_FOUND)
        it.branchCreate().setName(name).setStartPoint(startPoint).call()
        if (checkout) safeCheckout(it, name)
        diagnostics.event(BRANCH_CREATE_SUCCESS, safe(repository))
    }

    override suspend fun checkout(repository: LocalRepository, name: String) = mutate(repository, BRANCH_CHECKOUT_REQUESTED) {
        if (gitRef(it, "refs/heads/$name") == null) throw BranchOperationException(BranchErrorCategory.BRANCH_NOT_FOUND)
        safeCheckout(it, name)
        diagnostics.event(BRANCH_CHECKOUT_SUCCESS, safe(repository))
    }

    override suspend fun createTracking(repository: LocalRepository, remoteTrackingRef: String, localName: String, checkout: Boolean) = mutate(repository, BRANCH_TRACK_REMOTE_REQUESTED) {
        validateName(localName)
        if (!remoteTrackingRef.startsWith(Constants.R_REMOTES) || it.repository.findRef(remoteTrackingRef) == null) throw BranchOperationException(BranchErrorCategory.REMOTE_BRANCH_NOT_FOUND)
        if (gitRef(it, "refs/heads/$localName") != null) throw BranchOperationException(BranchErrorCategory.BRANCH_ALREADY_EXISTS)
        it.branchCreate().setName(localName).setStartPoint(remoteTrackingRef).setUpstreamMode(CreateBranchCommand.SetupUpstreamMode.TRACK).call()
        val remotePath = remoteTrackingRef.removePrefix(Constants.R_REMOTES)
        configureUpstream(it.repository, localName, remotePath.substringBefore('/'), remotePath.substringAfter('/'))
        if (checkout) safeCheckout(it, localName)
        diagnostics.event(BRANCH_TRACK_REMOTE_SUCCESS, safe(repository))
    }

    override suspend fun setUpstream(repository: LocalRepository, localName: String, remoteName: String, remoteBranchName: String) = mutate(repository, BRANCH_TRACK_REMOTE_REQUESTED) {
        validateName(localName)
        if (gitRef(it, "refs/heads/$localName") == null) throw BranchOperationException(BranchErrorCategory.BRANCH_NOT_FOUND)
        if (it.repository.findRef("refs/remotes/$remoteName/$remoteBranchName") == null) throw BranchOperationException(BranchErrorCategory.REMOTE_BRANCH_NOT_FOUND)
        configureUpstream(it.repository, localName, remoteName, remoteBranchName)
        diagnostics.event(BRANCH_TRACK_REMOTE_SUCCESS, safe(repository))
    }

    override suspend fun removeUpstream(repository: LocalRepository, localName: String) = mutate(repository, BRANCH_TRACK_REMOTE_REQUESTED) {
        if (gitRef(it, "refs/heads/$localName") == null) throw BranchOperationException(BranchErrorCategory.BRANCH_NOT_FOUND)
        it.repository.config.unset("branch", localName, "remote")
        it.repository.config.unset("branch", localName, "merge")
        it.repository.config.save()
        diagnostics.event(BRANCH_TRACK_REMOTE_SUCCESS, safe(repository))
    }

    override suspend fun rename(repository: LocalRepository, oldName: String, newName: String) = mutate(repository, BRANCH_RENAME_REQUESTED) {
        validateName(newName)
        if (gitRef(it, "refs/heads/$oldName") == null) throw BranchOperationException(BranchErrorCategory.BRANCH_NOT_FOUND)
        if (gitRef(it, "refs/heads/$newName") != null) throw BranchOperationException(BranchErrorCategory.BRANCH_ALREADY_EXISTS)
        it.branchRename().setOldName(oldName).setNewName(newName).call()
        diagnostics.event(BRANCH_RENAME_SUCCESS, safe(repository))
    }

    override suspend fun delete(repository: LocalRepository, name: String) = mutate(repository, BRANCH_DELETE_REQUESTED) {
        validateName(name)
        if (gitRef(it, "refs/heads/$name") == null) throw BranchOperationException(BranchErrorCategory.BRANCH_NOT_FOUND)
        if (it.repository.fullBranch == "refs/heads/$name") throw BranchOperationException(BranchErrorCategory.CANNOT_DELETE_CURRENT_BRANCH)
        val head = it.repository.resolve(Constants.HEAD) ?: throw BranchOperationException(BranchErrorCategory.START_POINT_NOT_FOUND)
        val tip = it.repository.resolve("refs/heads/$name") ?: throw BranchOperationException(BranchErrorCategory.BRANCH_NOT_FOUND)
        val merged = RevWalk(it.repository).use { walk -> walk.isMergedInto(walk.parseCommit(tip), walk.parseCommit(head)) }
        if (!merged) throw BranchOperationException(BranchErrorCategory.BRANCH_NOT_MERGED)
        it.branchDelete().setBranchNames(name).call()
        diagnostics.event(BRANCH_DELETE_SUCCESS, safe(repository))
    }

    private suspend fun <T> mutate(repository: LocalRepository, requested: GitDiagnosticEvent, block: (Git) -> T): T = withContext(ioDispatcher) {
        coordinator.withRepositoryLock {
            diagnostics.event(requested, safe(repository))
            try {
                loader.open(repository).use { git ->
                    if (git.repository.repositoryState != org.eclipse.jgit.lib.RepositoryState.SAFE) {
                        throw BranchOperationException(BranchErrorCategory.MERGE_IN_PROGRESS)
                    }
                    block(git)
                }
            } catch (error: CancellationException) { throw error }
            catch (error: BranchOperationException) { diagnostics.event(failure(requested), safeFailure(repository, error)); throw error }
            catch (error: CheckoutConflictException) {
                val mapped = BranchOperationException(BranchErrorCategory.CHECKOUT_WOULD_OVERWRITE_CHANGES, error)
                diagnostics.event(BRANCH_CHECKOUT_BLOCKED, safeFailure(repository, mapped)); throw mapped
            } catch (error: GitAPIException) {
                val mapped = BranchOperationException(BranchErrorCategory.GIT_OPERATION_FAILED, error)
                diagnostics.event(failure(requested), safeFailure(repository, mapped)); throw mapped
            } catch (error: Exception) {
                val mapped = BranchOperationException(BranchErrorCategory.GIT_OPERATION_FAILED, error)
                diagnostics.event(failure(requested), safeFailure(repository, mapped)); throw mapped
            }
        }
    }

    private fun safeCheckout(git: Git, name: String) {
        try { git.checkout().setName(name).call() }
        catch (error: CheckoutConflictException) { throw BranchOperationException(BranchErrorCategory.CHECKOUT_WOULD_OVERWRITE_CHANGES, error) }
    }

    private fun validateName(name: String) {
        if (name.isBlank() || !Repository.isValidRefName("refs/heads/$name") || name.startsWith("refs/") || name.contains("..")) throw BranchOperationException(BranchErrorCategory.INVALID_BRANCH_NAME)
    }

    private fun configureUpstream(repository: Repository, localName: String, remoteName: String, remoteBranchName: String) {
        repository.config.setString("branch", localName, "remote", remoteName)
        repository.config.setString("branch", localName, "merge", "refs/heads/$remoteBranchName")
        repository.config.save()
    }

    private fun gitRef(git: Git, name: String) = git.repository.findRef(name)
    private fun safe(repository: LocalRepository) = mapOf("repositoryId" to repository.id)
    private fun safeFailure(repository: LocalRepository, error: BranchOperationException) = safe(repository) + mapOf("category" to error.category.name)
    private fun failure(event: GitDiagnosticEvent) = when (event) {
        BRANCH_CREATE_REQUESTED -> BRANCH_CREATE_FAILED
        BRANCH_CHECKOUT_REQUESTED -> BRANCH_CHECKOUT_FAILED
        BRANCH_RENAME_REQUESTED -> BRANCH_RENAME_FAILED
        BRANCH_DELETE_REQUESTED -> BRANCH_DELETE_FAILED
        else -> BRANCH_TRACK_REMOTE_FAILED
    }
}
