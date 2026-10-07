package com.threeastudio.gitclonepush.feature.settings

import androidx.lifecycle.viewModelScope
import com.threeastudio.gitclonepush.core.model.AuthState
import com.threeastudio.gitclonepush.core.model.AuthenticatedUser
import com.threeastudio.gitclonepush.domain.usecase.SessionGitAuthorIdentityReader
import com.threeastudio.gitclonepush.testing.FakeAuthRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    private val alice = AuthenticatedUser(123456, "alice", "Alice", null, "alice@example.com")

    @Test
    fun loadsIdentityAutomaticallyWithoutSaveOrEditActions() = viewModelTest { models ->
        val auth = FakeAuthRepository(AuthState.Authenticated(alice))
        val model = SettingsViewModel(SessionGitAuthorIdentityReader(auth), auth).also(models::add)
        advanceUntilIdle()
        assertEquals("Alice", model.uiState.value.authorName)
        assertEquals("alice@example.com", model.uiState.value.authorEmail)
        assertFalse(model.uiState.value.isLoadingIdentity)
    }

    @Test
    fun followsAccountChangesAndClearsIdentityOnSignOut() = viewModelTest { models ->
        val auth = FakeAuthRepository(AuthState.Authenticated(alice))
        val model = SettingsViewModel(SessionGitAuthorIdentityReader(auth), auth).also(models::add)
        advanceUntilIdle()
        auth.state.value = AuthState.Authenticated(AuthenticatedUser(654321, "bob", "Bob", null))
        advanceUntilIdle()
        assertEquals("Bob", model.uiState.value.authorName)
        assertEquals("654321+bob@users.noreply.github.com", model.uiState.value.authorEmail)
        model.signOut()
        advanceUntilIdle()
        assertEquals(1, auth.logoutCalls)
        assertTrue(model.uiState.value.authorName.isEmpty())
        assertTrue(model.uiState.value.authorEmail.isEmpty())
    }

    @Test
    fun duplicateSignOutClicksDoNotStartDuplicateSessionMutations() = viewModelTest { models ->
        val auth = FakeAuthRepository(AuthState.Authenticated(alice))
        val model = SettingsViewModel(SessionGitAuthorIdentityReader(auth), auth).also(models::add)
        model.signOut()
        model.signOut()
        advanceUntilIdle()
        assertEquals(1, auth.logoutCalls)
        assertFalse(model.uiState.value.isSigningOut)
    }

    @Test
    fun signOutFailureKeepsAccountAndShowsSafeError() = viewModelTest { models ->
        val auth = FakeAuthRepository(AuthState.Authenticated(alice)).apply { logoutError = IllegalStateException("private failure") }
        val model = SettingsViewModel(SessionGitAuthorIdentityReader(auth), auth).also(models::add)
        advanceUntilIdle()
        model.signOut()
        advanceUntilIdle()
        assertEquals("Alice", model.uiState.value.authorName)
        assertEquals("Could not sign out. Please try again.", model.uiState.value.errorMessage)
        assertFalse(model.uiState.value.isSigningOut)
    }

    private fun viewModelTest(block: suspend TestScope.(MutableList<SettingsViewModel>) -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val models = mutableListOf<SettingsViewModel>()
        try { block(models) }
        finally {
            models.forEach { it.viewModelScope.cancel() }
            Dispatchers.resetMain()
        }
    }
}
