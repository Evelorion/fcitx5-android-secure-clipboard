/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.probe;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;

import java.util.List;

/**
 * Read-only ContentProvider that exposes the probe telemetry to the :app instrumented tests and to
 * {@code adb shell content query/call}.
 *
 * <p>It never returns clipboard text (only timestamps, lengths and MIME flags), so it is safe to
 * leave it exported without a permission. It is a developer tool and only installed on test
 * devices/emulators.</p>
 *
 * <pre>
 * content://org.fcitx.fcitx5.android.probe.events/status
 * content://org.fcitx.fcitx5.android.probe.events/events
 * method clear              -> {cleared=int}
 * method ensureListening    -> {listening=boolean}
 * method readCurrentClipboard -> {accessible,length,hasText,hasHtml,hasUri,itemCount}
 * </pre>
 */
public final class ProbeProvider extends ContentProvider {

    public static final String AUTHORITY = "org.fcitx.fcitx5.android.probe.events";

    public static final String PATH_STATUS = "status";
    public static final String PATH_EVENTS = "events";

    public static final String METHOD_CLEAR = "clear";
    public static final String METHOD_READ_CLIPBOARD = "readCurrentClipboard";
    public static final String METHOD_ENSURE_LISTENING = "ensureListening";
    /**
     * Test-only helper: writes the supplied plaintext into the system clipboard.
     *
     * <p>This is the only method that accepts clipboard content and it is used exclusively by
     * manual/adb tests to drive the app under test; the provider still never *returns* clipboard
     * text. It exists because no standard adb command can set the clipboard on modern Android.</p>
     */
    public static final String METHOD_SET_CLIPBOARD = "setClipboard";

    // status columns
    public static final String COL_LISTENING = "listening";
    public static final String COL_FOREGROUND = "foreground";
    public static final String COL_EVENT_COUNT = "event_count";
    public static final String COL_LAST_SEQUENCE = "last_sequence";
    public static final String COL_LAST_WALL_TIME_MS = "last_wall_time_ms";
    public static final String COL_LAST_ELAPSED_MS = "last_elapsed_ms";
    public static final String COL_LAST_LENGTH = "last_length";
    public static final String COL_LAST_READABLE = "last_readable";
    public static final String COL_LAST_HAS_TEXT = "last_has_text";
    public static final String COL_LAST_PROBE_FOREGROUND = "last_probe_foreground";

    // event columns
    public static final String COL_SEQUENCE = "sequence";
    public static final String COL_WALL_TIME_MS = "wall_time_ms";
    public static final String COL_ELAPSED_MS = "elapsed_ms";
    public static final String COL_LENGTH = "length";
    public static final String COL_READABLE = "readable";
    public static final String COL_HAS_TEXT = "has_text";
    public static final String COL_HAS_HTML = "has_html";
    public static final String COL_HAS_URI = "has_uri";
    public static final String COL_ITEM_COUNT = "item_count";
    public static final String COL_PROBE_FOREGROUND = "probe_foreground";

    // call() result keys
    public static final String KEY_CLEARED = "cleared";
    public static final String KEY_LISTENING = "listening";
    public static final String KEY_ACCESSIBLE = "accessible";
    public static final String KEY_LENGTH = "length";
    public static final String KEY_HAS_TEXT = "has_text";
    public static final String KEY_HAS_HTML = "has_html";
    public static final String KEY_HAS_URI = "has_uri";
    public static final String KEY_ITEM_COUNT = "item_count";

    private static final String[] STATUS_COLUMNS = new String[]{
            COL_LISTENING,
            COL_FOREGROUND,
            COL_EVENT_COUNT,
            COL_LAST_SEQUENCE,
            COL_LAST_WALL_TIME_MS,
            COL_LAST_ELAPSED_MS,
            COL_LAST_LENGTH,
            COL_LAST_READABLE,
            COL_LAST_HAS_TEXT,
            COL_LAST_PROBE_FOREGROUND,
    };

