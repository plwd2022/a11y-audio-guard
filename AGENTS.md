# AGENTS.md - AudioChannelGuard (无障碍声道守护)

This file provides essential information for AI coding agents working on the AudioChannelGuard project.

## Project Overview

**AudioChannelGuard** is an Android accessibility app that fixes an audio routing bug on Android 13+ where apps like TikTok (抖音) and WeChat hijack the communication audio route to the speaker via `setSpeakerphoneOn(true)` and never release it. This breaks TalkBack output for visually impaired users who use headsets.

The app monitors communication device changes and automatically calls `setCommunicationDevice(headset)` to restore audio routing when hijacking is detected. All APIs used are public (API 31+), no root required.

### Key Features
- Real-time monitoring of audio communication device changes
- Automatic restoration of audio routing to headset when hijacked
- Quick Settings tile for manual one-click fix
- Foreground service with keep-alive mechanisms
- Anti-tamper signature verification

## Project Structure

```
D:\git\k\acc/
├── app/                          # Gradle project root (NOT repo root)
│   ├── app/
│   │   ├── build.gradle.kts      # App module build configuration
│   │   ├── proguard-rules.pro    # ProGuard keep rules
│   │   └── src/main/
│   │       ├── AndroidManifest.xml
│   │       ├── kotlin/com/plwd/audiochannelguard/
│   │       │   ├── AudioGuardApp.kt      # Application class with signature verification
│   │       │   ├── AudioGuardService.kt  # Foreground service
│   │       │   ├── AudioRouteMonitor.kt  # Core audio monitoring engine
│   │       │   ├── MainActivity.kt       # Jetpack Compose UI
│   │       │   ├── AudioFixTile.kt       # Quick Settings tile
│   │       │   ├── ServiceGuard.kt       # WorkManager keep-alive
│   │       │   └── BootReceiver.kt       # Auto-start on boot
│   │       └── res/
│   │           ├── drawable/ic_headset.xml
│   │           └── values/strings.xml
│   ├── build.gradle.kts          # Root build configuration (plugins)
│   ├── settings.gradle.kts       # Project settings
│   ├── gradle.properties         # Gradle properties
│   └── gradle/                   # Gradle wrapper
├── AudioTool.java                # Standalone debugging tool
├── plwd_cn.keystore              # Release signing keystore
├── plwd_cn.keystore说明.md       # Keystore documentation (Chinese)
├── local.properties.example      # Example local properties
├── research-report.md            # Technical research report (Chinese)
├── developer-message.md          # Developer message (Chinese)
└── CLAUDE.md                     # Original Claude guidance
```

## Technology Stack

| Component | Version |
|-----------|---------|
| Language | Kotlin 1.9.22 |
| Build System | Gradle 8.5 + AGP 8.2.2 |
| minSdk | 31 (Android 12) |
| targetSdk | 34 (Android 14) |
| compileSdk | 34 |
| UI | Jetpack Compose BOM 2024.02 |
| Java | 17 |

### Dependencies
- Jetpack Compose (UI, Material3)
- AndroidX Activity Compose 1.8.2
- AndroidX Lifecycle Runtime KTX 2.7.0
- AndroidX Core KTX 1.12.0
- WorkManager 2.9.0 (keep-alive)

## Build Commands

**Important:** All Gradle commands must be run from the `app/` directory, NOT the repo root.

```bash
cd app

# Debug build
./gradlew assembleDebug

# Release build (requires signing configuration)
./gradlew assembleRelease

# Clean build
./gradlew clean
```

### Signing Configuration

Release builds require signing configuration via either:
1. Environment variables: `SIGNING_STORE_FILE`, `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS`, `SIGNING_KEY_PASSWORD`
2. `local.properties` file (copy from `local.properties.example`)

The keystore file `plwd_cn.keystore` is located at repo root.

## Architecture

### Core Components

1. **AudioRouteMonitor** (`AudioRouteMonitor.kt`)
   - Central monitoring engine
   - Registers `OnCommunicationDeviceChangedListener` for device change detection
   - Registers `AudioDeviceCallback` for headset connect/disconnect events
   - Maintains in-memory fix log (max 50 entries)
   - Exposes `GuardStatus` enum: `NORMAL`, `FIXED`, `NO_HEADSET`, `HIJACKED`
   - 500ms polling for background detection

2. **AudioGuardService** (`AudioGuardService.kt`)
   - Foreground service with `specialUse` type
   - Owns the `AudioRouteMonitor` instance
   - Static singleton pattern for UI/tile access
   - Uses `START_STICKY` for restart behavior
   - Delegates restart to `ServiceGuard` on task removal

3. **ServiceGuard** (`ServiceGuard.kt`)
   - WorkManager-based keep-alive
   - 15-minute periodic check
   - 3-second one-shot restart when service is killed
   - Only restarts if guard is enabled in preferences

