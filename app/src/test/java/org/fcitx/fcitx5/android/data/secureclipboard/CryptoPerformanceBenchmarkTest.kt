/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.secureclipboard

import org.fcitx.fcitx5.android.data.secureclipboard.crypto.ClipboardCrypto
import org.fcitx.fcitx5.android.data.secureclipboard.crypto.EncryptedPayload
import org.fcitx.fcitx5.android.data.secureclipboard.crypto.SecureClipboardAad
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random

/**
 * JVM micro-benchmark for the AES-256-GCM primitives used by the secure clipboard.
 *
 * IMPORTANT: this measures the desktop JVM provider and therefore is **not** a substitute for
 * on-device measurements (OnePlus / OxygenOS / ColorOS). It only gives an upper-bound sanity
 * check for the encryption cost per text length; the per-record database and Keystore overhead
 * is not included (Keystore operations require a real Android runtime).
 */
class CryptoPerformanceBenchmarkTest {

    private val crypto = ClipboardCrypto()

    // Deterministic non-secret test key (AES-256).
    private val key = SecretKeySpec(ByteArray(32) { (it * 7 + 1).toByte() }, "AES")

    @Test
    fun benchmarkEncryptDecrypt() {
        val lengths = intArrayOf(10, 100, 1_000, 10_000, 100_000)
        val warmup = 10
        val iterations = 30

        println("=== AES-256-GCM JVM benchmark (encrypt+decrypt, desktop JVM provider) ===")
        println("length(UTF-16 units) | avg(us) | min(us) | max(us) | MiB/s(round-trip)")

        for (length in lengths) {
            val text = buildString(length) {
                val random = Random(42)
                while (this.length < length) append(('a'.code + random.nextInt(26)).toChar())
            }
            val plain = text.toByteArray(Charsets.UTF_8)
            val aad = SecureClipboardAad.payload(1, CaptureSource.SystemClipboard.storageValue)

            repeat(warmup) { roundTrip(plain, aad) }

            val samples = LongArray(iterations)
            for (i in 0 until iterations) {
                val start = System.nanoTime()
                roundTrip(plain, aad)
                samples[i] = (System.nanoTime() - start) / 1_000L
            }
            samples.sort()
            val avg = samples.average()
            val throughputMib = (plain.size.toDouble() * 2.0 / (1 shl 20)) /
                    (samples.average() / 1_000_000.0)
            println(
                String.format(
                    "%20d | %7.1f | %6d | %7d | %8.1f",
                    length, avg, samples.first(), samples.last(), throughputMib
                )
            )
            assertTrue("round trip must succeed", samples.isNotEmpty())
        }
    }

    private fun roundTrip(plain: ByteArray, aad: ByteArray) {
        val encrypted: EncryptedPayload = crypto.encrypt(key, plain, aad)
        val decrypted = crypto.decrypt(key, encrypted, aad)
        if (decrypted.size != plain.size) error("round trip size mismatch")
    }

    @Test
    fun benchmarkLargePayloadRejectionIsCheap() {
        // The policy rejects oversized input before any crypto work: 100k chars is the limit.
        val text = "a".repeat(SecureClipboardLimits.MAX_TEXT_LENGTH + 1)
        val start = System.nanoTime()
        val error = SecureClipboardPolicy.validateText(text)
        val elapsedUs = (System.nanoTime() - start) / 1_000L
        println("=== oversized input rejection: $error in ${elapsedUs}us ===")
        assertTrue(error == SecureClipboardError.TooLargeText)
        assertTrue("rejection should be cheap", elapsedUs < 100_000)
    }
}
