package com.threeastudio.gitclonepush.data.documents

import android.util.Log

enum class FilesDiagnosticEvent {
    OPEN_IN_FILES_REQUESTED,
    OPEN_IN_FILES_URI_READY,
    OPEN_IN_FILES_LAUNCHED,
    OPEN_IN_FILES_FALLBACK,
    OPEN_IN_FILES_FAILED,
    FILES_LIST_STARTED,
    FILES_LIST_SUCCESS,
    FILES_LIST_FAILED,
    FILE_READ_STARTED,
    FILE_READ_SUCCESS,
    FILE_READ_FAILED,
    PROVIDER_ROOTS_QUERIED,
    PROVIDER_DOCUMENT_QUERIED,
    PROVIDER_CHILDREN_QUERIED,
    PROVIDER_DOCUMENT_OPENED,
    PROVIDER_DOCUMENT_CREATED,
    PROVIDER_DOCUMENT_RENAMED,
    PROVIDER_DOCUMENT_DELETED,
    PROVIDER_ACCESS_REJECTED
}

interface FilesDiagnostics {
    fun event(event: FilesDiagnosticEvent, metadata: Map<String, String> = emptyMap())
}

object NoOpFilesDiagnostics : FilesDiagnostics {
    override fun event(event: FilesDiagnosticEvent, metadata: Map<String, String>) = Unit
}

class DebugFilesDiagnostics : FilesDiagnostics {
    override fun event(event: FilesDiagnosticEvent, metadata: Map<String, String>) {
        val safe = metadata.filterKeys { it in SAFE_KEYS }
        val suffix = safe.entries.joinToString(" ") { "${it.key}=${it.value}" }
        Log.d(TAG, if (suffix.isBlank()) event.name else "${event.name} $suffix")
    }

    private companion object {
        const val TAG = "GitClonePushFiles"
        val SAFE_KEYS = setOf("operation", "success", "category", "isDirectory", "count", "binary", "truncated", "location")
    }
}
