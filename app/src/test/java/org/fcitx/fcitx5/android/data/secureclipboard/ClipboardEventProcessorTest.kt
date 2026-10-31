/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.secureclipboard

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * JVM tests of the channel B race handling. No android.* classes are involved: the platform
 * clipboard, the clock and the encrypted storage are all fakes, so every race can be reproduced
 * deterministically.
 */
class ClipboardEventProcessorTest {

    private class FakeClock(var current: Long = 1_000L) : ClipboardClock {
        override fun now(): Long = current

        fun advance(millis: Long) {
            current += millis
        }
    }

    private class FakeClipboard(
        var current: ClipboardSnapshot = ClipboardSnapshot.Empty,
    ) : ClipboardPort {
        var readCalls = 0
        var clearCalls = 0
        var clearResult = true

        override suspend fun read(): ClipboardSnapshot {
            readCalls++
            return current
        }

        override suspend fun clear(): Boolean {
            clearCalls++
            if (!clearResult) return false
            current = ClipboardSnapshot.Empty
            return true
        }
    }

    private class FakeVault {
        val savedTexts = mutableListOf<String>()
        val savedTimestamps = mutableListOf<Long>()
        var failure: SecureClipboardError? = null
        private var nextId = 1

        val save: SaveSecureCapture = { text, timestamp ->
            savedTexts.add(text)
            savedTimestamps.add(timestamp)
            val error = failure
            if (error == null) {
                SaveResult.Success(nextId++, 1L)
            } else {
                SaveResult.Failure(error)
            }
        }
    }

    private fun capture(
        text: String,
        timestamp: Long = 1L,
        clipTimestamp: Long? = timestamp,
        shouldClear: Boolean = true,
    ) = ClipboardSignal.Text(text, timestamp, clipTimestamp, shouldClear)

    private fun newProcessor(
        port: ClipboardPort,
        save: SaveSecureCapture,
        clock: ClipboardClock = FakeClock(),
        config: ClipboardProcessingConfig = ClipboardProcessingConfig(),
    ) = ClipboardEventProcessor(clipboard = port, save = save, clock = clock, config = config)

    /** Suspends until [count] signals are queued behind the currently processed one. */
    private suspend fun awaitQueued(processor: ClipboardEventProcessor, count: Int = 1) {
        var spins = 0
        while (processor.pendingSignals < count) {
            if (++spins > 10_000) fail("timed out waiting for the signal to be queued")
            yield()
        }
    }

    @Test
    fun consecutiveCopiesArePersistedAndCleared() = runBlocking {
        val clock = FakeClock()
        val port = FakeClipboard(ClipboardSnapshot.Text("A", 10L))
        val vault = FakeVault()
        val processor = newProcessor(port, vault.save, clock)

        val a = processor.submit(capture("A", 10L, clipTimestamp = 10L))
            as ProcessingResult.Completed
        assertEquals(1, a.id)
        assertTrue(a.cleared)
        assertEquals(ClipboardSnapshot.Empty, port.current)

        port.current = ClipboardSnapshot.Text("B", 20L)
        clock.advance(5)
        val b = processor.submit(capture("B", 20L, clipTimestamp = 20L))
            as ProcessingResult.Completed
        assertEquals(2, b.id)
        assertTrue(b.cleared)

        port.current = ClipboardSnapshot.Text("C", 30L)
        clock.advance(5)
        val c = processor.submit(capture("C", 30L, clipTimestamp = 30L))
            as ProcessingResult.Completed
        assertEquals(3, c.id)
        assertTrue(c.cleared)

        assertEquals(listOf("A", "B", "C"), vault.savedTexts)
        assertEquals(3, port.clearCalls)
        assertEquals(ClipboardProcessingState.COMPLETED, processor.state)
        assertEquals(3L, processor.stats.captureCount)
        assertEquals(3L, processor.stats.clearCount)
        assertEquals(3L, processor.submittedSignals)
    }

