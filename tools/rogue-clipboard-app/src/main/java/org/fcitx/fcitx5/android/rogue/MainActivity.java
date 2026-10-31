/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.rogue;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Exported test activity of the red-team "rogue app".
 *
 * <p>Two ways to drive it:</p>
 * <ul>
 *   <li>UI: one button per attack (button text is the exact attack name, which makes it easy to
 *       drive with uiautomator / adb input tap);</li>
 *   <li>adb intent: {@code am start -n org.fcitx.fcitx5.android.rogue/.MainActivity
 *       --es attack read_clipboard} (optionally add {@code --es pkg org.fcitx.fcitx5.android}
 *       to override the auto-discovered target package, or use {@code --es attack all}).</li>
 * </ul>
 *
 * <p>Every result is appended to the on-screen list (one TextView per RESULT line, so
 * uiautomator can read them) and written to logcat with tag ROGUE.</p>
 */
public final class MainActivity extends Activity {

    /** Intent extra selecting the attack to run. */
    public static final String EXTRA_ATTACK = "attack";

    /** Optional intent extra overriding the target package name. */
    public static final String EXTRA_PACKAGE = "pkg";

    private static final long INITIAL_ATTACK_DELAY_MS = 400L;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private ExecutorService executor;
    private ClipboardWatch clipboardWatch;
    private ScrollView scrollView;
    private LinearLayout resultsContainer;
    private TextView headerView;
    private TextView statusView;

