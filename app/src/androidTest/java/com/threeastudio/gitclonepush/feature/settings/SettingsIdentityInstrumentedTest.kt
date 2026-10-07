package com.threeastudio.gitclonepush.feature.settings

import android.net.Uri
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.threeastudio.gitclonepush.core.model.AuthState
import com.threeastudio.gitclonepush.core.model.AuthenticatedUser
import com.threeastudio.gitclonepush.core.model.ThemeMode
import com.threeastudio.gitclonepush.domain.repository.AuthLaunchRequest
import com.threeastudio.gitclonepush.domain.repository.AuthRepository
import com.threeastudio.gitclonepush.domain.usecase.SessionGitAuthorIdentityReader
import com.threeastudio.gitclonepush.ui.theme.GitClonePushTheme
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsIdentityInstrumentedTest {
    @get:Rule
    val compose = createComposeRule()

    private var model: SettingsViewModel? = null

    @After
    fun cancelViewModelScope() {
        compose.runOnUiThread { model?.viewModelScope?.cancel() }
    }

    @Test
    fun profileIdentityIsDisplayedAndHasNoEditOrSaveAction() {
        showSettings()

        compose.onNodeWithText("Alice", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("alice@example.com", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        compose.onAllNodes(hasSetTextAction(), useUnmergedTree = true).assertCountEquals(0)
        compose.onNodeWithText("Save Git identity").assertDoesNotExist()
    }

    @Test
    fun displayedIdentityFollowsTheActiveAccountWithoutManualInput() {
        val auth = showSettings()
        compose.runOnIdle {
            auth.state.value = AuthState.Authenticated(AuthenticatedUser(654321, "bob", "Bob", null))
        }

        compose.onNodeWithText("Bob", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("654321+bob@users.noreply.github.com", useUnmergedTree = true)
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("alice@example.com", useUnmergedTree = true).assertDoesNotExist()
        compose.onAllNodes(hasSetTextAction(), useUnmergedTree = true).assertCountEquals(0)
    }

    private fun showSettings(): ProfileAuthRepository {
        val auth = ProfileAuthRepository()
        val reader = SessionGitAuthorIdentityReader(auth)
        lateinit var viewModel: SettingsViewModel
        compose.runOnUiThread {
            viewModel = SettingsViewModel(reader, auth)
            model = viewModel
        }
        compose.setContent {
            GitClonePushTheme(dynamicColor = false) {
                SettingsScreen("alice", ThemeMode.SYSTEM, {}, reader, auth, viewModel)
            }
        }
        return auth
    }

    private class ProfileAuthRepository : AuthRepository {
        val state = MutableStateFlow<AuthState>(
            AuthState.Authenticated(AuthenticatedUser(123456, "alice", "Alice", null, "alice@example.com"))
        )

        override fun observeAuthState() = state
        override suspend fun beginLogin(): Result<AuthLaunchRequest> =
            Result.failure(UnsupportedOperationException("Not used in this UI test"))
        override suspend fun completeLogin(callbackUri: Uri): Result<Unit> =
            Result.failure(UnsupportedOperationException("Not used in this UI test"))
        override suspend fun cancelLogin() = Unit
        override suspend fun logout() { state.value = AuthState.Unauthenticated }
    }
}
