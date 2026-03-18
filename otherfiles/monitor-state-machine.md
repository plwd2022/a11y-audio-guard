# AudioRouteMonitor 状态迁移文档

本文档描述 `AudioRouteMonitor` 当前实现中的四类核心状态：

1. `PollingMode`
2. `EnhancedState`
3. held route 生命周期
4. 关键入口事件到状态迁移的主路径

目标不是替代源码，而是把“状态、触发条件、副作用”收束成一份可核对文档，方便后续继续重构时判断行为是否被改变。

---

## 1. `PollingMode`

`PollingMode` 是 monitor 的“主动观察/轮询状态”。

### 1.1 状态定义

| 状态 | 含义 | 典型目的 |
|------|------|----------|
| `IDLE` | 空闲，不做主动轮询 | 平时待机 |
| `CLASSIC_BLUETOOTH_CONFIRM` | 经典蓝牙被动确认窗口 | 判断“通信设备显示为内建”是否真的是持续劫持 |
| `CLEAR_PROBE` | 增强守护清空占用观察窗口 | 先释放本应用占用，看系统会不会自己恢复 |
| `RECOVERY_WINDOW` | 恢复后的稳定性观察窗口 | 防止刚修好又被抢回去 |
| `RELEASE_PROBE` | 归还系统观察窗口 | 尝试放手，让系统接管，再看是否再次被劫持 |

### 1.2 迁移图（文字版）

```text
IDLE
  -> CLASSIC_BLUETOOTH_CONFIRM
  -> CLEAR_PROBE
  -> RECOVERY_WINDOW
  -> RELEASE_PROBE

CLASSIC_BLUETOOTH_CONFIRM
  -> IDLE
  -> RECOVERY_WINDOW

CLEAR_PROBE
  -> IDLE
  -> RECOVERY_WINDOW

RECOVERY_WINDOW
  -> IDLE
  -> CLASSIC_BLUETOOTH_CONFIRM
  -> RELEASE_PROBE

RELEASE_PROBE
  -> IDLE
  -> RECOVERY_WINDOW
```

### 1.3 关键迁移表

| 当前状态 | 触发事件 | 进入状态 | 关键副作用 |
|----------|----------|----------|------------|
| `IDLE` | 经典蓝牙场景下检测到通信设备切到内建设备 | `CLASSIC_BLUETOOTH_CONFIRM` | 记录 builtin evidence，开始被动确认轮询 |
| `IDLE` | 增强守护发现通信设备在内建设备 | `CLEAR_PROBE` | 释放本应用通信设备，占用清空后短观察 |
| `IDLE` | 普通模式下确认被劫持并执行恢复 | `RECOVERY_WINDOW` | 执行恢复，随后进入稳定性观察 |
| `IDLE` | held route 存在且用户手动触发归还 | `RELEASE_PROBE` | 将 held route 标记为“手动归还中”，清理占用并观察是否复发 |
| `CLASSIC_BLUETOOTH_CONFIRM` | 连续命中达到阈值，确认持续劫持 | `RECOVERY_WINDOW` | 执行恢复并观察稳定性 |
| `CLASSIC_BLUETOOTH_CONFIRM` | 超时/条件变化/耳机消失 | `IDLE` | 停止确认，恢复正常待机 |
| `CLEAR_PROBE` | 系统自动恢复到耳机 | `IDLE` | 退出增强清空观察，恢复到 `EnhancedState.ACTIVE` 并报告正常 |
| `CLEAR_PROBE` | 观察窗口结束仍不正常 | `IDLE` | 回到增强守护常规监听 |
| `RECOVERY_WINDOW` | 连续稳定命中达到阈值 | `IDLE` | 退出恢复观察，报告稳定 |
| `RECOVERY_WINDOW` | 再次检测到 builtin route | `RECOVERY_WINDOW` | 立即再次恢复并重置稳定计数 |
| `RECOVERY_WINDOW` | 满足“应保持限制，不应自动归还” | `IDLE` | 进入 held route 状态，但退出轮询 |
| `RECOVERY_WINDOW` | 路由已稳定，但当前仍由 guard 持有通信控制且无需进入 held route | `RELEASE_PROBE` | 清理本应用占用并观察系统接管后是否再次被劫持 |
| `RELEASE_PROBE` | 归还后稳定，未再确认持续劫持 | `IDLE` | 清理 held route，恢复正常 |
| `RELEASE_PROBE` | 归还后再次检测到 builtin route | `RECOVERY_WINDOW` | 重新接管，held route 标记为“归还失败后继续保持” |

### 1.4 迁移契约

当前实现里，`PollingMode` 的四类主动窗口都有一组固定副作用：

