package com.threeastudio.gitclonepush.data.git

import com.threeastudio.gitclonepush.core.model.GitCredentials
import com.threeastudio.gitclonepush.core.model.LocalRepository
import com.threeastudio.gitclonepush.core.model.PullResult
import com.threeastudio.gitclonepush.core.model.PushResult
import com.threeastudio.gitclonepush.domain.repository.GitCredentialProvider
import com.threeastudio.gitclonepush.domain.repository.RemoteSyncErrorCategory
import com.threeastudio.gitclonepush.domain.repository.RemoteSyncException
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.PersonIdent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class JGitRemoteSynchronizerTest {
    @Test
    fun fetchUpdatesTrackingRefWithoutChangingWorktree() = runBlocking {
        withFixture { fixture ->
            File(fixture.localDir, "README.md").writeText("local working tree")
            commit(fixture.remoteClone, "remote.txt", "remote")
            fixture.synchronizer.fetch(fixture.local)
            assertEquals("local working tree", File(fixture.localDir, "README.md").readText())
            val state = fixture.synchronizer.readState(fixture.local)
            assertEquals(1, state.behind)
            assertEquals(com.threeastudio.gitclonepush.core.model.RemoteStateFreshness.FRESH, state.freshness)
            assertTrue(fixture.credentials.calls > 0)
        }
    }

    @Test
    fun pullFastForwardsAndClearsBehindCount() = runBlocking {
        withFixture { fixture ->
            commit(fixture.remoteClone, "remote.txt", "remote")
            val result = fixture.synchronizer.pull(fixture.local)
            assertEquals(PullResult.Updated(1), result)
            assertTrue(File(fixture.localDir, "remote.txt").exists())
            val state = fixture.synchronizer.readState(fixture.local)
            assertEquals(0, state.ahead)
            assertEquals(0, state.behind)
        }
    }

    @Test
    fun pushUsesOperationTimeCredentialsAndAdvancesRemote() = runBlocking {
        withFixture { fixture ->
            commit(fixture.localGit, "local.txt", "local", push = false)
            assertEquals(PushResult.Pushed, fixture.synchronizer.push(fixture.local))
            assertTrue(fixture.credentials.calls > 0)
            assertEquals(0, fixture.synchronizer.readState(fixture.local).ahead)
            assertFalse(File(fixture.localDir, ".git/config").readText().contains("fake-token"))

            Git.open(File(fixture.root, "remote.git")).use { bareRemote ->
                val remoteHead = bareRemote.repository.resolve("refs/heads/master")
                assertEquals(fixture.localGit.repository.resolve("HEAD"), remoteHead)
            }
            fixture.synchronizer.fetch(fixture.local)
            assertTrue(File(fixture.localDir, "local.txt").exists())
        }
    }

    @Test
    fun pullProtectsLocalChanges() = runBlocking {
        withFixture { fixture ->
            commit(fixture.remoteClone, "README.md", "remote replacement")
            File(fixture.localDir, "README.md").writeText("user changes")
            val error = runCatching { fixture.synchronizer.pull(fixture.local) }.exceptionOrNull()
            assertEquals(RemoteSyncErrorCategory.LOCAL_CHANGES_WOULD_BE_OVERWRITTEN, (error as RemoteSyncException).category)
            assertEquals("user changes", File(fixture.localDir, "README.md").readText())
        }
    }

    @Test
    fun divergedHistoryIsRejectedWithoutMerge() = runBlocking {
        withFixture { fixture ->
            commit(fixture.localGit, "local.txt", "local", push = false)
            commit(fixture.remoteClone, "remote.txt", "remote")
            val error = runCatching { fixture.synchronizer.pull(fixture.local) }.exceptionOrNull()
            assertEquals(RemoteSyncErrorCategory.DIVERGED, (error as RemoteSyncException).category)
            assertFalse(File(fixture.localDir, "remote.txt").exists())
        }
    }

    @Test
    fun nonFastForwardPushIsRejectedWithoutForce() = runBlocking {
        withFixture { fixture ->
            commit(fixture.remoteClone, "remote.txt", "remote")
            commit(fixture.localGit, "local.txt", "local", push = false)
            val error = runCatching { fixture.synchronizer.push(fixture.local) }.exceptionOrNull()
            assertEquals(RemoteSyncErrorCategory.PUSH_REJECTED_NON_FAST_FORWARD, (error as RemoteSyncException).category)
        }
    }

    @Test
    fun detachedHeadAndNoUpstreamAreControlled() = runBlocking {
        withFixture { fixture ->
            fixture.localGit.checkout().setName(fixture.localGit.repository.resolve("HEAD").name).setForced(true).call()
            val detached = runCatching { fixture.synchronizer.pull(fixture.local) }.exceptionOrNull()
            assertEquals(RemoteSyncErrorCategory.DETACHED_HEAD, (detached as RemoteSyncException).category)
        }
    }

    private suspend fun withFixture(block: suspend (Fixture) -> Unit) {
        val root = Files.createTempDirectory("git-remote-sync").toFile()
        try {
            val bareDir = File(root, "remote.git")
            Git.init().setBare(true).setDirectory(bareDir).call().apply {
                repository.config.setBoolean("receive", null, "denyNonFastForwards", true)
                repository.config.save()
                close()
            }
            val seedDir = File(root, "seed")
            val seed = Git.init().setDirectory(seedDir).call()
            File(seedDir, "README.md").writeText("initial")
            seed.add().addFilepattern("README.md").call()
            seed.commit().setMessage("initial").setAuthor(PersonIdent("Test", "test@example.com")).call()
            seed.repository.config.setString("remote", "origin", "url", bareDir.toURI().toString())
            seed.repository.config.save()
            seed.push().setRemote("origin").setRefSpecs(org.eclipse.jgit.transport.RefSpec("refs/heads/master:refs/heads/master")).call()
            seed.close()

            val localDir = File(root, "local")
            val remoteCloneDir = File(root, "remote-clone")
            val localGit = Git.cloneRepository().setURI(bareDir.toURI().toString()).setDirectory(localDir).call()
            val remoteClone = Git.cloneRepository().setURI(bareDir.toURI().toString()).setDirectory(remoteCloneDir).call()
            val local = LocalRepository("1", "owner", "repo", localDir.absolutePath, bareDir.toURI().toString())
            val credentials = RecordingCredentials()
            val synchronizer = JGitRemoteSynchronizer(
                JGitRepositoryLoader(),
                credentials,
                allowLocalFileTransport = true
            )
            block(Fixture(root, localDir, remoteCloneDir, localGit, remoteClone, local, synchronizer, credentials))
            localGit.close()
            remoteClone.close()
        } finally {
            root.deleteRecursively()
        }
    }

    private fun commit(git: Git, path: String, content: String, push: Boolean = true) {
        File(git.repository.workTree, path).writeText(content)
        git.add().addFilepattern(path).call()
        git.commit().setMessage("change").setAuthor(PersonIdent("Test", "test@example.com")).call()
        if (push) git.push().call()
    }

    private data class Fixture(
        val root: File,
        val localDir: File,
        val remoteCloneDir: File,
        val localGit: Git,
        val remoteClone: Git,
        val local: LocalRepository,
        val synchronizer: JGitRemoteSynchronizer,
        val credentials: RecordingCredentials
    )

    private class RecordingCredentials : GitCredentialProvider {
        var calls = 0
        override suspend fun credentialsFor(remote: com.threeastudio.gitclonepush.core.model.GitRemote): GitCredentials {
            calls++
            return GitCredentials("x-access-token", "fake-token")
        }
    }
}
