/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.secureclipboard

import android.app.Instrumentation
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import org.junit.Assume.assumeTrue

/**
 * Client of the standalone, test-only clipboard probe application (tools/clipboard-probe).
 *
 * The probe is deliberately not a dependency of :app: it is built and installed separately
 * (see docs/security/07-test-plan-and-instrumentation.md). Instrumented tests therefore talk to
 * it through its exported, read-only ContentProvider and skip via [assumeTrue] when the probe is
 * not installed.
 *
 * The provider only exposes timestamps, text lengths and MIME flags; clipboard plaintext is never
 * transmitted to or from the test process.
 */
internal object ClipboardProbeClient {

    const val PROBE_PACKAGE = "org.fcitx.fcitx5.android.probe"
    const val PROBE_ACTIVITY_CLASS = "org.fcitx.fcitx5.android.probe.MainActivity"
    const val AUTHORITY = "org.fcitx.fcitx5.android.probe.events"

    private const val PATH_STATUS = "status"
    private const val PATH_EVENTS = "events"

    private const val METHOD_CLEAR = "clear"
    private const val METHOD_ENSURE_LISTENING = "ensureListening"
    private const val METHOD_READ_CLIPBOARD = "readCurrentClipboard"

    private const val COL_LISTENING = "listening"
    private const val COL_FOREGROUND = "foreground"
    private const val COL_EVENT_COUNT = "event_count"
    private const val COL_LAST_SEQUENCE = "last_sequence"
    private const val COL_LAST_WALL_TIME_MS = "last_wall_time_ms"
    private const val COL_LAST_ELAPSED_MS = "last_elapsed_ms"
    private const val COL_LAST_LENGTH = "last_length"
    private const val COL_LAST_READABLE = "last_readable"
    private const val COL_LAST_HAS_TEXT = "last_has_text"
    private const val COL_LAST_PROBE_FOREGROUND = "last_probe_foreground"

    private const val COL_SEQUENCE = "sequence"
    private const val COL_WALL_TIME_MS = "wall_time_ms"
    private const val COL_ELAPSED_MS = "elapsed_ms"
    private const val COL_LENGTH = "length"
    private const val COL_READABLE = "readable"
    private const val COL_HAS_TEXT = "has_text"
    private const val COL_HAS_HTML = "has_html"
    private const val COL_HAS_URI = "has_uri"
    private const val COL_ITEM_COUNT = "item_count"
    private const val COL_PROBE_FOREGROUND = "probe_foreground"

    private const val KEY_CLEARED = "cleared"
    private const val KEY_LISTENING = "listening"
    private const val KEY_ACCESSIBLE = "accessible"
    private const val KEY_LENGTH = "length"
    private const val KEY_HAS_TEXT = "has_text"
    private const val KEY_HAS_HTML = "has_html"
    private const val KEY_HAS_URI = "has_uri"
    private const val KEY_ITEM_COUNT = "item_count"

    private val baseUri: Uri = Uri.parse("content://" + AUTHORITY)
    private val statusUri: Uri = Uri.parse("content://" + AUTHORITY + "/" + PATH_STATUS)
    private val eventsUri: Uri = Uri.parse("content://" + AUTHORITY + "/" + PATH_EVENTS)

    /** Snapshot of the probe status row. */
    data class Status(
        val listening: Boolean,
        val foreground: Boolean,
        val eventCount: Int,
        val lastSequence: Int,
        val lastWallTimeMs: Long,
        val lastElapsedRealtimeMs: Long,
        val lastTextLength: Int,
        val lastReadable: Boolean,
        val lastHasText: Boolean,
        val lastProbeForeground: Boolean,
    )

    /** One recorded clipboard change. Contains no clipboard text by design. */
    data class Event(
        val sequence: Int,
        val wallTimeMs: Long,
        val elapsedRealtimeMs: Long,
        val textLength: Int,
        val readable: Boolean,
        val hasText: Boolean,
        val hasHtml: Boolean,
        val hasUri: Boolean,
        val itemCount: Int,
        val probeForeground: Boolean,
    )

    /** Result of an explicit probe clipboard read. */
    data class ClipboardRead(
        val accessible: Boolean,
        val textLength: Int,
        val hasText: Boolean,
        val hasHtml: Boolean,
        val hasUri: Boolean,
        val itemCount: Int,
    )

    /** Skips the calling test when the probe APK is not installed on the device. */
    fun requireInstalled(context: Context) {
        val status = status(context)
        assumeTrue(
            "Clipboard probe (" + PROBE_PACKAGE + ") is not installed; " +
                "see docs/security/07-test-plan-and-instrumentation.md",
            status != null
        )
    }

