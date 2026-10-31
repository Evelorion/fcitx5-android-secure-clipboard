/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.probe;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Manual test UI of the clipboard probe.
 *
 * <p>Buttons (as required by the test plan):</p>
 * <ul>
 *   <li>a) read the current system clipboard length silently (background thread, content is never
 *       copied or logged);</li>
 *   <li>b) show / record the most recent clipboard change events (timestamp, length, MIME flags;
 *       never clipboard text);</li>
 *   <li>c) report whether a foreground app wrote the clipboard recently, including whether the
 *       probe itself was in the foreground while the write happened.</li>
 * </ul>
 *
 * <p>The probe is a test-only tool: it is not part of :app, is never shipped and declares no
 * permissions.</p>
 */
public final class MainActivity extends Activity {

    private static final int MAX_DISPLAY_EVENTS = 20;
    private static final long RECENT_WINDOW_MS = 5_000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService background = Executors.newSingleThreadExecutor();
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    private ProbeLog probeLog;
    private TextView statusView;
    private TextView resultView;
    private TextView eventsView;

    private final Runnable refreshRunnable = new Runnable() {
        @Override
        public void run() {
            renderStatus();
            handler.postDelayed(this, 1000L);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        probeLog = ProbeLog.get();
        probeLog.init(this);
        probeLog.ensureListening();
        setContentView(buildContentView());
    }

    @Override
    protected void onResume() {
        super.onResume();
        probeLog.setForeground(true);
        renderAll();
        handler.post(refreshRunnable);
    }

    @Override
    protected void onPause() {
        probeLog.setForeground(false);
        handler.removeCallbacks(refreshRunnable);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        background.shutdownNow();
        super.onDestroy();
    }

    private View buildContentView() {
        final ScrollView scrollView = new ScrollView(this);
        final LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        final int padding = dp(16);
        root.setPadding(padding, padding, padding, padding);
        scrollView.addView(root);

        final TextView title = new TextView(this);
        title.setText("Clipboard Probe");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f);
        root.addView(title);

        final TextView hint = new TextView(this);
        hint.setText("Test-only tool. Records clipboard change timestamps/lengths only; "
                + "never clipboard text. Declares no permissions and has no network access.");
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        root.addView(hint, matchWidth());

        statusView = bodyText();
        root.addView(statusView, matchWidth());

        resultView = bodyText();
        root.addView(resultView, matchWidth());

        root.addView(actionButton("a) Read clipboard length silently", new Runnable() {
            @Override
            public void run() {
                readClipboardLengthSilently();
            }
        }), matchWidth());

        root.addView(actionButton("b) Show last recorded events", new Runnable() {
            @Override
            public void run() {
                renderEvents();
            }
        }), matchWidth());

        root.addView(actionButton("c) Check foreground clipboard writer", new Runnable() {
            @Override
            public void run() {
                renderForegroundWriter();
            }
        }), matchWidth());

        root.addView(actionButton("Clear recorded events", new Runnable() {
            @Override
            public void run() {
                final int cleared = probeLog.clear();
                resultView.setText("Cleared " + cleared + " recorded events.");
                renderAll();
            }
        }), matchWidth());

        eventsView = bodyText();
        root.addView(eventsView, matchWidth());

        renderAll();
        return scrollView;
    }

