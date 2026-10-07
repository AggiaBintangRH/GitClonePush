package com.threeastudio.gitclonepush.data.auth

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.UnknownHostException

@RunWith(AndroidJUnit4::class)
class BackendOAuthTokenExchangeGatewayInstrumentedTest {
    @Test
    fun nullOrNonStringTokenIsRejected() = runBlocking {
        for (value in listOf("null", "123", "{}", "[]")) {
            val diagnostics = RecordingDiagnostics()
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                response(chain, 200, "{\"access_token\":$value}")
            }.build()
            val gateway = BackendOAuthTokenExchangeGateway(
                config = object : OAuthBackendConfig { override val baseUrl = "https://worker.example" },
                httpClient = client,
                diagnostics = diagnostics
            )
            assertTrue(gateway.exchange(OAuthTokenExchangeRequest("code", "ignored", "verifier")).isFailure)
            assertTrue(diagnostics.events.contains(AuthDiagnosticEvent.AUTH_BACKEND_TOKEN_MISSING))
        }
    }

    @Test
    fun postsJsonRequestAndParsesWorkerToken() = runBlocking {
        val diagnostics = RecordingDiagnostics()
        var capturedRequest: okhttp3.Request? = null
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            capturedRequest = chain.request()
            response(chain, 200, "{\"access_token\":\"token\",\"token_type\":\"bearer\",\"scope\":\"repo\"}")
        }.build()
        val gateway = BackendOAuthTokenExchangeGateway(
            config = object : OAuthBackendConfig { override val baseUrl = "https://worker.example" },
            httpClient = client,
            diagnostics = diagnostics
        )

        val result = gateway.exchange(OAuthTokenExchangeRequest("auth-code", "ignored-by-backend", "pkce-verifier"))

        assertTrue(result.isSuccess)
        assertEquals("token", result.getOrThrow().accessToken)
        assertEquals("https://worker.example/oauth/token", capturedRequest?.url.toString())
        assertEquals("application/json", capturedRequest?.header("Content-Type")?.substringBefore(';'))
        val payload = JSONObject(capturedRequest?.body?.let { body ->
            val buffer = okio.Buffer()
            body.writeTo(buffer)
            buffer.readUtf8()
        }.orEmpty())
        assertEquals("auth-code", payload.getString("code"))
        assertEquals("pkce-verifier", payload.getString("code_verifier"))
        assertFalse(payload.has("client_secret"))
        assertTrue(diagnostics.events.contains(AuthDiagnosticEvent.AUTH_BACKEND_PARSE_STARTED))
        assertTrue(diagnostics.events.contains(AuthDiagnosticEvent.AUTH_BACKEND_PARSE_SUCCESS))
    }

    @Test
    fun backendFailureBecomesUnsuccessfulResult() = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            response(chain, 502, "{\"error\":\"token_exchange_failed\"}")
        }.build()
        val gateway = BackendOAuthTokenExchangeGateway(
            config = object : OAuthBackendConfig { override val baseUrl = "https://worker.example" },
            httpClient = client
        )

        val result = gateway.exchange(OAuthTokenExchangeRequest("auth-code", "ignored", "verifier"))

        assertFalse(result.isSuccess)
    }

    @Test
    fun malformedJsonProducesControlledParseFailure() = runBlocking {
        val diagnostics = RecordingDiagnostics()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            response(chain, 200, "not-json")
        }.build()
        val gateway = BackendOAuthTokenExchangeGateway(
            config = object : OAuthBackendConfig { override val baseUrl = "https://worker.example" },
            httpClient = client,
            diagnostics = diagnostics
        )

        val result = gateway.exchange(OAuthTokenExchangeRequest("auth-code", "ignored", "verifier"))

        assertFalse(result.isSuccess)
        assertTrue(diagnostics.events.contains(AuthDiagnosticEvent.AUTH_BACKEND_PARSE_STARTED))
        assertTrue(diagnostics.events.contains(AuthDiagnosticEvent.AUTH_BACKEND_PARSE_FAILED))
    }

    @Test
    fun missingAccessTokenProducesControlledFailure() = runBlocking {
        val diagnostics = RecordingDiagnostics()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            response(chain, 200, "{\"token_type\":\"bearer\",\"scope\":\"repo\"}")
        }.build()
        val gateway = BackendOAuthTokenExchangeGateway(
            config = object : OAuthBackendConfig { override val baseUrl = "https://worker.example" },
            httpClient = client,
            diagnostics = diagnostics
        )

        val result = gateway.exchange(OAuthTokenExchangeRequest("auth-code", "ignored", "verifier"))

        assertFalse(result.isSuccess)
        assertTrue(diagnostics.events.contains(AuthDiagnosticEvent.AUTH_BACKEND_TOKEN_MISSING))
        assertFalse(diagnostics.events.contains(AuthDiagnosticEvent.AUTH_BACKEND_PARSE_SUCCESS))
    }

    @Test
    fun transportFailureEmitsExceptionTypeOnly() = runBlocking {
        val diagnostics = RecordingDiagnostics()
        val client = OkHttpClient.Builder().addInterceptor {
            throw UnknownHostException("redacted-host")
        }.build()
        val gateway = BackendOAuthTokenExchangeGateway(
            config = object : OAuthBackendConfig { override val baseUrl = "https://worker.example" },
            httpClient = client,
            diagnostics = diagnostics
        )

        val result = gateway.exchange(OAuthTokenExchangeRequest("auth-code", "ignored", "verifier"))

        assertFalse(result.isSuccess)
        assertTrue(diagnostics.events.contains(AuthDiagnosticEvent.AUTH_BACKEND_TRANSPORT_FAILURE))
        assertEquals("UnknownHostException", diagnostics.metadata["exceptionType"])
    }

    private fun response(chain: Interceptor.Chain, code: Int, body: String): Response =
        Response.Builder()
            .request(chain.request())
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(if (code == 200) "OK" else "Bad Gateway")
            .body(body.toResponseBody("application/json".toMediaType()))
            .build()

    private class RecordingDiagnostics : AuthDiagnostics {
        val events = mutableListOf<AuthDiagnosticEvent>()
        val metadata = mutableMapOf<String, String>()

        override fun event(event: AuthDiagnosticEvent, metadata: Map<String, String>) {
            events += event
            this.metadata.putAll(metadata)
        }
    }
}
