/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.secureclipboard

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.fcitx.fcitx5.android.data.secureclipboard.export.SecureClipboardExportCrypto
import org.fcitx.fcitx5.android.data.secureclipboard.export.SecureClipboardExportException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * JVM tests for [SecureClipboardExportCrypto].
 *
 * The tests do not use any Android class: they exercise the AES-256-GCM / PBKDF2 primitives with
 * plain `javax.crypto` and additionally decrypt the file with an independent implementation to
 * pin the documented on-disk format.
 */
class SecureClipboardExportCryptoTest {

    private val magic = SecureClipboardExportCrypto.MAGIC_TEXT.toByteArray(Charsets.US_ASCII)
    private val headerSize = magic.size + 1 +
            SecureClipboardExportCrypto.SALT_SIZE_BYTES +
            SecureClipboardExportCrypto.IV_SIZE_BYTES

    private fun passphrase(): CharArray = "correct horse battery staple".toCharArray()

    private fun record(
        id: Int,
        text: String,
        source: CaptureSource = CaptureSource.ProcessText,
        timestamp: Long = 1_000L + id,
        pinned: Boolean = false,
    ): SecureClipboardRecord = SecureClipboardRecord(
        id = id,
        text = text,
        source = source,
        timestamp = timestamp,
        pinned = pinned,
    )

    /* =================== independent format verification helpers =================== */