    @Test
    fun clipboardReplacedWhileSavingIsNotCleared() = runBlocking {
        val clock = FakeClock()
        val port = FakeClipboard(ClipboardSnapshot.Text("A", 10L))
        val saveStarted = CompletableDeferred<Unit>()
        val releaseFirstSave = CompletableDeferred<Unit>()
        val savedTexts = mutableListOf<String>()
        var saves = 0
        val save: SaveSecureCapture = { text, _ ->
            saves++
            if (saves == 1) {
                saveStarted.complete(Unit)
                releaseFirstSave.await()
            }
            savedTexts.add(text)
            SaveResult.Success(saves, 1L)
        }
        val processor = newProcessor(port, save, clock)

        val aDeferred = async { processor.submit(capture("A", 10L, clipTimestamp = 10L)) }
        saveStarted.await()
        // While A is being encrypted, the user copies B: the clipboard snapshot changes.
        port.current = ClipboardSnapshot.Text("B", 20L)
        clock.advance(5)
        val bDeferred = async { processor.submit(capture("B", 20L, clipTimestamp = 20L)) }
        awaitQueued(processor)
        releaseFirstSave.complete(Unit)

        val a = aDeferred.await() as ProcessingResult.Stale
        assertEquals(1, a.id)
        val b = bDeferred.await() as ProcessingResult.Completed
        assertTrue(b.cleared)

        // Only B is cleared; clearing after A would have destroyed the new copy.
        assertEquals(1, port.clearCalls)
        assertEquals(1L, processor.stats.staleSkipCount)
        assertEquals(listOf("A", "B"), savedTexts)
        assertEquals(ClipboardProcessingState.COMPLETED, processor.state)
    }

    @Test
    fun mismatchedSnapshotIsNotCleared() = runBlocking {
        val port = FakeClipboard(ClipboardSnapshot.Text("B", 20L))
        val processor = newProcessor(port, FakeVault().save)

        val result = processor.submit(capture("A", 10L, clipTimestamp = 10L))
            as ProcessingResult.Stale
        assertEquals(1, result.id)
        assertEquals(0, port.clearCalls)
        assertEquals(ClipboardProcessingState.STALE_CLIP, processor.state)
        assertEquals(1L, processor.stats.captureCount)
        assertEquals(1L, processor.stats.staleSkipCount)
    }

    @Test
    fun failedSaveKeepsTheClipboard() = runBlocking {
        val port = FakeClipboard(ClipboardSnapshot.Text("A", 10L))
        val vault = FakeVault().apply { failure = SecureClipboardError.StorageFailed }
        val processor = newProcessor(port, vault.save)

        val result = processor.submit(capture("A", 10L))
            as ProcessingResult.Failed
        assertEquals(SecureClipboardError.StorageFailed, result.error)
        assertEquals(0, port.clearCalls)
        assertEquals(ClipboardProcessingState.FAILED_STORAGE, processor.state)
        assertTrue(port.current is ClipboardSnapshot.Text)
    }

    @Test
    fun cryptoFailureIsReportedSeparately() = runBlocking {
        val port = FakeClipboard(ClipboardSnapshot.Text("A", 10L))
        val vault = FakeVault().apply { failure = SecureClipboardError.KeyInvalid }
        val processor = newProcessor(port, vault.save)

        val result = processor.submit(capture("A", 10L))
            as ProcessingResult.Failed
        assertEquals(SecureClipboardError.KeyInvalid, result.error)
        assertEquals(0, port.clearCalls)
        assertEquals(ClipboardProcessingState.FAILED_CRYPTO, processor.state)
    }

    @Test
    fun saveTimeoutIsReportedAsStorageFailure() = runBlocking {
        val port = FakeClipboard(ClipboardSnapshot.Text("A", 10L))
        val processor = newProcessor(
            port,
            save = { _, _ -> awaitCancellation() },
            config = ClipboardProcessingConfig(saveTimeoutMs = 50L),
        )

        val result = processor.submit(capture("A", 10L))
            as ProcessingResult.Failed
        assertEquals(SecureClipboardError.StorageFailed, result.error)
        assertEquals(0, port.clearCalls)
        assertEquals(ClipboardProcessingState.FAILED_STORAGE, processor.state)
    }

