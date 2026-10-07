package com.threeastudio.gitclonepush.data.documents

import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import android.webkit.MimeTypeMap
import com.threeastudio.gitclonepush.data.git.AndroidLocalRepositoryStore
import com.threeastudio.gitclonepush.data.git.JGitLocalRepositoryValidator
import com.threeastudio.gitclonepush.core.model.LocalRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.FileNotFoundException

/** SAF boundary exposing only validated working-tree files, never repository internals. */
class RepositoryDocumentsProvider : DocumentsProvider() {
    private lateinit var store: AndroidLocalRepositoryStore
    private lateinit var validator: JGitLocalRepositoryValidator
    private lateinit var security: DocumentPathSecurity
    private lateinit var diagnostics: FilesDiagnostics
    private val ioDispatcher = Dispatchers.IO
    private val authority: String get() = requireNotNull(context).packageName + ".documents"

    override fun onCreate(): Boolean {
        val appContext = requireNotNull(context)
        store = AndroidLocalRepositoryStore(appContext, ioDispatcher)
        validator = JGitLocalRepositoryValidator(ioDispatcher)
        security = DocumentPathSecurity(File(appContext.filesDir, "git-repositories"))
        diagnostics = if (com.threeastudio.gitclonepush.BuildConfig.DEBUG) DebugFilesDiagnostics() else NoOpFilesDiagnostics
        return true
    }

