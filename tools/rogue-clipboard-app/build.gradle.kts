/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */

// Test-only "rogue app" used by the red-team test plan.
//
// It plays the role of a hostile third-party application and tries to read the secure clipboard
// storage, the IPC endpoints and the private/external data of Fcitx5 for Android. Deliberately
// standalone: it does NOT use the build-logic convention plugins and has zero third-party
// dependencies (Android SDK only), so ":tools:rogue-clipboard-app:assembleDebug" can be built
// independently from ":app". The manifest declares no permission at all (in particular no
// INTERNET).
plugins {
    alias(libs.plugins.android.application)
}

// Keep in sync with build-logic/convention/src/main/kotlin/Versions.kt (compileSdk 36, minSdk 23).
val rogueCompileSdk = 36
val rogueMinSdk = 23
val rogueTargetSdk = 36

// Same override knobs as the convention build (ProjectExtensions.buildToolsVersion); fall back to
// a build-tools version that is part of this project's toolchain image.
val rogueBuildTools: String = System.getenv("BUILD_TOOLS_VERSION")
    ?: (findProperty("buildTools") as? String)
    ?: "36.0.0"

android {
    namespace = "org.fcitx.fcitx5.android.rogue"
    compileSdk = rogueCompileSdk
    buildToolsVersion = rogueBuildTools

    defaultConfig {
        applicationId = "org.fcitx.fcitx5.android.rogue"
        minSdk = rogueMinSdk
        targetSdk = rogueTargetSdk
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    lint {
        // Red-team test tool, never shipped: lint warnings must not break the standalone build.
        abortOnError = false
    }
}
