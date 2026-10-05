package com.threeastudio.gitclonepush.feature.repositories

import com.threeastudio.gitclonepush.core.model.GitRepository
import com.threeastudio.gitclonepush.core.model.RepositoryCloneState
import com.threeastudio.gitclonepush.core.model.RepositoryVisibility

enum class RepositoryFilter { ALL, PRIVATE, PUBLIC }

data class RepositoryListUiState(
    val isLoading: Boolean = true,
    val repositories: List<GitRepository> = emptyList(),
    val query: String = "",
    val filter: RepositoryFilter = RepositoryFilter.ALL,
    val cloneStates: Map<String, RepositoryCloneState> = emptyMap(),
    val errorMessage: String? = null
) {
    val visibleRepositories: List<GitRepository>
        get() = repositories.filter { repository ->
            val matchesQuery = repository.name.contains(query, ignoreCase = true)
            val matchesFilter = filter == RepositoryFilter.ALL ||
                (filter == RepositoryFilter.PRIVATE && repository.visibility == RepositoryVisibility.PRIVATE) ||
                (filter == RepositoryFilter.PUBLIC && repository.visibility == RepositoryVisibility.PUBLIC)
            matchesQuery && matchesFilter
        }
}
