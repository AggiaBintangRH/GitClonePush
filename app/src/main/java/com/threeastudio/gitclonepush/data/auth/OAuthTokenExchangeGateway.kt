package com.threeastudio.gitclonepush.data.auth

import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

data class OAuthTokenExchangeRequest(val code: String, val redirectUri: String, val codeVerifier: String)
data class OAuthToken(val accessToken: String)

interface OAuthTokenExchangeGateway {
    suspend fun exchange(request: OAuthTokenExchangeRequest): Result<OAuthToken>
}

class DirectGitHubTokenExchangeGateway(private val config: GitHubOAuthConfig, private val httpClient: OkHttpClient) : OAuthTokenExchangeGateway {
    override suspend fun exchange(request: OAuthTokenExchangeRequest): Result<OAuthToken> = try {
        val httpRequest = Request.Builder().url("https://github.com/login/oauth/access_token")
            .post(FormBody.Builder().add("client_id", config.clientId).add("code", request.code).add("redirect_uri", request.redirectUri).add("code_verifier", request.codeVerifier).build())
            .header("Accept", "application/json").build()
        httpClient.newCall(httpRequest).execute().use { response ->
            check(response.isSuccessful) { "AUTH_007_TOKEN_EXCHANGE_FAILED: GitHub token exchange failed (${response.code})." }
            val token = JSONObject(response.body?.string().orEmpty()).optString("access_token")
            check(token.isNotBlank()) { "AUTH_008_TOKEN_MISSING: GitHub did not return an access token." }
            Result.success(OAuthToken(token))
        }
    } catch (error: Throwable) { Result.failure(error) }
}
