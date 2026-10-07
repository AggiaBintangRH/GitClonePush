package com.threeastudio.gitclonepush.data.git

import com.threeastudio.gitclonepush.core.model.*
import com.threeastudio.gitclonepush.domain.repository.*
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.lib.RepositoryState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class JGitMergeSupportTest {
    @Test
    fun fastForwardMergeCreatesNoMergeCommit() = runBlocking {
        withRepo { root, git, merge ->
            initialCommit(git, root, "one.txt", "one")
            git.branchCreate().setName("feature").call()
            git.checkout().setName("feature").call()
            File(root, "two.txt").writeText("two")
            commit(git, "feature", "two.txt")
            git.checkout().setName("master").call()
            val before = git.repository.resolve("HEAD")!!.name
            assertTrue(merge.merge(local(root), "feature") is MergeResult.FastForward)
            assertEquals(git.repository.resolve("refs/heads/feature")!!.name, git.repository.resolve("HEAD")!!.name)
            assertTrue(before != git.repository.resolve("HEAD")!!.name)
            assertEquals(RepositoryState.SAFE, git.repository.repositoryState)
        }
    }

    @Test
    fun divergentCleanMergeCreatesMergeCommit() = runBlocking {
        withRepo { root, git, merge ->
            initialCommit(git, root, "base.txt", "base")
            git.branchCreate().setName("feature").call()
            File(root, "main.txt").writeText("main")
            commit(git, "main", "main.txt")
            git.checkout().setName("feature").call()
            File(root, "feature.txt").writeText("feature")
            commit(git, "feature", "feature.txt")
            git.checkout().setName("master").call()
            assertTrue(merge.merge(local(root), "feature") is MergeResult.Merged)
            val status = git.status().call()
            assertTrue(status.isClean)
            assertEquals(2, org.eclipse.jgit.revwalk.RevWalk(git.repository).use { walk -> walk.parseCommit(git.repository.resolve("HEAD")).parentCount })
        }
    }

    @Test
    fun conflictsPersistAndContinueAfterStagingResolution() = runBlocking {
        withRepo { root, git, merge ->
            initialCommit(git, root, "conflict.txt", "base")
            git.branchCreate().setName("feature").call()
            File(root, "conflict.txt").writeText("main")
            commit(git, "main", "conflict.txt")
            git.checkout().setName("feature").call()
            File(root, "conflict.txt").writeText("feature")
            commit(git, "feature", "conflict.txt")
            git.checkout().setName("master").call()
            val conflict = merge.merge(local(root), "feature") as MergeResult.Conflicted
            assertEquals(1, conflict.state.conflictedFiles.size)
            val restored = JGitMergeSupport(JGitRepositoryLoader(), GitRepositoryOperationCoordinator()).readState(local(root))
            assertEquals(MergeRepositoryState.MERGING, restored.repositoryState)
            assertTrue(merge.readConflictVersion(local(root), "conflict.txt", ConflictVersion.BASE).available)
            merge.useOurs(local(root), "conflict.txt")
            JGitRepositoryMutator(JGitRepositoryLoader()).stage(local(root), listOf("conflict.txt"))
            val ready = merge.readState(local(root))
            assertTrue(ready.canContinue)
            merge.continueMerge(local(root), CommitRequest("Resolve conflict", "Author", "author@example.com"))
            assertEquals(RepositoryState.SAFE, git.repository.repositoryState)
        }
    }

    @Test
    fun abortRestoresPreMergeTreeAndState() = runBlocking {
        withRepo { root, git, merge ->
            initialCommit(git, root, "conflict.txt", "base")
            git.branchCreate().setName("feature").call()
            File(root, "conflict.txt").writeText("main")
            commit(git, "main", "conflict.txt")
            git.checkout().setName("feature").call()
            File(root, "conflict.txt").writeText("feature")
            commit(git, "feature", "conflict.txt")
            git.checkout().setName("master").call()
            merge.merge(local(root), "feature")
            merge.abortMerge(local(root))
            assertEquals("main", File(root, "conflict.txt").readText())
            assertEquals(RepositoryState.SAFE, git.repository.repositoryState)
        }
    }

    private suspend fun withRepo(block: suspend (File, Git, JGitMergeSupport) -> Unit) {
        val root = Files.createTempDirectory("git-merge").toFile()
        val git = Git.init().setDirectory(root).call()
        git.repository.config.setString("user", null, "name", "Author")
        git.repository.config.setString("user", null, "email", "author@example.com")
        git.repository.config.save()
        val merge = JGitMergeSupport(JGitRepositoryLoader(), GitRepositoryOperationCoordinator())
        try { block(root, git, merge) } finally { git.close(); root.deleteRecursively() }
    }

    private fun local(root: File) = LocalRepository("merge-1", "owner", "repo", root.absolutePath, "https://github.com/owner/repo.git")
    private fun initialCommit(git: Git, root: File, name: String, content: String) { File(root, name).writeText(content); commit(git, "initial", name) }
    private fun commit(git: Git, message: String, path: String) { git.add().addFilepattern(path).call(); git.commit().setMessage(message).setAuthor(PersonIdent("Author", "author@example.com")).call() }
}
