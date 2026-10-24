# AudioChannelGuard 的 Android 音频 API 与方案优化研究

核验日期：2026-10-23。范围：当前 `acc/` 工作区，以及 Android 13、14、15、16 的首发 AOSP 标签、主线参考实现、CTS、Android Developers（含 Android 17 行为变化及新 API）和 Google TalkBack 源码。本文给出方案研究和验证顺序，没有改变应用运行代码。

## 1. 建议采用的方向

独立、普通权限的 APK 可以把**检测**做得更轻，但无法获得可靠控制其他应用全部无障碍音轨的公开 API。现有增强模式通过通信设备和通信模式间接改变无障碍策略，其复杂度有平台机制上的原因。

建议先做两件事：

1. 普通权限路径改为“播放事件触发 + 无障碍预计路由查询 + 有界的纠偏会话”。把静音探测降为有设备证据的兼容分支；通信模式保活只留给用户明确选择的兼容模式。
2. 单独验证用户显式授权的 ADB/Shizuku 路径：直接订阅无障碍属性路由、设置无障碍策略的首选设备。这条路径有机会删除通信模式保活和大部分主动探测，但必须先验证 ROM 权限、内部策略覆盖、蓝牙并发以及退出后的还原。

如果能修改读屏器，长期最直接的方式是让读屏器持有实际播音的 `AudioTrack`，对自己的语音流选择设备。它需要读屏端配合，不能由 `acc` 单方面替换系统 TalkBack 的播放器。

以上后两项是基于文档和源码的设计推论，并非已通过当前设备回归的替代实现。

## 2. 决定架构的六个事实

### 2.1 通信设备会影响无障碍策略，这是 AOSP 的明确实现

Android 13—16 的 `AudioDeviceBroker` 都查找通信策略和无障碍策略。在 `updateCommunicationRoute()` 中，选中的通信设备会同时应用到两者。Android 16 中可见 `mCommunicationStrategyId` 与 `mAccessibilityStrategyId` 的成对更新。

所以项目通过通信设备恢复读屏音频具有明确的实现基础；同时，它也会参与真实通信音频的选择。不能把所有跟随通信设备的行为归因为小米特有缺陷，也不能把扬声器通信设备直接当作读屏已经外放的证明。

