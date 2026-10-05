package com.threeastudio.gitclonepush.core.security

data class StoredAuthTokens(val accessToken: String)
interface SecureTokenStore { suspend fun read(): StoredAuthTokens?; suspend fun write(tokens: StoredAuthTokens); suspend fun clear() }
data class PendingOAuthTransaction(val state: String, val verifier: String)
interface PendingOAuthStore { suspend fun readPending(): PendingOAuthTransaction?; suspend fun writePending(transaction: PendingOAuthTransaction); suspend fun clearPending() }
