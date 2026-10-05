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
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.threeastudio.gitclonepush.feature.login.LoginScreen
import com.threeastudio.gitclonepush.feature.login.LoginViewModel
import com.threeastudio.gitclonepush.feature.repositories.RepositoryListScreen
import com.threeastudio.gitclonepush.feature.repository.RepositoryScreen
import com.threeastudio.gitclonepush.feature.settings.SettingsScreen
import com.threeastudio.gitclonepush.core.model.ThemeMode

private object Routes { const val LOGIN = "login"; const val REPOSITORIES = "repositories"; const val WORKSPACE = "workspace/{repositoryId}"; const val SETTINGS = "settings" }

@Composable
fun GitClientApp(application: GitClientApplication, themeMode: ThemeMode, onThemeModeChange: (ThemeMode) -> Unit) {
    val navController = rememberNavController()
    val context = LocalContext.current
    val authState by application.container.authRepository.observeAuthState().collectAsStateWithLifecycle()
    val oauthCallback by application.oauthCallback.collectAsStateWithLifecycle()
    LaunchedEffect(oauthCallback) { oauthCallback?.let { application.container.authRepository.completeLogin(it); application.clearOAuthCallback() } }
    LaunchedEffect(authState) {
        when (authState) {
            is com.threeastudio.gitclonepush.core.model.AuthState.Authenticated -> navController.navigate(Routes.REPOSITORIES) { popUpTo(Routes.LOGIN) { inclusive = true }; launchSingleTop = true }
            com.threeastudio.gitclonepush.core.model.AuthState.Unauthenticated -> if (navController.currentDestination?.route != Routes.LOGIN) navController.navigate(Routes.LOGIN) { popUpTo(Routes.REPOSITORIES) { inclusive = true } }
            else -> Unit
        }
    }
    NavHost(navController, startDestination = Routes.LOGIN) {
        composable(Routes.LOGIN) { LoginScreen({ context.startActivity(Intent(Intent.ACTION_VIEW, it)) }, viewModel(factory = LoginViewModelFactory(application.container.authRepository))) }
        composable(Routes.REPOSITORIES) { RepositoryListScreen((authState as? com.threeastudio.gitclonepush.core.model.AuthState.Authenticated)?.user?.login.orEmpty(), { id -> navController.navigate("workspace/$id") }, { navController.navigate(Routes.SETTINGS) }, application.container.repositoryRepository, application.container.repositoryCloner, application.container.localRepositoryStore) }
        composable(Routes.WORKSPACE) { entry -> RepositoryScreen(entry.arguments?.getString("repositoryId").orEmpty(), { navController.popBackStack() }, application.container.localRepositoryStore, application.container.repositoryReader, application.container.repositoryMutator, application.container.remoteSynchronizer, application.container.authorIdentityStore) }
        composable(Routes.SETTINGS) { SettingsScreen((authState as? com.threeastudio.gitclonepush.core.model.AuthState.Authenticated)?.user?.login.orEmpty(), themeMode, onThemeModeChange, { navController.navigate(Routes.LOGIN) { popUpTo(Routes.REPOSITORIES) { inclusive = true } } }, application.container.authorIdentityStore) }
    }
}

private class LoginViewModelFactory(private val authRepository: com.threeastudio.gitclonepush.domain.repository.AuthRepository) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = LoginViewModel(authRepository) as T
}
