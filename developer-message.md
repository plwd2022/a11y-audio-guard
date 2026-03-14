各位开发者好，

我借助 AI 辅助调试，基本定位到了 Android 14+ 上屏幕阅读器音频被抖音、微信等 App 从耳机劫持到扬声器的根因。

【问题本质】
这些 App 在播放语音消息时调用了 setSpeakerphoneOn(true)（目的是从听筒切到扬声器外放），但存在两个问题：
1. 播放结束后未释放，CommunicationRouteClient 残留在系统中持续生效
2. 未判断用户是否连接了耳机——插着耳机时根本不走听筒，这个调用完全多余

残留的 CommunicationRouteClient 会影响 AudioPolicyManager 对 STRATEGY_ACCESSIBILITY 的路由决策，导致无障碍音频流被错误地路由到扬声器。

【已验证的修复方法】
通过 setCommunicationDevice(headset) 可以将通信设备强制设回耳机，覆盖掉 App 残留的错误设置。该 API 为 Android 12+ 公开 API，无需 root 权限。

附件中包含：
1. 详细的技术分析报告（含日志对比、因果链、dumpsys 数据）
2. 触发 Bug 和修复 Bug 的演示视频

如果有兴趣一起推进修复方案（监控 App 或读屏侧适配），欢迎交流。
