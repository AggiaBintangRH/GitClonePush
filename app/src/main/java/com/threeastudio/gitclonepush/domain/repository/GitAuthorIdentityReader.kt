package com.threeastudio.gitclonepush.domain.repository

import com.threeastudio.gitclonepush.core.model.GitAuthorIdentity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/** Read-only identity used by Git mutations and account settings. */
interface GitAuthorIdentityReader {
    fun observe(): Flow<GitAuthorIdentity?>
    suspend fun read(): GitAuthorIdentity? = observe().first()
}
