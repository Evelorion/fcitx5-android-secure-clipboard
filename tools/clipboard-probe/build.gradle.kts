/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */

// Test-only clipboard probe application.
//
// Deliberately standalone: it does NOT use the build-logic convention plugins and has zero
// third-party dependencies (Android SDK only), so ":tools:clipboard-probe:assembleDebug" can be
// built independently from ":app". The manifest declares no permission at all (in particular no
// INTERNET), and the app never persists clipboard content.
plugins {
    alias(libs.plugins.android.application)
}

// Keep in sync with build-logic/convention/src/main/kotlin/Versions.kt (compileSdk 36, minSdk 23).
val probeCompileSdk = 36
val probeMinSdk = 23
val probeTargetSdk = 36

// Same override knobs as the convention build (ProjectExtensions.buildToolsVersion); fall back to
// a build-tools version that is part of this project's toolchain image.
val probeBuildTools: String = System.getenv("BUILD_TOOLS_VERSION")
    ?: (findProperty("buildTools") as? String)
    ?: "36.0.0"

android {
    namespace = "org.fcitx.fcitx5.android.probe"
    compileSdk = probeCompileSdk
    buildToolsVersion = probeBuildTools

    defaultConfig {
        applicationId = "org.fcitx.fcitx5.android.probe"
        minSdk = probeMinSdk
        targetSdk = probeTargetSdk
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    lint {
        // The probe is a developer/test tool and is never shipped; lint warnings must not break
        // the standalone build.
        abortOnError = false
    }
}
