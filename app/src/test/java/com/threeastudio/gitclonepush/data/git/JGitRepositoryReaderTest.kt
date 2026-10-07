package com.threeastudio.gitclonepush.data.git

import com.threeastudio.gitclonepush.core.model.GitFileState
import com.threeastudio.gitclonepush.core.model.LocalRepository
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.PersonIdent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class JGitRepositoryReaderTest {
    @Test
    fun reportsUntrackedModifiedAndDeletedFilesFromJGitStatus() = runBlocking {
        val root = Files.createTempDirectory("git-status").toFile()
        try {
            val git = Git.init().setDirectory(root).call()
            File(root, "tracked.txt").writeText("one")
            File(root, "deleted.txt").writeText("delete")
            git.add().addFilepattern(".").call()
            git.commit().setMessage("initial").setAuthor(PersonIdent("Test", "test@example.com")).call()
            File(root, "tracked.txt").writeText("two")
            File(root, "deleted.txt").delete()
            File(root, "new.txt").writeText("new")
            val changes = JGitRepositoryReader(JGitRepositoryLoader()).status(local(root)).changes.associateBy { it.path }
            assertEquals(GitFileState.MODIFIED, changes.getValue("tracked.txt").workTreeState)
            assertEquals(GitFileState.DELETED, changes.getValue("deleted.txt").workTreeState)
            assertEquals(GitFileState.UNTRACKED, changes.getValue("new.txt").workTreeState)
            git.close()
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun distinguishesStagedThenModifiedAgain() = runBlocking {
        val root = Files.createTempDirectory("git-staged-status").toFile()
        try {
            val git = Git.init().setDirectory(root).call()
            val file = File(root, "tracked.txt").apply { writeText("one") }
            git.add().addFilepattern("tracked.txt").call()
            git.commit().setMessage("initial").setAuthor(PersonIdent("Test", "test@example.com")).call()
            file.writeText("two")
            git.add().addFilepattern("tracked.txt").call()
            file.writeText("three")
            val change = JGitRepositoryReader(JGitRepositoryLoader()).status(local(root)).changes.single()
            assertEquals(GitFileState.MODIFIED, change.indexState)
            assertEquals(GitFileState.MODIFIED, change.workTreeState)
            assertTrue(change.path == "tracked.txt")
            git.close()
        } finally {
            root.deleteRecursively()
        }
    }

    private fun local(root: File) = LocalRepository("1", "owner", "repo", root.absolutePath, "https://github.com/owner/repo.git")
}
