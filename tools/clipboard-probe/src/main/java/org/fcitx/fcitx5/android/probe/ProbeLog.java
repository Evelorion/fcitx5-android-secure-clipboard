/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.probe;

import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Context;

import java.util.ArrayList;
import java.util.List;

/**
 * Process-wide, in-memory log of system clipboard change events.
 *
 * <p>Privacy: the probe never stores, persists or logs clipboard plaintext. An event keeps only
 * timestamps, the text length and MIME flags; the {@link CharSequence} itself is dropped as soon
 * as its length has been measured. Therefore neither the probe UI, nor the exported read-only
 * provider, nor adb can ever obtain clipboard content from this process.</p>
 *
 * <p>Android 10+ restricts clipboard reads for apps that are not in the foreground and are not the
 * default input method: {@link #readCurrentClipboard()} then reports {@code accessible == false},
 * and an event recorded while the probe is in the background usually has
 * {@code readable == false} and {@code textLength == -1}. Clipboard change callbacks are still
 * delivered to registered listeners, which is what the "system clipboard must remain untouched"
 * checks rely on.</p>
 */
public final class ProbeLog {

    /** Upper bound of retained events; older events are dropped. */
    public static final int MAX_EVENTS = 256;

    private static final ProbeLog INSTANCE = new ProbeLog();

    private final ClipboardManager.OnPrimaryClipChangedListener listener =
            new ClipboardManager.OnPrimaryClipChangedListener() {
                @Override
                public void onPrimaryClipChanged() {
                    recordEvent();
                }
            };

    private final List<Event> events = new ArrayList<>();

    private ClipboardManager clipboardManager;
    private boolean listening;
    private boolean foreground;
    private int sequence;

    private ProbeLog() {
    }

    public static ProbeLog get() {
        return INSTANCE;
    }

    /** One clipboard change. Contains no clipboard content by design. */
    public static final class Event {

        /** Monotonic event number starting at 1 after every {@link #clear()}. */
        public final int sequence;
        public final long wallTimeMs;
        public final long elapsedRealtimeMs;
        /** Text length in UTF-16 code units, or -1 when the content was not readable. */
        public final int textLength;
        /** Whether the probe could read the clip when the event was recorded. */
        public final boolean readable;
        public final boolean hasText;
        public final boolean hasHtml;
        public final boolean hasUri;
        public final int itemCount;
        /** Whether the probe activity was in the foreground when the event was recorded. */
        public final boolean probeForeground;

        Event(int sequence, long wallTimeMs, long elapsedRealtimeMs, int textLength,
              boolean readable, boolean hasText, boolean hasHtml, boolean hasUri,
              int itemCount, boolean probeForeground) {
            this.sequence = sequence;
            this.wallTimeMs = wallTimeMs;
            this.elapsedRealtimeMs = elapsedRealtimeMs;
            this.textLength = textLength;
            this.readable = readable;
            this.hasText = hasText;
            this.hasHtml = hasHtml;
            this.hasUri = hasUri;
            this.itemCount = itemCount;
            this.probeForeground = probeForeground;
        }
    }

    /** Result of an explicit clipboard read; the text itself is never returned. */
    public static final class ClipboardRead {

        /** False when the clipboard is empty or the OS denies access (background app). */
        public final boolean accessible;
        public final int textLength;
        public final boolean hasText;
        public final boolean hasHtml;
        public final boolean hasUri;
        public final int itemCount;

        ClipboardRead(boolean accessible, int textLength, boolean hasText, boolean hasHtml,
                      boolean hasUri, int itemCount) {
            this.accessible = accessible;
            this.textLength = textLength;
            this.hasText = hasText;
            this.hasHtml = hasHtml;
            this.hasUri = hasUri;
            this.itemCount = itemCount;
        }
    }

    /** Binds the log to the application clipboard service. Idempotent. */
    public synchronized void init(Context context) {
        if (clipboardManager != null) {
            return;
        }
        clipboardManager = (ClipboardManager) context.getApplicationContext()
                .getSystemService(Context.CLIPBOARD_SERVICE);
    }

