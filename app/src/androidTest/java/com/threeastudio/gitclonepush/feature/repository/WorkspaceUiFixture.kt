package com.threeastudio.gitclonepush.feature.repository

import com.threeastudio.gitclonepush.core.model.*
import com.threeastudio.gitclonepush.domain.repository.*
import kotlinx.coroutines.flow.flowOf
import java.io.File

/** Deterministic UI data; unexpected Git mutations fail instead of touching a user's repository. */
internal class WorkspaceUiFixture : LocalRepositoryStore, GitRepositoryReader,
    GitRepositoryMutator, GitRemoteSynchronizer, GitAuthorIdentityReader,
    GitBranchReader, RepositoryFileReader {
    val repository = LocalRepository("ui-fixture", "demo", "Android client", "/ui-fixture", "")
    val changes = listOf(
        FileChange("src/RepositoryReader.kt", FileChangeStatus.MODIFIED, GitFileState.MODIFIED, GitFileState.MODIFIED)
    )

    fun createViewModel() = RepositoryViewModel(
        repository.id, this, this, this, this, this, branchReader = this
    )

    override fun repositoryDirectory(repositoryId: String) = File(repository.directoryPath)
    override suspend fun listRepositories() = listOf(repository)
    override suspend fun save(repository: LocalRepository) = error("No storage writes in UI tests")
    override suspend fun status(repository: LocalRepository) = RepositoryStatus(changes, clean = false)
    override suspend fun currentBranch(repository: LocalRepository) = GitBranch("main", true)
    override suspend fun recentCommits(repository: LocalRepository, limit: Int) =
        (1..20).take(limit).map { GitCommit("Improve repository workflow $it", "commit-$it", "Demo developer", "Today") }
    override suspend fun listBranches(repository: LocalRepository) = listOf(
        GitBranchInfo("main", "refs/heads/main", BranchKind.LOCAL, true),
        GitBranchInfo("feature/accessible-workspace", "refs/heads/feature/accessible-workspace", BranchKind.LOCAL, false)
    )
    override fun observe() = flowOf(GitAuthorIdentity("Demo developer", "demo@example.com"))
    override suspend fun readState(repository: LocalRepository) = RemoteSyncState(
        branch = "main", upstream = "refs/remotes/origin/main", ahead = 0, behind = 0,
        hasRemote = true, hasUpstream = true
    )
    override suspend fun listDirectory(repositoryId: String, relativePath: String) =
        (1..40).map { RepositoryFileEntry.File("Source$it.kt", "Source$it.kt", 256L, false) }
    override suspend fun readFile(repositoryId: String, relativePath: String) =
        RepositoryFileContent.Text("// UI fixture", truncated = false)
    override suspend fun stage(repository: LocalRepository, paths: List<String>) = unexpectedMutation()
    override suspend fun unstage(repository: LocalRepository, paths: List<String>) = unexpectedMutation()
    override suspend fun stageAll(repository: LocalRepository) = unexpectedMutation()
    override suspend fun unstageAll(repository: LocalRepository) = unexpectedMutation()
    override suspend fun commit(repository: LocalRepository, request: CommitRequest): GitCommit = unexpectedMutation()
    override suspend fun fetch(repository: LocalRepository) = unexpectedMutation()
    override suspend fun pull(repository: LocalRepository): PullResult = unexpectedMutation()
    override suspend fun push(repository: LocalRepository): PushResult = unexpectedMutation()
    private fun unexpectedMutation(): Nothing = error("No Git mutations in scrolling tests")
}
