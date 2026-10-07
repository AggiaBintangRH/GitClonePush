package com.threeastudio.gitclonepush.app

import android.app.Application
import android.net.Uri
import com.threeastudio.gitclonepush.data.auth.GitHubAuthRepository
import com.threeastudio.gitclonepush.data.auth.AuthDiagnostics
import com.threeastudio.gitclonepush.data.auth.AuthDiagnosticEvent
import com.threeastudio.gitclonepush.data.auth.DebugAuthDiagnostics
import com.threeastudio.gitclonepush.data.auth.NoOpAuthDiagnostics
import com.threeastudio.gitclonepush.data.auth.SecurePkceGenerator
import com.threeastudio.gitclonepush.data.github.GitHubRepositoryCatalog
import com.threeastudio.gitclonepush.data.github.DebugRepositoryDiagnostics
import com.threeastudio.gitclonepush.data.github.NoOpRepositoryDiagnostics
import com.threeastudio.gitclonepush.data.github.RepositoryDiagnostics
import com.threeastudio.gitclonepush.data.git.DebugGitDiagnostics
import com.threeastudio.gitclonepush.data.git.GitDiagnostics
import com.threeastudio.gitclonepush.data.git.NoOpGitDiagnostics
import com.threeastudio.gitclonepush.data.git.*
import com.threeastudio.gitclonepush.data.documents.*
import com.threeastudio.gitclonepush.data.security.AndroidSecureSessionStore
import okhttp3.OkHttpClient
import kotlinx.coroutines.Dispatchers

class GitClientApplication : Application() {
    lateinit var container: AppContainer
    private lateinit var callbackDispatcher: OAuthCallbackDispatcher
    val oauthCallbacks get() = callbackDispatcher.callbacks
    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        callbackDispatcher = OAuthCallbackDispatcher(container.authDiagnostics)
    }
    fun publishOAuthCallback(uri: Uri) {
        callbackDispatcher.publish(uri)
    }
}

class AppContainer(application: Application) {
    private val httpClient = OkHttpClient.Builder().build()
    private val sessionStore = AndroidSecureSessionStore(application)
    private val oauthConfig = com.threeastudio.gitclonepush.data.auth.BuildConfigGitHubOAuthConfig()
    private val oauthBackendConfig = com.threeastudio.gitclonepush.data.auth.BuildConfigOAuthBackendConfig()
    val authDiagnostics: AuthDiagnostics = if (com.threeastudio.gitclonepush.BuildConfig.DEBUG) {
        DebugAuthDiagnostics()
    } else {
        NoOpAuthDiagnostics
    }
    val repositoryDiagnostics: RepositoryDiagnostics = if (com.threeastudio.gitclonepush.BuildConfig.DEBUG) {
        DebugRepositoryDiagnostics()
    } else {
        NoOpRepositoryDiagnostics
    }
    val gitDiagnostics: GitDiagnostics = if (com.threeastudio.gitclonepush.BuildConfig.DEBUG) {
        DebugGitDiagnostics()
    } else {
        NoOpGitDiagnostics
    }
    private val tokenExchangeGateway = com.threeastudio.gitclonepush.data.auth.BackendOAuthTokenExchangeGateway(oauthBackendConfig, httpClient, authDiagnostics)
    val authRepository = GitHubAuthRepository(
        sessionStore,
        sessionStore,
        SecurePkceGenerator(),
        oauthConfig,
        tokenExchangeGateway,
        httpClient,
        diagnostics = authDiagnostics,
        oauthBackendConfig = oauthBackendConfig,
        ioDispatcher = Dispatchers.IO
    )
    val repositoryRepository = GitHubRepositoryCatalog(sessionStore, httpClient, Dispatchers.IO, repositoryDiagnostics)
    private val repositoryOperationCoordinator = GitRepositoryOperationCoordinator()
    val localRepositoryStore = AndroidLocalRepositoryStore(application, Dispatchers.IO, repositoryOperationCoordinator)
    val cloneDestinationSelector = com.threeastudio.gitclonepush.data.storage.AndroidCloneDestinationSelector(application, localRepositoryStore, Dispatchers.IO, gitDiagnostics)
    val localRepositoryValidator = JGitLocalRepositoryValidator(Dispatchers.IO)
    val repositoryFileReader = JGitRepositoryFileReader(localRepositoryStore, localRepositoryValidator, Dispatchers.IO)
    val openRepositoryInFiles = AndroidOpenRepositoryInFiles(
        application,
        AndroidRepositoryDocumentsUriProvider(application, localRepositoryStore, localRepositoryValidator, Dispatchers.IO),
        if (com.threeastudio.gitclonepush.BuildConfig.DEBUG) DebugFilesDiagnostics() else NoOpFilesDiagnostics
    )
    private val credentialProvider = JGitCredentials(sessionStore)
    private val loader = JGitRepositoryLoader()
    val repositoryCloner = JGitRepositoryCloner(
        localRepositoryStore,
        credentialProvider,
        ioDispatcher = Dispatchers.IO,
        diagnostics = gitDiagnostics
    )
    val repositoryReader = JGitRepositoryReader(loader, Dispatchers.IO)
    val branchReader = JGitBranchReader(loader, Dispatchers.IO, gitDiagnostics)
    val branchMutator = JGitBranchMutator(loader, repositoryOperationCoordinator, Dispatchers.IO, gitDiagnostics)
    val repositoryMutator = JGitRepositoryMutator(loader, Dispatchers.IO, gitDiagnostics, repositoryOperationCoordinator)
    val remoteSynchronizer = JGitRemoteSynchronizer(loader, credentialProvider, Dispatchers.IO, gitDiagnostics, repositoryOperationCoordinator)
    val authorIdentityReader = com.threeastudio.gitclonepush.domain.usecase.SessionGitAuthorIdentityReader(authRepository)
    val mergeSupport = JGitMergeSupport(loader, repositoryOperationCoordinator, Dispatchers.IO, gitDiagnostics, authorIdentityReader)
    val historyDiffSupport = JGitHistoryDiffSupport(loader, Dispatchers.IO, gitDiagnostics)
    val rebaseSupport = JGitRebaseSupport(loader, repositoryOperationCoordinator, Dispatchers.IO, gitDiagnostics, remoteSynchronizer)
    val revertSupport = JGitRevertSupport(loader, repositoryOperationCoordinator, Dispatchers.IO, gitDiagnostics)
    val cherryPickSupport = JGitCherryPickSupport(loader, repositoryOperationCoordinator, Dispatchers.IO, gitDiagnostics)
    val stashSupport = JGitStashSupport(loader, repositoryOperationCoordinator, Dispatchers.IO, gitDiagnostics)
}
