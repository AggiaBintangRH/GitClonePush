package com.threeastudio.gitclonepush.domain.repository

import com.threeastudio.gitclonepush.core.model.GitRepository

/** Resolves an explicit folder selection into a destination the Git engine can open. */
fun interface CloneDestinationSelector {
    suspend fun select(repository: GitRepository, parentTreeUri: String): String
}

enum class CloneDestinationError {
    STORAGE_ACCESS_REQUIRED, UNSUPPORTED_LOCATION, INVALID_FOLDER, DESTINATION_EXISTS, STORAGE_UNAVAILABLE
}

class CloneDestinationException(val category: CloneDestinationError, cause: Throwable? = null) :
    Exception(category.name, cause)
