package com.opendroid.ai.core.security

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.nio.charset.StandardCharsets
import javax.inject.Inject
import javax.inject.Singleton

/** Stores the GitHub OAuth token only as an Android Keystore AES-GCM envelope. */
@Singleton
class GitHubTokenStore @Inject constructor(
    @ApplicationContext context: Context
) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val records = KeystoreSecretRecords(
        SharedPreferencesSecretRecordStorage(preferences),
        AndroidKeyStoreAeadCipher(KEY_ALIAS)
    )

    fun read(): String? = when (val result = records.read(TOKEN_KEY, TOKEN_AAD)) {
        is SecretRecordResult.Success -> result.value?.let { String(it, StandardCharsets.UTF_8) }
        SecretRecordResult.Unrecoverable, SecretRecordResult.StorageUnavailable -> null
    }

    fun write(token: String): Boolean = records.write(
        TOKEN_KEY,
        TOKEN_AAD,
        token.toByteArray(StandardCharsets.UTF_8)
    ) is SecretRecordResult.Success

    fun clear(): Boolean = records.removeRecord(TOKEN_KEY) is SecretRecordResult.Success

    private companion object {
        const val PREFERENCES = "github_secure_credentials"
        const val KEY_ALIAS = "github_oauth_token_v1"
        const val TOKEN_KEY = "oauth_access_token"
        const val TOKEN_AAD = "USMAN-AND-UMAR/GitHubOAuthToken/v1"
    }
}