4. **AudioFixTile** (`AudioFixTile.kt`)
   - Quick Settings tile service
   - Tapping calls `fixNow()` or starts service
   - Shows headset name and connection status

5. **MainActivity** (`MainActivity.kt`)
   - Jetpack Compose UI
   - Toggle switch persists to `SharedPreferences`
   - Shows status, device names, and fix log
   - About dialog with GitHub link

6. **AudioGuardApp** (`AudioGuardApp.kt`)
   - Application class
   - Creates notification channel
   - Dual-layer APK signature verification (anti-tamper)
   - Schedules periodic keep-alive on startup

7. **BootReceiver** (`BootReceiver.kt`)
   - Auto-starts service on `BOOT_COMPLETED` and `MY_PACKAGE_REPLACED`

### Key Constants

```kotlin
// Supported headset types (AudioRouteMonitor.HEADSET_TYPES)
TYPE_WIRED_HEADSET      // 3
TYPE_WIRED_HEADPHONES   // 4
TYPE_BLUETOOTH_A2DP     // 8
TYPE_BLUETOOTH_SCO      // 7
TYPE_USB_HEADSET        // 22
TYPE_BLE_HEADSET        // 26

// Built-in types that trigger fix
TYPE_BUILTIN_SPEAKER
TYPE_BUILTIN_EARPIECE

// SharedPreferences
PREFS_NAME = "audio_guard_prefs"
KEY_ENABLED = "guard_enabled"

// Notification
CHANNEL_ID = "audio_guard_channel"
```

## Code Style Guidelines

- **Language:** All user-facing strings are in Chinese (Simplified)
- **Log tag:** `AudioRouteMonitor` for audio-related logs
- **Package:** `com.plwd.audiochannelguard`
- **Indent:** 4 spaces
- **Kotlin style:** Official Kotlin code style (`kotlin.code.style=official`)

## Testing

There are **no automated tests** configured in this project.

For manual testing, use the included `AudioTool.java`:

```bash
# Compile and deploy
javac -source 1.8 -target 1.8 -bootclasspath android.jar AudioTool.java
d8 --min-api 31 --output . AudioTool.class
adb push classes.dex /data/local/tmp/AudioTool.dex

# Run commands
adb shell CLASSPATH=/data/local/tmp/AudioTool.dex app_process / AudioTool <command>

# Available commands:
#   status  - View current communication device status
#   break   - Simulate bug: call setSpeakerphoneOn(true)
#   fix     - Fix: call setCommunicationDevice(headset)
#   clear   - Clear: call clearCommunicationDevice()
```

## Security Considerations

### APK Signature Verification

The app implements dual-layer anti-tamper verification in `AudioGuardApp`:

1. **Layer 1:** PackageManager API verification
2. **Layer 2:** Direct APK file V2/V3 signing block parsing (bypasses Java API hooks like MT Manager)

Expected certificate SHA-256 hash is hardcoded:
```kotlin
EXPECTED_CERT_HASH = "222b4c298ca06cb38792288d3b5bfa5c77c00e423cc2ffc0b024b185e447fb52"
```

If verification fails, the app shows a toast message and refuses to function.

### Keystore Security

- Release keystore (`plwd_cn.keystore`) is stored at repo root
- Passwords are documented in `plwd_cn.keystore说明.md`
- `local.properties` is gitignored to prevent credential leakage

## Deployment

### Release APK Location
```
app/app/build/outputs/apk/release/app-release.apk
```

### ProGuard Rules
The file `app/app/proguard-rules.pro` keeps all manifest-declared components:
- `AudioGuardApp`
- `MainActivity`
- `AudioGuardService`
- `AudioFixTile`
- `BootReceiver`
- `ServiceGuard`

## Documentation Files

| File | Description |
|------|-------------|
| `research-report.md` | Detailed technical analysis of the Android 13+ audio routing bug (Chinese) |
| `developer-message.md` | Developer communication explaining the bug root cause (Chinese) |
| `plwd_cn.keystore说明.md` | Keystore usage instructions (Chinese) |
| `CLAUDE.md` | Original Claude Code guidance |

## Important Notes for Agents

1. **Gradle root is `app/`, not repo root** - Always `cd app` before running Gradle commands
2. **No tests exist** - Do not attempt to run test commands
3. **UI is Chinese-only** - Maintain Chinese for all user-facing strings
4. **Signature verification is mandatory** - The app will not function if the APK is re-signed
5. **Foreground service requires notification** - Service must call `startForeground()` within 5 seconds
6. **API 31+ only** - All audio APIs require Android 12 or higher
7. **Directly request escalation for likely-privileged commands** - For commands such as Gradle builds, `git add`/`git commit`/`git push`, APK installation, or other operations that may require elevated permissions in this environment, request escalated execution immediately instead of first trying a non-escalated run.
