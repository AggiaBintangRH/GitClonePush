package com.threeastudio.gitclonepush.data.auth

import android.util.Log
import com.threeastudio.gitclonepush.BuildConfig

enum class AuthDiagnosticEvent {
    AUTH_LOGIN_STARTED,
    AUTH_LOGIN_START_FAILED,
    AUTH_LOGIN_CANCELLED,
    AUTH_PENDING_TRANSACTION_CREATED,
    AUTH_BROWSER_LAUNCH_READY,
    AUTH_ANDROID_CALLBACK_RECEIVED,
    AUTH_CALLBACK_PUBLISHED,
    AUTH_CALLBACK_CONSUMED,
    AUTH_COMPLETE_LOGIN_STARTED,
    AUTH_PENDING_TRANSACTION_READ,
    AUTH_CALLBACK_PARAMETERS,
    AUTH_STATE_VALIDATION,
    AUTH_PROVIDER_ERROR,
    AUTH_TOKEN_EXCHANGE_START,
    AUTH_TOKEN_EXCHANGE_SUCCESS,
    AUTH_TOKEN_EXCHANGE_FAILURE,
    AUTH_BACKEND_REQUEST_STARTED,
    AUTH_BACKEND_TRANSPORT_FAILURE,
    AUTH_BACKEND_RESPONSE,
    AUTH_BACKEND_PARSE_STARTED,
    AUTH_BACKEND_PARSE_SUCCESS,
    AUTH_BACKEND_ERROR,
    AUTH_BACKEND_PARSE_FAILED,
    AUTH_BACKEND_TOKEN_MISSING,
    AUTH_TOKEN_RECEIVED,
    AUTH_TOKEN_STORED,
    AUTH_TOKEN_STORE_FAILED,
    AUTH_PROFILE_REQUEST_STARTED,
    AUTH_PROFILE_RESPONSE,
    AUTH_PROFILE_PARSE_SUCCESS,
    AUTH_PROFILE_PARSE_FAILED,
    AUTH_PROFILE_TRANSPORT_FAILURE,
    AUTH_PROFILE_LOADED,
    AUTH_PROFILE_FAILED,
    AUTH_SESSION_RESTORE_STARTED,
    AUTH_SESSION_RESTORE_TOKEN_FOUND,
    AUTH_SESSION_RESTORE_SUCCESS,
    AUTH_SESSION_RESTORE_FAILED,
    AUTH_SESSION_CLEARED_AFTER_RESTORE_FAILURE,
    AUTH_STATE_CHANGED,
    AUTH_COMPLETE_LOGIN_FAILED,
    AUTH_NAV_STATE_OBSERVED,
    AUTH_NAVIGATE_LOGIN,
    AUTH_NAVIGATE_REPOSITORIES
}

interface AuthDiagnostics {
    fun event(event: AuthDiagnosticEvent, metadata: Map<String, String> = emptyMap())
}

object NoOpAuthDiagnostics : AuthDiagnostics {
    override fun event(event: AuthDiagnosticEvent, metadata: Map<String, String>) = Unit
}

class DebugAuthDiagnostics : AuthDiagnostics {
    override fun event(event: AuthDiagnosticEvent, metadata: Map<String, String>) {
        if (!BuildConfig.DEBUG) return
        val safeMetadata = metadata
            .filterKeys { it in SAFE_METADATA_KEYS }
            .mapValues { (key, value) -> sanitizeValue(key, value) }
        val suffix = safeMetadata.entries.joinToString(" ") { (key, value) -> "$key=$value" }
        Log.d(TAG, if (suffix.isEmpty()) event.name else "${event.name} $suffix")
    }

    private fun sanitizeValue(key: String, value: String): String {
        if (key == "error" || key == "backendError" || key == "category") {
            return value.take(64).filter { it.isLetterOrDigit() || it == '_' || it == '-' || it == '.' }
                .ifBlank { "unknown" }
        }
        return value.take(128).filter { it != '\n' && it != '\r' }
    }

    private companion object {
        const val TAG = "GitClonePushOAuth"
        val SAFE_METADATA_KEYS = setOf(
            "attempt", "clientIdConfigured", "redirectUriConfigured", "redirectUri", "backendConfigured",
            "statePresent", "verifierPresent", "scheme", "host", "path", "hasCode", "hasState",
            "hasError", "found", "matched", "error", "category", "httpStatus", "endpoint",
            "baseUrlConfigured", "hasVerifier", "successful", "backendError", "accessTokenPresent",
            "tokenTypePresent", "scopePresent", "idPresent", "present", "loginPresent",
            "state", "exceptionType"
        )
    }
}

fun safeAuthFailureCategory(error: Throwable?): String =
    error?.message
        ?.substringBefore(':')
        ?.takeIf { it.matches(Regex("AUTH_[A-Z0-9_]+")) }
        ?: "AUTH_UNKNOWN_FAILURE"

fun safeAuthHttpStatus(error: Throwable?): String =
    Regex("(?:HTTP_|\\()([45]\\d{2})").find(error?.message.orEmpty())?.groupValues?.get(1).orEmpty()
