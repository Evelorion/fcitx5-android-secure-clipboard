/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.secureclipboard

import android.view.inputmethod.InputConnection
import org.fcitx.fcitx5.android.input.FcitxInputMethodService

/**
 * Identity of the input session that was focused when the user asked to paste a secure record.
 *
 * Decrypting a record is asynchronous, so the target editor can change before the plaintext is
 * ready. Committing into a different session would leak the record into an unintended app, so the
 * identity of the [InputConnection] (plus the editor package name) is captured up front and
 * re-checked immediately before [FcitxInputMethodService.commitText].
 *
 * The session object holds no plaintext and is only referenced for the duration of one paste.
 */
class PasteSession private constructor(
    private val connection: InputConnection,
    private val editorPackage: String?,
) {

    /** Whether the editor this session belongs to is still the one receiving input. */
    fun isActive(service: FcitxInputMethodService): Boolean {
        val current = service.currentInputConnection ?: return false
        if (current !== connection) return false
        val info = service.currentInputEditorInfo ?: return true
        val currentPackage = info.packageName?.toString()
        return editorPackage == null || currentPackage == null || editorPackage == currentPackage
    }

    companion object {
        /** @return null when no input connection is focused, i.e. there is nothing to paste into. */
        fun capture(service: FcitxInputMethodService): PasteSession? {
            val connection = service.currentInputConnection ?: return null
            val editorPackage = service.currentInputEditorInfo?.packageName?.toString()
            return PasteSession(connection, editorPackage)
        }
    }
}