| 进入状态 | 进入前会停掉 | 进入时会做什么 |
|----------|--------------|----------------|
| `CLASSIC_BLUETOOTH_CONFIRM` | 启动阶段经典蓝牙观察，必要时退出 `RECOVERY_WINDOW` | 设置确认耳机/命中数/截止时间，启动轮询 |
| `CLEAR_PROBE` | 经典蓝牙确认、启动观察、`RECOVERY_WINDOW`、`RELEASE_PROBE` | 清理本应用通信设备，更新 `EnhancedState.CLEAR_PROBE`，报告 `HIJACKED` |
| `RECOVERY_WINDOW` | 经典蓝牙确认、启动观察 | 设置稳定计数和截止时间，开始恢复后稳定性观察 |
| `RELEASE_PROBE` | 经典蓝牙确认、启动观察、`CLEAR_PROBE`、`RECOVERY_WINDOW` | 清理通信设备，必要时退出通信模式，开始“归还后是否复发”观察 |

### 1.5 补充：经典蓝牙启动观察不属于 `PollingMode`

当前实现还有一条独立于 `PollingMode` 的经典蓝牙启动观察支线：

- 入口：`startClassicBluetoothStartupObservation()`
- 退出：`stopClassicBluetoothStartupObservation()` / `handleClassicBluetoothStartupObserveTimeout()`
- 目的：启动阶段如果通信设备显示为 builtin，但 classic bluetooth soft guard 尚未确认真实出声设备，则先做一小段保真观察，不立刻强制接管

这条状态不会把 `pollingMode` 设成新的 enum 值，但会影响：

- `GuardStatusResolver` 对 builtin route 的公开状态投影
- soft guard 是否升级为强制恢复
- 是否允许从“显示 builtin”继续判定为“仍可先观察”

---

## 2. `EnhancedState`

`EnhancedState` 是“增强守护对外声明的控制状态”，不是底层轮询模式本身。

### 2.1 状态定义

| 状态 | 含义 |
|------|------|
| `DISABLED` | 增强守护关闭 |
| `WAITING_HEADSET` | 增强守护开启，但当前没有可用耳机 |
| `ACTIVE` | 增强守护工作中 |
| `CLEAR_PROBE` | 增强守护正在做清空占用观察 |
| `SUSPENDED_BY_CALL` | 因电话模式而暂停增强守护 |

### 2.2 迁移图（文字版）

```text
DISABLED
  -> WAITING_HEADSET
  -> ACTIVE

WAITING_HEADSET
  -> ACTIVE
  -> DISABLED
  -> SUSPENDED_BY_CALL

ACTIVE
  -> CLEAR_PROBE
  -> WAITING_HEADSET
  -> SUSPENDED_BY_CALL
  -> DISABLED

CLEAR_PROBE
  -> ACTIVE
  -> WAITING_HEADSET
  -> SUSPENDED_BY_CALL
  -> DISABLED

SUSPENDED_BY_CALL
  -> WAITING_HEADSET
  -> ACTIVE
  -> DISABLED
```

### 2.3 关键迁移条件

| 触发条件 | 新状态 | 说明 |
|----------|--------|------|
| 用户关闭增强守护 | `DISABLED` | 停止增强相关观察，释放通信模式 |
| 用户开启增强守护但未接耳机 | `WAITING_HEADSET` | 等耳机接入 |
| 用户开启增强守护且耳机可用 | `ACTIVE` | 进入常规增强守护 |
| 检测到电话模式 | `SUSPENDED_BY_CALL` | 停止 `CLEAR_PROBE` / `RELEASE_PROBE`，必要时释放通信模式 |
| 电话模式结束且有耳机 | `ACTIVE` | 重新进入增强守护 |
| 电话模式结束但无耳机 | `WAITING_HEADSET` | 等待耳机 |
| 增强守护发现 builtin route | `CLEAR_PROBE` | 先清空本应用占用做短观察 |
| 耳机全部断开 | `WAITING_HEADSET` | 同时清理 held route、builtin evidence、各类观察窗口 |

---

## 3. held route 生命周期

`held route` 不是独立 enum，而是一个状态对象：

- `active`
- `manualReleaseInProgress`
- `headsetKey`
- `kind`
- `message`

它表达的业务含义是：“当前守护认为不应自动归还系统，而应持续把通信路由捏在耳机侧。”

### 3.1 生命周期图（文字版）

```text
NONE
  -> HELD

HELD
  -> MANUAL_RELEASE_IN_PROGRESS
  -> NONE

MANUAL_RELEASE_IN_PROGRESS
  -> NONE
  -> HELD_RECLAIMED

HELD_RECLAIMED
  -> MANUAL_RELEASE_IN_PROGRESS
  -> NONE
```

### 3.2 各阶段含义

