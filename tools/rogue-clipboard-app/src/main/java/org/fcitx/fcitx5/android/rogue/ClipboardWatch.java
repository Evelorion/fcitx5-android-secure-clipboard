/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.rogue;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal system-clipboard observer used by the red-team attacks.
 *
 * <p>It records clipboard-change callbacks and can read the current clip while the rogue app is
 * in the foreground (Android 10+ denies clipboard reads to background apps, which is exactly one
 * of the platform defenses this tool documents).</p>
 *
 * <p>Events keep only the timestamp, readability, text length and a SHA-256 prefix. The preview
 * text is computed on demand for the read_clipboard attack only and is never persisted.</p>
 */
public final class ClipboardWatch {

    public static final int MAX_EVENTS = 128;
    public static final int PREVIEW_LENGTH = 80;

    /** One observed clipboard change callback, without content. */
    public static final class Event {
        public final int sequence;
        public final long wallTimeMs;
        public final boolean readable;
        public final int textLength;
        public final String hash8;

        Event(int sequence, long wallTimeMs, boolean readable, int textLength, String hash8) {
            this.sequence = sequence;
            this.wallTimeMs = wallTimeMs;
            this.readable = readable;
            this.textLength = textLength;
            this.hash8 = hash8;
        }

        public String summary() {
            return "#" + sequence + " readable=" + readable + " length=" + textLength
                    + " sha256_8=" + (hash8 == null ? "-" : hash8);
        }
    }

    /** Result of an explicit clipboard read; the raw text is not retained. */
    public static final class ReadResult {
        public final boolean accessible;
        public final boolean hasText;
        public final int textLength;
        public final String hash8;
        /** Escaped, capped preview; only the read_clipboard attack is allowed to display it. */
        public final String preview;
        public final int itemCount;

        ReadResult(boolean accessible, boolean hasText, int textLength, String hash8,
                   String preview, int itemCount) {
            this.accessible = accessible;
            this.hasText = hasText;
            this.textLength = textLength;
            this.hash8 = hash8;
            this.preview = preview;
            this.itemCount = itemCount;
        }
    }

    private final ClipboardManager clipboardManager;
    private final List<Event> events = new ArrayList<>();
    private final ClipboardManager.OnPrimaryClipChangedListener listener =
            new ClipboardManager.OnPrimaryClipChangedListener() {
                @Override
                public void onPrimaryClipChanged() {
                    recordEvent();
                }
            };

    private int sequence;
    private boolean listening;

    public ClipboardWatch(Context context) {
        this.clipboardManager = (ClipboardManager) context.getApplicationContext()
                .getSystemService(Context.CLIPBOARD_SERVICE);
    }

    /** Registers the change listener. Clipboard callbacks are delivered on the main thread. */
    public void start() {
        synchronized (this) {
            if (listening || clipboardManager == null) {
                return;
            }
            try {
                clipboardManager.addPrimaryClipChangedListener(listener);
                listening = true;
            } catch (RuntimeException ignored) {
                listening = false;
            }
        }
    }

    public void stop() {
        synchronized (this) {
            if (!listening || clipboardManager == null) {
                return;
            }
            try {
                clipboardManager.removePrimaryClipChangedListener(listener);
            } catch (RuntimeException ignored) {
                // Nothing sensible to do for a test tool.
            }
            listening = false;
        }
    }

    public synchronized int callbackCount() {
        return events.size();
    }

    public synchronized int readableCount() {
        int readable = 0;
        for (int index = 0; index < events.size(); index++) {
            if (events.get(index).readable) {
                readable++;
            }
        }
        return readable;
    }

    public synchronized Event lastEvent() {
        return events.isEmpty() ? null : events.get(events.size() - 1);
    }

    public synchronized List<Event> events() {
        return new ArrayList<>(events);
    }

    private void recordEvent() {
        boolean readable = false;
        int textLength = -1;
        String hash8 = null;
        try {
            final ClipData clip = clipboardManager == null ? null : clipboardManager.getPrimaryClip();
            if (clip != null) {
                readable = true;
                if (clip.getItemCount() > 0) {
                    final ClipData.Item item = clip.getItemAt(0);
                    final CharSequence text = item == null ? null : item.getText();
                    if (text != null) {
                        final String value = text.toString();
                        textLength = value.length();
                        hash8 = sha256Prefix(value);
                    }
                }
            }
        } catch (RuntimeException ignored) {
            // Android 10+ denies clipboard reads to background apps. The callback still counts.
            readable = false;
        }
        synchronized (this) {
            sequence++;
            events.add(new Event(sequence, System.currentTimeMillis(), readable, textLength, hash8));
            if (events.size() > MAX_EVENTS) {
                events.remove(0);
            }
        }
    }

    /** Reads the current clip. On Android 10+ this only works while the app is foreground/IME. */
    public ReadResult readNow() {
        try {
            final ClipData clip = clipboardManager == null ? null : clipboardManager.getPrimaryClip();
            if (clip == null) {
                return new ReadResult(false, false, -1, null, "", 0);
            }
            final int itemCount = clip.getItemCount();
            if (itemCount > 0) {
                final ClipData.Item item = clip.getItemAt(0);
                final CharSequence text = item == null ? null : item.getText();
                if (text != null) {
                    final String value = text.toString();
                    return new ReadResult(true, true, value.length(), sha256Prefix(value),
                            preview(value, PREVIEW_LENGTH), itemCount);
                }
            }
            return new ReadResult(true, false, -1, null, "", itemCount);
        } catch (RuntimeException ignored) {
            return new ReadResult(false, false, -1, null, "", 0);
        }
    }

    /** First 8 hex characters of SHA-256(UTF-8), used to compare clips without printing them. */
    public static String sha256Prefix(String value) {
        try {
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            final byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            final StringBuilder builder = new StringBuilder(8);
            for (int index = 0; index < 4; index++) {
                builder.append(String.format(java.util.Locale.US, "%02x", hash[index] & 0xFF));
            }
            return builder.toString();
        } catch (Exception e) {
            return "err";
        }
    }

    /** Escaped, single-line, length-capped preview; control characters become '?'. */
    public static String preview(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        final StringBuilder builder = new StringBuilder(Math.min(value.length(), maxLength));
        for (int index = 0; index < value.length() && builder.length() < maxLength; index++) {
            final char character = value.charAt(index);
            if (character < 0x20 || character == 0x7F) {
                builder.append('?');
            } else if (character == '"' || character == '\\') {
                builder.append(' ');
            } else {
                builder.append(character);
            }
        }
        if (value.length() > maxLength) {
            builder.append("...");
        }
        return builder.toString();
    }
}
