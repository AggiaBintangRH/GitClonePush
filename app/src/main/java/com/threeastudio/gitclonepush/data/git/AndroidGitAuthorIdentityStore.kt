package com.threeastudio.gitclonepush.data.git

import android.content.Context
import com.threeastudio.gitclonepush.core.model.GitAuthorIdentity
import com.threeastudio.gitclonepush.domain.repository.GitAuthorIdentityStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AndroidGitAuthorIdentityStore(context: Context) : GitAuthorIdentityStore {
    private val preferences = context.getSharedPreferences("git_identity", Context.MODE_PRIVATE)
    override suspend fun read(): GitAuthorIdentity? = withContext(Dispatchers.IO) {
        val name = preferences.getString("name", null); val email = preferences.getString("email", null)
        if (name.isNullOrBlank() || email.isNullOrBlank()) null else GitAuthorIdentity(name, email)
    }
    override suspend fun write(identity: GitAuthorIdentity) = withContext(Dispatchers.IO) { preferences.edit().putString("name", identity.name.trim()).putString("email", identity.email.trim()).apply() }
}