    @Test
    fun ownClearCallbackIsIgnored() = runBlocking {
        val clock = FakeClock()
        val port = FakeClipboard(ClipboardSnapshot.Text("A", 10L))
        val processor = newProcessor(port, FakeVault().save, clock)

        val completed = processor.submit(capture("A", 10L, clipTimestamp = 10L))
            as ProcessingResult.Completed
        assertTrue(completed.cleared)
        assertEquals(ClipboardSnapshot.Empty, port.current)

        // The platform then delivers the OnPrimaryClipChanged callback caused by our own clear.
        val ignored = processor.submit(ClipboardSignal.Empty)
        assertEquals(ProcessingResult.SelfClearIgnored, ignored)
        assertEquals(ClipboardProcessingState.SELF_CLEAR_IGNORED, processor.state)
        assertEquals(1L, processor.stats.selfClearIgnoredCount)
        assertEquals(1, port.clearCalls)
    }

    @Test
    fun newCopyDuringSelfClearWindowIsCaptured() = runBlocking {
        val clock = FakeClock()
        val port = FakeClipboard(ClipboardSnapshot.Text("A", 10L))
        val vault = FakeVault()
        val processor = newProcessor(port, vault.save, clock)

        processor.submit(capture("A", 10L, clipTimestamp = 10L))
        // New copy arrives in the self-clear grace window; the empty callback may follow.
        port.current = ClipboardSnapshot.Text("B", 20L)
        clock.advance(5)
        val ignored = processor.submit(ClipboardSignal.Empty)
        assertEquals(ProcessingResult.SelfClearIgnored, ignored)
        val b = processor.submit(capture("B", 20L, clipTimestamp = 20L))
            as ProcessingResult.Completed
        assertTrue(b.cleared)
        assertEquals(listOf("A", "B"), vault.savedTexts)
        assertEquals(ClipboardProcessingState.COMPLETED, processor.state)
    }

    @Test
    fun externalEmptyClipboardAfterGraceKeepsState() = runBlocking {
        val clock = FakeClock()
        val port = FakeClipboard(ClipboardSnapshot.Text("A", 10L))
        val processor = newProcessor(port, FakeVault().save, clock)

        processor.submit(capture("A", 10L, clipTimestamp = 10L))
        clock.advance(5_000) // well beyond the self-clear window
        val empty = processor.submit(ClipboardSignal.Empty)
        assertEquals(ProcessingResult.EmptyClipboard, empty)
        assertEquals(ClipboardProcessingState.COMPLETED, processor.state)
    }

    @Test
    fun duplicatePlatformCallbackIsIgnored() = runBlocking {
        val port = FakeClipboard(ClipboardSnapshot.Text("A", 10L))
        val processor = newProcessor(port, FakeVault().save)

        processor.submit(capture("A", 10L, clipTimestamp = 10L))
        val duplicate = processor.submit(capture("A", 10L, clipTimestamp = 10L))
        assertEquals(ProcessingResult.DuplicateIgnored, duplicate)
        assertEquals(1, port.clearCalls)
        assertEquals(1L, processor.stats.duplicateCount)
    }

    @Test
    fun staleQueuedSignalExpires() = runBlocking {
        val clock = FakeClock()
        val port = FakeClipboard(ClipboardSnapshot.Text("A", 10L))
        val saveStarted = CompletableDeferred<Unit>()
        val releaseFirstSave = CompletableDeferred<Unit>()
        var saves = 0
        val save: SaveSecureCapture = { _, _ ->
            saves++
            if (saves == 1) {
                saveStarted.complete(Unit)
                releaseFirstSave.await()
            }
            SaveResult.Success(saves, 1L)
        }
        val processor = newProcessor(
            port, save, clock,
            ClipboardProcessingConfig(maxEventAgeMs = 5_000L),
        )

        val aDeferred = async { processor.submit(capture("A", 10L, clipTimestamp = 10L)) }
        saveStarted.await()
        val bDeferred = async { processor.submit(capture("B", 20L, clipTimestamp = 20L)) }
        awaitQueued(processor)
        // The queue is blocked for too long: the pending signal becomes meaningless.
        clock.advance(6_000)
        releaseFirstSave.complete(Unit)

        aDeferred.await() as ProcessingResult.Completed
        assertEquals(ProcessingResult.Expired, bDeferred.await())
        assertEquals(1, port.clearCalls)
        assertEquals(1L, processor.stats.expiredCount)
        assertEquals(ClipboardProcessingState.IDLE, processor.state)
    }

