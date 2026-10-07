package com.threeastudio.gitclonepush.data.git

import android.content.Context
import com.threeastudio.gitclonepush.core.model.GitRepository
import com.threeastudio.gitclonepush.core.model.LocalRepository
import com.threeastudio.gitclonepush.domain.repository.CloneDestinationError
import com.threeastudio.gitclonepush.domain.repository.CloneDestinationException
import com.threeastudio.gitclonepush.domain.repository.LocalRepositoryRemover
import com.threeastudio.gitclonepush.domain.repository.LocalRepositoryStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.Git
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import com.threeastudio.gitclonepush.data.filesystem.hasLinkedAncestor
import com.threeastudio.gitclonepush.data.filesystem.isFilesystemLink

/** Private registry for legacy internal clones and explicitly selected shared-storage clones. */
class AndroidLocalRepositoryStore(
    context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val coordinator: GitRepositoryOperationCoordinator = GitRepositoryOperationCoordinator()
) : LocalRepositoryStore, LocalRepositoryRemover {
    private val root = File(context.filesDir, "git-repositories").canonicalFile
    private val metadataRoot = File(root, ".app-metadata")
    private val destinations = ConcurrentHashMap<String, File>()
    private val selectedTrees = ConcurrentHashMap<String, String>()
    private val destinationPolicy = RepositoryDestinationPolicy()

    override fun repositoryDirectory(repositoryId: String): File {
        checkId(repositoryId)
        return destinations[repositoryId] ?: File(root, repositoryId)
    }

    suspend fun selectDestination(repository: GitRepository, parent: File, parentTreeUri: String): File = withContext(ioDispatcher) {
        coordinator.withRepositoryLock {
            checkId(repository.id)
            val existing = listRepositories()
            if (existing.any { it.id == repository.id }) throw CloneDestinationException(CloneDestinationError.DESTINATION_EXISTS)
            if (existing.any { parent.canonicalPath == it.directoryPath || parent.canonicalPath.startsWith(it.directoryPath + File.separator) }) {
                throw CloneDestinationException(CloneDestinationError.INVALID_FOLDER)
            }
            val target = destinationPolicy.destination(parent, repository.name)
            if (destinations.any { it.key != repository.id && it.value == target }) {
                throw CloneDestinationException(CloneDestinationError.DESTINATION_EXISTS)
            }
            destinations[repository.id] = target
            selectedTrees[repository.id] = parentTreeUri
            target
        }
    }

    override suspend fun listRepositories(): List<LocalRepository> = withContext(ioDispatcher) {
        val sidecars = metadataRoot.listFiles().orEmpty().filter { it.extension == "json" }.mapNotNull { file ->
            runCatching {
                val repository = JSONObject(file.readText()).toLocalRepository()
                checkId(repository.id)
                val path = File(repository.directoryPath)
                require(!hasLinkedAncestor(path))
                require(repository.parentTreeUri != null || path.canonicalFile == File(root, repository.id))
                destinations[repository.id] = path
                repository.parentTreeUri?.let { selectedTrees[repository.id] = it }
                repository
            }.getOrNull()
        }
        val registeredIds = sidecars.map { it.id }.toSet()
        val legacy = root.listFiles().orEmpty().filter { it.name.matches(ID_PATTERN) && it.name !in registeredIds }.mapNotNull { directory ->
            val metadata = File(directory, "metadata.json")
            runCatching { JSONObject(metadata.readText()).toLocalRepository() }.getOrNull()?.takeIf {
                File(it.directoryPath).canonicalFile == File(root, it.id)
            }?.also { repository ->
                writeMetadata(repository)
                metadata.delete()
            }
        }
        sidecars + legacy
    }

    override suspend fun save(repository: LocalRepository) = withContext(ioDispatcher) {
        checkId(repository.id)
        require(isSafeRemoteUrl(repository.remoteUrl)) { "CLONE_006_UNSAFE_REMOTE" }
        val directory = repositoryDirectory(repository.id)
        require(!hasLinkedAncestor(directory) && directory.canonicalPath == repository.directoryPath) { "CLONE_008_UNREGISTERED_DESTINATION" }
        val gitDirectory = File(directory, ".git")
        require(gitDirectory.isDirectory && !isFilesystemLink(gitDirectory)) { "CLONE_009_INVALID_GIT_DIRECTORY" }
        writeMetadata(repository.copy(parentTreeUri = selectedTrees[repository.id] ?: repository.parentTreeUri))
    }

    override suspend fun remove(repositoryId: String) = withContext(ioDispatcher) {
        coordinator.withRepositoryLock {
            val repository = listRepositories().firstOrNull { it.id == repositoryId } ?: return@withRepositoryLock
            val target = repositoryDirectory(repositoryId)
            require(!hasLinkedAncestor(target) && target.canonicalPath == repository.directoryPath && target != root) { "CLONE_REMOVE_UNSAFE_TARGET" }
            if (target.exists()) {
                val gitDirectory = File(target, ".git")
                require(gitDirectory.isDirectory && !isFilesystemLink(gitDirectory)) { "CLONE_REMOVE_INVALID_REPOSITORY" }
                Git.open(target).use { git ->
                    require(git.repository.config.getString("remote", "origin", "url") == repository.remoteUrl) { "CLONE_REMOVE_REMOTE_CHANGED" }
                }
                val record = JSONObject(metadataFile(repositoryId).readText())
                val ownership = record.optString("ownershipId")
                if (ownership.isNotBlank()) {
                    val ownershipFile = File(gitDirectory, OWNERSHIP_FILE)
                    require(!isFilesystemLink(ownershipFile) && ownershipFile.readText() == ownership) { "CLONE_REMOVE_OWNERSHIP_CHANGED" }
                }
                deleteOwnedClone(target)
            }
            if (!metadataFile(repositoryId).delete() && metadataFile(repositoryId).exists()) error("CLONE_REMOVE_METADATA_FAILED")
            destinations.remove(repositoryId)
            selectedTrees.remove(repositoryId)
            Unit
        }
    }

    private fun writeMetadata(repository: LocalRepository) {
        checkId(repository.id)
        if (!metadataRoot.exists() && !metadataRoot.mkdirs()) error("CLONE_007_METADATA_STORAGE_FAILED")
        val ownership = UUID.randomUUID().toString()
        val gitDirectory = File(repository.directoryPath, ".git")
        if (gitDirectory.isDirectory && !isFilesystemLink(gitDirectory)) {
            val ownershipFile = File(gitDirectory, OWNERSHIP_FILE)
            require(!isFilesystemLink(ownershipFile)) { "CLONE_010_UNSAFE_OWNERSHIP_FILE" }
            ownershipFile.writeText(ownership)
        }
        val json = JSONObject().put("id", repository.id).put("owner", repository.owner).put("name", repository.name)
            .put("directoryPath", repository.directoryPath).put("remoteUrl", repository.remoteUrl)
            .put("parentTreeUri", repository.parentTreeUri).put("ownershipId", ownership)
        val temporary = File(metadataRoot, "${repository.id}.tmp")
        temporary.writeText(json.toString())
        if (!temporary.renameTo(metadataFile(repository.id))) error("CLONE_007_METADATA_STORAGE_FAILED")
        destinations[repository.id] = File(repository.directoryPath)
    }

    private fun metadataFile(id: String): File { checkId(id); return File(metadataRoot, "$id.json") }
    private fun checkId(id: String) = require(id.matches(ID_PATTERN)) { "REPOSITORY_003_INVALID_ID" }
    private fun JSONObject.toLocalRepository() = LocalRepository(
        getString("id"), getString("owner"), getString("name"), getString("directoryPath"), getString("remoteUrl"),
        optString("parentTreeUri").takeIf { it.isNotBlank() && it != "null" }
    )
    private fun isSafeRemoteUrl(url: String) = runCatching {
        val uri = URI(url)
        uri.scheme.equals("https", true) && uri.host.equals("github.com", true) && uri.userInfo == null &&
            uri.query == null && uri.fragment == null && uri.path.orEmpty().endsWith(".git")
    }.getOrDefault(false)

    private companion object {
        val ID_PATTERN = Regex("[0-9]+")
        const val OWNERSHIP_FILE = "gitclonepush-owner"
    }
}
