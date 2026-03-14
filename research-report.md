# Android 14+ 无障碍音频路由劫持问题研究报告

## 1. 问题描述

### 1.1 现象

在 Android 14 及以上版本中，当用户插着耳机（USB 耳机、蓝牙耳机或有线耳机）使用屏幕阅读器（TalkBack）时，打开微信、抖音等社交 App 播放语音消息或视频后，TalkBack 的语音输出会从耳机**错误地切换到手机扬声器**，导致：

- 无障碍语音从扬声器外放，**泄露用户隐私**
- 声音在扬声器和耳机之间**反复跳跃**（每 200-700ms 振荡一次）
- 即使关闭视频/语音播放，问题**持续存在**，不会自动恢复

### 1.2 影响范围

| 维度 | 范围 |
|------|------|
| 系统版本 | Android 14 及以上 |
| 耳机类型 | USB 耳机、蓝牙耳机、有线耳机均受影响 |
| 触发 App | 微信、抖音、QQ 等社交类 App |
| 受影响功能 | TalkBack、Switch Access 等所有走 `STRATEGY_ACCESSIBILITY` 的无障碍服务 |

---

## 2. 调试环境

| 项目 | 信息 |
|------|------|
| 设备 | 25060RK16C（MediaTek 平台） |
| 系统版本 | Android 14+ |
| 屏幕阅读器 | com.android.tback |
| TTS 引擎 | es.codefactory.vocalizertts (Vocalizer) |
| 测试耳机 | USB-Audio - H180 Plus (Type-C) |
| 触发 App | 抖音 com.ss.android.ugc.aweme |
| 调试工具 | adb logcat, dumpsys audio, 自研 AudioTool |

---

## 3. 根因分析

### 3.1 正常状态下的音频路由

通过 `adb logcat` 抓取正常状态下的音频日志（3308 行），确认正常路由链路：

```
TalkBack 创建 AudioTrack
  → AudioAttributes: usage=ASSISTANCE_ACCESSIBILITY(11), content=SPEECH(1), flags=0x900
  → stream=AUDIO_STREAM_ACCESSIBILITY(10)
  → APM getOutputForAttrInt() 返回 AUDIO_DEVICE_OUT_USB_HEADSET
  → 输出到 output handle 29 (fast mixer)
  → 设备: {AUDIO_DEVICE_OUT_USB_HEADSET, @:card=1;device=0}
```

**关键特征：**
- 所有 `getOutputForAttrInt()` 调用均返回 `USB_HEADSET`
- 全程无设备切换事件
- `Communication route clients:` 为空

### 3.2 异常状态下的音频路由

抓取异常状态日志（2414 行），发现路由被劫持：

```
TalkBack 创建 AudioTrack
  → 同样的 AudioAttributes
  → APM getOutputForAttrInt() 返回 AUDIO_DEVICE_OUT_SPEAKER  ← 这里变了
  → 输出到扬声器
```

**所有 `getOutputForAttrInt()` 调用均返回 SPEAKER，无一例外。**

### 3.3 根因：App 调用 `setSpeakerphoneOn(true)` 后未释放

在异常日志中找到铁证：

```
setCommunicationRouteForClient for uid: 10351
  device: AudioDeviceAttributes: role:output type:speaker
  from API: setSpeakerphoneOn(true)
  from u/pid: 10351/4441
```

**抖音（com.ss.android.ugc.aweme, UID 10351, PID 4441）调用了 `setSpeakerphoneOn(true)`**，在系统中创建了一个 `CommunicationRouteClient`：

```
CommunicationRouteClient:
  mDevice: type:speaker
  mPlaybackActive: true
  mRecordingActive: false   ← 并不在通话中
  mDisabled: false           ← 持续生效
```

### 3.4 因果链

```
App 播放语音消息，调用 setSpeakerphoneOn(true)
    ↓
AudioDeviceBroker 创建 CommunicationRouteClient(SPEAKER)
    ↓
AudioPolicyManager 调用 setPreferredDevicesForStrategyInt
  → strategy 0 (STRATEGY_MEDIA) → SPEAKER
  → strategy 3 (STRATEGY_ACCESSIBILITY) → 跟随 STRATEGY_MEDIA → SPEAKER
    ↓
TalkBack 创建 AudioTrack (USAGE_ASSISTANCE_ACCESSIBILITY)
    ↓
getDevicesForStrategy(STRATEGY_ACCESSIBILITY) → SPEAKER
    ↓
TalkBack 语音从扬声器输出
```

