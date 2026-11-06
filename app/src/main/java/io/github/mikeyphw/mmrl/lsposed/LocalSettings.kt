package io.github.mikeyphw.mmrl.lsposed

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Stored locally, never backed up, never printed or included in CI evidence. */
internal object LocalSettings {
    private const val PREFS = "lsposed_local_settings_v1"
    private const val TOKEN_DATA = "github_token_sealed"
    private const val AUTO_CONNECT = "auto_connect_vector"
    private const val APPEARANCE = "appearance_preference"
    private const val ALIAS = "mmrl_lsposed_github_token_v1"

    fun autoConnect(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(AUTO_CONNECT, true)

    fun setAutoConnect(context: Context, enabled: Boolean) {
        check(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(AUTO_CONNECT, enabled).commit())
    }

    /** Ordinary local UI preference; never contains a token or privileged state. */
    fun appearance(context: Context): String {
        val stored = runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(APPEARANCE, null)
        }.getOrNull()
        return AppearancePreference.canonical(stored)
    }

    fun setAppearance(context: Context, value: String) {
        require(AppearancePreference.canonical(value) == value) { "Invalid appearance choice" }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(APPEARANCE, value).apply()
    }

    /** CI emulator is deliberately unprivileged; physical-device read is opt-out. */
    // A physical device running a GSI may have a `generic` fingerprint. Do not
    // suppress its requested automatic Vector connection on that basis alone.
    fun isEmulator(): Boolean = Build.HARDWARE.lowercase() in setOf("ranchu", "goldfish") ||
        Build.PRODUCT.lowercase().contains("sdk_gphone")

    fun hasGitHubToken(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).contains(TOKEN_DATA)

    fun saveGitHubToken(context: Context, token: String) {
        val value = token.trim()
        require(value.length in 20..512 && value.none { it.isWhitespace() || it == ':' }) {
            "Enter a valid, non-empty GitHub access token"
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val ciphertext = cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8))
        // GCM IV is authenticated on decryption. No plaintext token is persisted.
        val packed = cipher.iv + ciphertext
        val encoded = Base64.encodeToString(packed, Base64.NO_WRAP)
        check(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(TOKEN_DATA, encoded).commit())
    }

    /** Only the HTTPS api.github.com transport consumes this; never redirect or log it. */
    fun gitHubToken(context: Context): String? {
        val encoded = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(TOKEN_DATA, null) ?: return null
        val packed = Base64.decode(encoded, Base64.DEFAULT)
        require(packed.size > 28) { "Invalid sealed credential" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, packed.copyOfRange(0, 12)))
        return String(cipher.doFinal(packed.copyOfRange(12, packed.size)), StandardCharsets.UTF_8)
    }

    fun clearGitHubToken(context: Context) {
        check(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(TOKEN_DATA).commit())
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = store.getKey(ALIAS, null) as? SecretKey
        if (existing != null) return existing
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build())
        return generator.generateKey()
    }
}