    @Test
    fun unsupportedContentIsRejectedWithoutSaving() = runBlocking {
        val port = FakeClipboard(ClipboardSnapshot.Text("A", 10L))
        val vault = FakeVault()
        val processor = newProcessor(port, vault.save)

        val sensitive = processor.submit(
            ClipboardSignal.Unsupported(UnsupportedReason.Sensitive)
        ) as ProcessingResult.Unsupported
        assertEquals(SecureClipboardError.SensitiveRejected, sensitive.error)

        val tooLongText = "x".repeat(SecureClipboardLimits.MAX_TEXT_LENGTH + 1)
        val tooLong = processor.submit(capture(tooLongText, 10L))
            as ProcessingResult.Unsupported
        assertEquals(UnsupportedReason.TooLarge, tooLong.reason)

        assertTrue(vault.savedTexts.isEmpty())
        assertEquals(0, port.clearCalls)
        assertEquals(ClipboardProcessingState.UNSUPPORTED_FORMAT, processor.state)
    }

    @Test
    fun clearAfterSavePreferenceIsRespected() = runBlocking {
        val port = FakeClipboard(ClipboardSnapshot.Text("A", 10L))
        val processor = newProcessor(port, FakeVault().save)

        val completed = processor.submit(
            capture("A", 10L, clipTimestamp = 10L, shouldClear = false)
        ) as ProcessingResult.Completed
        assertFalse(completed.cleared)
        assertEquals(0, port.clearCalls)
        assertTrue(port.current is ClipboardSnapshot.Text)
        assertEquals(ClipboardProcessingState.COMPLETED, processor.state)
    }

    @Test
    fun clearFailureIsReported() = runBlocking {
        val port = FakeClipboard(ClipboardSnapshot.Text("A", 10L)).apply { clearResult = false }
        val processor = newProcessor(port, FakeVault().save)

        val failed = processor.submit(capture("A", 10L, clipTimestamp = 10L))
            as ProcessingResult.ClearFailed
        assertEquals(1, failed.id)
        assertEquals(1, port.clearCalls)
        assertEquals(ClipboardProcessingState.FAILED_CLEAR, processor.state)
        assertEquals(1L, processor.stats.clearFailedCount)
        assertEquals(1L, processor.stats.captureCount)
    }

    @Test
    fun concurrentSignalsAreProcessedSerially() = runBlocking {
        val clock = FakeClock()
        val port = FakeClipboard(ClipboardSnapshot.Text("A", 10L))
        val saveStarted = CompletableDeferred<Unit>()
        val releaseFirstSave = CompletableDeferred<Unit>()
        val savedOrder = mutableListOf<String>()
        var saves = 0
        val save: SaveSecureCapture = { text, _ ->
            saves++
            if (saves == 1) {
                saveStarted.complete(Unit)
                releaseFirstSave.await()
            }
            savedOrder.add(text)
            SaveResult.Success(saves, 1L)
        }
        val processor = newProcessor(port, save, clock)

        val aDeferred = async(start = CoroutineStart.UNDISPATCHED) {
            processor.submit(capture("A", 10L, clipTimestamp = 10L))
        }
        saveStarted.await()
        // B and C are queued while A is still being persisted.
        val bDeferred = async(start = CoroutineStart.UNDISPATCHED) {
            processor.submit(capture("B", 20L, clipTimestamp = 20L))
        }
        val cDeferred = async(start = CoroutineStart.UNDISPATCHED) {
            processor.submit(capture("C", 30L, clipTimestamp = 30L))
        }
        awaitQueued(processor, 2)
        releaseFirstSave.complete(Unit)

        aDeferred.await()
        bDeferred.await()
        cDeferred.await()
        assertEquals(listOf("A", "B", "C"), savedOrder)
        assertEquals(0, processor.pendingSignals)
    }
}
