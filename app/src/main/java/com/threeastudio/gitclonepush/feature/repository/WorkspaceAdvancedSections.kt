@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.threeastudio.gitclonepush.feature.repository

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.threeastudio.gitclonepush.core.model.GitMutationState
import com.threeastudio.gitclonepush.core.model.RemoteOperationState

@Composable
internal fun RebaseSection(
    state: RepositoryUiState,
    onSelect: (String?) -> Unit,
    onStart: () -> Unit,
    onContinue: () -> Unit,
    onSkip: () -> Unit,
    onAbort: () -> Unit,
    onPrepareInteractive: () -> Unit,
    onInteractiveAction: (Int, com.threeastudio.gitclonepush.core.model.RebaseAction) -> Unit,
    onMoveInteractive: (Int, Int) -> Unit,
    onInteractiveMessage: (Int, String) -> Unit,
    onStartInteractive: () -> Unit
) {
    val rebase = state.rebaseState
    val active = rebase.operationState != com.threeastudio.gitclonepush.core.model.RebaseOperationState.IDLE
    val busy = state.mutationState != GitMutationState.IDLE ||
        state.remoteOperationState != RemoteOperationState.IDLE ||
        state.branchOperationState != com.threeastudio.gitclonepush.core.model.BranchOperationState.IDLE ||
        state.mergeState.repositoryState != com.threeastudio.gitclonepush.core.model.MergeRepositoryState.SAFE
    val candidates = state.branches.filter {
        it.kind == com.threeastudio.gitclonepush.core.model.BranchKind.LOCAL && !it.isCurrent
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (!active) {
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                candidates.forEach { branch ->
                    FilterChip(
                        selected = state.rebaseTargetBranch == branch.name,
                        onClick = { onSelect(branch.name) },
                        enabled = !busy,
                        label = { Text(branch.name) }
                    )
                }
                Button(onClick = onStart, enabled = !busy && state.rebaseTargetBranch != null) { Text("Rebase") }
            }
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onPrepareInteractive, enabled = !busy) { Text("Prepare interactive") }
                Button(onClick = onStartInteractive, enabled = !busy && state.interactivePlan.isNotEmpty()) { Text("Run interactive") }
            }
            state.interactivePlan.forEachIndexed { index, item ->
                val nextAction = when (item.action) {
                    com.threeastudio.gitclonepush.core.model.RebaseAction.PICK -> com.threeastudio.gitclonepush.core.model.RebaseAction.REWORD
                    com.threeastudio.gitclonepush.core.model.RebaseAction.REWORD -> com.threeastudio.gitclonepush.core.model.RebaseAction.SQUASH
                    com.threeastudio.gitclonepush.core.model.RebaseAction.SQUASH -> com.threeastudio.gitclonepush.core.model.RebaseAction.FIXUP
                    com.threeastudio.gitclonepush.core.model.RebaseAction.FIXUP -> com.threeastudio.gitclonepush.core.model.RebaseAction.DROP
                    com.threeastudio.gitclonepush.core.model.RebaseAction.DROP -> com.threeastudio.gitclonepush.core.model.RebaseAction.PICK
                }
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${item.shortId}  ${item.message}", style = MaterialTheme.typography.bodyMedium)
                    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { onInteractiveAction(index, nextAction) }, enabled = !busy) { Text(item.action.name) }
                        Button(onClick = { onMoveInteractive(index, -1) }, enabled = !busy && index > 0) { Text("↑") }
                        Button(onClick = { onMoveInteractive(index, 1) }, enabled = !busy && index < state.interactivePlan.lastIndex) { Text("↓") }
                    }
                    if (item.action == com.threeastudio.gitclonepush.core.model.RebaseAction.REWORD || item.action == com.threeastudio.gitclonepush.core.model.RebaseAction.SQUASH) {
                        OutlinedTextField(item.editedMessage.orEmpty(), { onInteractiveMessage(index, it) }, Modifier.fillMaxWidth(), label = { Text("Message") }, singleLine = true, enabled = !busy)
                    }
                }
            }
        } else {
            val label = when (rebase.operationState) {
                com.threeastudio.gitclonepush.core.model.RebaseOperationState.CONFLICTED -> "Resolve ${rebase.conflictedPaths.size} conflict(s)"
                com.threeastudio.gitclonepush.core.model.RebaseOperationState.STOPPED_FOR_EDIT -> "Rebase stopped for edit"
                else -> "Rebase in progress"
            }
            Text(label)
            rebase.conflictedPaths.forEach { path -> Text(path, Modifier.padding(start = 8.dp)) }
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onContinue, enabled = rebase.canContinue && !busy) { Text("Continue") }
                Button(onClick = onSkip, enabled = rebase.canSkip && !busy) { Text("Skip") }
                Button(onClick = onAbort, enabled = rebase.canAbort && !busy) { Text("Abort") }
            }
        }
    }
}

