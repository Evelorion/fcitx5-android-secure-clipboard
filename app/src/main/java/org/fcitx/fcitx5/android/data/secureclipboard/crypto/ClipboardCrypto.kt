/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.secureclipboard.crypto

import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.BadPaddingException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Typed failure of [ClipboardCrypto].
 *
 * Messages and causes never contain plaintext, key material or IV bytes: only the failure
 * category is exposed so it can be mapped to [org.fcitx.fcitx5.android.data.secureclipboard.SecureClipboardError]
 * and logged safely.
 */
sealed class ClipboardCryptoException(message: String, cause: Throwable? = null) :
    Exception(message, cause) {

    /** Encryption with the supplied key failed (provider error, unusable key, ...). */
    class EncryptionFailed(cause: Throwable? = null) :
        ClipboardCryptoException("Secure clipboard encryption failed", cause)

    /** Decryption failed for a non-authentication reason (provider error, malformed key, ...). */
    class DecryptionFailed(cause: Throwable? = null) :
        ClipboardCryptoException("Secure clipboard decryption failed", cause)

    /** The ciphertext or its AAD was modified: the GCM tag did not authenticate. */
    class AuthenticationFailed(cause: Throwable? = null) :
        ClipboardCryptoException("Secure clipboard authentication failed", cause)

    /** The supplied IV is not a 96-bit nonce produced by this class (empty / all-zero / wrong size). */
    class InvalidIv(val ivSize: Int) :
        ClipboardCryptoException("Invalid secure clipboard IV size: $ivSize")

    /** The supplied key is not an AES key and can not be used with AES-256-GCM. */
    class InvalidKey(cause: Throwable? = null) :
        ClipboardCryptoException("Invalid secure clipboard key", cause)
}

/**
 * A self-contained AES-256-GCM payload: the random 96-bit [iv] and the [ciphertext] which
 * already includes the 128-bit authentication tag.
 *
 * [toString] intentionally only exposes sizes so accidental logging stays harmless.
 */
class EncryptedPayload(val iv: ByteArray, val ciphertext: ByteArray) {

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EncryptedPayload) return false
        return iv.contentEquals(other.iv) && ciphertext.contentEquals(other.ciphertext)
    }

    override fun hashCode(): Int = 31 * iv.contentHashCode() + ciphertext.contentHashCode()

    override fun toString(): String =
        "EncryptedPayload(ivSize=${iv.size}, ciphertextSize=${ciphertext.size})"
}

/**
 * Pure-JVM AES-256-GCM implementation used by the encrypted clipboard storage.
 *
 * Design notes:
 *  - The [SecretKey] is always injected per call, so the class stays independent of the Android
 *    Keystore and can be covered by JVM unit tests with a plain `javax.crypto` key.
 *  - Encryption always lets the provider generate a fresh 96-bit IV from [secureRandom]
 *    (SecureRandom). A nonce is never supplied by callers, which keeps the Android Keystore
 *    "randomized encryption required" default intact.
 *  - A 128-bit GCM tag is used and optional associated data ([aad]) is authenticated but not
 *    encrypted. The storage layer binds stable record metadata as AAD so rows can not be
 *    transplanted between different sources/key versions without failing authentication.
 *  - Failures are thrown as [ClipboardCryptoException] subclasses; plaintext never appears in a
 *    message, cause message or log statement.
 */
