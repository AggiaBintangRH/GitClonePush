package com.threeastudio.gitclonepush.feature.repositories

import com.threeastudio.gitclonepush.core.model.GitRepository
import com.threeastudio.gitclonepush.core.model.CloneStatus
import com.threeastudio.gitclonepush.core.model.RepositoryCloneState
import com.threeastudio.gitclonepush.core.model.RepositoryVisibility

enum class RepositoryFilter { ALL, PRIVATE, PUBLIC, CLONED }

data class RepositoryListUiState(
    val isInitialLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val repositories: List<GitRepository> = emptyList(),
    val query: String = "",
    val filter: RepositoryFilter = RepositoryFilter.CLONED,
    val cloneStates: Map<String, RepositoryCloneState> = emptyMap(),
    val errorMessage: String? = null,
    val refreshErrorMessage: String? = null
) {
    val visibleRepositories: List<GitRepository>
        get() = repositories.filter { repository ->
            val matchesQuery = repository.name.contains(query, ignoreCase = true) ||
                "${repository.owner}/${repository.name}".contains(query, ignoreCase = true)
            val matchesFilter = filter == RepositoryFilter.ALL ||
                (filter == RepositoryFilter.PRIVATE && repository.visibility == RepositoryVisibility.PRIVATE) ||
                (filter == RepositoryFilter.PUBLIC && repository.visibility == RepositoryVisibility.PUBLIC) ||
                (filter == RepositoryFilter.CLONED && cloneStates[repository.id]?.status == CloneStatus.CLONED)
            val isLocal = cloneStates[repository.id]?.status == CloneStatus.CLONED
            matchesQuery && matchesFilter && isLocal
        }

    val activeClones: List<GitRepository>
        get() = repositories.filter { cloneStates[it.id]?.status in setOf(CloneStatus.CLONING, CloneStatus.ERROR) }
}
