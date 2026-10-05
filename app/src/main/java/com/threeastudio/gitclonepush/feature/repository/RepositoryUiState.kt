package com.threeastudio.gitclonepush.feature.repository

import com.threeastudio.gitclonepush.core.model.*

data class RepositoryUiState(
    val isLoading: Boolean = true,
    val localRepository: LocalRepository? = null,
    val branch: GitBranch? = null,
    val status: RepositoryStatus = RepositoryStatus(emptyList(), true),
    val commits: List<GitCommit> = emptyList(),
    val operationMessage: String? = null,
    val errorMessage: String? = null
)
