/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.secureclipboard

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicLong

/*
 * Channel B event processing: system clipboard -> validated snapshot -> encrypted save ->
 * re-validate the snapshot -> clear the system clipboard.
 *
 * The processor is intentionally free of any "android.*" dependency so the race handling can be
 * unit tested on the JVM. Platform access is injected through [ClipboardPort]; the encryption and
 * storage are injected through [SaveSecureCapture]. The whole class never logs or persists
 * plaintext; [ClipboardSignal.Text.text] only lives while the signal is being processed.
 *
 * Concurrency model: every signal is appended to a fair serial queue. Exactly one coroutine drains
 * the queue at a time, so two captures never race with each other. TOCTOU between "read the
 * clipboard" and "clear the clipboard" cannot be removed with the public Android API, but the
 * post-persist snapshot comparison narrows it to the smallest possible window and never clears a
 * clip that changed in the meantime.
 */

/** Monotonic time source, injectable so tests do not depend on wall-clock time. */
fun interface ClipboardClock {
    fun now(): Long
}

/** What the system clipboard currently contains, as far as the platform lets us know. */
sealed interface ClipboardSnapshot {
    /** A single text/plain item. [clipTimestamp] is ClipDescription.getTimestamp() on API 26+. */
    data class Text(val text: String, val clipTimestamp: Long?) : ClipboardSnapshot

    /** No primary clip at all (e.g. right after clearPrimaryClip()). */
    data object Empty : ClipboardSnapshot

    /** Something is in the clipboard but it is not a single non-sensitive text/plain item. */
    data class Other(val reason: UnsupportedReason) : ClipboardSnapshot

    /** The platform refused to hand out the clipboard content. */
    data object Unreadable : ClipboardSnapshot
}

/** A capture-relevant observation produced by the platform clipboard listener. */
sealed interface ClipboardSignal {
    /** A copy that is eligible for secure capture. [timestamp] becomes the record timestamp. */
    data class Text(
        val text: String,
        val timestamp: Long,
        /** ClipDescription timestamp when available, used to detect a clipboard replacement. */
        val clipTimestamp: Long?,
        /** Whether the clipboard should be cleared after a successful save (user preference). */
        val shouldClear: Boolean,
    ) : ClipboardSignal

    /** A copy that the policy rejects (images, URIs, HTML, multi-item, sensitive, too long). */
    data class Unsupported(val reason: UnsupportedReason) : ClipboardSignal

    /** Fired when the clipboard became empty (possibly because of our own clear). */
    data object Empty : ClipboardSignal
}

/** Why a clipboard observation is not eligible for secure capture. */
enum class UnsupportedReason(val error: SecureClipboardError) {
    EmptyText(SecureClipboardError.EmptyText),
    TooLarge(SecureClipboardError.TooLargeText),
    Sensitive(SecureClipboardError.SensitiveRejected),
    NonPlainText(SecureClipboardError.UnsupportedFormat),
    MultipleItems(SecureClipboardError.UnsupportedFormat),
    Unreadable(SecureClipboardError.UnsupportedFormat),
}

/**
 * Platform access used by the verify / clear phase of the state machine.
 * Implemented with android.content.ClipboardManager in [org.fcitx.fcitx5.android.data.clipboard.SecureClipboardController].
 */
interface ClipboardPort {
    suspend fun read(): ClipboardSnapshot

    /** @return true when the clipboard was cleared, false when the platform refused. */
    suspend fun clear(): Boolean
}

/** Encrypts and persists one capture. Must never store or log plaintext. */
typealias SaveSecureCapture = suspend (text: String, timestamp: Long) -> SaveResult

/** Observable stages of one channel B capture. */
enum class ClipboardProcessingState {
    IDLE,
    CAPTURED,
    VALIDATING,
    ENCRYPTING,
    PERSISTING,
    VERIFYING,
    CLEARING,
    COMPLETED,

    /** The key is unavailable/invalid or encryption failed; nothing was persisted. */
    FAILED_CRYPTO,

