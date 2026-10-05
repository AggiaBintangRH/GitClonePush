package com.threeastudio.gitclonepush.data.auth

import android.net.Uri
import com.threeastudio.gitclonepush.core.model.*
import com.threeastudio.gitclonepush.core.security.*
import com.threeastudio.gitclonepush.domain.repository.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.UUID

class GitHubAuthRepository(
    private val tokenStore: SecureTokenStore,
    private val pendingStore: PendingOAuthStore,
    private val pkceGenerator: PkceGenerator,
    private val oauthConfig: GitHubOAuthConfig,
    private val tokenExchangeGateway: OAuthTokenExchangeGateway,
    private val httpClient: OkHttpClient,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) : AuthRepository {
    private val authState = MutableStateFlow<AuthState>(AuthState.Loading)
    override fun observeAuthState(): StateFlow<AuthState> = authState.asStateFlow()
    init { scope.launch { restoreSession() } }

    override suspend fun beginLogin(): Result<AuthLaunchRequest> = runCatching {
        require(oauthConfig.clientId.isNotBlank()) { "AUTH_001_APPLICATION_CONFIG_MISSING: The application OAuth client ID is not configured in this build." }
        val pkce = pkceGenerator.generate()
        val state = UUID.randomUUID().toString()
        pendingStore.writePending(PendingOAuthTransaction(state, pkce.verifier))
        val uri = Uri.parse("https://github.com/login/oauth/authorize").buildUpon()
            .appendQueryParameter("client_id", oauthConfig.clientId)
            .appendQueryParameter("redirect_uri", oauthConfig.redirectUri)
            .appendQueryParameter("scope", "read:user repo")
            .appendQueryParameter("state", state)
            .appendQueryParameter("code_challenge", pkce.challenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .build()
        AuthLaunchRequest(uri)
    }

    override suspend fun completeLogin(callbackUri: Uri): Result<Unit> = try {
        val pending = pendingStore.readPending() ?: error("AUTH_002_NO_PENDING_LOGIN: Login session expired.")
        val returnedState = callbackUri.getQueryParameter("state") ?: error("AUTH_003_MISSING_STATE: OAuth state was not returned.")
        check(returnedState == pending.state) { "AUTH_004_STATE_MISMATCH: OAuth response was rejected." }
        check(callbackUri.getQueryParameter("error") == null) { "AUTH_005_PROVIDER_REJECTED: GitHub rejected authorization." }
        val code = callbackUri.getQueryParameter("code") ?: error("AUTH_006_CODE_MISSING: GitHub authorization code is missing.")
        val token = tokenExchangeGateway.exchange(OAuthTokenExchangeRequest(code, oauthConfig.redirectUri, pending.verifier)).getOrThrow().accessToken
        tokenStore.write(StoredAuthTokens(token)); pendingStore.clearPending(); loadUser(token)
        Result.success(Unit)
    } catch (error: Throwable) {
        pendingStore.clearPending(); authState.value = AuthState.Unauthenticated; Result.failure(error)
    }

    override suspend fun logout() { pendingStore.clearPending(); tokenStore.clear(); authState.value = AuthState.Unauthenticated }
    private suspend fun restoreSession() {
        val token = tokenStore.read()?.accessToken
        if (token.isNullOrBlank()) {
            authState.value = AuthState.Unauthenticated
        } else {
            try { loadUser(token) } catch (_: Throwable) { tokenStore.clear(); authState.value = AuthState.Unauthenticated }
        }
    }
    private suspend fun loadUser(token: String) {
        val request = Request.Builder().url("https://api.github.com/user").header("Authorization", "Bearer $token").header("Accept", "application/vnd.github+json").header("X-GitHub-Api-Version", "2022-11-28").build()
        httpClient.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "AUTH_009_PROFILE_FAILED: Could not load the GitHub profile (${response.code})." }
            val user = JSONObject(response.body?.string().orEmpty())
            authState.value = AuthState.Authenticated(AuthenticatedUser(user.getLong("id"), user.getString("login"), user.optString("name").ifBlank { null }, user.optString("avatar_url").ifBlank { null }))
        }
    }
}
