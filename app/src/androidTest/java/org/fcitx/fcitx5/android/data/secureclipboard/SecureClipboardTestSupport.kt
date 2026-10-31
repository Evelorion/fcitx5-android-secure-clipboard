/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.secureclipboard

import android.content.ClipData
import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import androidx.test.platform.app.InstrumentationRegistry
import org.fcitx.fcitx5.android.data.clipboard.ClipboardManager as FcitxClipboardManager
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import java.io.File
import java.util.UUID

/**
 * Shared helpers for the secure clipboard instrumented tests.
 *
 * Everything here is test code only: the tests run inside the application process (standard
 * AndroidJUnitRunner behaviour) and never modify production sources at runtime beyond user
 * preferences that are explicitly restored by [SecureMode].
 */
internal object SecureClipboardTestSupport {

    /** Instrumentation argument that opts into changing the secure clipboard preferences. */
    const val ARG_ENABLE_SECURE_MODE = "enableSecureMode"

    fun instrumentation() = InstrumentationRegistry.getInstrumentation()

    fun targetContext(): Context = instrumentation().targetContext

    /** Unique, non-secret marker. It is never printed or logged by the tests. */
    fun uniqueMarker(prefix: String = "SECPROBE"): String =
        prefix + "-" + UUID.randomUUID().toString()

    fun sleep(ms: Long) {
        SystemClock.sleep(ms)
    }

    /** Polls [condition] until it is true or the timeout expires. */
    fun awaitUntil(
        timeoutMs: Long = 5_000L,
        intervalMs: Long = 50L,
        condition: () -> Boolean,
    ): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (runCatching(condition).getOrDefault(false)) {
                return true
            }
            SystemClock.sleep(intervalMs)
        }
        return runCatching(condition).getOrDefault(false)
    }

    /**
     * Returns the encrypted repository, initializing the vault if the process did not do it yet.
     * Skips the test when the vault cannot be initialized (direct boot / locked credential store).
     */
    fun repository(): SecureClipboardRepository {
        val context = targetContext()
        if (!SecureClipboardVault.isInitialized) {
            runCatching { SecureClipboardVault.init(context) }
        }
        assumeTrue(
            "Encrypted secure clipboard vault is not initialized " +
                "(direct boot, locked credential storage or Keystore unavailable on this device)",
            SecureClipboardVault.isInitialized
        )
        return SecureClipboardVault.repository
    }

    /** Whether the app under test is the current default input method. */
    fun isDefaultIme(context: Context): Boolean {
        val defaultIme = try {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        } catch (e: Exception) {
            null
        } ?: return false
        return defaultIme.substringBefore('/') == context.packageName
    }

    fun systemClipboard(context: Context = targetContext()): android.content.ClipboardManager =
        context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager

    /** Writes a plain text clip so the channel B listener can see it. */
    fun putSystemClipboard(text: String, context: Context = targetContext()) {
        systemClipboard(context).setPrimaryClip(ClipData.newPlainText("clipboard-probe", text))
    }

    /**
     * Scans every file below the app private data directory for the UTF-8 plaintext marker.
     *
     * This covers the encrypted Room database and its WAL/SHM files, the legacy plaintext
     * clipboard database, SharedPreferences, files/ and cache/. A hit means plaintext was
     * persisted somewhere it must never be; the failure message lists paths only, never content.
     */
    fun assertNoPlaintextInAppStorage(marker: String, context: Context = targetContext()) {
        val needle = marker.toByteArray(Charsets.UTF_8)
        val dataDir = File(context.applicationInfo.dataDir)
        val maxFileSize = 32L * 1024L * 1024L
        val hits = ArrayList<String>()
        dataDir.walkTopDown()
            .onEnter { directory ->
                directory.name != "code_cache" && directory.name != "lib" && directory.name != "dex"
            }
            .filter { file -> file.isFile && file.length() >= 1L && file.length() <= maxFileSize }
            .forEach { file ->
                val bytes = runCatching { file.readBytes() }.getOrNull() ?: return@forEach
                if (bytes.containsSubSequence(needle)) {
                    hits.add(file.absolutePath)
                }
            }
        assertTrue(
            "Plaintext marker found in app private storage files (paths only): " + hits,
            hits.isEmpty()
        )
    }

    /** Asserts that the plaintext marker does not appear in the recent logcat output. */
    fun assertNoPlaintextInLogcat(marker: String) {
        val dump = try {
            instrumentation().uiAutomation.executeShellCommand("logcat -d -t 4000")
                .use { descriptor ->
                    android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor)
                        .use { it.readBytes() }
                }
                .toString(Charsets.UTF_8)
        } catch (t: Throwable) {
            assumeTrue(
                "logcat cannot be read on this device: " + t.javaClass.simpleName,
                false
            )
            return
        }
        assertTrue(
            "Plaintext marker was found in the logcat output",
            !dump.contains(marker)
        )
    }
}

/** Whether [needle] occurs as a contiguous byte sequence inside this array. */
internal fun ByteArray.containsSubSequence(needle: ByteArray): Boolean {
    if (needle.isEmpty()) return true
    if (needle.size > size) return false
    outer@ for (start in 0..(size - needle.size)) {
        for (offset in needle.indices) {
            if (this[start + offset] != needle[offset]) continue@outer
        }
        return true
    }
    return false
}