    private fun nativeDeriveKey(passphrase: CharArray, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(
            passphrase,
            salt,
            SecureClipboardExportCrypto.PBKDF2_ITERATIONS,
            SecureClipboardExportCrypto.KEY_SIZE_BITS,
        )
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(spec)
                .encoded
        } finally {
            spec.clearPassword()
        }
    }

    /** Decrypt the export with a from-scratch implementation of the documented format. */
    @Throws(GeneralSecurityException::class)
    private fun manuallyDecryptJson(passphrase: CharArray, data: ByteArray): String {
        require(data.size > headerSize)
        val version = data[magic.size].toInt() and 0xFF
        assertEquals(SecureClipboardExportCrypto.FORMAT_VERSION, version)
        val saltStart = magic.size + 1
        val saltEnd = saltStart + SecureClipboardExportCrypto.SALT_SIZE_BYTES
        val ivEnd = saltEnd + SecureClipboardExportCrypto.IV_SIZE_BYTES
        val salt = data.copyOfRange(saltStart, saltEnd)
        val iv = data.copyOfRange(saltEnd, ivEnd)
        val ciphertext = data.copyOfRange(ivEnd, data.size)
        val header = data.copyOfRange(0, saltEnd)
        val key = nativeDeriveKey(passphrase, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(SecureClipboardExportCrypto.TAG_SIZE_BITS, iv),
        )
        cipher.updateAAD(header)
        return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }

    /* =================== round trip =================== */

    @Test
    fun roundTripPreservesOrderAndFields() {
        val records = listOf(
            record(1, "first", CaptureSource.ProcessText, 111L, pinned = true),
            record(2, "second", CaptureSource.SystemClipboard, 222L),
            record(3, "third", CaptureSource.Share, 333L, pinned = true),
            record(4, "fourth", CaptureSource.ImeSelection, 444L),
        )
        val decrypted = SecureClipboardExportCrypto.decrypt(
            passphrase(),
            SecureClipboardExportCrypto.encrypt(passphrase(), records, exportedAt = 1_234L),
        )
        assertEquals(records.size, decrypted.size)
        records.zip(decrypted).forEach { (expected, actual) ->
            assertEquals(expected.text, actual.text)
            assertEquals(expected.source, actual.source)
            assertEquals(expected.timestamp, actual.timestamp)
            assertEquals(expected.pinned, actual.pinned)
            // Exported records never carry a database id: import assigns a fresh one.
            assertEquals(0, actual.id)
        }
    }

    @Test
    fun roundTripWithUnicodeEmojiAndLongText() {
        val longText = "长".repeat(20_000)
        val records = listOf(
            record(1, "中文剪贴板内容，包含标点。"),
            record(2, "emoji 😀🔐👩‍👩‍👧‍👦 and combining é"),
            record(3, longText),
            record(4, "multi\nline\r\ntext\ttabbed"),
        )
        val decrypted = SecureClipboardExportCrypto.decrypt(
            passphrase(),
            SecureClipboardExportCrypto.encrypt(passphrase(), records),
        )
        assertEquals(records.map { it.text }, decrypted.map { it.text })
        assertEquals(longText.length, decrypted[2].text.length)
    }

    @Test
    fun emptyRecordsRoundTrip() {
        val data = SecureClipboardExportCrypto.encrypt(passphrase(), emptyList(), exportedAt = 5L)
        assertTrue(data.size > headerSize + SecureClipboardExportCrypto.TAG_SIZE_BYTES)
        assertEquals(emptyList<SecureClipboardRecord>(), SecureClipboardExportCrypto.decrypt(passphrase(), data))
    }

    @Test
    fun emptyPassphraseRoundTrip() {
        val records = listOf(record(1, "text"))
        val data = SecureClipboardExportCrypto.encrypt(CharArray(0), records)
        assertEquals("text", SecureClipboardExportCrypto.decrypt(CharArray(0), data).single().text)
    }

    /* =================== format layout =================== */

    @Test
    fun fileLayoutMatchesSpec() {
        val records = listOf(record(1, "layout"))
        val exportedAt = 987_654_321L
        val data = SecureClipboardExportCrypto.encrypt(passphrase(), records, exportedAt = exportedAt)
        assertTrue(data.size > headerSize + SecureClipboardExportCrypto.TAG_SIZE_BYTES)
        assertArrayEquals(magic, data.copyOfRange(0, magic.size))
        assertEquals(
            SecureClipboardExportCrypto.FORMAT_VERSION,
            data[magic.size].toInt() and 0xFF,
        )
        // salt and iv are 16 and 12 bytes, header ends at 40 for "FCSC-EXPORT".
        assertEquals(11 + 1 + 16 + 12, headerSize)
        assertEquals(40, headerSize)
        val salt = data.copyOfRange(magic.size + 1, magic.size + 17)
        val iv = data.copyOfRange(magic.size + 17, magic.size + 29)
        assertEquals(16, salt.size)
        assertEquals(12, iv.size)
        // The encrypted file must not leak the plaintext.
        val asIso = String(data, Charsets.ISO_8859_1)
        assertFalse(asIso.contains("layout"))
    }

    @Test
    fun payloadJsonMatchesSpec() {
        val records = listOf(
            record(1, "json first", CaptureSource.Share, 42L, pinned = true),
            record(2, "json second", CaptureSource.ImeSelection, 43L),
        )
        val exportedAt = 1_111_222_333L
        val data = SecureClipboardExportCrypto.encrypt(passphrase(), records, exportedAt = exportedAt)
        val root = Json.parseToJsonElement(manuallyDecryptJson(passphrase(), data)).jsonObject

        assertEquals(SecureClipboardExportCrypto.FORMAT_VERSION, root["version"]!!.jsonPrimitive.int)
        assertEquals(exportedAt, root["exportedAt"]!!.jsonPrimitive.long)
        val array = root["records"]!!.jsonArray
        assertEquals(2, array.size)
        val first = array[0].jsonObject
        assertEquals("json first", first["text"]!!.jsonPrimitive.content)
        assertEquals(CaptureSource.Share.storageValue, first["source"]!!.jsonPrimitive.content)
        assertEquals(42L, first["timestamp"]!!.jsonPrimitive.long)
        assertTrue(first["pinned"]!!.jsonPrimitive.boolean)
        val second = array[1].jsonObject
        assertEquals(CaptureSource.ImeSelection.storageValue, second["source"]!!.jsonPrimitive.content)
        assertFalse(second["pinned"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun everyExportUsesFreshSaltAndIv() {
        val records = listOf(record(1, "same input"))
        val first = SecureClipboardExportCrypto.encrypt(passphrase(), records)
        val second = SecureClipboardExportCrypto.encrypt(passphrase(), records)
        val saltStart = magic.size + 1
        val saltEnd = saltStart + SecureClipboardExportCrypto.SALT_SIZE_BYTES
        val ivEnd = saltEnd + SecureClipboardExportCrypto.IV_SIZE_BYTES
        assertFalse(
            "salt must be random per export",
            first.copyOfRange(saltStart, saltEnd).contentEquals(second.copyOfRange(saltStart, saltEnd))
        )
        assertFalse(
            "iv must be random per export",
            first.copyOfRange(saltEnd, ivEnd).contentEquals(second.copyOfRange(saltEnd, ivEnd))
        )
        assertFalse(first.contentEquals(second))
        // Both files still decrypt to the same content.
        assertEquals("same input", SecureClipboardExportCrypto.decrypt(passphrase(), first).single().text)
        assertEquals("same input", SecureClipboardExportCrypto.decrypt(passphrase(), second).single().text)
    }

    /* =================== failure modes =================== */

    @Test
    fun wrongPassphraseFails() {
        val data = SecureClipboardExportCrypto.encrypt(passphrase(), listOf(record(1, "secret")))
        val error = assertThrows(SecureClipboardExportException.BadPassphraseOrCorrupted::class.java) {
            SecureClipboardExportCrypto.decrypt("wrong passphrase".toCharArray(), data)
        }
        assertFalse(error.message.orEmpty().contains("secret"))
        assertFalse(error.cause?.message.orEmpty().contains("secret"))
    }

    @Test
    fun tamperedCiphertextFails() {
        val data = SecureClipboardExportCrypto.encrypt(passphrase(), listOf(record(1, "tamper me")))
        val tampered = data.copyOf()
        tampered[headerSize + 3] = (tampered[headerSize + 3].toInt() xor 0x01).toByte()
        assertThrows(SecureClipboardExportException.BadPassphraseOrCorrupted::class.java) {
            SecureClipboardExportCrypto.decrypt(passphrase(), tampered)
        }
    }

    @Test
    fun tamperedTagFails() {
        val data = SecureClipboardExportCrypto.encrypt(passphrase(), listOf(record(1, "tamper tag")))
        val tampered = data.copyOf()
        tampered[tampered.size - 1] = (tampered[tampered.size - 1].toInt() xor 0x01).toByte()
        assertThrows(SecureClipboardExportException.BadPassphraseOrCorrupted::class.java) {
            SecureClipboardExportCrypto.decrypt(passphrase(), tampered)
        }
    }

    @Test
    fun tamperedSaltFailsAuthentication() {
        val data = SecureClipboardExportCrypto.encrypt(passphrase(), listOf(record(1, "salt")))
        val tampered = data.copyOf()
        tampered[magic.size + 1] = (tampered[magic.size + 1].toInt() xor 0x01).toByte()
        assertThrows(SecureClipboardExportException.BadPassphraseOrCorrupted::class.java) {
            SecureClipboardExportCrypto.decrypt(passphrase(), tampered)
        }
    }

    @Test
    fun tamperedIvFailsAuthentication() {
        val data = SecureClipboardExportCrypto.encrypt(passphrase(), listOf(record(1, "iv")))
        val tampered = data.copyOf()
        val ivStart = magic.size + 1 + SecureClipboardExportCrypto.SALT_SIZE_BYTES
        tampered[ivStart] = (tampered[ivStart].toInt() xor 0x01).toByte()
        assertThrows(SecureClipboardExportException.BadPassphraseOrCorrupted::class.java) {
            SecureClipboardExportCrypto.decrypt(passphrase(), tampered)
        }
    }

    @Test
    fun tamperedMagicIsInvalidFormat() {
        val data = SecureClipboardExportCrypto.encrypt(passphrase(), listOf(record(1, "magic")))
        val tampered = data.copyOf()
        tampered[0] = 'X'.code.toByte()
        assertThrows(SecureClipboardExportException.InvalidFormat::class.java) {
            SecureClipboardExportCrypto.decrypt(passphrase(), tampered)
        }
    }

    @Test
    fun unsupportedVersionIsInvalidFormat() {
        val data = SecureClipboardExportCrypto.encrypt(passphrase(), listOf(record(1, "version")))
        val tampered = data.copyOf()
        tampered[magic.size] = 2
        assertThrows(SecureClipboardExportException.InvalidFormat::class.java) {
            SecureClipboardExportCrypto.decrypt(passphrase(), tampered)
        }
    }

    @Test
    fun truncatedFileIsInvalidFormat() {
        assertThrows(SecureClipboardExportException.InvalidFormat::class.java) {
            SecureClipboardExportCrypto.decrypt(passphrase(), ByteArray(12))
        }
        val data = SecureClipboardExportCrypto.encrypt(passphrase(), listOf(record(1, "truncate")))
        assertThrows(SecureClipboardExportException.InvalidFormat::class.java) {
            SecureClipboardExportCrypto.decrypt(passphrase(), data.copyOfRange(0, 20))
        }
    }

    @Test
    fun nonExportBytesAreInvalidFormat() {
        val garbage = ByteArray(128) { (it * 13 + 7).toByte() }
        assertThrows(SecureClipboardExportException.InvalidFormat::class.java) {
            SecureClipboardExportCrypto.decrypt(passphrase(), garbage)
        }
    }

    @Test
    fun tooManyRecordsAreRejected() {
        val records = (0..SecureClipboardExportCrypto.MAX_EXPORT_RECORDS).map { record(it, "x") }
        assertThrows(IllegalArgumentException::class.java) {
            SecureClipboardExportCrypto.encrypt(passphrase(), records)
        }
    }

    @Test
    fun maximumRecordCountIsAccepted() {
        val records = (0 until SecureClipboardExportCrypto.MAX_EXPORT_RECORDS).map { record(it, "x") }
        val data = SecureClipboardExportCrypto.encrypt(passphrase(), records)
        assertEquals(SecureClipboardExportCrypto.MAX_EXPORT_RECORDS, SecureClipboardExportCrypto.decrypt(passphrase(), data).size)
    }

    /* =================== PBKDF2 fallback parity =================== */

    @Test
    fun pbkdf2FallbackMatchesPlatformImplementation() {
        val salt = ByteArray(SecureClipboardExportCrypto.SALT_SIZE_BYTES) { (it * 7 + 3).toByte() }
        val iterations = 1_000
        val fallback = SecureClipboardExportCrypto.pbkdf2HmacSha256(
            "passphrase-密码".toCharArray(),
            salt,
            iterations,
            SecureClipboardExportCrypto.KEY_SIZE_BYTES,
        )
        val spec = PBEKeySpec(
            "passphrase-密码".toCharArray(),
            salt,
            iterations,
            SecureClipboardExportCrypto.KEY_SIZE_BITS,
        )
        val platform = try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
        assertArrayEquals(platform, fallback)
        assertEquals(32, fallback.size)
        assertNotEquals(
            0,
            fallback.count { it.toInt() != 0 },
        )
    }

    @Test
    fun pbkdf2FallbackSupportsMultiBlockOutput() {
        val salt = ByteArray(16) { it.toByte() }
        val fallback = SecureClipboardExportCrypto.pbkdf2HmacSha256("multi".toCharArray(), salt, 10, 48)
        val spec = PBEKeySpec("multi".toCharArray(), salt, 10, 384)
        val platform = try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
        assertArrayEquals(platform, fallback)
        assertEquals(48, fallback.size)
    }
}
