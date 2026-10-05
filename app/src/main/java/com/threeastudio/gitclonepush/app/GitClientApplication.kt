package com.threeastudio.gitclonepush.app

import android.app.Application
import android.net.Uri
import com.threeastudio.gitclonepush.data.auth.GitHubAuthRepository
import com.threeastudio.gitclonepush.data.auth.SecurePkceGenerator
import com.threeastudio.gitclonepush.data.github.GitHubRepositoryCatalog
import com.threeastudio.gitclonepush.data.git.*
import com.threeastudio.gitclonepush.data.security.AndroidSecureSessionStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient

class GitClientApplication : Application() {
    lateinit var container: AppContainer
    private val _oauthCallback = MutableStateFlow<Uri?>(null)
    val oauthCallback = _oauthCallback.asStateFlow()
    override fun onCreate() { super.onCreate(); container = AppContainer(this) }
    fun publishOAuthCallback(uri: Uri) { _oauthCallback.value = uri }
    fun clearOAuthCallback() { _oauthCallback.value = null }
}

class AppContainer(application: Application) {
    private val httpClient = OkHttpClient.Builder().build()
    private val sessionStore = AndroidSecureSessionStore(application)
    private val oauthConfig = com.threeastudio.gitclonepush.data.auth.BuildConfigGitHubOAuthConfig()
    private val tokenExchangeGateway = com.threeastudio.gitclonepush.data.auth.DirectGitHubTokenExchangeGateway(oauthConfig, httpClient)
    val authRepository = GitHubAuthRepository(sessionStore, sessionStore, SecurePkceGenerator(), oauthConfig, tokenExchangeGateway, httpClient)
    val repositoryRepository = GitHubRepositoryCatalog(sessionStore, httpClient)
    private val credentialProvider = JGitCredentials(sessionStore)
    private val loader = JGitRepositoryLoader()
    val localRepositoryStore = AndroidLocalRepositoryStore(application)
    val repositoryCloner = JGitRepositoryCloner(localRepositoryStore, credentialProvider)
    val repositoryReader = JGitRepositoryReader(loader)
    val repositoryMutator = JGitRepositoryMutator(loader)
    val remoteSynchronizer = JGitRemoteSynchronizer(loader, credentialProvider)
    val authorIdentityStore = AndroidGitAuthorIdentityStore(application)
}
