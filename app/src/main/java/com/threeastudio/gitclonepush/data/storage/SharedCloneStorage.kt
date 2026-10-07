package com.threeastudio.gitclonepush.data.storage

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.net.toUri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import androidx.core.content.ContextCompat
import com.threeastudio.gitclonepush.core.model.GitRepository
import com.threeastudio.gitclonepush.data.git.AndroidLocalRepositoryStore
import com.threeastudio.gitclonepush.data.git.GitDiagnostics
import com.threeastudio.gitclonepush.data.git.GitDiagnosticEvent
import com.threeastudio.gitclonepush.data.git.NoOpGitDiagnostics
import com.threeastudio.gitclonepush.domain.repository.CloneDestinationError
import com.threeastudio.gitclonepush.domain.repository.CloneDestinationException
import com.threeastudio.gitclonepush.domain.repository.CloneDestinationSelector
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

object SharedCloneStorage {
    const val EXTERNAL_DOCUMENTS_AUTHORITY = "com.android.externalstorage.documents"

    // Android 10's scoped storage cannot grant raw-path Git access with this targetSdk.
    fun supported(): Boolean = Build.VERSION.SDK_INT != Build.VERSION_CODES.Q

    fun hasAccess(context: Context): Boolean = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> Environment.isExternalStorageManager()
        Build.VERSION.SDK_INT == Build.VERSION_CODES.Q -> false
        else -> ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }

    @Suppress("DEPRECATION")
    fun volumeRoot(context: Context, volumeId: String): File? {
        if (volumeId.equals("primary", ignoreCase = true)) return Environment.getExternalStorageDirectory().canonicalFile
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return context.getSystemService(StorageManager::class.java).storageVolumes
                .firstOrNull { it.uuid.equals(volumeId, ignoreCase = true) }?.directory?.canonicalFile
        }
        return context.getExternalFilesDirs(null).filterNotNull().mapNotNull { files ->
            val root = files.parentFile?.parentFile?.parentFile?.parentFile
            root?.takeIf { it.name.equals(volumeId, ignoreCase = true) }?.canonicalFile
        }.firstOrNull()
    }
}

class AndroidCloneDestinationSelector(
    private val context: Context,
    private val store: AndroidLocalRepositoryStore,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val diagnostics: GitDiagnostics = NoOpGitDiagnostics
) : CloneDestinationSelector {
    override suspend fun select(repository: GitRepository, parentTreeUri: String): String = withContext(ioDispatcher) {
        try {
            resolveSelection(repository, parentTreeUri)
        } catch (error: CloneDestinationException) {
            diagnostics.event(GitDiagnosticEvent.CLONE_DESTINATION_REJECTED, mapOf("category" to error.category.name))
            throw error
        } catch (error: SecurityException) {
            throw CloneDestinationException(CloneDestinationError.STORAGE_ACCESS_REQUIRED, error)
        } catch (error: IllegalArgumentException) {
            throw CloneDestinationException(CloneDestinationError.INVALID_FOLDER, error)
        } catch (error: IOException) {
            throw CloneDestinationException(CloneDestinationError.STORAGE_UNAVAILABLE, error)
        }
    }

    private suspend fun resolveSelection(repository: GitRepository, parentTreeUri: String): String {
        if (!SharedCloneStorage.hasAccess(context)) throw CloneDestinationException(CloneDestinationError.STORAGE_ACCESS_REQUIRED)
        val uri = parentTreeUri.toUri()
        if (uri.scheme != "content" || uri.authority != SharedCloneStorage.EXTERNAL_DOCUMENTS_AUTHORITY || !DocumentsContract.isTreeUri(uri)) {
            throw CloneDestinationException(CloneDestinationError.UNSUPPORTED_LOCATION)
        }
        val documentId = DocumentsContract.getTreeDocumentId(uri)
        val separator = documentId.indexOf(':')
        if (separator < 1) throw CloneDestinationException(CloneDestinationError.INVALID_FOLDER)
        val root = SharedCloneStorage.volumeRoot(context, documentId.substring(0, separator))
            ?: throw CloneDestinationException(CloneDestinationError.STORAGE_UNAVAILABLE)
        val relative = documentId.substring(separator + 1)
        val segments = relative.split('/')
        if (relative.isBlank() || segments.any { it.isBlank() || it in setOf(".", "..", ".git", "Android") || it.contains('\\') || it.contains('\u0000') }) {
            throw CloneDestinationException(CloneDestinationError.INVALID_FOLDER)
        }
        val parent = File(root, relative)
        if (parent.absoluteFile != parent.canonicalFile || !parent.canonicalPath.startsWith(root.canonicalPath + File.separator)) {
            throw CloneDestinationException(CloneDestinationError.INVALID_FOLDER)
        }
        val destination = store.selectDestination(repository, parent, parentTreeUri)
        diagnostics.event(GitDiagnosticEvent.CLONE_DESTINATION_SELECTED, mapOf("repositoryId" to repository.id))
        return destination.absolutePath
    }
}
