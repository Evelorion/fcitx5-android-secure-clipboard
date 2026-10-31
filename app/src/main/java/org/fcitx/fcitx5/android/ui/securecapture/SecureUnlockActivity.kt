/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.securecapture

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import org.fcitx.fcitx5.android.R
import timber.log.Timber

/**
 * Transparent launcher for the platform device credential confirmation.
 *
 * The platform credential UI can only be started with Activity.startActivityForResult, and the
 * input method service is not an activity. This activity bridges the two: [SecureUnlockGate]
 * starts it, it starts the credential confirmation and forwards the result back through
 * [SecureUnlockGate.notifyResult] before finishing.
 *
 * It never shows any content of its own and holds no clipboard data. The activity is fail-closed:
 * unavailable credentials, cancellation, recreation or any launch failure deliver false.
 */
class SecureUnlockActivity : Activity() {

    private var delivered = false

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The credential prompt covers sensitive UI; keep screenshots out of this window too.
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if (!SecureUnlockGate.isAvailable(this)) {
            deliver(false)
            return
        }
        if (savedInstanceState != null) {
            // Never silently re-prompt after a configuration change; the caller can ask again.
            deliver(false)
            return
        }
        val keyguard = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        val confirmIntent = keyguard?.createConfirmDeviceCredentialIntent(
            getString(R.string.auth_title),
            null
        )
        if (confirmIntent == null) {
            // Device credential confirmation not usable (no secure lock screen on some devices).
            deliver(false)
            return
        }
        try {
            startActivityForResult(confirmIntent, REQUEST_CONFIRM_DEVICE_CREDENTIAL)
        } catch (e: Exception) {
            Timber.w("Device credential confirmation could not be started: ${e.javaClass.simpleName}")
            deliver(false)
        }
    }

    @Deprecated("Deprecated in Android API 30, still required for the platform credential intent")
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQUEST_CONFIRM_DEVICE_CREDENTIAL) {
            deliver(resultCode == RESULT_OK)
            return
        }
        super.onActivityResult(requestCode, resultCode, data)
    }

    @Deprecated("Deprecated in Android API 33")
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        deliver(false)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // A second request while the confirmation is showing must not stack dialogs; the gate
        // fails the previous request when a new one arrives, so nothing to do here.
    }

    override fun onDestroy() {
        super.onDestroy()
        // Fail closed if the activity is killed before a result arrives.
        if (!delivered) SecureUnlockGate.notifyResult(false)
    }

    private fun deliver(resultOk: Boolean) {
        if (delivered) return
        delivered = true
        SecureUnlockGate.notifyResult(resultOk)
        finish()
    }

    private companion object {
        const val REQUEST_CONFIRM_DEVICE_CREDENTIAL = 0x5EC0
    }
}
