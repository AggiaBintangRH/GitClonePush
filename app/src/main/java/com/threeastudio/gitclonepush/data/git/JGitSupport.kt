package com.threeastudio.gitclonepush.data.git

import com.threeastudio.gitclonepush.core.model.*
import com.threeastudio.gitclonepush.core.security.SecureTokenStore
import com.threeastudio.gitclonepush.domain.repository.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.errors.GitAPIException
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.lib.RepositoryBuilder
import org.eclipse.jgit.transport.CredentialsProvider
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import org.eclipse.jgit.lib.ProgressMonitor
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class JGitCredentials(private val tokenStore: SecureTokenStore) : GitCredentialProvider {
    override suspend fun credentialsFor(remote: GitRemote) = GitCredentials("x-access-token", tokenStore.read()?.accessToken ?: error("GIT_001_AUTH_REQUIRED: Sign in before accessing a remote."))
}

class JGitRepositoryCloner(private val store: LocalRepositoryStore, private val credentials: GitCredentialProvider) : RepositoryCloner {
    override fun clone(request: CloneRepositoryRequest): Flow<CloneProgress> = channelFlow {
        trySend(CloneProgress.Preparing)
        val destination = File(request.destination)
        if (destination.exists() && destination.listFiles()?.isNotEmpty() == true) error("CLONE_001_DESTINATION_EXISTS: The destination already contains files.")
        val auth = credentials.credentialsFor(GitRemote(request.repository.cloneUrl))
        val monitor = object : ProgressMonitor {
            var total = 0; var completed = 0
            override fun start(totalTasks: Int) { total = totalTasks }
            override fun beginTask(title: String?, totalWork: Int) { total = totalWork; completed = 0 }
            override fun update(completed: Int) { this.completed += completed; trySend(CloneProgress.Receiving(this.completed.toLong(), total.toLong().takeIf { it > 0 }, if (total > 0) (this.completed * 100 / total).coerceIn(0, 100) else null)) }
            override fun endTask() = Unit
            override fun isCancelled() = false
            override fun showDuration(enabled: Boolean) = Unit
        }
        val git = Git.cloneRepository().setURI(request.repository.cloneUrl).setDirectory(destination).setCredentialsProvider(UsernamePasswordCredentialsProvider(auth.username, auth.password)).setProgressMonitor(monitor).call()
        try {
            val local = LocalRepository(request.repository.id, request.repository.owner, request.repository.name, destination.absolutePath, request.repository.cloneUrl)
            store.save(local); trySend(CloneProgress.Completed(local))
        } finally { git.close() }
    }.flowOn(Dispatchers.IO)
}

class JGitRepositoryLoader {
    fun open(repository: LocalRepository): Git = Git.open(File(repository.directoryPath))
}

class JGitRepositoryReader(private val loader: JGitRepositoryLoader) : GitRepositoryReader {
    override suspend fun status(repository: LocalRepository): RepositoryStatus = kotlinx.coroutines.withContext(Dispatchers.IO) { loader.open(repository).use { git -> val status = git.status().call(); val changes = buildList { status.modified.forEach { add(FileChange(it, FileChangeStatus.MODIFIED)) }; status.added.forEach { add(FileChange(it, FileChangeStatus.ADDED)) }; status.removed.forEach { add(FileChange(it, FileChangeStatus.DELETED)) }; status.untracked.forEach { add(FileChange(it, FileChangeStatus.ADDED)) } }; RepositoryStatus(changes, changes.isEmpty()) } }
    override suspend fun currentBranch(repository: LocalRepository): GitBranch = kotlinx.coroutines.withContext(Dispatchers.IO) { loader.open(repository).use { GitBranch(it.repository.branch, true) } }
    override suspend fun recentCommits(repository: LocalRepository, limit: Int): List<GitCommit> = kotlinx.coroutines.withContext(Dispatchers.IO) { loader.open(repository).use { git -> git.log().setMaxCount(limit).call().map { GitCommit(it.shortMessage, ObjectId.toString(it.id).take(7), it.authorIdent.name, DateTimeFormatter.ISO_LOCAL_DATE.format(Instant.ofEpochSecond(it.commitTime.toLong()).atZone(ZoneId.systemDefault()))) } } }
}

class JGitRepositoryMutator(private val loader: JGitRepositoryLoader) : GitRepositoryMutator {
    override suspend fun stage(repository: LocalRepository, paths: List<String>) = kotlinx.coroutines.withContext(Dispatchers.IO) { loader.open(repository).use { git -> paths.forEach { git.add().addFilepattern(it).call() } } }
    override suspend fun stageAll(repository: LocalRepository) { kotlinx.coroutines.withContext(Dispatchers.IO) { loader.open(repository).use { it.add().addFilepattern(".").call() }; Unit } }
    override suspend fun commit(repository: LocalRepository, request: CommitRequest): GitCommit = kotlinx.coroutines.withContext(Dispatchers.IO) { loader.open(repository).use { git -> val commit = git.commit().setMessage(request.message.trim()).setAuthor(PersonIdent(request.authorName, request.authorEmail)).call(); GitCommit(commit.shortMessage, ObjectId.toString(commit.id).take(7), request.authorName, "now") } }
}

class JGitRemoteSynchronizer(private val loader: JGitRepositoryLoader, private val credentials: GitCredentialProvider) : GitRemoteSynchronizer {
    override suspend fun fetch(repository: LocalRepository) { kotlinx.coroutines.withContext(Dispatchers.IO) { loader.open(repository).use { git -> git.fetch().setCredentialsProvider(credentials.provider(repository.remoteUrl)).call() }; Unit } }
    override suspend fun pull(repository: LocalRepository): PullResult = kotlinx.coroutines.withContext(Dispatchers.IO) { loader.open(repository).use { git -> val result = git.pull().setCredentialsProvider(credentials.provider(repository.remoteUrl)).call(); if (result.isSuccessful) PullResult.Updated(0) else PullResult.Conflict(emptyList()) } }
    override suspend fun push(repository: LocalRepository): PushResult = kotlinx.coroutines.withContext(Dispatchers.IO) { loader.open(repository).use { git -> val results = git.push().setCredentialsProvider(credentials.provider(repository.remoteUrl)).call(); if (results.any { it.remoteUpdates.any { update -> update.status.name.contains("REJECTED") } }) error("GIT_002_PUSH_REJECTED: The remote rejected the push.") else PushResult.Pushed } }
    private suspend fun GitCredentialProvider.provider(remote: String): CredentialsProvider { val credentials = credentialsFor(GitRemote(remote)); return UsernamePasswordCredentialsProvider(credentials.username, credentials.password) }
}
