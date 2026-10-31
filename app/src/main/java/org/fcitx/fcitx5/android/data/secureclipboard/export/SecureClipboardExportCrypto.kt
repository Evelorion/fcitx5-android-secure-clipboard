/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.secureclipboard.export

import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.fcitx.fcitx5.android.data.secureclipboard.CaptureSource
import org.fcitx.fcitx5.android.data.secureclipboard.SecureClipboardRecord
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.GeneralSecurityException
import java.security.NoSuchAlgorithmException
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.BadPaddingException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Typed failure of [SecureClipboardExportCrypto].
 *
 * Messages never contain the passphrase or clipboard plaintext.
 */
sealed class SecureClipboardExportException(message: String, cause: Throwable? = null) :
    Exception(message, cause) {

    /** The file is not a secure clipboard export, has a wrong version or is malformed. */
    class InvalidFormat(cause: Throwable? = null) :
        SecureClipboardExportException("Invalid secure clipboard export file", cause)

    /** AES-GCM authentication failed: wrong passphrase or modified/corrupted file. */
    class BadPassphraseOrCorrupted(cause: Throwable? = null) :
        SecureClipboardExportException("Wrong passphrase or corrupted secure clipboard export", cause)
}

/**
 * Payload of the encrypted export file, serialized as JSON before encryption.
 * Kept internal: only [SecureClipboardExportCrypto] (and its tests) may see the structure.
 */
@Serializable
internal data class ExportPayload(
    val version: Int,
    val exportedAt: Long,
    val records: List<ExportRecord>,
)

/** One exported record. The database id is intentionally not exported (it is re-assigned). */
@Serializable
internal data class ExportRecord(
    val text: String,
    val source: String,
    val timestamp: Long,
    val pinned: Boolean,
)

/**
 * Self-contained encrypted export format for the user-initiated backup/restore of the secure
 * clipboard history.
 *
 * File layout (all integers big-endian, fixed sizes):
 * ```
 * offset  size  content
 *      0    11  ASCII magic "FCSC-EXPORT"
 *     11     1  format version (1)
 *     12    16  PBKDF2 salt (SecureRandom)
 *     28    12  AES-GCM IV (SecureRandom, 96-bit)
 *     40     n  AES-256-GCM ciphertext of the JSON payload (includes 128-bit tag)
 * ```
 *
 * Key derivation: `PBKDF2WithHmacSHA256`, 210_000 iterations, 256-bit key. The magic, version
 * and salt are authenticated as GCM associated data, so modifying the header also fails
 * authentication.
 *
 * The JSON plaintext is:
 * ```json
 * {"version":1,"exportedAt":123,"records":[{"text":"...","source":"process_text",
 *  "timestamp":123,"pinned":false}]}
 * ```
 *
 * Security rules:
 *  - The passphrase is only used through [PBEKeySpec] / a local byte copy and never stored,
 *    logged or returned. Callers may keep ownership of their `CharArray` and should wipe it
 *    after use.
 *  - Derived key bytes and plaintext buffers are zeroed on all paths (best effort on the JVM;
 *    immutable intermediate Strings can only be garbage collected).
 *  - At most [MAX_EXPORT_RECORDS] (5000) records may be exported/imported per file.
 *
 * Note: Android API 23-25 do not ship a `PBKDF2WithHmacSHA256` SecretKeyFactory, so a byte
 * compatible pure `javax.crypto` implementation ([pbkdf2HmacSha256]) is used as fallback. Both
 * paths are covered by unit tests.
 */
object SecureClipboardExportCrypto {

    /** ASCII magic that identifies the file type. */
    const val MAGIC_TEXT = "FCSC-EXPORT"

    const val FORMAT_VERSION = 1

    const val SALT_SIZE_BYTES = 16

    const val IV_SIZE_BYTES = 12

    /** PBKDF2 iteration count (OWASP-style cost for AES-256 backups). */
    const val PBKDF2_ITERATIONS = 210_000

    const val KEY_SIZE_BITS = 256

    const val KEY_SIZE_BYTES = KEY_SIZE_BITS / 8

    const val TAG_SIZE_BITS = 128

    const val TAG_SIZE_BYTES = TAG_SIZE_BITS / 8

    /** Upper bound of records per export/import file. */
    const val MAX_EXPORT_RECORDS = 5000

    private val MAGIC: ByteArray = MAGIC_TEXT.toByteArray(Charsets.US_ASCII)

    private const val HEADER_SIZE =
        MAGIC_TEXT.length + 1 + SALT_SIZE_BYTES + IV_SIZE_BYTES

    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val KEY_ALGORITHM = "AES"
    private const val PBKDF2_ALGORITHM = "PBKDF2WithHmacSHA256"
    private const val HMAC_ALGORITHM = "HmacSHA256"

