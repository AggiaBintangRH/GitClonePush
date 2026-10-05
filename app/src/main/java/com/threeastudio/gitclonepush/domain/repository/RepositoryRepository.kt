package com.threeastudio.gitclonepush.domain.repository

import com.threeastudio.gitclonepush.core.model.GitRepository

interface RepositoryRepository {
    suspend fun getRepositories(): List<GitRepository>
}