证据：[Android 13 AudioDeviceBroker](https://android.googlesource.com/platform/frameworks/base/+/android-13.0.0_r1/services/core/java/com/android/server/audio/AudioDeviceBroker.java)、[Android 16 AudioDeviceBroker](https://android.googlesource.com/platform/frameworks/base/+/android-16.0.0_r1/services/core/java/com/android/server/audio/AudioDeviceBroker.java)。关键函数：`initRoutingStrategyIds()`、`updateCommunicationRoute()`。

### 2.2 约 6 秒后的失效可能是平台主动仲裁

这里有两种不同的机制：

- `AudioService` 对非特权应用的 `MODE_IN_COMMUNICATION` 请求检查播放/录音活动。音频模式的播放活动判定关注通信相关 usage；没有相应活动，应用可能失去模式所有权。
- 较新的 `AudioDeviceBroker` 还检查通信路由客户端的活动状态，包含约 6 秒的宽限期。客户端活跃并不等于它正在真实通话，播放与录音活动也是参与因素。

当前 `CommunicationModeKeepAlive` 正是通过通信 usage 音轨维持第一种条件。它虽然播放全零 PCM，仍会制造真实的播放活动，影响系统的模式仲裁；更换 `Handler`、协程或定时器不能消除这个机制。

Android 16 CTS 的 `testSetCommunicationDeviceDeniedNotModeOwnerNotPrivileged()` 明确容许非模式所有者、非特权应用的切换无效，或者短暂生效后回退；注释区分了 Android 14 与 14 QPR1 后的迁移行为。因此不能把暂时成功、数秒后回落一概视为需要更快重试的系统故障。

证据：[AudioService](https://android.googlesource.com/platform/frameworks/base/+/android-16.0.0_r1/services/core/java/com/android/server/audio/AudioService.java) 的 `CHECK_MODE_FOR_UID_PERIOD_MS`、`updateAudioModeHandlers()`、`SetModeDeathHandler.isActive()`；[CTS 通信设备测试](https://android.googlesource.com/platform/cts/+/android-16.0.0_r1/tests/tests/media/audio/src/android/media/audio/cts/AudioCommunicationDeviceTest.java)。

### 2.3 通信设备、预计无障碍路由、实际音轨路由应分开判断

`getCommunicationDevice()` 描述通信设备。AOSP 在没有适用通信设备时还可能回退到听筒等默认设备，因此内建设备这个结果本身不足以确认读屏声音正在外放。

Android 13 的 `getAudioDevicesForAttributes()` 查询指定属性的预计路由，不要求 `MODIFY_AUDIO_ROUTING`。它适合作为无障碍路由的独立证据，返回空列表时应记为未知，不能记为正常。它没有提供 TalkBack 某条既有音轨的身份、播放状态或保证，也不能完整复现其他播放器的显式首选设备、所有私有属性和厂商行为。

`AudioRouting.getRoutedDevice()` 查询对应播放器自己的路由，必须结合其播放状态理解。我们的静音音轨不是 TalkBack 的音轨。Android 16 新增的 `getRoutedDevices()` 支持查询同一播放器的多设备输出，仍没有赋予控制其他播放器的能力。

证据：[公开属性路由查询](https://developer.android.com/reference/android/media/AudioManager#getAudioDevicesForAttributes(android.media.AudioAttributes))、[AudioRouting](https://developer.android.com/reference/android/media/AudioRouting)、[AudioDeviceBroker 的默认设备处理](https://android.googlesource.com/platform/frameworks/base/+/android-16.0.0_r1/services/core/java/com/android/server/audio/AudioDeviceBroker.java)。

### 2.4 公开播放回调能减少主动探测，但拿不到真实设备与应用身份

`registerAudioPlaybackCallback()` 可向普通应用提供匿名化的活跃播放配置。AOSP 保留 usage、content type 和公开 flags，因此可以将无障碍播放活动作为触发查询的信号。

匿名化会去掉 UID/PID、播放器控制接口、真实设备 ID 和会话信息。不能据此准确指认某个干扰应用，也不能用它拿到 TalkBack 的真实输出设备。公开的 `AudioPlaybackConfiguration.getAudioDeviceInfo()` 在 API 36 被废弃，官方说明该信息从未被填充。新的设备列表接口仍有系统权限限制。

建议用播放回调唤起一次状态重评，而不是依靠它证明外放或精确识别 VoIP。回调缺失、迟到和结束状态也应通过会话截止时间与重新查询处理。

证据：[PlaybackActivityMonitor](https://android.googlesource.com/platform/frameworks/base/+/android-16.0.0_r1/services/core/java/com/android/server/audio/PlaybackActivityMonitor.java) 的 `dispatchPlaybackChange()`、`anonymizeForPublicConsumption()`；[AudioPlaybackConfiguration 源码](https://android.googlesource.com/platform/frameworks/base/+/android-16.0.0_r1/media/java/android/media/AudioPlaybackConfiguration.java) 的 `anonymizedCopy()`；[公开 API 文档](https://developer.android.com/reference/android/media/AudioPlaybackConfiguration#getAudioDeviceInfo())。

### 2.5 真正的属性路由监听与策略选设备接口需要额外权限

`addOnDevicesForAttributesChangedListener()` 可以直接监听指定属性的路由变化，但属于 System API。Android 16 的权限检查要求 `MODIFY_AUDIO_ROUTING` 或 `QUERY_AUDIO_STATE`；设置策略首选设备需要 `MODIFY_AUDIO_ROUTING`。这些不是普通运行时权限，在 Manifest 里声明或引导用户点“允许”并不能取得。

AOSP Android 13—16 的 Shell Manifest 都声明了 `MODIFY_AUDIO_ROUTING`、`QUERY_AUDIO_STATE`。这为用户授权的 shell 身份后端提供了可研究的权限基础。Shizuku 官方文档说明其调用可以使用 ADB/shell 或 root 身份，也强调不同系统版本的权限和执行限制不同。普通 APK 本身仍不会因此自动获得这些权限。

证据：[AudioManager 的 System API 与权限标注](https://android.googlesource.com/platform/frameworks/base/+/android-16.0.0_r1/media/java/android/media/AudioManager.java)、[AudioService 的服务端检查](https://android.googlesource.com/platform/frameworks/base/+/android-16.0.0_r1/services/core/java/com/android/server/audio/AudioService.java)、[权限定义](https://android.googlesource.com/platform/frameworks/base/+/android-16.0.0_r1/core/res/AndroidManifest.xml)、[Shell Manifest](https://android.googlesource.com/platform/frameworks/base/+/android-16.0.0_r1/packages/Shell/AndroidManifest.xml)、[Shizuku 官方开发文档](https://github.com/RikkaApps/Shizuku-API/blob/master/README.md)。

### 2.6 修复时间预算应区分设备类型与操作阶段

官方 BLE Audio 指南建议在 `setCommunicationDevice()` 接受请求后等待目标设备成为当前设备，示例最大等待时间为 30 秒。这个上限不是所有设备都应固定等待 30 秒，更不是允许读屏语音外放 30 秒；它说明蓝牙建立过程不能统一按几百毫秒或 6 秒判失败并重置。

应区分“切换仍在建立”“明确拒绝”“完成后又被改走”“完全未知”。可保留较短的有线设备验证窗口，对蓝牙建立使用完成回调和有界截止时间，避免不断 clear/set 打断连接。

证据：[Audio Manager self-managed call guide](https://developer.android.com/develop/connectivity/bluetooth/ble-audio/audio-manager)。

## 3. API 能力与替代价值

| API 或机制 | 范围与条件 | 对本项目的价值 |
|---|---|---|
| `getAudioDevicesForAttributes()` | 公开，API 33+，查询属性预计路由 | 提升为主要检测证据；目前代码仅在多耳机选择时使用，范围过窄 |
| `registerAudioPlaybackCallback()` | 公开，API 26+；普通应用收到匿名活跃配置 | 在无障碍播放和通信播放活动变化时触发查询，减少常驻探测 |
| `AudioDeviceCallback` | 设备接入/移除 | 继续使用；它不是所有软件路由变化的通用监听器 |
| `OnCommunicationDeviceChangedListener` | 公开，API 31+，通信设备变化 | 继续使用；不直接代表 TalkBack 的实际音轨变化 |
| `OnModeChangedListener` | 公开，API 31+，全局模式变化 | 暂停、恢复和记录线索；不能识别全部网络通话或模式所有者 |
| `setCommunicationDevice()` | 公开，API 31+；受可用设备及仲裁约束 | 保留为普通权限纠偏执行器，不能承诺持续压过其他应用 |
| `clearCommunicationDevice()` | 释放调用方先前的通信设备选择 | 只在本应用持有请求时作为释放操作，不是清空其他应用的总开关 |
| `AudioTrack.setPreferredDevice()` | 公开，API 23+，对应自己的音轨 | 最适合读屏端直接控制实际语音；不能把旁路静音音轨当作全局设备锁 |
| `AudioRouting.getRoutedDevices()` | API 36+，对应自己的活跃播放器 | 诊断多设备输出；不能解决跨应用控制权限 |
| `addOnDevicesForAttributesChangedListener()` | System API；需要路由或查询权限 | 授权后端可代替主动探测与轮询的一部分 |
| `setPreferredDevicesForStrategy()` | System API；需要路由权限 | 授权后端直接指定无障碍策略，避免借用通信策略 |
| `getAudioDeviceInfo()` on playback config | API 31 加入、API 36 废弃 | 不作为替代探测方案 |
| `setAudioAttributes()` on TTS | 控制自己的 TTS 请求属性 | 没有为另一个客户端的 TTS 提供设备选择权 |
| `synthesizeToFile()` + `onAudioAvailable()` | 自己发起的合成请求 | 读屏端可取得 PCM，再交由自己的播放器选择设备 |
| `MediaRouter2` | 媒体路由与路由会话；跨应用控制有权限要求 | 不能替换任意 TalkBack/TTS 音轨的策略控制 |
| Core-Telecom / `CallsManager` | 管理本应用真实的通信会话 | 不应通过创建虚假通话取得路由优先级 |
| `setPreferredMixerAttributes()` | API 34+；当前文档主要针对 USB 媒体播放 | 改善本应用媒体混音/格式，不能作为全局无障碍路由修复 |
| AAudio / Oboe `setDeviceId` | 本应用创建的原生音频流 | 有利于自己的播放器；不会扩大跨应用控制权限 |
| `FLAG_ENABLE_ACCESSIBILITY_VOLUME` | 无障碍音量流选择 | 控制音量分组，不提供耳机锁定功能 |
| AudioPlaybackCapture / MediaProjection | 公开抓取主要限 MEDIA/GAME/UNKNOWN 等条件 | 不能抓取正常的无障碍 usage 后重播，因此不是旁路接管语音的通路 |

补充来源：[TTS](https://developer.android.com/reference/android/speech/tts/TextToSpeech)、[UtteranceProgressListener](https://developer.android.com/reference/android/speech/tts/UtteranceProgressListener)、[USB 混音属性](https://developer.android.com/media/platform/improve-audio-playback)、[MediaRouter2](https://developer.android.com/reference/android/media/MediaRouter2)、[Core-Telecom](https://developer.android.com/develop/connectivity/telecom/voip-app/telecom)、[AAudio](https://developer.android.com/ndk/guides/audio/aaudio/aaudio)、[无障碍音量标记](https://developer.android.com/reference/android/accessibilityservice/AccessibilityServiceInfo#FLAG_ENABLE_ACCESSIBILITY_VOLUME)、[播放捕获限制](https://developer.android.com/reference/android/media/AudioPlaybackCaptureConfiguration)。

### 3.1 Android 17：前台服务并不总能保证后台音频能力

Android 17 的后台音频限制分两层：所有运行于 Android 17 的应用，受影响的音频交互一般要求可见 Activity 或非 `shortService` 前台服务；目标 API 37 的应用还要求后台前台服务具备 while-in-use 能力。用户在可见界面或明确通知操作中启动的服务与从后台启动的服务，需要分别检查。

受影响列表明确包含 `AudioTrack.write()`、AAudio/OpenSL ES、音频焦点及音量操作。不能仅因有这个新限制，就声称同一限制直接禁止 `setCommunicationDevice()`：该页面没有这样列举。音频播放受限可能被静默丢弃，因此音轨已启动、写入线程还活着不等于静音保活有效。

当前应用 targetSdk 34，已有 `specialUse` 前台服务；不能据此断言它立即失效。真正需要提前验证的是 Android 17 上的后台重建，以及未来升到 target 37 后从开机/Worker 等来源启动的服务。该变化进一步降低了长期依赖静音音轨维持仲裁资格的吸引力。

证据：[Android 17 后台音频加固](https://developer.android.com/about/versions/17/changes/bg-audio)。验证时检查 `AudioHardening` 日志，分别覆盖界面启动、通知启动、后台恢复；测试覆盖的强制报错行为不能等同于默认静默抑制行为。

### 3.2 新助手 API 与跨应用媒体代理也不是通用无障碍路由锁

最新 `AudioManager` 文档出现 `MODE_ASSISTANT_CONVERSATION`（36.1）、`STREAM_ASSISTANT`（37），以及 `canManageAssistantAudio()`（37.2）。后者的管理授权针对助手音频；通信模式说明明确关联交互式助手和 `USAGE_ASSISTANT`。不能将其替换成无障碍 usage 的通用权限，也不能假设这些季度版本能力都存在于基础 API 37 或现有 ROM。

`MediaRouter2.getInstance(Context, String, Executor, Runnable)` 从 API 35 提供经授权的跨应用媒体路由代理，因此说 MediaRouter2 只能控制自身应用是不准确的。但是它要求 `MEDIA_CONTENT_CONTROL` 或 `MEDIA_ROUTING_CONTROL`；后者官方说明仅提供给 `COMPANION_DEVICE_WATCH` 角色，不是普通 APK 可自行申请的通用开关。

Android 16 的系统路由控制实现还明确使用媒体产品策略：`DeviceRouteController.createInstance()` 通过 `AudioRoutingUtils.getMediaAudioProductStrategy()` 创建控制器。因此不能从跨应用代理能力推导出它能固定任意 TalkBack 无障碍音轨。没有找到适用于本项目普通权限部署方式的替代契约。

证据：[AudioManager](https://developer.android.com/reference/android/media/AudioManager)、[MediaRouter2 跨应用实例](https://developer.android.com/reference/android/media/MediaRouter2)、[MEDIA_ROUTING_CONTROL 权限](https://developer.android.com/reference/android/Manifest.permission#MEDIA_ROUTING_CONTROL)、[DeviceRouteController](https://android.googlesource.com/platform/frameworks/base/+/android-16.0.0_r1/services/core/java/com/android/server/media/DeviceRouteController.java)。

## 4. 对当前实现的具体简化建议

### 4.1 先改变检测依据，再减少探测音轨

`AudioRouteMonitor.predictedAccessibilityHeadset()` 目前只在多个耳机、没有已知目标等条件下查询属性路由。建议新增的检测行为应覆盖单耳机场景：通信设备变成内建、音频模式变化、设备变化、无障碍播放活动变化时，重新查询无障碍属性的预计设备。

不要由单一通信设备状态直接进入“已劫持”。至少分别保存：物理耳机、通信设备、预计无障碍设备、是否有无障碍播放活动、当前模式，以及本应用尚未完成的请求。

当预计路由仍包含目标耳机时，先观察而不立刻申请通信模式；结果为空或证据冲突时使用未知状态。这个改动能否完全替代静音探测，需要在现有故障设备上对照真实播报验证。API 31/32 没有公开属性查询，应保留独立兼容策略，不能直接按 API 33 路径删除回退。

### 4.2 让一次纠偏只有一个尚未完成的切换请求

用一个有截止时间的请求记录替代多个相互触发的重试窗口：目标设备、接受时间、所属操作、完成事件、是否已被用户操作取消。设备变化、用户手动归还、通话开始、配置变化均使旧请求失效。

被接受后优先等待完成回调；仅在明确错误或相应设备的建立超时后做一次恢复动作。已完成后的路由再次变化再作为新事件处理。不能把蓝牙尚在建立和外部再次改路由合并为同一种“劫持”。

这是一项建议的状态机重构，当前五种轮询模式不能在没有实机回归的情况下机械合并。

### 4.3 避免没有本应用请求时的 clear 观察

`startClearProbe()` 当前会先调用 `clearCommunicationDeviceSafely()`。当本应用没有通信设备请求时，这不能清掉微信或抖音的请求，却仍产生一个观察窗口和后续状态迁移。

建议只在本应用持有请求、确实打算归还控制权时使用 clear/release 流程；没有占用时直接重评证据。若同时持有通信模式，请求的释放也应纳入同一次会话结束，避免文案说“释放占用”，实际仍保留模式所有权。

证据：[AudioManager 的 clear 实现](https://android.googlesource.com/platform/frameworks/base/+/android-16.0.0_r1/media/java/android/media/AudioManager.java) 和 [AudioDeviceBroker 按调用 UID 查找客户端](https://android.googlesource.com/platform/frameworks/base/+/android-16.0.0_r1/services/core/java/com/android/server/audio/AudioDeviceBroker.java)。

### 4.4 将通信模式保活限制为明确的兼容选择

全零 PCM 仍在维持通信播放活动。建议普通模式只进行证据驱动的有界补救；需要长期维持通信模式的机型，作为明确说明副作用的兼容选择，不自动升级到永久占用。

不建议用 `MODE_RINGTONE`、其他电话模式、虚假 Telecom 通话或额外抢音频焦点来消除静音音轨。这些机制会引入与产品目标无关的全局语义。音频焦点解决播放协调，不授予无障碍设备选择权限。[音频焦点说明](https://developer.android.com/media/optimize/audio-focus)

### 4.5 将 16 kHz HAL 参数移出通用恢复路径

`hfp_set_sampling_rate=16000` 可在旧 Qualcomm HAL 实现中找到，但它不是跨设备的蓝牙宽带协商接口。`AudioManager.setParameters()` 没有返回协商结果，调用不抛异常不足以证明 mSBC、采样率或音质已经改善。蓝牙通话路径也不等于 A2DP 媒体路径。

AOSP 蓝牙项目早在 2021 年就有移除这类 `AudioManager.setParameters` 调用的提交。这证明它不适合作为长期通用依赖，但不能据此断言所有厂商固件都已删除该键。

若没有本项目目标 MTK/HyperOS 固件的实测依据，建议移除通用宽带选项及其两次延迟提示；有有效证据时，将其限制到对应设备兼容逻辑，不报告未经验证的音质成功。

证据：[旧 Qualcomm HFP HAL](https://android.googlesource.com/platform/hardware/qcom/audio/+/master/hal/audio_extn/hfp.c)、[移除 Bluetooth setParameters 的提交](https://android.googlesource.com/platform/packages/apps/Bluetooth/+/67a02818130239e472e876af1cce6893ab193003)、[BLE/HFP 通信指南](https://developer.android.com/develop/connectivity/bluetooth/ble-audio/audio-manager)。

### 4.6 优先使用系统 sticky 恢复，再评估 Worker 是否确有额外价值

当前前台服务已经返回 `START_STICKY`。官方说明，Android 12 的后台启动限制不影响系统重启 sticky 前台服务；应用主动从后台调用启动仍受限制。这两种行为应分开。

因此，15 分钟 Worker 和移除任务后延迟 3 秒的 Worker 不应作为基础保活保证。建议保留系统 sticky 恢复、开机允许的恢复入口和用户可见的恢复入口；只有设备证据证明定时检查能额外恢复时，再保留对应兼容措施。强行停止、用户停止和 ROM 限制仍不能由普通 Worker 保证绕过。

`specialUse` 是当前可解释的服务用途候选；不要仅为播放静音或规避限制而改称媒体播放服务。Android 16 对后台 Job/WorkManager 的配额变化也不是修改服务类型就能消除。

证据：[Service.START_STICKY](https://developer.android.com/reference/android/app/Service#START_STICKY)、[后台启动限制](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)、[前台服务类型](https://developer.android.com/develop/background-work/services/fgs/service-types)、[前台服务版本变化](https://developer.android.com/develop/background-work/services/fgs/changes)。

### 4.7 清理没有有效调用的能力应晚于方案选择

当前 `AccessibilitySoftRouteGuard` 仍定义 `PINNED` 模式，而监控引擎的调用固定请求 `OBSERVE`。选定新检测路径后，可以移除未使用的固定路由分支，或将其保留在明确的实验工具中。

这项删除本身不会让 TalkBack 获得全局耳机固定。AOSP 音频引擎会综合活跃客户端的显式偏好，不能依赖一个固定到耳机的旁路静音音轨永久控制其他客户端。[Audio policy Engine](https://android.googlesource.com/platform/frameworks/av/+/android-16.0.0_r1/services/audiopolicy/enginedefault/src/Engine.cpp)

### 4.8 让系统报告通信能力，让用户决定隐私目标

当前代码维护了物理耳机类型表和通信耳机类型表。AOSP 的可通信设备列表不含经典 A2DP，却包含 `TYPE_USB_DEVICE` 等当前过滤表没有涵盖的类型。把可通信列表再用一份固定类型表过滤，既可能保留没有意义的分支，也可能漏掉实际 USB DAC。

建议通信能力以 `availableCommunicationDevices` 为准，然后匹配用户选择的实际目标；设备清单的“可通信”不代表它一定是适合隐私播报的耳机。尤其不能为了支持 USB DAC 就把所有 `TYPE_USB_DEVICE` 或蓝牙音箱自动视为耳机。设备角色、当前选择和用户确认应分开处理，不能仅靠产品名猜测。

证据：[AudioDeviceBroker](https://android.googlesource.com/platform/frameworks/base/+/android-16.0.0_r1/services/core/java/com/android/server/audio/AudioDeviceBroker.java) 的 `VALID_COMMUNICATION_DEVICE_TYPES` 与 `getAvailableCommunicationDevices()`。

### 4.9 使用已有快照承载状态，减少同步 Binder 回读

当前 `buildMonitorSnapshot()`、目标查找和其他辅助函数会多次读取通信设备，UI 又通过 `callOnMonitorThread()` 同步等待。AOSP 的 `getCommunicationDevice()` 在路由更新未处理完时可能等待；因此这些读取不应被当成廉价、永远立即返回的内存字段。

建议继续使用现有单线程监控和 `MonitorSnapshot`，每次事件只采集一组必要字段，并将完整快照推送给 UI。使用通信设备回调中的设备作为事件信息，在需要确认时进行一次回读；UI 展示缓存快照，不逐个 getter 等待。超时默认值要表达“暂时未知”，不能把线程繁忙映射成“耳机已断开”。

这项建议可减少 Binder 调用和前台界面等待，也避免一次快照里的设备名与设备类型来自不同时间点。它不需要另建一套状态管理框架。[AudioDeviceBroker 的回读等待](https://android.googlesource.com/platform/frameworks/base/+/android-16.0.0_r1/services/core/java/com/android/server/audio/AudioDeviceBroker.java)

## 5. 三条实现路径的取舍

| 路径 | 用户需要做什么 | 可以减少什么 | 必须接受的边界 |
|---|---|---|---|
| 普通权限独立 App | 保持现有安装与前台服务使用方式 | 无条件探测、部分 clear 窗口、重复切换、常态静音音轨 | 不能可靠控制其他应用全部音轨；部分故障仍需要明确兼容模式；被动补救不能保证首个音节绝不外放 |
| 用户授权的 shell/Shizuku 后端 | 明确授权并按设备要求维持授权服务 | 通信模式占用、通信 usage 保活、路由探测音轨；有机会进一步减少轮询 | 非 SDK 调用与 ROM 差异；授权生命周期；系统内部策略可能覆盖外部策略；必须恢复原偏好 |
| 读屏端持有实际播放器 | 使用或修改可配合的读屏器 | 跨应用抢路由、代理音轨和通信模式竞争 | 需要维护实际语音队列、焦点、打断、范围回调和设备断开处理，不能改写其他已安装读屏器的输出 |

### 5.1 授权后端的最小验证范围

先做独立的显式实验，不直接给现有 App 增加长期后端抽象：

1. 检查实际执行身份及设备授予的路由/查询权限，不只检查 Shizuku 是否运行。
2. 通过 `AudioProductStrategy` 的属性匹配能力定位无障碍策略；不硬编码策略 ID。读取并保存现有首选设备。
3. 订阅无障碍属性的设备变化。系统监听器注册后不保证立即给出当前结果，因此先读一次状态。
4. 只设置无障碍策略的目标设备，验证真实 TTS、媒体及通话三者，而不申请 `MODE_IN_COMMUNICATION`。
5. 检查微信/抖音切换通信设备后，内部策略是否覆盖所设无障碍偏好。
6. 正常退出、App Binder 死亡、授权服务死亡、耳机断开和用户改选设备时，验证偏好还原。只有偏好仍是本会话写入的值时才尝试恢复，避免覆盖用户后来的选择。

`AudioDeviceInventory` 分别缓存外部 API 请求和内部路由角色，并保存外部首选设备；它不是一条天然随普通 App Binder 死亡自动释放的通信请求。后端必须设计明确的清理与失联恢复，不能把一次设置成功当作完整方案。

证据：[AudioDeviceInventory](https://android.googlesource.com/platform/frameworks/base/+/android-16.0.0_r1/services/core/java/com/android/server/audio/AudioDeviceInventory.java) 的 `setPreferredDevicesForStrategyAndSave()`、`reapplyExternalDevicesRoles()` 与两个角色缓存；[系统策略 API 说明](https://source.android.com/docs/core/audio/combined-audio-routing)。

### 5.2 读屏端的最小正确范围

Google TalkBack 源码通过自己的 TTS 实例设置无障碍音频属性；普通 `speak()` 的播放由 TTS 框架创建音轨。仅换 TTS 引擎或给 `SynthesisCallback.audioAvailable()` 增加逻辑，并不会自动取得那个框架播放器的设备选择权。

若重做读屏端播放，可采用不自动发声的 `synthesizeToFile()` 请求，通过合成回调取得 PCM，再交给读屏端自己创建的 `AudioTrack`，使用 `setPreferredDevice()` 和真实路由回调。不能在原本已经执行 `speak()` 的同时重放 `onAudioAvailable()` 数据，否则会重复发声。合成完成也不等于实际播放完成，读屏队列必须跟随自己的播放器。

这是需要原型评估的架构，不应假设它是几行替换。需要覆盖低延迟、打断、拼读、耳标、语音缓存、范围回调和蓝牙切换；也应继续保留无障碍 usage，避免破坏独立音量及系统语义。

证据：[Google TalkBack FailoverTextToSpeech](https://github.com/google/talkback/blob/master/utils/src/main/java/com/google/android/accessibility/utils/output/FailoverTextToSpeech.java)、[TTS BlockingAudioTrack](https://android.googlesource.com/platform/frameworks/base/+/android-16.0.0_r1/core/java/android/speech/tts/BlockingAudioTrack.java)、[TTS 服务输出参数](https://android.googlesource.com/platform/frameworks/base/+/android-16.0.0_r1/core/java/android/speech/tts/TextToSpeechService.java)、[合成回调契约](https://developer.android.com/reference/android/speech/tts/UtteranceProgressListener#onAudioAvailable(java.lang.String,%20byte[]))。

## 6. 验证顺序与删除条件

先在同一次故障中对照记录：通信设备、无障碍预计设备、自己的探测音轨设备、播放活动事件、模式、修复请求与完成时间。由用户实际确认 TTS 出声位置；在需要更强证据时结合授权设备日志。任何普通权限字段都不应冒充其他音轨的直接测量。

| 场景 | 必须核对的结果 |
|---|---|
| USB/有线，真实 TTS 正常但通信设备显示内建 | 新检测不误升级、不创建通信保活 |
| USB/有线，故障日志确认 TTS 外放 | 属性查询是否同样显示错误设备；恢复时延和外放持续时间 |
| 蓝牙 A2DP 正常播放 | 不因通信设备默认值而切入 SCO；记录媒体是否中断 |
| 蓝牙 HFP/SCO 或 LE Audio 建立较慢 | 不在正常建立期间反复 clear/set；最终完成或超时正确 |
| 微信真实语音/视频通话 | 用户切听筒、免提、耳机时不被守护反复改回 |
| 其他 App 只播放语音消息而持续保留路由请求 | 能说明是哪一层证据异常；不只凭前台 App 猜测原因 |
| 两副同名耳机、USB 与蓝牙共存 | 目标身份与当前选择保持一致，不能无提示切到另一副 |
| 耳机突然断开、屏幕锁定、系统时间改变 | 没有旧任务抢回路由，没有无限窗口 |
| 系统杀进程、用户停止、重启 | 区分 sticky 重建、用户明确停止与应用新发起的后台启动 |
| Android 17，当前 target 与未来 target 37 | 对照界面/通知启动和后台恢复的音频能力；音轨运行标记不能冒充保活成功 |
| 授权后端失联或内部策略覆盖外部偏好 | 明确回退与还原，不能残留设备锁或不断重写 |

只有对照结果证明属性查询与实际故障足够一致，才从相应设备路径删除静音探测；只有授权路径完成覆盖与还原验证，才删除该路径的通信模式保活。保留 Android 12 支持时，单独验证 API 31/32 的能力退化。

## 7. 本轮证据记录与范围

Exa 共执行 32 次有效查询，得到 171 条候选链接记录；按 URL 去重为 132 条，其中 118 条来自筛选的第一方域名/仓库。候选记录不等于逐篇读完的文献数量，重复、历史版本和第三方转载已在结论中区分。

另外成功下载了 41 份源码、Manifest、CTS 或提交快照，对上述关键路径作定点核验。Android 13—16 的首发标签用于版本对照，主线用于发现变化；不能假设某个主线机制已在所有已发布 ROM 中启用。Google TalkBack 的 master 源码也不等于设备上 `com.android.tback` 的确切构建。

41 份成功快照的原始 URL 及一次不存在路径的获取失败记录，见同目录 `android-api-source-manifest-2026-10-23.json`。源码缓存保留在本机临时目录，没有把整份平台源码加入仓库。

本轮没有安装应用、调用手机音频设置或进行真机回归，也没有改动运行代码。上文的架构替换、可删除机制与授权后端收益均应按第 6 节的条件验证。

## 8. 2026-10-24 实施核验

进一步沿调用方、状态投影、设备匹配和音轨生命周期核验后，实施以下范围明确的简化：

| 改动 | 可验证收益 | 保留的边界 |
|---|---|---|
| 通知默认刷新直接获取一份投影输入，不先单独调用 `getStatus()` | 少一次跨线程等待和状态采集；状态与通知字段来自同一份快照 | 显式状态事件仍可覆盖投影状态；尚未改成全局 UI 缓存 |
| `buildMonitorSnapshot()` 只读取一次通信设备 | 设备种类和名称不再分别读取；普通通知刷新路径的通信设备读取从六次降至两次 | 两次分别用于耳机选择和快照采集，并非系统级原子快照 |
| 删除重复的通信耳机类型表和无参候选回退 | 接受系统支持且能匹配目标的 USB_DEVICE 端点，避免类型表漏项 | 目标仍须来自已识别耳机；歧义匹配失败，不自动把 USB DAC 或蓝牙音箱认定为耳机 |
| 删除探测音轨未使用的 PINNED 模式及相关状态 | 仅保留现有观察语义；不再反复调用 `setPreferredDevice(null)` | 观察的是本应用音轨，不能证明其他读屏器实际输出 |
| 清除前检查是否持有已接受的设备请求 | 无请求的停止、配置变更和观察不再调用清除 API | 清除失败时保留持有标记，以便后续清理；模式占用与设备请求仍分别管理 |
| 探测音轨成功启动后才保存目标 | 配置或播放启动失败不会残留一个虚假的目标 | 不将线程活跃等同于 Android 17 上有效的播放能力 |

检查 `ClearProbeResolver` 后发现：内建设备会在首次 tick 立即进入纠偏，并不是每次都会等待完整 200ms。不能把删掉该窗口宣传为普遍缩短 200ms。本轮保留窗口及既有纠偏条件，只去掉无效 clear 调用并修正文案。

回归测试覆盖 USB 端点映射、歧义候选拒绝、扬声器排除、通知读取次数、持有请求清理及探测音轨启动失败。此前的电话暂停、延迟任务取消、单调时钟、恢复确认及后台启动分类测试一并保留。

发现并修复测试配置问题：缺少 `unitTests.isIncludeAndroidResources = true` 时，Robolectric 4.10.3 在 API 33 上以不支持的 legacy resources 模式运行，测试会被静默跳过。因此此前汇总的测试数量不能全部算作通过。本次启用二进制资源，并扩展关键安卓测试覆盖 API 31 与 33；属性路由查询用例仅运行于支持它的 API 33。[Robolectric 官方配置](https://robolectric.org/getting-started/)

未接入 Shizuku/非 SDK 后端，没有仅凭预计路由改变故障判定，也未删除现有设备兼容保活、HAL 选项或 WorkManager。当前 ADB 没有连接设备，尚不能声称电量、首音节外放时间或厂商路由成功率已获得真机改善。

### 8.1 最终验证记录

- Gradle：`testDebugUnitTest testReleaseUnitTest lint assembleDebug assembleRelease` 成功。
- Debug 与 Release 测试各 172 项，失败 0、错误 0、跳过 0。关键 Android 路径覆盖 API 31/33，其余为纯逻辑测试。
- Lint：0 错误、19 警告。其中 `ObsoleteLintCustomCheck` 表示部分 Compose 自定义规则与当前 Lint 不兼容，不能据此声称所有 Compose 规则都已检查；未为消除警告扩大升级整个构建工具链。
- Debug/Release 签名均通过 `apksigner verify`，证书 SHA-256 与内置校验值一致：`222b4c298ca06cb38792288d3b5bfa5c77c00e423cc2ffc0b024b185e447fb52`。
- 两个 APK 均为 `versionName=1.0.7`、`versionCode=91`，minSdk 31、targetSdk 34。当前环境的 Git 所有权检查会使构建脚本自动版本推导回退，因此本次使用项目已有的 `APP_VERSION_NAME` / `APP_VERSION_CODE` 环境参数指定版本，不修改全局 Git 信任设置，也不创建发布标签。
- Debug APK SHA-256：`54c0c616fdcc365954d92e9bdc48eae112b48ccb3cc7649fc8a7250a6f8dd7ee`。
- Release APK SHA-256：`632000f63bd129d9e544e9e6ffde900ffae167fb75d333f41dcd05b9a67b9f5d`。
- `git diff --check` 通过。未安装 APK，未做蓝牙硬件、实际 TalkBack 输出、Android 17 或功耗回归。

## 9. 第二轮细节验证：异步边界与用户开关

继续检查了监控入口、模式/设备回调、恢复与归还窗口、音轨生命周期、通知投影、服务销毁、Worker/开机/磁贴启动，以及相关应用提示和更新模块的部分调用链。这不是所有厂商 ROM 的行为证明，也不是完整的更新模块安全审计。

### 9.1 复现后修复的四类问题

| 问题 | 复现方式与修复前结果 | 修复 |
|---|---|---|
| 销毁后的通知回调仍能发通知 | 销毁服务并移除常驻通知，再向原状态监听器投递一次迟到事件，通知重新出现 | 销毁开始时撤下服务实例；刷新入口核对实例身份后才读取快照或发送通知 |
| 已过期的扬声器回调会再次抢路由 | 当前设备已经恢复有线耳机，再投递旧扬声器事件，仍发起一次切换请求 | 处理前比对事件设备 ID 与当前通信设备，忽略过期事件 |
| 蓝牙参数延迟任务超出有效会话 | 不再持有请求时仍写参数；关闭后迅速重开选项，旧的 250/1200ms 重试仍执行 | 重试检查本应用设备请求；用专用 Handler token 在清除设备请求及清空尝试记录时取消旧任务 |
| 延迟启动忽略已关闭的保护开关 | 开关已关闭时直接模拟一个此前排队的服务创建，仍发布运行实例并启动监控 | 创建时重新读取开关，关闭时不创建音频监控；收到关闭状态下的启动命令时停止并返回 START_NOT_STICKY |

前四个测试场景在 API 31/33 上产生了 8 个修复前断言失败；关闭后延迟启动场景另产生 2 个失败。总共新增五个逻辑用例、十次 SDK 用例执行。先验证失败再修复，未将这些结论仅建立在代码推测上。销毁后的测试直接模拟迟到回调，不能据此估算真实设备上该竞态的发生概率。

token 只用于两项蓝牙参数重试，不调用 `removeCallbacksAndMessages(null)`，避免顺带删除恢复窗口、停止清理等任务。带 token 的 `Handler.postDelayed()` 自 API 28 提供，满足本项目 minSdk 31。[Handler 调度与取消契约](https://developer.android.com/reference/android/os/Handler#postDelayed(java.lang.Runnable,%20java.lang.Object,%20long))

启动请求若来自 `startForegroundService()`，仍先履行前台通知要求再停止；不依赖后台来源和开关状态恰好保持不变。对有效启用状态保留 START_STICKY，不能因增加停止保护而取消正常系统恢复。[Service.START_STICKY](https://developer.android.com/reference/android/app/Service#START_STICKY)

### 9.2 优化收益与代价

这次直接减少的是过期事件引起的重复切换、无效蓝牙参数写入、销毁后的通知重建，以及关闭状态下不应启动的音频监控。过期事件检查会增加一次通信设备回读：这是正确性校验的开销，不应宣传成所有事件路径都减少 Binder 调用。

没有新增轮询频率、后台服务、权限、依赖或通用状态机框架。HAL 参数仍属于用户选择的兼容功能；重试取消不会把“参数调用未抛异常”升级成“蓝牙音质已改善”的证明。

### 9.3 继续优化前需要的证据

- UI、磁贴和通知仍可能同步等候监控线程。下一步有价值的是复用完整快照并测量主线程等待；需要同时定义过期/未知状态，不能把一次超时当作耳机断开。也不能统一取消所有超时 FutureTask，因为停止和资源清理任务仍必须完成。
- 相关应用提示会读取 UsageEvents 并遍历事件。应先记录调用频率、事件数量和耗时，再决定增量缓存或合并查询；其事件时间戳是日历时间，不能机械替换成 elapsedRealtime。归因仍是相关性证据，不是其他应用调用音频 API 的直接证明。
- 本轮只追读更新模块的下载、包名/签名检查和时间统计等部分路径，未做网络故障与安装器全链路回归，也未改动更新行为。
- 静音探测替换、通信保活删除、蓝牙建立时间预算、Shizuku 策略后端和 Android 17 适配，继续遵循第 6 节的实机验证条件。本机模拟测试不能证明 TalkBack 首音节不会外放，也不能量化省电收益。

### 9.4 本轮最终检查

- `testDebugUnitTest testReleaseUnitTest lint assembleDebug assembleRelease` 成功；两种变体各 182 项测试，失败/错误/跳过均为 0。
- Lint 0 错误、19 警告，部分 Compose 自定义规则的兼容性限制同第 8.1 节。
- Debug/Release 均通过 `apksigner verify`，证书 SHA-256 与应用内置值一致。
- 使用已有环境参数生成 `1.0.7 (92)`，不创建发布标签、不上传 APK 到 Release。
- Debug APK SHA-256：`1a92717a4dcec555f21b2e48183b0bc987e1fa089f18f14c75c1badee7cb340a`。
- Release APK SHA-256：`f6c5ce73cc15b94ce664197f0e9926bd9a362d37b509db0c1807f95388d5c812`。
- 未安装到手机；没有实际蓝牙链路、TalkBack 音频、功耗和 Android 17 的新增实机结果。
