package com.threeastudio.gitclonepush.data.git

import com.threeastudio.gitclonepush.core.model.RepositoryFileContent
import com.threeastudio.gitclonepush.core.model.RepositoryFileEntry
import com.threeastudio.gitclonepush.data.documents.FilesDiagnosticEvent
import com.threeastudio.gitclonepush.data.documents.FilesDiagnostics
import com.threeastudio.gitclonepush.data.documents.NoOpFilesDiagnostics
import com.threeastudio.gitclonepush.data.documents.RepositoryPathSecurity
import com.threeastudio.gitclonepush.domain.repository.LocalRepositoryStore
import com.threeastudio.gitclonepush.domain.repository.LocalRepositoryValidator
import com.threeastudio.gitclonepush.domain.repository.RepositoryFileReader
import com.threeastudio.gitclonepush.domain.repository.RepositoryFilesErrorCategory
import com.threeastudio.gitclonepush.domain.repository.RepositoryFilesException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class JGitRepositoryFileReader(
    private val store: LocalRepositoryStore,
    private val validator: LocalRepositoryValidator,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val diagnostics: FilesDiagnostics = NoOpFilesDiagnostics,
    private val pathSecurity: RepositoryPathSecurity = RepositoryPathSecurity()
) : RepositoryFileReader {
    override suspend fun listDirectory(repositoryId: String, relativePath: String): List<RepositoryFileEntry> =
        withContext(ioDispatcher) {
            diagnostics.event(FilesDiagnosticEvent.FILES_LIST_STARTED)
            try {
                val directory = safeDirectory(repositoryId, relativePath)
                val entries = directory.listFiles()?.filter { pathSecurity.isVisibleName(it.name) }?.map { file ->
                    if (file.isDirectory) RepositoryFileEntry.Directory(file.name, relative(file, repositoryRoot(repositoryId)))
                    else RepositoryFileEntry.File(file.name, relative(file, repositoryRoot(repositoryId)), file.length(), isBinary(file))
                }.orEmpty().sortedWith(compareBy<RepositoryFileEntry> { it !is RepositoryFileEntry.Directory }.thenBy { it.name.lowercase() })
                diagnostics.event(FilesDiagnosticEvent.FILES_LIST_SUCCESS, mapOf("count" to entries.size.toString()))
                entries
            } catch (error: CancellationException) { throw error }
            catch (error: RepositoryFilesException) {
                diagnostics.event(FilesDiagnosticEvent.FILES_LIST_FAILED, mapOf("category" to error.category.name))
                throw error
            } catch (error: Exception) {
                diagnostics.event(FilesDiagnosticEvent.FILES_LIST_FAILED, mapOf("category" to RepositoryFilesErrorCategory.READ_FAILED.name))
                throw RepositoryFilesException(RepositoryFilesErrorCategory.READ_FAILED, error)
            }
        }

    override suspend fun readFile(repositoryId: String, relativePath: String): RepositoryFileContent =
        withContext(ioDispatcher) {
            diagnostics.event(FilesDiagnosticEvent.FILE_READ_STARTED)
            try {
                val file = safeFile(repositoryId, relativePath)
                val size = file.length()
                if (size == 0L) {
                    diagnostics.event(FilesDiagnosticEvent.FILE_READ_SUCCESS, mapOf("binary" to "false", "truncated" to "false"))
                    return@withContext RepositoryFileContent.Text("", false)
                }
                if (isBinary(file)) {
                    diagnostics.event(FilesDiagnosticEvent.FILE_READ_SUCCESS, mapOf("binary" to "true", "truncated" to "false"))
                    return@withContext RepositoryFileContent.Binary(size)
                }
                val limit = MAX_PREVIEW_BYTES.toLong().coerceAtMost(size).toInt()
                val bytes = file.inputStream().use { input ->
                    val output = java.io.ByteArrayOutputStream(limit)
                    val buffer = ByteArray(8 * 1024)
                    var remaining = limit
                    while (remaining > 0) {
                        val count = input.read(buffer, 0, minOf(buffer.size, remaining))
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        remaining -= count
                    }
                    output.toByteArray()
                }
                val text = bytes.toString(Charsets.UTF_8)
                if (text.contains('\uFFFD')) return@withContext RepositoryFileContent.Binary(size)
                val content = RepositoryFileContent.Text(text, size > MAX_PREVIEW_BYTES)
                diagnostics.event(FilesDiagnosticEvent.FILE_READ_SUCCESS, mapOf("binary" to "false", "truncated" to content.truncated.toString()))
                content
            } catch (error: CancellationException) { throw error }
            catch (error: RepositoryFilesException) {
                diagnostics.event(FilesDiagnosticEvent.FILE_READ_FAILED, mapOf("category" to error.category.name))
                throw error
            } catch (error: Exception) {
                diagnostics.event(FilesDiagnosticEvent.FILE_READ_FAILED, mapOf("category" to RepositoryFilesErrorCategory.READ_FAILED.name))
                throw RepositoryFilesException(RepositoryFilesErrorCategory.READ_FAILED, error)
            }
        }

    private suspend fun repositoryRoot(repositoryId: String): File {
        val repository = store.listRepositories().firstOrNull { it.id == repositoryId }
            ?: throw RepositoryFilesException(RepositoryFilesErrorCategory.REPOSITORY_INVALID)
        val managed = runCatching { store.repositoryDirectory(repositoryId).canonicalFile }.getOrElse {
            throw RepositoryFilesException(RepositoryFilesErrorCategory.REPOSITORY_INVALID, it)
        }
        val declared = runCatching { File(repository.directoryPath).canonicalFile }.getOrNull()
        if (declared != managed || !validator.validate(repository)) throw RepositoryFilesException(RepositoryFilesErrorCategory.REPOSITORY_INVALID)
        return managed
    }

    private suspend fun safeDirectory(repositoryId: String, path: String): File {
        val root = repositoryRoot(repositoryId)
        val resolved = pathSecurity.resolve(root, path) ?: throw RepositoryFilesException(RepositoryFilesErrorCategory.PATH_FORBIDDEN)
        if (!resolved.exists()) throw RepositoryFilesException(RepositoryFilesErrorCategory.DIRECTORY_NOT_FOUND)
        if (!resolved.isDirectory) throw RepositoryFilesException(RepositoryFilesErrorCategory.NOT_DIRECTORY)
        return resolved
    }

    private suspend fun safeFile(repositoryId: String, path: String): File {
        val root = repositoryRoot(repositoryId)
        val resolved = pathSecurity.resolve(root, path) ?: throw RepositoryFilesException(RepositoryFilesErrorCategory.PATH_FORBIDDEN)
        if (!resolved.exists()) throw RepositoryFilesException(RepositoryFilesErrorCategory.FILE_NOT_FOUND)
        if (!resolved.isFile) throw RepositoryFilesException(RepositoryFilesErrorCategory.NOT_REGULAR_FILE)
        return resolved
    }

    private fun relative(file: File, root: File): String = file.relativeTo(root).path.replace(File.separatorChar, '/')

    private fun isBinary(file: File): Boolean {
        file.inputStream().use { input ->
            val buffer = ByteArray(BINARY_SCAN_BYTES)
            val read = input.read(buffer)
            if (read <= 0) return false
            if (buffer.copyOf(read).any { it == 0.toByte() }) return true
            return buffer.copyOf(read).toString(Charsets.UTF_8).contains('\uFFFD')
        }
    }

    private companion object {
        const val MAX_PREVIEW_BYTES = 256 * 1024
        const val BINARY_SCAN_BYTES = 8 * 1024
    }
}
