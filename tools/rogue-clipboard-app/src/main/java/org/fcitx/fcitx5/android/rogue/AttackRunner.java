/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.rogue;

import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.database.Cursor;
import android.net.Uri;
import android.os.IBinder;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The red-team attacks of the rogue clipboard app.
 *
 * <p>All attacks run as an ordinary third-party app: no permission, no root, no INTERNET. Every
 * attack emits exactly one {@code RESULT|attack|status|detail} line through {@link RogueLog}.
 * The status vocabulary is documented in {@link RogueLog} and in README.md.</p>
 *
 * <p>Important Android platform limitations that the tool documents instead of hiding:</p>
 * <ul>
 *   <li>Android 10+ only lets the foreground app (or the default IME) read clipboard content;
 *       a background listener still receives change callbacks but cannot read the text that is
 *       recorded by a secure channel B capture.</li>
 *   <li>Android 10+ blocks background activity launches: after the first forged activity put the
 *       rogue app into the background, further launches may be silently dropped. The spam attack
 *       therefore reports attempts/denied and the note that the platform may silently block.</li>
 *   <li>Android 11+ package visibility is declared through &lt;queries&gt; in the manifest; this
 *       is visibility, not a capability.</li>
 * </ul>
 */
public final class AttackRunner {

    public static final String ATTACK_ALL = "all";

    /** Attack names accepted by the "attack" intent extra, in suite order. */
    public static final String[] ATTACKS = new String[]{
            "read_clipboard",
            "listen_clipboard",
            "read_fcitx_private",
            "bind_fcitx_ipc",
            "query_fcitx_provider",
            "forged_process_text",
            "spam_process_text",
            "forged_send",
            "read_logs",
            "read_fcitx_external",
    };

    /** Short UI labels, parallel to {@link #ATTACKS}. */
    public static final String[] ATTACK_TITLES = new String[]{
            "read_clipboard (foreground read)",
            "listen_clipboard (3 s)",
            "read_fcitx_private (DB/prefs)",
            "bind_fcitx_ipc (service)",
            "query_fcitx_provider (content)",
            "forged_process_text (inject)",
            "spam_process_text (x10)",
            "forged_send (inject)",
            "read_logs (logcat)",
            "read_fcitx_external (Android/data)",
    };

    private static final String INJECT_PROCESS_TEXT_PREFIX = "ROGUE_INJECT_";
    private static final String INJECT_SEND_TEXT = "ROGUE_SEND_1";
    private static final long ACTIVITY_SETTLE_MS = 900L;
    private static final int MAX_DETAIL_MESSAGE = 120;

    private AttackRunner() {
    }

    public static boolean isKnown(String attack) {
        if (attack == null) {
            return false;
        }
        final String normalized = attack.trim().toLowerCase(Locale.US);
        if (ATTACK_ALL.equals(normalized)) {
            return true;
        }
        for (String candidate : ATTACKS) {
            if (candidate.equals(normalized)) {
                return true;
            }
        }
        return false;
    }

