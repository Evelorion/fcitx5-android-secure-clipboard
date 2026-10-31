/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.securecapture

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.clipboard.ClipboardManager
import org.fcitx.fcitx5.android.data.secureclipboard.CaptureRequest
import org.fcitx.fcitx5.android.data.secureclipboard.CaptureSource
import org.fcitx.fcitx5.android.data.secureclipboard.SaveResult
import org.fcitx.fcitx5.android.data.secureclipboard.SecureClipboardError
import org.fcitx.fcitx5.android.data.secureclipboard.SecureClipboardLimits
import org.fcitx.fcitx5.android.data.secureclipboard.SecureClipboardVault
import org.fcitx.fcitx5.android.databinding.ActivitySecureCaptureBinding
import org.fcitx.fcitx5.android.utils.toast
import timber.log.Timber
import java.util.Locale

/**
 * Shared confirmation and encrypted save flow for the two externally reachable capture entry
 * points (ACTION_PROCESS_TEXT and ACTION_SEND).
 *
 * Security properties of this base class:
 *  - The window is FLAG_SECURE, the preview is masked and the plaintext is never rendered.
 *  - The user must explicitly confirm before anything is stored.
 *  - Nothing is written to the system clipboard and no plaintext is returned to the caller; the
 *    result is RESULT_OK without data, or RESULT_CANCELED.
 *  - Plaintext only lives in [pendingRequest] while the confirmation dialog is showing and is
 *    dropped as soon as the activity is destroyed.
 *  - Storage errors are mapped to stable, non-sensitive messages.
 */
abstract class BaseSecureCaptureActivity : Activity() {

    private val scope: CoroutineScope = MainScope()

    private lateinit var binding: ActivitySecureCaptureBinding

    private var pendingRequest: CaptureRequest? = null

    private var saving = false

    /** Capture source of the concrete entry point. */
    protected abstract val captureSource: CaptureSource