    override fun queryRoots(projection: Array<out String>?): Cursor = MatrixCursor(ROOT_COLUMNS).apply {
        diagnostics.event(FilesDiagnosticEvent.PROVIDER_ROOTS_QUERIED)
        addRow(arrayOf<Any>("root", "root", "Git Client", DocumentsContract.Root.FLAG_SUPPORTS_IS_CHILD, "*/*", 0))
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor = MatrixCursor(DOCUMENT_COLUMNS).apply {
        diagnostics.event(FilesDiagnosticEvent.PROVIDER_DOCUMENT_QUERIED)
        val document = resolveDocument(documentId)
        val displayName = parseDocumentId(documentId)?.takeIf { it.relativePath.isBlank() }?.let { parsed ->
            validRepositories().firstOrNull { it.id == parsed.repositoryId }?.name
        }
        addDocumentRow(this, documentId, document, displayName)
    }

    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor = MatrixCursor(DOCUMENT_COLUMNS).apply {
        diagnostics.event(FilesDiagnosticEvent.PROVIDER_CHILDREN_QUERIED)
        when (parentDocumentId) {
            ROOT_ID -> validRepositories().forEach { repository ->
                addDocumentRow(this, repositoryDocumentId(repository.id), repositoryDirectory(repository), repository.name)
            }
            else -> {
                val parent = resolveDocument(parentDocumentId)
                if (!parent.isDirectory) throw FileNotFoundException("Not a directory")
                parent.listFiles().orEmpty()
                    .filter { isVisible(it) && it.absoluteFile == it.canonicalFile }
                    .sortedBy { it.name.lowercase() }
                    .forEach { child -> addDocumentRow(this, childDocumentId(parentDocumentId, child.name), child) }
            }
        }
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        if (parentDocumentId == ROOT_ID) return parseDocumentId(documentId)?.relativePath?.isNullOrBlank() == true
        val parent = parseDocumentId(parentDocumentId) ?: return false
        val child = parseDocumentId(documentId) ?: return false
        if (parent.repositoryId != child.repositoryId || child.relativePath.isBlank()) return false
        val parentPath = parent.relativePath.trimEnd('/')
        val childPath = child.relativePath
        return (parentPath.isBlank() || childPath.startsWith("$parentPath/")) &&
            security.resolve(repositoryDirectory(parentDocumentId), childPath) != null
    }

    override fun getDocumentType(documentId: String): String = mimeType(resolveDocument(documentId))

    @androidx.annotation.RequiresApi(26)
    override fun findDocumentPath(parentDocumentId: String?, childDocumentId: String): DocumentsContract.Path {
        resolveDocument(childDocumentId)
        val path = mutableListOf(ROOT_ID)
        if (childDocumentId != ROOT_ID) {
            val child = parseDocumentId(childDocumentId) ?: throw FileNotFoundException("Invalid document id")
            val repositoryId = repositoryDocumentId(child.repositoryId)
            path += repositoryId
            var relative = ""
            child.relativePath.split('/').filter { it.isNotBlank() }.forEach { segment ->
                relative = if (relative.isBlank()) segment else "$relative/$segment"
                path += "$repositoryId:$relative"
            }
        }
        if (parentDocumentId == null) return DocumentsContract.Path(ROOT_ID, path)
        val index = path.indexOf(parentDocumentId)
        if (index < 0) throw FileNotFoundException("Document is outside the requested parent")
        return DocumentsContract.Path(null, path.drop(index))
    }

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        val file = resolveDocument(documentId).takeUnless { it.isDirectory } ?: throw FileNotFoundException("Directories cannot be opened as files")
        val accessMode = ParcelFileDescriptor.parseMode(mode)
        diagnostics.event(FilesDiagnosticEvent.PROVIDER_DOCUMENT_OPENED, mapOf("isDirectory" to "false"))
        return ParcelFileDescriptor.open(file, accessMode)
    }

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        if (!security.isSafeDisplayName(displayName)) throw FileNotFoundException("Unsafe document name")
        if (parentDocumentId == ROOT_ID) throw FileNotFoundException("Repositories cannot be created here")
        val parent = resolveDocument(parentDocumentId).takeIf { it.isDirectory } ?: throw FileNotFoundException("Parent is not a directory")
        val child = security.resolve(repositoryDirectory(parentDocumentId), relativePath(parentDocumentId, displayName))
            ?: throw FileNotFoundException("Unsafe document path")
        if (child.exists()) throw FileNotFoundException("Document already exists")
        val created = if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) child.mkdir() else child.createNewFile()
        if (!created) throw FileNotFoundException("Could not create document")
        diagnostics.event(FilesDiagnosticEvent.PROVIDER_DOCUMENT_CREATED, mapOf("isDirectory" to (mimeType == DocumentsContract.Document.MIME_TYPE_DIR).toString()))
        return childDocumentId(parentDocumentId, displayName)
    }

    override fun deleteDocument(documentId: String) {
        val file = resolveDocument(documentId)
        if (documentId == ROOT_ID || isRepositoryDocument(documentId)) throw FileNotFoundException("Repository root cannot be deleted")
        deleteTree(file, repositoryDirectory(documentId).canonicalFile)
        diagnostics.event(FilesDiagnosticEvent.PROVIDER_DOCUMENT_DELETED)
    }

    override fun renameDocument(documentId: String, displayName: String): String {
        if (!security.isSafeDisplayName(displayName)) throw FileNotFoundException("Unsafe document name")
        val source = resolveDocument(documentId)
        if (isRepositoryDocument(documentId)) throw FileNotFoundException("Repository root cannot be renamed")
        val parentId = parentDocumentId(documentId)
        val parent = resolveDocument(parentId)
        val targetRelative = relativePath(parentId, displayName)
        val target = security.resolve(repositoryDirectory(parentId), targetRelative) ?: throw FileNotFoundException("Unsafe target")
        if (target.exists()) throw FileNotFoundException("Target already exists")
        if (!source.renameTo(target)) throw FileNotFoundException("Could not rename document")
        diagnostics.event(FilesDiagnosticEvent.PROVIDER_DOCUMENT_RENAMED)
        return childDocumentId(parentId, displayName)
    }

    private fun validRepositories(): List<LocalRepository> = runBlocking(ioDispatcher) {
        store.listRepositories().filter { repository ->
            repository.directoryPath == store.repositoryDirectory(repository.id).canonicalPath && validator.validate(repository)
        }
    }

    private fun repositoryDirectory(repository: LocalRepository): File =
        store.repositoryDirectory(repository.id).takeIf {
            it.absoluteFile == it.canonicalFile && it.canonicalPath == repository.directoryPath
        } ?: throw FileNotFoundException("Repository is not registered")

    private fun resolveDocument(documentId: String): File {
        if (documentId == ROOT_ID) return File(requireNotNull(context).filesDir, "git-repositories")
        val parsed = parseDocumentId(documentId) ?: throw FileNotFoundException("Invalid document id")
        val repository = validRepositories().firstOrNull { it.id == parsed.repositoryId } ?: throw FileNotFoundException("Repository unavailable")
        val directory = repositoryDirectory(repository)
        return security.resolve(directory, parsed.relativePath) ?: throw FileNotFoundException("Unsafe document path")
    }

    private fun addDocumentRow(cursor: MatrixCursor, documentId: String, file: File, displayName: String? = null) {
        if (!isVisible(file) && documentId != ROOT_ID) throw FileNotFoundException("Hidden document")
        val flags = if (file.isDirectory) {
            DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE or DocumentsContract.Document.FLAG_SUPPORTS_DELETE or DocumentsContract.Document.FLAG_SUPPORTS_RENAME
        } else {
            DocumentsContract.Document.FLAG_SUPPORTS_WRITE or DocumentsContract.Document.FLAG_SUPPORTS_DELETE or DocumentsContract.Document.FLAG_SUPPORTS_RENAME
        }
        cursor.addRow(arrayOf(documentId, displayName ?: file.name.ifBlank { "Repositories" }, mimeType(file), file.length(), file.lastModified(), flags))
    }

    private fun mimeType(file: File): String = if (file.isDirectory) DocumentsContract.Document.MIME_TYPE_DIR else
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: "application/octet-stream"

    private fun isVisible(file: File): Boolean = security.isSafeDisplayName(file.name)

    private fun deleteTree(file: File, repositoryRoot: File) {
        val canonicalPath = file.canonicalPath
        val rootPath = repositoryRoot.canonicalPath
        if (canonicalPath != rootPath && !canonicalPath.startsWith(rootPath + File.separator)) throw FileNotFoundException("Path escaped repository")
        if (file.isDirectory) file.listFiles().orEmpty().forEach { child ->
            if (!isVisible(child) || child.absoluteFile.path != child.canonicalFile.path) throw FileNotFoundException("Unsafe child path")
            deleteTree(child, repositoryRoot)
        }
        if (!file.delete()) throw FileNotFoundException("Could not delete document")
    }

    private fun repositoryDocumentId(id: String) = "repo:$id"
    private fun childDocumentId(parentId: String, name: String): String {
        val parent = parseDocumentId(parentId) ?: throw FileNotFoundException("Invalid parent")
        return "${repositoryDocumentId(parent.repositoryId)}:${relativePath(parentId, name)}"
    }
    private fun isRepositoryDocument(id: String) = parseDocumentId(id)?.relativePath.isNullOrEmpty()
    private fun parentDocumentId(id: String): String {
        val child = parseDocumentId(id) ?: throw FileNotFoundException("Invalid document id")
        val repositoryId = repositoryDocumentId(child.repositoryId)
        val parentPath = child.relativePath.substringBeforeLast('/', "")
        return if (parentPath.isBlank()) repositoryId else "$repositoryId:$parentPath"
    }
    private fun relativePath(parentId: String, name: String): String = parseDocumentId(parentId)?.let { if (it.relativePath.isBlank()) name else "${it.relativePath}/$name" } ?: throw FileNotFoundException("Invalid parent")
    private fun repositoryDirectory(documentId: String): File = validRepositories().firstOrNull { it.id == parseDocumentId(documentId)?.repositoryId }?.let(::repositoryDirectory) ?: throw FileNotFoundException("Repository unavailable")

    private data class ParsedDocumentId(val repositoryId: String, val relativePath: String)
    private fun parseDocumentId(id: String): ParsedDocumentId? {
        if (!id.startsWith("repo:")) return null
        val body = id.removePrefix("repo:")
        val separator = body.indexOf(':')
        val repositoryId = if (separator < 0) body else body.substring(0, separator)
        val relative = if (separator < 0) "" else body.substring(separator + 1)
        return ParsedDocumentId(repositoryId, relative).takeIf { it.repositoryId.matches(Regex("[0-9]+")) }
    }

    private companion object {
        const val ROOT_ID = "root"
        val ROOT_COLUMNS = arrayOf(DocumentsContract.Root.COLUMN_ROOT_ID, DocumentsContract.Root.COLUMN_DOCUMENT_ID, DocumentsContract.Root.COLUMN_TITLE, DocumentsContract.Root.COLUMN_FLAGS, DocumentsContract.Root.COLUMN_MIME_TYPES, DocumentsContract.Root.COLUMN_ICON)
        val DOCUMENT_COLUMNS = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED, DocumentsContract.Document.COLUMN_FLAGS)
    }
}
