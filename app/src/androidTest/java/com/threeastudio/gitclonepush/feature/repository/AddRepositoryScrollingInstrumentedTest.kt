package com.threeastudio.gitclonepush.feature.repository

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.threeastudio.gitclonepush.core.model.GitRepository
import com.threeastudio.gitclonepush.core.model.RepositoryVisibility
import com.threeastudio.gitclonepush.data.github.NoOpRepositoryDiagnostics
import com.threeastudio.gitclonepush.domain.repository.*
import com.threeastudio.gitclonepush.feature.repositories.RepositoryListScreen
import com.threeastudio.gitclonepush.feature.repositories.RepositoryListViewModel
import com.threeastudio.gitclonepush.ui.theme.GitClonePushTheme
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AddRepositoryScrollingInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private var model: RepositoryListViewModel? = null

    @After
    fun cancelFixtureViewModel() {
        compose.runOnUiThread { model?.viewModelScope?.cancel() }
    }

    @Test
    fun repositoryPickerScrollsLongListsAndKeepsSearchUsableWithKeyboard() {
        val store = WorkspaceUiFixture()
        val catalog = object : RepositoryRepository {
            override suspend fun getRepositories() = (1..50).map { index ->
                GitRepository("repo-$index", "Repository $index", "demo", RepositoryVisibility.PUBLIC, "Kotlin", "main", "Today")
            }
        }
        val cloner = object : RepositoryCloner {
            override fun clone(request: CloneRepositoryRequest) = error("Do not clone in UI tests")
        }
        val validator = LocalRepositoryValidator { true }
        lateinit var viewModel: RepositoryListViewModel
        compose.runOnUiThread {
            viewModel = RepositoryListViewModel(catalog, cloner, store, localRepositoryValidator = validator)
            model = viewModel
        }
        compose.setContent {
            GitClonePushTheme(dynamicColor = false) {
                RepositoryListScreen("demo", {}, {}, catalog, cloner, store, NoOpRepositoryDiagnostics, validator, viewModel = viewModel)
            }
        }
        compose.onNodeWithContentDescription("Add repository").performClick()
        val picker = compose.onNodeWithTag("add-repository-list")
        val repositoryRow = hasText("Repository 50") and hasSetTextAction().not()
        picker.performScrollToNode(repositoryRow)
        compose.onNode(repositoryRow).assertIsDisplayed()
        picker.performScrollToNode(hasText("Search GitHub repositories"))
        compose.onNodeWithText("Search GitHub repositories").performClick().performTextInput("Repository 50")
        picker.performScrollToNode(repositoryRow)
        compose.onNode(repositoryRow).assertIsDisplayed()
        compose.onNodeWithText("Repository 1").assertDoesNotExist()
        compose.onNodeWithText("Close").assertIsDisplayed().performClick()
        picker.assertDoesNotExist()
    }
}