    private static final String[] EVENT_COLUMNS = new String[]{
            COL_SEQUENCE,
            COL_WALL_TIME_MS,
            COL_ELAPSED_MS,
            COL_LENGTH,
            COL_READABLE,
            COL_HAS_TEXT,
            COL_HAS_HTML,
            COL_HAS_URI,
            COL_ITEM_COUNT,
            COL_PROBE_FOREGROUND,
    };

    @Override
    public boolean onCreate() {
        final Context context = getContext();
        if (context == null) {
            return false;
        }
        final ProbeLog log = ProbeLog.get();
        log.init(context);
        // Registration is best effort: the provider must stay queryable even if the platform
        // refuses the listener (it does not, but the probe should degrade instead of crashing).
        log.ensureListening();
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
                        String sortOrder) {
        final ProbeLog log = ProbeLog.get();
        final String path = uri.getLastPathSegment();

        if (PATH_EVENTS.equals(path)) {
            final MatrixCursor cursor = new MatrixCursor(EVENT_COLUMNS);
            final List<ProbeLog.Event> events = log.events();
            for (int index = 0; index < events.size(); index++) {
                final ProbeLog.Event event = events.get(index);
                cursor.addRow(new Object[]{
                        event.sequence,
                        event.wallTimeMs,
                        event.elapsedRealtimeMs,
                        event.textLength,
                        event.readable ? 1 : 0,
                        event.hasText ? 1 : 0,
                        event.hasHtml ? 1 : 0,
                        event.hasUri ? 1 : 0,
                        event.itemCount,
                        event.probeForeground ? 1 : 0,
                });
            }
            return cursor;
        }

        if (!PATH_STATUS.equals(path)) {
            return null;
        }

        final ProbeLog.Event last = log.lastEvent();
        final MatrixCursor cursor = new MatrixCursor(STATUS_COLUMNS);
        cursor.addRow(new Object[]{
                log.isListening() ? 1 : 0,
                log.isForeground() ? 1 : 0,
                log.eventCount(),
                log.lastSequence(),
                last == null ? -1L : last.wallTimeMs,
                last == null ? -1L : last.elapsedRealtimeMs,
                last == null ? -1 : last.textLength,
                last == null ? 0 : (last.readable ? 1 : 0),
                last == null ? 0 : (last.hasText ? 1 : 0),
                last == null ? 0 : (last.probeForeground ? 1 : 0),
        });
        return cursor;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        final Bundle result = new Bundle();
        final ProbeLog log = ProbeLog.get();

        if (METHOD_CLEAR.equals(method)) {
            result.putInt(KEY_CLEARED, log.clear());
            return result;
        }
        if (METHOD_ENSURE_LISTENING.equals(method)) {
            result.putBoolean(KEY_LISTENING, log.ensureListening());
            return result;
        }
        if (METHOD_SET_CLIPBOARD.equals(method)) {
            final String text = arg == null ? "" : arg;
            boolean set = false;
            try {
                final android.content.ClipboardManager clipboardManager =
                        (android.content.ClipboardManager) getContext()
                                .getSystemService(Context.CLIPBOARD_SERVICE);
                if (clipboardManager != null) {
                    clipboardManager.setPrimaryClip(
                            android.content.ClipData.newPlainText("probe", text));
                    set = true;
                }
            } catch (RuntimeException ignored) {
                set = false;
            }
            result.putBoolean("set", set);
            result.putInt(KEY_LENGTH, text.length());
            return result;
        }
        if (METHOD_READ_CLIPBOARD.equals(method)) {
            final ProbeLog.ClipboardRead read = log.readCurrentClipboard();
            result.putBoolean(KEY_ACCESSIBLE, read.accessible);
            result.putInt(KEY_LENGTH, read.textLength);
            result.putBoolean(KEY_HAS_TEXT, read.hasText);
            result.putBoolean(KEY_HAS_HTML, read.hasHtml);
            result.putBoolean(KEY_HAS_URI, read.hasUri);
            result.putInt(KEY_ITEM_COUNT, read.itemCount);
            return result;
        }
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("Clipboard probe telemetry is read-only");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("Clipboard probe telemetry is read-only");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("Clipboard probe telemetry is read-only");
    }
}