    fun status(context: Context): Status? {
        val cursor = query(context, statusUri) ?: return null
        return cursor.use {
            if (!it.moveToFirst()) {
                null
            } else {
                Status(
                    listening = it.getInt(it.getColumnIndexOrThrow(COL_LISTENING)) == 1,
                    foreground = it.getInt(it.getColumnIndexOrThrow(COL_FOREGROUND)) == 1,
                    eventCount = it.getInt(it.getColumnIndexOrThrow(COL_EVENT_COUNT)),
                    lastSequence = it.getInt(it.getColumnIndexOrThrow(COL_LAST_SEQUENCE)),
                    lastWallTimeMs = it.getLong(it.getColumnIndexOrThrow(COL_LAST_WALL_TIME_MS)),
                    lastElapsedRealtimeMs = it.getLong(it.getColumnIndexOrThrow(COL_LAST_ELAPSED_MS)),
                    lastTextLength = it.getInt(it.getColumnIndexOrThrow(COL_LAST_LENGTH)),
                    lastReadable = it.getInt(it.getColumnIndexOrThrow(COL_LAST_READABLE)) == 1,
                    lastHasText = it.getInt(it.getColumnIndexOrThrow(COL_LAST_HAS_TEXT)) == 1,
                    lastProbeForeground =
                        it.getInt(it.getColumnIndexOrThrow(COL_LAST_PROBE_FOREGROUND)) == 1,
                )
            }
        }
    }

    fun events(context: Context): List<Event> {
        val cursor = query(context, eventsUri) ?: return emptyList()
        return cursor.use {
            val result = ArrayList<Event>(it.count)
            while (it.moveToNext()) {
                result.add(
                    Event(
                        sequence = it.getInt(it.getColumnIndexOrThrow(COL_SEQUENCE)),
                        wallTimeMs = it.getLong(it.getColumnIndexOrThrow(COL_WALL_TIME_MS)),
                        elapsedRealtimeMs = it.getLong(it.getColumnIndexOrThrow(COL_ELAPSED_MS)),
                        textLength = it.getInt(it.getColumnIndexOrThrow(COL_LENGTH)),
                        readable = it.getInt(it.getColumnIndexOrThrow(COL_READABLE)) == 1,
                        hasText = it.getInt(it.getColumnIndexOrThrow(COL_HAS_TEXT)) == 1,
                        hasHtml = it.getInt(it.getColumnIndexOrThrow(COL_HAS_HTML)) == 1,
                        hasUri = it.getInt(it.getColumnIndexOrThrow(COL_HAS_URI)) == 1,
                        itemCount = it.getInt(it.getColumnIndexOrThrow(COL_ITEM_COUNT)),
                        probeForeground = it.getInt(it.getColumnIndexOrThrow(COL_PROBE_FOREGROUND)) == 1,
                    )
                )
            }
            result
        }
    }

    /** Clears the probe log and returns the number of dropped events, or -1 on failure. */
    fun clear(context: Context): Int = try {
        context.contentResolver.call(baseUri, METHOD_CLEAR, null, null)?.getInt(KEY_CLEARED, -1) ?: -1
    } catch (e: Exception) {
        -1
    }

    /** Makes sure the probe registered its clipboard listener; returns false on failure. */
    fun ensureListening(context: Context): Boolean = try {
        context.contentResolver.call(baseUri, METHOD_ENSURE_LISTENING, null, null)
            ?.getBoolean(KEY_LISTENING) ?: false
    } catch (e: Exception) {
        false
    }

    /** Reads the clipboard length in the probe process; only works while the probe is foreground. */
    fun readCurrentClipboard(context: Context): ClipboardRead? = try {
        context.contentResolver.call(baseUri, METHOD_READ_CLIPBOARD, null, null)?.let { bundle ->
            ClipboardRead(
                accessible = bundle.getBoolean(KEY_ACCESSIBLE, false),
                textLength = bundle.getInt(KEY_LENGTH, -1),
                hasText = bundle.getBoolean(KEY_HAS_TEXT, false),
                hasHtml = bundle.getBoolean(KEY_HAS_HTML, false),
                hasUri = bundle.getBoolean(KEY_HAS_URI, false),
                itemCount = bundle.getInt(KEY_ITEM_COUNT, 0),
            )
        }
    } catch (e: Exception) {
        null
    }

    /**
     * Brings the probe activity to the foreground via adb shell so it may read the clipboard
     * (Android 10+ restricts clipboard access to the foreground app / default IME). The caller has
     * to poll [status] for foreground == true; this call is best effort.
     */
    fun launchProbeActivity(context: Context, instrumentation: Instrumentation) {
        val component = PROBE_PACKAGE + "/" + PROBE_ACTIVITY_CLASS
        val launchedViaShell = try {
            instrumentation.uiAutomation.executeShellCommand("am start -W -n " + component)
                .use { descriptor: ParcelFileDescriptor ->
                    ParcelFileDescriptor.AutoCloseInputStream(descriptor)
                        .use { it.readBytes() }
                }
            true
        } catch (t: Throwable) {
            false
        }
        if (!launchedViaShell) {
            // Fallback; on modern Android this may be blocked as a background activity start.
            runCatching {
                val intent = android.content.Intent().apply {
                    setClassName(PROBE_PACKAGE, PROBE_ACTIVITY_CLASS)
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            }
        }
    }

    private fun query(context: Context, uri: Uri): Cursor? = try {
        context.contentResolver.query(uri, null, null, null, null)
    } catch (e: Exception) {
        null
    }
}
