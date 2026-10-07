package com.threeastudio.gitclonepush.data.git

import com.threeastudio.gitclonepush.core.model.LocalRepository
import com.threeastudio.gitclonepush.core.model.RepositoryStatus
import com.threeastudio.gitclonepush.domain.repository.CherryPickOperationException
import com.threeastudio.gitclonepush.domain.repository.GitRemoteSynchronizer
import com.threeastudio.gitclonepush.domain.repository.RevertErrorCategory
import com.threeastudio.gitclonepush.domain.repository.RevertOperationException
import com.threeastudio.gitclonepush.domain.repository.StashErrorCategory
import com.threeastudio.gitclonepush.domain.repository.StashOperationException
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.lib.RepositoryState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class JGitHistoryMutationSupportTest {
    @Test
    fun revertCreatesInverseCommitAndPreservesOriginalHistory() = runBlocking {
        withFixture { f ->
            commit(f.git, f.root, "README.md", "base", "base")
            commit(f.git, f.root, "README.md", "changed", "change")
            val target = f.git.repository.resolve("HEAD")!!.name
            f.revert.revert(f.local, target)
            assertEquals("base", File(f.root, "README.md").readText())
            assertEquals(3, f.git.log().call().toList().size)
            assertNotEquals(target, f.git.repository.resolve("HEAD")!!.name)
            assertEquals(RepositoryState.SAFE, f.git.repository.repositoryState)
        }
    }

    @Test
    fun mergeRevertRequiresExplicitMainlineAndSafelyRejectsUnsupportedJGitPath() = runBlocking {
        withFixture { f ->
            commit(f.git, f.root, "base.txt", "base", "base")
            f.git.branchCreate().setName("feature").call()
            f.git.checkout().setName("master").call()
            commit(f.git, f.root, "main.txt", "main", "main")
            f.git.checkout().setName("feature").call()
            commit(f.git, f.root, "feature.txt", "feature", "feature")
            f.git.checkout().setName("master").call()
            f.git.merge().include(f.git.repository.resolve("refs/heads/feature")!!).call()
            val merge = f.git.repository.resolve("HEAD")!!.name
            val error = runCatching { f.revert.revert(f.local, merge) }.exceptionOrNull()
            assertEquals(RevertErrorCategory.MAINLINE_PARENT_REQUIRED, (error as RevertOperationException).category)
            assertEquals(RepositoryState.SAFE, f.git.repository.repositoryState)
        }
    }

    @Test
    fun cherryPickAppliesSingleAndMultipleCommitsInProvidedOrder() = runBlocking {
        withFixture { f ->
            commit(f.git, f.root, "base.txt", "base", "base")
            f.git.branchCreate().setName("feature").call()
            f.git.checkout().setName("feature").call()
            commit(f.git, f.root, "one.txt", "one", "one")
            val one = f.git.repository.resolve("HEAD")!!.name
            commit(f.git, f.root, "two.txt", "two", "two")
            val two = f.git.repository.resolve("HEAD")!!.name
            f.git.checkout().setName("master").call()
            f.cherry.cherryPick(f.local, listOf(one, two))
            assertTrue(File(f.root, "one.txt").isFile)
            assertTrue(File(f.root, "two.txt").isFile)
            assertEquals(listOf("two", "one"), f.git.log().call().take(2).map { it.shortMessage })
            assertEquals(RepositoryState.SAFE, f.git.repository.repositoryState)
        }
    }

    @Test
    fun cherryPickRejectsMergeCommitWithoutMainline() = runBlocking {
        withFixture { f ->
            commit(f.git, f.root, "base.txt", "base", "base")
            f.git.branchCreate().setName("feature").call()
            f.git.checkout().setName("master").call()
            commit(f.git, f.root, "main.txt", "main", "main")
            f.git.checkout().setName("feature").call()
            commit(f.git, f.root, "feature.txt", "feature", "feature")
            f.git.checkout().setName("master").call()
            f.git.merge().include(f.git.repository.resolve("refs/heads/feature")!!).call()
            val merge = f.git.repository.resolve("HEAD")!!.name
            f.git.checkout().setName("feature").call()
            f.git.checkout().setName("master").call()
            val error = runCatching { f.cherry.cherryPick(f.local, listOf(merge)) }.exceptionOrNull()
            assertEquals(com.threeastudio.gitclonepush.domain.repository.CherryPickErrorCategory.MERGE_COMMIT_MAINLINE_REQUIRED, (error as CherryPickOperationException).category)
        }
    }

    @Test
    fun stashTrackedAndOptionalUntrackedChangesApplyKeepsEntryPopRemovesIt() = runBlocking {
        withFixture { f ->
            commit(f.git, f.root, "README.md", "base", "base")
            File(f.root, "README.md").writeText("tracked")
            File(f.root, "new.txt").writeText("untracked")
            val entry = f.stash.create(f.local, "work", includeUntracked = true)
            assertEquals("base", File(f.root, "README.md").readText())
            assertFalse(File(f.root, "new.txt").exists())
            assertEquals(1, f.stash.list(f.local).size)
            f.stash.apply(f.local, entry.index)
            assertEquals(1, f.stash.list(f.local).size)
            f.git.reset().setMode(org.eclipse.jgit.api.ResetCommand.ResetType.HARD).setRef("HEAD").call()
            File(f.root, "new.txt").delete()
            f.stash.pop(f.local, 0)
            assertEquals(0, f.stash.list(f.local).size)
            assertEquals("tracked", File(f.root, "README.md").readText())
            assertEquals("untracked", File(f.root, "new.txt").readText())
        }
    }

    @Test
    fun stashDropLeavesWorkingTreeUnchanged() = runBlocking {
        withFixture { f ->
            commit(f.git, f.root, "README.md", "base", "base")
            File(f.root, "README.md").writeText("tracked")
            f.stash.create(f.local, null, false)
            assertEquals("base", File(f.root, "README.md").readText())
            f.stash.drop(f.local, 0)
            assertEquals(0, f.stash.list(f.local).size)
            assertEquals("base", File(f.root, "README.md").readText())
        }
    }

    private suspend fun withFixture(block: suspend (Fixture) -> Unit) {
        val root = Files.createTempDirectory("git-history-mutations").toFile()
        val git = Git.init().setDirectory(root).call()
        git.repository.config.setString("user", null, "name", "Author")
        git.repository.config.setString("user", null, "email", "author@example.com")
        git.repository.config.save()
        val local = LocalRepository("history-1", "owner", "repo", root.absolutePath, "https://github.com/owner/repo.git")
        val coordinator = GitRepositoryOperationCoordinator()
        val loader = JGitRepositoryLoader()
        try { block(Fixture(root, git, local, JGitRevertSupport(loader, coordinator), JGitCherryPickSupport(loader, coordinator), JGitStashSupport(loader, coordinator))) }
        finally { git.close(); root.deleteRecursively() }
    }

    private fun commit(git: Git, root: File, path: String, content: String, message: String) {
        File(root, path).writeText(content)
        git.add().addFilepattern(path).call()
        git.commit().setMessage(message).setAuthor(PersonIdent("Author", "author@example.com")).call()
    }

    private data class Fixture(val root: File, val git: Git, val local: LocalRepository, val revert: JGitRevertSupport, val cherry: JGitCherryPickSupport, val stash: JGitStashSupport)
}