/**
 * Opt-in control of the secure clipboard master switch.
 *
 * The tests never silently change the user's security configuration: enabling the mode migrates
 * and wipes the legacy plaintext clipboard database, which must only happen on a disposable test
 * device. Run the suite with:
 *
 * adb shell am instrument -w -e enableSecureMode true \
 *     org.fcitx.fcitx5.android.debug.test/androidx.test.runner.AndroidJUnitRunner
 */
internal object SecureMode {

    private var originalEnabled: Boolean? = null
    private var originalAutoCapture: Boolean? = null
    private var originalClearAfterSave: Boolean? = null
    private var originalCompatMode: Boolean? = null
    private var originalClipboardListening: Boolean? = null

    private fun optedIn(): Boolean =
        InstrumentationRegistry.getArguments()
            .getString(SecureClipboardTestSupport.ARG_ENABLE_SECURE_MODE) == "true"

    private fun security() = AppPrefs.getInstance().security

    /** Ensures the master switch is on; skips the test when it is off and the run did not opt in. */
    fun requireEnabled() {
        val prefs = security()
        if (originalEnabled == null) {
            originalEnabled = prefs.secureClipboardEnabled.getValue()
            originalAutoCapture = prefs.secureClipboardAutoCapture.getValue()
            originalClearAfterSave = prefs.secureClipboardClearAfterSave.getValue()
            originalCompatMode = prefs.secureClipboardCompatMode.getValue()
            originalClipboardListening =
                AppPrefs.getInstance().clipboard.clipboardListening.getValue()
        }
        if (!prefs.secureClipboardEnabled.getValue()) {
            assumeTrue(
                "Secure clipboard mode is disabled. Re-run with -e " +
                    SecureClipboardTestSupport.ARG_ENABLE_SECURE_MODE +
                    " true on a disposable emulator: enabling the mode migrates and wipes the " +
                    "legacy plaintext clipboard database.",
                optedIn()
            )
            prefs.secureClipboardEnabled.setValue(true)
            // Write through ManagedPreference AND fire the change synchronously: the facade
          // registers its listener through ManagedPreference.OnChangeListener, and the global
          // SharedPreferences listener of AppPrefs is asynchronous (posted to the main thread).
            prefs.secureClipboardEnabled.fireChange()
        }
        assumeTrue(
            "Secure clipboard mode did not become active",
            SecureClipboardTestSupport.awaitUntil(10_000L) {
                FcitxClipboardManager.isSecureMode && SecureClipboardVault.isInitialized
            }
        )
        SecureClipboardTestSupport.sleep(500L)
    }

    /** Ensures channel B (listen + clear after save) is active; requires opt-in otherwise. */
    fun requireChannelBEnabled() {
        requireEnabled()
        val prefs = security()
        val channelBReady = prefs.secureClipboardAutoCapture.getValue() &&
            prefs.secureClipboardClearAfterSave.getValue() &&
            !prefs.secureClipboardCompatMode.getValue()
        if (!channelBReady) {
            assumeTrue(
                "Secure clipboard channel B is not configured for this test. Re-run with -e " +
                    SecureClipboardTestSupport.ARG_ENABLE_SECURE_MODE + " true.",
                optedIn()
            )
            prefs.secureClipboardAutoCapture.setValue(true)
            prefs.secureClipboardClearAfterSave.setValue(true)
            prefs.secureClipboardCompatMode.setValue(false)
            prefs.secureClipboardAutoCapture.fireChange()
            SecureClipboardTestSupport.sleep(500L)
        }
        // The facade listens to the legacy clipboardListening preference (same storage key as
        // secureClipboardAutoCapture but a different ManagedPreference object): make sure the
        // listener is enabled and fire it explicitly.
        val clipboardPrefs = AppPrefs.getInstance().clipboard
        if (!clipboardPrefs.clipboardListening.getValue()) {
            assumeTrue(
                "Clipboard listening is disabled. Re-run with -e " +
                    SecureClipboardTestSupport.ARG_ENABLE_SECURE_MODE + " true.",
                optedIn()
            )
            clipboardPrefs.clipboardListening.setValue(true)
        }
        clipboardPrefs.clipboardListening.fireChange()
        // Give the preference change listeners time to (re)register the channel B listener.
        SecureClipboardTestSupport.sleep(700L)
    }

    /** Restores the preference values observed by [requireEnabled]; safe to call multiple times. */
    fun restore() {
        if (originalEnabled == null) return
        val prefs = security()
        originalCompatMode?.let { prefs.secureClipboardCompatMode.setValue(it) }
        originalClearAfterSave?.let { prefs.secureClipboardClearAfterSave.setValue(it) }
        originalAutoCapture?.let {
            prefs.secureClipboardAutoCapture.setValue(it)
            prefs.secureClipboardAutoCapture.fireChange()
        }
        originalEnabled?.let {
            prefs.secureClipboardEnabled.setValue(it)
            prefs.secureClipboardEnabled.fireChange()
        }
        originalClipboardListening?.let {
            AppPrefs.getInstance().clipboard.clipboardListening.setValue(it)
            AppPrefs.getInstance().clipboard.clipboardListening.fireChange()
        }
        originalEnabled = null
        originalAutoCapture = null
        originalClearAfterSave = null
        originalCompatMode = null
        originalClipboardListening = null
        SecureClipboardTestSupport.sleep(300L)
    }
}
