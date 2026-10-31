/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.securecapture

import android.content.ClipDescription
import android.content.Intent
import android.os.Build
import org.fcitx.fcitx5.android.data.secureclipboard.CaptureSource
import org.fcitx.fcitx5.android.data.secureclipboard.SecureClipboardError

/**
 * ACTION_SEND text/plain entry point.
 *
 * Only plain text in EXTRA_TEXT is considered. Images, URIs, streams and multi-item clips are
 * rejected as unsupported; the action never writes the system clipboard and never returns the
 * captured text to the sharing app.
 */
class SecureShareReceiverActivity : BaseSecureCaptureActivity() {

    override val captureSource: CaptureSource = CaptureSource.Share

    override fun validateIntent(intent: Intent?): SecureCaptureValidation {
        if (intent == null) {
            return SecureCaptureValidation.Invalid(SecureClipboardError.UnsupportedFormat)
        }
        val rawText = intent.extras?.get(Intent.EXTRA_TEXT)
        return SecureCaptureValidator.validateShare(
            action = intent.action,
            type = intent.type,
            rawText = rawText,
            sensitive = isSensitiveClip(intent),
        )
    }

    /** Respect the platform sensitive flag carried by the shared clip, when present. */
    private fun isSensitiveClip(intent: Intent): Boolean {
        val extras = try {
            intent.clipData?.description?.extras
        } catch (e: Exception) {
            null
        } ?: return false
        val key = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ClipDescription.EXTRA_IS_SENSITIVE
        } else {
            "android.content.extra.IS_SENSITIVE"
        }
        return extras.getBoolean(key, false)
    }
}
