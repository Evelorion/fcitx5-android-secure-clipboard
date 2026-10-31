/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.secureclipboard

import android.content.Context
import org.fcitx.fcitx5.android.data.prefs.AppPrefs

/**
 * Process-wide access point for the encrypted clipboard storage.
 *
 * The encrypted database lives in the credential-protected storage of the app and is therefore
 * only initialized after the user has unlocked the device. In direct-boot mode the vault stays
 * uninitialized on purpose: we must never try to read user secrets before first unlock.
 */
object SecureClipboardVault {

    @Volatile
    private var repositoryInstance: SecureClipboardRepository? = null

    @Volatile
    private var initialized = false

    val isInitialized: Boolean get() = initialized

    val repository: SecureClipboardRepository
        get() = repositoryInstance
            ?: throw IllegalStateException("SecureClipboardVault is not initialized")

    fun init(context: Context) {
        if (initialized) return
        repositoryInstance = SecureClipboardRepositoryImpl(
            context.applicationContext,
            // Read the user setting lazily at save time so changing it does not need a restart.
            historyLimitProvider = {
                AppPrefs.getInstance().security.secureClipboardHistoryLimit.getValue()
            }
        )
        initialized = true
    }

    fun reset() {
        repositoryInstance = null
        initialized = false
    }
}
