/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.rogue;

import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Sink for red-team results.
 *
 * <p>Every attack emits exactly one machine-readable line, both to the on-screen result list and
 * to logcat (tag {@value #TAG}):</p>
 *
 * <pre>RESULT|&lt;attack&gt;|&lt;status&gt;|&lt;detail&gt;</pre>
 *
 * <p>Status vocabulary (documented in README.md):</p>
 * <ul>
 *   <li>{@code SUCCESS} - the attack achieved its goal (a real vulnerability);</li>
 *   <li>{@code BLOCKED} - the target, the Android platform or a permission denied the attack;</li>
 *   <li>{@code INFO} - neutral observation / capability baseline, decide from the detail;</li>
 *   <li>{@code ERROR} - the attack tool itself failed (e.g. process execution problem);</li>
 *   <li>{@code DONE} - end of a run-all suite (only for the synthetic suite line).</li>
 * </ul>
 *
 * <p>Privacy: clipboard plaintext must not appear in a detail field. The single documented
 * exception is the read_clipboard attack, whose purpose is to demonstrate that a foreground
 * third-party app CAN read the clipboard; it prints a short, escaped preview and is meant to be
 * used with synthetic test strings only (see README).</p>
 */
public final class RogueLog {

    /** logcat tag required by the red-team test plan. */
    public static final String TAG = "ROGUE";

    public static final int MAX_LINES = 200;
    public static final int MAX_DETAIL_LENGTH = 400;

    /** Receives every result line; the activity posts it to the UI thread. */
    public interface Listener {
        void onResult(String line);
    }

    private static final List<String> LINES = new ArrayList<>();
    private static Listener listener;

    private RogueLog() {
    }

    public static void setListener(Listener newListener) {
        synchronized (LINES) {
            listener = newListener;
        }
    }

    /** Copy of the results emitted since process start (survives activity re-creation). */
    public static List<String> lines() {
        synchronized (LINES) {
            return new ArrayList<>(LINES);
        }
    }

    public static void clear() {
        synchronized (LINES) {
            LINES.clear();
        }
    }

    public static void emit(String attack, String status, String detail) {
        final String line = "RESULT|" + token(attack) + "|" + token(status) + "|"
                + sanitize(detail);
        Log.i(TAG, line);
        final Listener currentListener;
        synchronized (LINES) {
            LINES.add(line);
            if (LINES.size() > MAX_LINES) {
                LINES.remove(0);
            }
            currentListener = listener;
        }
        if (currentListener != null) {
            currentListener.onResult(line);
        }
    }

    /** Lower-case identifier restricted to [a-z0-9_], max 40 chars. */
    private static String token(String value) {
        if (value == null) {
            return "_";
        }
        final String lower = value.toLowerCase(Locale.US);
        final StringBuilder builder = new StringBuilder(Math.min(lower.length(), 40));
        for (int index = 0; index < lower.length() && builder.length() < 40; index++) {
            final char character = lower.charAt(index);
            final boolean allowed = (character >= 'a' && character <= 'z')
                    || (character >= '0' && character <= '9')
                    || character == '_';
            builder.append(allowed ? character : '_');
        }
        return builder.length() == 0 ? "_" : builder.toString();
    }

    /**
     * Single-line, pipe-free detail. Removes control characters (a detail must never break the
     * line-oriented wire format) and caps the length.
     */
    public static String sanitize(String detail) {
        if (detail == null) {
            return "";
        }
        final StringBuilder builder = new StringBuilder(Math.min(detail.length(), MAX_DETAIL_LENGTH));
        boolean previousSpace = false;
        for (int index = 0; index < detail.length() && builder.length() < MAX_DETAIL_LENGTH; index++) {
            char character = detail.charAt(index);
            if (character < 0x20 || character == 0x7F) {
                character = ' ';
            } else if (character == '|') {
                character = '/';
            }
            if (character == ' ') {
                if (previousSpace) {
                    continue;
                }
                previousSpace = true;
            } else {
                previousSpace = false;
            }
            builder.append(character);
        }
        String sanitized = builder.toString().trim();
        if (detail.length() > MAX_DETAIL_LENGTH) {
            sanitized = sanitized + "...";
        }
        return sanitized;
    }
}
