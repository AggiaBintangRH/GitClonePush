package com.threeastudio.gitclonepush.domain.repository

import android.net.Uri

interface RepositoryDocumentsUriProvider {
    suspend fun repositoryRootUri(repositoryId: String): Uri
}

interface OpenRepositoryInFiles {
    suspend fun open(repositoryId: String): Result<Unit>
}

enum class OpenInFilesErrorCategory {
    REPOSITORY_NOT_FOUND,
    PROVIDER_UNAVAILABLE,
    DOCUMENT_UNAVAILABLE,
    NO_FILE_MANAGER_AVAILABLE,
    OPEN_FAILED
}

class OpenInFilesException(
    val category: OpenInFilesErrorCategory,
    cause: Throwable? = null
) : Exception(category.name, cause)
