# AudioChannelGuard（无障碍声道守护）

在 Android 13+ 上，部分 App（如抖音、微信）可能通过 `setSpeakerphoneOn(true)` 将通信路由错误拉到扬声器且不释放，导致 TalkBack 等无障碍播报从耳机跳到外放。
AudioChannelGuard 用公开 API（Android 13+）自动将通信设备纠偏回耳机，缓解这一问题。

## 功能简介

- 实时监听通信设备变化（`OnCommunicationDeviceChangedListener`）
- 监听耳机插拔（`AudioDeviceCallback`）
- 检测到路由被劫持到扬声器/听筒时自动修复
- 快捷设置磁贴一键修复
- 前台服务 + WorkManager 保活
- 仅使用公开 API，无需 root

## 当前策略

项目当前采用：**事件驱动为主 + 短时恢复窗口轮询兜底**

- 默认不常驻轮询
- 仅在高风险窗口临时轮询（如检测到被切内建设备、耳机刚接入、手动修复后）
- 稳定后自动退出恢复窗口

这样比“纯回调”更稳，也比“永久 500ms 常驻轮询”更省电。

## 适用范围

- 系统：Android 13+
- 重点场景：Android 13+ 无障碍播报路由异常
- 耳机类型：有线、USB、蓝牙（含 BLE）

## 技术原理（简版）

1. 问题 App 调用 `setSpeakerphoneOn(true)`
2. 系统保留 `CommunicationRouteClient(speaker)`
3. 无障碍流策略可能被带到 speaker
4. 本项目监听到异常后调用 `setCommunicationDevice(headset)` 抢回耳机路由

详细分析见 `research-report.md`。

## 快速开始

### 1) 构建

> Gradle 根目录是 `app/`，不是仓库根目录。

```bash
cd app
./gradlew assembleDebug
./gradlew assembleRelease
```

Release APK 输出路径：

`app/app/build/outputs/apk/release/app-release.apk`

### 2) 安装

```bash
adb install -r app/app/build/outputs/apk/release/app-release.apk
```

多设备连接时：

```bash
adb -s <serial> install -r app/app/build/outputs/apk/release/app-release.apk
```

### 3) 观察日志

```bash
adb logcat -s AudioRouteMonitor AudioGuardService ServiceGuard AudioFixTile
```

重点关键字：

- `进入恢复观察窗口`
- `退出恢复观察窗口`
- `已将声道恢复到`

## 项目结构

```text
acc/
├── app/                                # Gradle 工程根目录
│   └── app/src/main/kotlin/com/plwd/audiochannelguard/
│       ├── AudioGuardApp.kt            # 应用初始化与签名校验
│       ├── AudioGuardService.kt        # 前台守护服务
│       ├── AudioRouteMonitor.kt        # 核心路由监控与修复
│       ├── AudioFixTile.kt             # 快捷设置磁贴
│       ├── ServiceGuard.kt             # WorkManager 保活
│       ├── BootReceiver.kt             # 开机/升级后拉起
│       └── MainActivity.kt             # UI
├── AudioTool.java                      # ADB 侧验证工具
├── research-report.md                  # 根因与验证报告
└── developer-message.md                # 对外沟通摘要
```

## AudioTool 验证工具

`AudioTool.java` 用于在设备侧直接验证“复现 → 修复 → 清理”闭环。

### 编译与部署

```bash
javac -source 1.8 -target 1.8 -bootclasspath android.jar AudioTool.java
d8 --min-api 31 --output . AudioTool.class
adb push classes.dex /data/local/tmp/AudioTool.dex
```

### 命令

```bash
adb shell CLASSPATH=/data/local/tmp/AudioTool.dex app_process / AudioTool status
adb shell CLASSPATH=/data/local/tmp/AudioTool.dex app_process / AudioTool break
adb shell CLASSPATH=/data/local/tmp/AudioTool.dex app_process / AudioTool fix
adb shell CLASSPATH=/data/local/tmp/AudioTool.dex app_process / AudioTool clear
```

## 重要说明

- 这是“通信路由纠偏”方案，不是官方 Accessibility 音量 API 的替代实现
- 当前项目无自动化测试，主要依赖实机 + ADB 回归
- UI 用户文案以中文为主

## 安全与签名

- Release 构建需要签名配置（环境变量或 `local.properties`）
- 项目包含签名完整性校验逻辑，重签后可能无法正常运行
- 请勿将 keystore 与密码上传到公开仓库

## 相关文档

- `research-report.md`：完整技术分析与日志证据
- `developer-message.md`：问题摘要版本
- `project-overview.md`：项目核心说明文档
- `AGENTS.md`：工程与开发约束

## License

见 `LICENSE`。
