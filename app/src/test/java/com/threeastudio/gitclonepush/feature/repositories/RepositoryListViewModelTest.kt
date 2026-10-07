package com.threeastudio.gitclonepush.feature.repositories

import com.threeastudio.gitclonepush.core.model.CloneProgress
import com.threeastudio.gitclonepush.core.model.GitRepository
import com.threeastudio.gitclonepush.core.model.LocalRepository
import com.threeastudio.gitclonepush.core.model.RepositoryVisibility
import com.threeastudio.gitclonepush.domain.repository.CloneRepositoryRequest
import com.threeastudio.gitclonepush.domain.repository.LocalRepositoryStore
import com.threeastudio.gitclonepush.domain.repository.LocalRepositoryValidator
import com.threeastudio.gitclonepush.domain.repository.RepositoryCloner
import com.threeastudio.gitclonepush.domain.repository.RepositoryRepository
import com.threeastudio.gitclonepush.domain.repository.CloneDestinationSelector
import com.threeastudio.gitclonepush.domain.repository.CloneDestinationException
import com.threeastudio.gitclonepush.domain.repository.CloneDestinationError
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class RepositoryListViewModelTest {
    private val repository = GitRepository("1", "demo", "alice", RepositoryVisibility.PUBLIC, "Kotlin", "main", "2026-01-01T00:00:00Z", "https://github.com/alice/demo.git")

    @Test
    fun initialLoadSuccessAndFailureAreRepresented() = viewModelTest {
        val success = RepositoryListViewModel(FakeRepositoryRepository(listOf(listOf(repository))), NoOpCloner, FakeLocalStore())
        advanceUntilIdle()
        assertEquals(listOf(repository), success.uiState.value.repositories)
        assertFalse(success.uiState.value.isInitialLoading)

        val failure = RepositoryListViewModel(FakeRepositoryRepository(failures = 1), NoOpCloner, FakeLocalStore())
        advanceUntilIdle()
        assertFalse(failure.uiState.value.isInitialLoading)
        assertTrue(failure.uiState.value.errorMessage != null)
    }

    @Test
    fun retrySuccessReplacesInitialError() = viewModelTest {
        val repositorySource = FakeRepositoryRepository(listOf(listOf(repository)), failures = 1)
        val viewModel = RepositoryListViewModel(repositorySource, NoOpCloner, FakeLocalStore())
        advanceUntilIdle()
        viewModel.retryInitialLoad()
        advanceUntilIdle()
        assertEquals(listOf(repository), viewModel.uiState.value.repositories)
        assertEquals(null, viewModel.uiState.value.errorMessage)
    }

    @Test
    fun refreshKeepsExistingDataAndUiSelectionsWhileRunning() = viewModelTest {
        val releaseRefresh = CompletableDeferred<Unit>()
        val source = FakeRepositoryRepository(listOf(listOf(repository), listOf(repository.copy(name = "updated"))))
        source.blockSecondCall = releaseRefresh
        val viewModel = RepositoryListViewModel(source, NoOpCloner, FakeLocalStore())
        advanceUntilIdle()
        viewModel.updateQuery("demo")
        viewModel.selectFilter(RepositoryFilter.PUBLIC)
        viewModel.refresh()
        runCurrent()

        assertTrue(viewModel.uiState.value.isRefreshing)
        assertEquals(listOf(repository), viewModel.uiState.value.repositories)
        assertEquals("demo", viewModel.uiState.value.query)
        assertEquals(RepositoryFilter.PUBLIC, viewModel.uiState.value.filter)

        releaseRefresh.complete(Unit)
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.isRefreshing)
        assertEquals("updated", viewModel.uiState.value.repositories.single().name)
    }

    @Test
    fun refreshFailureRetainsOldData() = viewModelTest {
        val source = FakeRepositoryRepository(listOf(listOf(repository)), failOnCall = 2)
        val viewModel = RepositoryListViewModel(source, NoOpCloner, FakeLocalStore())
        advanceUntilIdle()
        viewModel.refresh()
        advanceUntilIdle()
        assertEquals(listOf(repository), viewModel.uiState.value.repositories)
        assertFalse(viewModel.uiState.value.isRefreshing)
        assertTrue(viewModel.uiState.value.refreshErrorMessage != null)
    }

    @Test
    fun concurrentRefreshCallsOnlyStartOneRequest() = viewModelTest {
        val releaseRefresh = CompletableDeferred<Unit>()
        val source = FakeRepositoryRepository(listOf(listOf(repository), listOf(repository)))
        source.blockSecondCall = releaseRefresh
        val viewModel = RepositoryListViewModel(source, NoOpCloner, FakeLocalStore())
        advanceUntilIdle()
        viewModel.refresh()
        viewModel.refresh()
        runCurrent()
        assertEquals(2, source.calls)
        releaseRefresh.complete(Unit)
        advanceUntilIdle()
    }

    @Test
    fun refreshDoesNotWipeClonedState() = viewModelTest {
        val localStore = FakeLocalStore()
        val source = FakeRepositoryRepository(listOf(listOf(repository), listOf(repository.copy(updatedAt = "new"))))
        val viewModel = RepositoryListViewModel(source, CompletingCloner(localStore), localStore)
        advanceUntilIdle()
        viewModel.clone(repository.id)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.cloneStates[repository.id]?.status?.name == "CLONED")
        viewModel.refresh()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.cloneStates[repository.id]?.status?.name == "CLONED")
    }

    @Test
    fun duplicateCloneClicksStartOnlyOneUnderlyingOperation() = viewModelTest {
        val cloner = BlockingCloner()
        val viewModel = RepositoryListViewModel(FakeRepositoryRepository(listOf(listOf(repository))), cloner, FakeLocalStore())
        advanceUntilIdle()
        viewModel.clone(repository.id)
        viewModel.clone(repository.id)
        runCurrent()
        assertEquals(1, cloner.calls)
        cloner.release.complete(Unit)
        advanceUntilIdle()
    }

    @Test
    fun freshViewModelReconcilesPersistedValidatedRepository() = viewModelTest {
        val localStore = FakeLocalStore()
        localStore.save(LocalRepository("1", "alice", "demo", "/managed/1", repository.cloneUrl))
        val viewModel = RepositoryListViewModel(
            FakeRepositoryRepository(listOf(listOf(repository))),
            NoOpCloner,
            localStore,
            localRepositoryValidator = LocalRepositoryValidator { true }
        )
        advanceUntilIdle()
        assertEquals("CLONED", viewModel.uiState.value.cloneStates[repository.id]?.status?.name)
    }

    @Test
    fun selectedFolderIsResolvedBeforeCloneAndUsedAsRealDestination() = viewModelTest {
        val requests = mutableListOf<CloneRepositoryRequest>()
        var selections = 0
        val viewModel = RepositoryListViewModel(
            FakeRepositoryRepository(listOf(listOf(repository))),
            object : RepositoryCloner {
                override fun clone(request: CloneRepositoryRequest): Flow<CloneProgress> = flow { requests += request }
            }, FakeLocalStore(),
            cloneDestinationSelector = CloneDestinationSelector { repo, selection ->
                assertEquals(repository, repo)
                assertEquals("selected-parent-uri", selection)
                selections++
                "/chosen/folder/demo"
            }
        )
        advanceUntilIdle()
        assertEquals(0, requests.size)
        viewModel.clone(repository.id, "selected-parent-uri")
        viewModel.clone(repository.id, "selected-parent-uri")
        advanceUntilIdle()
        assertEquals(1, selections)
        assertEquals("/chosen/folder/demo", requests.single().destination)
    }

    @Test
    fun invalidFolderNeverStartsCloneAndExposesControlledError() = viewModelTest {
        val cloner = BlockingCloner()
        val viewModel = RepositoryListViewModel(
            FakeRepositoryRepository(listOf(listOf(repository))), cloner, FakeLocalStore(),
            cloneDestinationSelector = CloneDestinationSelector { _, _ -> throw CloneDestinationException(CloneDestinationError.DESTINATION_EXISTS) }
        )
        advanceUntilIdle()
        viewModel.clone(repository.id, "selected-parent-uri")
        advanceUntilIdle()
        assertEquals(0, cloner.calls)
        assertEquals("ERROR", viewModel.uiState.value.cloneStates[repository.id]?.status?.name)
        assertTrue(viewModel.uiState.value.cloneStates[repository.id]?.errorMessage?.contains("already exists") == true)
    }

    @Test
    fun restoredFolderResultWaitsForInitialCatalogLoad() = viewModelTest {
        val releaseLoad = CompletableDeferred<Unit>()
        val requests = mutableListOf<CloneRepositoryRequest>()
        val source = object : RepositoryRepository {
            override suspend fun getRepositories(): List<GitRepository> {
                releaseLoad.await()
                return listOf(repository)
            }
        }
        val viewModel = RepositoryListViewModel(
            source,
            object : RepositoryCloner {
                override fun clone(request: CloneRepositoryRequest): Flow<CloneProgress> = flow { requests += request }
            }, FakeLocalStore(),
            cloneDestinationSelector = CloneDestinationSelector { _, _ -> "/chosen/folder/demo" }
        )
        viewModel.clone(repository.id, "restored-folder-uri")
        runCurrent()
        assertEquals(0, requests.size)
        releaseLoad.complete(Unit)
        advanceUntilIdle()
        assertEquals("/chosen/folder/demo", requests.single().destination)
    }

    @Test
    fun unavailableRepositoryProducesErrorInsteadOfSilentlyDiscardingSelection() = viewModelTest {
        val cloner = BlockingCloner()
        val viewModel = RepositoryListViewModel(FakeRepositoryRepository(), cloner, FakeLocalStore())
        viewModel.clone(repository.id, "selected-folder-uri")
        advanceUntilIdle()
        assertEquals(0, cloner.calls)
        assertEquals("ERROR", viewModel.uiState.value.cloneStates[repository.id]?.status?.name)
        assertTrue(viewModel.uiState.value.cloneStates[repository.id]?.errorMessage?.contains("unavailable") == true)
    }

    private fun viewModelTest(block: suspend TestScope.() -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            block()
        } finally {
            Dispatchers.resetMain()
        }
    }

    private class FakeRepositoryRepository(
        private val responses: List<List<GitRepository>> = emptyList(),
        private var failures: Int = 0,
        private val failOnCall: Int? = null
    ) : RepositoryRepository {
        var calls = 0
        var blockSecondCall: CompletableDeferred<Unit>? = null
        override suspend fun getRepositories(): List<GitRepository> {
            calls++
            if (failures > 0) { failures--; error("test failure") }
            if (calls == failOnCall) error("test failure")
            if (calls == 2) blockSecondCall?.await()
            return responses.getOrElse(calls - 1) { responses.lastOrNull().orEmpty() }
        }
    }

    private object NoOpCloner : RepositoryCloner {
        override fun clone(request: CloneRepositoryRequest): Flow<CloneProgress> = flow { }
    }

    private class CompletingCloner(private val store: FakeLocalStore) : RepositoryCloner {
        override fun clone(request: CloneRepositoryRequest): Flow<CloneProgress> = flow {
            val local = LocalRepository(request.repository.id, request.repository.owner, request.repository.name, request.destination, request.repository.cloneUrl)
            store.save(local)
            emit(CloneProgress.Completed(local))
        }
    }

    private class BlockingCloner : RepositoryCloner {
        var calls = 0
        val release = CompletableDeferred<Unit>()
        override fun clone(request: CloneRepositoryRequest): Flow<CloneProgress> = flow {
            calls++
            emit(CloneProgress.Preparing)
            release.await()
            emit(CloneProgress.Completed(LocalRepository(request.repository.id, request.repository.owner, request.repository.name, request.destination, request.repository.cloneUrl)))
        }
    }

    private class FakeLocalStore : LocalRepositoryStore {
        private val repositories = mutableListOf<LocalRepository>()
        override fun repositoryDirectory(repositoryId: String) = File("build/test-repositories/$repositoryId")
        override suspend fun listRepositories(): List<LocalRepository> = repositories.toList()
        override suspend fun save(repository: LocalRepository) { repositories.removeAll { it.id == repository.id }; repositories += repository }
    }
}
