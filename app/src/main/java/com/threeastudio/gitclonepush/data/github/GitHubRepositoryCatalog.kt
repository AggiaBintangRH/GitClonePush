package com.threeastudio.gitclonepush.data.github

import com.threeastudio.gitclonepush.core.model.GitRepository
import com.threeastudio.gitclonepush.core.model.RepositoryVisibility
import com.threeastudio.gitclonepush.core.security.SecureTokenStore
import com.threeastudio.gitclonepush.domain.repository.RepositoryDataException
import com.threeastudio.gitclonepush.domain.repository.RepositoryErrorCategory
import com.threeastudio.gitclonepush.domain.repository.RepositoryRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.io.IOException

class GitHubRepositoryCatalog(
    private val tokenStore: SecureTokenStore,
    private val httpClient: OkHttpClient,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val diagnostics: RepositoryDiagnostics = NoOpRepositoryDiagnostics
) : RepositoryRepository {
    override suspend fun getRepositories(): List<GitRepository> = withContext(ioDispatcher) {
        val token = tokenStore.read()?.accessToken
            ?: throw RepositoryDataException(RepositoryErrorCategory.AUTHENTICATION_REQUIRED)
        val repositories = mutableListOf<GitRepository>()
        var page = FIRST_PAGE

        while (page <= MAX_PAGES) {
            diagnostics.event(RepositoryDiagnosticEvent.REPO_API_REQUEST, mapOf("page" to page.toString()))
            val pageRepositories = requestPage(token, page)
            diagnostics.event(
                RepositoryDiagnosticEvent.REPO_PAGE_LOADED,
                mapOf("page" to page.toString(), "count" to pageRepositories.size.toString())
            )
            repositories += pageRepositories
            if (pageRepositories.size < PAGE_SIZE) break
            page++
        }

        diagnostics.event(RepositoryDiagnosticEvent.REPO_LOAD_SUCCESS, mapOf("total" to repositories.size.toString()))
        repositories
    }

    private fun requestPage(token: String, page: Int): List<GitRepository> {
        val request = Request.Builder()
            .url("https://api.github.com/user/repos?visibility=all&affiliation=owner,collaborator,organization_member&sort=updated&per_page=$PAGE_SIZE&page=$page")
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .build()

        try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw responseFailure(response.code)
                val pageRepositories = parsePage(response.body?.string().orEmpty())
                diagnostics.event(
                    RepositoryDiagnosticEvent.REPO_API_RESPONSE,
                    mapOf("page" to page.toString(), "httpStatus" to response.code.toString(), "count" to pageRepositories.size.toString())
                )
                return pageRepositories
            }
        } catch (error: RepositoryDataException) {
            throw error
        } catch (error: CancellationException) {
            throw error
        } catch (error: IOException) {
            throw RepositoryDataException(RepositoryErrorCategory.NETWORK_UNAVAILABLE, cause = error)
        } catch (error: Exception) {
            throw RepositoryDataException(RepositoryErrorCategory.UNKNOWN, cause = error)
        }
    }

    private fun parsePage(body: String): List<GitRepository> = try {
        val array = JSONArray(body)
        buildList(array.length()) {
            for (index in 0 until array.length()) add(mapRepository(array.getJSONObject(index)))
        }
    } catch (error: Exception) {
        throw RepositoryDataException(RepositoryErrorCategory.INVALID_RESPONSE, cause = error)
    }

    private fun mapRepository(json: org.json.JSONObject) = try {
        GitRepository(
            id = json.getLong("id").toString(),
            name = json.getString("name"),
            owner = json.getJSONObject("owner").getString("login"),
            visibility = if (json.optBoolean("private")) RepositoryVisibility.PRIVATE else RepositoryVisibility.PUBLIC,
            language = if (json.isNull("language")) null else json.optString("language").ifBlank { null },
            defaultBranch = json.optString("default_branch", "main"),
            updatedAt = json.getString("updated_at"),
            cloneUrl = json.getString("clone_url")
        )
    } catch (error: Exception) {
        throw RepositoryDataException(RepositoryErrorCategory.INVALID_RESPONSE, cause = error)
    }

    private fun responseFailure(statusCode: Int) = RepositoryDataException(
        category = when (statusCode) {
            401 -> RepositoryErrorCategory.AUTHENTICATION_REQUIRED
            403, 429 -> RepositoryErrorCategory.RATE_LIMITED
            else -> RepositoryErrorCategory.API_FAILURE
        },
        httpStatus = statusCode
    )

    private companion object {
        const val FIRST_PAGE = 1
        const val PAGE_SIZE = 100
        const val MAX_PAGES = 1000
    }
}
