package com.threeastudio.gitclonepush.app

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.threeastudio.gitclonepush.data.auth.AuthDiagnosticEvent
import com.threeastudio.gitclonepush.data.auth.AuthDiagnostics
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OAuthCallbackDispatcherInstrumentedTest {
    @Test
    fun onePublishedCallbackIsDeliveredOnceWithoutReplay() = runBlocking {
        val diagnostics = RecordingDiagnostics()
        val dispatcher = OAuthCallbackDispatcher(diagnostics)
        val callback = Uri.parse("gitclonepush://oauth/callback?code=code&state=state")
        val received = mutableListOf<Uri>()
        val collector: Job = launch {
            dispatcher.callbacks.take(1).toList(received)
        }

        dispatcher.publish(callback)
        dispatcher.publish(callback)
        collector.join()

        assertEquals(listOf(callback), received)
        assertEquals(1, diagnostics.events.count { it == AuthDiagnosticEvent.AUTH_CALLBACK_PUBLISHED })
        assertNull(withTimeoutOrNull(100) { dispatcher.callbacks.first() })
    }

    private class RecordingDiagnostics : AuthDiagnostics {
        val events = mutableListOf<AuthDiagnosticEvent>()

        override fun event(event: AuthDiagnosticEvent, metadata: Map<String, String>) {
            events += event
        }
    }
}
