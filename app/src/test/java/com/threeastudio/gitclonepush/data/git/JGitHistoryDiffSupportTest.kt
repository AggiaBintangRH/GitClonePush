package com.threeastudio.gitclonepush.data.git

import com.threeastudio.gitclonepush.core.model.*
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.PersonIdent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class JGitHistoryDiffSupportTest {
    @Test
    fun historyIsNewestFirstAndPagesDoNotDuplicate() = runBlocking {
        withRepo { root, git, reader ->
            repeat(5) { index -> File(root, "file$index.txt").writeText(index.toString()); commit(git, "commit-$index", "file$index.txt") }
            val first = reader.loadPage(local(root), 0, 2)
            val second = reader.loadPage(local(root), 2, 2)
            assertEquals(listOf("commit-4", "commit-3"), first.commits.map { it.message.trim() })
            assertEquals(listOf("commit-2", "commit-1"), second.commits.map { it.message.trim() })
            assertTrue(first.hasMore)
            assertTrue(first.commits.map { it.id }.intersect(second.commits.map { it.id }).isEmpty())
        }
    }

    @Test
    fun rootAndNormalCommitDetailsAndDiffsAreReadable() = runBlocking {
        withRepo { root, git, reader ->
            File(root, "notes.txt").writeText("one\n")
            commit(git, "root commit", "notes.txt")
            val rootId = git.repository.resolve("HEAD")!!.name
            val rootDetail = reader.loadDetail(local(root), rootId)
            assertTrue(rootDetail.parents.isEmpty())
            assertEquals(GitChangeType.ADD, rootDetail.changedFiles.single().changeType)
            File(root, "notes.txt").writeText("one\ntwo\n")
            commit(git, "modify notes", "notes.txt")
            val detail = reader.loadDetail(local(root), git.repository.resolve("HEAD")!!.name)
            assertEquals(1, detail.parents.size)
            val diff = reader.read(local(root), DiffRequest(DiffScope.COMMIT, detail.id))
            assertTrue(diff.files.single().hunks.flatMap { it.lines }.any { it.type == DiffLineType.ADDED && it.text == "two" })
        }
    }

    @Test
    fun stagedAndUnstagedDiffsRemainIndependent() = runBlocking {
        withRepo { root, git, reader ->
            val file = File(root, "tracked.txt").apply { writeText("one\n") }
            commit(git, "initial", "tracked.txt")
            file.writeText("one\nstaged\n")
            git.add().addFilepattern("tracked.txt").call()
            file.writeText("one\nstaged\nunstaged\n")
            val staged = reader.read(local(root), DiffRequest(DiffScope.STAGED, path = "tracked.txt"))
            val unstaged = reader.read(local(root), DiffRequest(DiffScope.UNSTAGED, path = "tracked.txt"))
            assertTrue(staged.files.flatMap { it.hunks }.flatMap { it.lines }.any { it.text == "staged" && it.type == DiffLineType.ADDED })
            assertTrue(unstaged.files.flatMap { it.hunks }.flatMap { it.lines }.any { it.text == "unstaged" && it.type == DiffLineType.ADDED })
        }
    }

    @Test
    fun untrackedDeletedBinaryAndLargeFilesAreSafe() = runBlocking {
        withRepo { root, git, reader ->
            File(root, "tracked.txt").writeText("old\n")
            File(root, "binary.bin").writeBytes(byteArrayOf(0, 1, 2))
            commit(git, "initial", "tracked.txt", "binary.bin")
            File(root, "new.txt").writeText("new\n")
            File(root, "tracked.txt").delete()
            File(root, "binary.bin").writeBytes(byteArrayOf(3, 4, 5))
            File(root, "large.txt").writeText("x\n".repeat(200_000))
            val result = reader.read(local(root), DiffRequest(DiffScope.UNSTAGED))
            assertTrue("files=${result.files.map { it.oldPath to it.newPath }}" , result.files.any { it.newPath == "new.txt" && it.changeType == GitChangeType.ADD })
            assertTrue("files=${result.files.map { it.oldPath to it.newPath }}" , result.files.any { it.oldPath == "tracked.txt" && it.changeType == GitChangeType.DELETE })
            assertTrue("files=${result.files.map { it.oldPath to it.newPath }}" , result.files.any { it.isBinary })
            assertTrue("files=${result.files.map { it.oldPath to it.newPath }}" , result.files.any { it.truncated })
            assertFalse(result.files.any { it.hunks.flatMap { h -> h.lines }.any { line -> line.text.contains("\u0000") } })
        }
    }

    @Test
    fun mergeCommitPreservesParentMetadata() = runBlocking {
        withRepo { root, git, reader ->
            File(root, "base.txt").writeText("base")
            commit(git, "base", "base.txt")
            git.branchCreate().setName("feature").call()
            File(root, "main.txt").writeText("main")
            commit(git, "main", "main.txt")
            git.checkout().setName("feature").call()
            File(root, "feature.txt").writeText("feature")
            commit(git, "feature", "feature.txt")
            git.checkout().setName("master").call()
            git.merge().include(git.repository.findRef("refs/heads/feature")).call()
            val detail = reader.loadDetail(local(root), git.repository.resolve("HEAD")!!.name)
            assertEquals(2, detail.parents.size)
        }
    }

    private suspend fun withRepo(block: suspend (File, Git, JGitHistoryDiffSupport) -> Unit) {
        val root = Files.createTempDirectory("git-history").toFile()
        val git = Git.init().setDirectory(root).call()
        git.repository.config.setString("user", null, "name", "Author")
        git.repository.config.setString("user", null, "email", "author@example.com")
        git.repository.config.save()
        val reader = JGitHistoryDiffSupport(JGitRepositoryLoader())
        try { block(root, git, reader) } finally { git.close(); root.deleteRecursively() }
    }

    private fun local(root: File) = LocalRepository("history-1", "owner", "repo", root.absolutePath, "https://github.com/owner/repo.git")
    private fun commit(git: Git, message: String, vararg paths: String) { paths.forEach { git.add().addFilepattern(it).call() }; git.commit().setMessage(message).setAuthor(PersonIdent("Author", "author@example.com")).call() }
}
