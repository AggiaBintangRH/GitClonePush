package com.threeastudio.gitclonepush.domain.usecase

import com.threeastudio.gitclonepush.core.model.GitRepository
import com.threeastudio.gitclonepush.domain.repository.LocalRepositoryStore
import com.threeastudio.gitclonepush.domain.repository.LocalRepositoryValidator
import com.threeastudio.gitclonepush.domain.repository.RepositoryRepository

data class LoadedRepositories(
    val repositories: List<GitRepository>,
    val clonedRepositoryIds: Set<String>
)

class LoadRepositoriesUseCase(
    private val repositoryRepository: RepositoryRepository,
    private val localRepositoryStore: LocalRepositoryStore,
    private val localRepositoryValidator: LocalRepositoryValidator
) {
    suspend operator fun invoke(): LoadedRepositories {
        val repositories = repositoryRepository.getRepositories()
        val clonedIds = localRepositoryStore.listRepositories()
            .filter { localRepositoryValidator.validate(it) }
            .map { it.id }
            .toSet()
        return LoadedRepositories(repositories, clonedIds)
    }
}
