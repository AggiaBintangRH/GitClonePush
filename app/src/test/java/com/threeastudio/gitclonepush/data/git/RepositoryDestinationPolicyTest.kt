package com.threeastudio.gitclonepush.data.git

import com.threeastudio.gitclonepush.domain.repository.CloneDestinationException
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class RepositoryDestinationPolicyTest {
    @Test fun repositoryNameIsUsedAndFilesAreNeverOverwritten() {
        val root = Files.createTempDirectory("destination-test").toFile().canonicalFile
        try {
            val policy = RepositoryDestinationPolicy()
            assertEquals(File(root, "my-repository"), policy.destination(root, "my-repository"))
            val existing = File(root, "my-repository").apply { mkdir() }
            val file = File(existing, "keep.txt").apply { writeText("keep") }
            assertTrue(runCatching { policy.destination(root, "my-repository") }.exceptionOrNull() is CloneDestinationException)
            assertEquals("keep", file.readText())
        } finally { root.deleteRecursively() }
    }

    @Test fun unsafeRepositoryNamesAreRejected() {
        val root = Files.createTempDirectory("destination-name-test").toFile().canonicalFile
        try {
            listOf("..", ".", ".git", "../escape", "a/b", "a\\b", "/absolute").forEach { name ->
                assertTrue(runCatching { RepositoryDestinationPolicy().destination(root, name) }.exceptionOrNull() is CloneDestinationException)
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun registeredFolderDeletionDoesNotDeleteSiblingFiles() {
        val root = Files.createTempDirectory("owned-deletion-test").toFile().canonicalFile
        try {
            val clone = File(root, "clone").apply { mkdir() }
            File(clone, "nested").mkdir()
            File(clone, "nested/file.txt").writeText("fixture")
            val sibling = File(root, "keep.txt").apply { writeText("keep") }
            deleteOwnedClone(clone)
            assertFalse(clone.exists())
            assertEquals("keep", sibling.readText())
        } finally { root.deleteRecursively() }
    }
}
