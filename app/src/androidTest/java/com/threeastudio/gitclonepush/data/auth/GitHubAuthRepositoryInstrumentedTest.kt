package com.threeastudio.gitclonepush.data.auth

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.threeastudio.gitclonepush.core.model.AuthState
import com.threeastudio.gitclonepush.core.model.GitAuthorIdentity
import com.threeastudio.gitclonepush.core.security.PendingOAuthStore
import com.threeastudio.gitclonepush.core.security.PendingOAuthTransaction
import com.threeastudio.gitclonepush.core.security.SecureTokenStore
import com.threeastudio.gitclonepush.core.security.StoredAuthTokens
import com.threeastudio.gitclonepush.domain.usecase.SessionGitAuthorIdentityReader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.MediaType.Companion.toMediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GitHubAuthRepositoryInstrumentedTest {
    @Test
    fun stateMismatchDoesNotCallTokenExchange() = runBlocking {
        val gateway = FakeGateway()
        val diagnostics = RecordingDiagnostics()
        val repository = repository(gateway, diagnostics = diagnostics)
        repository.beginLogin()

        val result = repository.completeLogin(
            Uri.parse("gitclonepush://oauth/callback?code=code&state=wrong")
        )

        assertFalse(result.isSuccess)
        assertEquals(0, gateway.calls)
        assertTrue(diagnostics.events.contains(AuthDiagnosticEvent.AUTH_STATE_VALIDATION))
        assertTrue(diagnostics.events.contains(AuthDiagnosticEvent.AUTH_COMPLETE_LOGIN_FAILED))
    }

    @Test
    fun providerErrorDoesNotCallTokenExchange() = runBlocking {
        val gateway = FakeGateway()
        val diagnostics = RecordingDiagnostics()
        val repository = repository(gateway, diagnostics = diagnostics)
        val state = repository.beginLogin().getOrThrow().authorizationUri.getQueryParameter("state")

        val result = repository.completeLogin(
            Uri.parse("gitclonepush://oauth/callback?error=access_denied&error_description=cancelled&state=$state")
        )

        assertFalse(result.isSuccess)
        assertEquals(0, gateway.calls)
        assertTrue(diagnostics.events.contains(AuthDiagnosticEvent.AUTH_PROVIDER_ERROR))
        assertTrue(diagnostics.events.contains(AuthDiagnosticEvent.AUTH_COMPLETE_LOGIN_FAILED))
    }

    @Test
    fun validCallbackStoresBackendTokenAndUsesPagesRedirect() = runBlocking {
        val gateway = FakeGateway()
        val tokenStore = FakeTokenStore()
        val diagnostics = RecordingDiagnostics()
        val repository = repository(gateway, tokenStore, diagnostics = diagnostics, profileStatus = 200)
        val state = repository.beginLogin().getOrThrow().authorizationUri.getQueryParameter("state")

        val result = repository.completeLogin(
            Uri.parse("gitclonepush://oauth/callback?code=code&state=$state")
        )

        assertTrue(result.isSuccess)
        assertEquals("backend-token", tokenStore.tokens?.accessToken)
        assertNotNull(gateway.lastRequest)
        assertEquals(
            "https://aggiabintangrh.github.io/HomePageGitClonePush/oauth/callback.html",
            gateway.lastRequest?.redirectUri
        )
        assertTrue(diagnostics.events.contains(AuthDiagnosticEvent.AUTH_TOKEN_EXCHANGE_SUCCESS))
        assertTrue(diagnostics.events.contains(AuthDiagnosticEvent.AUTH_TOKEN_RECEIVED))
        assertTrue(diagnostics.events.contains(AuthDiagnosticEvent.AUTH_TOKEN_STORED))
        assertTrue(diagnostics.events.contains(AuthDiagnosticEvent.AUTH_PROFILE_LOADED))
        assertTrue(diagnostics.events.contains(AuthDiagnosticEvent.AUTH_PROFILE_PARSE_SUCCESS))
        assertTrue(diagnostics.events.contains(AuthDiagnosticEvent.AUTH_STATE_CHANGED))
    }

    @Test
    fun publicProfileEmailBecomesAutomaticCommitIdentity() = runBlocking {
        val repository = repository(
            FakeGateway(),
            profileBody = """{"id":123456,"login":"alice","name":"Alice","email":"alice@example.com","avatar_url":null}"""
        )
        val state = repository.beginLogin().getOrThrow().authorizationUri.getQueryParameter("state")

        repository.completeLogin(Uri.parse("gitclonepush://oauth/callback?code=code&state=$state")).getOrThrow()

        val profile = (repository.observeAuthState().value as AuthState.Authenticated).user
        assertEquals("alice@example.com", profile.email)
        assertEquals(
            GitAuthorIdentity("Alice", "alice@example.com"),
            SessionGitAuthorIdentityReader(repository).read()
        )
    }

    @Test
    fun nullProfileFieldsUseUsernameAndNoreplyNotLiteralNull() = runBlocking {
        val repository = repository(
            FakeGateway(),
            profileBody = """{"id":123456,"login":"alice","name":null,"email":null,"avatar_url":null}"""
        )
        val state = repository.beginLogin().getOrThrow().authorizationUri.getQueryParameter("state")

        repository.completeLogin(Uri.parse("gitclonepush://oauth/callback?code=code&state=$state")).getOrThrow()

        val profile = (repository.observeAuthState().value as AuthState.Authenticated).user
        assertNull(profile.displayName)
        assertNull(profile.email)
        assertNull(profile.avatarUrl)
        assertEquals(
            GitAuthorIdentity("alice", "123456+alice@users.noreply.github.com"),
            SessionGitAuthorIdentityReader(repository).read()
        )
    }

    @Test
    fun restoredSessionAutomaticallyProvidesCommitIdentity() = runBlocking {
        val tokenStore = FakeTokenStore().apply { tokens = StoredAuthTokens("fixture-session") }
        val repository = repository(
            FakeGateway(),
            tokenStore,
            profileBody = """{"id":123456,"login":"alice","name":"Alice","email":null}"""
        )

        assertEquals(
            GitAuthorIdentity("Alice", "123456+alice@users.noreply.github.com"),
            SessionGitAuthorIdentityReader(repository).read()
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class, DelicateCoroutinesApi::class)
    @Test
    fun profileRequestRunsOnInjectedIoDispatcher() = runBlocking {
        val dispatcher = newSingleThreadContext("profile-io-test")
        try {
            val diagnostics = RecordingDiagnostics()
            val repository = repository(
                FakeGateway(),
                diagnostics = diagnostics,
                profileStatus = 200,
                ioDispatcher = dispatcher
            )
            val state = repository.beginLogin().getOrThrow().authorizationUri.getQueryParameter("state")

            assertTrue(repository.completeLogin(Uri.parse("gitclonepush://oauth/callback?code=code&state=$state")).isSuccess)
            assertTrue(diagnostics.threadNames[AuthDiagnosticEvent.AUTH_PROFILE_RESPONSE].orEmpty().contains("profile-io-test"))
        } finally {
            dispatcher.close()
        }
    }

    @Test
    fun malformedProfileJsonProducesControlledFailure() = runBlocking {
        val diagnostics = RecordingDiagnostics()
        val repository = repository(FakeGateway(), diagnostics = diagnostics, profileBody = "not-json")
        val state = repository.beginLogin().getOrThrow().authorizationUri.getQueryParameter("state")

        assertFalse(repository.completeLogin(Uri.parse("gitclonepush://oauth/callback?code=code&state=$state")).isSuccess)
        assertTrue(diagnostics.events.contains(AuthDiagnosticEvent.AUTH_PROFILE_PARSE_FAILED))
        assertTrue(diagnostics.events.contains(AuthDiagnosticEvent.AUTH_PROFILE_FAILED))
    }

    @Test
    fun profileTransportFailureEmitsTransportDiagnostic() = runBlocking {
        val diagnostics = RecordingDiagnostics()
        val transportClient = OkHttpClient.Builder().addInterceptor {
            throw java.net.UnknownHostException("redacted")
        }.build()
        val repository = repository(FakeGateway(), diagnostics = diagnostics, httpClient = transportClient)
        val state = repository.beginLogin().getOrThrow().authorizationUri.getQueryParameter("state")

        assertFalse(repository.completeLogin(Uri.parse("gitclonepush://oauth/callback?code=code&state=$state")).isSuccess)
        assertTrue(diagnostics.events.contains(AuthDiagnosticEvent.AUTH_PROFILE_TRANSPORT_FAILURE))
    }

    @Test
    fun missingPendingTransactionEmitsFailureDiagnostics() = runBlocking {
        val gateway = FakeGateway()
        val diagnostics = RecordingDiagnostics()
        val repository = repository(gateway, diagnostics = diagnostics)

        val result = repository.completeLogin(Uri.parse("gitclonepush://oauth/callback?code=code&state=state"))

        assertFalse(result.isSuccess)
        assertEquals(0, gateway.calls)
        assertTrue(diagnostics.events.contains(AuthDiagnosticEvent.AUTH_PENDING_TRANSACTION_READ))
        assertTrue(diagnostics.events.contains(AuthDiagnosticEvent.AUTH_COMPLETE_LOGIN_FAILED))
    }

    @Test
    fun missingStatePreventsTokenExchange() = runBlocking {
        val gateway = FakeGateway()
        val diagnostics = RecordingDiagnostics()
        val repository = repository(gateway, diagnostics = diagnostics)
        repository.beginLogin()

        val result = repository.completeLogin(Uri.parse("gitclonepush://oauth/callback?code=code"))

        assertFalse(result.isSuccess)
        assertEquals(0, gateway.calls)
        assertTrue(diagnostics.events.contains(AuthDiagnosticEvent.AUTH_STATE_VALIDATION))
    }

    @Test
    fun backendFailureEmitsExchangeFailureDiagnostics() = runBlocking {
        val gateway = FakeGateway(Result.failure(IllegalStateException("AUTH_012_BACKEND_HTTP_502: rejected")))
        val diagnostics = RecordingDiagnostics()
        val repository = repository(gateway, diagnostics = diagnostics)
        val state = repository.beginLogin().getOrThrow().authorizationUri.getQueryParameter("state")

        val result = repository.completeLogin(Uri.parse("gitclonepush://oauth/callback?code=code&state=$state"))

        assertFalse(result.isSuccess)
        assertTrue(diagnostics.events.contains(AuthDiagnosticEvent.AUTH_TOKEN_EXCHANGE_FAILURE))
        assertTrue(diagnostics.events.contains(AuthDiagnosticEvent.AUTH_COMPLETE_LOGIN_FAILED))
    }

    @Test
    fun profileFailureEmitsProfileDiagnostics() = runBlocking {
        val gateway = FakeGateway()
        val diagnostics = RecordingDiagnostics()
        val repository = repository(gateway, diagnostics = diagnostics, profileStatus = 401)
        val state = repository.beginLogin().getOrThrow().authorizationUri.getQueryParameter("state")

        val result = repository.completeLogin(Uri.parse("gitclonepush://oauth/callback?code=code&state=$state"))

        assertFalse(result.isSuccess)
        assertTrue(diagnostics.events.contains(AuthDiagnosticEvent.AUTH_PROFILE_RESPONSE))
        assertTrue(diagnostics.events.contains(AuthDiagnosticEvent.AUTH_PROFILE_FAILED))
        assertTrue(diagnostics.events.contains(AuthDiagnosticEvent.AUTH_COMPLETE_LOGIN_FAILED))
    }

    private fun repository(
        gateway: FakeGateway,
        tokenStore: FakeTokenStore = FakeTokenStore(),
        diagnostics: RecordingDiagnostics = RecordingDiagnostics(),
        profileStatus: Int = 200,
        profileBody: String? = null,
        httpClient: OkHttpClient? = null,
        ioDispatcher: CoroutineDispatcher = Dispatchers.Unconfined
    ) = GitHubAuthRepository(
        tokenStore = tokenStore,
        pendingStore = tokenStore,
        pkceGenerator = object : PkceGenerator {
            override fun generate() = PkcePair("verifier", "challenge")
        },
        oauthConfig = object : GitHubOAuthConfig {
            override val clientId = "public-client-id"
            override val redirectUri = "https://aggiabintangrh.github.io/HomePageGitClonePush/oauth/callback.html"
        },
        tokenExchangeGateway = gateway,
        httpClient = httpClient ?: profileClient(profileStatus, profileBody),
        scope = CoroutineScope(Dispatchers.Unconfined),
        diagnostics = diagnostics,
        ioDispatcher = ioDispatcher
    )

    private fun profileClient(status: Int, bodyOverride: String? = null): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(status)
                .message(if (status == 200) "OK" else "Unauthorized")
                .body(
                    (bodyOverride ?: if (status == 200) {
                        "{\"id\":1,\"login\":\"demo-user\",\"name\":\"Demo\",\"avatar_url\":\"\"}"
                    } else {
                        "{\"message\":\"unauthorized\"}"
                    }).toResponseBody("application/json".toMediaType())
                )
                .build()
        }
        .build()

    private class FakeGateway : OAuthTokenExchangeGateway {
        private val result: Result<OAuthToken>

        constructor(result: Result<OAuthToken> = Result.success(OAuthToken("backend-token"))) {
            this.result = result
        }

        var calls = 0
        var lastRequest: OAuthTokenExchangeRequest? = null

        override suspend fun exchange(request: OAuthTokenExchangeRequest): Result<OAuthToken> {
            calls++
            lastRequest = request
            return result
        }
    }

    private class RecordingDiagnostics : AuthDiagnostics {
        val events = mutableListOf<AuthDiagnosticEvent>()
        val threadNames = mutableMapOf<AuthDiagnosticEvent, String>()

        override fun event(event: AuthDiagnosticEvent, metadata: Map<String, String>) {
            events += event
            threadNames[event] = Thread.currentThread().name
        }
    }

    private class FakeTokenStore : SecureTokenStore, PendingOAuthStore {
        var tokens: StoredAuthTokens? = null
        private var pending: PendingOAuthTransaction? = null

        override suspend fun read() = tokens
        override suspend fun write(tokens: StoredAuthTokens) { this.tokens = tokens }
        override suspend fun clear() { tokens = null }
        override suspend fun readPending() = pending
        override suspend fun writePending(transaction: PendingOAuthTransaction) { pending = transaction }
        override suspend fun clearPending() { pending = null }
    }
}