    /** Runs one attack (or all of them) and emits the result line(s). */
    public static void run(Context context, String attack, String requestedPackage) {
        final Context appContext = context.getApplicationContext();
        final String normalized = attack == null ? "" : attack.trim().toLowerCase(Locale.US);
        if (ATTACK_ALL.equals(normalized)) {
            runAll(appContext, requestedPackage);
            return;
        }
        if (!isKnown(normalized)) {
            RogueLog.emit(normalized.length() == 0 ? "unknown" : normalized, "ERROR",
                    "unknown_attack;known=" + join(ATTACKS, ','));
            return;
        }
        final FcitxTarget target = FcitxTarget.resolve(appContext, requestedPackage);
        try {
            switch (normalized) {
                case "read_clipboard":
                    attackReadClipboard(appContext);
                    break;
                case "listen_clipboard":
                    attackListenClipboard(appContext);
                    break;
                case "read_fcitx_private":
                    attackReadFcitxPrivate(appContext, target);
                    break;
                case "bind_fcitx_ipc":
                    attackBindFcitxIpc(appContext, target);
                    break;
                case "query_fcitx_provider":
                    attackQueryFcitxProvider(appContext, target);
                    break;
                case "forged_process_text":
                    attackForgedProcessText(appContext, target);
                    break;
                case "spam_process_text":
                    attackSpamProcessText(appContext, target);
                    break;
                case "forged_send":
                    attackForgedSend(appContext, target);
                    break;
                case "read_logs":
                    attackReadLogs(appContext, target);
                    break;
                case "read_fcitx_external":
                    attackReadFcitxExternal(appContext, target);
                    break;
                default:
                    RogueLog.emit(normalized, "ERROR", "unknown_attack");
                    break;
            }
        } catch (Throwable throwable) {
            RogueLog.emit(normalized, "ERROR",
                    "uncaught=" + throwable.getClass().getSimpleName() + ":"
                            + firstLine(throwable.getMessage()));
        }
    }

