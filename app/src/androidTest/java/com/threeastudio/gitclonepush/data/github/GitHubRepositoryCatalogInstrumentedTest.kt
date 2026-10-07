package com.threeastudio.gitclonepush.data.github

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.threeastudio.gitclonepush.core.model.RepositoryVisibility
import com.threeastudio.gitclonepush.core.security.SecureTokenStore
import com.threeastudio.gitclonepush.core.security.StoredAuthTokens
import com.threeastudio.gitclonepush.domain.repository.RepositoryDataException
import com.threeastudio.gitclonepush.domain.repository.RepositoryErrorCategory
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GitHubRepositoryCatalogInstrumentedTest {
    private val jsonMediaType = "application/json".toMediaType()

    @Test
    fun mapsPublicPrivateOwnerBranchAndNullableLanguage() = runBlocking {
        val repositories = catalog(
            responseBody = """
                [
                  {"id":1,"name":"public","owner":{"login":"alice"},"private":false,"language":null,"default_branch":"main","updated_at":"2026-01-01T00:00:00Z","clone_url":"https://github.com/alice/public.git"},
                  {"id":2,"name":"private","owner":{"login":"alice"},"private":true,"language":"Kotlin","default_branch":"develop","updated_at":"2026-01-02T00:00:00Z","clone_url":"https://github.com/alice/private.git"}
                ]
            """.trimIndent()
        ).getRepositories()

        assertEquals(2, repositories.size)
        assertEquals("alice", repositories[0].owner)
        assertEquals("main", repositories[0].defaultBranch)
        assertNull(repositories[0].language)
        assertEquals(RepositoryVisibility.PRIVATE, repositories[1].visibility)
    }

    @Test
    fun combinesFullFirstPageAndShortSecondPage() = runBlocking {
        val requestedPages = mutableListOf<String>()
        val firstPage = (1..100).joinToString(",") { repositoryJson(it) }
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val page = chain.request().url.queryParameter("page").orEmpty()
            requestedPages += page
            val body = if (page == "1") "[$firstPage]" else "[${repositoryJson(101)}]"
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody(jsonMediaType)).build()
        }.build()

        val repositories = GitHubRepositoryCatalog(TokenStore(), client).getRepositories()

        assertEquals(101, repositories.size)
        assertEquals(listOf("1", "2"), requestedPages)
    }

    @Test
    fun mapsAuthenticationRateLimitServerAndMalformedResponses() = runBlocking {
        assertEquals(RepositoryErrorCategory.AUTHENTICATION_REQUIRED, failureFor(401).category)
        assertEquals(RepositoryErrorCategory.RATE_LIMITED, failureFor(403).category)
        assertEquals(RepositoryErrorCategory.API_FAILURE, failureFor(500).category)
        assertEquals(RepositoryErrorCategory.INVALID_RESPONSE, failureFor(200, "not-json").category)
    }

    private suspend fun failureFor(status: Int, body: String = "[]"): RepositoryDataException = try {
        catalog(status, body).getRepositories()
        error("Expected repository failure")
    } catch (error: RepositoryDataException) {
        error
    }

    private fun catalog(status: Int = 200, responseBody: String = "[]"): GitHubRepositoryCatalog {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(status)
                .message("test").body(responseBody.toResponseBody(jsonMediaType)).build()
        }.build()
        return GitHubRepositoryCatalog(TokenStore(), client)
    }

    private fun repositoryJson(id: Int) = "{\"id\":$id,\"name\":\"repo-$id\",\"owner\":{\"login\":\"alice\"},\"private\":false,\"language\":\"Kotlin\",\"default_branch\":\"main\",\"updated_at\":\"2026-01-01T00:00:00Z\",\"clone_url\":\"https://github.com/alice/repo-$id.git\"}"

    private class TokenStore : SecureTokenStore {
        override suspend fun read() = StoredAuthTokens("test-token")
        override suspend fun write(tokens: StoredAuthTokens) = Unit
        override suspend fun clear() = Unit
    }
}
