package com.threeastudio.gitclonepush.data.security

import android.content.Context
import android.util.Base64
import androidx.core.content.edit
import com.threeastudio.gitclonepush.core.security.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class AndroidSecureSessionStore(context: Context) : SecureTokenStore, PendingOAuthStore {
    private val preferences = context.applicationContext.getSharedPreferences("secure_session", Context.MODE_PRIVATE)
    private val keyAlias = "gitclonepush_session_key"

    override suspend fun read(): StoredAuthTokens? = withContext(Dispatchers.IO) { decode("tokens")?.let(::StoredAuthTokens) }
    override suspend fun write(tokens: StoredAuthTokens) = withContext(Dispatchers.IO) { encode("tokens", tokens.accessToken) }
    override suspend fun clear() = withContext(Dispatchers.IO) { preferences.edit { clear() } }
    override suspend fun readPending(): PendingOAuthTransaction? = withContext(Dispatchers.IO) {
        decode("oauth")?.split("|", limit = 2)?.takeIf { it.size == 2 }?.let { PendingOAuthTransaction(it[0], it[1]) }
    }
    override suspend fun writePending(transaction: PendingOAuthTransaction) = withContext(Dispatchers.IO) { encode("oauth", "${transaction.state}|${transaction.verifier}") }
    override suspend fun clearPending() = withContext(Dispatchers.IO) { preferences.edit { remove("oauth") } }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!keyStore.containsAlias(keyAlias)) {
            KeyGenerator.getInstance("AES", "AndroidKeyStore").apply {
                init(android.security.keystore.KeyGenParameterSpec.Builder(keyAlias, android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or android.security.keystore.KeyProperties.PURPOSE_DECRYPT).setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE).build())
            }.generateKey()
        }
        return (keyStore.getEntry(keyAlias, null) as KeyStore.SecretKeyEntry).secretKey
    }
    private fun encode(name: String, value: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8))
        preferences.edit { putString(name, Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)) }
    }
    private fun decode(name: String): String? {
        val raw = preferences.getString(name, null) ?: return null
        val bytes = Base64.decode(raw, Base64.NO_WRAP)
        require(bytes.size > 12) { "SECURITY_001_INVALID_SESSION_DATA" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        return String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), StandardCharsets.UTF_8)
    }
}
