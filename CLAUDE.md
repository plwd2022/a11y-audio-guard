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

## Architecture

All source is in `app/app/src/main/kotlin/com/plwd/audiochannelguard/`. The app is a single-module Gradle project under `app/`.

### Core flow

1. **AudioRouteMonitor** — The central engine. Registers two listeners on `AudioManager`:
   - `OnCommunicationDeviceChangedListener`: fires when any app changes the communication device. If it changed to a built-in speaker/earpiece while a headset is connected, immediately calls `setCommunicationDevice(headset)` to restore routing.
   - `AudioDeviceCallback`: fires on headset connect/disconnect. On connect, checks if communication device is stuck on a built-in and fixes it.
   - Maintains an in-memory fix log (max 50 entries) and exposes `GuardStatus` enum (NORMAL/FIXED/NO_HEADSET).

2. **AudioGuardService** — Foreground service (`specialUse` type) that owns the `AudioRouteMonitor` instance. Exposes a static singleton pattern (`instance`, `getMonitor()`) so the UI and tile can access the monitor. Uses `START_STICKY` and delegates restart to `ServiceGuard` on task removal.

3. **ServiceGuard** — WorkManager-based keep-alive. Schedules a 15-minute periodic check and a one-shot 3-second restart when the service is killed. Only restarts if the guard is enabled in prefs.

4. **AudioFixTile** — Quick Settings tile. Tapping it calls `fixNow()` on the monitor (or starts the service if not running). Shows headset name and connection status.

5. **MainActivity** — Jetpack Compose UI. Toggle switch persists enabled state to `SharedPreferences` (`audio_guard_prefs` / `guard_enabled`). Registers `OnServiceRebindListener` callbacks to stay in sync with the monitor. Shows status, device names, and fix log.

6. **AudioGuardApp** — Application class. Creates the notification channel, verifies APK signature against a hardcoded SHA-256 hash (anti-tamper), and schedules the periodic keep-alive on startup if guard is enabled.

7. **BootReceiver** — Starts the service on `BOOT_COMPLETED` and `MY_PACKAGE_REPLACED` if guard is enabled.

### Key constants

- Supported headset types: `TYPE_WIRED_HEADSET`, `TYPE_WIRED_HEADPHONES`, `TYPE_BLUETOOTH_A2DP`, `TYPE_USB_HEADSET`, `TYPE_BLE_HEADSET`, `TYPE_BLE_SPEAKER` (defined in `AudioRouteMonitor.HEADSET_TYPES`)
- Built-in types that trigger fix: `TYPE_BUILTIN_SPEAKER`, `TYPE_BUILTIN_EARPIECE`
- SharedPreferences name: `audio_guard_prefs`, key: `guard_enabled`
- Notification channel ID: `audio_guard_channel`

## Tech Stack

- Kotlin, Jetpack Compose (BOM 2024.02), Material 3
- minSdk 31 (Android 12), targetSdk 34 (Android 14)
- AGP 8.2.2, Kotlin 1.9.22, Gradle 8.5
- WorkManager for service keep-alive
- R8/ProGuard enabled for release builds (shrink + minify)

## UI Language

The app UI is entirely in Chinese (Simplified). All user-facing strings are in Chinese. The log tag is `AudioRouteMonitor`.
