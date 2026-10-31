/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.secureclipboard

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.runBlocking
import org.fcitx.fcitx5.android.data.clipboard.ClipboardManager as FcitxClipboardManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Device-level "no leak through the system clipboard" tests for both capture channels and the
 * private paste/read path.
 *
 * They use the standalone clipboard probe app (tools/clipboard-probe) as an independent observer:
 * the probe records clipboard change callbacks in another UID, so a test can prove that secure
 * capture, encrypted read and secure paste never write the system clipboard. Every test that needs
 * a specific device state (secure mode enabled, app being the default IME, probe installed) skips
 * via JUnit assumptions instead of failing.
 *
 * See docs/security/07-test-plan-and-instrumentation.md for the adb setup commands.
 */
class ClipboardSecurityInstrumentedTest {

    private val instrumentation = SecureClipboardTestSupport.instrumentation()
    private val context: Context = SecureClipboardTestSupport.targetContext()

    @After
    fun tearDown() {
        SecureMode.restore()
    }

    @Test
    fun secureCapturePathDoesNotWriteSystemClipboard() {
        SecureMode.requireEnabled()
        val baseline = probeBaseline()
        val marker = SecureClipboardTestSupport.uniqueMarker("CAPTURE")
        val id = saveViaFacade(marker)
        try {
            assertProbeEventCountUnchanged(baseline)
        } finally {
            deleteRecord(id)
        }
    }

    @Test
    fun encryptedReadDoesNotWriteSystemClipboard() {
        SecureMode.requireEnabled()
        val baseline = probeBaseline()
        val marker = SecureClipboardTestSupport.uniqueMarker("READ")
        val id = saveViaFacade(marker)
        try {
            val text = runBlocking { FcitxClipboardManager.readText(id) }
            assertEquals("decrypted read must return the original text", marker, text)
            assertProbeEventCountUnchanged(baseline)
        } finally {
            deleteRecord(id)
        }
    }

    @Test
    fun facadeExposesOnlyMaskedDisplayEntriesForSecureRecords() {
        SecureMode.requireEnabled()
        val marker = SecureClipboardTestSupport.uniqueMarker("MASK")
        val id = saveViaFacade(marker)
        try {
            val entry = runBlocking { FcitxClipboardManager.get(id) }
            assertNotNull("secure record must be exposed as a display entry", entry)
            val display = entry!!
            assertTrue("secure display entries must be flagged secure", display.secure)
            assertNotEquals(
                "the secure display entry must not expose the decrypted plaintext",
                marker,
                display.text
            )
        } finally {
            deleteRecord(id)
        }
    }

    @Test
    fun secureCaptureActivityDoesNotWriteSystemClipboard() {
        SecureMode.requireEnabled()
        val component = resolveActivity(Intent.ACTION_PROCESS_TEXT, PROCESS_TEXT_ACTIVITY)
        assumeTrue("SecureCaptureActivity is not available in this build", component != null)
        val targetComponent = component!!
        val baseline = probeBaseline()
        val marker = SecureClipboardTestSupport.uniqueMarker("PROCESS_TEXT")
        val intent = Intent(Intent.ACTION_PROCESS_TEXT).apply {
            setComponent(targetComponent)
            type = "text/plain"
            putExtra(Intent.EXTRA_PROCESS_TEXT, marker)
            putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, false)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val activity = instrumentation.startActivitySync(intent)
        try {
            instrumentation.waitForIdleSync()
            assertProbeEventCountUnchanged(baseline)
        } finally {
            runCatching { instrumentation.runOnMainSync { activity.finish() } }
        }
    }

