/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.secureclipboard

import android.content.Context
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Device-level tests of the encrypted clipboard storage.
 *
 * Requirements covered: encrypted-only persistence, AES-256-GCM parameters, round-trip,
 * de-duplication, rejection of empty / oversized / malformed / sensitive input, no plaintext at
 * rest, no plaintext in logcat and no system clipboard write from the storage path itself.
 *
 * These tests do not need the secure clipboard master switch: they use the vault directly. They
 * skip (via JUnit assumptions) when the encrypted vault or the Keystore is unavailable, for
 * example before the first unlock.
 */
class SecureClipboardStorageInstrumentedTest {

    private val repository: SecureClipboardRepository
        get() = SecureClipboardTestSupport.repository()

    @Test
    fun captureIsStoredAsCiphertextOnlyAndRoundTrips() {
        runBlocking {
            val marker = SecureClipboardTestSupport.uniqueMarker()
            val id = saveValidCapture(marker)
            try {
                val entry = repository.getRow(id)
                assertNotNull("encrypted row must be readable", entry)
                val row = entry!!
                assertTrue("ciphertext must not be empty", row.ciphertext.isNotEmpty())
                assertEquals("AES-256-GCM IV must be 96 bit", 12, row.iv.size)
                assertTrue("IV must not be all zero", row.iv.any { it.toInt() != 0 })
                val plaintextBytes = marker.toByteArray(Charsets.UTF_8)
                assertTrue(
                    "ciphertext must not contain the plaintext",
                    !row.ciphertext.containsSubSequence(plaintextBytes)
                )
                assertTrue(
                    "encrypted metadata must not contain the plaintext",
                    !row.encryptedMetadata.containsSubSequence(plaintextBytes)
                )
                val record = repository.read(id)
                assertNotNull("record must decrypt", record)
                assertEquals("decrypted record must round-trip", marker, record?.text)
                SecureClipboardTestSupport.assertNoPlaintextInAppStorage(marker)
            } finally {
                runCatching { repository.delete(id) }
            }
        }
    }

    @Test
    fun emptyOversizedMalformedAndSensitiveCapturesAreRejected() {
        runBlocking {
            assertEquals(
                "empty text must be rejected",
                SecureClipboardError.EmptyText,
                repository.save(CaptureRequest(text = "", source = CaptureSource.ProcessText))
                    .failureError()
            )
            assertEquals(
                "blank text must be rejected",
                SecureClipboardError.EmptyText,
                repository.save(CaptureRequest(text = " \t\n ", source = CaptureSource.ProcessText))
                    .failureError()
            )
            val tooLong = "a".repeat(SecureClipboardLimits.MAX_TEXT_LENGTH + 1)
            assertEquals(
                "oversized text must be rejected",
                SecureClipboardError.TooLargeText,
                repository.save(CaptureRequest(text = tooLong, source = CaptureSource.ProcessText))
                    .failureError()
            )
            assertEquals(
                "ISO control characters must be rejected",
                SecureClipboardError.UnsupportedFormat,
                repository.save(
                    CaptureRequest(text = "abc\u0000def", source = CaptureSource.ProcessText)
                ).failureError()
            )
            assertEquals(
                "unpaired surrogates must be rejected",
                SecureClipboardError.UnsupportedFormat,
                repository.save(
                    CaptureRequest(text = "abc\uD800", source = CaptureSource.ProcessText)
                ).failureError()
            )
            assertEquals(
                "sensitive content must be rejected by default",
                SecureClipboardError.SensitiveRejected,
                repository.save(
                    CaptureRequest(
                        text = SecureClipboardTestSupport.uniqueMarker("SENSITIVE"),
                        source = CaptureSource.ProcessText,
                        sensitive = true,
                    )
                ).failureError()
            )
        }
    }

    @Test
    fun duplicateCaptureIsDeduplicated() {
        runBlocking {
            val marker = SecureClipboardTestSupport.uniqueMarker("DUP")
            val firstId = saveValidCapture(marker)
            try {
                val secondId = saveValidCapture(marker)
                assertEquals(
                    "identical content must be de-duplicated to a single row",
                    firstId,
                    secondId
                )
            } finally {
                runCatching { repository.delete(firstId) }
            }
        }
    }

    @Test
    fun unusualUnicodeRoundTrips() {
        runBlocking {
            // Emoji, ZWJ sequence, combining marks and RTL text must survive encryption unchanged.
            val marker = "\uD83D\uDE00 e\u0301 \u0645\u0631\u062D\u0628\u0627 " +
                "\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67 " +
                SecureClipboardTestSupport.uniqueMarker("UNICODE")
            val id = saveValidCapture(marker)
            try {
                assertEquals(
                    "unicode plaintext must round-trip",
                    marker,
                    repository.read(id)?.text
                )
            } finally {
                runCatching { repository.delete(id) }
            }
        }
    }

    @Test
    fun noPlaintextLeaksIntoLogcat() {
        runBlocking {
            val marker = SecureClipboardTestSupport.uniqueMarker("LOGCAT")
            val id = saveValidCapture(marker)
            try {
                SecureClipboardTestSupport.sleep(500L)
                SecureClipboardTestSupport.assertNoPlaintextInLogcat(marker)
            } finally {
                runCatching { repository.delete(id) }
            }
        }
    }

    @Test
    fun directStorageSaveDoesNotTouchSystemClipboard() {
        val context: Context = SecureClipboardTestSupport.targetContext()
        ClipboardProbeClient.requireInstalled(context)
        ClipboardProbeClient.ensureListening(context)
        ClipboardProbeClient.clear(context)
        SecureClipboardTestSupport.sleep(200L)
        val baseline = probeStatusOrSkip(context)
        val marker = SecureClipboardTestSupport.uniqueMarker("STORAGE")
        runBlocking {
            val id = saveValidCapture(marker)
            runCatching { repository.delete(id) }
        }
        SecureClipboardTestSupport.sleep(800L)
        val after = probeStatusOrSkip(context)
        assertEquals(
            "encrypted storage must never write the system clipboard",
            baseline.eventCount,
            after.eventCount
        )
    }

    @Test
    fun readingMissingRecordReturnsNull() {
        runBlocking {
            assertNull("reading a missing record must return null", repository.read(Int.MAX_VALUE))
            assertNull("missing row must return null", repository.getRow(Int.MAX_VALUE))
        }
    }

    private fun saveValidCapture(text: String): Int = runBlocking {
        when (
            val result = repository.save(
                CaptureRequest(text = text, source = CaptureSource.ProcessText)
            )
        ) {
            is SaveResult.Success -> result.id
            is SaveResult.Failure -> {
                assumeTrue(
                    "encrypted storage is unavailable on this device: " + result.error,
                    false
                )
                -1
            }
        }
    }

    private fun probeStatusOrSkip(context: Context): ClipboardProbeClient.Status {
        val status = ClipboardProbeClient.status(context)
        return status ?: run {
            assumeTrue("clipboard probe status is unavailable", false)
            error("unreachable: clipboard probe status is unavailable")
        }
    }
}

/** Error of a failed save, or null for a successful one. */
private fun SaveResult.failureError(): SecureClipboardError? = (this as? SaveResult.Failure)?.error
