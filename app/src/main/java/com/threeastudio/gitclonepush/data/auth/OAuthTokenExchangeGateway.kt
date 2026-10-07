package com.threeastudio.gitclonepush.data.auth

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import java.io.IOException

data class OAuthTokenExchangeRequest(
    val code: String,
    val redirectUri: String,
    val codeVerifier: String,
    val attemptId: String? = null
)
data class OAuthToken(val accessToken: String)

interface OAuthTokenExchangeGateway {
    suspend fun exchange(request: OAuthTokenExchangeRequest): Result<OAuthToken>
}

class BackendOAuthTokenExchangeGateway(
    private val config: OAuthBackendConfig,
    private val httpClient: OkHttpClient,
    private val diagnostics: AuthDiagnostics = NoOpAuthDiagnostics
) : OAuthTokenExchangeGateway {
    override suspend fun exchange(request: OAuthTokenExchangeRequest): Result<OAuthToken> = withContext(Dispatchers.IO) {
        try {
            val baseUrl = config.baseUrl.trim().trimEnd('/')
            require(baseUrl.startsWith("https://")) { "AUTH_011_BACKEND_CONFIG_MISSING: Configure an HTTPS OAuth backend URL in the application build." }
            diagnostics.event(
                AuthDiagnosticEvent.AUTH_BACKEND_REQUEST_STARTED,
                mapOf(
                    "attempt" to (request.attemptId ?: "unknown"),
                    "baseUrlConfigured" to "true",
                    "endpoint" to "/oauth/token",
                    "hasCode" to request.code.isNotBlank().toString(),
                    "hasVerifier" to request.codeVerifier.isNotBlank().toString()
                )
            )
            val body = JSONObject()
                .put("code", request.code)
                .put("code_verifier", request.codeVerifier)
                .toString()
                .toRequestBody(JSON_MEDIA_TYPE)
            val httpRequest = Request.Builder()
                .url("$baseUrl/oauth/token")
                .post(body)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .build()
            val response = try {
                httpClient.newCall(httpRequest).execute()
            } catch (transportError: Exception) {
                if (transportError is CancellationException) throw transportError
                diagnostics.event(
                    AuthDiagnosticEvent.AUTH_BACKEND_TRANSPORT_FAILURE,
                    mapOf(
                        "attempt" to (request.attemptId ?: "unknown"),
                        "exceptionType" to (transportError::class.simpleName ?: "Exception")
                    )
                )
                throw transportError
            }
            response.use { response ->
                val responseBody = response.body?.string().orEmpty()
                val backendError = if (response.isSuccessful) null else responseError(responseBody)
                diagnostics.event(
                    AuthDiagnosticEvent.AUTH_BACKEND_RESPONSE,
                    buildMap {
                        put("attempt", request.attemptId ?: "unknown")
                        put("httpStatus", response.code.toString())
                        put("successful", response.isSuccessful.toString())
                        backendError?.let { put("backendError", it) }
                    }
                )
                if (!response.isSuccessful) {
                    diagnostics.event(
                        AuthDiagnosticEvent.AUTH_BACKEND_ERROR,
                        buildMap {
                            put("attempt", request.attemptId ?: "unknown")
                            backendError?.let { put("backendError", it) }
                            put("httpStatus", response.code.toString())
                        }
                    )
                    error("AUTH_012_BACKEND_HTTP_${response.code}: OAuth backend rejected the token exchange.")
                }
                diagnostics.event(
                    AuthDiagnosticEvent.AUTH_BACKEND_PARSE_STARTED,
                    mapOf("attempt" to (request.attemptId ?: "unknown"))
                )
                val payload = try {
                    JSONObject(responseBody)
                } catch (parseError: Throwable) {
                    diagnostics.event(
                        AuthDiagnosticEvent.AUTH_BACKEND_PARSE_FAILED,
                        mapOf(
                            "attempt" to (request.attemptId ?: "unknown"),
                            "exceptionType" to (parseError::class.simpleName ?: "JSONException")
                        )
                    )
                    error("AUTH_014_BACKEND_PARSE_FAILED: OAuth backend returned invalid JSON.")
                }
                val token = (payload.opt("access_token") as? String).orEmpty()
                if (token.isBlank()) {
                    diagnostics.event(
                        AuthDiagnosticEvent.AUTH_BACKEND_TOKEN_MISSING,
                        mapOf("attempt" to (request.attemptId ?: "unknown"))
                    )
                    error("AUTH_013_BACKEND_TOKEN_MISSING: OAuth backend returned no access token.")
                }
                diagnostics.event(
                    AuthDiagnosticEvent.AUTH_BACKEND_PARSE_SUCCESS,
                    mapOf(
                        "attempt" to (request.attemptId ?: "unknown"),
                        "accessTokenPresent" to "true",
                        "tokenTypePresent" to payload.optString("token_type").isNotBlank().toString(),
                        "scopePresent" to payload.optString("scope").isNotBlank().toString()
                    )
                )
                Result.success(OAuthToken(token))
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            diagnostics.event(
                AuthDiagnosticEvent.AUTH_TOKEN_EXCHANGE_FAILURE,
                buildMap {
                    put("attempt", request.attemptId ?: "unknown")
                    put("category", backendFailureCategory(error))
                    safeAuthHttpStatus(error).takeIf { it.isNotBlank() }?.let { put("httpStatus", it) }
                }
            )
            Result.failure(error)
        }
    }

    private fun responseError(body: String): String? = runCatching {
        JSONObject(body).optString("error").takeIf { it.matches(Regex("[A-Za-z0-9_.-]{1,64}")) }
    }.getOrNull()

    private fun backendFailureCategory(error: Throwable): String = when (error) {
        is IOException, is SecurityException -> "AUTH_BACKEND_TRANSPORT_FAILURE"
        else -> safeAuthFailureCategory(error)
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
