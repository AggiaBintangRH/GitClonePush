package com.threeastudio.gitclonepush.data.fake

import com.threeastudio.gitclonepush.core.model.GitRepository
import com.threeastudio.gitclonepush.core.model.RepositoryVisibility
import com.threeastudio.gitclonepush.domain.repository.RepositoryRepository
import kotlinx.coroutines.delay

class FakeRepositoryRepository : RepositoryRepository {
    override suspend fun getRepositories(): List<GitRepository> {
        delay(450)
        return listOf(
            GitRepository("repo-1", "diarization-engine", "Aggia", RepositoryVisibility.PRIVATE, "Kotlin", "main", "Updated 2 hours ago"),
            GitRepository("repo-2", "android-git-client", "Aggia", RepositoryVisibility.PRIVATE, "Kotlin", "main", "Updated yesterday"),
            GitRepository("repo-3", "audio-tools", "Aggia", RepositoryVisibility.PUBLIC, "Python", "develop", "Updated 3 days ago")
        )
    }
}
