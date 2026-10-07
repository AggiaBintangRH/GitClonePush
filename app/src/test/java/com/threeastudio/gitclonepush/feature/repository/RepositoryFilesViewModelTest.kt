package com.threeastudio.gitclonepush.feature.repository

import com.threeastudio.gitclonepush.core.model.RepositoryFileContent
import com.threeastudio.gitclonepush.core.model.RepositoryFileEntry
import com.threeastudio.gitclonepush.domain.repository.RepositoryFileReader
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RepositoryFilesViewModelTest {
    @Test
    fun latestDirectoryRequestWinsAndLoadingClears() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val oldRequest = CompletableDeferred<Unit>()
            val reader = object : RepositoryFileReader {
                override suspend fun listDirectory(repositoryId: String, relativePath: String): List<RepositoryFileEntry> {
                    if (relativePath.isEmpty()) oldRequest.await()
                    return listOf(RepositoryFileEntry.Directory(relativePath, relativePath))
                }
                override suspend fun readFile(repositoryId: String, relativePath: String) = RepositoryFileContent.Text("", false)
            }
            val model = RepositoryFilesViewModel("repo", reader)
            runCurrent()
            assertTrue(model.uiState.value.isLoading)
            model.openDirectory("latest")
            advanceUntilIdle()
            oldRequest.complete(Unit)
            advanceUntilIdle()
            assertEquals("latest", model.uiState.value.currentPath)
            assertEquals("latest", model.uiState.value.entries.single().name)
            assertFalse(model.uiState.value.isLoading)
        } finally { Dispatchers.resetMain() }
    }

    @Test
    fun unexpectedReadFailureIsSafeAndRetrySucceeds() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            var fail = true
            val reader = object : RepositoryFileReader {
                override suspend fun listDirectory(repositoryId: String, relativePath: String): List<RepositoryFileEntry> {
                    if (fail) error("private implementation details")
                    return emptyList()
                }
                override suspend fun readFile(repositoryId: String, relativePath: String) = RepositoryFileContent.Text("", false)
            }
            val model = RepositoryFilesViewModel("repo", reader)
            advanceUntilIdle()
            assertEquals("READ_FAILED", model.uiState.value.error)
            assertFalse(model.uiState.value.isLoading)
            fail = false
            model.refresh()
            advanceUntilIdle()
            assertEquals(null, model.uiState.value.error)
            assertFalse(model.uiState.value.isLoading)
        } finally { Dispatchers.resetMain() }
    }
}
