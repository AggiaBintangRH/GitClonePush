package com.threeastudio.gitclonepush.feature.login

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.res.painterResource
import com.threeastudio.gitclonepush.R
import androidx.compose.ui.graphics.Color
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
import android.net.Uri
import com.threeastudio.gitclonepush.core.designsystem.components.GitPrimaryButton
import com.threeastudio.gitclonepush.core.designsystem.components.LoadingOverlay
import com.threeastudio.gitclonepush.core.designsystem.components.InlineError

@Composable
fun LoginScreen(onOpenBrowser: (Uri) -> Unit, viewModel: LoginViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LoginContent(state, { viewModel.continueWithGitHub(onOpenBrowser) }, viewModel::cancelLogin)
}

@Composable
private fun LoginContent(state: LoginUiState, onSignIn: () -> Unit, onCancel: () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = maxHeight).padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(painterResource(R.drawable.gitclient_logo), null, Modifier.size(72.dp), tint = Color.Unspecified)
            Spacer(Modifier.size(20.dp)); Text("Git Client", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            Text("Manage your repositories directly from Android.", textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            Spacer(Modifier.size(36.dp)); GitPrimaryButton(if (state.isSigningIn) "Connecting…" else "Continue with GitHub", onSignIn, modifier = Modifier.fillMaxWidth(), enabled = !state.isSigningIn)
            state.errorMessage?.let { InlineError(it, Modifier.padding(top = 12.dp)) }
            Text("You'll be redirected to GitHub to authorize access.", style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 14.dp))
            Text("Your GitHub password is never entered into this app.", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 28.dp))
        }
    }
    LoadingOverlay(state.progressMessage, if (state.phase == LoginPhase.AWAITING_AUTHORIZATION) onCancel else null)
}

@Preview(showBackground = true)
@Composable private fun LoginScreenPreview() { MaterialTheme { LoginContent(LoginUiState(), {}, {}) } }
