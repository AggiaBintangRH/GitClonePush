package com.threeastudio.gitclonepush.data.filesystem

import java.io.File

/** NIO detects Windows links too; canonical-path fallback also works on Android API 24–25. */
fun isFilesystemLink(file: File): Boolean {
    if (file.absoluteFile != file.canonicalFile) return true
    return runCatching {
        val files = Class.forName("java.nio.file.Files")
        val pathType = Class.forName("java.nio.file.Path")
        val path = File::class.java.getMethod("toPath").invoke(file)
        files.getMethod("isSymbolicLink", pathType).invoke(null, path) as Boolean
    }.getOrDefault(false)
}

fun hasLinkedAncestor(file: File): Boolean {
    var current: File? = file.absoluteFile
    while (current != null) {
        if (isFilesystemLink(current)) return true
        current = current.parentFile
    }
    return false
}
