/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.securecapture

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.suspendCancellableCoroutine
import timber.log.Timber
import kotlin.coroutines.resume

/**
 * Optional device credential confirmation used before decrypting a secure record for paste.
 *
 * The gate is the only supported way for non-activity callers (e.g. the input method service)
 * to trigger the system device credential prompt: it starts [SecureUnlockActivity], which in
 * turn starts the platform credential confirmation activity and reports the result back through
 * [notifyResult].
 *
 * Contract:
 *  - [isAvailable] must be checked before promising the feature to the user.
 *  - [requestUnlock] always answers through the callback, on the main thread. When device
 *    credentials are unavailable the callback receives false immediately.
 *  - The gate holds at most one pending request. A second request while one is pending is
 *    failed immediately (with false) instead of replacing the pending request.
 *  - No clipboard content or credential material ever passes through the gate.
 */
object SecureUnlockGate {

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    private val lock = Any()

    /** Callback of the request currently waiting for [SecureUnlockActivity]. */
    private var pendingCallback: ((Boolean) -> Unit)? = null

    /** Watchdog that fails a request whose credential activity never reports back. */
    private var timeoutRunnable: Runnable? = null

    /** Whether there is a request waiting for the device credential result. */
    val isPending: Boolean
        get() = synchronized(lock) { pendingCallback != null }

    /**
     * Whether device credential confirmation can be used on this device.
     *
     * Requires API 23+ and a secure lock screen (PIN/pattern/password). When this returns false,
     * callers should surface a non-sensitive "authentication unavailable" message.
     */
    fun isAvailable(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false
        val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
            ?: return false
        return try {
            @Suppress("DEPRECATION")
            keyguard.isDeviceSecure
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Ask the user to confirm the device credential.
     *
     * Can be called from any context; activity context are used directly, other context are
     * launched with [Intent.FLAG_ACTIVITY_NEW_TASK]. [onResult] is invoked on the main thread
     * with true only after a successful confirmation.
     */
    fun requestUnlock(context: Context, onResult: (Boolean) -> Unit) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { requestUnlock(context, onResult) }
            return
        }
        if (!isAvailable(context)) {
            onResult(false)
            return
        }
        val alreadyPending = synchronized(lock) {
            if (pendingCallback != null) {
                true
            } else {
                pendingCallback = onResult
                false
            }
        }
        if (alreadyPending) {
            // Never let two credential prompts race for one result: fail the new request.
            onResult(false)
            return
        }
        scheduleTimeout()
        val intent = Intent(context, SecureUnlockActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
            if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            // Activity start may be rejected by background start restrictions; fail closed.
            Timber.w("Secure unlock activity could not be started: ${e.javaClass.simpleName}")
            deliver(false)
        }
    }

    /**
     * Coroutine friendly variant. The caller should wrap it in a timeout: if the activity is
     * destroyed without a result the gate delivers false, but process death cannot be observed.
     */
    suspend fun requestUnlock(context: Context): Boolean =
        suspendCancellableCoroutine { continuation ->
            // If the caller times out (withTimeout) the pending request must not linger and
            // block the next attempt; the credential activity result then becomes a no-op.
            continuation.invokeOnCancellation { cancelPending() }
            requestUnlock(context) { resultOk ->
                if (continuation.isActive) continuation.resume(resultOk)
            }
        }

    /** Fail the pending request (if any) with false, e.g. when the paste session ends. */
    fun cancelPending() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { cancelPending() }
            return
        }
        deliver(false)
    }

    /**
     * Called by [SecureUnlockActivity] with the credential confirmation result. A result is
     * delivered at most once per request; calls without a pending request are ignored.
     */
    fun notifyResult(resultOk: Boolean) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { notifyResult(resultOk) }
            return
        }
        deliver(resultOk)
    }

    private fun scheduleTimeout() {
        val runnable = Runnable {
            // Background activity start restrictions or a vanished credential UI can leave the
            // request unanswered; fail closed instead of keeping the callback pending forever.
            deliver(false)
        }
        timeoutRunnable = runnable
        mainHandler.postDelayed(runnable, REQUEST_TIMEOUT_MS)
    }

    private fun clearTimeout() {
        timeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        timeoutRunnable = null
    }

    /** Upper bound for a full device credential confirmation round trip. */
    private const val REQUEST_TIMEOUT_MS = 120_000L

    private fun deliver(resultOk: Boolean) {
        clearTimeout()
        val callback = synchronized(lock) {
            val current = pendingCallback
            pendingCallback = null
            current
        }
        callback?.invoke(resultOk)
    }
}