    private String requestedPackage;
    private String pendingAttack;
    private boolean initialAttackScheduled;
    private boolean resumed;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        executor = Executors.newSingleThreadExecutor();
        clipboardWatch = new ClipboardWatch(this);
        clipboardWatch.start();
        setContentView(buildContentView());
        RogueLog.setListener(new RogueLog.Listener() {
            @Override
            public void onResult(String line) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        appendResult(line);
                    }
                });
            }
        });
        renderHistory();
        renderTargetHeader();
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        renderTargetHeader();
        maybeScheduleInitialAttack();
    }

    @Override
    protected void onPause() {
        resumed = false;
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        RogueLog.setListener(null);
        if (clipboardWatch != null) {
            clipboardWatch.stop();
        }
        if (executor != null) {
            executor.shutdownNow();
        }
        super.onDestroy();
    }

    private void handleIntent(Intent intent) {
        if (intent == null) {
            return;
        }
        final String packageExtra = intent.getStringExtra(EXTRA_PACKAGE);
        if (packageExtra != null && packageExtra.trim().length() > 0) {
            requestedPackage = packageExtra.trim();
        }
        final String attackExtra = intent.getStringExtra(EXTRA_ATTACK);
        if (attackExtra != null && attackExtra.trim().length() > 0) {
            pendingAttack = attackExtra.trim();
            initialAttackScheduled = false;
            if (statusView != null) {
                statusView.setText("queued attack: " + pendingAttack);
            }
            renderTargetHeader();
            maybeScheduleInitialAttack();
        }
    }

    private void maybeScheduleInitialAttack() {
        if (!resumed || initialAttackScheduled || pendingAttack == null) {
            return;
        }
        initialAttackScheduled = true;
        final String attack = pendingAttack;
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                launchAttack(attack);
            }
        }, INITIAL_ATTACK_DELAY_MS);
    }

    private void launchAttack(final String attack) {
        final String normalized = attack == null ? "" : attack.trim().toLowerCase(Locale.US);
        statusView.setText("running: " + normalized);
        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    if (AttackRunner.ATTACK_ALL.equals(normalized)) {
                        AttackRunner.runAll(getApplicationContext(), requestedPackage);
                    } else {
                        AttackRunner.run(getApplicationContext(), normalized, requestedPackage);
                    }
                } catch (Throwable throwable) {
                    RogueLog.emit(normalized.length() == 0 ? "unknown" : normalized, "ERROR",
                            "uncaught=" + throwable.getClass().getSimpleName());
                }
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        statusView.setText("idle");
                    }
                });
            }
        });
    }

    private void appendResult(String line) {
        if (resultsContainer == null) {
            return;
        }
        final TextView view = new TextView(this);
        view.setText(line);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        view.setTypeface(Typeface.MONOSPACE);
        view.setTextIsSelectable(true);
        view.setPadding(0, dp(2), 0, dp(2));
        resultsContainer.addView(view);
        if (scrollView != null) {
            scrollView.post(new Runnable() {
                @Override
                public void run() {
                    scrollView.fullScroll(View.FOCUS_DOWN);
                }
            });
        }
    }

    private void renderHistory() {
        final List<String> lines = RogueLog.lines();
        for (String line : lines) {
            appendResult(line);
        }
    }

    private void renderTargetHeader() {
        if (headerView == null) {
            return;
        }
        final FcitxTarget target = FcitxTarget.resolve(this, requestedPackage);
        final StringBuilder builder = new StringBuilder();
        builder.append("target: ").append(target.packageName)
                .append(" installed=").append(target.installed);
        if (target.installed) {
            builder.append(" version=").append(target.versionName)
                    .append(" debuggable=").append(target.debuggable)
                    .append(" allowBackup=").append(target.allowBackup);
        }
        builder.append('\n').append("default IME: ").append(target.defaultIme);
        if (pendingAttack != null) {
            builder.append('\n').append("requested attack: ").append(pendingAttack);
        } else {
            builder.append('\n').append("requested attack: none (use a button or --es attack)");
        }
        headerView.setText(builder.toString());
    }

    private View buildContentView() {
        scrollView = new ScrollView(this);
        final LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        final int padding = dp(12);
        root.setPadding(padding, padding, padding, padding);
        scrollView.addView(root);

        final TextView title = new TextView(this);
        title.setText("Rogue Clipboard App (red-team, test-only)");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f);
        root.addView(title, matchWidth());

        final TextView warning = new TextView(this);
        warning.setText("Zero permissions, no INTERNET. Runs 10 attacks against Fcitx5 for "
                + "Android from a hostile third-party app's point of view. Results are printed "
                + "below as RESULT|attack|status|detail and to logcat tag ROGUE. "
                + "Only use on a dedicated test device/emulator.");
        warning.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        root.addView(warning, matchWidth());

        headerView = bodyText();
        root.addView(headerView, matchWidth());

        statusView = bodyText();
        statusView.setText("idle");
        root.addView(statusView, matchWidth());

        for (int index = 0; index < AttackRunner.ATTACKS.length; index++) {
            final String attackName = AttackRunner.ATTACKS[index];
            final String attackTitle = AttackRunner.ATTACK_TITLES[index];
            root.addView(actionButton(attackName,
                    new Runnable() {
                        @Override
                        public void run() {
                            launchAttack(attackName);
                        }
                    }, attackTitle), matchWidth());
        }

        root.addView(actionButton(AttackRunner.ATTACK_ALL, new Runnable() {
            @Override
            public void run() {
                launchAttack(AttackRunner.ATTACK_ALL);
            }
        }, "run the whole attack suite"), matchWidth());

        root.addView(actionButton("clear_results", new Runnable() {
            @Override
            public void run() {
                RogueLog.clear();
                resultsContainer.removeAllViews();
                statusView.setText("idle");
            }
        }, "clear the on-screen result list"), matchWidth());

        final TextView resultsTitle = new TextView(this);
        resultsTitle.setText("results (one RESULT line per TextView):");
        resultsTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        root.addView(resultsTitle, matchWidth());

        resultsContainer = new LinearLayout(this);
        resultsContainer.setOrientation(LinearLayout.VERTICAL);
        root.addView(resultsContainer, matchWidth());

        return scrollView;
    }

    private Button actionButton(final String text, final Runnable action, final String contentHint) {
        final Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        button.setContentDescription(text + " - " + contentHint);
        button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                action.run();
            }
        });
        return button;
    }

    private TextView bodyText() {
        final TextView textView = new TextView(this);
        textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        textView.setTextIsSelectable(true);
        return textView;
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
