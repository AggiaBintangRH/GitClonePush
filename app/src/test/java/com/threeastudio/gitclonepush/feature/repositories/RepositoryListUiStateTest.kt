package com.threeastudio.gitclonepush.feature.repositories

import com.threeastudio.gitclonepush.core.model.GitRepository
import com.threeastudio.gitclonepush.core.model.RepositoryVisibility
import com.threeastudio.gitclonepush.core.model.CloneStatus
import com.threeastudio.gitclonepush.core.model.RepositoryCloneState
import org.junit.Assert.assertEquals
import org.junit.Test

class RepositoryListUiStateTest {
    private val repositories = listOf(
        GitRepository("1", "private-repo", "owner", RepositoryVisibility.PRIVATE, "Kotlin", "main", "today"),
        GitRepository("2", "public-repo", "owner", RepositoryVisibility.PUBLIC, "Python", "main", "today")
    )

    @Test
    fun `private filter returns only private repositories`() {
        val state = RepositoryListUiState(repositories = repositories, filter = RepositoryFilter.PRIVATE, cloneStates = clonedStates())
        assertEquals(listOf("private-repo"), state.visibleRepositories.map { it.name })
    }

    @Test
    fun `query is case insensitive`() {
        val state = RepositoryListUiState(repositories = repositories, query = "PUBLIC", filter = RepositoryFilter.ALL, cloneStates = clonedStates())
        assertEquals(listOf("public-repo"), state.visibleRepositories.map { it.name })
    }

    @Test
    fun `cloned filter returns only locally cloned repositories`() {
        val state = RepositoryListUiState(
            repositories = repositories,
            filter = RepositoryFilter.CLONED,
            cloneStates = mapOf("2" to RepositoryCloneState(CloneStatus.CLONED, 100))
        )
        assertEquals(listOf("public-repo"), state.visibleRepositories.map { it.name })
    }

    @Test
    fun `all filter still hides repositories which have never been cloned`() {
        val state = RepositoryListUiState(repositories = repositories, filter = RepositoryFilter.ALL)
        assertEquals(emptyList<GitRepository>(), state.visibleRepositories)
    }

    private fun clonedStates() = repositories.associate { it.id to RepositoryCloneState(CloneStatus.CLONED, 100) }
}
