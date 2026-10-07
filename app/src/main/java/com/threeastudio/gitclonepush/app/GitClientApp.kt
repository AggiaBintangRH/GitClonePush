package com.threeastudio.gitclonepush.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import android.content.Intent
import android.net.Uri
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.threeastudio.gitclonepush.feature.login.LoginScreen
import com.threeastudio.gitclonepush.feature.login.LoginViewModel
import com.threeastudio.gitclonepush.feature.repositories.RepositoryListScreen
import com.threeastudio.gitclonepush.feature.repository.RepositoryScreen
import com.threeastudio.gitclonepush.feature.repository.HistoryScreen
import com.threeastudio.gitclonepush.feature.repository.CommitDetailScreen
import com.threeastudio.gitclonepush.feature.repository.DiffScreen
import com.threeastudio.gitclonepush.feature.settings.SettingsScreen
import com.threeastudio.gitclonepush.core.model.ThemeMode
import com.threeastudio.gitclonepush.data.auth.AuthDiagnosticEvent
import com.threeastudio.gitclonepush.data.auth.safeAuthFailureCategory
import com.threeastudio.gitclonepush.core.designsystem.components.LoadingOverlay

private object Routes { const val LOGIN = "login"; const val REPOSITORIES = "repositories"; const val WORKSPACE = "workspace/{repositoryId}"; const val HISTORY = "history/{repositoryId}"; const val DETAIL = "commit/{repositoryId}/{commitId}"; const val DIFF = "diff/{repositoryId}/{scope}/{commitId}/{path}"; const val SETTINGS = "settings" }

