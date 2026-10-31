/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.secureclipboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * JVM tests for [SecureClipboardPolicy]: capture limits / character policy and the keyed
 * de-duplication tag. No Android classes are involved.
 */
class SecureClipboardPolicyTest {

    private val dedupKey: SecretKey = SecretKeySpec(ByteArray(32) { it.toByte() }, "HmacSHA256")
    private val otherDedupKey: SecretKey = SecretKeySpec(ByteArray(32) { (it + 1).toByte() }, "HmacSHA256")

    private fun expectedDedupTag(key: SecretKey, text: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(key)
        val digest = mac.doFinal(text.toByteArray(Charsets.UTF_8))
        val builder = StringBuilder(digest.size * 2)
        for (byte in digest) {
            val value = byte.toInt() and 0xFF
            builder.append(Character.forDigit(value ushr 4, 16))
            builder.append(Character.forDigit(value and 0x0F, 16))
        }
        return builder.toString()
    }

    /* =================== text policy =================== */

    @Test
    fun normalTextAccepted() {
        assertNull(SecureClipboardPolicy.validateText("hello world"))
        assertNull(SecureClipboardPolicy.validateText("多行文本\n第二行\t制表符"))
        assertNull(SecureClipboardPolicy.validateText("😀 emoji"))
    }

    @Test
    fun emptyTextRejected() {
        assertEquals(SecureClipboardError.EmptyText, SecureClipboardPolicy.validateText(""))
    }

    @Test
    fun blankTextRejected() {
        assertEquals(SecureClipboardError.EmptyText, SecureClipboardPolicy.validateText("   "))
        assertEquals(SecureClipboardError.EmptyText, SecureClipboardPolicy.validateText("\n\t\r"))
        assertEquals(SecureClipboardError.EmptyText, SecureClipboardPolicy.validateText("\u3000\u3000"))
    }

    @Test
    fun maximumLengthAccepted() {
        val text = "a".repeat(SecureClipboardLimits.MAX_TEXT_LENGTH)
        assertNull(SecureClipboardPolicy.validateText(text))
    }

    @Test
    fun tooLongTextRejected() {
        val text = "a".repeat(SecureClipboardLimits.MAX_TEXT_LENGTH + 1)
        assertEquals(SecureClipboardError.TooLargeText, SecureClipboardPolicy.validateText(text))
    }

    @Test
    fun controlCharactersRejected() {
        assertEquals(
            SecureClipboardError.UnsupportedFormat,
            SecureClipboardPolicy.validateText("nul\u0000inside")
        )
        assertEquals(
            SecureClipboardError.UnsupportedFormat,
            SecureClipboardPolicy.validateText("bell\u0007")
        )
        assertEquals(
            SecureClipboardError.UnsupportedFormat,
            SecureClipboardPolicy.validateText("delete\u007Fme")
        )
        assertEquals(
            SecureClipboardError.UnsupportedFormat,
            SecureClipboardPolicy.validateText("nel\u0085me")
        )
    }

    @Test
    fun allowedLineBreaksAccepted() {
        assertNull(SecureClipboardPolicy.validateText("line1\nline2\r\nline3\ttabbed"))
    }

    @Test
    fun unpairedSurrogatesRejected() {
        assertEquals(
            SecureClipboardError.UnsupportedFormat,
            SecureClipboardPolicy.validateText("bad high \uD800")
        )
        assertEquals(
            SecureClipboardError.UnsupportedFormat,
            SecureClipboardPolicy.validateText("bad low \uDC00")
        )
    }

    @Test
    fun nonCharactersRejected() {
        assertEquals(
            SecureClipboardError.UnsupportedFormat,
            SecureClipboardPolicy.validateText("non character \uFFFE")
        )
        assertEquals(
            SecureClipboardError.UnsupportedFormat,
            SecureClipboardPolicy.validateText("non character \uFFFF")
        )
    }

    @Test
    fun sensitiveRejectedByDefault() {
        val request = CaptureRequest(
            text = "password-like",
            source = CaptureSource.SystemClipboard,
            sensitive = true,
        )
        assertEquals(SecureClipboardError.SensitiveRejected, SecureClipboardPolicy.validate(request))
    }

    @Test
    fun sensitiveAcceptedWhenExplicitlyAllowed() {
        val request = CaptureRequest(
            text = "flagged content",
            source = CaptureSource.SystemClipboard,
            sensitive = true,
        )
        assertNull(SecureClipboardPolicy.validate(request, allowSensitive = true))
        assertNull(SecureClipboardPolicy.validateText("flagged content", sensitive = true, allowSensitive = true))
    }

    @Test
    fun requestValidationUsesTextRules() {
        assertEquals(
            SecureClipboardError.EmptyText,
            SecureClipboardPolicy.validate(CaptureRequest(" ", CaptureSource.ImeSelection))
        )
        assertEquals(
            SecureClipboardError.TooLargeText,
            SecureClipboardPolicy.validate(
                CaptureRequest("a".repeat(SecureClipboardLimits.MAX_TEXT_LENGTH + 1), CaptureSource.Share)
            )
        )
        assertNull(SecureClipboardPolicy.validate(CaptureRequest("ok", CaptureSource.ProcessText)))
    }

    /* =================== de-duplication tag =================== */

    @Test
    fun dedupTagIsKeyedHexSha256Length() {
        val tag = SecureClipboardPolicy.dedupTag(dedupKey, "some clipboard text")
        assertEquals(64, tag.length)
        assertTrue(tag.all { character -> character in '0'..'9' || character in 'a'..'f' })
        assertEquals(expectedDedupTag(dedupKey, "some clipboard text"), tag)
        assertNotNull(tag)
    }

    @Test
    fun dedupTagIsDeterministic() {
        val first = SecureClipboardPolicy.dedupTag(dedupKey, "repeatable")
        val second = SecureClipboardPolicy.dedupTag(dedupKey, "repeatable")
        assertEquals(first, second)
    }

    @Test
    fun dedupTagDependsOnKey() {
        val first = SecureClipboardPolicy.dedupTag(dedupKey, "same text")
        val second = SecureClipboardPolicy.dedupTag(otherDedupKey, "same text")
        assertTrue(first != second)
    }

    @Test
    fun dedupTagDependsOnContent() {
        val first = SecureClipboardPolicy.dedupTag(dedupKey, "alpha")
        val second = SecureClipboardPolicy.dedupTag(dedupKey, "beta")
        assertTrue(first != second)
    }

    @Test
    fun dedupTagStringAndByteOverloadsAgree() {
        val text = "多字节 \u00e9 \uD83D\uDE00"
        assertEquals(
            SecureClipboardPolicy.dedupTag(dedupKey, text),
            SecureClipboardPolicy.dedupTag(dedupKey, text.toByteArray(Charsets.UTF_8))
        )
    }
}
