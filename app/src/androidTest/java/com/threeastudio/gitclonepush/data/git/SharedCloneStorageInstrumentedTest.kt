package com.threeastudio.gitclonepush.data.git

import android.content.ContextWrapper
import android.content.pm.ProviderInfo
import android.os.Environment
import android.provider.DocumentsContract
import android.system.Os
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.threeastudio.gitclonepush.core.model.*
import com.threeastudio.gitclonepush.data.documents.RepositoryDocumentsProvider
import com.threeastudio.gitclonepush.data.documents.RepositoryPathSecurity
import com.threeastudio.gitclonepush.data.storage.AndroidCloneDestinationSelector
import com.threeastudio.gitclonepush.data.storage.SharedCloneStorage
import com.threeastudio.gitclonepush.domain.repository.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.toList
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.PersonIdent
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SharedCloneStorageInstrumentedTest {
    @Test
    fun chosenFolderCloneExternalEditsCommitRestartProviderAndRemoval() = runBlocking {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(SharedCloneStorage.hasAccess(base))
        @Suppress("DEPRECATION")
        val parent = File(Environment.getExternalStorageDirectory().canonicalFile, "Documents/GitClonePushTest-${UUID.randomUUID()}")
        val registry = File(base.cacheDir, "clone-registry-${UUID.randomUUID()}")
        val context = object : ContextWrapper(base) { override fun getFilesDir(): File = registry }
        assertTrue(parent.mkdirs())
        assertTrue(registry.mkdirs())
        try {
            val store = AndroidLocalRepositoryStore(context)
            val remote = GitRepository("987654321", "demo", "fixture", RepositoryVisibility.PUBLIC, null, "main", "now", "https://github.com/fixture/demo.git")
            val tree = DocumentsContract.buildTreeDocumentUri(SharedCloneStorage.EXTERNAL_DOCUMENTS_AUTHORITY, "primary:Documents/${parent.name}")
            val target = AndroidCloneDestinationSelector(context, store).select(remote, tree.toString())
            assertEquals(File(parent, "demo").canonicalPath, target)
            assertFalse(File(target).exists())

            // Local deterministic transport fixture exercises the production cloner without GitHub.
            val source = File(registry, "source")
            Git.init().setDirectory(source).call().use { git ->
                File(source, "tracked.txt").writeText("initial\n")
                File(source, "deleted.txt").writeText("initial\n")
                git.add().addFilepattern(".").call()
                git.commit().setMessage("initial").setAuthor(PersonIdent("Fixture", "fixture@example.com")).call()
            }
            val fixtureStore = object : LocalRepositoryStore {
                override fun repositoryDirectory(repositoryId: String) = store.repositoryDirectory(repositoryId)
                override suspend fun listRepositories() = store.listRepositories()
                override suspend fun save(repository: LocalRepository) {
                    Git.open(File(repository.directoryPath)).use { git ->
                        git.repository.config.setString("remote", "origin", "url", remote.cloneUrl)
                        git.repository.config.save()
                    }
                    store.save(repository.copy(remoteUrl = remote.cloneUrl))
                }
            }
            val events = JGitRepositoryCloner(
                fixtureStore, object : GitCredentialProvider {
                    override suspend fun credentialsFor(remote: GitRemote) = GitCredentials("fixture", "fixture-password")
                }, LocalRepositoryValidator { true }
            ).clone(CloneRepositoryRequest(remote.copy(cloneUrl = source.toURI().toString()), target)).toList()
            assertTrue(events.last() is CloneProgress.Completed)
            assertTrue(File(target, "tracked.txt").exists())

            // Fresh store simulates process restart: resolve from private persisted registration.
            val freshStore = AndroidLocalRepositoryStore(context)
            val local = freshStore.listRepositories().single()
            assertEquals(target, freshStore.repositoryDirectory(remote.id).canonicalPath)
            assertEquals(tree.toString(), local.parentTreeUri)
            assertTrue(JGitLocalRepositoryValidator().validate(local))

            // Direct disk edits and provider edits both affect this same JGit work tree.
            File(target, "external-added.txt").writeText("added externally\n")
            File(target, "tracked.txt").writeText("modified externally\n")
            assertTrue(File(target, "deleted.txt").delete())
            val provider = RepositoryDocumentsProvider()
            provider.attachInfo(context, ProviderInfo().apply {
                authority = "${base.packageName}.documents"
                exported = true
                grantUriPermissions = true
                readPermission = "android.permission.MANAGE_DOCUMENTS"
                writePermission = "android.permission.MANAGE_DOCUMENTS"
            })
            val rootId = "repo:${remote.id}"
            provider.queryChildDocuments(rootId, null, sortOrder = null).use { cursor ->
                val names = mutableListOf<String>()
                while (cursor.moveToNext()) names += cursor.getString(cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME))
                assertFalse(names.contains(".git"))
            }
            assertTrue(runCatching { provider.queryDocument("$rootId:.git/config", null) }.isFailure)
            assertTrue(runCatching { provider.queryDocument("$rootId:../outside", null) }.isFailure)
            val created = provider.createDocument(rootId, "text/plain", "from-files.txt")
            assertTrue(provider.isChildDocument(rootId, created))
            val folder = provider.createDocument(rootId, DocumentsContract.Document.MIME_TYPE_DIR, "nested")
            val nestedFile = provider.createDocument(folder, "text/plain", "child.txt")
            assertEquals("$rootId:nested/child.txt", nestedFile)
            provider.queryDocument(nestedFile, null).use { assertTrue(it.moveToFirst()) }
            assertEquals(listOf("root", rootId, folder, nestedFile), provider.findDocumentPath(null, nestedFile).path)
            assertEquals(listOf(folder, nestedFile), provider.findDocumentPath(folder, nestedFile).path)
            assertTrue(runCatching { provider.findDocumentPath(null, "$rootId:.git/config") }.isFailure)
            assertTrue(runCatching { provider.findDocumentPath("repo:999", nestedFile) }.isFailure)
            val renamed = provider.renameDocument(nestedFile, "renamed.txt")
            assertTrue(File(target, "nested/renamed.txt").exists())
            provider.deleteDocument(renamed)
            provider.deleteDocument(folder)
            provider.openDocument(created, "wt", null).use { descriptor ->
                android.os.ParcelFileDescriptor.AutoCloseOutputStream(descriptor).use { it.write("via Files\n".toByteArray()) }
            }

            val reader = JGitRepositoryReader(JGitRepositoryLoader(), Dispatchers.IO)
            val changes = reader.status(local).changes.associateBy { it.path }
            assertEquals(GitFileState.UNTRACKED, changes["external-added.txt"]?.workTreeState)
            assertEquals(GitFileState.UNTRACKED, changes["from-files.txt"]?.workTreeState)
            assertEquals(GitFileState.MODIFIED, changes["tracked.txt"]?.workTreeState)
            assertEquals(GitFileState.DELETED, changes["deleted.txt"]?.workTreeState)
            val mutator = JGitRepositoryMutator(JGitRepositoryLoader())
            mutator.stageAll(local)
            mutator.commit(local, CommitRequest("external folder changes", "Fixture", "fixture@example.com"))
            assertTrue(reader.status(local).clean)
            val config = File(target, ".git/config").readText()
            assertFalse(config.contains("fixture-password"))
            Git.open(File(target)).use { assertEquals(remote.cloneUrl, it.repository.config.getString("remote", "origin", "url")) }

            val sibling = File(parent, "keep.txt").apply { writeText("keep") }
            freshStore.remove(remote.id)
            assertFalse(File(target).exists())
            assertEquals("keep", sibling.readText())
            assertTrue(AndroidLocalRepositoryStore(context).listRepositories().isEmpty())
        } finally {
            deleteOwnedClone(parent.canonicalFile)
            deleteOwnedClone(registry.canonicalFile)
        }
    }

    @Test
    fun unsupportedProviderAndExistingFolderAreRejectedWithoutOverwriting() = runBlocking {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(SharedCloneStorage.hasAccess(base))
        val registry = File(base.cacheDir, "destination-registry-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(base) { override fun getFilesDir() = registry }
        try {
            val repo = GitRepository("987654322", "demo", "fixture", RepositoryVisibility.PUBLIC, null, "main", "now", "https://github.com/fixture/demo.git")
            val selector = AndroidCloneDestinationSelector(context, AndroidLocalRepositoryStore(context))
            val error = runCatching { selector.select(repo, "content://cloud.example/tree/folder") }.exceptionOrNull()
            assertEquals(CloneDestinationError.UNSUPPORTED_LOCATION, (error as CloneDestinationException).category)
            val existing = File(registry, "demo").apply { mkdir() }
            val file = File(existing, "keep.txt").apply { writeText("keep") }
            val store = AndroidLocalRepositoryStore(context)
            assertTrue(runCatching { store.selectDestination(repo, registry.canonicalFile, "fixture-tree") }.exceptionOrNull() is CloneDestinationException)
            assertEquals("keep", file.readText())
        } finally { deleteOwnedClone(registry.canonicalFile) }
    }

    @Test
    fun concurrentRepositoriesCannotReserveTheSameDestination() = runBlocking {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val registry = File(base.cacheDir, "reservation-${UUID.randomUUID()}").canonicalFile.apply { mkdirs() }
        val context = object : ContextWrapper(base) { override fun getFilesDir() = registry }
        try {
            val store = AndroidLocalRepositoryStore(context)
            val repo = GitRepository("987654323", "same-name", "fixture", RepositoryVisibility.PUBLIC, null, "main", "now", "https://github.com/fixture/same-name.git")
            val results = listOf(repo, repo.copy(id = "987654324", owner = "another")).map { repository ->
                async(Dispatchers.IO) { runCatching { store.selectDestination(repository, registry, "fixture-tree") } }
            }.awaitAll()
            assertEquals(1, results.count { it.isSuccess })
            assertEquals(CloneDestinationError.DESTINATION_EXISTS, (results.single { it.isFailure }.exceptionOrNull() as CloneDestinationException).category)
            assertFalse(File(registry, repo.name).exists())
        } finally { deleteOwnedClone(registry) }
    }

    @Test
    fun symlinkEscapesAreBlockedAndCloneDeletionNeverFollowsLinks() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(base.cacheDir, "symlink-clone-${UUID.randomUUID()}").canonicalFile.apply { mkdirs() }
        try {
            val clone = File(root, "clone").apply { mkdir() }
            val outside = File(root, "outside").apply { mkdir() }
            val keep = File(outside, "keep.txt").apply { writeText("keep") }
            val gitDirectory = File(clone, ".git").apply { mkdir() }
            File(gitDirectory, "config").writeText("private fixture")
            Os.symlink(outside.absolutePath, File(clone, "escape").absolutePath)
            Os.symlink(gitDirectory.absolutePath, File(clone, "hidden-git").absolutePath)
            val security = RepositoryPathSecurity()
            assertNull(security.resolve(clone, "escape/keep.txt"))
            assertNull(security.resolve(clone, "hidden-git/config"))
            assertNull(security.resolve(clone, ".git/config"))
            deleteOwnedClone(clone)
            assertFalse(clone.exists())
            assertEquals("keep", keep.readText())
        } finally { deleteOwnedClone(root) }
    }
}
