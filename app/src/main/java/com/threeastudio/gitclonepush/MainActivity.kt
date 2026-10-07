package com.threeastudio.gitclonepush

import android.os.Bundle
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.threeastudio.gitclonepush.app.GitClientApp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.threeastudio.gitclonepush.core.model.ThemeMode
import com.threeastudio.gitclonepush.ui.theme.GitClonePushTheme
import com.threeastudio.gitclonepush.data.auth.AuthDiagnosticEvent

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val application = application as com.threeastudio.gitclonepush.app.GitClientApplication
        intent.data?.let { callback ->
            application.container.authDiagnostics.event(
                AuthDiagnosticEvent.AUTH_ANDROID_CALLBACK_RECEIVED,
                callbackMetadata(callback, "onCreate")
            )
            application.publishOAuthCallback(callback)
        }
        setContent {
            var themeMode by remember { mutableStateOf(ThemeMode.SYSTEM) }
            GitClonePushTheme(themeMode = themeMode) {
                GitClientApp(application, themeMode, { themeMode = it })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val application = application as com.threeastudio.gitclonepush.app.GitClientApplication
        intent.data?.let { callback ->
            application.container.authDiagnostics.event(
                AuthDiagnosticEvent.AUTH_ANDROID_CALLBACK_RECEIVED,
                callbackMetadata(callback, "onNewIntent")
            )
            application.publishOAuthCallback(callback)
        }
    }

    private fun callbackMetadata(uri: android.net.Uri, source: String): Map<String, String> = mapOf(
        "source" to source,
        "scheme" to (uri.scheme ?: ""),
        "host" to (uri.host ?: ""),
        "path" to (uri.path ?: ""),
        "hasCode" to (uri.getQueryParameter("code") != null).toString(),
        "hasState" to (uri.getQueryParameter("state") != null).toString(),
        "hasError" to (uri.getQueryParameter("error") != null).toString()
    )
}
