package com.threeastudio.gitclonepush.data.documents

import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class DocumentPathSecurityTest {
    @Test
    fun rejectsTraversalAndGitInternals() {
        val root = Files.createTempDirectory("documents-security").toFile()
        try {
            val repository = File(root, "1").apply { mkdirs() }
            val security = DocumentPathSecurity(root)
            assertNotNull(security.resolve(repository, "README.md"))
            assertNull(security.resolve(repository, "../2/secret.txt"))
            assertNull(security.resolve(repository, ".git/config"))
            assertNull(security.resolve(repository, "src/../../secret.txt"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun rejectsSymlinkThatEscapesRepository() {
        val root = Files.createTempDirectory("documents-symlink").toFile()
        try {
            val repository = File(root, "1").apply { mkdirs() }
            val outside = Files.createTempDirectory("documents-outside").toFile().apply { File(this, "secret.txt").writeText("secret") }
            val link = File(repository, "linked")
            runCatching { Files.createSymbolicLink(link.toPath(), outside.toPath()) }.getOrElse {
                return
            }
            assumeTrue("Windows did not create a symbolic link for this test", Files.isSymbolicLink(link.toPath()))
            assertNull(DocumentPathSecurity(root).resolve(repository, "linked/secret.txt"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun allowsOnlySafeSingleComponentNames() {
        val security = DocumentPathSecurity(File("."))
        assertTrue(security.isSafeDisplayName("README.md"))
        assertTrue(!security.isSafeDisplayName("../secret"))
        assertTrue(!security.isSafeDisplayName(".git"))
    }
}
