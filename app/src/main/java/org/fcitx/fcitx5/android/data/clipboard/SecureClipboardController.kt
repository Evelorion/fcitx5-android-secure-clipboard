/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.clipboard

import android.content.ClipData
import android.content.ClipDescription
import android.content.Context
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.secureclipboard.CaptureRequest
import org.fcitx.fcitx5.android.data.secureclipboard.CaptureSource
import org.fcitx.fcitx5.android.data.secureclipboard.ClipboardClock
import org.fcitx.fcitx5.android.data.secureclipboard.ClipboardEventProcessor
import org.fcitx.fcitx5.android.data.secureclipboard.ClipboardPort
import org.fcitx.fcitx5.android.data.secureclipboard.ClipboardProcessingConfig
import org.fcitx.fcitx5.android.data.secureclipboard.ClipboardProtectionState
import org.fcitx.fcitx5.android.data.secureclipboard.ClipboardSecurityStatus
import org.fcitx.fcitx5.android.data.secureclipboard.ClipboardSecurityStatusHolder
import org.fcitx.fcitx5.android.data.secureclipboard.ClipboardSignal
import org.fcitx.fcitx5.android.data.secureclipboard.ClipboardSnapshot
import org.fcitx.fcitx5.android.data.secureclipboard.ProcessingResult
import org.fcitx.fcitx5.android.data.secureclipboard.SaveResult
import org.fcitx.fcitx5.android.data.secureclipboard.SecureClipboardError
import org.fcitx.fcitx5.android.data.secureclipboard.SecureClipboardRepository
import org.fcitx.fcitx5.android.data.secureclipboard.UnsupportedReason
import org.fcitx.fcitx5.android.data.secureclipboard.utf8LengthExceeds
import org.fcitx.fcitx5.android.utils.clipboardManager
import timber.log.Timber
import android.content.ClipboardManager as SystemClipboardManager

/**
 * Channel B ("protect normal copy") implementation.
 *
 * Registered by the [ClipboardManager] facade as the single system-clipboard listener while secure
 * mode is enabled. Every clipboard change is turned into a [ClipboardSignal], processed serially by
 * [ClipboardEventProcessor] (encrypt -> persist -> re-check snapshot -> clear) and reported back to
 * the facade so the clipboard panel can show a masked placeholder entry.
 *
 * Security notes:
 *  - Only a single, non-sensitive text/plain item is ever captured; images, URIs, HTML, multi-item
 *    clips, sensitive clips and over-long texts are rejected before any plaintext is retained.
 *  - Plaintext never reaches the log. Only class names of thrown exceptions are logged.
 *  - The system clipboard is only cleared when the snapshot read back after a successful save is
 *    still identical to what was encrypted.
 */
