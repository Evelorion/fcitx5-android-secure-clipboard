/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.securecapture

import android.content.Intent
import org.fcitx.fcitx5.android.data.secureclipboard.CaptureSource
import org.fcitx.fcitx5.android.data.secureclipboard.SecureClipboardError

/**
 * ACTION_PROCESS_TEXT entry point, shown as "Secure capture" in the system text selection menu.
 *
 * The caller's text is only read from EXTRA_PROCESS_TEXT; no other app data is accessed. The
 * action never writes the system clipboard and returns RESULT_OK without the captured text, so
 * the source editor keeps its original selection.
 */
class SecureCaptureActivity : BaseSecureCaptureActivity() {

    override val captureSource: CaptureSource = CaptureSource.ProcessText

    override fun validateIntent(intent: Intent?): SecureCaptureValidation {
        if (intent == null) {
            return SecureCaptureValidation.Invalid(SecureClipboardError.UnsupportedFormat)
        }
        // Keep the raw extra as Any on purpose: the validator rejects anything that is not a
        // CharSequence instead of silently coercing it.
        val rawText = intent.extras?.get(Intent.EXTRA_PROCESS_TEXT)
        val readOnly = intent.getBooleanExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, false)
        return SecureCaptureValidator.validateProcessText(
            action = intent.action,
            type = intent.type,
            rawText = rawText,
            readOnly = readOnly,
        )
    }
}