| 阶段 | 条件 | 对外提示 |
|------|------|----------|
| `NONE` | `active=false` | 无 held route 提示 |
| `HELD` | `active=true` 且 `manualReleaseInProgress=false` | “暂不自动归还，等待用户手动解除限制” |
| `MANUAL_RELEASE_IN_PROGRESS` | `manualReleaseInProgress=true` | “正在尝试归还…控制权” |
| `HELD_RECLAIMED` | 手动归还后再次检测到劫持，重新接管 | “已重新接管…，可再次尝试解除限制” |

### 3.3 关键进入/退出条件

| 触发条件 | 结果 |
|----------|------|
| `RECOVERY_WINDOW` 结束后命中“应该保持限制”条件 | 进入 `HELD` |
| 用户触发“尝试解除外放占用” | 进入 `MANUAL_RELEASE_IN_PROGRESS`，同时启动 `RELEASE_PROBE` |
| `RELEASE_PROBE` 内确认归还后未再持续劫持 | 退出到 `NONE` |
| `RELEASE_PROBE` 或回调再次检测到 builtin route | 进入 `HELD_RECLAIMED` 并重新接管 |
| 耳机断开 / 守护停止 / 增强守护关闭 | 清理到 `NONE` |

---

## 4. 关键入口事件路径

本节回答“哪些入口会改状态”。

### 4.1 `modeChangedListener`

主用途：处理电话模式、通信模式被系统抢走后重新申请。

路径：

1. 若进入电话模式：
   - 停止 `CLEAR_PROBE`
   - 停止 `RELEASE_PROBE`
   - 必要时释放通信模式与通信设备
   - `EnhancedState -> SUSPENDED_BY_CALL`
   - 同步 classic bluetooth soft guard
2. 若退出电话模式：
   - 有耳机则刷新增强守护观察状态
   - 无耳机则 `EnhancedState -> WAITING_HEADSET`

### 4.2 `commDeviceListener`

主用途：处理“通信设备变成 builtin / 外设 / null”。

路径：

1. 无耳机：
   - 清 builtin evidence
   - 清 held route
   - 停止经典蓝牙相关观察
   - 报 `NO_HEADSET`
   - 这里不会直接停掉 `CLEAR_PROBE` / `RECOVERY_WINDOW` / `RELEASE_PROBE`；耳机真正全部断开后的总清理由 `deviceCallback` 负责
2. builtin route：
   - `RELEASE_PROBE` 中优先走“归还后是否仍稳定”判断
   - 增强守护开启时走 `handleEnhancedBuiltinRouteDetected()`
   - 普通模式但经典蓝牙可被动确认时走 `CLASSIC_BLUETOOTH_CONFIRM`
   - 其他情况立即恢复并进入 `RECOVERY_WINDOW`
3. 非 builtin route：
   - 清 builtin evidence
   - 停止经典蓝牙确认/启动观察
   - 增强守护恢复到 `ACTIVE`

### 4.3 `deviceCallback`

主用途：耳机接入/断开。

耳机接入：

1. 增强守护开启：刷新增强观察状态
2. 普通模式：
   - 若当前通信设备是 builtin 且经典蓝牙可被动确认，则只观察
   - 否则尝试恢复并进入 `RECOVERY_WINDOW`

耳机全部断开：

1. 停止所有轮询模式
2. 清 builtin evidence
3. 清 held route
4. 释放通信设备 / 通信模式
5. `EnhancedState -> WAITING_HEADSET`
6. 对外状态 -> `NO_HEADSET`

### 4.4 `evaluateClassicBluetoothSoftGuardRoute`

主用途：经典蓝牙保真观察确认“真实出声已经落到 builtin”。

路径：

1. 仅在 `PollingMode.IDLE` 且 soft guard 运行中生效
2. 若没有近期 builtin evidence，只记“被动跳过”日志，不升级
3. 若仍处于 soft guard 启动延迟或硬升级冷却窗口，则延后或跳过升级
4. 若满足升级条件：
   - 停止启动观察
   - 必要时申请通信模式
   - 强制恢复到耳机
   - 进入 `RECOVERY_WINDOW`

### 4.5 用户手动入口

| 入口 | 主路径 |
|------|--------|
| `fixNow()` | 直接尝试恢复到耳机，成功后进入 `RECOVERY_WINDOW` |
| `tryManualReleaseHeldRoute()` | 将 held route 置为“手动归还中”，进入 `RELEASE_PROBE` |
| `setEnhancedModeEnabled()` | 触发增强守护整套状态重置/重建 |

---

## 5. 阅读源码时的核对顺序

如果后续继续重构，建议按这个顺序核对：

1. 入口事件先看：`modeChangedListener`、`commDeviceListener`、`deviceCallback`
2. 再看 `PollingMode` 窗口：`start/stopClearProbe`、`start/stopRecoveryWindow`、`start/stopReleaseProbe`
3. 再看经典蓝牙分支：启动观察、被动确认、soft guard 升级
4. 最后看 held route 生命周期：进入保持、手动归还、归还失败回退

这样最不容易漏掉副作用。
