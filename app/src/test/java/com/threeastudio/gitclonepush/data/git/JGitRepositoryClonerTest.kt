package com.threeastudio.gitclonepush.data.git

import com.threeastudio.gitclonepush.core.model.GitRepository
import com.threeastudio.gitclonepush.core.model.GitCredentials
import com.threeastudio.gitclonepush.core.model.LocalRepository
import com.threeastudio.gitclonepush.core.model.RepositoryVisibility
import com.threeastudio.gitclonepush.domain.repository.CloneErrorCategory
import com.threeastudio.gitclonepush.domain.repository.CloneOperationException
import com.threeastudio.gitclonepush.domain.repository.CloneRepositoryRequest
import com.threeastudio.gitclonepush.domain.repository.GitCredentialProvider
import com.threeastudio.gitclonepush.domain.repository.LocalRepositoryStore
import com.threeastudio.gitclonepush.domain.repository.LocalRepositoryValidator
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.PersonIdent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class JGitRepositoryClonerTest {
    @Test
    fun publicCloneCompletesWithRealProgressAndPersistsSafeMetadata() = runBlocking {
        val root = Files.createTempDirectory("git-clone-test").toFile()
        try {
            val source = createSourceRepository(root)
            val destination = File(root, "destination")
            val store = RecordingStore(destination)
            val credentials = RecordingCredentials()
            source.close()
            val request = request(root, destination)
            val events = JGitRepositoryCloner(store, credentials, AlwaysValidValidator()).clone(request).toList()

            assertTrue(events.any { it is com.threeastudio.gitclonepush.core.model.CloneProgress.Receiving })
            assertTrue(events.last() is com.threeastudio.gitclonepush.core.model.CloneProgress.Completed)
            assertEquals("x-access-token", credentials.lastCredentials?.username)
            assertEquals("fake-token", credentials.lastCredentials?.password)
            assertEquals(1, store.saved.size)
            assertFalse(File(destination, ".git/config").readText().contains("fake-token"))
            assertTrue(File(destination, "README.md").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun existingValidRepositoryIsReconciledWithoutSecondClone() = runBlocking {
        val root = Files.createTempDirectory("git-existing-test").toFile()
        try {
            val destination = File(root, "destination")
            createSourceRepository(root, destination)
            val credentials = RecordingCredentials()
            val store = RecordingStore(destination)
            val events = JGitRepositoryCloner(store, credentials, AlwaysValidValidator()).clone(request(root, destination)).toList()

            assertTrue(events.last() is com.threeastudio.gitclonepush.core.model.CloneProgress.Completed)
            assertEquals(0, credentials.calls)
            assertEquals(1, store.saved.size)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun unrelatedExistingDirectoryIsNotDeleted() = runBlocking {
        val root = Files.createTempDirectory("git-conflict-test").toFile()
        try {
            val destination = File(root, "destination").apply { mkdirs() }
            val unrelated = File(destination, "user-file.txt").apply { writeText("keep") }
            val error = runCatching {
                JGitRepositoryCloner(RecordingStore(destination), RecordingCredentials(), AlwaysInvalidValidator())
                    .clone(request(root, destination)).toList()
            }.exceptionOrNull()

            assertEquals(CloneErrorCategory.DESTINATION_CONFLICT, (error as CloneOperationException).category)
            assertTrue(unrelated.exists())
            assertEquals("keep", unrelated.readText())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun failedApplicationOwnedCloneIsCleaned() = runBlocking {
        val root = Files.createTempDirectory("git-cleanup-test").toFile()
        try {
            val destination = File(root, "destination")
            val error = runCatching {
                JGitRepositoryCloner(RecordingStore(destination), FailingCredentials(), AlwaysValidValidator())
                    .clone(request(root, destination)).toList()
            }.exceptionOrNull()

            assertEquals(CloneErrorCategory.AUTHENTICATION_REQUIRED, (error as CloneOperationException).category)
            assertFalse(destination.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun validatorAcceptsCleanGithubOriginAndRejectsCredentialedOrigin() = runBlocking {
        val root = Files.createTempDirectory("git-validator-test").toFile()
        try {
            val clean = createSourceRepository(root, File(root, "clean"))
            clean.repository.config.setString("remote", "origin", "url", "https://github.com/alice/demo.git")
            clean.repository.config.save()
            assertTrue(JGitLocalRepositoryValidator().validate(local(root, "clean")))

            clean.repository.config.setString("remote", "origin", "url", "https://fake-token@github.com/alice/demo.git")
            clean.repository.config.save()
            assertFalse(JGitLocalRepositoryValidator().validate(local(root, "clean")))
            clean.close()
        } finally {
            root.deleteRecursively()
        }
    }

    private fun createSourceRepository(root: File, directory: File = File(root, "source")): Git {
        val git = Git.init().setDirectory(directory).call()
        File(directory, "README.md").writeText("clone fixture")
        git.add().addFilepattern("README.md").call()
        git.commit().setMessage("initial").setAuthor(PersonIdent("Test", "test@example.com")).call()
        return git
    }

    private fun request(root: File, destination: File) = CloneRepositoryRequest(
        GitRepository("1", "demo", "alice", RepositoryVisibility.PUBLIC, "Kotlin", "main", "now", "${File(root, "source").toURI()}"),
        destination.absolutePath
    )

    private fun local(root: File, name: String) = LocalRepository("1", "alice", "demo", File(root, name).absolutePath, "https://github.com/alice/demo.git")

    private class RecordingStore(private val destination: File) : LocalRepositoryStore {
        val saved = mutableListOf<LocalRepository>()
        override fun repositoryDirectory(repositoryId: String) = destination
        override suspend fun listRepositories() = saved.toList()
        override suspend fun save(repository: LocalRepository) { saved += repository }
    }

    private class RecordingCredentials : GitCredentialProvider {
        var calls = 0
        var lastCredentials: GitCredentials? = null
        override suspend fun credentialsFor(remote: com.threeastudio.gitclonepush.core.model.GitRemote): GitCredentials {
            calls++
            return GitCredentials("x-access-token", "fake-token").also { lastCredentials = it }
        }
    }

    private class FailingCredentials : GitCredentialProvider {
        override suspend fun credentialsFor(remote: com.threeastudio.gitclonepush.core.model.GitRemote): GitCredentials =
            throw CloneOperationException(CloneErrorCategory.AUTHENTICATION_REQUIRED)
    }

    private class AlwaysValidValidator : LocalRepositoryValidator {
        override suspend fun validate(repository: LocalRepository) = repository.directoryPath.isNotBlank()
    }

    private class AlwaysInvalidValidator : LocalRepositoryValidator {
        override suspend fun validate(repository: LocalRepository) = false
    }
}
