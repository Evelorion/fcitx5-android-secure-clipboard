/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.securecapture

import org.fcitx.fcitx5.android.data.secureclipboard.SecureClipboardError
import org.fcitx.fcitx5.android.data.secureclipboard.SecureClipboardLimits

/**
 * Result of validating a capture request before any plaintext is handed to the storage layer.
 *
 * [Valid.text] is the normalized, still short-lived plaintext. It must not be logged or persisted
 * by the caller; the encrypted repository is the only place allowed to persist it.
 */
sealed interface SecureCaptureValidation {

    data class Valid(
        val text: String,
        val readOnly: Boolean = false,
        val sensitive: Boolean = false,
    ) : SecureCaptureValidation

    data class Invalid(val error: SecureClipboardError) : SecureCaptureValidation
}

/**
 * Validation rules shared by every channel A capture entry point (ACTION_PROCESS_TEXT,
 * ACTION_SEND and the IME "save selected text" action).
 *
 * This object intentionally has no dependency on [android.content.Intent] so that the rules can be
 * exercised by plain JVM unit tests. Activities pass the already extracted extras in.
 *
 * Rules:
 *  - The action and (for ACTION_SEND) the MIME type must match the entry point.
 *  - The text extra must really be a [CharSequence]; arbitrary extra types are rejected.
 *  - Control characters are normalized away (C0 except tab/newline, DEL, C1, zero width joiners)
 *    and CRLF is canonicalized to LF. The normalized text must not be blank.
 *  - Both UTF-16 length and UTF-8 byte size are capped, because LIMITS are shared with the
 *    repository which enforces them again.
 */
object SecureCaptureValidator {

    const val ACTION_PROCESS_TEXT = "android.intent.action.PROCESS_TEXT"
    const val ACTION_SEND = "android.intent.action.SEND"

    const val MIME_TYPE_TEXT_PLAIN = "text/plain"
    const val MIME_TYPE_TEXT_HTML = "text/html"

    /** Masking character for the (never plaintext) preview in the confirmation UI. */
    const val MASK_CHAR = '\u2022'

    /** Maximum number of mask characters rendered in the confirmation preview. */
    const val MAX_PREVIEW_MASK_LENGTH = 64

    /**
     * Validate an ACTION_PROCESS_TEXT request.
     *
     * @param action the intent action, expected to be [ACTION_PROCESS_TEXT].
     * @param type the intent MIME type; the framework usually sends "text/plain", but some
     * callers omit it, therefore null is accepted for this entry point.
     * @param rawText the raw EXTRA_PROCESS_TEXT value, kept as [Any] on purpose so that a wrong
     * extra type is rejected instead of being silently coerced.
     * @param readOnly the EXTRA_PROCESS_TEXT_READONLY hint of the caller.
     */
    fun validateProcessText(
        action: String?,
        type: String?,
        rawText: Any?,
        readOnly: Boolean,
    ): SecureCaptureValidation {
        if (action != ACTION_PROCESS_TEXT) return invalid(SecureClipboardError.UnsupportedFormat)
        if (type != null && !isTextType(type)) return invalid(SecureClipboardError.UnsupportedFormat)
        return validateText(rawText, readOnly = readOnly, sensitive = false)
    }

    /**
     * Validate an ACTION_SEND request.
     *
     * @param sensitive whether the caller marked the clip as sensitive.
     */
    fun validateShare(
        action: String?,
        type: String?,
        rawText: Any?,
        sensitive: Boolean,
    ): SecureCaptureValidation {
        if (action != ACTION_SEND) return invalid(SecureClipboardError.UnsupportedFormat)
        if (type == null || !isTextType(type)) return invalid(SecureClipboardError.UnsupportedFormat)
        return validateText(rawText, readOnly = false, sensitive = sensitive)
    }

    /** Validate text read from [android.view.inputmethod.InputConnection#getSelectedText]. */
    fun validateSelectedText(rawText: CharSequence?): SecureCaptureValidation =
        validateText(rawText, readOnly = false, sensitive = false)

    private fun validateText(
        rawText: Any?,
        readOnly: Boolean,
        sensitive: Boolean,
    ): SecureCaptureValidation {
        if (rawText == null) return invalid(SecureClipboardError.EmptyText)
        if (rawText !is CharSequence) return invalid(SecureClipboardError.UnsupportedFormat)
        val normalized = normalize(rawText)
        if (normalized.isBlank()) return invalid(SecureClipboardError.EmptyText)
        if (normalized.length > SecureClipboardLimits.MAX_TEXT_LENGTH) {
            return invalid(SecureClipboardError.TooLargeText)
        }
        // Defensive: check the encoded size as well, not only the UTF-16 length.
        if (normalized.toByteArray(Charsets.UTF_8).size > SecureClipboardLimits.MAX_TEXT_UTF8_BYTES) {
            return invalid(SecureClipboardError.TooLargeText)
        }
        return SecureCaptureValidation.Valid(
            text = normalized,
            readOnly = readOnly,
            sensitive = sensitive
        )
    }

    /**
     * Normalize a [CharSequence] for storage. Spans are dropped, line endings canonicalized and
     * invisible/control characters removed. The mapping is deterministic and locale independent.
     */
    fun normalize(raw: CharSequence): String {
        val out = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            when {
                c == '\r' -> {
                    // CRLF and lone CR both become LF
                    if (i + 1 < raw.length && raw[i + 1] == '\n') i++
                    out.append('\n')
                }
                c == '\n' || c == '\t' -> out.append(c)
                c.code < 0x20 || c.code == 0x7F -> Unit // other C0 controls and DEL
                c.code in 0x80..0x9F -> Unit // C1 controls
                c == '\u200B' || c == '\u200C' || c == '\u200D' || c == '\uFEFF' -> Unit // zero width
                else -> out.append(c)
            }
            i++
        }
        return out.toString()
    }

    /**
     * Build the masked preview shown in the confirmation UI. The real text is never rendered,
     * only its length is hinted at, capped so that a very long selection cannot blow up the view.
     */
    fun maskPreview(text: CharSequence): String =
        MASK_CHAR.toString().repeat(minOf(text.length, MAX_PREVIEW_MASK_LENGTH))

    /** Accept any text MIME type and parameters, not only the exact text/plain value. */
    private fun isTextType(type: String): Boolean =
        type.startsWith("text/", ignoreCase = true)

    private fun invalid(error: SecureClipboardError): SecureCaptureValidation.Invalid =
        SecureCaptureValidation.Invalid(error)
}
