package com.threeastudio.gitclonepush.domain.repository

import com.threeastudio.gitclonepush.core.model.GitRepository

interface RepositoryRepository {
    suspend fun getRepositories(): List<GitRepository>
}

enum class RepositoryErrorCategory {
    AUTHENTICATION_REQUIRED,
    NETWORK_UNAVAILABLE,
    RATE_LIMITED,
    API_FAILURE,
    INVALID_RESPONSE,
    UNKNOWN
}

class RepositoryDataException(
    val category: RepositoryErrorCategory,
    val httpStatus: Int? = null,
    cause: Throwable? = null
) : Exception(category.name, cause)
