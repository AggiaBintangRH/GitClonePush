package com.threeastudio.gitclonepush.data.documents

import java.io.File

/** Validates provider paths before any filesystem operation is performed. */
class DocumentPathSecurity(private val repositoriesRoot: File) {
    private val pathSecurity = RepositoryPathSecurity()
    private val canonicalRoot = repositoriesRoot.canonicalFile

    fun repositoryDirectory(id: String): File? {
        if (!id.matches(Regex("[0-9]+"))) return null
        val directory = File(canonicalRoot, id).canonicalFile
        return directory.takeIf { isWithin(canonicalRoot, it) && it == File(canonicalRoot, id).canonicalFile }
    }

    fun resolve(repositoryDirectory: File, relativePath: String): File? {
        return pathSecurity.resolve(repositoryDirectory, relativePath)
    }

    fun isSafeDisplayName(name: String): Boolean =
        pathSecurity.isVisibleName(name)

    private fun isWithin(root: File, candidate: File): Boolean {
        val rootPath = root.canonicalPath
        val candidatePath = candidate.canonicalPath
        return candidatePath == rootPath || candidatePath.startsWith(rootPath + File.separator)
    }

}
