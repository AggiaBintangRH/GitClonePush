package com.threeastudio.gitclonepush.feature.repositories

import com.threeastudio.gitclonepush.core.model.GitRepository
import com.threeastudio.gitclonepush.core.model.RepositoryVisibility
import org.junit.Assert.assertEquals
import org.junit.Test

class RepositoryListUiStateTest {
    private val repositories = listOf(
        GitRepository("1", "private-repo", "owner", RepositoryVisibility.PRIVATE, "Kotlin", "main", "today"),
        GitRepository("2", "public-repo", "owner", RepositoryVisibility.PUBLIC, "Python", "main", "today")
    )

    @Test
    fun `private filter returns only private repositories`() {
        val state = RepositoryListUiState(repositories = repositories, filter = RepositoryFilter.PRIVATE)
        assertEquals(listOf("private-repo"), state.visibleRepositories.map { it.name })
    }

    @Test
    fun `query is case insensitive`() {
        val state = RepositoryListUiState(repositories = repositories, query = "PUBLIC")
        assertEquals(listOf("public-repo"), state.visibleRepositories.map { it.name })
    }
}