@Composable
fun GitClientApp(application: GitClientApplication, themeMode: ThemeMode, onThemeModeChange: (ThemeMode) -> Unit) {
    val navController = rememberNavController()
    val context = LocalContext.current
    val authState by application.container.authRepository.observeAuthState().collectAsStateWithLifecycle()
    val loginViewModel: LoginViewModel = viewModel(factory = LoginViewModelFactory(application.container.authRepository))
    LaunchedEffect(Unit) {
        application.oauthCallbacks.collect { callback ->
            application.container.authDiagnostics.event(
                AuthDiagnosticEvent.AUTH_CALLBACK_CONSUMED,
                mapOf("scheme" to (callback.scheme ?: ""), "host" to (callback.host ?: ""), "path" to (callback.path ?: ""))
            )
            val result = loginViewModel.completeLogin(callback).await()
            if (result.isFailure) {
                val error = result.exceptionOrNull()
                application.container.authDiagnostics.event(
                    AuthDiagnosticEvent.AUTH_COMPLETE_LOGIN_FAILED,
                    mapOf("category" to safeAuthFailureCategory(error))
                )
            }
        }
    }
    LaunchedEffect(authState) {
        val observedState = when (authState) {
            com.threeastudio.gitclonepush.core.model.AuthState.Loading -> "Loading"
            com.threeastudio.gitclonepush.core.model.AuthState.Unauthenticated -> "Unauthenticated"
            is com.threeastudio.gitclonepush.core.model.AuthState.Authenticated -> "Authenticated"
        }
        application.container.authDiagnostics.event(AuthDiagnosticEvent.AUTH_NAV_STATE_OBSERVED, mapOf("state" to observedState))
        when (authState) {
            is com.threeastudio.gitclonepush.core.model.AuthState.Authenticated -> {
                application.container.authDiagnostics.event(AuthDiagnosticEvent.AUTH_NAVIGATE_REPOSITORIES)
                navController.navigate(Routes.REPOSITORIES) { popUpTo(Routes.LOGIN) { inclusive = true }; launchSingleTop = true }
            }
            com.threeastudio.gitclonepush.core.model.AuthState.Unauthenticated -> if (navController.currentDestination?.route != Routes.LOGIN) {
                application.container.authDiagnostics.event(AuthDiagnosticEvent.AUTH_NAVIGATE_LOGIN)
                navController.navigate(Routes.LOGIN) { popUpTo(Routes.REPOSITORIES) { inclusive = true } }
            }
            else -> Unit
        }
    }
    NavHost(navController, startDestination = Routes.LOGIN) {
        composable(Routes.LOGIN) { LoginScreen({ context.startActivity(Intent(Intent.ACTION_VIEW, it)) }, loginViewModel) }
        composable(Routes.REPOSITORIES) { RepositoryListScreen((authState as? com.threeastudio.gitclonepush.core.model.AuthState.Authenticated)?.user?.login.orEmpty(), { id -> navController.navigate("workspace/$id") }, { navController.navigate(Routes.SETTINGS) }, application.container.repositoryRepository, application.container.repositoryCloner, application.container.localRepositoryStore, application.container.repositoryDiagnostics, application.container.localRepositoryValidator, cloneDestinationSelector = application.container.cloneDestinationSelector) }
        composable(Routes.WORKSPACE) { entry ->
            val id = entry.arguments?.getString("repositoryId").orEmpty()
            RepositoryScreen(id, { navController.popBackStack() }, application.container.localRepositoryStore, application.container.repositoryReader, application.container.repositoryMutator, application.container.remoteSynchronizer, application.container.authorIdentityReader, application.container.gitDiagnostics, application.container.branchReader, application.container.branchMutator, application.container.mergeSupport, application.container.mergeSupport, application.container.rebaseSupport, application.container.rebaseSupport, application.container.revertSupport, application.container.revertSupport, application.container.cherryPickSupport, application.container.cherryPickSupport, application.container.stashSupport, application.container.stashSupport,
                onOpenHistory = { navController.navigate("history/$id") },
                onOpenDiff = { scope, path -> navController.navigate("diff/$id/${scope.name}/none/${Uri.encode(path)}") },
                fileReader = application.container.repositoryFileReader,
                openInFiles = application.container.openRepositoryInFiles,
                localRepositoryRemover = application.container.localRepositoryStore,
                onCloneRemoved = { navController.popBackStack() })
        }
        composable(Routes.HISTORY) { entry ->
            val id = entry.arguments?.getString("repositoryId").orEmpty()
            HistoryScreen(id, { navController.popBackStack() }, { commit -> navController.navigate("commit/$id/$commit") }, application.container.localRepositoryStore, application.container.historyDiffSupport)
        }
        composable(Routes.DETAIL) { entry ->
            val id = entry.arguments?.getString("repositoryId").orEmpty(); val commit = entry.arguments?.getString("commitId").orEmpty()
            CommitDetailScreen(id, commit, { navController.popBackStack() }, { path, parent -> navController.navigate("diff/$id/${com.threeastudio.gitclonepush.core.model.DiffScope.COMMIT.name}/$commit/${Uri.encode(path)}") }, application.container.localRepositoryStore, application.container.historyDiffSupport)
        }
        composable(Routes.DIFF) { entry ->
            val id = entry.arguments?.getString("repositoryId").orEmpty(); val scope = entry.arguments?.getString("scope").orEmpty(); val commit = entry.arguments?.getString("commitId").orEmpty(); val path = entry.arguments?.getString("path").orEmpty().takeUnless { it == "none" }
            val request = com.threeastudio.gitclonepush.core.model.DiffRequest(com.threeastudio.gitclonepush.core.model.DiffScope.valueOf(scope), commit.takeUnless { it == "none" }, path)
            DiffScreen(if (path == null) "Diff" else "Diff · $path", { navController.popBackStack() }, application.container.localRepositoryStore, application.container.historyDiffSupport, id, request)
        }
        composable(Routes.SETTINGS) { SettingsScreen((authState as? com.threeastudio.gitclonepush.core.model.AuthState.Authenticated)?.user?.login.orEmpty(), themeMode, onThemeModeChange, application.container.authorIdentityReader, application.container.authRepository) }
    }
    LoadingOverlay(if (authState == com.threeastudio.gitclonepush.core.model.AuthState.Loading) "Restoring your GitHub session…" else null)
}

private class LoginViewModelFactory(private val authRepository: com.threeastudio.gitclonepush.domain.repository.AuthRepository) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = LoginViewModel(authRepository) as T
}
