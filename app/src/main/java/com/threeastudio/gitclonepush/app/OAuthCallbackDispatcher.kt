package com.threeastudio.gitclonepush.app

import android.net.Uri
import com.threeastudio.gitclonepush.data.auth.AuthDiagnosticEvent
import com.threeastudio.gitclonepush.data.auth.AuthDiagnostics
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow

/** Delivers each OAuth callback once without replaying it during recomposition. */
class OAuthCallbackDispatcher(private val diagnostics: AuthDiagnostics) {
    private val callbackChannel = Channel<Uri>(Channel.BUFFERED)
    private var lastPublishedCallback: Uri? = null

    val callbacks = callbackChannel.receiveAsFlow()

    @Synchronized
    fun publish(uri: Uri) {
        if (uri == lastPublishedCallback) return
        lastPublishedCallback = uri
        diagnostics.event(
            AuthDiagnosticEvent.AUTH_CALLBACK_PUBLISHED,
            mapOf("scheme" to (uri.scheme ?: ""), "host" to (uri.host ?: ""), "path" to (uri.path ?: ""))
        )
        callbackChannel.trySend(uri)
    }
}
