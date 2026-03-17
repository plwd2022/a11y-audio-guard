# AudioChannelGuard 项目文档（核心版）

## 1. 项目定位

AudioChannelGuard（无障碍声道守护）是一个面向 Android 13+ 的通信音频路由纠偏工具。

它解决的问题不是“通话质量”本身，而是：在用户已连接耳机时，部分 App（如抖音、微信）通过 `setSpeakerphoneOn(true)` 把通信路由错误拉到扬声器，并且未及时释放，导致 TalkBack 等无障碍播报从耳机跳到外放，带来隐私泄露和使用中断。

项目核心能力是：

- 监听通信设备变化与耳机插拔事件
- 在检测到“被劫持到内建扬声器/听筒”时，调用 `setCommunicationDevice(headset)` 抢回耳机路由
- 提供前台服务常驻、快捷开关、手动一键修复

所有关键 API 为 Android 公开 API（API 33+），无需 root。

---

## 2. 问题根因（来自实测与日志）

根据 `research-report.md` 与 `developer-message.md` 的结论，问题因果链如下：

1. 社交 App 播放语音/视频时调用 `setSpeakerphoneOn(true)`
2. 系统创建 `CommunicationRouteClient(speaker)`
3. 客户端未正确释放，覆盖持续存在
4. AudioPolicy 在策略评估时让 `STRATEGY_ACCESSIBILITY` 跟随到 speaker
5. TalkBack 播报被错误输出到扬声器

附带现象：在某些机型可出现耳机与扬声器间反复振荡。

---

## 3. 起点验证：AudioTool.java

`AudioTool.java` 是项目的起点验证工具，用于在设备侧直接调用音频 API，完成“复现 → 修复 → 清理”的闭环验证。

### 3.1 工具能力

- `status`：查看当前输出设备与通信设备
- `break`：调用 `setSpeakerphoneOn(true)`，模拟劫持
- `fix`：调用 `setCommunicationDevice(headset)`，强制拉回耳机
- `clear`：调用 `clearCommunicationDevice()` 清理覆盖

### 3.2 编译与执行

```bash
javac -source 1.8 -target 1.8 -bootclasspath android.jar AudioTool.java
d8 --min-api 31 --output . AudioTool.class
adb push classes.dex /data/local/tmp/AudioTool.dex

adb shell CLASSPATH=/data/local/tmp/AudioTool.dex app_process / AudioTool status
adb shell CLASSPATH=/data/local/tmp/AudioTool.dex app_process / AudioTool break
adb shell CLASSPATH=/data/local/tmp/AudioTool.dex app_process / AudioTool fix
adb shell CLASSPATH=/data/local/tmp/AudioTool.dex app_process / AudioTool clear
```

### 3.3 关键结论

- 问题可稳定复现
- `setCommunicationDevice(headset)` 可稳定纠偏
- 方案具备工程化落地价值

---

## 4. App 实现架构

Gradle 工程根目录在 `app/`（不是仓库根目录）。

核心模块：

- `AudioRouteMonitor.kt`：守护引擎，监听路由变化、执行自动修复、维护日志
- `AudioGuardService.kt`：前台服务承载守护逻辑
- `ServiceGuard.kt`：WorkManager 保活
- `AudioFixTile.kt`：快捷设置一键修复
- `MainActivity.kt`：UI 开关、状态和日志展示
- `BootReceiver.kt`：开机与升级后自动拉起
- `AudioGuardApp.kt`：应用初始化、通知通道、签名校验

---

## 5. 路由守护策略演化

### 阶段 1：纯事件监听

- 依赖 `OnCommunicationDeviceChangedListener` + `AudioDeviceCallback`
- 功耗低，但后台某些 ROM 可能回调延迟

### 阶段 2：常驻 500ms 轮询

- 解决后台延迟问题
- 响应快，但功耗和干预强度偏高

### 阶段 3（当前）：事件驱动 + 恢复窗口轮询

当前实现已落地为“条件轮询”模型（见 `AudioRouteMonitor.kt`）：

- 默认不轮询
- 仅在高风险窗口临时轮询（500ms，最多约 6 秒）
- 连续稳定命中后提前退出（3 次）

触发恢复窗口场景：

1. 回调检测到通信设备被切到内建设备（扬声器/听筒）
2. 耳机刚接入，需要确认稳定
3. 用户手动点击“立即修复”
4. 启动时已处于内建设备

停止恢复窗口场景：

- 稳定命中达标
- 超时
- 无耳机
- 守护停止或耳机全部断开

---

## 6. 运行与发布

### 6.1 构建

```bash
cd app
./gradlew assembleDebug
./gradlew assembleRelease
```

Release APK 默认输出：`app/app/build/outputs/apk/release/app-release.apk`

### 6.2 安装

```bash
adb install -r app/app/build/outputs/apk/release/app-release.apk
```

若多设备连接，使用：

```bash
adb -s <serial> install -r app/app/build/outputs/apk/release/app-release.apk
```

### 6.3 日志观察

```bash
adb logcat -s AudioRouteMonitor AudioGuardService ServiceGuard AudioFixTile
```

重点观察：

- `进入恢复观察窗口`
- `退出恢复观察窗口`
- `已将声道恢复到 ...`

---

## 7. 安全与合规

- 项目包含发布签名能力，但签名文件和密码必须安全管理
- 生产环境建议通过环境变量或本地私有配置注入签名参数
- 应用包含双层签名校验（篡改防护），重签后可能无法正常运行

---

## 8. 已知边界

- 无自动化测试，当前以 ADB + 实机场景回归为主
- 行为受 ROM 音频策略差异影响，需持续通过日志调参
- 本项目是“通信路由纠偏方案”，不是官方 Accessibility 音量控制 API 替代

---

## 9. 维护建议（下一步）

1. 累积真实设备日志，持续评估恢复窗口参数
2. 补充“修复耗时/成功率”统计，形成量化指标
3. 在 README 保持准确对外口径：缓解无障碍播报路由异常
4. 后续按需引入轻量自动化验证（至少做构建与静态检查）

---

## 10. 参考资料

- `research-report.md`：问题与因果链完整分析
- `developer-message.md`：问题摘要与对外沟通版本
- `AudioTool.java`：设备侧 API 验证工具
- `AGENTS.md`：工程结构与开发约束
- `plwd_cn.keystore说明.md`：签名使用说明