### 3.5 振荡现象的原因

日志显示音频设备在 SPEAKER 和 USB_HEADSET 之间反复切换（11+ 次/25 秒）：

**周期 A — TalkBack 停止说话时：**
```
所有 accessibility 流停止
→ MediaTek gainTable_routeAndApplyVolumeFromStopSource 重新评估
→ 无活跃 source → 路由回到 USB_HEADSET
→ [MTK_APM_Route] changing device 0x2 to 0x4000000, delayMs=84
```

**周期 B — TalkBack 开始说下一句时：**
```
startSource 重新评估路由
→ 命中 CommunicationRouteClient(SPEAKER)
→ 路由跳回 SPEAKER
→ [MTK_APM_Route] changing device 0x4000000 to 0x2, delayMs=0
```

### 3.6 App 存在的两个 Bug

**Bug 1：用完不释放**

App 播放完语音消息后，未调用 `clearCommunicationDevice()` 或 `setSpeakerphoneOn(false)` 释放 `CommunicationRouteClient`，导致路由覆盖持续生效。

**Bug 2：不判断耳机状态**

App 调用 `setSpeakerphoneOn(true)` 的本意是将语音消息从听筒切到扬声器。但用户插着耳机时，音频本来就不走听筒，此调用完全多余。App 未检查耳机连接状态就无条件调用。

---

## 4. 技术验证

### 4.1 验证工具

编写了 `AudioTool.java`，通过 `app_process` 在设备上直接调用 Android 音频 API：

```java
// 编译和部署
javac -source 1.8 -target 1.8 -bootclasspath android.jar AudioTool.java
d8 --min-api 31 --output . AudioTool.class
adb push classes.dex /data/local/tmp/AudioTool.dex

// 运行
adb shell CLASSPATH=/data/local/tmp/AudioTool.dex app_process / AudioTool <command>
```

支持的命令：
| 命令 | 功能 |
|------|------|
| `status` | 查看当前通信设备状态和输出设备列表 |
| `break` | 模拟 Bug：调用 `setSpeakerphoneOn(true)` |
| `fix` | 修复：调用 `setCommunicationDevice(headset)` |
| `clear` | 清除：调用 `clearCommunicationDevice()` |

### 4.2 验证结果

#### 步骤 1：确认正常基线

```
$ AudioTool status
isSpeakerphoneOn: false
Output devices:
  type=1  id=2      name=25060RK16C              (earpiece)
  type=2  id=3      name=25060RK16C              (speaker)
  type=18 id=13     name=25060RK16C              (builtin_speaker_safe)
  type=22 id=185461 name=USB-Audio - H180 Plus   (usb_headset)
CommunicationDevice: type=22 name=USB-Audio - H180 Plus (Type-C)
```

TalkBack 正常从 USB 耳机输出。

#### 步骤 2：模拟 Bug

```
$ AudioTool break
DONE: setSpeakerphoneOn(true) - simulated bug
Keeping process alive...
```

`dumpsys audio` 确认状态被污染：
```
Communication route clients:
  CommunicationRouteClient:
    mDevice: type:speaker
    mPlaybackActive: true
    mRecordingActive: false
    mDisabled: false

Active communication device: type:speaker
```

**实际验证：TalkBack 语音确实从扬声器输出，Bug 复现成功。**

#### 步骤 3：修复

```
$ AudioTool fix
setSpeakerphoneOn(false) called
setCommunicationDevice type=22 name=USB-Audio - H180 Plus (Type-C) result=true
Fix applied. Keeping process alive...
```

`dumpsys audio` 确认修复生效：
```
Communication route clients:
  [1] mDevice: type:usb_headset addr:card=1;device=0  ← fix 进程（优先级高）
  [2] mDevice: type:speaker                            ← break 进程（被压制）

Active communication device: type:usb_headset
```

**实际验证：TalkBack 语音恢复到 USB 耳机输出，修复成功。**

### 4.3 关键发现

| 发现 | 意义 |
|------|------|
| `CommunicationRouteClient` 绑定进程生命周期 | 进程退出后自动清理，App 进程常驻导致不释放 |
| 多个 Client 按优先级排序（最新的最高） | 我们的 fix 进程能压制住 App 的 speaker 设置 |
| `setCommunicationDevice()` 无需特殊权限 | 普通 App 即可调用，无需 root |
| `setSpeakerphoneOn(true)` 等效于 `setCommunicationDevice(speaker)` | 两者底层走同一个 CommunicationRouteClient 机制 |

