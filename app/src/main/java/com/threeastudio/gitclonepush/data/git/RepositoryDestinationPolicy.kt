package com.threeastudio.gitclonepush.data.git

import com.threeastudio.gitclonepush.domain.repository.CloneDestinationError
import com.threeastudio.gitclonepush.domain.repository.CloneDestinationException
import java.io.File
import com.threeastudio.gitclonepush.data.filesystem.hasLinkedAncestor
import com.threeastudio.gitclonepush.data.filesystem.isFilesystemLink

/** Pure filesystem checks shared by folder registration and its regression tests. */
class RepositoryDestinationPolicy {
    fun destination(parent: File, repositoryName: String): File {
        if (!repositoryName.matches(Regex("[A-Za-z0-9._-]+")) || repositoryName in setOf(".", "..", ".git")) {
            throw CloneDestinationException(CloneDestinationError.INVALID_FOLDER)
        }
        if (hasLinkedAncestor(parent) || !parent.isDirectory || !parent.canWrite()) {
            throw CloneDestinationException(CloneDestinationError.STORAGE_UNAVAILABLE)
        }
        val destination = File(parent, repositoryName)
        if (isFilesystemLink(destination) || destination.exists()) {
            throw CloneDestinationException(CloneDestinationError.DESTINATION_EXISTS)
        }
        return destination
    }
}
