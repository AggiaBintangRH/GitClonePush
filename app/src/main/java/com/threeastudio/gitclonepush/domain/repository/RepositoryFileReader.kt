package com.threeastudio.gitclonepush.domain.repository

import com.threeastudio.gitclonepush.core.model.RepositoryFileContent
import com.threeastudio.gitclonepush.core.model.RepositoryFileEntry

interface RepositoryFileReader {
    suspend fun listDirectory(repositoryId: String, relativePath: String = ""): List<RepositoryFileEntry>
    suspend fun readFile(repositoryId: String, relativePath: String): RepositoryFileContent
}

enum class RepositoryFilesErrorCategory {
    REPOSITORY_INVALID,
    DIRECTORY_NOT_FOUND,
    NOT_DIRECTORY,
    PATH_FORBIDDEN,
    PATH_OUTSIDE_REPOSITORY,
    FILE_NOT_FOUND,
    NOT_REGULAR_FILE,
    BINARY_FILE,
    READ_FAILED,
    CANCELLED,
    UNKNOWN
}

class RepositoryFilesException(
    val category: RepositoryFilesErrorCategory,
    cause: Throwable? = null
) : Exception(category.name, cause)