    private val secureRandom = SecureRandom()

    private val json = Json {
        // Forward compatible: ignore fields added by a future export version.
        ignoreUnknownKeys = true
    }

    /**
     * Encrypt [records] into a self-contained export file protected by [passphrase].
     *
     * @throws IllegalArgumentException when more than [MAX_EXPORT_RECORDS] records are supplied
     * @throws IllegalStateException when the platform crypto provider fails (never contains data)
     */
    fun encrypt(
        passphrase: CharArray,
        records: List<SecureClipboardRecord>,
        exportedAt: Long = System.currentTimeMillis(),
    ): ByteArray {
        require(records.size <= MAX_EXPORT_RECORDS) {
            "Too many records for a secure clipboard export"
        }

        val salt = ByteArray(SALT_SIZE_BYTES).also { secureRandom.nextBytes(it) }
        val iv = ByteArray(IV_SIZE_BYTES).also { secureRandom.nextBytes(it) }
        val derivedKey = try {
            deriveKey(passphrase, salt)
        } catch (e: GeneralSecurityException) {
            throw IllegalStateException("Secure clipboard export key derivation failed", e)
        }

        val plaintext = try {
            encodePayload(records, exportedAt)
        } catch (e: Exception) {
            // Never leave the derived key behind when payload serialization fails.
            derivedKey.fill(0)
            throw e
        }
        try {
            // Header (magic | version | salt) is authenticated as GCM AAD.
            val header = ByteArray(MAGIC.size + 1 + SALT_SIZE_BYTES)
            MAGIC.copyInto(header, 0)
            header[MAGIC.size] = FORMAT_VERSION.toByte()
            salt.copyInto(header, MAGIC.size + 1)

            val ciphertext = try {
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(
                    Cipher.ENCRYPT_MODE,
                    SecretKeySpec(derivedKey, KEY_ALGORITHM),
                    GCMParameterSpec(TAG_SIZE_BITS, iv),
                )
                cipher.updateAAD(header)
                cipher.doFinal(plaintext)
            } catch (e: GeneralSecurityException) {
                throw IllegalStateException("Secure clipboard export encryption failed", e)
            }

            val output = ByteArray(HEADER_SIZE + ciphertext.size)
            header.copyInto(output, 0)
            iv.copyInto(output, header.size)
            ciphertext.copyInto(output, header.size + iv.size)
            return output
        } finally {
            plaintext.fill(0)
            derivedKey.fill(0)
        }
    }

