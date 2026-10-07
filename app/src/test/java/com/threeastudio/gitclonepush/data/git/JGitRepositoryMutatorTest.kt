package com.threeastudio.gitclonepush.data.git

import com.threeastudio.gitclonepush.core.model.GitFileState
import com.threeastudio.gitclonepush.core.model.LocalRepository
import com.threeastudio.gitclonepush.core.model.CommitRequest
import com.threeastudio.gitclonepush.domain.repository.GitMutationErrorCategory
import com.threeastudio.gitclonepush.domain.repository.GitMutationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.treewalk.TreeWalk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class JGitRepositoryMutatorTest {
    @Test
    fun untrackedFileCanBeStagedAndUnstagedWithoutBeingDeleted() = runBlocking {
        withRepository { root, git, mutator ->
            File(root, "notes.txt").writeText("notes")
            mutator.stage(local(root), listOf("notes.txt"))
            assertEquals(GitFileState.ADDED, status(root).changes.single().indexState)
            mutator.unstage(local(root), listOf("notes.txt"))
            val change = status(root).changes.single()
            assertEquals(GitFileState.UNTRACKED, change.workTreeState)
            assertTrue(File(root, "notes.txt").exists())
            git.close()
        }
    }

    @Test
    fun deletedTrackedFileCanBeStagedAndUnstaged() = runBlocking {
        withRepository { root, git, mutator ->
            createInitialCommit(git, root, "deleted.txt" to "delete me")
            File(root, "deleted.txt").delete()
            mutator.stage(local(root), listOf("deleted.txt"))
            assertEquals(GitFileState.DELETED, status(root).changes.single().indexState)
            mutator.unstage(local(root), listOf("deleted.txt"))
            assertEquals(GitFileState.DELETED, status(root).changes.single().workTreeState)
            assertTrue(!File(root, "deleted.txt").exists())
            git.close()
        }
    }

    @Test
    fun stageAllStagesChangesButRespectsGitignore() = runBlocking {
        withRepository { root, git, mutator ->
            createInitialCommit(git, root, "tracked.txt" to "one", "deleted.txt" to "delete")
            File(root, ".gitignore").writeText("ignored.txt\n")
            File(root, "new.txt").writeText("new")
            File(root, "ignored.txt").writeText("ignored")
            File(root, "tracked.txt").writeText("two")
            File(root, "deleted.txt").delete()
            mutator.stageAll(local(root))
            val current = git.status().call()
            assertTrue("new.txt" in current.added)
            assertTrue("tracked.txt" in current.added || "tracked.txt" in current.changed)
            assertTrue("deleted.txt" in current.removed)
            assertFalse("ignored.txt" in current.added)
            git.close()
        }
    }

    @Test
    fun commitContainsOnlyStagedSnapshotWhenFileChangesAgain() = runBlocking {
        withRepository { root, git, mutator ->
            createInitialCommit(git, root, "tracked.txt" to "one")
            File(root, "tracked.txt").writeText("staged version")
            mutator.stage(local(root), listOf("tracked.txt"))
            File(root, "tracked.txt").writeText("newer working tree version")
            val result = mutator.commit(local(root), CommitRequest("Update file", "Test Author", "test@example.com"))
            assertNotNull(result.hash)
            assertEquals("newer working tree version", File(root, "tracked.txt").readText())
            val change = status(root).changes.single()
            assertEquals(GitFileState.MODIFIED, change.workTreeState)
            assertEquals("staged version", committedContent(git))
            git.close()
        }
    }

    @Test
    fun commitUsesProvidedAccountForBothAuthorAndCommitterNotOldGitConfig() = runBlocking {
        withRepository { root, git, mutator ->
            git.repository.config.setString("user", null, "name", "Old identity")
            git.repository.config.setString("user", null, "email", "old@example.com")
            git.repository.config.save()
            File(root, "account.txt").writeText("account fixture")
            mutator.stageAll(local(root))
            mutator.commit(local(root), CommitRequest("Account commit", "Alice", "123456+alice@users.noreply.github.com"))
            val commit = git.log().call().first()
            assertEquals("Alice", commit.authorIdent.name)
            assertEquals("Alice", commit.committerIdent.name)
            assertEquals("123456+alice@users.noreply.github.com", commit.authorIdent.emailAddress)
            assertEquals(commit.authorIdent.emailAddress, commit.committerIdent.emailAddress)
            git.close()
        }
    }

    @Test
    fun commitRejectsBlankMessageAndMissingAuthor() = runBlocking {
        withRepository { root, git, mutator ->
            File(root, "new.txt").writeText("new")
            mutator.stageAll(local(root))
            val blank = runCatching { mutator.commit(local(root), CommitRequest("  ", "Author", "author@example.com")) }.exceptionOrNull()
            assertEquals(GitMutationErrorCategory.COMMIT_MESSAGE_EMPTY, (blank as GitMutationException).category)
            val author = runCatching { mutator.commit(local(root), CommitRequest("message", "", "")) }.exceptionOrNull()
            assertEquals(GitMutationErrorCategory.AUTHOR_IDENTITY_MISSING, (author as GitMutationException).category)
            git.close()
        }
    }

    @Test
    fun commitRejectsWhenNothingIsStaged() = runBlocking {
        withRepository { root, git, mutator ->
            val error = runCatching { mutator.commit(local(root), CommitRequest("message", "Author", "author@example.com")) }.exceptionOrNull()
            assertEquals(GitMutationErrorCategory.NOTHING_STAGED, (error as GitMutationException).category)
            git.close()
        }
    }

    @Test
    fun unbornHeadCanBeStagedAndCommitted() = runBlocking {
        withRepository { root, git, mutator ->
            File(root, "first.txt").writeText("first")
            mutator.stageAll(local(root))
            mutator.commit(local(root), CommitRequest("Initial commit", "Author", "author@example.com"))
            assertNotNull(git.repository.resolve("HEAD"))
            assertTrue(status(root).clean)
            git.close()
        }
    }

    @Test
    fun concurrentMutationsAreSerializedByTheMutator() = runBlocking {
        withRepository { root, git, mutator ->
            File(root, "a.txt").writeText("a")
            File(root, "b.txt").writeText("b")
            listOf(async { mutator.stage(local(root), listOf("a.txt")) }, async { mutator.stage(local(root), listOf("b.txt")) }).awaitAll()
            assertEquals(2, status(root).changes.count { it.indexState == GitFileState.ADDED })
            git.close()
        }
    }

    private suspend fun withRepository(block: suspend (File, Git, JGitRepositoryMutator) -> Unit) {
        val root = Files.createTempDirectory("git-mutator").toFile()
        val git = Git.init().setDirectory(root).call()
        try {
            block(root, git, JGitRepositoryMutator(JGitRepositoryLoader()))
        } finally {
            root.deleteRecursively()
        }
    }

    private fun local(root: File) = LocalRepository("1", "owner", "repo", root.absolutePath, "https://github.com/owner/repo.git")
    private fun status(root: File) = kotlinx.coroutines.runBlocking { JGitRepositoryReader(JGitRepositoryLoader()).status(local(root)) }

    private fun createInitialCommit(git: Git, root: File, vararg files: Pair<String, String>) {
        files.forEach { (name, content) -> File(root, name).writeText(content) }
        git.add().addFilepattern(".").call()
        git.commit().setMessage("initial").setAuthor(PersonIdent("Test", "test@example.com")).call()
    }

    private fun committedContent(git: Git): String = RevWalk(git.repository).use { walk ->
        val commit = walk.parseCommit(git.repository.resolve("HEAD"))
        TreeWalk.forPath(git.repository, "tracked.txt", commit.tree).use { tree ->
            git.repository.open(tree.getObjectId(0)).bytes.toString(Charsets.UTF_8)
        }
    }
}
