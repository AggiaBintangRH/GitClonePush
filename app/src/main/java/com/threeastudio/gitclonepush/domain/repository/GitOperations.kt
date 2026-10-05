package com.threeastudio.gitclonepush.domain.repository

import com.threeastudio.gitclonepush.core.model.*
import kotlinx.coroutines.flow.Flow

data class CloneRepositoryRequest(val repository: GitRepository, val destination: String)
interface RepositoryCloner { fun clone(request: CloneRepositoryRequest): Flow<CloneProgress> }
interface GitRepositoryReader {
    suspend fun status(repository: LocalRepository): RepositoryStatus
    suspend fun currentBranch(repository: LocalRepository): GitBranch
    suspend fun recentCommits(repository: LocalRepository, limit: Int): List<GitCommit>
}
interface GitRepositoryMutator {
    suspend fun stage(repository: LocalRepository, paths: List<String>)
    suspend fun stageAll(repository: LocalRepository)
    suspend fun commit(repository: LocalRepository, request: CommitRequest): GitCommit
}
interface GitRemoteSynchronizer {
    suspend fun fetch(repository: LocalRepository)
    suspend fun pull(repository: LocalRepository): PullResult
    suspend fun push(repository: LocalRepository): PushResult
}
interface LocalRepositoryStore {
    fun repositoryDirectory(repositoryId: String): java.io.File
    suspend fun listRepositories(): List<LocalRepository>
    suspend fun save(repository: LocalRepository)
}
interface GitCredentialProvider { suspend fun credentialsFor(remote: GitRemote): GitCredentials }
interface GitAuthorIdentityStore { suspend fun read(): GitAuthorIdentity?; suspend fun write(identity: GitAuthorIdentity) }