    /** The encrypted database rejected the record; nothing was persisted. */
    FAILED_STORAGE,

    /** The clipboard could not be (re)read before clearing; nothing was cleared. */
    FAILED_CLIPBOARD_ACCESS,

    /** The record was persisted but the clipboard could not be cleared. */
    FAILED_CLEAR,

    /** The record was persisted but the clipboard content changed meanwhile; it was not cleared. */
    STALE_CLIP,

    /** The content is not eligible for secure capture (empty/over-long/sensitive/not plain text). */
    UNSUPPORTED_FORMAT,

    /** An empty clipboard observation that was caused by our own clearPrimaryClip() call. */
    SELF_CLEAR_IGNORED,
}

/** Non-sensitive counters, mirrored into ClipboardSecurityStatusHolder by the controller. */
data class ClipboardProcessingStats(
    /** Records that were successfully encrypted and persisted. */
    val captureCount: Long = 0,
    /** Successful clearPrimaryClip() calls. */
    val clearCount: Long = 0,
    /** Captures persisted but skipped clearing because the clipboard changed. */
    val staleSkipCount: Long = 0,
    /** Captures persisted but clearPrimaryClip() failed. */
    val clearFailedCount: Long = 0,
    val selfClearIgnoredCount: Long = 0,
    val duplicateCount: Long = 0,
    val expiredCount: Long = 0,
    val failedCount: Long = 0,
)

/** Tuning knobs; defaults are production values and tests inject smaller ones. */
data class ClipboardProcessingConfig(
    val maxTextLength: Int = SecureClipboardLimits.MAX_TEXT_LENGTH,
    val maxTextUtf8Bytes: Int = SecureClipboardLimits.MAX_TEXT_UTF8_BYTES,
    /** Signals that waited longer than this in the serial queue are dropped. */
    val maxEventAgeMs: Long = 10_000L,
    /** A save that takes longer than this is treated as a storage failure. */
    val saveTimeoutMs: Long = 20_000L,
    /** Window after our own clear in which an empty clipboard observation is treated as self-caused. */
    val selfClearGraceMs: Long = 1_000L,
    /** Fallback duplicate window for platforms without ClipDescription timestamps (API < 26). */
    val duplicateWindowMs: Long = 500L,
)

/** Result of one processed signal. Plaintext is never part of a result. */
sealed interface ProcessingResult {
    /**
     * The record was persisted. [cleared] tells whether the system clipboard was cleared as well;
     * it is false when the user disabled "clear after save" or enabled compatibility mode.
     */
    data class Completed(
        val id: Int,
        val timestamp: Long,
        val source: CaptureSource,
        val cleared: Boolean,
    ) : ProcessingResult

    /** Persisted, but the clipboard changed before verification, so it was not cleared. */
    data class Stale(
        val id: Int,
        val timestamp: Long,
        val source: CaptureSource,
    ) : ProcessingResult

    /** Persisted, but clearPrimaryClip() failed (the plaintext may still be in the clipboard). */
    data class ClearFailed(
        val id: Int,
        val timestamp: Long,
        val source: CaptureSource,
    ) : ProcessingResult

    /** Encryption/storage/clipboard-access failure. [error] is null when no contract error fits. */
    data class Failed(
        val error: SecureClipboardError?,
        val cause: Throwable? = null,
    ) : ProcessingResult

    /** The content was intentionally not captured. */
    data class Unsupported(
        val reason: UnsupportedReason,
        val error: SecureClipboardError,
    ) : ProcessingResult

    /** The observed empty clipboard was caused by our own clear. */
    data object SelfClearIgnored : ProcessingResult

    /** A duplicated platform callback for an already handled clip. */
    data object DuplicateIgnored : ProcessingResult

    /** The signal was too old when its turn in the queue came (process was busy). */
    data object Expired : ProcessingResult

    /** External clear / empty clipboard, not caused by us. */
    data object EmptyClipboard : ProcessingResult

    /** The owning coroutine scope was cancelled while the signal was queued. */
    data object Cancelled : ProcessingResult
}