class SecureClipboardController(
    context: Context,
    private val scope: CoroutineScope,
    private val repositoryProvider: () -> SecureClipboardRepository,
    private val onEntryPersisted: (id: Int, timestamp: Long, source: CaptureSource) -> Unit,
    private val transformText: (String) -> String,
) : SystemClipboardManager.OnPrimaryClipChangedListener {

    private val appContext = context.applicationContext
    private val systemClipboard = appContext.clipboardManager
    private val mainDispatcher = Dispatchers.Main.immediate
    private val processingConfig = ClipboardProcessingConfig()

    private val securityPrefs get() = AppPrefs.getInstance().security

    private val processor = ClipboardEventProcessor(
        clipboard = object : ClipboardPort {
            override suspend fun read(): ClipboardSnapshot =
                withContext(mainDispatcher) { parseCurrentClip().toSnapshot() }

            override suspend fun clear(): Boolean =
                withContext(mainDispatcher) { clearSystemClipboard() }
        },
        save = ::persistCapture,
        clock = ClipboardClock { System.currentTimeMillis() },
        config = processingConfig,
    )

    @Volatile
    private var listening = false

    /**
     * Register / unregister the system clipboard listener. Called from the facade on the main
     * thread; android.content.ClipboardManager listener registration is main-thread bound.
     */
    fun setListening(enabled: Boolean) {
        if (enabled == listening) return
        listening = enabled
        if (enabled) {
            systemClipboard.addPrimaryClipChangedListener(this)
            val ready = try {
                repositoryProvider()
                true
            } catch (e: Exception) {
                false
            }
            updateStatus {
                it.copy(
                    state = if (ready) {
                        ClipboardProtectionState.Protected
                    } else {
                        ClipboardProtectionState.Degraded
                    }
                )
            }
        } else {
            systemClipboard.removePrimaryClipChangedListener(this)
            updateStatus { it.copy(state = ClipboardProtectionState.Disabled) }
        }
    }

    override fun onPrimaryClipChanged() {
        if (!listening) return
        val signal = try {
            parseCurrentClip().toSignal()
        } catch (e: Exception) {
            Timber.w("Secure clipboard: cannot parse primary clip (${e.javaClass.simpleName})")
            return
        }
        scope.launch {
            val result = processor.submit(signal)
            applyResult(result)
        }
    }

    /* =================== persistence =================== */

    private suspend fun persistCapture(text: String, timestamp: Long): SaveResult {
        val transformed = try {
            transformText(text)
        } catch (e: Exception) {
            Timber.w("Secure clipboard: transformer failed (${e.javaClass.simpleName})")
            text
        }
        val request = CaptureRequest(
            text = transformed,
            source = CaptureSource.SystemClipboard,
            timestamp = timestamp,
        )
        val repository = try {
            repositoryProvider()
        } catch (e: Exception) {
            return SaveResult.Failure(SecureClipboardError.NotReady)
        }
        return try {
            repository.save(request)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w("Secure clipboard: save failed (${e.javaClass.simpleName})")
            SaveResult.Failure(SecureClipboardError.StorageFailed, e)
        }
    }

    /* =================== platform clipboard access =================== */

    private fun parseCurrentClip(): ParsedClip {
        val clip = try {
            systemClipboard.primaryClip
        } catch (e: Exception) {
            Timber.w("Secure clipboard: clipboard read denied (${e.javaClass.simpleName})")
            return ParsedClip.Unreadable
        } ?: return ParsedClip.Empty
        return parseClipData(clip)
    }

    private fun parseClipData(clip: ClipData): ParsedClip {
        val description = clip.description
        if (clip.itemCount > 1) {
            return ParsedClip.Unsupported(UnsupportedReason.MultipleItems)
        }
        if (clip.itemCount < 1) {
            return ParsedClip.Empty
        }
        val mimeTypeCount = try {
            description.mimeTypeCount
        } catch (e: Exception) {
            return ParsedClip.Unreadable
        }
        // Only a pure text/plain copy is captured. Mixed clips (text/html + text/plain, URI,
        // image, ...) are policy rejects so no unexpected content is encrypted.
        if (mimeTypeCount != 1) {
            return ParsedClip.Unsupported(UnsupportedReason.NonPlainText)
        }
        if (description.getMimeType(0) != ClipDescription.MIMETYPE_TEXT_PLAIN) {
            return ParsedClip.Unsupported(UnsupportedReason.NonPlainText)
        }
        if (isSensitive(description)) {
            return ParsedClip.Unsupported(UnsupportedReason.Sensitive)
        }
        val text = clip.getItemAt(0)?.text?.toString()
            ?: return ParsedClip.Unsupported(UnsupportedReason.NonPlainText)
        if (text.isBlank()) {
            // A local blank copy is indistinguishable from the pre-API-28 clear fallback
            // (setPrimaryClip with an empty text). Neither carries content worth capturing.
            return ParsedClip.Empty
        }
        if (text.length > processingConfig.maxTextLength ||
            utf8LengthExceeds(text, processingConfig.maxTextUtf8Bytes)
        ) {
            return ParsedClip.Unsupported(UnsupportedReason.TooLarge)
        }
        val clipTimestamp = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            description.timestamp
        } else {
            null
        }
        return ParsedClip.Text(text, clipTimestamp)
    }

    private fun isSensitive(description: ClipDescription): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        return try {
            val extras = description.extras ?: return false
            val key = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ClipDescription.EXTRA_IS_SENSITIVE
            } else {
                LEGACY_SENSITIVE_EXTRA
            }
            extras.getBoolean(key, false)
        } catch (e: Exception) {
            false
        }
    }

    private fun clearSystemClipboard(): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            systemClipboard.clearPrimaryClip()
        } else {
            // clearPrimaryClip() is API 28+; overwriting with an empty text is the closest
            // available fallback and does not leave user content behind.
            systemClipboard.setPrimaryClip(ClipData.newPlainText("", ""))
        }
        true
    } catch (e: Exception) {
        Timber.w("Secure clipboard: clearing the clipboard failed (${e.javaClass.simpleName})")
        false
    }

    /* =================== signal / snapshot mapping =================== */

    private sealed interface ParsedClip {
        data class Text(val text: String, val clipTimestamp: Long?) : ParsedClip
        data object Empty : ParsedClip
        data class Unsupported(val reason: UnsupportedReason) : ParsedClip
        data object Unreadable : ParsedClip
    }

    private fun ParsedClip.toSignal(): ClipboardSignal = when (this) {
        is ParsedClip.Text -> ClipboardSignal.Text(
            text = text,
            timestamp = clipTimestamp ?: System.currentTimeMillis(),
            clipTimestamp = clipTimestamp,
            shouldClear = shouldClearAfterSave(),
        )

        is ParsedClip.Unsupported -> ClipboardSignal.Unsupported(reason)
        ParsedClip.Empty -> ClipboardSignal.Empty
        ParsedClip.Unreadable -> ClipboardSignal.Unsupported(UnsupportedReason.Unreadable)
    }

    private fun ParsedClip.toSnapshot(): ClipboardSnapshot = when (this) {
        is ParsedClip.Text -> ClipboardSnapshot.Text(text, clipTimestamp)
        is ParsedClip.Unsupported -> ClipboardSnapshot.Other(reason)
        ParsedClip.Empty -> ClipboardSnapshot.Empty
        ParsedClip.Unreadable -> ClipboardSnapshot.Unreadable
    }

    /**
     * Compatibility mode deliberately keeps the system clipboard so apps that rely on native
     * paste keep working; the encrypted copy is still stored, it is just not cleared.
     */
    private fun shouldClearAfterSave(): Boolean {
        val prefs = securityPrefs
        return prefs.secureClipboardClearAfterSave.getValue() &&
            !prefs.secureClipboardCompatMode.getValue()
    }

    /* =================== status reporting =================== */

    private fun applyResult(result: ProcessingResult) {
        val now = System.currentTimeMillis()
        when (result) {
            is ProcessingResult.Completed -> notifyPersisted(result.id, result.timestamp)
            is ProcessingResult.Stale -> notifyPersisted(result.id, result.timestamp)
            is ProcessingResult.ClearFailed -> notifyPersisted(result.id, result.timestamp)
            else -> Unit
        }
        val stats = processor.stats
        ClipboardSecurityStatusHolder.update { current ->
            val base = current.copy(
                captureCount = stats.captureCount,
                clearCount = stats.clearCount,
                staleSkipCount = stats.staleSkipCount,
                clearFailedCount = stats.clearFailedCount,
            )
            when (result) {
                is ProcessingResult.Completed -> base.copy(
                    state = ClipboardProtectionState.Protected,
                    lastCaptureTime = result.timestamp,
                    lastCaptureSource = result.source,
                    lastCaptureSuccess = true,
                    lastError = null,
                    lastErrorTime = null,
                    lastClearTime = if (result.cleared) now else current.lastClearTime,
                    lastClearSuccess = if (result.cleared) true else current.lastClearSuccess,
                )

                is ProcessingResult.Stale -> base.copy(
                    state = ClipboardProtectionState.Protected,
                    lastCaptureTime = result.timestamp,
                    lastCaptureSource = result.source,
                    lastCaptureSuccess = true,
                    lastError = null,
                    lastErrorTime = null,
                )

                is ProcessingResult.ClearFailed -> base.copy(
                    state = ClipboardProtectionState.Degraded,
                    lastCaptureTime = result.timestamp,
                    lastCaptureSource = result.source,
                    lastCaptureSuccess = true,
                    lastClearTime = now,
                    lastClearSuccess = false,
                )

                is ProcessingResult.Failed -> base.copy(
                    state = ClipboardProtectionState.Degraded,
                    lastCaptureTime = now,
                    lastCaptureSource = CaptureSource.SystemClipboard,
                    lastCaptureSuccess = false,
                    lastError = result.error,
                    lastErrorTime = if (result.error != null) now else current.lastErrorTime,
                )

                is ProcessingResult.Unsupported -> base.copy(
                    // Policy rejections are not a protection failure; history stays safe.
                    lastCaptureTime = now,
                    lastCaptureSource = CaptureSource.SystemClipboard,
                    lastCaptureSuccess = false,
                    lastError = result.error,
                    lastErrorTime = now,
                )

                ProcessingResult.SelfClearIgnored,
                ProcessingResult.DuplicateIgnored,
                ProcessingResult.Expired,
                ProcessingResult.EmptyClipboard,
                ProcessingResult.Cancelled -> base
            }
        }
    }

    private fun notifyPersisted(id: Int, timestamp: Long) {
        try {
            onEntryPersisted(id, timestamp, CaptureSource.SystemClipboard)
        } catch (e: Exception) {
            Timber.w("Secure clipboard: persisted callback failed (${e.javaClass.simpleName})")
        }
    }

    private fun updateStatus(block: (ClipboardSecurityStatus) -> ClipboardSecurityStatus) {
        ClipboardSecurityStatusHolder.update(block)
    }

    companion object {
        private const val LEGACY_SENSITIVE_EXTRA = "android.content.extra.IS_SENSITIVE"
    }
}
