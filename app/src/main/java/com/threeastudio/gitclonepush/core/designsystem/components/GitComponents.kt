package com.threeastudio.gitclonepush.core.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.tooling.preview.Preview
import com.threeastudio.gitclonepush.core.model.CloneStatus
import com.threeastudio.gitclonepush.core.model.Commit
import com.threeastudio.gitclonepush.core.model.FileChange
import com.threeastudio.gitclonepush.core.model.FileChangeStatus
import com.threeastudio.gitclonepush.core.model.GitRepository
import com.threeastudio.gitclonepush.core.model.RepositoryCloneState
import com.threeastudio.gitclonepush.core.model.RepositoryFile
import com.threeastudio.gitclonepush.core.model.RepositoryVisibility
import com.threeastudio.gitclonepush.ui.theme.Amber
import com.threeastudio.gitclonepush.ui.theme.Green
import com.threeastudio.gitclonepush.ui.theme.Red

@Composable fun GitPrimaryButton(text: String, onClick: () -> Unit, enabled: Boolean = true, modifier: Modifier = Modifier) = Button(onClick, enabled = enabled, modifier = modifier.fillMaxWidth()) { Text(text) }
@Composable fun GitSecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) = OutlinedButton(onClick, modifier = modifier) { Text(text) }

@Composable
fun GitTopAppBar(title: String, onSettingsClick: (() -> Unit)? = null, showBack: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        onSettingsClick?.let { IconButton(onClick = it) { Icon(if (showBack) Icons.AutoMirrored.Filled.ArrowBack else Icons.Default.Settings, if (showBack) "Go back" else "Open settings") } }
    }
}

@Composable
fun RepositoryCard(repository: GitRepository, cloneState: RepositoryCloneState, onClick: () -> Unit, onCloneClick: () -> Unit, modifier: Modifier = Modifier) {
    Card(modifier.fillMaxWidth().clickable(onClick = onClick), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Code, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.size(10.dp)); Text(repository.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text(if (repository.visibility == RepositoryVisibility.PRIVATE) "Private" else "Public", style = MaterialTheme.typography.labelMedium)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { BranchChip(repository.language ?: "Unknown"); BranchChip(repository.defaultBranch) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(repository.updatedAt, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                when (cloneState.status) {
                    CloneStatus.NOT_CLONED -> GitSecondaryButton("Clone", onCloneClick)
                    CloneStatus.CLONING -> Text("Cloning ${cloneState.progress}%", style = MaterialTheme.typography.labelLarge)
                    CloneStatus.CLONED -> Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.CheckCircle, null, tint = Green); Spacer(Modifier.size(4.dp)); Text("Cloned") }
                    CloneStatus.ERROR -> GitSecondaryButton("Retry", onCloneClick)
                }
            }
            if (cloneState.status == CloneStatus.CLONING) LinearProgressIndicator({ cloneState.progress / 100f }, Modifier.fillMaxWidth())
        }
    }
}

@Composable fun BranchChip(text: String) = AssistChip(onClick = {}, label = { Text(text) })

@Composable
fun GitStatusBadge(status: FileChangeStatus) {
    val (label, color) = when (status) { FileChangeStatus.MODIFIED -> "M" to Amber; FileChangeStatus.ADDED -> "A" to Green; FileChangeStatus.DELETED -> "D" to Red; FileChangeStatus.RENAMED -> "R" to MaterialTheme.colorScheme.primary }
    Text(label, color = color, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { contentDescription = status.name.lowercase() })
}

@Composable fun FileChangeItem(change: FileChange, modifier: Modifier = Modifier) = Row(modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) { GitStatusBadge(change.status); Spacer(Modifier.size(12.dp)); Text(change.path) }

@Composable fun CommitListItem(commit: Commit, modifier: Modifier = Modifier) = Row(modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
    Icon(Icons.Default.Upload, null, tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.size(12.dp)); Column(Modifier.weight(1f)) { Text(commit.message, fontWeight = FontWeight.Medium); Text("${commit.hash} · ${commit.author}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }; Text(commit.time, style = MaterialTheme.typography.labelSmall)
}

@Composable fun FileTreeItem(file: RepositoryFile, modifier: Modifier = Modifier) = Row(modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) { Icon(if (file.isDirectory) Icons.Default.Folder else Icons.Default.Code, null, tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.size(12.dp)); Text(file.path) }

@Composable fun LoadingState(message: String = "Loading…") = Column(Modifier.fillMaxWidth().padding(40.dp), horizontalAlignment = Alignment.CenterHorizontally) { CircularProgressIndicator(); Spacer(Modifier.size(16.dp)); Text(message) }
@Composable fun EmptyState(message: String) = Text(message, Modifier.fillMaxWidth().padding(40.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
@Composable fun ErrorState(message: String) = Text(message, Modifier.fillMaxWidth().padding(24.dp), color = MaterialTheme.colorScheme.error)

@Preview(showBackground = true)
@Composable private fun RepositoryCardPreview() { RepositoryCard(GitRepository("preview", "diarization-engine", "Aggia", RepositoryVisibility.PRIVATE, "Kotlin", "main", "Updated 2 hours ago"), RepositoryCloneState(), {}, {}) }

@Preview(showBackground = true)
@Composable private fun FileChangeItemPreview() { FileChangeItem(FileChange("src/main/kotlin/Vad.kt", FileChangeStatus.MODIFIED)) }

@Preview(showBackground = true)
@Composable private fun CommitListItemPreview() { CommitListItem(Commit("Improve speaker embedding", "a93fe42", "Aggia", "2 hours ago")) }
