package com.threeastudio.gitclonepush.data.git

import java.io.File
import java.io.IOException
import com.threeastudio.gitclonepush.data.filesystem.hasLinkedAncestor
import com.threeastudio.gitclonepush.data.filesystem.isFilesystemLink

/** Deletes a registered clone without following symlinks into unrelated directories. */
internal fun deleteOwnedClone(directory: File) {
    if (hasLinkedAncestor(directory)) {
        throw IOException("CLONE_REMOVE_UNSAFE_ROOT")
    }
    fun deleteEntry(entry: File) {
        if (!isFilesystemLink(entry) && entry.isDirectory) {
            val children = entry.listFiles() ?: throw IOException("CLONE_REMOVE_UNREADABLE_DIRECTORY")
            children.forEach(::deleteEntry)
        }
        if (!entry.delete()) throw IOException("CLONE_REMOVE_FAILED")
    }
    if (directory.exists()) deleteEntry(directory)
}
