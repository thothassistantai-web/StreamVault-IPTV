package com.streamvault.data.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-GCM credential crypto backed by Android Keystore.
 *
 * Values are persisted as: enc:v1:<base64(iv + ciphertext)>
 */
object CredentialCrypto {
    private const val TAG = "CredentialCrypto"
    private const val KEYSTORE_TYPE = "AndroidKeyStore"
    private const val KEY_ALIAS = "streamvault_credentials"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_SIZE_BYTES = 12
    private const val AUTH_TAG_BITS = 128
    private const val PREFIX = "enc:v1:"

    fun encryptIfNeeded(value: String): String {
        if (value.isBlank() || value.startsWith(PREFIX)) return value

        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
            val iv = cipher.iv
            val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
            val packed = iv + encrypted
            PREFIX + java.util.Base64.getEncoder().encodeToString(packed)
        } catch (e: Exception) {
            // Do NOT fall back to plaintext — rethrow so the caller can surface the failure.
            Log.e(TAG, "Keystore encryption failed. Credential will NOT be stored.", e)
            throw SecurityException("Failed to encrypt credential: ${e.message}", e)
        }
    }

    fun decryptIfNeeded(value: String): String {
        if (!value.startsWith(PREFIX)) return value

        return try {
            val payload = value.removePrefix(PREFIX)
            val bytes = java.util.Base64.getDecoder().decode(payload)
            if (bytes.size <= IV_SIZE_BYTES) return value

            val iv = bytes.copyOfRange(0, IV_SIZE_BYTES)
            val ciphertext = bytes.copyOfRange(IV_SIZE_BYTES, bytes.size)

            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateSecretKey(),
                GCMParameterSpec(AUTH_TAG_BITS, iv)
            )
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (e: Exception) {
            // Decryption failed (e.g. key invalidated after device reset).
            // Return empty string — the caller will surface an auth failure rather than
            // silently sending a garbled ciphertext blob as the password.
            Log.e(TAG, "Keystore decryption failed. Stored credential is unreadable.", e)
            ""
        }
    }

    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_TYPE).apply { load(null) }
        val existing = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_TYPE)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .setUserAuthenticationRequired(false)
            .build()

        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }
}