    /** Runs the complete suite in order, then emits a synthetic suite DONE marker. */
    public static void runAll(Context context, String requestedPackage) {
        final Context appContext = context.getApplicationContext();
        for (String attack : ATTACKS) {
            run(appContext, attack, requestedPackage);
            try {
                Thread.sleep(350L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        RogueLog.emit("suite", "DONE", "attacks=" + ATTACKS.length
                + ";note=forged_activity_launches_after_the_first_background_transition_may_be_blocked");
    }

    /* =================== 1. read_clipboard =================== */

    /**
     * Foreground clipboard read. This is the baseline capability demonstration of the rogue app;
     * the content preview is intended for synthetic test strings only (see README) and the
     * SHA-256 prefix allows comparing a leaked clip without printing it.
     */
    private static void attackReadClipboard(Context context) {
        final ClipboardWatch watch = new ClipboardWatch(context);
        final ClipboardWatch.ReadResult result = watch.readNow();
        final String detail = "accessible=" + result.accessible
                + ";hasText=" + result.hasText
                + ";length=" + result.textLength
                + ";sha256_8=" + (result.hash8 == null ? "-" : result.hash8)
                + ";preview=\"" + result.preview + "\""
                + ";itemCount=" + result.itemCount
                + ";note=foreground_read_is_allowed_by_the_platform_test_strings_only";
        RogueLog.emit("read_clipboard", result.accessible ? "SUCCESS" : "BLOCKED", detail);
    }

    /* =================== 2. listen_clipboard =================== */

    /** Registers a clipboard listener for 3 seconds and reports callbacks / readable length. */
    private static void attackListenClipboard(Context context) {
        final ClipboardManager clipboardManager = (ClipboardManager) context
                .getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboardManager == null) {
            RogueLog.emit("listen_clipboard", "ERROR", "clipboard_manager=null");
            return;
        }
        final AtomicInteger callbacks = new AtomicInteger();
        final AtomicInteger readable = new AtomicInteger();
        final AtomicInteger lastLength = new AtomicInteger(-1);
        final AtomicReference<String> lastHash = new AtomicReference<>("-");
        final ClipboardManager.OnPrimaryClipChangedListener listener =
                new ClipboardManager.OnPrimaryClipChangedListener() {
                    @Override
                    public void onPrimaryClipChanged() {
                        callbacks.incrementAndGet();
                        try {
                            final ClipData clip = clipboardManager.getPrimaryClip();
                            if (clip == null || clip.getItemCount() == 0) {
                                return;
                            }
                            final ClipData.Item item = clip.getItemAt(0);
                            final CharSequence text = item == null ? null : item.getText();
                            if (text != null) {
                                readable.incrementAndGet();
                                lastLength.set(text.length());
                                lastHash.set(ClipboardWatch.sha256Prefix(text.toString()));
                            }
                        } catch (RuntimeException ignored) {
                            // Android 10+ background read restriction; the callback still counts.
                        }
                    }
                };
        clipboardManager.addPrimaryClipChangedListener(listener);
        try {
            Thread.sleep(3000L);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        clipboardManager.removePrimaryClipChangedListener(listener);
        final String status = callbacks.get() > 0 && readable.get() > 0 ? "SUCCESS" : "INFO";
        RogueLog.emit("listen_clipboard", status,
                "window_ms=3000;callbacks=" + callbacks.get()
                        + ";readable=" + readable.get()
                        + ";last_length=" + lastLength.get()
                        + ";last_sha256_8=" + lastHash.get());
    }

    /* =================== 3. read_fcitx_private =================== */

    /** Direct FileInputStream attacks on the target private storage. */
    private static void attackReadFcitxPrivate(Context context, FcitxTarget target) {
        if (!target.installed) {
            RogueLog.emit("read_fcitx_private", "BLOCKED",
                    "target_pkg=" + target.packageName + ";installed=false");
            return;
        }
        final StringBuilder detail = new StringBuilder("target_pkg=");
        detail.append(target.packageName).append(';');
        boolean anyRead = false;
        for (String path : target.privatePaths) {
            final String name = baseName(path);
            try (FileInputStream input = new FileInputStream(path)) {
                final byte[] buffer = new byte[64];
                final int read = input.read(buffer);
                if (read > 0) {
                    anyRead = true;
                }
                detail.append(name).append("=READ:").append(read).append("B;");
            } catch (java.io.FileNotFoundException e) {
                detail.append(name).append("=FileNotFoundException;");
            } catch (SecurityException e) {
                detail.append(name).append("=SecurityException;");
            } catch (IOException e) {
                detail.append(name).append('=').append(e.getClass().getSimpleName()).append(';');
            }
        }
        final File dataDir = new File("/data/data/" + target.packageName);
        File[] children = null;
        String listError = "none";
        try {
            children = dataDir.listFiles();
        } catch (SecurityException e) {
            listError = "SecurityException";
        }
        detail.append("data_dir_exists=").append(dataDir.exists())
                .append(";data_dir_list=")
                .append(children == null ? "null/" + listError : String.valueOf(children.length));
        RogueLog.emit("read_fcitx_private", anyRead ? "SUCCESS" : "BLOCKED", detail.toString());
    }

    /* =================== 4. bind_fcitx_ipc =================== */

    /** Tries to bind the signature-protected FcitxRemoteService IPC endpoint. */
    private static void attackBindFcitxIpc(Context context, FcitxTarget target) {
        if (!target.installed) {
            RogueLog.emit("bind_fcitx_ipc", "BLOCKED",
                    "target_pkg=" + target.packageName + ";installed=false");
            return;
        }
        final Intent actionIntent = new Intent(target.ipcAction());
        actionIntent.setPackage(target.packageName);
        final String actionResult = tryBindService(context, actionIntent);

        final Intent componentIntent = new Intent();
        componentIntent.setClassName(target.packageName, FcitxTarget.REMOTE_SERVICE_CLASS);
        final String componentResult = tryBindService(context, componentIntent);

        final boolean bound = actionResult.contains("bound=true")
                || componentResult.contains("bound=true");
        RogueLog.emit("bind_fcitx_ipc", bound ? "SUCCESS" : "BLOCKED",
                "target_pkg=" + target.packageName
                        + ";action=" + target.ipcAction()
                        + ";action_result=" + actionResult
                        + ";explicit_result=" + componentResult);
    }

    private static String tryBindService(Context context, Intent intent) {
        final ServiceConnection connection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                // A successful bind would show up as bound=true in the result line.
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                // Nothing to do for a test tool.
            }
        };
        boolean bound = false;
        String error = "none";
        try {
            bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE);
        } catch (SecurityException e) {
            error = "SecurityException:" + firstLine(e.getMessage());
        } catch (RuntimeException e) {
            error = e.getClass().getSimpleName() + ":" + firstLine(e.getMessage());
        } finally {
            if (bound) {
                try {
                    context.unbindService(connection);
                } catch (RuntimeException ignored) {
                    // Test tool.
                }
            }
        }
        return "bound=" + bound + ";exception=" + (error.length() == 0 ? "none" : error);
    }