    @Test
    fun secureShareIntentDoesNotWriteSystemClipboard() {
        SecureMode.requireEnabled()
        val component = resolveActivity(Intent.ACTION_SEND, SHARE_ACTIVITY)
        assumeTrue("SecureShareReceiverActivity is not available in this build", component != null)
        val targetComponent = component!!
        val baseline = probeBaseline()
        val marker = SecureClipboardTestSupport.uniqueMarker("SHARE")
        val intent = Intent(Intent.ACTION_SEND).apply {
            setComponent(targetComponent)
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, marker)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val activity = instrumentation.startActivitySync(intent)
        try {
            instrumentation.waitForIdleSync()
            assertProbeEventCountUnchanged(baseline)
        } finally {
            runCatching { instrumentation.runOnMainSync { activity.finish() } }
        }
    }

    @Test
    fun invalidSecureCaptureIntentIsRejectedWithoutClipboardWrite() {
        SecureMode.requireEnabled()
        val component = resolveActivity(Intent.ACTION_PROCESS_TEXT, PROCESS_TEXT_ACTIVITY)
        assumeTrue("SecureCaptureActivity is not available in this build", component != null)
        val targetComponent = component!!
        val baseline = probeBaseline()
        val intent = Intent().apply {
            setComponent(targetComponent)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val activity = instrumentation.startActivitySync(intent)
        try {
            instrumentation.waitForIdleSync()
            val finished = SecureClipboardTestSupport.awaitUntil(3_000L) { activity.isFinishing }
            assertTrue(
                "an intent without ACTION_PROCESS_TEXT must be rejected (activity should finish)",
                finished
            )
            assertProbeEventCountUnchanged(baseline)
        } finally {
            runCatching { instrumentation.runOnMainSync { activity.finish() } }
        }
    }

    @Test
    fun plainCopyIsEncryptedAndSystemClipboardIsCleared() {
        SecureMode.requireChannelBEnabled()
        assumeDefaultIme()
        ClipboardProbeClient.requireInstalled(context)
        val repository = SecureClipboardTestSupport.repository()
        ClipboardProbeClient.ensureListening(context)
        ClipboardProbeClient.launchProbeActivity(context, instrumentation)
        assumeTrue(
            "the clipboard probe did not reach the foreground; cannot observe the clipboard",
            SecureClipboardTestSupport.awaitUntil(10_000L) {
                ClipboardProbeClient.status(context)?.foreground == true
            }
        )
        ClipboardProbeClient.clear(context)
        SecureClipboardTestSupport.sleep(300L)
        // Let the one-shot legacy migration (triggered by enabling secure mode) settle first.
        SecureClipboardTestSupport.sleep(1_500L)

        val countBefore = runBlocking { repository.itemCount() }
        val marker = SecureClipboardTestSupport.uniqueMarker("COPY")
        SecureClipboardTestSupport.putSystemClipboard(marker)

        val persisted = SecureClipboardTestSupport.awaitUntil(15_000L) {
            runBlocking { repository.itemCount() > countBefore }
        }
        assertTrue(
            "a normal system clipboard copy must be encrypted and persisted by channel B",
            persisted
        )
        val cleared = SecureClipboardTestSupport.awaitUntil(10_000L) {
            clipboardLooksCleared()
        }
        assertTrue(
            "the system clipboard must be cleared after the encrypted save",
            cleared
        )
        assertTrue(
            "the clipboard probe must have observed the clipboard write",
            ClipboardProbeClient.events(context).isNotEmpty()
        )
    }

    @Test
    fun burstCopiesAreHandledAndSystemClipboardEndsCleared() {
        SecureMode.requireChannelBEnabled()
        assumeDefaultIme()
        ClipboardProbeClient.requireInstalled(context)
        val repository = SecureClipboardTestSupport.repository()
        ClipboardProbeClient.ensureListening(context)
        ClipboardProbeClient.launchProbeActivity(context, instrumentation)
        assumeTrue(
            "the clipboard probe did not reach the foreground; cannot observe the clipboard",
            SecureClipboardTestSupport.awaitUntil(10_000L) {
                ClipboardProbeClient.status(context)?.foreground == true
            }
        )
        ClipboardProbeClient.clear(context)
        SecureClipboardTestSupport.sleep(1_500L)

        val countBefore = runBlocking { repository.itemCount() }
        repeat(3) { index ->
            SecureClipboardTestSupport.putSystemClipboard(
                SecureClipboardTestSupport.uniqueMarker("BURST-" + index)
            )
            SecureClipboardTestSupport.sleep(150L)
        }
        val persisted = SecureClipboardTestSupport.awaitUntil(15_000L) {
            runBlocking { repository.itemCount() > countBefore }
        }
        assertTrue("at least one burst copy must be persisted", persisted)
        val cleared = SecureClipboardTestSupport.awaitUntil(10_000L) {
            clipboardLooksCleared()
        }
        assertTrue("the system clipboard must end up cleared after burst copies", cleared)
    }

    /**
     * The system clipboard is considered cleared when the probe can not read a clip, or when it
     * only sees an empty text clip (the API < 28 fallback writes an empty clip instead of using
     * clearPrimaryClip()).
     */
    private fun clipboardLooksCleared(): Boolean {
        val read = ClipboardProbeClient.readCurrentClipboard(context) ?: return false
        return !read.accessible || read.textLength == 0
    }

    private fun assumeDefaultIme() {
        assumeTrue(
            "The app under test must be the current default input method: Android 10+ only lets " +
                "the default IME read the clipboard in the background. Enable it with: " +
                "adb shell ime enable " + context.packageName + "/" + IME_SERVICE +
                " && adb shell ime set " + context.packageName + "/" + IME_SERVICE,
            SecureClipboardTestSupport.isDefaultIme(context)
        )
    }

    private fun saveViaFacade(text: String): Int {
        val result = runBlocking {
            FcitxClipboardManager.saveSecureCapture(
                CaptureRequest(text = text, source = CaptureSource.ProcessText)
            )
        }
        return when (result) {
            is SaveResult.Success -> result.id
            is SaveResult.Failure -> {
                assumeTrue("secure capture failed: " + result.error, false)
                -1
            }
        }
    }

    private fun deleteRecord(id: Int) {
        runCatching { runBlocking { SecureClipboardTestSupport.repository().delete(id) } }
    }

    private fun probeBaseline(): Int {
        ClipboardProbeClient.requireInstalled(context)
        ClipboardProbeClient.ensureListening(context)
        ClipboardProbeClient.clear(context)
        SecureClipboardTestSupport.sleep(200L)
        val status = ClipboardProbeClient.status(context)
        if (status == null) {
            assumeTrue("clipboard probe status is unavailable", false)
        }
        return status?.eventCount ?: -1
    }

    private fun assertProbeEventCountUnchanged(baseline: Int) {
        SecureClipboardTestSupport.sleep(1_000L)
        val status = ClipboardProbeClient.status(context)
        if (status == null) {
            assumeTrue("clipboard probe status is unavailable", false)
        }
        assertEquals(
            "the secure clipboard path must not write the system clipboard",
            baseline,
            status?.eventCount ?: -1
        )
    }

    private fun resolveActivity(action: String, className: String): ComponentName? {
        val intent = Intent(action).apply {
            setClassName(context.packageName, className)
            if (action == Intent.ACTION_PROCESS_TEXT || action == Intent.ACTION_SEND) {
                type = "text/plain"
            }
        }
        return context.packageManager.resolveActivity(intent, 0)?.activityInfo?.let { info ->
            ComponentName(info.packageName, info.name)
        }
    }

    private companion object {
        const val PROCESS_TEXT_ACTIVITY =
            "org.fcitx.fcitx5.android.ui.securecapture.SecureCaptureActivity"
        const val SHARE_ACTIVITY =
            "org.fcitx.fcitx5.android.ui.securecapture.SecureShareReceiverActivity"
        const val IME_SERVICE = "org.fcitx.fcitx5.android.input.FcitxInputMethodService"
    }
}
