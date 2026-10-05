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

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val application = application as com.threeastudio.gitclonepush.app.GitClientApplication
        intent.data?.let { application.publishOAuthCallback(it) }
        setContent {
            var themeMode by remember { mutableStateOf(ThemeMode.SYSTEM) }
            GitClonePushTheme(themeMode = themeMode) {
                GitClientApp(application, themeMode, { themeMode = it })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.data?.let { (application as com.threeastudio.gitclonepush.app.GitClientApplication).publishOAuthCallback(it) }
    }
}
