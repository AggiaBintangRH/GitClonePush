package com.threeastudio.gitclonepush.data.documents

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import android.provider.DocumentsContract
import com.threeastudio.gitclonepush.core.model.LocalRepository
import com.threeastudio.gitclonepush.data.filesystem.hasLinkedAncestor
import com.threeastudio.gitclonepush.data.storage.SharedCloneStorage
import com.threeastudio.gitclonepush.domain.repository.LocalRepositoryStore
import com.threeastudio.gitclonepush.domain.repository.LocalRepositoryValidator
import com.threeastudio.gitclonepush.domain.repository.OpenInFilesErrorCategory
import com.threeastudio.gitclonepush.domain.repository.OpenInFilesException
import com.threeastudio.gitclonepush.domain.repository.RepositoryDocumentsUriProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class AndroidRepositoryDocumentsUriProvider(
    private val context: Context,
    private val store: LocalRepositoryStore,
    private val validator: LocalRepositoryValidator,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : RepositoryDocumentsUriProvider {
    private val authority = "${context.packageName}.documents"

    override suspend fun repositoryRootUri(repositoryId: String): Uri = withContext(ioDispatcher) {
        if (!repositoryId.matches(Regex("[0-9]+"))) unavailable()
        val repository = store.listRepositories().firstOrNull { it.id == repositoryId }
            ?: throw OpenInFilesException(OpenInFilesErrorCategory.REPOSITORY_NOT_FOUND)
        val directory = File(repository.directoryPath)
        if (hasLinkedAncestor(directory) || store.repositoryDirectory(repositoryId).canonicalFile != directory.canonicalFile ||
            !directory.isDirectory || !validator.validate(repository)) unavailable()

        if (repository.parentTreeUri != null) return@withContext sharedStorageUri(repository, directory)
        val id = "repo:$repositoryId"
        DocumentsContract.buildDocumentUriUsingTree(DocumentsContract.buildTreeDocumentUri(authority, id), id)
    }

    private fun sharedStorageUri(repository: LocalRepository, directory: File): Uri {
        val tree = (repository.parentTreeUri ?: unavailable()).toUri()
        if (tree.scheme != "content" || tree.authority != SharedCloneStorage.EXTERNAL_DOCUMENTS_AUTHORITY ||
            !DocumentsContract.isTreeUri(tree)) unavailable()
        val parentId = DocumentsContract.getTreeDocumentId(tree)
        val separator = parentId.indexOf(':')
        if (separator < 1) unavailable()
        val volumeId = parentId.substring(0, separator)
        val relative = parentId.substring(separator + 1)
        if (relative.isBlank() || relative.split('/').any { it in setOf("", ".", "..", ".git", "Android") || '\\' in it || '\u0000' in it }) unavailable()
        val volume = SharedCloneStorage.volumeRoot(context, volumeId) ?: unavailable()
        val parent = File(volume, relative)
        if (hasLinkedAncestor(parent) || parent.canonicalFile != directory.parentFile?.canonicalFile ||
            !parent.canonicalPath.startsWith(volume.canonicalPath + File.separator)) unavailable()
        // A plain document URI lets system Files reconstruct the real disk folder's ancestry.
        return DocumentsContract.buildDocumentUri(tree.authority, "$parentId/${directory.name}")
    }

    private fun unavailable(): Nothing = throw OpenInFilesException(OpenInFilesErrorCategory.DOCUMENT_UNAVAILABLE)
}