/** Bounds check helper shared with the controller when it pre-parses ClipData. */
fun utf8LengthExceeds(text: String, limit: Int): Boolean {
    if (limit <= 0) return text.isNotEmpty()
    // UTF-8 uses at most 3 bytes per UTF-16 code unit (a surrogate pair is 4 bytes for 2 units),
    // so texts below limit / 3 can be accepted without materialising a byte array.
    if (text.length <= limit / 3) return false
    return text.toByteArray(Charsets.UTF_8).size > limit
}

/**
 * Serialized channel B state machine.
 *
 * Callers feed every clipboard observation through [submit]; the returned result is delivered on
 * the dispatcher of the submitting coroutine. Signals must be submitted from a coroutine that is
 * not the same one that is currently inside [ClipboardPort.clear] (the Android listener dispatches
 * asynchronously, so the controller always submits from a launched coroutine).
 */
class ClipboardEventProcessor(
    private val clipboard: ClipboardPort,
    private val save: SaveSecureCapture,
    private val clock: ClipboardClock = ClipboardClock { System.currentTimeMillis() },
    private val config: ClipboardProcessingConfig = ClipboardProcessingConfig(),
) {

    private class QueuedSignal(
        val signal: ClipboardSignal,
        val sequence: Long,
        val enqueuedAt: Long,
        val result: CompletableDeferred<ProcessingResult>,
    )

    private class CompletedCapture(
        val textHash: Int,
        val clipTimestamp: Long?,
        val completedAt: Long,
    )

    private val queueMutex = Mutex()
    private val queue = ArrayDeque<QueuedSignal>()
    private var draining = false

    private val submittedSequence = AtomicLong(0)

    @Volatile
    private var pendingCount = 0

    @Volatile
    private var lastCompleted: CompletedCapture? = null

    @Volatile
    private var selfClearArmed = false

    @Volatile
    private var selfClearDeadline = 0L

    @Volatile
    var state: ClipboardProcessingState = ClipboardProcessingState.IDLE
        private set

    @Volatile
    var stats: ClipboardProcessingStats = ClipboardProcessingStats()
        private set

    /** Monotonic count of submitted signals; useful for diagnostics and tests. */
    val submittedSignals: Long get() = submittedSequence.get()

    /** Number of signals waiting behind the currently processed one. */
    val pendingSignals: Int get() = pendingCount

    private val cryptoErrors = setOf(
        SecureClipboardError.KeyUnavailable,
        SecureClipboardError.KeyInvalid,
        SecureClipboardError.EncryptionFailed,
    )

    /** Enqueue one observation and process it in order. Never throws for capture failures. */
    suspend fun submit(signal: ClipboardSignal): ProcessingResult {
        val sequence = submittedSequence.incrementAndGet()
        val queued = QueuedSignal(signal, sequence, clock.now(), CompletableDeferred())
        val isDrainer = queueMutex.withLock {
            queue.addLast(queued)
            pendingCount = queue.size
            if (draining) {
                false
            } else {
                draining = true
                true
            }
        }
        if (isDrainer) {
            drainQueue()
        }
        return queued.result.await()
    }

    private suspend fun drainQueue() {
        var current: QueuedSignal? = null
        try {
            while (true) {
                current = queueMutex.withLock {
                    if (queue.isEmpty()) {
                        null
                    } else {
                        queue.removeFirst().also { pendingCount = queue.size }
                    }
                } ?: break
                val item = current
                val result = processSafely(item.signal, item.enqueuedAt)
                item.result.complete(result)
                current = null
            }
        } finally {
            val leftovers = ArrayList<QueuedSignal>(queue.size + 1)
            queueMutex.withLock {
                current?.let { leftovers.add(it) }
                while (queue.isNotEmpty()) {
                    leftovers.add(queue.removeFirst())
                }
                pendingCount = 0
                draining = false
            }
            leftovers.forEach {
                if (!it.result.isCompleted) {
                    it.result.complete(ProcessingResult.Cancelled)
                }
            }
        }
    }

    private suspend fun processSafely(signal: ClipboardSignal, enqueuedAt: Long): ProcessingResult =
        try {
            process(signal, enqueuedAt)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Last-resort guard: processing must never take the listener down. The exception
            // message is not logged because it is not guaranteed to be free of user content.
            state = ClipboardProcessingState.FAILED_STORAGE
            bumpStats { it.copy(failedCount = it.failedCount + 1) }
            ProcessingResult.Failed(SecureClipboardError.StorageFailed)
        }

    private suspend fun process(signal: ClipboardSignal, enqueuedAt: Long): ProcessingResult {
        if (clock.now() - enqueuedAt > config.maxEventAgeMs) {
            bumpStats { it.copy(expiredCount = it.expiredCount + 1) }
            state = ClipboardProcessingState.IDLE
            return ProcessingResult.Expired
        }
        return when (signal) {
            ClipboardSignal.Empty -> processEmptyObservation()
            is ClipboardSignal.Unsupported -> processUnsupported(signal.reason)
            is ClipboardSignal.Text -> processText(signal)
        }
    }

    private fun processEmptyObservation(): ProcessingResult {
        val now = clock.now()
        if (selfClearArmed && now <= selfClearDeadline) {
            bumpStats { it.copy(selfClearIgnoredCount = it.selfClearIgnoredCount + 1) }
            state = ClipboardProcessingState.SELF_CLEAR_IGNORED
            return ProcessingResult.SelfClearIgnored
        }
        // An externally observed empty clipboard is not a capture attempt: keep the last state.
        selfClearArmed = false
        return ProcessingResult.EmptyClipboard
    }

    private fun processUnsupported(reason: UnsupportedReason): ProcessingResult {
        // Somebody copied content we do not handle; any pending self-clear notification is gone.
        selfClearArmed = false
        state = ClipboardProcessingState.UNSUPPORTED_FORMAT
        bumpStats { it.copy(failedCount = it.failedCount + 1) }
        return ProcessingResult.Unsupported(reason, reason.error)
    }

    private suspend fun processText(signal: ClipboardSignal.Text): ProcessingResult {
        val text = signal.text
        val textHash = text.hashCode()

        if (isDuplicate(signal.clipTimestamp, textHash)) {
            bumpStats { it.copy(duplicateCount = it.duplicateCount + 1) }
            return ProcessingResult.DuplicateIgnored
        }

        // A real capture attempt means any pending "our clear caused this" notification is stale.
        selfClearArmed = false

        state = ClipboardProcessingState.CAPTURED

        state = ClipboardProcessingState.VALIDATING
        validate(text)?.let { reason ->
            state = ClipboardProcessingState.UNSUPPORTED_FORMAT
            bumpStats { it.copy(failedCount = it.failedCount + 1) }
            return ProcessingResult.Unsupported(reason, reason.error)
        }

        // ENCRYPTING and PERSISTING happen inside a single repository.save() transaction; both
        // states are exposed so a failure can be attributed to either phase.
        state = ClipboardProcessingState.ENCRYPTING
        val saveResult = try {
            withTimeout(config.saveTimeoutMs) { save(text, signal.timestamp) }
        } catch (e: TimeoutCancellationException) {
            state = ClipboardProcessingState.FAILED_STORAGE
            bumpStats { it.copy(failedCount = it.failedCount + 1) }
            return ProcessingResult.Failed(SecureClipboardError.StorageFailed)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            state = ClipboardProcessingState.FAILED_STORAGE
            bumpStats { it.copy(failedCount = it.failedCount + 1) }
            return ProcessingResult.Failed(SecureClipboardError.StorageFailed)
        }

        val success = when (saveResult) {
            is SaveResult.Failure -> {
                state = if (saveResult.error in cryptoErrors) {
                    ClipboardProcessingState.FAILED_CRYPTO
                } else {
                    ClipboardProcessingState.FAILED_STORAGE
                }
                bumpStats { it.copy(failedCount = it.failedCount + 1) }
                return ProcessingResult.Failed(saveResult.error, saveResult.cause)
            }
            is SaveResult.Success -> saveResult
        }

        state = ClipboardProcessingState.PERSISTING
        bumpStats { it.copy(captureCount = it.captureCount + 1) }
        val completed = CompletedCapture(textHash, signal.clipTimestamp, clock.now())

        // The record is safely stored. Before clearing anything we must prove that the clipboard
        // still contains exactly the snapshot we encrypted; otherwise the user copied something
        // else in the meantime and clearing would silently destroy that new copy.
        state = ClipboardProcessingState.VERIFYING
        val current = readSafely()
        val stillSame = when (current) {
            is ClipboardSnapshot.Text -> current.text == text &&
                timestampsMatch(signal.clipTimestamp, current.clipTimestamp)
            ClipboardSnapshot.Empty -> false
            is ClipboardSnapshot.Other -> false
            ClipboardSnapshot.Unreadable -> null
        }
        when (stillSame) {
            null -> {
                state = ClipboardProcessingState.FAILED_CLIPBOARD_ACCESS
                bumpStats { it.copy(failedCount = it.failedCount + 1) }
                lastCompleted = completed
                return ProcessingResult.Failed(null)
            }
            false -> {
                state = ClipboardProcessingState.STALE_CLIP
                bumpStats { it.copy(staleSkipCount = it.staleSkipCount + 1) }
                lastCompleted = completed
                return ProcessingResult.Stale(success.id, signal.timestamp, CaptureSource.SystemClipboard)
            }
            true -> Unit
        }

        if (!signal.shouldClear) {
            state = ClipboardProcessingState.COMPLETED
            lastCompleted = completed
            return ProcessingResult.Completed(
                success.id, signal.timestamp, CaptureSource.SystemClipboard, cleared = false
            )
        }

        state = ClipboardProcessingState.CLEARING
        val cleared = try {
            clipboard.clear()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
        lastCompleted = completed
        if (!cleared) {
            state = ClipboardProcessingState.FAILED_CLEAR
            bumpStats { it.copy(clearFailedCount = it.clearFailedCount + 1) }
            return ProcessingResult.ClearFailed(success.id, signal.timestamp, CaptureSource.SystemClipboard)
        }

        state = ClipboardProcessingState.COMPLETED
        bumpStats { it.copy(clearCount = it.clearCount + 1) }
        // clearPrimaryClip() notifies the listener again with an empty clipboard. Arm a short
        // window so that this self-caused callback is not mistaken for an external clipboard
        // change (the window is also dropped as soon as a real text copy arrives).
        selfClearArmed = true
        selfClearDeadline = clock.now() + config.selfClearGraceMs
        return ProcessingResult.Completed(
            success.id, signal.timestamp, CaptureSource.SystemClipboard, cleared = true
        )
    }

    private fun validate(text: String): UnsupportedReason? = when {
        text.isBlank() -> UnsupportedReason.EmptyText
        text.length > config.maxTextLength -> UnsupportedReason.TooLarge
        utf8LengthExceeds(text, config.maxTextUtf8Bytes) -> UnsupportedReason.TooLarge
        else -> null
    }

    private fun isDuplicate(clipTimestamp: Long?, textHash: Int): Boolean {
        val previous = lastCompleted ?: return false
        return if (clipTimestamp != null && previous.clipTimestamp != null) {
            clipTimestamp == previous.clipTimestamp && textHash == previous.textHash
        } else {
            // API < 26 has no ClipDescription timestamp; fall back to a short time window.
            textHash == previous.textHash &&
                clock.now() - previous.completedAt <= config.duplicateWindowMs
        }
    }

    private fun timestampsMatch(expected: Long?, actual: Long?): Boolean =
        if (expected == null || actual == null) true else expected == actual

    private suspend fun readSafely(): ClipboardSnapshot = try {
        clipboard.read()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        ClipboardSnapshot.Unreadable
    }

    private fun bumpStats(block: (ClipboardProcessingStats) -> ClipboardProcessingStats) {
        stats = block(stats)
    }
}