    /* =================== 5. query_fcitx_provider =================== */

    /** Tries to query the exported (MANAGE_DOCUMENTS protected) FcitxDataProvider. */
    private static void attackQueryFcitxProvider(Context context, FcitxTarget target) {
        if (!target.installed) {
            RogueLog.emit("query_fcitx_provider", "BLOCKED",
                    "target_pkg=" + target.packageName + ";installed=false");
            return;
        }
        final String uriString = target.providerUri();
        boolean accessible = false;
        String outcome;
        try (Cursor cursor = context.getContentResolver().query(Uri.parse(uriString),
                null, null, null, null)) {
            if (cursor == null) {
                outcome = "cursor=null;exception=none";
            } else {
                accessible = true;
                outcome = "cursor_rows=" + cursor.getCount()
                        + ";columns=" + cursor.getColumnCount();
            }
        } catch (SecurityException e) {
            outcome = "exception=SecurityException:" + firstLine(e.getMessage());
        } catch (RuntimeException e) {
            outcome = "exception=" + e.getClass().getSimpleName() + ":" + firstLine(e.getMessage());
        }
        RogueLog.emit("query_fcitx_provider", accessible ? "SUCCESS" : "BLOCKED",
                "target_pkg=" + target.packageName + ";uri=" + uriString + ";" + outcome);
    }

    /* =================== 6. forged_process_text =================== */

    /** Forges ACTION_PROCESS_TEXT against the secure capture activity. */
    private static void attackForgedProcessText(Context context, FcitxTarget target) {
        if (!target.installed) {
            RogueLog.emit("forged_process_text", "BLOCKED",
                    "target_pkg=" + target.packageName + ";installed=false");
            return;
        }
        final String injectedText = INJECT_PROCESS_TEXT_PREFIX + "1";
        final Intent intent = buildProcessTextIntent(target, injectedText);
        final ClipboardWatch watch = new ClipboardWatch(context);
        watch.start();
        final int callbacksBefore = watch.callbackCount();
        final String startResult = startTargetActivity(context, intent);
        sleep(ACTIVITY_SETTLE_MS);
        final int callbacks = watch.callbackCount() - callbacksBefore;
        watch.stop();
        final boolean started = startResult.contains("started=true");
        RogueLog.emit("forged_process_text", started ? "INFO" : "BLOCKED",
                "target_pkg=" + target.packageName
                        + ";component=" + target.component(FcitxTarget.SECURE_CAPTURE_CLASS)
                        + ";extra_length=" + injectedText.length()
                        + ";" + startResult
                        + ";clipboard_callbacks=" + callbacks
                        + ";expected=confirmation_dialog_and_no_clipboard_write"
                        + ";note=started_means_start_call_accepted_visibility_not_verifiable_as_third_party");
    }

