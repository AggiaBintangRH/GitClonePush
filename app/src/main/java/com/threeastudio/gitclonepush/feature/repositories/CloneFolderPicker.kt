package com.threeastudio.gitclonepush.feature.repositories

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.core.net.toUri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.threeastudio.gitclonepush.data.storage.SharedCloneStorage

/** Owns Android permission/picker UI; destination validation and clone remain in the ViewModel. */
@Composable
fun rememberCloneFolderPicker(
    onFolderSelected: (String, String) -> Unit,
    onError: (String) -> Unit
): (String) -> Unit {
    val context = LocalContext.current
    var pendingRepositoryId by rememberSaveable { mutableStateOf<String?>(null) }
    var showAccessExplanation by rememberSaveable { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val repositoryId = pendingRepositoryId
        pendingRepositoryId = null
        if (uri != null && repositoryId != null) onFolderSelected(repositoryId, uri.toString())
    }
    val openPicker: () -> Unit = {
        try { picker.launch(null) }
        catch (_: ActivityNotFoundException) { pendingRepositoryId = null; onError("No folder picker is available on this device.") }
    }
    val accessSettings = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (SharedCloneStorage.hasAccess(context)) openPicker()
        else { pendingRepositoryId = null; onError("File access was not allowed. Enable it to clone into a folder on your device.") }
    }
    val legacyPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        if (allowed) openPicker()
        else { pendingRepositoryId = null; onError("Storage permission is required to clone into the selected folder.") }
    }
    if (showAccessExplanation) {
        AlertDialog(
            onDismissRequest = { showAccessExplanation = false; pendingRepositoryId = null },
            title = { Text("Clone to a device folder") },
            text = { Text("Allow Git Client to manage files so Git can clone, track your edits, and commit changes in the folder you choose.") },
            dismissButton = { TextButton(onClick = { showAccessExplanation = false; pendingRepositoryId = null }) { Text("Cancel") } },
            confirmButton = { TextButton(onClick = {
                showAccessExplanation = false
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    try {
                        accessSettings.launch(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, "package:${context.packageName}".toUri()))
                    } catch (_: ActivityNotFoundException) {
                        try { accessSettings.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
                        catch (_: ActivityNotFoundException) { pendingRepositoryId = null; onError("File access settings are unavailable on this device.") }
                    }
                } else legacyPermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }) { Text("Allow file access") } }
        )
    }
    return { repositoryId ->
        if (pendingRepositoryId == null) {
            when {
                !SharedCloneStorage.supported() -> onError("Choosing a shared clone folder requires Android 11 or newer on this device. Existing repositories remain available.")
                SharedCloneStorage.hasAccess(context) -> { pendingRepositoryId = repositoryId; openPicker() }
                else -> { pendingRepositoryId = repositoryId; showAccessExplanation = true }
            }
        }
    }
}
