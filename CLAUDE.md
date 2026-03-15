# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

**AudioChannelGuard** (无障碍声道守护) is an Android app that fixes an audio routing bug on Android 14+ where apps like TikTok/WeChat hijack the communication audio route to the speaker via `setSpeakerphoneOn(true)` and never release it, breaking TalkBack output for visually impaired users who use headsets.

The app monitors communication device changes and automatically calls `setCommunicationDevice(headset)` to restore audio routing when hijack is detected. All APIs used are public (API 31+), no root required.

## Build Commands

The Gradle project root is `app/` (not the repo root). All Gradle commands must be run from there.

```bash
cd app

# Debug build
./gradlew assembleDebug

# Release build (requires signing env vars: SIGNING_STORE_FILE, SIGNING_STORE_PASSWORD, SIGNING_KEY_ALIAS, SIGNING_KEY_PASSWORD)
./gradlew assembleRelease

# Clean
./gradlew clean
```

There are no tests configured in this project.

### Signing alternatives

Release signing can be configured via environment variables (as above) **or** by copying `local.properties.example` to `app/local.properties` and filling in the keystore path and passwords.

### Debugging on device

```bash
# Install APK
adb install -r app/app/build/outputs/apk/release/app-release.apk

# Watch logs (key tags)
adb logcat -s AudioRouteMonitor AudioGuardService ServiceGuard AudioFixTile
```

Key log phrases: `进入恢复观察窗口`, `退出恢复观察窗口`, `已将声道恢复到`.

### AudioTool (ADB-side verification)

`AudioTool.java` at repo root is a standalone tool for reproducing and verifying the audio routing bug on-device.

```bash
# Compile & deploy
javac -source 1.8 -target 1.8 -bootclasspath android.jar AudioTool.java
d8 --min-api 31 --output . AudioTool.class
adb push classes.dex /data/local/tmp/AudioTool.dex

# Commands: status | break | fix | clear
adb shell CLASSPATH=/data/local/tmp/AudioTool.dex app_process / AudioTool status
adb shell CLASSPATH=/data/local/tmp/AudioTool.dex app_process / AudioTool break   # simulate hijack
adb shell CLASSPATH=/data/local/tmp/AudioTool.dex app_process / AudioTool fix     # restore headset
adb shell CLASSPATH=/data/local/tmp/AudioTool.dex app_process / AudioTool clear   # clear comm device
```

## Architecture

All source is in `app/app/src/main/kotlin/com/plwd/audiochannelguard/`. The app is a single-module Gradle project under `app/`.

### Monitoring strategy: event-driven + recovery window polling

The app does **not** poll continuously. It uses callbacks as the primary detection mechanism, with two short polling windows:
- **Recovery window** (6s, 500ms interval): activates when hijacking is detected, a headset connects, or a manual fix is triggered. Exits early once routing is confirmed stable for 3 consecutive checks.
- **Clear probe** (200ms, 100ms interval): a brief window used in enhanced mode after clearing the communication device, to detect if the system re-routes correctly before reacquiring `MODE_IN_COMMUNICATION` (250ms reacquire delay).

### Core components

1. **AudioRouteMonitor** — The central engine. Registers two listeners on `AudioManager`:
   - `OnCommunicationDeviceChangedListener`: fires when any app changes the communication device. If it changed to a built-in speaker/earpiece while a headset is connected, immediately calls `setCommunicationDevice(headset)` to restore routing.
   - `AudioDeviceCallback`: fires on headset connect/disconnect. On connect, checks if communication device is stuck on a built-in and fixes it.
   - Maintains an in-memory fix log (max 50 entries) and exposes `GuardStatus` enum (NORMAL / FIXED / FIXED_BUT_SPEAKER_ROUTE / NO_HEADSET / HIJACKED).
   - Supports **enhanced mode** (communication mode management): acquires `MODE_IN_COMMUNICATION` to strengthen routing, with automatic suspension during active calls (`MODE_IN_CALL`, `MODE_RINGTONE`). Exposes `EnhancedState` enum (DISABLED / WAITING_HEADSET / ACTIVE / CLEAR_PROBE / SUSPENDED_BY_CALL).

