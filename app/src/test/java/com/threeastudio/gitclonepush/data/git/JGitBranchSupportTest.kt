package com.threeastudio.gitclonepush.data.git

import com.threeastudio.gitclonepush.core.model.BranchKind
import com.threeastudio.gitclonepush.core.model.LocalRepository
import com.threeastudio.gitclonepush.domain.repository.BranchErrorCategory
import com.threeastudio.gitclonepush.domain.repository.BranchOperationException
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.PersonIdent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class JGitBranchSupportTest {
    @Test
    fun listsLocalRemoteBranchesAndMarksCurrent() = runBlocking {
        withFixture { fixture ->
            fixture.git.repository.updateRef("refs/remotes/origin/feature/remote").apply { setNewObjectId(fixture.git.repository.resolve("HEAD")) }.update()
            val branches = fixture.reader.listBranches(fixture.local)
            assertTrue(branches.any { it.kind == BranchKind.LOCAL && it.isCurrent && it.name == "master" })
            assertTrue(branches.any { it.kind == BranchKind.REMOTE && it.name == "origin/feature/remote" })
        }
    }

    @Test
    fun createsChecksOutAndRenamesBranchPreservingTip() = runBlocking {
        withFixture { fixture ->
            val tip = fixture.git.repository.resolve("HEAD")
            fixture.mutator.create(fixture.local, "feature/test")
            fixture.mutator.checkout(fixture.local, "feature/test")
            assertEquals("refs/heads/feature/test", fixture.git.repository.fullBranch)
            fixture.mutator.rename(fixture.local, "feature/test", "feature/renamed")
            assertEquals("refs/heads/feature/renamed", fixture.git.repository.fullBranch)
            assertEquals(tip, fixture.git.repository.resolve("refs/heads/feature/renamed"))
        }
    }

    @Test
    fun dirtyCheckoutIsBlockedWithoutChangingBranchOrContent() = runBlocking {
        withFixture { fixture ->
            fixture.mutator.create(fixture.local, "feature/other", checkout = true)
            File(fixture.root, "README.md").writeText("feature")
            fixture.git.add().addFilepattern("README.md").call()
            fixture.git.commit().setMessage("feature change").setAuthor(PersonIdent("Test", "test@example.com")).call()
            fixture.mutator.checkout(fixture.local, "master")
            File(fixture.root, "README.md").writeText("uncommitted")
            val error = runCatching { fixture.mutator.checkout(fixture.local, "feature/other") }.exceptionOrNull()
            assertEquals(BranchErrorCategory.CHECKOUT_WOULD_OVERWRITE_CHANGES, (error as BranchOperationException).category)
            assertEquals("master", fixture.git.repository.branch)
            assertEquals("uncommitted", File(fixture.root, "README.md").readText())
        }
    }

    @Test
    fun createsTrackingBranchAndSetsUpstream() = runBlocking {
        withFixture { fixture ->
            fixture.git.repository.updateRef("refs/remotes/origin/feature/remote").apply { setNewObjectId(fixture.git.repository.resolve("HEAD")) }.update()
            fixture.mutator.createTracking(fixture.local, "refs/remotes/origin/feature/remote", "feature/local", true)
            Git.open(fixture.root).use { reopened ->
                assertEquals("refs/heads/feature/local", reopened.repository.fullBranch)
                assertEquals("origin", reopened.repository.config.getString("branch", "feature/local", "remote"))
                assertEquals("refs/heads/feature/remote", reopened.repository.config.getString("branch", "feature/local", "merge"))
                assertEquals("origin", reopened.repository.config.getString("branch", "feature/local", "remote"))
                assertEquals("refs/heads/feature/remote", reopened.repository.config.getString("branch", "feature/local", "merge"))
            }
            fixture.mutator.removeUpstream(fixture.local, "feature/local")
            Git.open(fixture.root).use { reopened -> assertEquals(null, reopened.repository.config.getString("branch", "feature/local", "remote")) }
        }
    }

    @Test
    fun currentAndUnmergedBranchesCannotBeDeleted() = runBlocking {
        withFixture { fixture ->
            val current = runCatching { fixture.mutator.delete(fixture.local, "master") }.exceptionOrNull()
            assertEquals(BranchErrorCategory.CANNOT_DELETE_CURRENT_BRANCH, (current as BranchOperationException).category)
            fixture.mutator.create(fixture.local, "feature/unmerged", checkout = true)
            File(fixture.root, "unmerged.txt").writeText("work")
            fixture.git.add().addFilepattern("unmerged.txt").call()
            fixture.git.commit().setMessage("unmerged").setAuthor(PersonIdent("Test", "test@example.com")).call()
            fixture.mutator.checkout(fixture.local, "master")
            val unmerged = runCatching { fixture.mutator.delete(fixture.local, "feature/unmerged") }.exceptionOrNull()
            assertEquals(BranchErrorCategory.BRANCH_NOT_MERGED, (unmerged as BranchOperationException).category)
            assertTrue(fixture.git.repository.findRef("refs/heads/feature/unmerged") != null)
        }
    }

    @Test
    fun detachedHeadCanRecoverByCreatingBranchAtCurrentCommit() = runBlocking {
        withFixture { fixture ->
            val detached = fixture.git.repository.resolve("HEAD")
            fixture.git.checkout().setName(detached.name).setForced(true).call()
            fixture.mutator.create(fixture.local, "recovery", "HEAD", checkout = true)
            assertEquals("refs/heads/recovery", fixture.git.repository.fullBranch)
            assertEquals(detached, fixture.git.repository.resolve("refs/heads/recovery"))
        }
    }

    private suspend fun withFixture(block: suspend (Fixture) -> Unit) {
        val root = Files.createTempDirectory("git-branches").toFile()
        val git = Git.init().setDirectory(root).call()
        try {
            File(root, "README.md").writeText("initial")
            git.add().addFilepattern("README.md").call()
            git.commit().setMessage("initial").setAuthor(PersonIdent("Test", "test@example.com")).call()
            git.repository.config.setString("remote", "origin", "url", "https://github.com/owner/repo.git")
            git.repository.config.save()
            val local = LocalRepository("1", "owner", "repo", root.absolutePath, "https://github.com/owner/repo.git")
            val loader = JGitRepositoryLoader()
            block(Fixture(root, git, local, JGitBranchReader(loader), JGitBranchMutator(loader, GitRepositoryOperationCoordinator())))
        } finally {
            git.close()
            root.deleteRecursively()
        }
    }

    private data class Fixture(val root: File, val git: Git, val local: LocalRepository, val reader: JGitBranchReader, val mutator: JGitBranchMutator)
}