---

## 5. 解决方案

### 5.1 方案设计：音频路由守护 App

开发一个后台监控 App，检测到无障碍音频被劫持到扬声器时自动恢复到耳机。

**核心 API（全部为公开 API，无需 root）：**

| API | 版本要求 | 权限 | 用途 |
|-----|---------|------|------|
| `AudioManager.addOnCommunicationDeviceChangedListener()` | API 31+ | 无 | 监听通信设备变化 |
| `AudioManager.setCommunicationDevice(AudioDeviceInfo)` | API 31+ | 无 | 将通信设备设回耳机 |
| `AudioManager.clearCommunicationDevice()` | API 31+ | 无 | 清除通信设备覆盖 |
| `AudioManager.getDevices(GET_DEVICES_OUTPUTS)` | API 23+ | 无 | 获取已连接的输出设备 |

**App 架构：**

```
┌─────────────────────────────────────────────┐
│                MainActivity                  │
│  - 启用/禁用守护开关                          │
│  - 显示当前音频路由状态                        │
│  - 日志查看                                   │
└─────────────────┬───────────────────────────┘
                  │
┌─────────────────▼───────────────────────────┐
│           ForegroundService                  │
│  - OnCommunicationDeviceChangedListener      │
│  - AudioDeviceCallback (耳机插拔检测)         │
│  - 自动修复逻辑:                              │
│    if (通信设备==SPEAKER && 耳机已连接)        │
│        setCommunicationDevice(耳机)           │
└─────────────────┬───────────────────────────┘
                  │
┌─────────────────▼───────────────────────────┐
│          Quick Settings Tile                 │
│  - 手动一键恢复音频路由                        │
└─────────────────────────────────────────────┘
```

**自动修复逻辑：**

```java
audioManager.addOnCommunicationDeviceChangedListener(executor, device -> {
    if (device != null && device.getType() == TYPE_BUILTIN_SPEAKER) {
        AudioDeviceInfo headset = findConnectedHeadset();
        if (headset != null) {
            audioManager.setCommunicationDevice(headset);
            log("音频路由被劫持到扬声器，已自动恢复到: " + headset.getProductName());
        }
    }
});
```

### 5.2 对其他 App 的影响

**无影响。** 我们的介入条件是"插着耳机 + 通信设备被切到扬声器"，在此条件下：

- App 调用 `setSpeakerphoneOn(true)` 的本意是从听筒切到扬声器
- 插着耳机时音频不走听筒，该调用本身多余
- 我们将通信设备改回耳机，与用户预期一致
- 不插耳机时完全不介入

### 5.3 技术栈

| 项目 | 选择 |
|------|------|
| 语言 | Kotlin |
| UI | Jetpack Compose |
| 构建 | Gradle + AGP |
| minSdk | 31 (Android 12) |
| targetSdk | 34 (Android 14) |

---

## 6. 相关日志文件

| 文件 | 说明 |
|------|------|
| `normal.log` | 正常状态下的音频日志（3308 行） |
| `broken.log` | 异常状态下的音频日志（2414 行） |
| `AudioTool.java` | 验证工具源码 |

---

## 7. 建议

### 对 App 开发者

1. 调用 `setSpeakerphoneOn(true)` / `setCommunicationDevice()` 前，检查是否有外部音频设备连接
2. 播放完成后及时调用 `setSpeakerphoneOn(false)` / `clearCommunicationDevice()` 释放资源
3. 在 `onPause()` / `onStop()` 中清理通信设备设置

### 对 Google / AOSP

1. `STRATEGY_ACCESSIBILITY` 不应盲目跟随 `CommunicationRouteClient` 的设备覆盖
2. 当外部音频设备连接时，无障碍音频流应强制跟随物理输出设备
3. 考虑为无障碍音频流增加独立的路由策略，不受通信设备覆盖影响

### 对屏幕阅读器开发者

1. 可使用 `AudioTrack.setPreferredDevice(AudioDeviceInfo)` 显式锁定输出设备
2. 这是 per-track 级别的覆盖，直接绕过 AudioPolicyManager 的策略决策
3. 不受任何 App 的 `setCommunicationDevice` 影响

---

*报告日期：2026-03-14*
*测试人员：用户 + Claude Code*
*验证结果：方案技术可行性已通过 ADB 端到端验证*
