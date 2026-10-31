<!--
  SPDX-License-Identifier: LGPL-2.1-or-later
  SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
-->

# Clipboard probe (test-only)

Standalone Android application used by the secure clipboard test plan
(`docs/security/07-test-plan-and-instrumentation.md`). It is **not** a dependency of `:app`,
is never shipped with the product and declares **no permissions** (in particular no INTERNET).

## What it records

Only non-sensitive telemetry of the Android system clipboard:

- clipboard change event timestamps (wall clock + elapsed realtime),
- text length of the current clip (when readable), MIME flags, item count,
- whether the probe itself was in the foreground when the event was observed.

It **never** stores, persists or logs clipboard text. On Android 10+ a background app is not
allowed to read clipboard content, so events recorded in the background typically have
`readable=false` and `length=-1`; the change callback is still observed, which is enough for the
"the system clipboard must not have changed" checks.

## Build / install

```bat
gradlew.bat :tools:clipboard-probe:assembleDebug
adb install -r tools\clipboard-probe\build\outputs\apk\debug\clipboard-probe-debug.apk
```

The APK path may include a version suffix depending on AGP; use the file produced under
`tools/clipboard-probe/build/outputs/apk/debug/`.

## UI

Launch with `adb shell am start -n org.fcitx.fcitx5.android.probe/.MainActivity`:

- **a) Read clipboard length silently** – reads the current clip length on a background thread,
  without copying or showing the content;
- **b) Show last recorded events** – timestamp / length / MIME flags of the last 20 events;
- **c) Check foreground clipboard writer** – reports whether a clipboard write was observed in the
  last 5 seconds and whether the probe was in the foreground at that time;
- **Clear recorded events** – resets the in-memory log.

## adb telemetry endpoint

The read-only provider `org.fcitx.fcitx5.android.probe.events` exposes the same data:

```bash
adb shell content query --uri content://org.fcitx.fcitx5.android.probe.events/status
adb shell content query --uri content://org.fcitx.fcitx5.android.probe.events/events
adb shell content call  --uri content://org.fcitx.fcitx5.android.probe.events --method readCurrentClipboard
adb shell content call  --uri content://org.fcitx.fcitx5.android.probe.events --method clear
```

The provider is intentionally exported without a permission: its rows contain no clipboard text,
only timestamps, lengths and flags, and the app is installed on test devices only.
