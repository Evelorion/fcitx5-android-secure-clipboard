/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.secureclipboard

import java.security.GeneralSecurityException
import javax.crypto.Mac
import javax.crypto.SecretKey

/**
 * Pure-Kotlin capture policy for the encrypted clipboard.
 *
 * Kept free of Android imports so the limits and the de-duplication tag can be covered by plain
 * JVM unit tests. The repository always validates again (defence in depth) even when an entry
 * point already validated the request.
 */
object SecureClipboardPolicy {

    private const val HMAC_ALGORITHM = "HmacSHA256"
    private const val HEX_DIGITS = "0123456789abcdef"

    /**
     * Validate a capture request.
     *
     * @param allowSensitive whether the caller explicitly allowed sensitive content. The default
     *   rejects every record flagged as sensitive (channel B / password managers), because the
     *   secure clipboard must not silently archive content the source marked as a secret.
     * @return the first validation error, or `null` when the request may be persisted.
     */
    fun validate(request: CaptureRequest, allowSensitive: Boolean = false): SecureClipboardError? =
        validateText(request.text, request.sensitive, allowSensitive)

    /** Validate plaintext independently of a [CaptureRequest]. */
    fun validateText(
        text: String,
        sensitive: Boolean = false,
        allowSensitive: Boolean = false
    ): SecureClipboardError? {
        if (text.isEmpty() || text.isBlank()) return SecureClipboardError.EmptyText
        if (text.length > SecureClipboardLimits.MAX_TEXT_LENGTH) return SecureClipboardError.TooLargeText
        if (text.toByteArray(Charsets.UTF_8).size > SecureClipboardLimits.MAX_TEXT_UTF8_BYTES) {
            return SecureClipboardError.TooLargeText
        }
        if (!hasSupportedCodePoints(text)) return SecureClipboardError.UnsupportedFormat
        if (sensitive && !allowSensitive) return SecureClipboardError.SensitiveRejected
        return null
    }

    /**
     * Keyed HMAC-SHA256 de-duplication tag, lower-case hex encoded.
     *
     * The tag is keyed with a dedicated Android Keystore HMAC key: a plain hash of clipboard text
     * would allow an offline dictionary attack against short secrets once the database leaks.
     */
    fun dedupTag(dedupKey: SecretKey, text: String): String =
        dedupTag(dedupKey, text.toByteArray(Charsets.UTF_8))

    /** Binary overload of [dedupTag] so callers do not allocate UTF-8 twice. */
    fun dedupTag(dedupKey: SecretKey, plaintext: ByteArray): String {
        try {
            val mac = Mac.getInstance(HMAC_ALGORITHM)
            mac.init(dedupKey)
            val digest = mac.doFinal(plaintext)
            val hex = CharArray(digest.size * 2)
            for (index in digest.indices) {
                val value = digest[index].toInt() and 0xFF
                hex[index * 2] = HEX_DIGITS[value ushr 4]
                hex[index * 2 + 1] = HEX_DIGITS[value and 0x0F]
            }
            return String(hex)
        } catch (e: GeneralSecurityException) {
            throw IllegalStateException("Secure clipboard dedup tag computation failed", e)
        }
    }

    /**
     * Reject text that can not be round-tripped losslessly through the database / editors:
     * unpaired surrogates, ISO control characters (except TAB/LF/CR) and Unicode noncharacters.
     */
    private fun hasSupportedCodePoints(text: String): Boolean {
        var index = 0
        while (index < text.length) {
            val current = text[index]
            when {
                Character.isHighSurrogate(current) -> {
                    if (index + 1 >= text.length || !Character.isLowSurrogate(text[index + 1])) {
                        return false
                    }
                    index += 2
                }

                Character.isLowSurrogate(current) -> return false
                current == '\uFFFE' || current == '\uFFFF' -> return false
                Character.isISOControl(current) &&
                        current != '\t' && current != '\n' && current != '\r' -> return false

                else -> index++
            }
        }
        return true
    }
}
