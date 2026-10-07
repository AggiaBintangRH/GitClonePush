package com.threeastudio.gitclonepush.data.documents

import android.content.Context
import android.content.Intent
import android.content.ClipData
import android.provider.DocumentsContract
import android.os.Build
import com.threeastudio.gitclonepush.domain.repository.OpenInFilesErrorCategory
import com.threeastudio.gitclonepush.domain.repository.OpenInFilesException
import com.threeastudio.gitclonepush.domain.repository.OpenRepositoryInFiles
import com.threeastudio.gitclonepush.domain.repository.RepositoryDocumentsUriProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AndroidOpenRepositoryInFiles(
    private val context: Context,
    private val uriProvider: RepositoryDocumentsUriProvider,
    private val diagnostics: FilesDiagnostics = NoOpFilesDiagnostics,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate
) : OpenRepositoryInFiles {
    override suspend fun open(repositoryId: String): Result<Unit> {
        diagnostics.event(FilesDiagnosticEvent.OPEN_IN_FILES_REQUESTED)
        val uri = try {
            uriProvider.repositoryRootUri(repositoryId)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            val failure = error as? OpenInFilesException ?: OpenInFilesException(OpenInFilesErrorCategory.DOCUMENT_UNAVAILABLE, error)
            diagnostics.event(FilesDiagnosticEvent.OPEN_IN_FILES_FAILED, mapOf("category" to failure.category.name))
            return Result.failure(failure)
        }
        val ownedByApp = uri.authority == "${context.packageName}.documents"
        diagnostics.event(FilesDiagnosticEvent.OPEN_IN_FILES_URI_READY, mapOf("location" to if (ownedByApp) "app_provider" else "shared_storage"))
        // The app cannot issue permission grants for the system's external-storage provider.
        val flags = Intent.FLAG_ACTIVITY_NEW_TASK or if (ownedByApp) {
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
        } else 0
        val directIntent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, DocumentsContract.Document.MIME_TYPE_DIR).addFlags(flags)
        if (ownedByApp && DocumentsContract.isTreeUri(uri)) {
            directIntent.clipData = ClipData.newRawUri("Repository files", DocumentsContract.buildTreeDocumentUri(uri.authority, DocumentsContract.getTreeDocumentId(uri)))
        }
        try {
            withContext(mainDispatcher) { context.startActivity(directIntent) }
            diagnostics.event(FilesDiagnosticEvent.OPEN_IN_FILES_LAUNCHED)
            return Result.success(Unit)
        } catch (_: android.content.ActivityNotFoundException) {
            diagnostics.event(FilesDiagnosticEvent.OPEN_IN_FILES_FALLBACK)
        } catch (error: CancellationException) {
            throw error
        } catch (_: RuntimeException) {
            diagnostics.event(FilesDiagnosticEvent.OPEN_IN_FILES_FALLBACK)
        }

        val fallbackIntent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            fallbackIntent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, uri)
        }
        return try {
            withContext(mainDispatcher) { context.startActivity(fallbackIntent) }
            diagnostics.event(FilesDiagnosticEvent.OPEN_IN_FILES_LAUNCHED)
            Result.success(Unit)
        } catch (error: android.content.ActivityNotFoundException) {
            diagnostics.event(FilesDiagnosticEvent.OPEN_IN_FILES_FAILED, mapOf("category" to OpenInFilesErrorCategory.NO_FILE_MANAGER_AVAILABLE.name))
            Result.failure(OpenInFilesException(OpenInFilesErrorCategory.NO_FILE_MANAGER_AVAILABLE, error))
        } catch (error: CancellationException) {
            throw error
        } catch (error: RuntimeException) {
            diagnostics.event(FilesDiagnosticEvent.OPEN_IN_FILES_FAILED, mapOf("category" to OpenInFilesErrorCategory.OPEN_FAILED.name))
            Result.failure(OpenInFilesException(OpenInFilesErrorCategory.OPEN_FAILED, error))
        }
    }
}
