/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.secureclipboard

import org.fcitx.fcitx5.android.data.secureclipboard.crypto.ClipboardCrypto
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

/**
 * Small auxiliary metadata stored *encrypted* next to every clipboard payload.
 *
 * The encrypted blob only contains non-content facts (length, sensitive flag, source app,
 * read-only hint) so the panel can eventually render a mask without decrypting the full
 * history. It is an additional layer on top of the payload encryption, not a replacement.
 *
 * The [SecureClipboardEntry.encryptedMetadata] column has no separate IV column, therefore
 * [packEncrypted] prefixes the metadata IV to the metadata ciphertext. The IV is freshly generated
 * for every metadata encryption, so the metadata never reuses the payload IV (GCM nonce reuse is
 * fatal).
 */
object SecureClipboardMetadata {

    private const val MAGIC = 0x53434C42 // "SCLB"
    private const val FORMAT_VERSION = 1
    private const val MAX_SOURCE_APP_BYTES = 512

    /** Decoded metadata. [length] is the plaintext length in UTF-16 code units. */
    data class Decoded(
        val length: Int,
        val sensitive: Boolean,
        val sourceApp: String?,
        val readOnly: Boolean,
    )

    /** Encode the metadata plaintext (before encryption). */
    fun encode(
        length: Int,
        sensitive: Boolean,
        sourceApp: String?,
        readOnly: Boolean,
    ): ByteArray {
        val appBytes = sourceApp
            ?.toByteArray(Charsets.UTF_8)
            ?.takeIf { it.size <= MAX_SOURCE_APP_BYTES }
        val flags = (if (sensitive) 1 else 0) or (if (readOnly) 2 else 0)
        val buffer = ByteArrayOutputStream(16 + (appBytes?.size ?: 0))
        DataOutputStream(buffer).use { output ->
            output.writeInt(MAGIC)
            output.writeByte(FORMAT_VERSION)
            output.writeInt(length.coerceAtLeast(0))
            output.writeByte(flags)
            if (appBytes == null) {
                output.writeShort(-1)
            } else {
                output.writeShort(appBytes.size)
                output.write(appBytes)
            }
        }
        return buffer.toByteArray()
    }

    /**
     * Decode metadata plaintext. Returns `null` for malformed input instead of throwing: a
     * missing display hint must never break reading a clipboard record.
     */
    fun decode(bytes: ByteArray): Decoded? = try {
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            val magic = input.readInt()
            val version = input.readByte().toInt() and 0xFF
            if (magic != MAGIC || version != FORMAT_VERSION) {
                return null
            }
            val length = input.readInt()
            if (length < 0 || length > SecureClipboardLimits.MAX_TEXT_LENGTH) {
                return null
            }
            val flags = input.readByte().toInt() and 0xFF
            val appLength = input.readShort().toInt()
            val sourceApp = when {
                appLength < 0 -> null
                appLength > MAX_SOURCE_APP_BYTES -> null
                appLength > input.available() -> null
                else -> {
                    val app = ByteArray(appLength)
                    input.readFully(app)
                    String(app, Charsets.UTF_8)
                }
            }
            Decoded(
                length = length,
                sensitive = flags and 1 != 0,
                sourceApp = sourceApp,
                readOnly = flags and 2 != 0,
            )
        }
    } catch (e: IOException) {
        null
    }

    /** Prefix the metadata IV to the metadata ciphertext for storage in a single BLOB column. */
    fun packEncrypted(iv: ByteArray, ciphertext: ByteArray): ByteArray {
        val packed = ByteArray(iv.size + ciphertext.size)
        iv.copyInto(packed, 0)
        ciphertext.copyInto(packed, iv.size)
        return packed
    }

    /** Split [packEncrypted] output. Returns `null` when the blob can not contain a payload. */
    fun splitEncrypted(packed: ByteArray): Pair<ByteArray, ByteArray>? {
        if (packed.size <= ClipboardCrypto.IV_SIZE_BYTES) return null
        val iv = packed.copyOfRange(0, ClipboardCrypto.IV_SIZE_BYTES)
        val ciphertext = packed.copyOfRange(ClipboardCrypto.IV_SIZE_BYTES, packed.size)
        return iv to ciphertext
    }
}
