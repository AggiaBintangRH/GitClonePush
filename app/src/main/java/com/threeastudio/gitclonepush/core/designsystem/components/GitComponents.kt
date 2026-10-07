package com.threeastudio.gitclonepush.core.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.filled.Add
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
import androidx.compose.material3.Surface
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.tooling.preview.Preview
import com.threeastudio.gitclonepush.core.model.CloneStatus
import com.threeastudio.gitclonepush.core.model.Commit
import com.threeastudio.gitclonepush.core.model.FileChange
import com.threeastudio.gitclonepush.core.model.FileChangeStatus
import com.threeastudio.gitclonepush.core.model.GitFileState
import com.threeastudio.gitclonepush.core.model.GitRepository
import com.threeastudio.gitclonepush.core.model.RepositoryCloneState
import com.threeastudio.gitclonepush.core.model.RepositoryFile
import com.threeastudio.gitclonepush.core.model.RepositoryVisibility
import com.threeastudio.gitclonepush.ui.theme.Amber
import com.threeastudio.gitclonepush.ui.theme.Green
import com.threeastudio.gitclonepush.ui.theme.Red
import java.time.Duration
import java.time.Instant

@Composable fun GitPrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) = Button(onClick, enabled = enabled, modifier = modifier.fillMaxWidth()) { Text(text) }
@Composable fun GitSecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) = OutlinedButton(onClick, modifier = modifier) { Text(text) }

@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
fun GitStateChip(label: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp))
    }
}

@Composable
fun InlineError(message: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer
    ) {
        Text(message, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(12.dp))
    }
}

@Composable
fun OperationProgress(label: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun GitTopAppBar(
    title: String,
    onSettingsClick: (() -> Unit)? = null,
    showBack: Boolean = false,
    onActionClick: (() -> Unit)? = null,
    actionContentDescription: String = "Add repository",
    actionIcon: androidx.compose.ui.graphics.vector.ImageVector = Icons.Default.Add
) {
    TopAppBar(
        title = { Text(title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = {
            if (showBack) onSettingsClick?.let { onBack ->
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Go back") }
            }
        },
        actions = {
            if (!showBack) onSettingsClick?.let { onSettings ->
                IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "Open settings") }
            }
            onActionClick?.let { IconButton(onClick = it) { Icon(actionIcon, actionContentDescription) } }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
    )
}

@Composable
fun RepositoryCard(repository: GitRepository, cloneState: RepositoryCloneState, onClick: () -> Unit, onCloneClick: () -> Unit, modifier: Modifier = Modifier) {
    Card(modifier.fillMaxWidth().clickable(onClick = onClick).semantics { contentDescription = "Repository ${repository.name}" }, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), shape = RoundedCornerShape(16.dp), elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Code, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.size(10.dp)); Column(Modifier.weight(1f)) { Text(repository.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold); Text(repository.owner, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                GitStateChip(if (repository.visibility == RepositoryVisibility.PRIVATE) "Private" else "Public")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { BranchChip(repository.language ?: "Unknown"); BranchChip(repository.defaultBranch) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(formatRepositoryUpdatedAt(repository.updatedAt), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                when (cloneState.status) {
                    CloneStatus.NOT_CLONED -> GitSecondaryButton("Clone", onCloneClick)
                    CloneStatus.CLONING -> OperationProgress("Cloning repository… ${cloneState.progress}%")
                    CloneStatus.CLONED -> Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.CheckCircle, "Cloned", tint = Green); Spacer(Modifier.size(4.dp)); Text("Cloned", style = MaterialTheme.typography.labelLarge) }
                    CloneStatus.ERROR -> GitSecondaryButton("Retry", onCloneClick)
                }
            }
            if (cloneState.status == CloneStatus.CLONING) LinearProgressIndicator({ cloneState.progress / 100f }, Modifier.fillMaxWidth())
            cloneState.errorMessage?.let { InlineError(it) }
        }
    }
}

