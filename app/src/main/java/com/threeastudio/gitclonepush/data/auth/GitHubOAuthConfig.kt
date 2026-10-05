package com.threeastudio.gitclonepush.data.auth

import com.threeastudio.gitclonepush.BuildConfig

interface GitHubOAuthConfig {
    val clientId: String
    val redirectUri: String
}

class BuildConfigGitHubOAuthConfig : GitHubOAuthConfig {
    override val clientId: String = BuildConfig.GITHUB_CLIENT_ID
    override val redirectUri: String = BuildConfig.GITHUB_REDIRECT_URI
}