    private static Intent buildProcessTextIntent(FcitxTarget target, String text) {
        final Intent intent = new Intent(Intent.ACTION_PROCESS_TEXT);
        intent.setClassName(target.packageName, FcitxTarget.SECURE_CAPTURE_CLASS);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_PROCESS_TEXT, text);
        intent.putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, false);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return intent;
    }

    /* =================== 7. spam_process_text =================== */

    /** Fires 10 forged PROCESS_TEXT intents in a burst. */
    private static void attackSpamProcessText(Context context, FcitxTarget target) {
        if (!target.installed) {
            RogueLog.emit("spam_process_text", "BLOCKED",
                    "target_pkg=" + target.packageName + ";installed=false");
            return;
        }
        final ClipboardWatch watch = new ClipboardWatch(context);
        watch.start();
        final int callbacksBefore = watch.callbackCount();
        int started = 0;
        int denied = 0;
        String lastError = "none";
        for (int index = 0; index < 10; index++) {
            final Intent intent = buildProcessTextIntent(
                    target, INJECT_PROCESS_TEXT_PREFIX + index);
            final String result = startTargetActivity(context, intent);
            if (result.contains("started=true")) {
                started++;
            } else {
                denied++;
                lastError = result;
            }
            sleep(120L);
        }
        sleep(1000L);
        final int callbacks = watch.callbackCount() - callbacksBefore;
        watch.stop();
        RogueLog.emit("spam_process_text", started > 0 ? "INFO" : "BLOCKED",
                "target_pkg=" + target.packageName
                        + ";attempts=10;started=" + started
                        + ";denied=" + denied
                        + ";last_error=" + lastError
                        + ";clipboard_callbacks=" + callbacks
                        + ";note=background_activity_launch_may_silently_drop_calls_after_the_first_as_third_party_app");
    }

    /* =================== 8. forged_send =================== */

    /** Forges ACTION_SEND text/plain against the secure share receiver activity. */
    private static void attackForgedSend(Context context, FcitxTarget target) {
        if (!target.installed) {
            RogueLog.emit("forged_send", "BLOCKED",
                    "target_pkg=" + target.packageName + ";installed=false");
            return;
        }
        final Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setClassName(target.packageName, FcitxTarget.SECURE_SHARE_CLASS);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_TEXT, INJECT_SEND_TEXT);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        final ClipboardWatch watch = new ClipboardWatch(context);
        watch.start();
        final int callbacksBefore = watch.callbackCount();
        final String startResult = startTargetActivity(context, intent);
        sleep(ACTIVITY_SETTLE_MS);
        final int callbacks = watch.callbackCount() - callbacksBefore;
        watch.stop();
        final boolean started = startResult.contains("started=true");
        RogueLog.emit("forged_send", started ? "INFO" : "BLOCKED",
                "target_pkg=" + target.packageName
                        + ";component=" + target.component(FcitxTarget.SECURE_SHARE_CLASS)
                        + ";extra_length=" + INJECT_SEND_TEXT.length()
                        + ";" + startResult
                        + ";clipboard_callbacks=" + callbacks
                        + ";expected=confirmation_dialog_and_no_clipboard_write");
    }

    private static String startTargetActivity(Context context, Intent intent) {
        try {
            context.startActivity(intent);
            return "started=true;exception=none";
        } catch (ActivityNotFoundException e) {
            return "started=false;exception=ActivityNotFoundException";
        } catch (SecurityException e) {
            return "started=false;exception=SecurityException:" + firstLine(e.getMessage());
        } catch (RuntimeException e) {
            return "started=false;exception=" + e.getClass().getSimpleName() + ":"
                    + firstLine(e.getMessage());
        }
    }

    /* =================== 9. read_logs =================== */

    /** Tries to read other apps' logcat output. */
    private static void attackReadLogs(Context context, FcitxTarget target) {
        int total = 0;
        int fcitxLines = 0;
        int targetLines = 0;
        String stderr = "none";
        String exitCode = "n/a";
        final String targetLower = target.packageName.toLowerCase(Locale.US);
        Process process = null;
        try {
            process = Runtime.getRuntime().exec(new String[]{"logcat", "-d", "-t", "400"});
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                int guard = 0;
                while ((line = reader.readLine()) != null && guard < 800) {
                    guard++;
                    if (line.contains(RogueLog.TAG)) {
                        continue;
                    }
                    total++;
                    final String lower = line.toLowerCase(Locale.US);
                    if (lower.contains("fcitx") || lower.contains("clipboard")) {
                        fcitxLines++;
                    }
                    if (lower.contains(targetLower)) {
                        targetLines++;
                    }
                }
            }
            try (BufferedReader errorReader = new BufferedReader(new InputStreamReader(
                    process.getErrorStream(), StandardCharsets.UTF_8))) {
                final String errorLine = errorReader.readLine();
                if (errorLine != null && errorLine.trim().length() > 0) {
                    stderr = firstLine(errorLine);
                }
            }
            process.destroy();
            try {
                exitCode = String.valueOf(process.waitFor());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        } catch (Exception e) {
            RogueLog.emit("read_logs", "ERROR",
                    "exception=" + e.getClass().getSimpleName() + ":" + firstLine(e.getMessage()));
            return;
        } finally {
            if (process != null) {
                try {
                    process.destroy();
                } catch (RuntimeException ignored) {
                    // Test tool.
                }
            }
        }
        RogueLog.emit("read_logs", targetLines > 0 ? "SUCCESS" : "BLOCKED",
                "target_pkg=" + target.packageName
                        + ";window_lines=400;total_lines=" + total
                        + ";fcitx_or_clipboard_lines=" + fcitxLines
                        + ";target_pkg_lines=" + targetLines
                        + ";exit=" + exitCode
                        + ";stderr=" + stderr
                        + ";note=modern_android_restricts_logcat_reads_to_own_uid");
    }

    /* =================== 10. read_fcitx_external =================== */

    /** Tries to list the target /sdcard/Android/data files and secure-exports dirs. */
    private static void attackReadFcitxExternal(Context context, FcitxTarget target) {
        if (!target.installed) {
            RogueLog.emit("read_fcitx_external", "BLOCKED",
                    "target_pkg=" + target.packageName + ";installed=false");
            return;
        }
        final StringBuilder detail = new StringBuilder("target_pkg=");
        detail.append(target.packageName).append(';');
        boolean listed = false;
        int index = 0;
        for (String path : target.externalPaths) {
            detail.append('p').append(index).append('=').append(abbreviateExternal(path, target))
                    .append('{');
            final File directory = new File(path);
            try {
                if (!directory.exists()) {
                    detail.append("exists=false;exception=none");
                } else {
                    final File[] children = directory.listFiles();
                    if (children == null) {
                        detail.append("exists=true;listable=false;exception=none");
                    } else {
                        detail.append("exists=true;listable=true;children=")
                                .append(children.length).append(";exception=none");
                        if (children.length > 0) {
                            listed = true;
                        }
                    }
                }
            } catch (SecurityException e) {
                detail.append("exists=unknown;exception=SecurityException");
            }
            detail.append("};");
            index++;
        }
        RogueLog.emit("read_fcitx_external", listed ? "SUCCESS" : "BLOCKED", detail.toString());
    }

    /* =================== helpers =================== */

    private static void sleep(long milliseconds) {
        try {
            Thread.sleep(milliseconds);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static String baseName(String path) {
        final int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }

    private static String abbreviateExternal(String path, FcitxTarget target) {
        final String sdcardPrefix = "/sdcard/Android/data/" + target.packageName;
        final String emulatedPrefix = "/storage/emulated/0/Android/data/" + target.packageName;
        if (path.startsWith(sdcardPrefix)) {
            return "sdcard:" + path.substring(sdcardPrefix.length());
        }
        if (path.startsWith(emulatedPrefix)) {
            return "emulated:" + path.substring(emulatedPrefix.length());
        }
        return path;
    }

    private static String firstLine(String message) {
        if (message == null) {
            return "none";
        }
        final String trimmed = message.trim();
        final int newline = trimmed.indexOf('\n');
        final String value = newline < 0 ? trimmed : trimmed.substring(0, newline);
        return value.length() > MAX_DETAIL_MESSAGE
                ? value.substring(0, MAX_DETAIL_MESSAGE) + "..."
                : value;
    }

    private static String join(String[] values, char separator) {
        final StringBuilder builder = new StringBuilder();
        for (int index = 0; index < values.length; index++) {
            if (index > 0) {
                builder.append(separator);
            }
            builder.append(values[index]);
        }
        return builder.toString();
    }
}
