package com.threeastudio.gitclonepush.data.github

import android.util.Log

enum class RepositoryDiagnosticEvent {
    REPO_INITIAL_LOAD_STARTED,
    REPO_API_REQUEST,
    REPO_API_RESPONSE,
    REPO_PAGE_LOADED,
    REPO_LOAD_SUCCESS,
    REPO_REFRESH_STARTED,
    REPO_REFRESH_SUCCESS,
    REPO_REFRESH_FAILED,
    REPO_LOCAL_STATE_RECONCILED
}

interface RepositoryDiagnostics {
    fun event(event: RepositoryDiagnosticEvent, metadata: Map<String, String> = emptyMap())
}

object NoOpRepositoryDiagnostics : RepositoryDiagnostics {
    override fun event(event: RepositoryDiagnosticEvent, metadata: Map<String, String>) = Unit
}

class DebugRepositoryDiagnostics : RepositoryDiagnostics {
    override fun event(event: RepositoryDiagnosticEvent, metadata: Map<String, String>) {
        val safeMetadata = metadata.filterKeys { it in SAFE_KEYS }
        val suffix = safeMetadata.entries.joinToString(" ") { "${it.key}=${it.value}" }
        Log.d(TAG, if (suffix.isBlank()) event.name else "${event.name} $suffix")
    }

    private companion object {
        const val TAG = "GitClonePushRepo"
        val SAFE_KEYS = setOf("page", "httpStatus", "count", "total", "remoteCount", "clonedCount", "category")
    }
}
