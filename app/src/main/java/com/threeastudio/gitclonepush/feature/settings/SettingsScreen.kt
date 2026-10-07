package com.threeastudio.gitclonepush.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.threeastudio.gitclonepush.core.designsystem.components.GitTopAppBar
import com.threeastudio.gitclonepush.core.designsystem.components.SectionCard
import com.threeastudio.gitclonepush.core.designsystem.components.LoadingOverlay
import com.threeastudio.gitclonepush.core.model.ThemeMode
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.threeastudio.gitclonepush.domain.repository.GitAuthorIdentityReader
import com.threeastudio.gitclonepush.domain.repository.AuthRepository

@Composable
fun SettingsScreen(
    username: String,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    identityReader: GitAuthorIdentityReader,
    authRepository: AuthRepository,
    viewModel: SettingsViewModel = viewModel(factory = SettingsViewModelFactory(identityReader, authRepository))
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    Scaffold(topBar = { GitTopAppBar("Settings") }) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding)
                .consumeWindowInsets(padding).imePadding()
                .verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            SectionCard("Appearance") {
                ThemeMode.entries.forEach { mode ->
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (themeMode == mode) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .heightIn(min = 56.dp)
                                .selectable(themeMode == mode, role = Role.RadioButton, onClick = { onThemeModeChange(mode) })
                                .padding(horizontal = 12.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            RadioButton(themeMode == mode, onClick = null)
                            Text(mode.name.lowercase().replaceFirstChar { it.uppercase() })
                        }
                    }
                }
            }
            SectionCard("GitHub Account") {
                Text("Logged in as: ${username.ifBlank { "Unknown user" }}")
                OutlinedButton(onClick = viewModel::signOut, enabled = !state.isSigningOut) {
                    Text(if (state.isSigningOut) "Signing out…" else "Sign Out")
                }
                state.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
            SectionCard("Git author identity") {
                Text(
                    if (state.isLoadingIdentity) "Loading your GitHub identity…"
                    else if (state.authorName.isBlank()) "Sign in to GitHub to load your commit identity."
                    else "Automatically set from your signed-in GitHub account. Editing is disabled.",
                    style = MaterialTheme.typography.bodyMedium
                )
                OutlinedTextField(
                    value = state.authorName,
                    onValueChange = {},
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Author name") },
                    singleLine = true,
                    readOnly = true
                )
                OutlinedTextField(
                    value = state.authorEmail,
                    onValueChange = {},
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Author email") },
                    singleLine = true,
                    readOnly = true
                )
            }
            SectionCard("About") { Text("App Version: 0.1.0") }
        }
    }
    LoadingOverlay(if (state.isSigningOut) "Signing out securely…" else if (state.isLoadingIdentity) "Loading your GitHub identity…" else null)
}

class SettingsViewModelFactory(
    private val identityReader: GitAuthorIdentityReader,
    private val authRepository: AuthRepository
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = SettingsViewModel(identityReader, authRepository) as T
}
