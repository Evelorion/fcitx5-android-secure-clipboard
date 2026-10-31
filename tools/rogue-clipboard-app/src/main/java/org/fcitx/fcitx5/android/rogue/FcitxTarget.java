/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.rogue;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.provider.Settings;

/**
 * Read-only description of the installed Fcitx5 for Android ("小企鹅输入法") target used by the
 * red-team attacks.
 *
 * <p>The package name is discovered at runtime: the debug build ({@value #DEFAULT_PACKAGE}) is
 * preferred, the release build ({@value #RELEASE_PACKAGE}) is the fallback and an explicit
 * package can be passed via the "pkg" intent extra. All class names are the stable
 * namespace-based names declared in the app manifest (the applicationId suffix of a debug build
 * does not change the class names).</p>
 */
public final class FcitxTarget {

    /** Debug applicationId, the default target used by the local test build. */
    public static final String DEFAULT_PACKAGE = "org.fcitx.fcitx5.android.debug";

    /** Release applicationId fallback. */
    public static final String RELEASE_PACKAGE = "org.fcitx.fcitx5.android";

    public static final String REMOTE_SERVICE_CLASS =
            "org.fcitx.fcitx5.android.FcitxRemoteService";
    public static final String IME_SERVICE_CLASS =
            "org.fcitx.fcitx5.android.input.FcitxInputMethodService";
    public static final String SECURE_CAPTURE_CLASS =
            "org.fcitx.fcitx5.android.ui.securecapture.SecureCaptureActivity";
    public static final String SECURE_SHARE_CLASS =
            "org.fcitx.fcitx5.android.ui.securecapture.SecureShareReceiverActivity";
    public static final String SECURE_UNLOCK_CLASS =
            "org.fcitx.fcitx5.android.ui.securecapture.SecureUnlockActivity";
    public static final String CLIPBOARD_EDIT_CLASS =
            "org.fcitx.fcitx5.android.ui.main.ClipboardEditActivity";

    public static final String PROVIDER_AUTHORITY_SUFFIX = ".provider";
    public static final String IPC_ACTION_SUFFIX = ".IPC";

    public final String packageName;
    public final boolean installed;
    public final String label;
    public final String versionName;
    public final boolean debuggable;
    public final boolean allowBackup;
    public final String defaultIme;

    /** Candidate private data files the read_fcitx_private attack tries to open. */
    public final String[] privatePaths;

    /** Candidate external files dirs the read_fcitx_external attack tries to list. */
    public final String[] externalPaths;

    private FcitxTarget(String packageName, boolean installed, String label, String versionName,
                        boolean debuggable, boolean allowBackup, String defaultIme,
                        String[] privatePaths, String[] externalPaths) {
        this.packageName = packageName;
        this.installed = installed;
        this.label = label;
        this.versionName = versionName;
        this.debuggable = debuggable;
        this.allowBackup = allowBackup;
        this.defaultIme = defaultIme;
        this.privatePaths = privatePaths;
        this.externalPaths = externalPaths;
    }

    /** Discovers the target package, preferring the debug build. */
    public static FcitxTarget resolve(Context context, String requestedPackage) {
        final PackageManager packageManager = context.getPackageManager();
        final String[] candidates;
        if (requestedPackage != null && requestedPackage.trim().length() > 0) {
            candidates = new String[]{requestedPackage.trim(), DEFAULT_PACKAGE, RELEASE_PACKAGE};
        } else {
            candidates = new String[]{DEFAULT_PACKAGE, RELEASE_PACKAGE};
        }
        for (String candidate : candidates) {
            final PackageInfo info = packageInfo(packageManager, candidate);
            if (info != null) {
                final ApplicationInfo applicationInfo = info.applicationInfo;
                return new FcitxTarget(
                        candidate,
                        true,
                        applicationLabel(packageManager, applicationInfo),
                        safeVersionName(info),
                        applicationInfo != null
                                && (applicationInfo.flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0,
                        applicationInfo != null
                                && (applicationInfo.flags & ApplicationInfo.FLAG_ALLOW_BACKUP) != 0,
                        defaultIme(context),
                        privatePathsFor(candidate),
                        externalPathsFor(candidate));
            }
        }
        final String fallback = candidates[0];
        return new FcitxTarget(fallback, false, "-", "-", false, false, defaultIme(context),
                privatePathsFor(fallback), externalPathsFor(fallback));
    }

    public String component(String className) {
        return packageName + "/" + className;
    }

    public String providerUri() {
        return "content://" + packageName + PROVIDER_AUTHORITY_SUFFIX + "/";
    }

    public String ipcAction() {
        return packageName + IPC_ACTION_SUFFIX;
    }

    private static PackageInfo packageInfo(PackageManager packageManager, String packageName) {
        try {
            return packageManager.getPackageInfo(packageName,
                    PackageManager.GET_ACTIVITIES
                            | PackageManager.GET_SERVICES
                            | PackageManager.GET_PROVIDERS
                            | PackageManager.GET_RECEIVERS);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        } catch (SecurityException e) {
            // Package visibility (Android 11+) is declared in the rogue manifest.
            return null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String applicationLabel(PackageManager packageManager,
                                           ApplicationInfo applicationInfo) {
        if (applicationInfo == null) {
            return "-";
        }
        try {
            final CharSequence label = packageManager.getApplicationLabel(applicationInfo);
            return label == null ? "-" : label.toString();
        } catch (RuntimeException e) {
            return "-";
        }
    }

    private static String safeVersionName(PackageInfo info) {
        try {
            return info.versionName == null ? "-" : info.versionName;
        } catch (RuntimeException e) {
            return "-";
        }
    }

    private static String defaultIme(Context context) {
        try {
            final String value = Settings.Secure.getString(context.getContentResolver(),
                    Settings.Secure.DEFAULT_INPUT_METHOD);
            return value == null ? "-" : value;
        } catch (RuntimeException e) {
            return "-";
        }
    }

    /** Direct private-storage paths an attacker would try to read from another app UID. */
    public static String[] privatePathsFor(String packageName) {
        final String dataDir = "/data/data/" + packageName;
        final String deviceDataDir = "/data/user_de/0/" + packageName;
        return new String[]{
                dataDir + "/databases/secure_clbdb",
                dataDir + "/databases/secure_clbdb-wal",
                dataDir + "/databases/secure_clbdb-shm",
                dataDir + "/databases/clbdb",
                dataDir + "/databases/clbdb-wal",
                dataDir + "/shared_prefs/" + packageName + "_preferences.xml",
                dataDir + "/shared_prefs/org.fcitx.fcitx5.android_preferences.xml",
                deviceDataDir + "/shared_prefs/" + packageName + "_preferences.xml",
        };
    }

    /** Candidate external files dirs (Android/data/<pkg>) the attack tries to list. */
    public static String[] externalPathsFor(String packageName) {
        return new String[]{
                "/sdcard/Android/data/" + packageName + "/files",
                "/sdcard/Android/data/" + packageName + "/files/secure-exports",
                "/storage/emulated/0/Android/data/" + packageName + "/files",
                "/storage/emulated/0/Android/data/" + packageName + "/files/secure-exports",
        };
    }
}
