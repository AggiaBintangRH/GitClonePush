package com.threeastudio.gitclonepush.data.documents

import java.io.File
import com.threeastudio.gitclonepush.data.filesystem.isFilesystemLink

/** Single repository-relative path policy shared by internal reads and SAF operations. */
class RepositoryPathSecurity {
    fun resolve(repositoryRoot: File, relativePath: String): File? {
        if (relativePath.isBlank()) return repositoryRoot.canonicalFile
        if (relativePath.indexOf('\u0000') >= 0) return null
        if (relativePath.startsWith('/') || relativePath.startsWith('\\')) return null
        if (WINDOWS_DRIVE.matches(relativePath) || URI_SCHEME.matches(relativePath)) return null

        val segments = relativePath.replace('\\', '/').split('/')
        if (segments.any { it.isBlank() || it == "." || it == ".." || !isVisibleName(it) }) return null

        val root = repositoryRoot.canonicalFile
        var current = root
        for (segment in segments) {
            current = File(current, segment)
            if (isSymbolicLink(current)) return null
        }
        val candidate = current.canonicalFile
        return candidate.takeIf { isWithin(root, it) }
    }

    fun isVisibleName(name: String): Boolean =
        name.isNotBlank() && name != "." && name != ".." &&
            name != ".git" && name != "metadata.json" &&
            name != ".app-metadata" && !name.endsWith(".clone-in-progress") &&
            !name.contains('/') && !name.contains('\\') && !name.contains('\u0000')

    private fun isWithin(root: File, candidate: File): Boolean =
        candidate.canonicalPath == root.canonicalPath || candidate.canonicalPath.startsWith(root.canonicalPath + File.separator)

    private fun isSymbolicLink(file: File): Boolean = isFilesystemLink(file)

    private companion object {
        val WINDOWS_DRIVE = Regex("^[A-Za-z]:")
        val URI_SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")
    }
}