    private void readClipboardLengthSilently() {
        resultView.setText("Reading current system clipboard length (no content is copied)...");
        background.execute(new Runnable() {
            @Override
            public void run() {
                final ProbeLog.ClipboardRead read = probeLog.readCurrentClipboard();
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        resultView.setText(describeRead(read));
                    }
                });
            }
        });
    }

    private static String describeRead(ProbeLog.ClipboardRead read) {
        if (!read.accessible) {
            return "Clipboard: not readable / empty\n"
                    + "(On Android 10+ a background app cannot read the clipboard at all; "
                    + "the probe must be in the foreground.)";
        }
        final StringBuilder builder = new StringBuilder();
        builder.append("Clipboard readable: yes\n");
        builder.append("Text length: ")
                .append(read.textLength >= 0 ? read.textLength : "n/a (no text item)")
                .append("\n");
        builder.append("MIME: plain=")
                .append(read.hasText)
                .append(", html=")
                .append(read.hasHtml)
                .append(", uri=")
                .append(read.hasUri)
                .append("\n");
        builder.append("Items: ").append(read.itemCount);
        return builder.toString();
    }

    private void renderStatus() {
        final ProbeLog.Event last = probeLog.lastEvent();
        final StringBuilder builder = new StringBuilder();
        builder.append("Clipboard listener registered: ")
                .append(probeLog.isListening() ? "yes" : "no")
                .append('\n');
        builder.append("Probe in foreground: ")
                .append(probeLog.isForeground() ? "yes" : "no")
                .append('\n');
        builder.append("Recorded clipboard events: ").append(probeLog.eventCount());
        if (last != null) {
            builder.append('\n').append("Latest event: ").append(describeEvent(last));
        } else {
            builder.append('\n').append("Latest event: none");
        }
        statusView.setText(builder.toString());
    }

    private void renderEvents() {
        final List<ProbeLog.Event> events = probeLog.events();
        final StringBuilder builder = new StringBuilder();
        builder.append("Last ").append(Math.min(events.size(), MAX_DISPLAY_EVENTS))
                .append(" of ").append(events.size()).append(" recorded events (oldest first):");
        if (events.isEmpty()) {
            builder.append("\n(none)");
        } else {
            final int start = Math.max(0, events.size() - MAX_DISPLAY_EVENTS);
            for (int index = start; index < events.size(); index++) {
                builder.append('\n').append(describeEvent(events.get(index)));
            }
        }
        eventsView.setText(builder.toString());
    }

    private void renderForegroundWriter() {
        final List<ProbeLog.Event> events = probeLog.events();
        final long nowElapsed = SystemClock.elapsedRealtime();
        final ProbeLog.Event last = events.isEmpty() ? null : events.get(events.size() - 1);
        int recentCount = 0;
        for (int index = events.size() - 1; index >= 0; index--) {
            if (nowElapsed - events.get(index).elapsedRealtimeMs <= RECENT_WINDOW_MS) {
                recentCount++;
            } else {
                break;
            }
        }

        final StringBuilder builder = new StringBuilder();
        builder.append("Clipboard listener registered: ")
                .append(probeLog.isListening() ? "yes" : "no")
                .append('\n');
        if (last == null) {
            builder.append("Foreground app wrote the clipboard: unknown (no event recorded yet)\n");
        } else {
            final long ageMs = Math.max(0L, nowElapsed - last.elapsedRealtimeMs);
            builder.append("Foreground app wrote the clipboard within the last ")
                    .append(RECENT_WINDOW_MS / 1000)
                    .append(" s: ")
                    .append(recentCount > 0 ? "yes (" + recentCount + " event(s))" : "no")
                    .append('\n');
            builder.append("Latest write age: ").append(ageMs).append(" ms\n");
            builder.append("Probe was in the foreground at that write: ")
                    .append(last.probeForeground ? "yes" : "no")
                    .append('\n');
            builder.append("Latest event: ").append(describeEvent(last));
        }
        builder.append('\n').append("Note: Android 10+ only lets the foreground app (or the "
                + "default input method) read clipboard content; the probe records the callback "
                + "regardless.");
        resultView.setText(builder.toString());
        renderStatus();
    }

    private String describeEvent(ProbeLog.Event event) {
        final String time = timeFormat.format(new Date(event.wallTimeMs));
        final String length = event.textLength >= 0 ? event.textLength + " chars" : "unknown";
        return "#" + event.sequence
                + " at " + time
                + " (elapsed=" + event.elapsedRealtimeMs + " ms)"
                + " length=" + length
                + " readable=" + event.readable
                + " text=" + event.hasText
                + " html=" + event.hasHtml
                + " uri=" + event.hasUri
                + " probeForeground=" + event.probeForeground;
    }

    private void renderAll() {
        renderStatus();
        renderEvents();
    }

    private TextView bodyText() {
        final TextView textView = new TextView(this);
        textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        textView.setTextIsSelectable(true);
        return textView;
    }

    private Button actionButton(String label, final Runnable action) {
        final Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTag(action);
        button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                action.run();
            }
        });
        return button;
    }

    private LinearLayout.LayoutParams matchWidth() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                getResources().getDisplayMetrics()));
    }
}
