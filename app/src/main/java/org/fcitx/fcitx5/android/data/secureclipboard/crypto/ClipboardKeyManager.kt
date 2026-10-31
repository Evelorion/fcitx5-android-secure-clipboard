/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.secureclipboard.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.UserNotAuthenticatedException
import org.fcitx.fcitx5.android.data.secureclipboard.SecureClipboardError
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.ProviderException
import java.security.UnrecoverableKeyException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * Result of obtaining a key from the Android Keystore.
 *
 * [Failure.error] is always either [SecureClipboardError.KeyUnavailable] (key can be created /
 * unlocked later) or [SecureClipboardError.KeyInvalid] (existing key material is permanently
 * unusable and must not be silently replaced, otherwise all ciphertext would become unreadable).
 */
sealed interface ClipboardKeyResult {

    /** The key is ready to use. [keyVersion] identifies the on-disk key generation. */
    class Ready(val key: SecretKey, val keyVersion: Int) : ClipboardKeyResult

    /** The key is not usable. [cause] is safe to log (no key material is exposed by it). */
    class Failure(val error: SecureClipboardError, val cause: Throwable? = null) : ClipboardKeyResult
}

/**
 * Android Keystore based key manager for the encrypted clipboard database.
 *
 * Two keys are used, both generated inside the Android Keystore and therefore non-exportable:
 *  - [ENCRYPTION_KEY_ALIAS]: AES-256-GCM key for payloads and metadata.
 *  - [DEDUP_KEY_ALIAS]: HMAC-SHA256 key for the de-duplication tag (the tag itself is not a
 *    secret but is keyed so it can not be used as an offline dictionary of clipboard history).
 *
 * Key material is never written to SharedPreferences, the database, a file or a log. The manager
 * only returns in-memory [SecretKey] handles. A permanently invalidated key (for example after a
 * lock-screen change when user authentication was required) is reported as
 * [SecureClipboardError.KeyInvalid]; [KeyPermanentlyInvalidatedException] and
 * [UnrecoverableKeyException] are handled explicitly. The key is never regenerated over a broken
 * one: that would make existing ciphertext silently unreadable.
 */
class ClipboardKeyManager(
    private val keyStoreProvider: () -> KeyStore = { createAndroidKeyStore() }
) {

    companion object {
        /** Current key generation stored in every row. Bump together with a migration. */
        const val CURRENT_KEY_VERSION = 1

        const val ENCRYPTION_KEY_ALIAS = "secure_clipboard_aes_v1"
        const val DEDUP_KEY_ALIAS = "secure_clipboard_dedup_v1"

        private const val ANDROID_KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val AES_ALGORITHM = "AES"
        private const val DEDUP_KEY_SIZE_BITS = 256
        private const val AES_KEY_SIZE_BITS = 256

        private fun createAndroidKeyStore(): KeyStore =
            KeyStore.getInstance(ANDROID_KEYSTORE_PROVIDER).apply { load(null) }
    }

    private val keyStore: KeyStore by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { keyStoreProvider() }

    /** AES-256-GCM key used to encrypt clipboard payloads and their metadata. */
    fun encryptionKey(): ClipboardKeyResult =
        obtain(ENCRYPTION_KEY_ALIAS, expectedAlgorithm = AES_ALGORITHM) { generateEncryptionKey() }

    /**
     * HMAC-SHA256 key used for the de-duplication tag.
     *
     * The Android Keystore does not expose AES key bytes, so a separate HMAC key is required.
     */
    fun dedupKey(): ClipboardKeyResult =
        obtain(DEDUP_KEY_ALIAS, expectedAlgorithm = null) { generateDedupKey() }

    /** Non-throwing availability probe for the status UI. */
    fun isEncryptionKeyAvailable(): Boolean =
        try {
            encryptionKey() is ClipboardKeyResult.Ready
        } catch (e: Exception) {
            false
        }

    /**
     * Delete both clipboard keys. Only used when the user wipes the encrypted history and wants
     * to make the remaining ciphertext unreadable forever. Never call this during normal mode
     * switches: existing rows would become unreadable.
     */
    fun deleteKeys() {
        runCatching { keyStore.deleteEntry(ENCRYPTION_KEY_ALIAS) }
        runCatching { keyStore.deleteEntry(DEDUP_KEY_ALIAS) }
    }

    private fun obtain(
        alias: String,
        expectedAlgorithm: String?,
        generate: () -> SecretKey
    ): ClipboardKeyResult = try {
        val existing = keyStore.getKey(alias, null)
        if (existing != null) {
            if (existing !is SecretKey) {
                ClipboardKeyResult.Failure(SecureClipboardError.KeyInvalid)
            } else if (expectedAlgorithm != null &&
                !expectedAlgorithm.equals(existing.algorithm, ignoreCase = true)
            ) {
                // Wrong key type behind our alias: treat as invalid instead of overwriting it.
                ClipboardKeyResult.Failure(SecureClipboardError.KeyInvalid)
            } else {
                ClipboardKeyResult.Ready(existing, CURRENT_KEY_VERSION)
            }
        } else {
            ClipboardKeyResult.Ready(generate(), CURRENT_KEY_VERSION)
        }
    } catch (e: KeyPermanentlyInvalidatedException) {
        ClipboardKeyResult.Failure(SecureClipboardError.KeyInvalid, e)
    } catch (e: UnrecoverableKeyException) {
        ClipboardKeyResult.Failure(SecureClipboardError.KeyInvalid, e)
    } catch (e: UserNotAuthenticatedException) {
        ClipboardKeyResult.Failure(SecureClipboardError.KeyUnavailable, e)
    } catch (e: GeneralSecurityException) {
        ClipboardKeyResult.Failure(SecureClipboardError.KeyUnavailable, e)
    } catch (e: ProviderException) {
        // Some OEM providers wrap the permanent invalidation inside a ProviderException.
        val cause = e.cause
        val invalid = cause is KeyPermanentlyInvalidatedException || cause is UnrecoverableKeyException
        ClipboardKeyResult.Failure(
            if (invalid) SecureClipboardError.KeyInvalid else SecureClipboardError.KeyUnavailable,
            e
        )
    } catch (e: IllegalStateException) {
        ClipboardKeyResult.Failure(SecureClipboardError.KeyUnavailable, e)
    }

    private fun generateEncryptionKey(): SecretKey {
        val spec = KeyGenParameterSpec.Builder(
            ENCRYPTION_KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(AES_KEY_SIZE_BITS)
            // We never supply an IV on encryption: the provider generates a fresh random one,
            // which is exactly what "randomized encryption required" enforces.
            .setRandomizedEncryptionRequired(true)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE_PROVIDER)
            .apply { init(spec) }
            .generateKey()
    }

    private fun generateDedupKey(): SecretKey {
        val spec = KeyGenParameterSpec.Builder(
            DEDUP_KEY_ALIAS,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
        )
            .setKeySize(DEDUP_KEY_SIZE_BITS)
            .build()
        return KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_HMAC_SHA256,
            ANDROID_KEYSTORE_PROVIDER
        )
            .apply { init(spec) }
            .generateKey()
    }
}
