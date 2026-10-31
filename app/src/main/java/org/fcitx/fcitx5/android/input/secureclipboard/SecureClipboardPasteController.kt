/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.secureclipboard

import kotlinx.coroutines.CancellationException
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import timber.log.Timber

/** Result of an injected device-credential gate. */
enum class SecurePasteAuthResult {
    Success,
    Failed,
    Unavailable,
}

/** Typed outcome of a secure paste attempt; contains no clipboard plaintext. */
sealed interface SecurePasteResult {
    data object Success : SecurePasteResult

    /** No editor is focused right now. */
    data object NoInputConnection : SecurePasteResult

    /** The focused editor changed between decrypting and committing. */
    data object SessionChanged : SecurePasteResult

    /** The record vanished, could not be decrypted, or was empty. */
    data object RecordUnavailable : SecurePasteResult

    /** Device authentication was requested but no gate is wired up (or none is available). */
    data object AuthenticationUnavailable : SecurePasteResult

    /** The user failed / cancelled device authentication. */
    data object AuthenticationFailed : SecurePasteResult

    /** The input connection rejected the commit. */
    data object Failed : SecurePasteResult
}

/**
 * Private paste path for the encrypted clipboard (channel A and channel B records).
 *
 * Reads exactly one record, verifies that the input session is still the one the user started from
 * and commits the plaintext through [FcitxInputMethodService.commitText]. The system clipboard is
 * never written and the plaintext is never logged or cached.
 *
 * Device-credential verification is injected as [authenticate]:
 *  - `null` means "no gate wired up yet": when [requireAuthentication] returns true the paste
 *    fails closed with [SecurePasteResult.AuthenticationUnavailable].
 *  - The final integration maps T2's SecureUnlockGate onto this lambda:
 *    `authenticate = { SecureUnlockGate... }`.
 *
 * TODO(integration): wire T2's SecureUnlockGate in ClipboardWindow once it lands. The Lead owns
 * the final wiring; this class only depends on the abstract gate.
 */
class SecureClipboardPasteController(
    private val service: FcitxInputMethodService,
    private val readRecordText: suspend (id: Int) -> String?,
    private val requireAuthentication: () -> Boolean = { false },
    private val authenticate: (suspend () -> SecurePasteAuthResult)? = null,
) {

    /** Decrypt one record and commit it into the currently focused editor. */
    suspend fun paste(id: Int): SecurePasteResult {
        if (id < 0) return SecurePasteResult.RecordUnavailable

        var session = PasteSession.capture(service) ?: return SecurePasteResult.NoInputConnection

        if (requireAuthentication()) {
            val gate = authenticate ?: return SecurePasteResult.AuthenticationUnavailable
            when (gate()) {
                SecurePasteAuthResult.Success -> Unit
                SecurePasteAuthResult.Failed -> return SecurePasteResult.AuthenticationFailed
                SecurePasteAuthResult.Unavailable -> return SecurePasteResult.AuthenticationUnavailable
            }
            // The credential UI may have changed the focused editor; capture it again.
            session = PasteSession.capture(service) ?: return SecurePasteResult.NoInputConnection
        }

        // Decrypt exactly one record. The plaintext is referenced by this local value only and is
        // dropped as soon as it has been handed to the input connection.
        val text = try {
            readRecordText(id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w("Secure paste: decryption failed (${e.javaClass.simpleName})")
            null
        } ?: return SecurePasteResult.RecordUnavailable

        if (text.isEmpty()) return SecurePasteResult.RecordUnavailable
        if (!session.isActive(service)) return SecurePasteResult.SessionChanged

        return try {
            // Existing commit wrapper of the service: it replaces composing/selected text and
            // keeps the engine cursor tracking in sync. Never writes the system clipboard.
            service.commitText(text)
            SecurePasteResult.Success
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w("Secure paste: commit failed (${e.javaClass.simpleName})")
            SecurePasteResult.Failed
        }
    }
}