@Composable
internal fun MergeSection(
    state: RepositoryUiState,
    onSelect: (String?) -> Unit,
    onMerge: () -> Unit,
    onOurs: (String) -> Unit,
    onTheirs: (String) -> Unit,
    onContinue: () -> Unit,
    onAbort: () -> Unit,
    onMessageChange: (String) -> Unit
) {
    val active = state.mergeState.repositoryState != com.threeastudio.gitclonepush.core.model.MergeRepositoryState.SAFE
    val candidates = state.branches.filter { it.kind == com.threeastudio.gitclonepush.core.model.BranchKind.LOCAL && !it.isCurrent }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (!active) {
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                candidates.forEach { branch ->
                    FilterChip(
                        selected = state.mergeSourceBranch == branch.name,
                        onClick = { onSelect(branch.name) },
                        label = { Text(branch.name) }
                    )
                }
                Button(onClick = onMerge, enabled = state.mergeSourceBranch != null) { Text("Merge") }
            }
        } else {
            Text(if (state.mergeState.conflictedFiles.isEmpty()) "Merge ready to continue" else "Resolve ${state.mergeState.conflictedFiles.size} conflict(s)")
            state.mergeState.conflictedFiles.forEach { conflict ->
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(conflict.path)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { onOurs(conflict.path) }) { Text("Ours") }
                        Button(onClick = { onTheirs(conflict.path) }) { Text("Theirs") }
                    }
                }
            }
            OutlinedTextField(state.commitMessage, onMessageChange, Modifier.fillMaxWidth(), label = { Text("Merge commit message") }, singleLine = true)
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onContinue, enabled = state.mergeState.canContinue) { Text("Continue merge") }
                Button(onClick = onAbort) { Text("Abort merge") }
            }
        }
    }
}

@Composable
internal fun BranchSection(
    state: RepositoryUiState,
    onRefresh: () -> Unit,
    onCreate: () -> Unit,
    onCreateAndCheckout: () -> Unit,
    onCheckout: (String) -> Unit,
    onTrack: (String, String) -> Unit,
    onRename: (String) -> Unit,
    onDelete: (String) -> Unit,
    onSelect: (String?) -> Unit,
    onBranchInput: (String) -> Unit,
    onRenameInput: (String) -> Unit
) {
    val busy = state.branchOperationState != com.threeastudio.gitclonepush.core.model.BranchOperationState.IDLE || state.mutationState != GitMutationState.IDLE || state.remoteOperationState != RemoteOperationState.IDLE
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Local & remote", style = MaterialTheme.typography.titleSmall)
            Button(onClick = onRefresh, enabled = !busy) { Text(if (state.branchOperationState == com.threeastudio.gitclonepush.core.model.BranchOperationState.REFRESHING) "Refreshing…" else "Refresh") }
        }
        Text(if (state.remoteState.isDetachedHead) "Detached HEAD" else "Current: ${state.remoteState.branch ?: state.branch?.name ?: "unknown"}")
        OutlinedTextField(state.branchInput, onBranchInput, Modifier.fillMaxWidth(), label = { Text("New branch name") }, singleLine = true, enabled = !busy)
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onCreate, enabled = !busy && state.branchInput.isNotBlank()) { Text("Create") }
            Button(onClick = onCreateAndCheckout, enabled = !busy && state.branchInput.isNotBlank()) { Text("Create + checkout") }
        }
        state.branches.filter { it.kind == com.threeastudio.gitclonepush.core.model.BranchKind.LOCAL }.forEach { branch ->
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(if (branch.isCurrent) "✓ ${branch.name}" else branch.name, style = MaterialTheme.typography.bodyLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!branch.isCurrent) TextButton(onClick = { onCheckout(branch.name) }, enabled = !busy) { Text("Checkout") }
                    TextButton(onClick = { onSelect(branch.name) }, enabled = !busy) { Text(if (state.selectedBranchName == branch.name) "Selected" else "More") }
                }
            }
        }
        state.branches.filter { it.kind == com.threeastudio.gitclonepush.core.model.BranchKind.REMOTE }.forEach { branch ->
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(branch.name, style = MaterialTheme.typography.bodyLarge)
                TextButton(onClick = { onTrack(branch.fullRef, branch.remoteBranchName ?: branch.name.substringAfter('/')) }, enabled = !busy) { Text("Track") }
            }
        }
        state.selectedBranchName?.let { selected ->
            OutlinedTextField(state.branchRenameInput, onRenameInput, Modifier.fillMaxWidth(), label = { Text("Rename $selected to") }, singleLine = true, enabled = !busy)
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onRename(selected) }, enabled = !busy && state.branchRenameInput.isNotBlank()) { Text("Rename") }
                Button(onClick = { onDelete(selected) }, enabled = !busy) { Text("Delete") }
            }
        }
    }
}

