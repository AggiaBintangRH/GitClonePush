package com.threeastudio.gitclonepush.data.github

import com.threeastudio.gitclonepush.core.model.GitRepository
import com.threeastudio.gitclonepush.core.model.RepositoryVisibility
import com.threeastudio.gitclonepush.core.security.SecureTokenStore
import com.threeastudio.gitclonepush.domain.repository.RepositoryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray

class GitHubRepositoryCatalog(private val tokenStore: SecureTokenStore, private val httpClient: OkHttpClient) : RepositoryRepository {
    override suspend fun getRepositories(): List<GitRepository> = withContext(Dispatchers.IO) {
        val token = tokenStore.read()?.accessToken ?: error("REPOSITORY_001_AUTH_REQUIRED: Sign in to load repositories.")
        val repositories = mutableListOf<GitRepository>(); var page = 1; var lastPage = false
        while (true) {
            val request = Request.Builder().url("https://api.github.com/user/repos?visibility=all&affiliation=owner,collaborator,organization_member&sort=updated&per_page=100&page=$page").header("Authorization", "Bearer $token").header("Accept", "application/vnd.github+json").header("X-GitHub-Api-Version", "2022-11-28").build()
            httpClient.newCall(request).execute().use { response ->
                check(response.isSuccessful) { "REPOSITORY_002_API_FAILED: GitHub repository request failed (${response.code})." }
                val array = JSONArray(response.body?.string().orEmpty())
                for (index in 0 until array.length()) repositories += mapRepository(array.getJSONObject(index))
                lastPage = array.length() < 100
            }
            if (lastPage) break
            page++
        }
        repositories
    }

    private fun mapRepository(json: org.json.JSONObject) = GitRepository(
        id = json.getLong("id").toString(), name = json.getString("name"), owner = json.getJSONObject("owner").getString("login"),
        visibility = if (json.optBoolean("private")) RepositoryVisibility.PRIVATE else RepositoryVisibility.PUBLIC,
        language = json.optString("language").ifBlank { null }, defaultBranch = json.optString("default_branch", "main"), updatedAt = json.optString("updated_at"), cloneUrl = json.getString("clone_url")
    )
}
