package com.threeastudio.gitclonepush.data.auth

import android.net.Uri
import androidx.core.net.toUri
import com.threeastudio.gitclonepush.core.model.AuthState
import com.threeastudio.gitclonepush.core.model.AuthenticatedUser
import com.threeastudio.gitclonepush.core.security.PendingOAuthStore
import com.threeastudio.gitclonepush.core.security.PendingOAuthTransaction
import com.threeastudio.gitclonepush.core.security.SecureTokenStore
import com.threeastudio.gitclonepush.core.security.StoredAuthTokens
import com.threeastudio.gitclonepush.domain.repository.AuthLaunchRequest
import com.threeastudio.gitclonepush.domain.repository.AuthRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
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
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val diagnostics: AuthDiagnostics = NoOpAuthDiagnostics,
    private val oauthBackendConfig: OAuthBackendConfig? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : AuthRepository {
    private val authState = MutableStateFlow<AuthState>(AuthState.Loading)
    private var loginAttemptId: String? = null

    override fun observeAuthState(): StateFlow<AuthState> = authState.asStateFlow()

    init {
        diagnostics.event(AuthDiagnosticEvent.AUTH_STATE_CHANGED, mapOf("state" to "Loading"))
        scope.launch { restoreSession() }
    }

    override suspend fun beginLogin(): Result<AuthLaunchRequest> {
        val attempt = UUID.randomUUID().toString()
        loginAttemptId = attempt
        diagnostics.event(
            AuthDiagnosticEvent.AUTH_LOGIN_STARTED,
            mapOf(
                "attempt" to attempt,
                "clientIdConfigured" to oauthConfig.clientId.isNotBlank().toString(),
                "redirectUriConfigured" to oauthConfig.redirectUri.startsWith("https://").toString(),
                "backendConfigured" to (oauthBackendConfig?.baseUrl?.startsWith("https://") ?: false).toString()
            )
        )

        return runCatching {
            require(oauthConfig.clientId.isNotBlank()) {
                "AUTH_001_APPLICATION_CONFIG_MISSING: The application OAuth client ID is not configured in this build."
            }
            val pkce = pkceGenerator.generate()
            val state = UUID.randomUUID().toString()
            pendingStore.writePending(PendingOAuthTransaction(state, pkce.verifier))
            diagnostics.event(
                AuthDiagnosticEvent.AUTH_PENDING_TRANSACTION_CREATED,
                mapOf("attempt" to attempt, "statePresent" to "true", "verifierPresent" to "true")
            )
            val uri = "https://github.com/login/oauth/authorize".toUri().buildUpon()
                .appendQueryParameter("client_id", oauthConfig.clientId)
                .appendQueryParameter("redirect_uri", oauthConfig.redirectUri)
                .appendQueryParameter("scope", "read:user repo")
                .appendQueryParameter("state", state)
                .appendQueryParameter("code_challenge", pkce.challenge)
                .appendQueryParameter("code_challenge_method", "S256")
                .build()
            diagnostics.event(
                AuthDiagnosticEvent.AUTH_BROWSER_LAUNCH_READY,
                mapOf(
                    "attempt" to attempt,
                    "scheme" to (uri.scheme ?: ""),
                    "host" to (uri.host ?: ""),
                    "redirectUri" to oauthConfig.redirectUri
                )
            )
            AuthLaunchRequest(uri)
        }.onFailure { error ->
            if (error is CancellationException) throw error
            diagnostics.event(
                AuthDiagnosticEvent.AUTH_LOGIN_START_FAILED,
                mapOf("attempt" to attempt, "category" to safeAuthFailureCategory(error))
            )
        }
    }

    override suspend fun completeLogin(callbackUri: Uri): Result<Unit> {
        val attempt = loginAttemptId ?: "unknown"
        diagnostics.event(
            AuthDiagnosticEvent.AUTH_COMPLETE_LOGIN_STARTED,
            mapOf(
                "attempt" to attempt,
                "scheme" to (callbackUri.scheme ?: ""),
                "host" to (callbackUri.host ?: ""),
                "path" to (callbackUri.path ?: "")
            )
        )

        return try {
            val pending = pendingStore.readPending()
            diagnostics.event(
                AuthDiagnosticEvent.AUTH_PENDING_TRANSACTION_READ,
                mapOf("attempt" to attempt, "found" to (pending != null).toString())
            )
            checkNotNull(pending) { "AUTH_002_NO_PENDING_LOGIN: Login session expired." }

            val returnedState = callbackUri.getQueryParameter("state")
            val code = callbackUri.getQueryParameter("code")
            val providerError = callbackUri.getQueryParameter("error")
            diagnostics.event(
                AuthDiagnosticEvent.AUTH_CALLBACK_PARAMETERS,
                mapOf(
                    "attempt" to attempt,
                    "hasState" to (returnedState != null).toString(),
                    "hasCode" to (code != null).toString(),
                    "hasError" to (providerError != null).toString()
                )
            )

            val stateMatches = returnedState != null && returnedState == pending.state
            diagnostics.event(
                AuthDiagnosticEvent.AUTH_STATE_VALIDATION,
                mapOf("attempt" to attempt, "matched" to stateMatches.toString())
            )
            check(returnedState != null) { "AUTH_003_MISSING_STATE: OAuth state was not returned." }
            check(stateMatches) { "AUTH_004_STATE_MISMATCH: OAuth response was rejected." }

            if (providerError != null) {
                diagnostics.event(
                    AuthDiagnosticEvent.AUTH_PROVIDER_ERROR,
                    mapOf("attempt" to attempt, "error" to providerError)
                )
                error("AUTH_005_PROVIDER_REJECTED: GitHub authorization was cancelled or rejected.")
            }

            checkNotNull(code) { "AUTH_006_CODE_MISSING: GitHub authorization code is missing." }
            diagnostics.event(AuthDiagnosticEvent.AUTH_TOKEN_EXCHANGE_START, mapOf("attempt" to attempt))
            val exchangeResult = tokenExchangeGateway.exchange(
                OAuthTokenExchangeRequest(code, oauthConfig.redirectUri, pending.verifier, attempt)
            )
            exchangeResult.onSuccess {
                diagnostics.event(AuthDiagnosticEvent.AUTH_TOKEN_EXCHANGE_SUCCESS, mapOf("attempt" to attempt))
            }.onFailure { error ->
                diagnostics.event(
                    AuthDiagnosticEvent.AUTH_TOKEN_EXCHANGE_FAILURE,
                    buildMap {
                        put("attempt", attempt)
                        put("category", safeAuthFailureCategory(error))
                        safeAuthHttpStatus(error).takeIf { it.isNotBlank() }?.let { put("httpStatus", it) }
                    }
                )
            }
            val token = exchangeResult.getOrThrow().accessToken
            diagnostics.event(AuthDiagnosticEvent.AUTH_TOKEN_RECEIVED, mapOf("attempt" to attempt, "present" to "true"))

            try {
                tokenStore.write(StoredAuthTokens(token))
                diagnostics.event(AuthDiagnosticEvent.AUTH_TOKEN_STORED, mapOf("attempt" to attempt))
            } catch (storageError: Throwable) {
                if (storageError is CancellationException) throw storageError
                diagnostics.event(
                    AuthDiagnosticEvent.AUTH_TOKEN_STORE_FAILED,
                    mapOf("attempt" to attempt, "category" to storageError::class.simpleName.orEmpty())
                )
                error("AUTH_015_TOKEN_STORE_FAILED: Could not securely store the GitHub session.")
            }

            pendingStore.clearPending()
            loadUser(token, attempt)
            Result.success(Unit)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            runCatching { pendingStore.clearPending() }
            setAuthState(AuthState.Unauthenticated)
            diagnostics.event(
                AuthDiagnosticEvent.AUTH_COMPLETE_LOGIN_FAILED,
                buildMap {
                    put("attempt", attempt)
                    put("category", safeAuthFailureCategory(error))
                    safeAuthHttpStatus(error).takeIf { it.isNotBlank() }?.let { put("httpStatus", it) }
                }
            )
            Result.failure(error)
        }
    }

    override suspend fun logout() {
        pendingStore.clearPending()
        tokenStore.clear()
        setAuthState(AuthState.Unauthenticated)
    }

    override suspend fun cancelLogin() {
        pendingStore.clearPending()
        diagnostics.event(AuthDiagnosticEvent.AUTH_LOGIN_CANCELLED)
    }

    private suspend fun restoreSession() {
        diagnostics.event(AuthDiagnosticEvent.AUTH_SESSION_RESTORE_STARTED)
        val token = try {
            tokenStore.read()?.accessToken
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            diagnostics.event(
                AuthDiagnosticEvent.AUTH_SESSION_RESTORE_FAILED,
                mapOf("category" to safeAuthFailureCategory(error))
            )
            runCatching { tokenStore.clear() }
            diagnostics.event(AuthDiagnosticEvent.AUTH_SESSION_CLEARED_AFTER_RESTORE_FAILURE)
            setAuthState(AuthState.Unauthenticated)
            return
        }
        diagnostics.event(
            AuthDiagnosticEvent.AUTH_SESSION_RESTORE_TOKEN_FOUND,
            mapOf("present" to (!token.isNullOrBlank()).toString())
        )
        if (token.isNullOrBlank()) {
            setAuthState(AuthState.Unauthenticated)
            return
        }

        try {
            loadUser(token, "restore")
            diagnostics.event(AuthDiagnosticEvent.AUTH_SESSION_RESTORE_SUCCESS)
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            diagnostics.event(
                AuthDiagnosticEvent.AUTH_SESSION_RESTORE_FAILED,
                buildMap {
                    put("category", safeAuthFailureCategory(error))
                    safeAuthHttpStatus(error).takeIf { it.isNotBlank() }?.let { put("httpStatus", it) }
                }
            )
            if (error is ProfileHttpException && error.statusCode in setOf(401, 403)) {
                tokenStore.clear()
                diagnostics.event(AuthDiagnosticEvent.AUTH_SESSION_CLEARED_AFTER_RESTORE_FAILURE)
            }
            setAuthState(AuthState.Unauthenticated)
        }
    }

    private suspend fun loadUser(token: String, attempt: String) {
        diagnostics.event(AuthDiagnosticEvent.AUTH_PROFILE_REQUEST_STARTED, mapOf("attempt" to attempt))
        try {
            withContext(ioDispatcher) {
                val request = Request.Builder()
                    .url("https://api.github.com/user")
                    .header("Authorization", "Bearer $token")
                    .header("Accept", "application/vnd.github+json")
                    .header("X-GitHub-Api-Version", "2022-11-28")
                    .build()
                httpClient.newCall(request).execute().use { response ->
                    diagnostics.event(
                        AuthDiagnosticEvent.AUTH_PROFILE_RESPONSE,
                        mapOf(
                            "attempt" to attempt,
                            "httpStatus" to response.code.toString(),
                            "successful" to response.isSuccessful.toString()
                        )
                    )
                    if (!response.isSuccessful) {
                        diagnostics.event(
                            AuthDiagnosticEvent.AUTH_PROFILE_FAILED,
                            mapOf(
                                "attempt" to attempt,
                                "httpStatus" to response.code.toString(),
                                "category" to "AUTH_PROFILE_HTTP_${response.code}"
                            )
                        )
                        throw ProfileHttpException(response.code)
                    }

                    val user = try {
                        JSONObject(response.body?.string().orEmpty())
                    } catch (parseError: Exception) {
                        diagnostics.event(
                            AuthDiagnosticEvent.AUTH_PROFILE_PARSE_FAILED,
                            mapOf("attempt" to attempt, "exceptionType" to (parseError::class.simpleName ?: "JSONException"))
                        )
                        diagnostics.event(
                            AuthDiagnosticEvent.AUTH_PROFILE_FAILED,
                            mapOf("attempt" to attempt, "category" to "AUTH_PROFILE_PARSE_FAILED")
                        )
                        throw ProfileParseException()
                    }
                    val idPresent = user.optLong("id", 0) > 0
                    val loginPresent = user.optionalString("login") != null
                    if (!idPresent || !loginPresent) {
                        diagnostics.event(
                            AuthDiagnosticEvent.AUTH_PROFILE_PARSE_FAILED,
                            mapOf("attempt" to attempt, "exceptionType" to "JSONException")
                        )
                        diagnostics.event(
                            AuthDiagnosticEvent.AUTH_PROFILE_FAILED,
                            mapOf("attempt" to attempt, "category" to "AUTH_PROFILE_PARSE_FAILED")
                        )
                        throw ProfileParseException()
                    }
                    diagnostics.event(
                        AuthDiagnosticEvent.AUTH_PROFILE_PARSE_SUCCESS,
                        mapOf("attempt" to attempt, "idPresent" to "true", "loginPresent" to "true")
                    )
                    val authenticatedUser = AuthenticatedUser(
                        user.getLong("id"),
                        user.getString("login"),
                        user.optionalString("name"),
                        user.optionalString("avatar_url"),
                        user.optionalString("email")
                    )
                    diagnostics.event(AuthDiagnosticEvent.AUTH_PROFILE_LOADED, mapOf("attempt" to attempt, "loginPresent" to "true"))
                    setAuthState(AuthState.Authenticated(authenticatedUser))
                }
            }
        } catch (knownFailure: ProfileOperationException) {
            throw knownFailure
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (transportError: Exception) {
            diagnostics.event(
                AuthDiagnosticEvent.AUTH_PROFILE_TRANSPORT_FAILURE,
                mapOf("attempt" to attempt, "exceptionType" to (transportError::class.simpleName ?: "IOException"))
            )
            diagnostics.event(
                AuthDiagnosticEvent.AUTH_PROFILE_FAILED,
                mapOf("attempt" to attempt, "category" to "AUTH_PROFILE_TRANSPORT_FAILURE")
            )
            throw ProfileTransportException(transportError)
        }
    }

    private fun JSONObject.optionalString(key: String): String? =
        (opt(key) as? String)?.trim()?.takeIf { it.isNotBlank() }

    private sealed class ProfileOperationException(message: String) : IllegalStateException(message)
    private class ProfileHttpException(val statusCode: Int) : ProfileOperationException("AUTH_009_PROFILE_HTTP_$statusCode: Could not load the GitHub profile.")
    private class ProfileParseException : ProfileOperationException("AUTH_017_PROFILE_PARSE_FAILED: GitHub profile response was invalid.")
    private class ProfileTransportException(cause: Throwable) : ProfileOperationException("AUTH_016_PROFILE_TRANSPORT_FAILURE: GitHub profile request failed.") {
        init { initCause(cause) }
    }

    private fun setAuthState(state: AuthState) {
        authState.value = state
        diagnostics.event(AuthDiagnosticEvent.AUTH_STATE_CHANGED, mapOf("state" to stateName(state)))
    }

    private fun stateName(state: AuthState): String = when (state) {
        AuthState.Loading -> "Loading"
        AuthState.Unauthenticated -> "Unauthenticated"
        is AuthState.Authenticated -> "Authenticated"
    }
}
