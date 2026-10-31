/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.secureclipboard

import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.data.clipboard.ClipboardManager
import org.fcitx.fcitx5.android.data.secureclipboard.CaptureRequest
import org.fcitx.fcitx5.android.data.secureclipboard.CaptureSource
import org.fcitx.fcitx5.android.data.secureclipboard.SaveResult
import org.fcitx.fcitx5.android.data.secureclipboard.SecureClipboardError
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import org.fcitx.fcitx5.android.ui.securecapture.SecureCaptureValidation
import org.fcitx.fcitx5.android.ui.securecapture.SecureCaptureValidator
import timber.log.Timber

/**
 * Channel A capture of the text currently selected in the focused editor.
 *
 * Triggered explicitly by the user tapping the shield button in the toolbar. The controller:
 *  - never writes the system clipboard and never changes the selection,
 *  - reads the selection exactly once through [InputConnection.getSelectedText], no batch
 *    surrounding text APIs are used,
 *  - re-checks that the same editor/input connection is still focused after the read,
 *  - stores the text through [ClipboardManager.saveSecureCapture] only (encrypted vault),
 *  - drops every plaintext reference as soon as the request has been handed over.
 *
 * [capture] must be called from the main thread, which is where input connection methods are
 * expected to run; [onResult] is always delivered on the main thread.
 */
class SelectedTextCaptureController(private val service: FcitxInputMethodService) {

    sealed interface Result {
        data class Saved(val id: Int) : Result

        data class Unavailable(val reason: Reason) : Result

        data class Failed(val error: SecureClipboardError) : Result
    }

    enum class Reason {
        /** The secure clipboard mode is disabled in the settings. */
        NotSecureMode,

        /** There is no focused editor / the editor changed while reading. */
        NoEditor,

        /** Nothing is selected in the focused editor. */
        NoSelection,

        /** The focused editor is a password-like field. */
        PasswordField,

        /** The policy denied the capture for another reason. */
        Denied,
    }

    fun capture(onResult: (Result) -> Unit) {
        if (!ClipboardManager.isSecureMode) {
            onResult(Result.Unavailable(Reason.NotSecureMode))
            return
        }
        val editorInfo = service.currentInputEditorInfo
        val inputConnection = service.currentInputConnection
        if (editorInfo == null || inputConnection == null) {
            onResult(Result.Unavailable(Reason.NoEditor))
            return
        }
        val policy = SelectedTextPolicy.evaluate(editorInfo)
        if (!policy.allowed) {
            val reason = when (policy.denial) {
                SelectedTextPolicy.DenialReason.PasswordField -> Reason.PasswordField
                else -> Reason.Denied
            }
            onResult(Result.Unavailable(reason))
            return
        }
        // The toolbar only offers the action for a known non-empty selection; check it once more
        // here so a stale button tap cannot read the whole field on clients with odd behavior.
        if (service.currentInputSelection.isEmpty()) {
            onResult(Result.Unavailable(Reason.NoSelection))
            return
        }
        // Read the selection exactly once. Any failure is reported as "cannot read selection";
        // the exception message is never surfaced (it may contain editor content).
        val raw: CharSequence? = try {
            inputConnection.getSelectedText(0)
        } catch (e: Exception) {
            null
        }
        if (raw.isNullOrEmpty()) {
            onResult(Result.Unavailable(Reason.NoSelection))
            return
        }
        when (val validation = SecureCaptureValidator.validateSelectedText(raw)) {
            is SecureCaptureValidation.Invalid -> {
                val reason = when (validation.error) {
                    SecureClipboardError.EmptyText -> Result.Unavailable(Reason.NoSelection)
                    SecureClipboardError.UnsupportedFormat -> Result.Unavailable(Reason.Denied)
                    else -> Result.Failed(validation.error)
                }
                onResult(reason)
            }

            is SecureCaptureValidation.Valid -> {
                // The editor might have changed while the selection text was read.
                if (service.currentInputConnection !== inputConnection ||
                    !isSameEditor(editorInfo, service.currentInputEditorInfo)
                ) {
                    onResult(Result.Unavailable(Reason.NoEditor))
                    return
                }
                val request = CaptureRequest(
                    text = validation.text,
                    source = CaptureSource.ImeSelection,
                    sensitive = policy.sensitive,
                    sourceApp = editorInfo.packageName?.toString(),
                )
                service.lifecycleScope.launch {
                    val saveResult = try {
                        withContext(Dispatchers.IO) {
                            ClipboardManager.saveSecureCapture(request)
                        }
                    } catch (e: Exception) {
                        // Do not log the throwable message: it may embed editor content.
                        Timber.w("Secure selected text save failed: ${e.javaClass.simpleName}")
                        SaveResult.Failure(SecureClipboardError.StorageFailed)
                    }
                    val out = when (saveResult) {
                        is SaveResult.Success -> Result.Saved(saveResult.id)
                        is SaveResult.Failure -> Result.Failed(saveResult.error)
                    }
                    withContext(Dispatchers.Main.immediate) { onResult(out) }
                }
            }
        }
    }

    private fun isSameEditor(before: EditorInfo, now: EditorInfo?): Boolean {
        if (now == null) return false
        return before.packageName?.toString() == now.packageName?.toString() &&
                before.inputType == now.inputType &&
                before.fieldId == now.fieldId
    }
}
