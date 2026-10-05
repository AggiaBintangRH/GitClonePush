package com.threeastudio.gitclonepush.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Button
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.threeastudio.gitclonepush.core.designsystem.components.GitTopAppBar
import com.threeastudio.gitclonepush.core.model.ThemeMode
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.threeastudio.gitclonepush.domain.repository.GitAuthorIdentityStore

@Composable
fun SettingsScreen(username: String, themeMode: ThemeMode, onThemeModeChange: (ThemeMode) -> Unit, onSignOut: () -> Unit, authorStore: GitAuthorIdentityStore, viewModel: SettingsViewModel = viewModel(factory = SettingsViewModelFactory(authorStore))) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    Scaffold(topBar = { GitTopAppBar("Settings") }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Text("Appearance", style = androidx.compose.material3.MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { ThemeMode.entries.forEach { mode -> Row { RadioButton(themeMode == mode, { onThemeModeChange(mode) }); Text(mode.name.lowercase().replaceFirstChar { it.uppercase() }, modifier = Modifier.padding(top = 12.dp, end = 8.dp)) } } }
            HorizontalDivider(); Text("GitHub Account", style = androidx.compose.material3.MaterialTheme.typography.titleMedium); Text("Logged in as: ${username.ifBlank { "Unknown user" }}")
            androidx.compose.material3.OutlinedButton(onClick = onSignOut) { Text("Sign Out") }
            Text("Git author identity", style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
            OutlinedTextField(state.authorName, viewModel::updateName, Modifier.fillMaxWidth(), label = { Text("Author name") }, singleLine = true)
            OutlinedTextField(state.authorEmail, viewModel::updateEmail, Modifier.fillMaxWidth(), label = { Text("Author email") }, singleLine = true)
            Button(viewModel::save, enabled = state.authorName.isNotBlank() && state.authorEmail.isNotBlank()) { Text(if (state.saved) "Saved" else "Save Git identity") }
            HorizontalDivider(); Text("About", style = androidx.compose.material3.MaterialTheme.typography.titleMedium); Text("App Version: 0.1.0")
        }
    }
}

class SettingsViewModelFactory(private val store: GitAuthorIdentityStore) : androidx.lifecycle.ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST") override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T = SettingsViewModel(store) as T
}
