package com.threeastudio.gitclonepush.data.git

import android.content.Context
import com.threeastudio.gitclonepush.core.model.LocalRepository
import com.threeastudio.gitclonepush.domain.repository.LocalRepositoryStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

class AndroidLocalRepositoryStore(context: Context) : LocalRepositoryStore {
    private val root = File(context.filesDir, "git-repositories")
    override fun repositoryDirectory(repositoryId: String): File {
        require(repositoryId.matches(Regex("[0-9]+"))) { "REPOSITORY_003_INVALID_ID: Invalid repository identifier." }
        return File(root, repositoryId)
    }
    override suspend fun listRepositories(): List<LocalRepository> = withContext(Dispatchers.IO) {
        root.listFiles()?.mapNotNull { directory ->
            runCatching { JSONObject(File(directory, "metadata.json").readText()).toLocalRepository() }.getOrNull()
        }.orEmpty()
    }
    override suspend fun save(repository: LocalRepository) = withContext(Dispatchers.IO) {
        val directory = repositoryDirectory(repository.id)
        directory.mkdirs()
        File(directory, "metadata.json").writeText(JSONObject().put("id", repository.id).put("owner", repository.owner).put("name", repository.name).put("directoryPath", repository.directoryPath).put("remoteUrl", repository.remoteUrl).toString())
    }
    private fun JSONObject.toLocalRepository() = LocalRepository(getString("id"), getString("owner"), getString("name"), getString("directoryPath"), getString("remoteUrl"))
}
