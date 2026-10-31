/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.secureclipboard

import org.fcitx.fcitx5.android.data.secureclipboard.crypto.ClipboardCrypto
import org.fcitx.fcitx5.android.data.secureclipboard.crypto.ClipboardCryptoException
import org.fcitx.fcitx5.android.data.secureclipboard.crypto.EncryptedPayload
import org.fcitx.fcitx5.android.data.secureclipboard.crypto.SecureClipboardAad
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.spec.SecretKeySpec

/**
 * JVM tests for [ClipboardCrypto] / [SecureClipboardAad]: no Android classes are involved.
 */
class ClipboardCryptoTest {

    private val crypto = ClipboardCrypto()
    private val key = ClipboardCrypto.generateAesKey()
    private val aad = SecureClipboardAad.payload(1, "system_clipboard")

    private fun encrypt(plaintext: String, aadBytes: ByteArray = aad): EncryptedPayload =
        crypto.encrypt(key, plaintext.toByteArray(Charsets.UTF_8), aadBytes)

    private fun flipByte(bytes: ByteArray, index: Int): ByteArray {
        val copy = bytes.copyOf()
        copy[index] = (copy[index].toInt() xor 0x01).toByte()
        return copy
    }

    private fun hex(bytes: ByteArray): String {
        val builder = StringBuilder(bytes.size * 2)
        for (byte in bytes) {
            val value = byte.toInt() and 0xFF
            builder.append(Character.forDigit(value ushr 4, 16))
            builder.append(Character.forDigit(value and 0x0F, 16))
        }
        return builder.toString()
    }

    @Test
    fun roundTripPreservesPlaintext() {
        val plaintext = "hello 世界\n\tand more".toByteArray(Charsets.UTF_8)
        val payload = crypto.encrypt(key, plaintext, aad)
        assertArrayEquals(plaintext, crypto.decrypt(key, payload, aad))
    }

    @Test
    fun emptyPlaintextRoundTrip() {
        val payload = crypto.encrypt(key, ByteArray(0), aad)
        assertEquals(ClipboardCrypto.TAG_SIZE_BYTES, payload.ciphertext.size)
        assertArrayEquals(ByteArray(0), crypto.decrypt(key, payload, aad))
    }

    @Test
    fun ciphertextSizeIsPlaintextPlusTag() {
        val plaintext = "abcdef".toByteArray(Charsets.UTF_8)
        val payload = crypto.encrypt(key, plaintext, aad)
        assertEquals(plaintext.size + 16, payload.ciphertext.size)
        assertEquals(ClipboardCrypto.IV_SIZE_BYTES, payload.iv.size)
    }

    @Test
    fun ciphertextDoesNotContainPlaintext() {
        val plaintext = "do-not-store-this-in-plaintext".toByteArray(Charsets.UTF_8)
        val payload = crypto.encrypt(key, plaintext, aad)
        assertFalse(String(payload.ciphertext, Charsets.ISO_8859_1)
            .contains(String(plaintext, Charsets.ISO_8859_1)))
    }

    @Test
    fun everyEncryptionUsesFreshIv() {
        val ivs = HashSet<String>(256)
        repeat(200) {
            val payload = encrypt("same plaintext every time")
            assertEquals(ClipboardCrypto.IV_SIZE_BYTES, payload.iv.size)
            assertFalse(payload.iv.all { byte -> byte == 0.toByte() })
            assertTrue("IV reuse detected", ivs.add(hex(payload.iv)))
        }
    }

    @Test
    fun tamperedCiphertextFailsAuthentication() {
        val payload = encrypt("tamper me")
        val tampered = EncryptedPayload(payload.iv, flipByte(payload.ciphertext, 0))
        assertThrows(ClipboardCryptoException.AuthenticationFailed::class.java) {
            crypto.decrypt(key, tampered, aad)
        }
    }

    @Test
    fun tamperedTagFailsAuthentication() {
        val payload = encrypt("tamper the tag")
        val lastIndex = payload.ciphertext.size - 1
        val tampered = EncryptedPayload(payload.iv, flipByte(payload.ciphertext, lastIndex))
        assertThrows(ClipboardCryptoException.AuthenticationFailed::class.java) {
            crypto.decrypt(key, tampered, aad)
        }
    }

