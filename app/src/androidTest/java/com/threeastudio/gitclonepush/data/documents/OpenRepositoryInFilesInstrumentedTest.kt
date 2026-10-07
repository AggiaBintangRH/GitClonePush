package com.threeastudio.gitclonepush.data.documents

import android.accessibilityservice.AccessibilityService
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.Looper
import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.core.content.IntentCompat
import com.threeastudio.gitclonepush.core.model.GitRepository
import com.threeastudio.gitclonepush.core.model.LocalRepository
import com.threeastudio.gitclonepush.core.model.RepositoryVisibility
import com.threeastudio.gitclonepush.data.git.AndroidLocalRepositoryStore
import com.threeastudio.gitclonepush.data.git.JGitLocalRepositoryValidator
import com.threeastudio.gitclonepush.data.git.deleteOwnedClone
import com.threeastudio.gitclonepush.data.storage.AndroidCloneDestinationSelector
import com.threeastudio.gitclonepush.data.storage.SharedCloneStorage
import com.threeastudio.gitclonepush.domain.repository.LocalRepositoryStore
import com.threeastudio.gitclonepush.domain.repository.LocalRepositoryValidator
import com.threeastudio.gitclonepush.domain.repository.OpenInFilesErrorCategory
import com.threeastudio.gitclonepush.domain.repository.OpenInFilesException
import com.threeastudio.gitclonepush.domain.repository.RepositoryDocumentsUriProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.eclipse.jgit.api.Git
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class OpenRepositoryInFilesInstrumentedTest {
    @Test
    fun restoredSharedCloneTargetsSavedRepositoryFolderAndResolvesOffMain() = runBlocking {
        withRepository { fixture ->
            var validated = false
            val provider = AndroidRepositoryDocumentsUriProvider(
                fixture.context, AndroidLocalRepositoryStore(fixture.context),
                LocalRepositoryValidator {
                    assertNotEquals(Looper.getMainLooper(), Looper.myLooper())
                    validated = true
                    JGitLocalRepositoryValidator().validate(it)
                }
            )
            val uri = provider.repositoryRootUri(fixture.repository.id)
            assertTrue(validated)
            assertEquals("content", uri.scheme)
            assertEquals(SharedCloneStorage.EXTERNAL_DOCUMENTS_AUTHORITY, uri.authority)
            assertEquals("primary:Documents/${fixture.parent.name}/demo", DocumentsContract.getDocumentId(uri))
            assertFalse(DocumentsContract.isTreeUri(uri))
            assertFalse(uri.toString().contains(fixture.repository.directoryPath))
        }
    }

    @Test
    fun legacyInternalCloneKeepsItsProtectedProviderUri() = runBlocking {
        withRepository(shared = false) { fixture ->
            val uri = fixture.uriProvider().repositoryRootUri(fixture.repository.id)
            assertEquals("${fixture.context.packageName}.documents", uri.authority)
            assertEquals("repo:${fixture.repository.id}", DocumentsContract.getDocumentId(uri))
            assertTrue(DocumentsContract.isTreeUri(uri))
        }
    }

    @Test
    fun mismatchedSavedParentCannotRedirectFilesToAnotherDirectory() = runBlocking {
        withRepository { fixture ->
            val invalid = fixture.repository.copy(parentTreeUri = DocumentsContract.buildTreeDocumentUri(
                SharedCloneStorage.EXTERNAL_DOCUMENTS_AUTHORITY, "primary:Download"
            ).toString())
            val store = object : LocalRepositoryStore {
                override fun repositoryDirectory(repositoryId: String) = File(invalid.directoryPath)
                override suspend fun listRepositories() = listOf(invalid)
                override suspend fun save(repository: LocalRepository) = error("not used")
            }
            val provider = AndroidRepositoryDocumentsUriProvider(fixture.context, store, JGitLocalRepositoryValidator())
            val error = runCatching { provider.repositoryRootUri(invalid.id) }.exceptionOrNull()
            assertEquals(OpenInFilesErrorCategory.DOCUMENT_UNAVAILABLE, (error as OpenInFilesException).category)
        }
    }

    @Test
    fun directoryViewerGetsExactSavedUriWithoutInvalidExternalPermissionGrants() = runBlocking {
        withRepository { fixture ->
            val capturing = CapturingContext(fixture.context)
            val expected = fixture.uriProvider().repositoryRootUri(fixture.repository.id)
            assertTrue(AndroidOpenRepositoryInFiles(capturing, fixture.uriProvider()).open(fixture.repository.id).isSuccess)
            val intent = capturing.intents.single()
            assertEquals(Intent.ACTION_VIEW, intent.action)
            assertEquals(expected, intent.data)
            assertEquals(DocumentsContract.Document.MIME_TYPE_DIR, intent.type)
            assertEquals(0, intent.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION))
            assertNull(intent.clipData)
        }
    }

    @Test
    fun fallbackKeepsExactRepositoryAsInitialFolderNotDownloads() = runBlocking {
        withRepository { fixture ->
            val capturing = CapturingContext(fixture.context, rejectViewer = true)
            assertTrue(AndroidOpenRepositoryInFiles(capturing, fixture.uriProvider()).open(fixture.repository.id).isSuccess)
            assertEquals(2, capturing.intents.size)
            val fallback = capturing.intents.last()
            assertEquals(Intent.ACTION_OPEN_DOCUMENT_TREE, fallback.action)
            assertEquals(capturing.intents.first().data, IntentCompat.getParcelableExtra(fallback, DocumentsContract.EXTRA_INITIAL_URI, Uri::class.java))
        }
    }

    @Test
    fun unavailableRepositoryShowsControlledFailureWithoutOpeningDefaultFiles() = runBlocking {
        withRepository { fixture ->
            val capturing = CapturingContext(fixture.context)
            val result = AndroidOpenRepositoryInFiles(capturing, fixture.uriProvider()).open("999999999")
            assertEquals(OpenInFilesErrorCategory.REPOSITORY_NOT_FOUND, (result.exceptionOrNull() as OpenInFilesException).category)
            assertTrue(capturing.intents.isEmpty())
        }
    }

    @Test
    fun cancellationIsNotConvertedIntoAnOpenFilesError() = runBlocking {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val capturing = CapturingContext(base)
        val provider = object : RepositoryDocumentsUriProvider {
            override suspend fun repositoryRootUri(repositoryId: String): android.net.Uri = throw CancellationException("fixture cancellation")
        }
        val error = runCatching { AndroidOpenRepositoryInFiles(capturing, provider).open("1") }.exceptionOrNull()
        assertTrue(error is CancellationException)
        assertTrue(capturing.intents.isEmpty())
    }

    @Test
    fun systemFilesActuallyDisplaysTheSavedRepositoryContents() = runBlocking {
        withRepository { fixture ->
            val marker = "git-files-marker-${UUID.randomUUID()}.txt"
            withContext(Dispatchers.IO) { File(fixture.repository.directoryPath, marker).writeText("fixture") }
            val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
            try {
                AndroidOpenRepositoryInFiles(fixture.context, fixture.uriProvider()).open(fixture.repository.id).getOrThrow()
                withTimeout(10_000) {
                    while (automation.rootInActiveWindow?.findAccessibilityNodeInfosByText(marker).isNullOrEmpty()) delay(100)
                }
            } finally {
                automation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            }
        }
    }

    @Test
    fun systemFilesCanNavigateDirectlyToLegacyInternalClone() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = AndroidLocalRepositoryStore(context)
        val id = "99" + UUID.randomUUID().toString().filter { it.isDigit() }
        val directory = store.repositoryDirectory(id).canonicalFile
        check(!directory.exists() && store.listRepositories().none { it.id == id })
        var created = false
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        try {
            withContext(Dispatchers.IO) {
                check(directory.parentFile?.let { it.isDirectory || it.mkdirs() } == true)
                check(directory.mkdir())
                created = true
                Git.init().setDirectory(directory).call().use { git ->
                    git.repository.config.setString("remote", "origin", "url", "https://github.com/fixture/legacy.git")
                    git.repository.config.save()
                }
            }
            store.save(LocalRepository(id, "fixture", "LegacyFilesTest", directory.path, "https://github.com/fixture/legacy.git"))
            val marker = "legacy-files-marker-${UUID.randomUUID()}.txt"
            withContext(Dispatchers.IO) { File(directory, marker).writeText("fixture") }
            val provider = AndroidRepositoryDocumentsUriProvider(context, store, JGitLocalRepositoryValidator())
            AndroidOpenRepositoryInFiles(context, provider).open(id).getOrThrow()
            withTimeout(10_000) {
                while (automation.rootInActiveWindow?.findAccessibilityNodeInfosByText(marker).isNullOrEmpty()) delay(100)
            }
        } finally {
            automation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            if (created) store.remove(id)
        }
    }

    private class CapturingContext(base: Context, private val rejectViewer: Boolean = false) : ContextWrapper(base) {
        val intents = mutableListOf<Intent>()
        override fun startActivity(intent: Intent) {
            intents += Intent(intent)
            if (rejectViewer && intent.action == Intent.ACTION_VIEW) throw ActivityNotFoundException()
        }
    }

    private data class Fixture(val context: Context, val parent: File, val store: AndroidLocalRepositoryStore, val repository: LocalRepository) {
        fun uriProvider() = AndroidRepositoryDocumentsUriProvider(context, store, JGitLocalRepositoryValidator())
    }

    private suspend fun withRepository(shared: Boolean = true, block: suspend (Fixture) -> Unit) {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        if (shared) assumeTrue(SharedCloneStorage.hasAccess(base))
        val registry = File(base.cacheDir, "files-registry-${UUID.randomUUID()}").canonicalFile
        @Suppress("DEPRECATION")
        val parent = if (shared) File(Environment.getExternalStorageDirectory().canonicalFile, "Documents/GitFilesTest-${UUID.randomUUID()}") else File(registry, "unused-parent")
        val context = object : ContextWrapper(base) { override fun getFilesDir() = registry }
        try {
            withContext(Dispatchers.IO) {
                check(registry.mkdirs())
                if (shared) check(parent.mkdirs())
            }
            val store = AndroidLocalRepositoryStore(context)
            val remote = GitRepository("987650001", "demo", "fixture", RepositoryVisibility.PUBLIC, null, "main", "now", "https://github.com/fixture/demo.git")
            val directory = if (shared) {
                val tree = DocumentsContract.buildTreeDocumentUri(SharedCloneStorage.EXTERNAL_DOCUMENTS_AUTHORITY, "primary:Documents/${parent.name}")
                File(AndroidCloneDestinationSelector(context, store).select(remote, tree.toString()))
            } else store.repositoryDirectory(remote.id)
            withContext(Dispatchers.IO) {
                Git.init().setDirectory(directory).call().use { git ->
                    git.repository.config.setString("remote", "origin", "url", remote.cloneUrl)
                    git.repository.config.save()
                }
            }
            store.save(LocalRepository(remote.id, remote.owner, remote.name, directory.canonicalPath, remote.cloneUrl))
            block(Fixture(context, parent, store, store.listRepositories().single()))
        } finally {
            withContext(Dispatchers.IO) {
                if (shared) deleteOwnedClone(parent)
                deleteOwnedClone(registry)
            }
        }
    }
}
