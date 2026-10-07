package com.threeastudio.gitclonepush.data.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OAuthConfigurationTest {
    @Test
    fun `production redirect uri is the GitHub Pages callback`() {
        assertEquals(
            "https://aggiabintangrh.github.io/HomePageGitClonePush/oauth/callback.html",
            BuildConfigGitHubOAuthConfig().redirectUri
        )
    }

    @Test
    fun `backend configuration exposes the configured base url`() {
        assertTrue(BuildConfigOAuthBackendConfig().baseUrl.isEmpty() || BuildConfigOAuthBackendConfig().baseUrl.startsWith("https://"))
    }
}