    @Test
    fun tamperedIvFailsAuthentication() {
        val payload = encrypt("tamper the iv")
        val tampered = EncryptedPayload(flipByte(payload.iv, 0), payload.ciphertext)
        assertThrows(ClipboardCryptoException.AuthenticationFailed::class.java) {
            crypto.decrypt(key, tampered, aad)
        }
    }

    @Test
    fun aadMismatchFailsAuthentication() {
        val payload = encrypt("bound to the first aad")
        val otherAad = SecureClipboardAad.payload(1, "share")
        assertNotEquals(hex(aad), hex(otherAad))
        assertThrows(ClipboardCryptoException.AuthenticationFailed::class.java) {
            crypto.decrypt(key, payload, otherAad)
        }
    }

    @Test
    fun wrongKeyFailsAuthentication() {
        val payload = encrypt("wrong key")
        val otherKey = ClipboardCrypto.generateAesKey()
        assertThrows(ClipboardCryptoException.AuthenticationFailed::class.java) {
            crypto.decrypt(otherKey, payload, aad)
        }
    }

    @Test
    fun emptyIvRejected() {
        val payload = encrypt("empty iv")
        val broken = EncryptedPayload(ByteArray(0), payload.ciphertext)
        val error = assertThrows(ClipboardCryptoException.InvalidIv::class.java) {
            crypto.decrypt(key, broken, aad)
        }
        assertEquals(0, error.ivSize)
    }

    @Test
    fun allZeroIvRejected() {
        val payload = encrypt("zero iv")
        val broken = EncryptedPayload(ByteArray(ClipboardCrypto.IV_SIZE_BYTES), payload.ciphertext)
        val error = assertThrows(ClipboardCryptoException.InvalidIv::class.java) {
            crypto.decrypt(key, broken, aad)
        }
        assertEquals(ClipboardCrypto.IV_SIZE_BYTES, error.ivSize)
    }

    @Test
    fun oversizedIvRejected() {
        val payload = encrypt("oversized iv")
        val broken = EncryptedPayload(ByteArray(ClipboardCrypto.IV_SIZE_BYTES + 1) { 1 }, payload.ciphertext)
        assertThrows(ClipboardCryptoException.InvalidIv::class.java) {
            crypto.decrypt(key, broken, aad)
        }
    }

    @Test
    fun ciphertextShorterThanTagIsRejected() {
        val payload = encrypt("short ciphertext")
        val broken = EncryptedPayload(payload.iv, payload.ciphertext.copyOfRange(0, 8))
        assertThrows(ClipboardCryptoException.AuthenticationFailed::class.java) {
            crypto.decrypt(key, broken, aad)
        }
    }

    @Test
    fun nonAesKeyRejected() {
        val hmacKey = SecretKeySpec(ByteArray(32) { 1 }, "HmacSHA256")
        assertThrows(ClipboardCryptoException.InvalidKey::class.java) {
            crypto.encrypt(hmacKey, "text".toByteArray(), aad)
        }
        assertThrows(ClipboardCryptoException.InvalidKey::class.java) {
            crypto.decrypt(hmacKey, encrypt("text"), aad)
        }
    }

    @Test
    fun failuresDoNotLeakPlaintext() {
        val secret = "extremely-secret-clipboard-content"
        val payload = encrypt(secret)
        val tampered = EncryptedPayload(payload.iv, flipByte(payload.ciphertext, 1))
        val error = assertThrows(ClipboardCryptoException.AuthenticationFailed::class.java) {
            crypto.decrypt(key, tampered, aad)
        }
        assertFalse(error.message.orEmpty().contains(secret))
        assertFalse(error.cause?.message.orEmpty().contains(secret))
        assertFalse(payload.toString().contains(secret))
    }

    @Test
    fun aadIsDeterministicAndPurposeSeparated() {
        assertArrayEquals(
            SecureClipboardAad.payload(1, "system_clipboard"),
            SecureClipboardAad.payload(1, "system_clipboard")
        )
        assertFalse(
            SecureClipboardAad.payload(1, "system_clipboard")
                .contentEquals(SecureClipboardAad.metadata(1, "system_clipboard"))
        )
        assertFalse(
            SecureClipboardAad.payload(1, "system_clipboard")
                .contentEquals(SecureClipboardAad.payload(2, "system_clipboard"))
        )
        // AAD must never contain plaintext; it only carries non-sensitive bindings.
        val asText = String(SecureClipboardAad.payload(1, "ime_selection"), Charsets.UTF_8)
        assertTrue(asText.startsWith("fcitx5-secure-clipboard|"))
    }
}