    /** Validate the launch intent without trusting it and without reading other app data. */
    protected abstract fun validateIntent(intent: Intent?): SecureCaptureValidation

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Never allow screenshots or screen recording of capture content.
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        // The PROCESS_TEXT / SEND activities are exported, so any installed app can start them.
        // The user still has to confirm, but throttle the confirmation UI to keep a malicious
        // caller from spamming dialogs (DoS). This does not weaken the confirmation requirement.
        if (!allowLaunch(SystemClock.elapsedRealtime())) {
            toast(R.string.secure_capture_rate_limited)
            finish()
            return
        }
        binding = ActivitySecureCaptureBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.secureCaptureCancel.setOnClickListener { cancelCapture() }
        binding.secureCaptureSave.setOnClickListener { saveCapture() }
        processIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        processIntent(intent)
    }

    private fun processIntent(intent: Intent?) {
        if (intent == null) {
            showErrorAndFinish(SecureClipboardError.UnsupportedFormat)
            return
        }
        val sourceApp = resolveSourcePackage()
        val action = intent.action
        val validation = try {
            validateIntent(intent)
        } catch (e: Exception) {
            null
        }
        // Drop the plaintext extra from this process local Intent copy as soon as it has been
        // validated. The system may still keep its own copy in the activity record; this cannot
        // be avoided for an externally started activity and is documented as a remaining risk.
        intent.removeExtra(Intent.EXTRA_PROCESS_TEXT)
        intent.removeExtra(Intent.EXTRA_TEXT)
        when (validation) {
            is SecureCaptureValidation.Valid -> {
                if (!ClipboardManager.isSecureMode) {
                    showErrorAndFinish(SecureClipboardError.Disabled)
                    return
                }
                if (!SecureClipboardVault.isInitialized) {
                    showErrorAndFinish(SecureClipboardError.NotReady)
                    return
                }
                val request = CaptureRequest(
                    text = validation.text,
                    source = captureSource,
                    sensitive = validation.sensitive,
                    sourceApp = sourceApp,
                    readOnly = validation.readOnly,
                )
                pendingRequest = request
                renderConfirmation(validation, action, sourceApp)
            }

            is SecureCaptureValidation.Invalid -> showErrorAndFinish(validation.error)

            null -> showErrorAndFinish(SecureClipboardError.UnsupportedFormat)
        }
    }

    private fun renderConfirmation(
        valid: SecureCaptureValidation.Valid,
        action: String?,
        sourceApp: String?,
    ) {
        // Only the mask is rendered, never the captured text.
        binding.secureCapturePreview.text = SecureCaptureValidator.maskPreview(valid.text)
        binding.secureCaptureInfo.text =
            getString(R.string.secure_capture_source, buildSourceLabel(action, sourceApp))
        binding.secureCaptureLength.text =
            getString(R.string.secure_capture_length, valid.text.length)
        binding.secureCaptureReadonly.visibility = if (valid.readOnly) View.VISIBLE else View.GONE
        saving = false
        setButtonsEnabled(true)
    }

    private fun buildSourceLabel(action: String?, sourceApp: String?): String {
        val entryPoint = action?.substringAfterLast('.')?.takeIf { it.isNotEmpty() }
            ?: captureSource.storageValue.uppercase(Locale.ROOT)
        return listOfNotNull(entryPoint, sourceApp).joinToString(" \u00b7 ")
    }

    /**
     * Best effort package name of the launching app. The referrer is supplied by the platform
     * for explicit launches; no other app data is queried. Null when the platform does not tell.
     */
    private fun resolveSourcePackage(): String? {
        try {
            callingPackage?.takeIf { it.isNotEmpty() }?.let { return it }
        } catch (e: Exception) {
            // getCallingPackage is allowed to be unavailable; ignore.
        }
        return try {
            referrer?.takeIf { it.scheme == "android-app" }?.host
        } catch (e: Exception) {
            null
        }
    }

    private fun saveCapture() {
        if (saving) return
        val request = pendingRequest ?: return
        saving = true
        setButtonsEnabled(false)
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    ClipboardManager.saveSecureCapture(request)
                } catch (e: Exception) {
                    // Never log the throwable message: it may embed the captured text.
                    Timber.w("Secure capture save failed: ${e.javaClass.simpleName}")
                    SaveResult.Failure(SecureClipboardError.StorageFailed)
                }
            }
            saving = false
            when (result) {
                is SaveResult.Success -> {
                    // RESULT_OK without data: the captured text is never handed back.
                    setResult(RESULT_OK)
                    toast(R.string.secure_capture_saved)
                    finish()
                }

                is SaveResult.Failure -> {
                    setButtonsEnabled(true)
                    showFailureDialog(result.error)
                }
            }
        }
    }

    private fun cancelCapture() {
        if (saving) return
        setResult(RESULT_CANCELED)
        finish()
    }

    private fun setButtonsEnabled(enabled: Boolean) {
        binding.secureCaptureSave.isEnabled = enabled
        binding.secureCaptureCancel.isEnabled = enabled
    }

    private fun showFailureDialog(error: SecureClipboardError) {
        AlertDialog.Builder(this)
            .setTitle(R.string.secure_capture)
            .setMessage(errorMessage(error))
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    /**
     * Reject an invalid or unsupported request immediately: show a non-sensitive error toast and
     * finish. A malformed external intent never gets to keep a dialog on screen.
     */
    private fun showErrorAndFinish(error: SecureClipboardError) {
        if (isFinishing) return
        setResult(RESULT_CANCELED)
        toast(errorMessage(error))
        finish()
    }

    /** Map a storage error to a stable, non-sensitive user message. */
    private fun errorMessage(error: SecureClipboardError): String = when (error) {
        SecureClipboardError.Disabled -> getString(R.string.secure_clipboard_need_enable)
        SecureClipboardError.EmptyText -> getString(R.string.secure_capture_empty)
        SecureClipboardError.TooLargeText -> getString(
            R.string.secure_capture_too_large,
            SecureClipboardLimits.MAX_TEXT_LENGTH
        )

        SecureClipboardError.UnsupportedFormat -> getString(R.string.secure_capture_unsupported)
        SecureClipboardError.SensitiveRejected -> getString(R.string.secure_capture_sensitive)
        else -> getString(R.string.secure_capture_failed, error.name)
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        // Release the plaintext reference as soon as the confirmation UI is gone.
        pendingRequest = null
    }

    companion object {
        /** Window for the exported-entry-point launch throttle. */
        private const val RATE_WINDOW_MS = 2_000L

        /** Maximum confirmation dialogs opened by external apps per window. */
        private const val MAX_LAUNCHES_PER_WINDOW = 5

        private var rateWindowStart = 0L
        private var launchesInWindow = 0

        @Synchronized
        private fun allowLaunch(now: Long): Boolean {
            if (now - rateWindowStart > RATE_WINDOW_MS) {
                rateWindowStart = now
                launchesInWindow = 0
            }
            launchesInWindow++
            return launchesInWindow <= MAX_LAUNCHES_PER_WINDOW
        }
    }
}
