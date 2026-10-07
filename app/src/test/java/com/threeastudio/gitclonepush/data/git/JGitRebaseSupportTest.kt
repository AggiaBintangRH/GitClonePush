package com.threeastudio.gitclonepush.data.git

import com.threeastudio.gitclonepush.core.model.LocalRepository
import com.threeastudio.gitclonepush.core.model.RemoteStateFreshness
import com.threeastudio.gitclonepush.core.model.RemoteSyncState
import com.threeastudio.gitclonepush.core.model.RebaseAction
import com.threeastudio.gitclonepush.core.model.RebaseOperationState
import com.threeastudio.gitclonepush.core.model.RebaseResult
import com.threeastudio.gitclonepush.core.model.InteractiveRebaseItem
import com.threeastudio.gitclonepush.domain.repository.GitRemoteSynchronizer
import com.threeastudio.gitclonepush.domain.repository.RebaseErrorCategory
import com.threeastudio.gitclonepush.domain.repository.RebaseOperationException
import com.threeastudio.gitclonepush.domain.repository.GitRepositoryMutator
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.lib.RepositoryState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class JGitRebaseSupportTest {
    @Test
    fun cleanRebaseRewritesCurrentBranchAndPreservesContent() = runBlocking {
        withFixture { fixture ->
            commit(fixture.git, fixture.root, "base.txt", "base", "base")
            fixture.git.branchCreate().setName("feature").call()
            fixture.git.checkout().setName("master").call()
            commit(fixture.git, fixture.root, "main.txt", "main", "main")
            fixture.git.checkout().setName("feature").call()
            commit(fixture.git, fixture.root, "feature.txt", "feature", "feature")
            val oldFeatureTip = fixture.git.repository.resolve("refs/heads/feature")!!.name

            val result = fixture.rebase.start(fixture.local, "master")

            assertTrue("Unexpected interactive result: $result", result is RebaseResult.Rebased || result is RebaseResult.FastForward)
            assertEquals("feature", fixture.git.repository.branch)
            assertTrue(File(fixture.root, "main.txt").isFile)
            assertTrue(File(fixture.root, "feature.txt").isFile)
            assertFalse(oldFeatureTip == fixture.git.repository.resolve("HEAD")!!.name)
            assertEquals(RepositoryState.SAFE, fixture.git.repository.repositoryState)
        }
    }

    @Test
    fun alreadyUpToDateIsReportedWithoutChangingHead() = runBlocking {
        withFixture { fixture ->
            commit(fixture.git, fixture.root, "base.txt", "base", "base")
            fixture.git.branchCreate().setName("feature").call()
            fixture.git.checkout().setName("feature").call()
            val before = fixture.git.repository.resolve("HEAD")!!.name

            assertTrue(fixture.rebase.start(fixture.local, "master") is RebaseResult.AlreadyUpToDate)
            assertEquals(before, fixture.git.repository.resolve("HEAD")!!.name)
        }
    }

    @Test
    fun conflictStateSurvivesReaderRecreationAndContinueAfterStaging() = runBlocking {
        withFixture { fixture ->
            commit(fixture.git, fixture.root, "conflict.txt", "base", "base")
            fixture.git.branchCreate().setName("feature").call()
            fixture.git.checkout().setName("master").call()
            commit(fixture.git, fixture.root, "conflict.txt", "master", "master")
            fixture.git.checkout().setName("feature").call()
            commit(fixture.git, fixture.root, "conflict.txt", "feature", "feature")

            val conflict = fixture.rebase.start(fixture.local, "master")
            assertTrue("Unexpected rebase result: $conflict", conflict is RebaseResult.Conflicted)
            val restored = JGitRebaseSupport(JGitRepositoryLoader(), GitRepositoryOperationCoordinator()).readState(fixture.local)
            assertEquals(RebaseOperationState.CONFLICTED, restored.operationState)
            assertTrue(restored.conflictedPaths.contains("conflict.txt"))

            File(fixture.root, "conflict.txt").writeText("resolved")
            fixture.repositoryMutator.stage(fixture.local, listOf("conflict.txt"))
            val continued = fixture.rebase.continueRebase(fixture.local)
            assertTrue(continued is RebaseResult.Rebased || continued is RebaseResult.Stopped)
            assertEquals(RepositoryState.SAFE, fixture.git.repository.repositoryState)
            assertTrue(fixture.git.status().call().isClean)
        }
    }

    @Test
    fun abortRestoresPreRebaseBranchAndTree() = runBlocking {
        withFixture { fixture ->
            commit(fixture.git, fixture.root, "conflict.txt", "base", "base")
            fixture.git.branchCreate().setName("feature").call()
            fixture.git.checkout().setName("master").call()
            commit(fixture.git, fixture.root, "conflict.txt", "master", "master")
            fixture.git.checkout().setName("feature").call()
            commit(fixture.git, fixture.root, "conflict.txt", "feature", "feature")
            val originalTip = fixture.git.repository.resolve("HEAD")!!.name

            val conflict = fixture.rebase.start(fixture.local, "master")
            assertTrue("Unexpected rebase result: $conflict", conflict is RebaseResult.Conflicted)
            fixture.rebase.abort(fixture.local)

            assertEquals(originalTip, fixture.git.repository.resolve("HEAD")!!.name)
            assertEquals("feature", fixture.git.repository.branch)
            assertEquals("feature", File(fixture.root, "conflict.txt").readText())
            assertEquals(RepositoryState.SAFE, fixture.git.repository.repositoryState)
        }
    }

    @Test
    fun skipCommitOmitsConflictingReplayAndFinishesSafely() = runBlocking {
        withFixture { fixture ->
            commit(fixture.git, fixture.root, "conflict.txt", "base", "base")
            fixture.git.branchCreate().setName("feature").call()
            fixture.git.checkout().setName("master").call()
            commit(fixture.git, fixture.root, "conflict.txt", "master", "master")
            fixture.git.checkout().setName("feature").call()
            commit(fixture.git, fixture.root, "conflict.txt", "feature", "conflicting feature")

            val started = fixture.rebase.start(fixture.local, "master")
            assertTrue(started is RebaseResult.Conflicted)
            val skipped = fixture.rebase.skipCommit(fixture.local)

            assertTrue(skipped is RebaseResult.Rebased || skipped is RebaseResult.AlreadyUpToDate)
            assertEquals("master", File(fixture.root, "conflict.txt").readText())
            assertEquals(RepositoryState.SAFE, fixture.git.repository.repositoryState)
            assertTrue(fixture.git.log().call().asSequence().none { it.shortMessage == "conflicting feature" })
        }
    }

    @Test
    fun dirtyAndDetachedRepositoriesAreRejected() = runBlocking {
        withFixture { fixture ->
            commit(fixture.git, fixture.root, "base.txt", "base", "base")
            fixture.git.branchCreate().setName("feature").call()
            fixture.git.checkout().setName("feature").call()
            File(fixture.root, "dirty.txt").writeText("dirty")
            val dirty = runCatching { fixture.rebase.start(fixture.local, "master") }.exceptionOrNull()
            assertEquals(RebaseErrorCategory.REBASE_BLOCKED_BY_LOCAL_CHANGES, (dirty as RebaseOperationException).category)

            fixture.git.checkout().setName(fixture.git.repository.resolve("HEAD")!!.name).setForced(true).call()
            File(fixture.root, "dirty.txt").delete()
            val detached = runCatching { fixture.rebase.start(fixture.local, "master") }.exceptionOrNull()
            assertEquals(RebaseErrorCategory.DETACHED_HEAD, (detached as RebaseOperationException).category)
        }
    }

    @Test
    fun publishedHistoryRewriteIsBlockedWhenRemoteIsBehind() = runBlocking {
        withFixture(remote = FakeRemote(RemoteSyncState(hasUpstream = true, freshness = RemoteStateFreshness.FRESH, behind = 1))) { fixture ->
            commit(fixture.git, fixture.root, "base.txt", "base", "base")
            fixture.git.branchCreate().setName("feature").call()
            fixture.git.checkout().setName("master").call()
            commit(fixture.git, fixture.root, "main.txt", "main", "main")
            fixture.git.checkout().setName("feature").call()
            commit(fixture.git, fixture.root, "feature.txt", "feature", "feature")

            val error = runCatching { fixture.rebase.start(fixture.local, "master") }.exceptionOrNull()
            assertEquals(RebaseErrorCategory.PUBLISHED_HISTORY_REWRITE_BLOCKED, (error as RebaseOperationException).category)
            assertEquals("feature", fixture.git.repository.branch)
            assertEquals(RepositoryState.SAFE, fixture.git.repository.repositoryState)
        }
    }

    @Test
    fun interactivePlanRejectsInvalidFirstSquashAndDuplicateCommit() = runBlocking {
        withFixture { fixture ->
            commit(fixture.git, fixture.root, "base.txt", "base", "base")
            commit(fixture.git, fixture.root, "second.txt", "second", "second")
            val head = fixture.git.repository.resolve("HEAD")!!.name
            val parent = fixture.git.repository.resolve("HEAD^1")!!.name

            val firstSquash = runCatching {
                fixture.rebase.interactive(fixture.local, listOf(InteractiveRebaseItem(head, head.take(7), "second", RebaseAction.SQUASH)))
            }.exceptionOrNull()
            assertEquals(RebaseErrorCategory.INVALID_INTERACTIVE_PLAN, (firstSquash as RebaseOperationException).category)

            val duplicate = runCatching {
                fixture.rebase.interactive(fixture.local, listOf(
                    InteractiveRebaseItem(parent, parent.take(7), "base"),
                    InteractiveRebaseItem(parent, parent.take(7), "base")
                ))
            }.exceptionOrNull()
            assertEquals(RebaseErrorCategory.INVALID_INTERACTIVE_PLAN, (duplicate as RebaseOperationException).category)
        }
    }

    @Test
    fun interactiveRewordChangesMessageWithoutChangingTree() = runBlocking {
        withFixture { fixture ->
            commit(fixture.git, fixture.root, "base.txt", "base", "base")
            commit(fixture.git, fixture.root, "first.txt", "first", "first message")
            commit(fixture.git, fixture.root, "second.txt", "second", "old message")
            val second = fixture.git.repository.resolve("HEAD")!!.name
            val first = fixture.git.repository.resolve("HEAD^1")!!.name

            val result = fixture.rebase.interactive(fixture.local, listOf(
                InteractiveRebaseItem(first, first.take(7), "first message"),
                InteractiveRebaseItem(second, second.take(7), "old message", RebaseAction.REWORD, "new message")
            ))

            assertTrue("Unexpected interactive result: $result", result is RebaseResult.Rebased || result is RebaseResult.FastForward)
            assertEquals("new message", fixture.git.log().call().iterator().next().shortMessage)
            assertTrue(File(fixture.root, "second.txt").isFile)
            assertTrue(fixture.git.status().call().isClean)
        }
    }

    private suspend fun withFixture(remote: GitRemoteSynchronizer? = null, block: suspend (Fixture) -> Unit) {
        val root = Files.createTempDirectory("git-rebase").toFile()
        val git = Git.init().setDirectory(root).call()
        git.repository.config.setString("user", null, "name", "Author")
        git.repository.config.setString("user", null, "email", "author@example.com")
        git.repository.config.save()
        val local = LocalRepository("rebase-1", "owner", "repo", root.absolutePath, "https://github.com/owner/repo.git")
        try {
            val loader = JGitRepositoryLoader()
            block(Fixture(root, git, local, JGitRebaseSupport(loader, GitRepositoryOperationCoordinator(), remoteSynchronizer = remote), JGitRepositoryMutator(loader)))
        } finally {
            git.close()
            root.deleteRecursively()
        }
    }

    private fun commit(git: Git, root: File, path: String, content: String, message: String) {
        File(root, path).writeText(content)
        git.add().addFilepattern(path).call()
        git.commit().setMessage(message).setAuthor(PersonIdent("Author", "author@example.com")).call()
    }

    private data class Fixture(
        val root: File,
        val git: Git,
        val local: LocalRepository,
        val rebase: JGitRebaseSupport,
        val repositoryMutator: GitRepositoryMutator
    )

    private class FakeRemote(private val state: RemoteSyncState) : GitRemoteSynchronizer {
        override suspend fun fetch(repository: LocalRepository) = Unit
        override suspend fun pull(repository: LocalRepository) = com.threeastudio.gitclonepush.core.model.PullResult.AlreadyUpToDate
        override suspend fun push(repository: LocalRepository) = com.threeastudio.gitclonepush.core.model.PushResult.AlreadyUpToDate
        override suspend fun readState(repository: LocalRepository): RemoteSyncState = state
    }
}