@Composable
internal fun HistoryMutationSection(state: RepositoryUiState, onContinueRevert: () -> Unit, onAbortRevert: () -> Unit, onContinueCherryPick: () -> Unit, onSkipCherryPick: () -> Unit, onAbortCherryPick: () -> Unit) {
    val mutation = state.historyMutation
    if (mutation.kind == com.threeastudio.gitclonepush.core.model.HistoryMutationKind.NONE) return
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(if (mutation.kind == com.threeastudio.gitclonepush.core.model.HistoryMutationKind.REVERTING) "Revert in progress" else "Cherry-pick in progress")
        mutation.conflictedPaths.forEach { Text(it, Modifier.padding(start = 8.dp)) }
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (mutation.kind == com.threeastudio.gitclonepush.core.model.HistoryMutationKind.REVERTING) {
                Button(onClick = onContinueRevert, enabled = mutation.canContinue) { Text("Continue revert") }
                Button(onClick = onAbortRevert, enabled = mutation.canAbort) { Text("Abort revert") }
            } else {
                Button(onClick = onContinueCherryPick, enabled = mutation.canContinue) { Text("Continue cherry-pick") }
                Button(onClick = onSkipCherryPick, enabled = mutation.canSkip) { Text("Skip") }
                Button(onClick = onAbortCherryPick, enabled = mutation.canAbort) { Text("Abort") }
            }
        }
    }
}

@Composable
internal fun StashSection(state: RepositoryUiState, onMessageChange: (String) -> Unit, onIncludeUntrackedChange: (Boolean) -> Unit, onCreate: () -> Unit, onApply: (Int) -> Unit, onPop: (Int) -> Unit, onDrop: (Int) -> Unit) {
    var pendingDrop by remember { androidx.compose.runtime.mutableStateOf<Int?>(null) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(state.stashMessage, onMessageChange, Modifier.fillMaxWidth(), label = { Text("Optional stash message") }, singleLine = true)
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Checkbox(state.stashIncludeUntracked, onIncludeUntrackedChange)
            Text("Include untracked files", Modifier.weight(1f))
        }
        Button(onClick = onCreate, modifier = Modifier.fillMaxWidth()) { Text("Create stash") }
        state.stashes.forEach { stash ->
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("stash@{${stash.index}} ${stash.shortId} ${stash.message.orEmpty()}")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { onApply(stash.index) }) { Text("Apply") }
                    TextButton(onClick = { onPop(stash.index) }) { Text("Pop") }
                    TextButton(onClick = { pendingDrop = stash.index }) { Text("Drop") }
                }
            }
        }
    }
    pendingDrop?.let { index -> AlertDialog(onDismissRequest = { pendingDrop = null }, title = { Text("Drop stash?") }, text = { Text("This removes the stash entry without changing the working tree.") }, confirmButton = { TextButton(onClick = { onDrop(index); pendingDrop = null }) { Text("Drop") } }, dismissButton = { TextButton(onClick = { pendingDrop = null }) { Text("Cancel") } }) }
}
