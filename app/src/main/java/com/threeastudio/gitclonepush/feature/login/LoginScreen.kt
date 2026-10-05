package com.threeastudio.gitclonepush.feature.login

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import android.net.Uri
import com.threeastudio.gitclonepush.core.designsystem.components.GitPrimaryButton

@Composable
fun LoginScreen(onOpenBrowser: (Uri) -> Unit, viewModel: LoginViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(Icons.Default.Code, null, Modifier.size(72.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.size(20.dp)); Text("Git Client", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        Text("Manage your repositories directly from Android.", textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
        Spacer(Modifier.size(36.dp)); GitPrimaryButton(if (state.isSigningIn) "Connecting…" else "Continue with GitHub", { viewModel.continueWithGitHub(onOpenBrowser) }, !state.isSigningIn, Modifier.fillMaxWidth())
        state.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 12.dp)) }
        Text("You'll be redirected to GitHub to authorize access.", style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 14.dp))
        Text("Your GitHub password is never entered into this app.", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 28.dp))
    }
}

@Preview(showBackground = true)
@Composable private fun LoginScreenPreview() { Text("Login preview requires a ViewModel") }
