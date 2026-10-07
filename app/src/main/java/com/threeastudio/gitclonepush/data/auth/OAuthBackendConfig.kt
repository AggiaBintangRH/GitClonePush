package com.threeastudio.gitclonepush.data.auth

import com.threeastudio.gitclonepush.BuildConfig

interface OAuthBackendConfig {
    val baseUrl: String
}

class BuildConfigOAuthBackendConfig : OAuthBackendConfig {
    override val baseUrl: String = BuildConfig.OAUTH_BACKEND_BASE_URL
}
