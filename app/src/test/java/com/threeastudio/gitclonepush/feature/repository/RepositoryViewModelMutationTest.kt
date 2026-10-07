package com.threeastudio.gitclonepush.feature.repository

import com.threeastudio.gitclonepush.core.model.CommitRequest
import com.threeastudio.gitclonepush.core.model.AuthState
import com.threeastudio.gitclonepush.core.model.AuthenticatedUser
import com.threeastudio.gitclonepush.domain.usecase.SessionGitAuthorIdentityReader
import com.threeastudio.gitclonepush.testing.FakeAuthRepository
import com.threeastudio.gitclonepush.core.model.FileChange
import com.threeastudio.gitclonepush.core.model.FileChangeStatus
import com.threeastudio.gitclonepush.core.model.GitAuthorIdentity
import com.threeastudio.gitclonepush.core.model.GitBranch
import com.threeastudio.gitclonepush.core.model.GitCommit
import com.threeastudio.gitclonepush.core.model.GitMutationState
import com.threeastudio.gitclonepush.core.model.LocalRepository
import com.threeastudio.gitclonepush.core.model.RepositoryStatus
import com.threeastudio.gitclonepush.domain.repository.GitAuthorIdentityReader
import com.threeastudio.gitclonepush.domain.repository.GitRemoteSynchronizer
import com.threeastudio.gitclonepush.domain.repository.GitRepositoryMutator
import com.threeastudio.gitclonepush.domain.repository.GitRepositoryReader
import com.threeastudio.gitclonepush.domain.repository.LocalRepositoryStore
import com.threeastudio.gitclonepush.core.model.PullResult
import com.threeastudio.gitclonepush.core.model.PushResult
import com.threeastudio.gitclonepush.core.model.RemoteStateFreshness
import com.threeastudio.gitclonepush.core.model.RemoteSyncState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertNull
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class RepositoryViewModelMutationTest {
    @Test
    fun stageShowsProgressBlocksDuplicateRequestsAndClearsProgressAfterSuccess() = testViewModel {
        val gate = CompletableDeferred<Unit>()
        val mutator = FakeMutator().apply { stageGate = gate }
        val viewModel = createViewModel(mutator)
        advanceUntilIdle()
        viewModel.stage("new.txt")
        runCurrent()
        assertEquals("Staging changes…", viewModel.uiState.value.progressMessage)
        viewModel.stage("new.txt")
        runCurrent()
        assertEquals(1, mutator.stagedPaths.size)
        gate.complete(Unit)
        advanceUntilIdle()
        assertNull(viewModel.uiState.value.progressMessage)
    }

    @Test
    fun identityLookupFailureClearsCommitLoadingAndPreservesMessage() = testViewModel {
        val identity = object : GitAuthorIdentityReader {
            override fun observe() = kotlinx.coroutines.flow.flow<com.threeastudio.gitclonepush.core.model.GitAuthorIdentity?> { error("identity read failure") }
        }
        val viewModel = createViewModel(FakeMutator(), identityReader = identity)
        advanceUntilIdle()
        viewModel.updateCommitMessage("Retain this message")
        viewModel.commit()
        advanceUntilIdle()
        assertNull(viewModel.uiState.value.progressMessage)
        assertEquals("Retain this message", viewModel.uiState.value.commitMessage)
        assertTrue(viewModel.uiState.value.errorMessage != null)
    }

    @Test
    fun stageRefreshesStatusAndClearsSelection() = testViewModel {
        val mutator = FakeMutator()
        val viewModel = createViewModel(mutator)
        advanceUntilIdle()
        viewModel.togglePathSelection("new.txt")
        viewModel.stageSelected()
        advanceUntilIdle()
        assertEquals(listOf(listOf("new.txt")), mutator.stagedPaths)
        assertTrue(viewModel.uiState.value.selectedPaths.isEmpty())
        assertEquals(GitMutationState.IDLE, viewModel.uiState.value.mutationState)
    }

    @Test
    fun commitUsesConfiguredAuthorAndClearsMessageOnlyOnSuccess() = testViewModel {
        val mutator = FakeMutator()
        val viewModel = createViewModel(mutator)
        advanceUntilIdle()
        viewModel.updateCommitMessage("Add file")
        viewModel.commit()
        advanceUntilIdle()
        assertEquals(CommitRequest("Add file", "Configured", "configured@example.com"), mutator.lastCommit)
        assertEquals("", viewModel.uiState.value.commitMessage)
    }

    @Test
    fun commitAutomaticallyUsesCurrentGitHubAccountWithoutManualSettings() = testViewModel {
        val auth = FakeAuthRepository(AuthState.Authenticated(AuthenticatedUser(123456, "alice", "Alice", null)))
        val mutator = FakeMutator()
        val viewModel = createViewModel(mutator, identityReader = SessionGitAuthorIdentityReader(auth))
        advanceUntilIdle()
        viewModel.updateCommitMessage("Account commit")
        viewModel.commit()
        advanceUntilIdle()
        assertEquals(CommitRequest("Account commit", "Alice", "123456+alice@users.noreply.github.com"), mutator.lastCommit)
        auth.state.value = AuthState.Authenticated(AuthenticatedUser(654321, "bob", "Bob", null))
        viewModel.updateCommitMessage("New account commit")
        viewModel.commit()
        advanceUntilIdle()
        assertEquals(CommitRequest("New account commit", "Bob", "654321+bob@users.noreply.github.com"), mutator.lastCommit)
    }

    @Test
    fun failedCommitPreservesMessage() = testViewModel {
        val mutator = FakeMutator().apply { failCommit = true }
        val viewModel = createViewModel(mutator)
        advanceUntilIdle()
        viewModel.updateCommitMessage("Keep me")
        viewModel.commit()
        advanceUntilIdle()
        assertEquals("Keep me", viewModel.uiState.value.commitMessage)
        assertTrue(viewModel.uiState.value.errorMessage != null)
    }

    @Test
    fun fetchRefreshesRemoteStateAndReturnsToIdle() = testViewModel {
        val synchronizer = FakeSynchronizer()
        val viewModel = createViewModel(FakeMutator(), synchronizer)
        advanceUntilIdle()
        viewModel.fetch()
        advanceUntilIdle()
        assertEquals(1, synchronizer.fetchCalls)
        assertEquals(RemoteStateFreshness.FRESH, viewModel.uiState.value.remoteState.freshness)
        assertEquals(com.threeastudio.gitclonepush.core.model.RemoteOperationState.IDLE, viewModel.uiState.value.remoteOperationState)
    }

    private fun testViewModel(block: suspend kotlinx.coroutines.test.TestScope.() -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try { block() } finally { Dispatchers.resetMain() }
    }

    private fun createViewModel(mutator: FakeMutator, synchronizer: GitRemoteSynchronizer = NoOpSynchronizer, identityReader: GitAuthorIdentityReader = FixedIdentityReader()): RepositoryViewModel = RepositoryViewModel(
        "1", FakeStore(), FakeReader(), mutator, synchronizer, identityReader
    )

    private class FakeStore : LocalRepositoryStore {
        private val repository = LocalRepository("1", "owner", "repo", File("build/test-repo").absolutePath, "https://github.com/owner/repo.git")
        override fun repositoryDirectory(repositoryId: String) = File(repository.directoryPath)
        override suspend fun listRepositories() = listOf(repository)
        override suspend fun save(repository: LocalRepository) = Unit
    }

    private class FakeReader : GitRepositoryReader {
        private val status = RepositoryStatus(listOf(FileChange("new.txt", FileChangeStatus.ADDED, workTreeState = com.threeastudio.gitclonepush.core.model.GitFileState.UNTRACKED)), false)
        override suspend fun status(repository: LocalRepository) = status
        override suspend fun currentBranch(repository: LocalRepository) = GitBranch("main", true)
        override suspend fun recentCommits(repository: LocalRepository, limit: Int) = emptyList<GitCommit>()
    }

    private class FakeMutator : GitRepositoryMutator {
        val stagedPaths = mutableListOf<List<String>>()
        var lastCommit: CommitRequest? = null
        var failCommit = false
        var stageGate: CompletableDeferred<Unit>? = null
        override suspend fun stage(repository: LocalRepository, paths: List<String>) { stagedPaths += paths; stageGate?.await() }
        override suspend fun unstage(repository: LocalRepository, paths: List<String>) = Unit
        override suspend fun stageAll(repository: LocalRepository) = Unit
        override suspend fun unstageAll(repository: LocalRepository) = Unit
        override suspend fun commit(repository: LocalRepository, request: CommitRequest): GitCommit {
            if (failCommit) error("test commit failure")
            lastCommit = request
            return GitCommit("Add file", "abc1234", request.authorName, "now")
        }
    }

    private class FixedIdentityReader : GitAuthorIdentityReader {
        override fun observe() = kotlinx.coroutines.flow.flowOf(GitAuthorIdentity("Configured", "configured@example.com"))
    }

    private object NoOpSynchronizer : GitRemoteSynchronizer {
        override suspend fun fetch(repository: LocalRepository) = Unit
        override suspend fun pull(repository: LocalRepository) = PullResult.AlreadyUpToDate
        override suspend fun push(repository: LocalRepository) = PushResult.Pushed
    }

    private class FakeSynchronizer : GitRemoteSynchronizer {
        var fetchCalls = 0
        private var state = RemoteSyncState(branch = "main", upstream = "refs/remotes/origin/main", ahead = 0, behind = 0, freshness = RemoteStateFreshness.UNKNOWN, hasUpstream = true, hasRemote = true)
        override suspend fun fetch(repository: LocalRepository) { fetchCalls++; state = state.copy(freshness = RemoteStateFreshness.FRESH) }
        override suspend fun pull(repository: LocalRepository) = PullResult.AlreadyUpToDate
        override suspend fun push(repository: LocalRepository) = PushResult.AlreadyUpToDate
        override suspend fun readState(repository: LocalRepository) = state
    }
}
