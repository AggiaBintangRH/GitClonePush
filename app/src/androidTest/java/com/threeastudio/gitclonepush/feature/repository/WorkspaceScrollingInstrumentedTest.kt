package com.threeastudio.gitclonepush.feature.repository

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.threeastudio.gitclonepush.core.model.AuthState
import com.threeastudio.gitclonepush.domain.repository.AuthLaunchRequest
import com.threeastudio.gitclonepush.domain.repository.AuthRepository
import com.threeastudio.gitclonepush.feature.login.LoginScreen
import com.threeastudio.gitclonepush.feature.login.LoginViewModel
import com.threeastudio.gitclonepush.ui.theme.GitClonePushTheme
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class WorkspaceScrollingInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val models = mutableListOf<ViewModel>()
    @Volatile private var keyboardVisible = false

    @After
    fun cancelFixtureViewModels() {
        compose.runOnUiThread { models.forEach { it.viewModelScope.cancel() } }
    }

    @Test
    fun advancedActionsAndCommitFormRemainReachableInLightTheme() {
        showWorkspace()
        workspace().performScrollToNode(hasText("Advanced actions"))
        compose.onNodeWithText("Advanced actions").performClick()
        workspace().performScrollToNode(hasText("New branch name"))
        compose.onNodeWithText("New branch name").assertIsDisplayed()
        capture("workspace-light.png")
        workspace().performScrollToNode(hasText("Optional stash message"))
        compose.onNodeWithText("Optional stash message").assertIsDisplayed()
        workspace().performScrollToNode(hasText("Commit staged changes"))
        compose.onNodeWithText("Commit staged changes").assertIsDisplayed()
        workspace().performScrollToNode(hasText("Hide advanced actions"))
        compose.onNodeWithText("Hide advanced actions").performClick()
        compose.onNodeWithText("New branch name").assertDoesNotExist()
    }

    @Test
    fun filesAndCommitsShareTheWorkspaceScrollWithoutNestedLists() {
        showWorkspace()
        workspace().performScrollToKey("workspace-tabs")
        compose.onNodeWithText("Files").performClick()
        compose.waitForIdle()
        workspace().performScrollToKey("file:Source40.kt")
        compose.onNodeWithText("Source40.kt").assertIsDisplayed()
        workspace().performScrollToKey("workspace-tabs")
        compose.onNodeWithText("Commits").performClick()
        workspace().performScrollToKey("commit:commit-20")
        compose.onNodeWithText("Improve repository workflow 20").assertIsDisplayed()
    }

    @Test
    fun narrowScreenAndLargeFontKeepAllFileActionsInsideTheViewport() {
        showWorkspace(dark = true, fontScale = 1.6f)
        listOf("Stage", "View diff", "Unstage", "Staged diff").forEach { label ->
            workspace().performScrollToNode(hasText(label))
            val node = compose.onNodeWithText(label).assertIsDisplayed().fetchSemanticsNode()
            val viewport = workspace().fetchSemanticsNode().boundsInRoot
            assertTrue("$label must not extend outside the viewport", node.boundsInRoot.left >= viewport.left && node.boundsInRoot.right <= viewport.right)
        }
        capture("workspace-dark-large-font.png")
        workspace().performScrollToNode(hasText("Commit staged changes"))
        compose.onNodeWithText("Commit staged changes").assertIsDisplayed()
    }

    @Test
    fun commitMessageAndSubmitRemainReachableWithKeyboardOpen() {
        showWorkspace()
        workspace().performScrollToNode(hasText("Commit message"))
        compose.onNodeWithText("Commit message").performClick().performTextInput("Improve accessibility")
        compose.waitUntil(timeoutMillis = 5_000) { keyboardVisible }
        workspace().performScrollToNode(hasText("Commit staged changes"))
        compose.onNodeWithText("Commit staged changes").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("Improve accessibility").assertExists()
    }

    @Test
    fun loginCanScrollToAuthorizationAndPrivacyTextAtLargeFont() {
        lateinit var model: LoginViewModel
        compose.runOnUiThread {
            model = LoginViewModel(object : AuthRepository {
                override fun observeAuthState() = flowOf(AuthState.Unauthenticated)
                override suspend fun beginLogin(): Result<AuthLaunchRequest> = error("No real login in UI tests")
                override suspend fun completeLogin(callbackUri: Uri): Result<Unit> = error("No callback in UI tests")
                override suspend fun cancelLogin() = Unit
                override suspend fun logout() = Unit
            })
            models += model
        }
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.6f)) {
                GitClonePushTheme(dynamicColor = false) {
                    Surface { Box(Modifier.requiredSize(320.dp, 400.dp).clipToBounds()) { LoginScreen({}, model) } }
                }
            }
        }
        compose.onNodeWithText("Continue with GitHub").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Your GitHub password is never entered into this app.").performScrollTo().assertIsDisplayed()
    }

    private fun showWorkspace(dark: Boolean = false, fontScale: Float = 1f) {
        val fixture = WorkspaceUiFixture()
        lateinit var model: RepositoryViewModel
        compose.runOnUiThread { model = fixture.createViewModel(); models += model }
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                keyboardVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
                GitClonePushTheme(darkTheme = dark, dynamicColor = false) {
                    Box(Modifier.requiredSize(320.dp, 480.dp).clipToBounds()) {
                        RepositoryScreen(
                            repositoryId = fixture.repository.id, onBack = {}, localStore = fixture,
                            reader = fixture, mutator = fixture, synchronizer = fixture,
                            identityReader = fixture, branchReader = fixture, fileReader = fixture, viewModel = model
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun workspace() = compose.onNodeWithTag("repository-workspace")

    private fun capture(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.cacheDir, name).outputStream().use { output ->
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, output)
        }
    }
}