    /**
     * Decrypt and parse an export file created by [encrypt].
     *
     * Returned [SecureClipboardRecord]s have `id = 0`: the database assigns a fresh id when the
     * records are imported.
     *
     * @throws SecureClipboardExportException.InvalidFormat for malformed / unsupported files
     * @throws SecureClipboardExportException.BadPassphraseOrCorrupted when authentication fails
     */
    fun decrypt(passphrase: CharArray, data: ByteArray): List<SecureClipboardRecord> {
        if (data.size < HEADER_SIZE + TAG_SIZE_BYTES) {
            throw SecureClipboardExportException.InvalidFormat()
        }
        for (index in MAGIC.indices) {
            if (data[index] != MAGIC[index]) {
                throw SecureClipboardExportException.InvalidFormat()
            }
        }
        val version = data[MAGIC.size].toInt() and 0xFF
        if (version != FORMAT_VERSION) {
            throw SecureClipboardExportException.InvalidFormat()
        }

        val saltStart = MAGIC.size + 1
        val saltEnd = saltStart + SALT_SIZE_BYTES
        val ivStart = saltEnd
        val ivEnd = ivStart + IV_SIZE_BYTES
        val salt = data.copyOfRange(saltStart, saltEnd)
        val iv = data.copyOfRange(ivStart, ivEnd)
        val ciphertext = data.copyOfRange(ivEnd, data.size)
        // Magic | version | salt is the authenticated AAD computed by encrypt().
        val header = data.copyOfRange(0, saltEnd)

        val derivedKey = try {
            deriveKey(passphrase, salt)
        } catch (e: GeneralSecurityException) {
            throw SecureClipboardExportException.BadPassphraseOrCorrupted(e)
        }
        val plaintext = try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(derivedKey, KEY_ALGORITHM),
                GCMParameterSpec(TAG_SIZE_BITS, iv),
            )
            cipher.updateAAD(header)
            cipher.doFinal(ciphertext)
        } catch (e: AEADBadTagException) {
            throw SecureClipboardExportException.BadPassphraseOrCorrupted(e)
        } catch (e: BadPaddingException) {
            throw SecureClipboardExportException.BadPassphraseOrCorrupted(e)
        } catch (e: GeneralSecurityException) {
            throw SecureClipboardExportException.BadPassphraseOrCorrupted(e)
        } finally {
            derivedKey.fill(0)
        }

        try {
            val payload = try {
                json.decodeFromString(ExportPayload.serializer(), String(plaintext, Charsets.UTF_8))
            } catch (e: SerializationException) {
                throw SecureClipboardExportException.InvalidFormat(e)
            } catch (e: IllegalArgumentException) {
                throw SecureClipboardExportException.InvalidFormat(e)
            }
            if (payload.version != FORMAT_VERSION || payload.records.size > MAX_EXPORT_RECORDS) {
                throw SecureClipboardExportException.InvalidFormat()
            }
            return payload.records.map { record ->
                SecureClipboardRecord(
                    id = 0,
                    text = record.text,
                    source = CaptureSource.fromStorageValue(record.source),
                    timestamp = record.timestamp,
                    pinned = record.pinned,
                )
            }
        } finally {
            plaintext.fill(0)
        }
    }

    /* =================== key derivation =================== */

    private fun deriveKey(passphrase: CharArray, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(passphrase, salt, PBKDF2_ITERATIONS, KEY_SIZE_BITS)
        try {
            return try {
                SecretKeyFactory.getInstance(PBKDF2_ALGORITHM)
                    .generateSecret(spec)
                    .encoded
            } catch (e: NoSuchAlgorithmException) {
                // API 23-25: no PBKDF2WithHmacSHA256 provider; identical pure fallback.
                pbkdf2HmacSha256(passphrase, salt, PBKDF2_ITERATIONS, KEY_SIZE_BYTES)
            }
        } finally {
            spec.clearPassword()
        }
    }

    /**
     * PBKDF2 with HMAC-SHA256 (RFC 8018) implemented with [Mac] only.
     *
     * Used on Android versions whose providers do not ship `PBKDF2WithHmacSHA256`; the unit
     * tests assert it is byte-for-byte identical to the platform implementation.
     */
    internal fun pbkdf2HmacSha256(
        passphrase: CharArray,
        salt: ByteArray,
        iterations: Int,
        keyLengthBytes: Int,
    ): ByteArray {
        require(iterations > 0) { "iterations must be positive" }
        require(keyLengthBytes > 0) { "key length must be positive" }
        val passwordBytes = encodeUtf8(passphrase)
        try {
            val mac = Mac.getInstance(HMAC_ALGORITHM)
            mac.init(SecretKeySpec(passwordBytes, HMAC_ALGORITHM))
            val hashLength = mac.macLength
            val blockCount = (keyLengthBytes + hashLength - 1) / hashLength
            val output = ByteArray(blockCount * hashLength)
            val blockIndexBytes = ByteArray(4)
            for (blockIndex in 1..blockCount) {
                blockIndexBytes[0] = (blockIndex ushr 24).toByte()
                blockIndexBytes[1] = (blockIndex ushr 16).toByte()
                blockIndexBytes[2] = (blockIndex ushr 8).toByte()
                blockIndexBytes[3] = blockIndex.toByte()
                mac.update(salt)
                mac.update(blockIndexBytes)
                var u = mac.doFinal()
                val t = u.copyOf()
                for (iteration in 1 until iterations) {
                    u = mac.doFinal(u)
                    for (index in t.indices) {
                        t[index] = (t[index].toInt() xor u[index].toInt()).toByte()
                    }
                }
                System.arraycopy(t, 0, output, (blockIndex - 1) * hashLength, hashLength)
            }
            return output.copyOf(keyLengthBytes)
        } catch (e: GeneralSecurityException) {
            throw IllegalStateException("Secure clipboard PBKDF2 fallback failed", e)
        } finally {
            passwordBytes.fill(0)
        }
    }

    /** Encode the passphrase to UTF-8 without creating an immutable String copy. */
    private fun encodeUtf8(chars: CharArray): ByteArray {
        val encoder = Charsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
        return try {
            val buffer = encoder.encode(CharBuffer.wrap(chars))
            ByteArray(buffer.remaining()).also { buffer.get(it) }
        } catch (e: CharacterCodingException) {
            throw IllegalStateException("Secure clipboard passphrase encoding failed", e)
        }
    }

    /* =================== JSON payload =================== */

    private fun encodePayload(
        records: List<SecureClipboardRecord>,
        exportedAt: Long,
    ): ByteArray {
        val payload = ExportPayload(
            version = FORMAT_VERSION,
            exportedAt = exportedAt,
            records = records.map { record ->
                ExportRecord(
                    text = record.text,
                    source = record.source.storageValue,
                    timestamp = record.timestamp,
                    pinned = record.pinned,
                )
            },
        )
        // The JSON String is only a short-lived intermediate; payload bytes are wiped by caller.
        return json.encodeToString(ExportPayload.serializer(), payload).toByteArray(Charsets.UTF_8)
    }
}
