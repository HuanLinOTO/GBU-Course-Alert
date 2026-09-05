package me.huanlin.gbuca.data.local

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** 凭据仅存本机 Keystore 加密存储，不上传、不写日志。 */
class CredentialStore(context: Context) {

    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "gbuca_creds",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun save(username: String, password: String) {
        prefs.edit().putString("username", username).putString("password", password).apply()
    }

    val username: String? get() = prefs.getString("username", null)?.takeIf { it.isNotBlank() }
    val password: String? get() = prefs.getString("password", null)

    fun clear() = prefs.edit().clear().apply()
}
