package com.threeastudio.gitclonepush.data.git

import com.threeastudio.gitclonepush.core.model.LocalRepository
import com.threeastudio.gitclonepush.core.model.RepositoryFileContent
import com.threeastudio.gitclonepush.core.model.RepositoryFileEntry
import com.threeastudio.gitclonepush.domain.repository.LocalRepositoryStore
import com.threeastudio.gitclonepush.domain.repository.LocalRepositoryValidator
import com.threeastudio.gitclonepush.domain.repository.RepositoryFilesErrorCategory
import com.threeastudio.gitclonepush.domain.repository.RepositoryFilesException
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class JGitRepositoryFileReaderTest {
    @Test
    fun listsRootAndNestedDirectoriesAsDirectChildrenInStableOrder() = runBlocking {
        withFixture { root, reader ->
            File(root, "src/main/java/App.kt").apply { parentFile!!.mkdirs(); writeText("package demo") }
            File(root, "src/README.md").writeText("readme")
            val rootEntries = reader.listDirectory("42", "")
            assertEquals(listOf("src"), rootEntries.map { it.name })
            val nested = reader.listDirectory("42", "src")
            assertEquals(listOf("main", "README.md"), nested.map { it.name })
            assertTrue(nested.first() is RepositoryFileEntry.Directory)
        }
    }

    @Test
    fun hidesGitAndRejectsTraversalAndAbsolutePaths() = runBlocking {
        withFixture { root, reader ->
            File(root, ".git/config").writeText("private")
            assertTrue(reader.listDirectory("42", "").none { it.name == ".git" })
            assertCategory(RepositoryFilesErrorCategory.PATH_FORBIDDEN) { reader.readFile("42", ".git/config") }
            assertCategory(RepositoryFilesErrorCategory.PATH_FORBIDDEN) { reader.listDirectory("42", "../") }
            assertCategory(RepositoryFilesErrorCategory.PATH_FORBIDDEN) { reader.readFile("42", "/etc/passwd") }
        }
    }

    @Test
    fun previewsTextAndBoundsLargeContent() = runBlocking {
        withFixture { root, reader ->
            File(root, "README.md").writeText("hello")
            File(root, "large.txt").writeText("x".repeat(300 * 1024))
            assertEquals(RepositoryFileContent.Text("hello", false), reader.readFile("42", "README.md"))
            val large = reader.readFile("42", "large.txt") as RepositoryFileContent.Text
            assertTrue(large.truncated)
            assertTrue(large.text.length <= 256 * 1024)
        }
    }

    @Test
    fun binaryContentIsNotDecodedAsText() = runBlocking {
        withFixture { root, reader ->
            File(root, "image.bin").writeBytes(byteArrayOf(0, 1, 2, 3))
            assertTrue(reader.readFile("42", "image.bin") is RepositoryFileContent.Binary)
        }
    }

    private suspend fun withFixture(block: suspend (File, JGitRepositoryFileReader) -> Unit) {
        val root = Files.createTempDirectory("repository-files").toFile()
        val git = Git.init().setDirectory(root).call()
        val repository = LocalRepository("42", "owner", "repo", root.canonicalPath, "https://github.com/owner/repo.git")
        val store = object : LocalRepositoryStore {
            override fun repositoryDirectory(repositoryId: String): File = root
            override suspend fun listRepositories(): List<LocalRepository> = listOf(repository)
            override suspend fun save(repository: LocalRepository) = Unit
        }
        val validator = LocalRepositoryValidator { true }
        try { block(root, JGitRepositoryFileReader(store, validator)) }
        finally { git.close(); root.deleteRecursively() }
    }

    private suspend fun assertCategory(category: RepositoryFilesErrorCategory, block: suspend () -> Unit) {
        val error = runCatching { block() }.exceptionOrNull() as RepositoryFilesException
        assertEquals(category, error.category)
    }
}
