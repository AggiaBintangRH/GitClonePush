package com.threeastudio.gitclonepush.domain.usecase

import com.threeastudio.gitclonepush.core.model.AuthState
import com.threeastudio.gitclonepush.core.model.AuthenticatedUser
import com.threeastudio.gitclonepush.core.model.GitAuthorIdentity
import com.threeastudio.gitclonepush.domain.repository.AuthRepository
import com.threeastudio.gitclonepush.domain.repository.GitAuthorIdentityReader
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Derives identity from the active account; never reads a manual setting or a token. */
class SessionGitAuthorIdentityReader(private val authRepository: AuthRepository) : GitAuthorIdentityReader {
    override fun observe(): Flow<GitAuthorIdentity?> = authRepository.observeAuthState()
        .map { state -> (state as? AuthState.Authenticated)?.user?.commitIdentity() }
        .distinctUntilChanged()

    private fun AuthenticatedUser.commitIdentity(): GitAuthorIdentity? {
        val username = login.trim()
        if (id <= 0 || !LOGIN_PATTERN.matches(username)) return null
        val name = displayName?.trim()?.takeIf { value ->
            value.isNotBlank() && value.none { it.isISOControl() || it in "<>" }
        } ?: username
        val publicEmail = email?.trim()?.takeIf { EMAIL_PATTERN.matches(it) }
        return GitAuthorIdentity(name, publicEmail ?: "$id+$username@users.noreply.github.com")
    }

    private companion object {
        val LOGIN_PATTERN = Regex("[A-Za-z0-9_-]+")
        val EMAIL_PATTERN = Regex("[^\\s<>@\\p{Cntrl}]+@[^\\s<>@\\p{Cntrl}]+\\.[^\\s<>@\\p{Cntrl}]+")
    }
}