2. **AudioGuardService** — Foreground service (`specialUse` type) that owns the `AudioRouteMonitor` instance. Exposes a static singleton pattern (`instance`, `getMonitor()`) so the UI and tile can access the monitor. Uses `START_STICKY` and delegates restart to `ServiceGuard` on task removal.

3. **ServiceGuard** — WorkManager-based keep-alive. Schedules a 15-minute periodic check and a one-shot 3-second restart when the service is killed. Only restarts if the guard is enabled in prefs.

4. **AudioFixTile** — Quick Settings tile. Tapping it calls `fixNow()` on the monitor (or starts the service if not running). Shows headset name and connection status.

5. **MainActivity** — Jetpack Compose UI. Toggle switch persists enabled state to `SharedPreferences` (`audio_guard_prefs` / `guard_enabled`). Registers `OnServiceRebindListener` callbacks to stay in sync with the monitor. Shows status, device names, and fix log.

6. **PermissionChecker / PermissionGuideDialog** — Permission guidance system. `PermissionChecker` detects battery optimization, notification, auto-start, and background restriction status. Includes manufacturer-specific intent routing for Xiaomi/HyperOS, Huawei/HarmonyOS, OPPO/ColorOS, OnePlus, vivo, Samsung, Meizu with nested fallback chains. `PermissionGuideDialog` renders the Compose dialog with auto-refresh on resume and manual confirmation for permissions not detectable via API.

7. **AudioGuardApp** — Application class. Creates the notification channel, verifies APK signature against a hardcoded SHA-256 hash (anti-tamper with dual-layer verification), and schedules the periodic keep-alive on startup if guard is enabled.

8. **BootReceiver** — Starts the service on `BOOT_COMPLETED` and `MY_PACKAGE_REPLACED` if guard is enabled.

### Key constants

- Supported headset types: `TYPE_WIRED_HEADSET`, `TYPE_WIRED_HEADPHONES`, `TYPE_BLUETOOTH_A2DP`, `TYPE_BLUETOOTH_SCO`, `TYPE_USB_HEADSET`, `TYPE_BLE_HEADSET`, `TYPE_HEARING_AID` (defined in `AudioRouteMonitor.HEADSET_TYPES`)
- Built-in types that trigger fix: `TYPE_BUILTIN_SPEAKER`, `TYPE_BUILTIN_EARPIECE`
- SharedPreferences name: `audio_guard_prefs`, keys: `guard_enabled`, `enhanced_mode`, `tile_added`, `auto_start_confirmed`, `bg_restrict_confirmed`
- Notification channel ID: `audio_guard_channel`
- Recovery window: 6s duration, 500ms poll interval, 3 stable hits to exit

### Signing

Both debug and release builds use the same release signing config. The keystore (`plwd_cn.keystore`) is at repo root. The app has anti-tamper signature verification — re-signing with a different key will cause the app to refuse to function.

## CI/CD

GitHub Actions workflow (`.github/workflows/cross-repo-release.yml`) triggers on `v*` tags. It builds a release APK and publishes it as a GitHub Release to a separate public repo (`plwd2022/myk-fuxi-Publish`). Signing secrets are configured in the CI environment.

## Tech Stack

- Kotlin 1.9.22, Jetpack Compose (BOM 2024.02), Material 3
- minSdk 31 (Android 12), targetSdk 34 (Android 14), Java 17
- AGP 8.2.2, Gradle 8.5
- WorkManager 2.9.0 for service keep-alive
- R8/ProGuard enabled for release builds (shrink + minify)

## Development Constraints

- **UI language:** All user-facing strings are in Chinese (Simplified). Maintain Chinese for any new UI text.
- **Log tag:** `AudioRouteMonitor` is the primary log tag.
- **Foreground service:** Must call `startForeground()` within 5 seconds of `onCreate()` per Android requirements.
- **API 31+ only:** All `AudioManager` communication device APIs require Android 12+; do not add fallback paths for older APIs.
- **Signature verification is mandatory:** The app verifies its own APK signature at runtime. Re-signing with a different key will cause the app to refuse to function.
- **ProGuard keeps:** All manifest-declared components are kept in `app/app/proguard-rules.pro`. New components registered in the manifest must be added there.