private fun formatRepositoryUpdatedAt(value: String): String {
    val updatedAt = runCatching { Instant.parse(value) }.getOrNull() ?: return value
    val minutes = Duration.between(updatedAt, Instant.now()).toMinutes().coerceAtLeast(0)
    return when {
        minutes < 1 -> "Updated just now"
        minutes < 60 -> "Updated ${minutes} minute${if (minutes == 1L) "" else "s"} ago"
        minutes < 1_440 -> {
            val hours = minutes / 60
            "Updated ${hours} hour${if (hours == 1L) "" else "s"} ago"
        }
        minutes < 2_880 -> "Updated yesterday"
        else -> {
            val days = minutes / 1_440
            "Updated $days days ago"
        }
    }
}

@Composable fun BranchChip(text: String) = AssistChip(onClick = {}, label = { Text(text) })

@Composable
fun GitStatusBadge(status: FileChangeStatus) {
    val (label, color) = when (status) { FileChangeStatus.MODIFIED -> "M" to Amber; FileChangeStatus.ADDED -> "A" to Green; FileChangeStatus.DELETED -> "D" to Red; FileChangeStatus.RENAMED -> "R" to MaterialTheme.colorScheme.primary; FileChangeStatus.CONFLICTED -> "!" to Red }
    Text(label, color = color, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { contentDescription = status.name.lowercase() })
}

@Composable
fun FileChangeItem(change: FileChange, modifier: Modifier = Modifier) = Row(
    modifier.fillMaxWidth().padding(vertical = 10.dp),
    verticalAlignment = Alignment.CenterVertically
) {
    GitStatusBadge(change.status)
    Spacer(Modifier.size(12.dp))
    Column(Modifier.weight(1f)) {
        Text(change.path)
        Text(
            change.stateDescription(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun FileChange.stateDescription(): String = when {
    indexState != null && workTreeState != null -> "Staged index change · additional working-tree change"
    indexState != null -> "Staged"
    workTreeState == GitFileState.UNTRACKED -> "Untracked"
    workTreeState != null -> "Not staged"
    else -> "Clean"
}

@Composable fun CommitListItem(commit: Commit, modifier: Modifier = Modifier) = Row(modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
    Icon(Icons.Default.Upload, null, tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.size(12.dp)); Column(Modifier.weight(1f)) { Text(commit.message, fontWeight = FontWeight.Medium); Text("${commit.hash} · ${commit.author}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }; Text(commit.time, style = MaterialTheme.typography.labelSmall)
}

@Composable fun FileTreeItem(file: RepositoryFile, modifier: Modifier = Modifier) = Row(modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) { Icon(if (file.isDirectory) Icons.Default.Folder else Icons.Default.Code, null, tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.size(12.dp)); Text(file.path) }

@Composable fun LoadingState(message: String = "Loading…") = Column(Modifier.fillMaxWidth().padding(40.dp), horizontalAlignment = Alignment.CenterHorizontally) { CircularProgressIndicator(); Spacer(Modifier.size(16.dp)); Text(message) }
@Composable fun EmptyState(message: String) = Text(message, Modifier.fillMaxWidth().padding(40.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
@Composable
fun ErrorState(message: String, onRetry: (() -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(message, color = MaterialTheme.colorScheme.error)
        onRetry?.let {
            Spacer(Modifier.size(12.dp))
            GitSecondaryButton("Retry", it)
        }
    }
}

@Preview(showBackground = true)
@Composable private fun RepositoryCardPreview() { RepositoryCard(GitRepository("preview", "diarization-engine", "Aggia", RepositoryVisibility.PRIVATE, "Kotlin", "main", "Updated 2 hours ago"), RepositoryCloneState(), {}, {}) }

@Preview(showBackground = true)
@Composable private fun FileChangeItemPreview() { FileChangeItem(FileChange("src/main/kotlin/Vad.kt", FileChangeStatus.MODIFIED)) }

@Preview(showBackground = true)
@Composable private fun CommitListItemPreview() { CommitListItem(Commit("Improve speaker embedding", "a93fe42", "Aggia", "2 hours ago")) }