    /** Registers the clipboard listener exactly once. Safe to call from provider/activity creation. */
    public synchronized boolean ensureListening() {
        if (listening) {
            return true;
        }
        if (clipboardManager == null) {
            return false;
        }
        try {
            clipboardManager.addPrimaryClipChangedListener(listener);
            listening = true;
        } catch (RuntimeException ignored) {
            listening = false;
        }
        return listening;
    }

    public synchronized boolean isListening() {
        return listening;
    }

    public synchronized void setForeground(boolean value) {
        foreground = value;
    }

    public synchronized boolean isForeground() {
        return foreground;
    }

    public synchronized int eventCount() {
        return events.size();
    }

    public synchronized int lastSequence() {
        return sequence;
    }

    /** Copy of the retained events, oldest first. */
    public synchronized List<Event> events() {
        return new ArrayList<>(events);
    }

    public synchronized Event lastEvent() {
        return events.isEmpty() ? null : events.get(events.size() - 1);
    }

    /** Drops all events and resets the sequence counter. Returns the number of dropped events. */
    public synchronized int clear() {
        final int cleared = events.size();
        events.clear();
        sequence = 0;
        return cleared;
    }

    private synchronized void recordEvent() {
        final long wall = System.currentTimeMillis();
        final long elapsed = android.os.SystemClock.elapsedRealtime();
        int textLength = -1;
        boolean readable = false;
        boolean hasText = false;
        boolean hasHtml = false;
        boolean hasUri = false;
        int itemCount = 0;
        try {
            final ClipData clip = clipboardManager == null ? null : clipboardManager.getPrimaryClip();
            if (clip != null) {
                readable = true;
                itemCount = clip.getItemCount();
                final ClipDescription description = clip.getDescription();
                if (description != null) {
                    hasText = description.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN);
                    hasHtml = description.hasMimeType(ClipDescription.MIMETYPE_TEXT_HTML);
                    hasUri = description.hasMimeType(ClipDescription.MIMETYPE_TEXT_URILIST);
                }
                if (itemCount > 0) {
                    final ClipData.Item item = clip.getItemAt(0);
                    final CharSequence text = item == null ? null : item.getText();
                    if (text != null) {
                        textLength = text.length();
                        hasText = true;
                    } else if (item != null && item.getUri() != null) {
                        textLength = item.getUri().toString().length();
                        hasUri = true;
                    }
                }
            }
        } catch (RuntimeException ignored) {
            // Android 10+ denies clipboard reads to background apps. The event itself is still
            // recorded so tests can detect that the clipboard changed at all.
            readable = false;
        }
        sequence++;
        events.add(new Event(sequence, wall, elapsed, textLength, readable, hasText, hasHtml,
                hasUri, itemCount, foreground));
        if (events.size() > MAX_EVENTS) {
            events.remove(0);
        }
    }

    /**
     * Reads the current system clipboard only to measure it.
     *
     * <p>The returned object deliberately carries no text. A caller must be in the foreground (or
     * the default input method) on Android 10+ for this call to be able to see the clip at all.</p>
     */
    public synchronized ClipboardRead readCurrentClipboard() {
        try {
            final ClipData clip = clipboardManager == null ? null : clipboardManager.getPrimaryClip();
            if (clip == null) {
                return new ClipboardRead(false, -1, false, false, false, 0);
            }
            int textLength = -1;
            boolean hasText = false;
            boolean hasHtml = false;
            boolean hasUri = false;
            final ClipDescription description = clip.getDescription();
            if (description != null) {
                hasText = description.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN);
                hasHtml = description.hasMimeType(ClipDescription.MIMETYPE_TEXT_HTML);
                hasUri = description.hasMimeType(ClipDescription.MIMETYPE_TEXT_URILIST);
            }
            final int itemCount = clip.getItemCount();
            if (itemCount > 0) {
                final ClipData.Item item = clip.getItemAt(0);
                final CharSequence text = item == null ? null : item.getText();
                if (text != null) {
                    textLength = text.length();
                    hasText = true;
                } else if (item != null && item.getUri() != null) {
                    textLength = item.getUri().toString().length();
                    hasUri = true;
                }
            }
            return new ClipboardRead(true, textLength, hasText, hasHtml, hasUri, itemCount);
        } catch (RuntimeException ignored) {
            return new ClipboardRead(false, -1, false, false, false, 0);
        }
    }
}
