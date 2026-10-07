package com.threeastudio.gitclonepush.domain.usecase

import com.threeastudio.gitclonepush.core.model.AuthState
import com.threeastudio.gitclonepush.core.model.AuthenticatedUser
import com.threeastudio.gitclonepush.core.model.GitAuthorIdentity
import com.threeastudio.gitclonepush.testing.FakeAuthRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class SessionGitAuthorIdentityReaderTest {
    private val alice = AuthenticatedUser(123456, "alice", "Alice", null, "alice@example.com")

    @Test
    fun usesAuthenticatedProfileNameAndPublicEmail() = runTest {
        val reader = SessionGitAuthorIdentityReader(FakeAuthRepository(AuthState.Authenticated(alice)))
        assertEquals(GitAuthorIdentity("Alice", "alice@example.com"), reader.read())
    }

    @Test
    fun privateEmailUsesAccountSpecificNoreplyWithoutRequestingPrivateEmail() = runTest {
        val reader = SessionGitAuthorIdentityReader(FakeAuthRepository(AuthState.Authenticated(alice.copy(email = null))))
        assertEquals(GitAuthorIdentity("Alice", "123456+alice@users.noreply.github.com"), reader.read())
    }

    @Test
    fun missingDisplayNameUsesGitHubUsername() = runTest {
        val reader = SessionGitAuthorIdentityReader(FakeAuthRepository(AuthState.Authenticated(alice.copy(displayName = null))))
        assertEquals("alice", reader.read()?.name)
    }

    @Test
    fun invalidProfileFieldsFallBackToSafeAccountIdentity() = runTest {
        val reader = SessionGitAuthorIdentityReader(FakeAuthRepository(AuthState.Authenticated(
            alice.copy(displayName = "Bad\nName", email = "invalid\nemail@example.com")
        )))
        assertEquals(GitAuthorIdentity("alice", "123456+alice@users.noreply.github.com"), reader.read())
    }

    @Test
    fun accountSwitchAndLogoutNeverReusePreviousIdentity() = runTest {
        val auth = FakeAuthRepository(AuthState.Authenticated(alice))
        val reader = SessionGitAuthorIdentityReader(auth)
        assertEquals("Alice", reader.read()?.name)
        auth.state.value = AuthState.Authenticated(AuthenticatedUser(654321, "bob", "Bob", null))
        assertEquals(GitAuthorIdentity("Bob", "654321+bob@users.noreply.github.com"), reader.read())
        auth.logout()
        assertNull(reader.read())
    }

    @Test
    fun newReaderAfterSessionRestorationNeedsNoSavedManualIdentity() = runTest {
        val restoredAuth = FakeAuthRepository(AuthState.Authenticated(alice))
        assertEquals(SessionGitAuthorIdentityReader(restoredAuth).read(), SessionGitAuthorIdentityReader(restoredAuth).read())
        restoredAuth.state.value = AuthState.Loading
        assertNull(SessionGitAuthorIdentityReader(restoredAuth).read())
    }

    @Test
    fun invalidAccountCannotSupplyACommitIdentity() = runTest {
        val auth = FakeAuthRepository(AuthState.Authenticated(alice.copy(login = "bad@name")))
        assertNull(SessionGitAuthorIdentityReader(auth).read())
        auth.state.value = AuthState.Authenticated(alice.copy(id = 0))
        assertNull(SessionGitAuthorIdentityReader(auth).read())
    }
}