class ClipboardCrypto(
    private val secureRandom: SecureRandom = SecureRandom()
) {

    companion object {
        /** AES-256 key size in bits. */
        const val KEY_SIZE_BITS = 256

        /** GCM nonce size in bytes (96-bit, the only size recommended by NIST SP 800-38D). */
        const val IV_SIZE_BYTES = 12

        /** GCM authentication tag size in bits (128-bit). */
        const val TAG_SIZE_BITS = 128

        /** GCM tag size in bytes. */
        const val TAG_SIZE_BYTES = TAG_SIZE_BITS / 8

        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val ALGORITHM = "AES"
        private val EMPTY = ByteArray(0)

        /** Generate a fresh AES-256 key (used by tests and by non-Keystore fallbacks). */
        fun generateAesKey(secureRandom: SecureRandom = SecureRandom()): SecretKey =
            KeyGenerator.getInstance(ALGORITHM).apply {
                init(KEY_SIZE_BITS, secureRandom)
            }.generateKey()

        private fun isAllZero(bytes: ByteArray): Boolean {
            var accumulator = 0
            for (byte in bytes) accumulator = accumulator or byte.toInt()
            return accumulator == 0
        }
    }

    /**
     * Encrypt [plaintext] with [key] and authenticate [aad].
     *
     * A fresh random IV is generated by the cipher provider for every call. The returned
     * [EncryptedPayload] can be persisted as-is; the IV must be stored next to it.
     */
    fun encrypt(key: SecretKey, plaintext: ByteArray, aad: ByteArray = EMPTY): EncryptedPayload {
        checkKey(key)
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key, secureRandom)
            if (aad.isNotEmpty()) cipher.updateAAD(aad)
            val ciphertext = cipher.doFinal(plaintext)
            val iv = cipher.iv ?: throw ClipboardCryptoException.EncryptionFailed()
            if (iv.size != IV_SIZE_BYTES || isAllZero(iv)) {
                // A provider returning a bogus nonce is treated as an encryption failure.
                throw ClipboardCryptoException.EncryptionFailed()
            }
            return EncryptedPayload(iv.copyOf(), ciphertext)
        } catch (e: ClipboardCryptoException) {
            throw e
        } catch (e: GeneralSecurityException) {
            throw ClipboardCryptoException.EncryptionFailed(e)
        }
    }

    /**
     * Decrypt and authenticate [payload] with [key] and [aad].
     *
     * @throws ClipboardCryptoException.InvalidIv if the IV is empty, all zero or not 96-bit
     * @throws ClipboardCryptoException.AuthenticationFailed if the tag does not match
     * @throws ClipboardCryptoException.DecryptionFailed for any other provider failure
     */
    fun decrypt(key: SecretKey, payload: EncryptedPayload, aad: ByteArray = EMPTY): ByteArray {
        checkKey(key)
        val iv = payload.iv
        if (iv.size != IV_SIZE_BYTES || isAllZero(iv)) {
            throw ClipboardCryptoException.InvalidIv(iv.size)
        }
        if (payload.ciphertext.size < TAG_SIZE_BYTES) {
            // Too short to even contain a GCM tag: authentication can not succeed.
            throw ClipboardCryptoException.AuthenticationFailed()
        }
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_SIZE_BITS, iv))
            if (aad.isNotEmpty()) cipher.updateAAD(aad)
            return cipher.doFinal(payload.ciphertext)
        } catch (e: AEADBadTagException) {
            throw ClipboardCryptoException.AuthenticationFailed(e)
        } catch (e: BadPaddingException) {
            throw ClipboardCryptoException.AuthenticationFailed(e)
        } catch (e: GeneralSecurityException) {
            throw ClipboardCryptoException.DecryptionFailed(e)
        }
    }

    private fun checkKey(key: SecretKey) {
        try {
            if (!ALGORITHM.equals(key.algorithm, ignoreCase = true)) {
                throw ClipboardCryptoException.InvalidKey()
            }
        } catch (e: ClipboardCryptoException) {
            throw e
        } catch (e: Exception) {
            throw ClipboardCryptoException.InvalidKey(e)
        }
    }
}

/**
 * Associated data builder for the encrypted clipboard rows.
 *
 * AAD binds payload/metadata to the storage schema version, the key version and the capture
 * source, so modifying those stored (plaintext) columns makes authentication fail instead of
 * silently reinterpreting the content. The record creation time is deliberately *not* bound:
 * de-duplication bumps it without re-encrypting the payload.
 *
 * The format is deterministic ASCII (no secrets, safe to compute in unit tests):
 * `fcitx5-secure-clipboard|v1|payload|1|system_clipboard`
 */
object SecureClipboardAad {

    private const val SCHEME = "fcitx5-secure-clipboard"
    private const val SCHEME_VERSION = 1

    fun payload(keyVersion: Int, sourceType: String): ByteArray =
        build("payload", keyVersion, sourceType)

    fun metadata(keyVersion: Int, sourceType: String): ByteArray =
        build("metadata", keyVersion, sourceType)

    private fun build(purpose: String, keyVersion: Int, sourceType: String): ByteArray =
        "$SCHEME|v$SCHEME_VERSION|$purpose|$keyVersion|$sourceType".toByteArray(Charsets.UTF_8)
}
