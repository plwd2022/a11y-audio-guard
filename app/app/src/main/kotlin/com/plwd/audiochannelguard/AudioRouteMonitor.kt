package com.plwd.audiochannelguard

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.Callable
import java.util.concurrent.Executor
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

enum class GuardStatus {
    NORMAL,
    FIXED,
    FIXED_BUT_SPEAKER_ROUTE,
    NO_HEADSET,
    HIJACKED
}

enum class EnhancedState {
    DISABLED,
    WAITING_HEADSET,
    ACTIVE,
    CLEAR_PROBE,
    SUSPENDED_BY_CALL,
}

class AudioRouteMonitor(private val context: Context) {

    private enum class PollingMode {
        IDLE,
        CLASSIC_BLUETOOTH_CONFIRM,
        CLEAR_PROBE,
        RECOVERY_WINDOW,
        RELEASE_PROBE,
    }

    private data class ClassicBluetoothConfirmState(
        val headset: AudioDeviceInfo? = null,
        val reason: String? = null,
        val builtInType: Int? = null,
        val hitCount: Int = 0,
        val deadlineElapsedMs: Long = 0L,
    )

    private data class ClassicBluetoothStartupObserveState(
        val headset: AudioDeviceInfo? = null,
        val builtInType: Int? = null,
        val deadlineElapsedMs: Long = 0L,
    ) {
        val active: Boolean
            get() = headset != null && deadlineElapsedMs != 0L
    }

    private data class ClassicBluetoothSoftGuardRuntimeState(
        val startedAtElapsedMs: Long = 0L,
        val lastEscalationAtElapsedMs: Long = 0L,
        val lastPassiveObserveLoggedAtElapsedMs: Long = 0L,
        val lastReleaseObserveLoggedAtElapsedMs: Long = 0L,
        val lastPassiveSkipLoggedAtElapsedMs: Long = 0L,
    )

    private data class ClassicBluetoothWidebandState(
        val enabled: Boolean = false,
        val attemptTimesMs: Map<String, Long> = emptyMap(),
    )

    private data class ClassicBluetoothState(
        val softGuardEnabled: Boolean = false,
        val confirm: ClassicBluetoothConfirmState = ClassicBluetoothConfirmState(),
        val startupObserve: ClassicBluetoothStartupObserveState = ClassicBluetoothStartupObserveState(),
        val softGuardRuntime: ClassicBluetoothSoftGuardRuntimeState =
            ClassicBluetoothSoftGuardRuntimeState(),
        val wideband: ClassicBluetoothWidebandState = ClassicBluetoothWidebandState(),
    )

    companion object {
        private const val TAG = "AudioRouteMonitor"

        val HEADSET_TYPES = setOf(
            AudioDeviceInfo.TYPE_WIRED_HEADSET,     // 3
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,  // 4
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,    // 8
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,     // 7
            AudioDeviceInfo.TYPE_USB_HEADSET,       // 22
            AudioDeviceInfo.TYPE_BLE_HEADSET,       // 26
            AudioDeviceInfo.TYPE_HEARING_AID,       // 23 - Android 14+ 助听器支持
        )

        private val COMMUNICATION_HEADSET_TYPES = setOf(
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,    // Some BT devices report as A2DP in comm devices
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_HEARING_AID,       // Android 14+ 助听器支持
        )

        private val BUILTIN_TYPES = setOf(
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE,
        )

        private const val CLEAR_PROBE_POLL_MS = 100L
        private const val CLEAR_PROBE_WINDOW_MS = 200L
        private const val CLASSIC_BLUETOOTH_CONFIRM_POLL_MS = 300L
        private const val CLASSIC_BLUETOOTH_CONFIRM_WINDOW_MS = 900L
        private const val CLASSIC_BLUETOOTH_CONFIRM_REQUIRED_HITS = 3
        private const val CLASSIC_BLUETOOTH_STARTUP_OBSERVE_WINDOW_MS = 1800L
        private const val CLASSIC_BLUETOOTH_PASSIVE_OBSERVE_LOG_COOLDOWN_MS = 5000L
        private const val CLASSIC_BLUETOOTH_RELEASE_OBSERVE_LOG_COOLDOWN_MS = 1000L
        private const val MODE_REACQUIRE_DELAY_MS = 250L
        private const val RECOVERY_POLL_MS = 500L
        private const val RECOVERY_WINDOW_MS = 6000L
        private const val RELEASE_PROBE_POLL_MS = 100L
        private const val RELEASE_PROBE_WINDOW_MS = 1800L
        private const val REQUIRED_STABLE_HITS = 3
        private const val RESTORE_REQUEST_DEBOUNCE_MS = 350L
        private const val SOFT_GUARD_VERIFY_DELAY_MS = 450L
        private const val SOFT_GUARD_HARD_RECLAIM_COOLDOWN_MS = 1500L
        private const val SOFT_GUARD_HIJACK_EVIDENCE_WINDOW_MS = 3000L
        private const val SOFT_GUARD_PASSIVE_SKIP_LOG_COOLDOWN_MS = 5000L
        private const val CLASSIC_BLUETOOTH_WIDEBAND_COOLDOWN_MS = 8000L
        private val CLASSIC_BLUETOOTH_WIDEBAND_RETRY_DELAYS_MS = longArrayOf(250L, 1200L)
        private const val CLASSIC_BLUETOOTH_WIDEBAND_HINT = "hfp_set_sampling_rate=16000"
    }

    private val audioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val monitorThread = HandlerThread("AudioRouteMonitor").apply { start() }
    private val handler = Handler(monitorThread.looper)
    private val callbackExecutor = Executor { runnable ->
        handler.post(runnable)
    }
    private val listenerLock = Any()
    private val classicBluetoothSoftGuard = AccessibilitySoftRouteGuard(
        callbackHandler = handler,
        onRoutedDeviceChanged = ::handleClassicBluetoothSoftGuardRouteChanged
    )
    @Volatile
    private var running = false

    private var pollingMode = PollingMode.IDLE
    private var lastReportedStatus = GuardStatus.NO_HEADSET
    private var clearProbeDeadlineMs = 0L
    private var recoveryDeadlineMs = 0L
    private var releaseProbeDeadlineMs = 0L
    private var stableHitCount = 0
    private var clearProbeHeadset: AudioDeviceInfo? = null
    private var releaseProbeHeadset: AudioDeviceInfo? = null
    private var enhancedModeEnabled = false
    private var enhancedState = EnhancedState.DISABLED
    private var modeListenerRegistered = false
    private var modeRequestedByEnhanced = false
    private var guardOwnsCommunicationDevice = false
    private var heldRouteState = HeldRouteState()
    private var lastRestoreAttemptDeviceKey: String? = null
    private var lastRestoreAttemptAtElapsedMs = 0L
    private var classicBluetoothState = ClassicBluetoothState()
    private var lastBuiltinRouteEvidenceAtElapsedMs = 0L
    private val statusListeners = linkedSetOf<(GuardStatus) -> Unit>()
    private val fixLogListeners = linkedSetOf<() -> Unit>()
    private val enhancedStateListeners = linkedSetOf<(EnhancedState) -> Unit>()
    private val classicBluetoothSoftGuardVerificationRunnable = Runnable {
        evaluateClassicBluetoothSoftGuardRoute("经典蓝牙保真守护自检")
    }
    private val classicBluetoothStartupObserveTimeoutRunnable = Runnable {
        handleClassicBluetoothStartupObserveTimeout()
    }

    private val pollingRunnable = object : Runnable {
        override fun run() {
            if (!running) return

            when (pollingMode) {
                PollingMode.IDLE -> return
                PollingMode.CLASSIC_BLUETOOTH_CONFIRM -> handleClassicBluetoothConfirmTick()
                PollingMode.CLEAR_PROBE -> handleClearProbeTick()
                PollingMode.RECOVERY_WINDOW -> handleRecoveryWindowTick()
                PollingMode.RELEASE_PROBE -> handleReleaseProbeTick()
            }
        }
    }

    private val reacquireEnhancedRunnable = Runnable {
        if (!running || !enhancedModeEnabled) return@Runnable
        maybeReacquireEnhancedMode("增强守护尝试重新接管")
    }

    private val _fixLog = mutableListOf<FixEvent>()
    val fixLog: List<FixEvent>
        get() = callOnMonitorThread(emptyList()) { _fixLog.toList() }

    var onStatusChanged: ((GuardStatus) -> Unit)? = null
    private fun reportStatus(status: GuardStatus) {
        lastReportedStatus = status
        val listeners = synchronized(listenerLock) { statusListeners.toList() }
        postToMainThread {
            onStatusChanged?.invoke(status)
            listeners.forEach { it(status) }
        }
    }

    var onFixLogUpdated: (() -> Unit)? = null
    var onEnhancedStateChanged: ((EnhancedState) -> Unit)? = null

    fun addStatusListener(listener: (GuardStatus) -> Unit) {
        synchronized(listenerLock) {
            statusListeners.add(listener)
        }
    }

    fun removeStatusListener(listener: (GuardStatus) -> Unit) {
        synchronized(listenerLock) {
            statusListeners.remove(listener)
        }
    }

    fun addFixLogListener(listener: () -> Unit) {
        synchronized(listenerLock) {
            fixLogListeners.add(listener)
        }
    }

    fun removeFixLogListener(listener: () -> Unit) {
        synchronized(listenerLock) {
            fixLogListeners.remove(listener)
        }
    }

    fun addEnhancedStateListener(listener: (EnhancedState) -> Unit) {
        synchronized(listenerLock) {
            enhancedStateListeners.add(listener)
        }
    }

    fun removeEnhancedStateListener(listener: (EnhancedState) -> Unit) {
        synchronized(listenerLock) {
            enhancedStateListeners.remove(listener)
        }
    }

    private val modeChangedListener =
        AudioManager.OnModeChangedListener { mode ->
            Log.i(TAG, "Audio mode changed: $mode")
            if (!running || !enhancedModeEnabled) return@OnModeChangedListener

            if (shouldSuspendForCall(mode)) {
                handler.removeCallbacks(reacquireEnhancedRunnable)
                stopClearProbe("检测到电话模式")
                stopReleaseProbe("检测到电话模式")
                if (enhancedState != EnhancedState.SUSPENDED_BY_CALL) {
                    addLog(
                        "检测到电话模式，暂停增强守护",
                        code = FixEventCode.MODE_SUSPENDED_BY_CALL,
                        level = FixEventLevel.WARNING,
                    )
                }
                if (hasGuardCommunicationHold()) {
                    clearCommunicationDeviceSafely()
                    tryLeaveCommunicationMode("检测到电话模式")
                }
                updateEnhancedState(EnhancedState.SUSPENDED_BY_CALL)
                syncClassicBluetoothSoftGuard("检测到电话模式")
                return@OnModeChangedListener
            }

            val headset = findConnectedHeadset()
            if (headset == null) {
                updateEnhancedState(EnhancedState.WAITING_HEADSET)
                return@OnModeChangedListener
            }

            if (modeRequestedByEnhanced && mode != AudioManager.MODE_IN_COMMUNICATION) {
                handler.removeCallbacks(reacquireEnhancedRunnable)
                handler.postDelayed(reacquireEnhancedRunnable, MODE_REACQUIRE_DELAY_MS)
            } else if (pollingMode == PollingMode.IDLE || enhancedState == EnhancedState.SUSPENDED_BY_CALL) {
                refreshEnhancedObservationState("音频模式变化后更新监听状态", headset)
            }
            syncClassicBluetoothSoftGuard("音频模式变化后更新保真守护")
        }

    private val commDeviceListener =
        AudioManager.OnCommunicationDeviceChangedListener { device ->
            Log.i(TAG, "Communication device changed: type=${device?.type} name=${device?.productName}")
            val headset = findConnectedHeadset()
            if (headset == null) {
                clearBuiltinRouteEvidence()
                clearHeldRouteState()
                stopClassicBluetoothConfirm("耳机不可用", announce = false)
                stopClassicBluetoothStartupObservation("耳机不可用", announce = false)
                if (enhancedModeEnabled) {
                    updateEnhancedState(EnhancedState.WAITING_HEADSET)
                }
                reportStatus(GuardStatus.NO_HEADSET)
                return@OnCommunicationDeviceChangedListener
            }

            if (device == null && pollingMode != PollingMode.IDLE) {
                return@OnCommunicationDeviceChangedListener
            }

            if (device?.type in BUILTIN_TYPES) {
                if (pollingMode == PollingMode.RELEASE_PROBE) {
                    val builtInDevice = device ?: return@OnCommunicationDeviceChangedListener
                    if (maybeContinueClassicBluetoothManualReleaseObservation(headset, builtInDevice)) {
                        return@OnCommunicationDeviceChangedListener
                    }
                    rememberBuiltinRouteEvidence(builtInDevice.type)
                    handleReleaseProbeBuiltinRouteDetected(headset, builtInDevice)
                    return@OnCommunicationDeviceChangedListener
                }
                if (enhancedModeEnabled) {
                    rememberBuiltinRouteEvidence(device?.type)
                    val builtInDevice = device ?: return@OnCommunicationDeviceChangedListener
                    handleEnhancedBuiltinRouteDetected(headset, builtInDevice)
                } else if (shouldUsePassiveClassicBluetoothConfirmation(headset)) {
                    rememberBuiltinRouteEvidence(device?.type)
                    startClassicBluetoothConfirm(
                        headset = headset,
                        builtInType = device?.type,
                        reason = "检测到通信设备切到${builtinDeviceName(device?.type)}"
                    )
                } else {
                    rememberBuiltinRouteEvidence(device?.type)
                    restoreCommunicationToHeadset(
                        preferredOutputDevice = headset,
                        reason = "检测到声道被劫持到${builtinDeviceName(device?.type)}"
                    )
                    startRecoveryWindow("回调检测到路由劫持")
                }
            } else {
                clearBuiltinRouteEvidence()
                stopClassicBluetoothConfirm("通信设备恢复为非内建设备", announce = false)
                stopClassicBluetoothStartupObservation("通信设备恢复为非内建设备", announce = false)
                if (enhancedModeEnabled && enhancedState != EnhancedState.SUSPENDED_BY_CALL) {
                    updateEnhancedState(EnhancedState.ACTIVE)
                }
                reportStatus(currentStableRouteStatus(headset))
                syncClassicBluetoothSoftGuard("通信设备变化后更新保真守护")
            }
        }

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            val headset = addedDevices.firstOrNull { it.type in HEADSET_TYPES && it.isSink }
            if (headset != null) {
                addLog(
                    "耳机已连接: ${headset.productName}",
                    code = FixEventCode.HEADSET_CONNECTED,
                )
                if (enhancedModeEnabled) {
                    refreshEnhancedObservationState("检测到耳机接入", headset)
                    syncClassicBluetoothSoftGuard("检测到耳机接入")
                    return
                }

                val commDevice = audioManager.communicationDevice
                if (commDevice?.type in BUILTIN_TYPES) {
                    if (shouldUsePassiveClassicBluetoothConfirmation(headset)) {
                        clearBuiltinRouteEvidence()
                        maybeLogPassiveClassicBluetoothObservation(
                            headset = headset,
                            reason = "耳机接入后通信设备显示为${builtinDeviceName(commDevice?.type)}"
                        )
                        reportStatus(GuardStatus.NORMAL)
                    } else {
                        rememberBuiltinRouteEvidence(commDevice?.type)
                        restoreCommunicationToHeadset(
                            preferredOutputDevice = headset,
                            reason = "检测到耳机接入，尝试恢复声道"
                        )
                        startRecoveryWindow("耳机接入后检测到内建设备")
                    }
                } else {
                    startRecoveryWindow("耳机接入后确认路由稳定")
                    reportStatus(GuardStatus.NORMAL)
                }
                syncClassicBluetoothSoftGuard("检测到耳机接入")
            }
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            val headset = removedDevices.firstOrNull { it.type in HEADSET_TYPES && it.isSink }
            if (headset != null) {
                addLog(
                    "耳机已断开: ${headset.productName}",
                    code = FixEventCode.HEADSET_DISCONNECTED,
                    level = FixEventLevel.WARNING,
                )
                if (findConnectedHeadset() == null) {
                    stopClearProbe("耳机全部断开")
                    stopClassicBluetoothConfirm("耳机全部断开", announce = false)
                    stopClassicBluetoothStartupObservation("耳机全部断开", announce = false)
                    stopRecoveryWindow("耳机全部断开")
                    stopReleaseProbe("耳机全部断开")
                    clearClassicBluetoothWidebandAttempts()
                    clearBuiltinRouteEvidence()
                    clearHeldRouteState()
                    clearCommunicationDeviceSafely()
                    if (enhancedModeEnabled) {
                        tryLeaveCommunicationMode("耳机全部断开")
                        updateEnhancedState(EnhancedState.WAITING_HEADSET)
                    }
                    reportStatus(GuardStatus.NO_HEADSET)
                } else {
                    syncClassicBluetoothSoftGuard("耳机列表变化")
                }
            }
        }
    }

    private fun shouldUsePassiveClassicBluetoothConfirmation(headset: AudioDeviceInfo): Boolean {
        return ClassicBluetoothPassiveCandidateResolver.resolve(
            ClassicBluetoothPassiveCandidateInput(
                enhancedModeEnabled = enhancedModeEnabled,
                hasGuardCommunicationHold = hasGuardCommunicationHold(),
                isClassicBluetoothHeadset = isClassicBluetoothOutputDevice(headset),
            )
        ).outcome == ClassicBluetoothPassiveCandidateOutcome.ALLOWED
    }

    private fun maybeLogPassiveClassicBluetoothObservation(
        headset: AudioDeviceInfo,
        reason: String,
    ) {
        val now = SystemClock.elapsedRealtime()
        if (
            now - classicBluetoothState.softGuardRuntime.lastPassiveObserveLoggedAtElapsedMs <
            CLASSIC_BLUETOOTH_PASSIVE_OBSERVE_LOG_COOLDOWN_MS
        ) {
            return
        }
        classicBluetoothState =
            classicBluetoothState.copy(
                softGuardRuntime =
                    classicBluetoothState.softGuardRuntime.copy(
                        lastPassiveObserveLoggedAtElapsedMs = now
                    )
            )
        addLog(
            "$reason，经典蓝牙默认先观察，不主动接管 ${headset.productName}",
            code = FixEventCode.CLASSIC_BLUETOOTH_OBSERVING,
        )
    }

    private fun isClassicBluetoothPassiveObservationActive(
        headset: AudioDeviceInfo,
        routedDevice: AudioDeviceInfo?,
    ): Boolean {
        return classicBluetoothState.softGuardEnabled &&
            isClassicBluetoothOutputDevice(headset) &&
            routedDevice?.type !in BUILTIN_TYPES
    }

    private fun classicBluetoothPassiveObservationReason(
        builtInDevice: AudioDeviceInfo,
        routedDevice: AudioDeviceInfo?,
    ): String {
        return if (routedDevice != null) {
            "通信设备显示为${builtinDeviceName(builtInDevice.type)}，但保真观察仍在 ${routedDevice.productName}"
        } else {
            "通信设备显示为${builtinDeviceName(builtInDevice.type)}，但保真观察尚未确认实际出声设备"
        }
    }

    private fun classicBluetoothStartupObservationReason(
        builtInDevice: AudioDeviceInfo,
        routedDevice: AudioDeviceInfo?,
    ): String {
        return if (routedDevice != null && routedDevice.type !in BUILTIN_TYPES) {
            "通信设备显示为${builtinDeviceName(builtInDevice.type)}，但保真观察仍在 ${routedDevice.productName}，未确认持续劫持"
        } else {
            "通信设备显示为${builtinDeviceName(builtInDevice.type)}，但保真观察尚未确认实际出声设备，未确认持续劫持"
        }
    }

    private fun maybeLogClassicBluetoothReleaseObservation(
        builtInDevice: AudioDeviceInfo,
        routedDevice: AudioDeviceInfo?,
    ) {
        val now = SystemClock.elapsedRealtime()
        if (
            now - classicBluetoothState.softGuardRuntime.lastReleaseObserveLoggedAtElapsedMs <
            CLASSIC_BLUETOOTH_RELEASE_OBSERVE_LOG_COOLDOWN_MS
        ) {
            return
        }
        classicBluetoothState =
            classicBluetoothState.copy(
                softGuardRuntime =
                    classicBluetoothState.softGuardRuntime.copy(
                        lastReleaseObserveLoggedAtElapsedMs = now
                    )
            )

        val message = if (routedDevice != null) {
            "归还系统后通信设备显示为${builtinDeviceName(builtInDevice.type)}，" +
                "但保真观察仍在 ${routedDevice.productName}，继续等待系统稳定"
        } else {
            "归还系统后通信设备显示为${builtinDeviceName(builtInDevice.type)}，" +
                "保真观察尚未确认实际出声设备，继续等待系统稳定"
        }
        addLog(
            message,
            code = FixEventCode.CLASSIC_BLUETOOTH_OBSERVING,
        )
    }

    private fun maybeContinueClassicBluetoothManualReleaseObservation(
        headset: AudioDeviceInfo,
        builtInDevice: AudioDeviceInfo,
    ): Boolean {
        if (
            !heldRouteState.manualReleaseInProgress ||
            !classicBluetoothState.softGuardEnabled ||
            !isClassicBluetoothOutputDevice(headset)
        ) {
            return false
        }

        val routedDevice = classicBluetoothSoftGuard.getRoutedDevice()
        if (routedDevice?.type in BUILTIN_TYPES) {
            return false
        }

        val timedOut = System.currentTimeMillis() >= releaseProbeDeadlineMs
        if (timedOut && routedDevice != null && isSamePhysicalDevice(routedDevice, headset)) {
            clearBuiltinRouteEvidence()
            stopReleaseProbe(
                "归还系统后通信设备仍显示为${builtinDeviceName(builtInDevice.type)}，" +
                    "但保真观察持续在 ${routedDevice.productName}"
            )
            clearHeldRouteState()
            if (enhancedModeEnabled && enhancedState != EnhancedState.SUSPENDED_BY_CALL) {
                updateEnhancedState(EnhancedState.ACTIVE)
            }
            reportStatus(GuardStatus.NORMAL)
            return true
        }

        maybeLogClassicBluetoothReleaseObservation(
            builtInDevice = builtInDevice,
            routedDevice = routedDevice
        )
        handler.removeCallbacks(pollingRunnable)
        handler.postDelayed(pollingRunnable, RELEASE_PROBE_POLL_MS)
        return true
    }

    private fun hasClassicBluetoothStartupObservation(): Boolean {
        return classicBluetoothState.startupObserve.active
    }

    private fun startClassicBluetoothStartupObservation(
        headset: AudioDeviceInfo,
        builtInType: Int?,
        reason: String,
    ) {
        if (!running || !shouldUsePassiveClassicBluetoothConfirmation(headset)) return

        if (!classicBluetoothState.softGuardEnabled) {
            clearBuiltinRouteEvidence()
            maybeLogPassiveClassicBluetoothObservation(headset, reason)
            reportStatus(GuardStatus.NORMAL)
            return
        }

        if (
            hasClassicBluetoothStartupObservation() &&
            classicBluetoothState.startupObserve.headset?.let { isSamePhysicalDevice(it, headset) } == true &&
            classicBluetoothState.startupObserve.builtInType == builtInType
        ) {
            return
        }

        stopClassicBluetoothConfirm("切换到启动保真观察", announce = false)
        classicBluetoothState = classicBluetoothState.copy(
            startupObserve = ClassicBluetoothStartupObserveState(
                headset = headset,
                builtInType = builtInType,
                deadlineElapsedMs =
                    SystemClock.elapsedRealtime() + CLASSIC_BLUETOOTH_STARTUP_OBSERVE_WINDOW_MS,
            )
        )
        rememberBuiltinRouteEvidence(builtInType)
        addLog(
            "$reason，经典蓝牙先做保真观察，不主动接管 ${headset.productName}",
            code = FixEventCode.CLASSIC_BLUETOOTH_OBSERVING,
        )
        reportStatus(GuardStatus.NORMAL)
        handler.removeCallbacks(classicBluetoothStartupObserveTimeoutRunnable)
        handler.postDelayed(
            classicBluetoothStartupObserveTimeoutRunnable,
            CLASSIC_BLUETOOTH_STARTUP_OBSERVE_WINDOW_MS
        )
        syncClassicBluetoothSoftGuard("进入启动阶段经典蓝牙保真观察")
    }

    private fun stopClassicBluetoothStartupObservation(
        reason: String,
        announce: Boolean = true,
        clearEvidence: Boolean = true,
    ) {
        if (!hasClassicBluetoothStartupObservation()) return
        classicBluetoothState =
            classicBluetoothState.copy(startupObserve = ClassicBluetoothStartupObserveState())
        handler.removeCallbacks(classicBluetoothStartupObserveTimeoutRunnable)
        if (clearEvidence) {
            clearBuiltinRouteEvidence()
        }
        if (announce) {
            addLog(
                "退出启动阶段经典蓝牙观察: $reason",
                code = FixEventCode.CLASSIC_BLUETOOTH_OBSERVE_STOPPED,
            )
        }
        syncClassicBluetoothSoftGuard("退出启动阶段经典蓝牙保真观察")
    }

    private fun handleClassicBluetoothStartupObserveTimeout() {
        if (!hasClassicBluetoothStartupObservation()) return

        val headset = classicBluetoothState.startupObserve.headset ?: findConnectedHeadset()
        val commDevice = audioManager.communicationDevice
        val routedDevice = classicBluetoothSoftGuard.getRoutedDevice()
        val builtInDevice = commDevice?.takeIf { it.type in BUILTIN_TYPES }
        when (
            ClassicBluetoothStartupObserveResolver.resolve(
                ClassicBluetoothStartupObserveDecisionInput(
                    hasHeadset = headset != null,
                    communicationDeviceKind = communicationDeviceKind(commDevice),
                    routedDeviceKind = communicationDeviceKind(routedDevice),
                )
            ).outcome
        ) {
            ClassicBluetoothStartupObserveOutcome.STOP_NO_HEADSET -> {
                stopClassicBluetoothStartupObservation("观察期间未检测到耳机")
                reportStatus(GuardStatus.NO_HEADSET)
                return
            }

            ClassicBluetoothStartupObserveOutcome.STOP_ROUTE_RECOVERED -> {
                stopClassicBluetoothStartupObservation("启动阶段通信设备已恢复为非内建设备")
                reportStatus(GuardStatus.NORMAL)
                return
            }

            ClassicBluetoothStartupObserveOutcome.EVALUATE_SOFT_GUARD -> {
                evaluateClassicBluetoothSoftGuardRoute("启动阶段保真观察窗口结束", routedDevice)
                if (!hasClassicBluetoothStartupObservation()) {
                    return
                }
            }

            ClassicBluetoothStartupObserveOutcome.STOP_UNCONFIRMED -> {
            }
        }

        val availableBuiltInDevice = builtInDevice ?: return
        stopClassicBluetoothStartupObservation(
            classicBluetoothStartupObservationReason(availableBuiltInDevice, routedDevice)
        )
        reportStatus(GuardStatus.NORMAL)
    }

    private fun startClassicBluetoothConfirm(
        headset: AudioDeviceInfo,
        builtInType: Int?,
        reason: String,
    ) {
        if (!running || !shouldUsePassiveClassicBluetoothConfirmation(headset)) return

        if (
            pollingMode == PollingMode.CLASSIC_BLUETOOTH_CONFIRM &&
            classicBluetoothState.confirm.headset?.let { isSamePhysicalDevice(it, headset) } == true &&
            classicBluetoothState.confirm.builtInType == builtInType
        ) {
            return
        }

        if (pollingMode == PollingMode.RECOVERY_WINDOW) {
            stopRecoveryWindow("切换到经典蓝牙被动确认")
        }
        stopClassicBluetoothStartupObservation(
            "切换到经典蓝牙被动确认",
            announce = false,
            clearEvidence = false
        )

        classicBluetoothState = classicBluetoothState.copy(
            confirm = ClassicBluetoothConfirmState(
                headset = headset,
                reason = reason,
                builtInType = builtInType,
                hitCount = 1,
                deadlineElapsedMs =
                    SystemClock.elapsedRealtime() + CLASSIC_BLUETOOTH_CONFIRM_WINDOW_MS,
            )
        )
        pollingMode = PollingMode.CLASSIC_BLUETOOTH_CONFIRM
        addLog(
            "$reason，经典蓝牙先确认是否为持续劫持",
            code = FixEventCode.CLASSIC_BLUETOOTH_OBSERVING,
        )
        reportStatus(GuardStatus.NORMAL)
        handler.removeCallbacks(pollingRunnable)
        handler.postDelayed(pollingRunnable, CLASSIC_BLUETOOTH_CONFIRM_POLL_MS)
        syncClassicBluetoothSoftGuard("进入经典蓝牙被动确认")
    }

    private fun stopClassicBluetoothConfirm(reason: String, announce: Boolean = true) {
        if (pollingMode != PollingMode.CLASSIC_BLUETOOTH_CONFIRM) return
        pollingMode = PollingMode.IDLE
        classicBluetoothState =
            classicBluetoothState.copy(confirm = ClassicBluetoothConfirmState())
        handler.removeCallbacks(pollingRunnable)
        if (announce) {
            addLog(
                "退出经典蓝牙观察: $reason",
                code = FixEventCode.CLASSIC_BLUETOOTH_OBSERVE_STOPPED,
            )
        }
        syncClassicBluetoothSoftGuard("退出经典蓝牙被动确认")
    }

    private fun handleClassicBluetoothConfirmTick() {
        if (pollingMode != PollingMode.CLASSIC_BLUETOOTH_CONFIRM) return

        val headset = classicBluetoothState.confirm.headset ?: findConnectedHeadset()
        val commDevice = audioManager.communicationDevice
        val routedDevice = classicBluetoothSoftGuard.getRoutedDevice()
        val builtInDevice = commDevice?.takeIf { it.type in BUILTIN_TYPES }
        val decision = ClassicBluetoothConfirmResolver.resolve(
            ClassicBluetoothConfirmDecisionInput(
                hasHeadset = headset != null,
                passiveConfirmationAllowed =
                    headset?.let { shouldUsePassiveClassicBluetoothConfirmation(it) } ?: false,
                communicationDeviceKind = communicationDeviceKind(commDevice),
                softGuardObservationActive =
                    headset != null && isClassicBluetoothPassiveObservationActive(headset, routedDevice),
                timedOut = SystemClock.elapsedRealtime() >= classicBluetoothState.confirm.deadlineElapsedMs,
                confirmHitCount = classicBluetoothState.confirm.hitCount,
                requiredHits = CLASSIC_BLUETOOTH_CONFIRM_REQUIRED_HITS,
            )
        )

        classicBluetoothState =
            classicBluetoothState.copy(
                confirm = classicBluetoothState.confirm.copy(hitCount = decision.nextHitCount)
            )
        when (decision.outcome) {
            ClassicBluetoothConfirmOutcome.STOP_NO_HEADSET -> {
                clearBuiltinRouteEvidence()
                stopClassicBluetoothConfirm("观察期间未检测到耳机")
                reportStatus(GuardStatus.NO_HEADSET)
                return
            }

            ClassicBluetoothConfirmOutcome.STOP_CONDITIONS_CHANGED -> {
                stopClassicBluetoothConfirm("观察条件已变化", announce = false)
                return
            }

            ClassicBluetoothConfirmOutcome.STOP_ROUTE_RECOVERED -> {
                clearBuiltinRouteEvidence()
                stopClassicBluetoothConfirm("观察期间未再检测到内建设备")
                reportStatus(GuardStatus.NORMAL)
                return
            }

            ClassicBluetoothConfirmOutcome.STOP_SOFT_GUARD_TIMEOUT -> {
                val availableBuiltInDevice = builtInDevice ?: return
                val reason = classicBluetoothPassiveObservationReason(availableBuiltInDevice, routedDevice)
                clearBuiltinRouteEvidence()
                stopClassicBluetoothConfirm("$reason，未确认持续劫持")
                reportStatus(GuardStatus.NORMAL)
                return
            }

            ClassicBluetoothConfirmOutcome.CONTINUE_SOFT_GUARD_OBSERVATION -> {
                val availableHeadset = headset ?: return
                val availableBuiltInDevice = builtInDevice ?: return
                maybeLogPassiveClassicBluetoothObservation(
                    availableHeadset,
                    classicBluetoothPassiveObservationReason(availableBuiltInDevice, routedDevice)
                )
                handler.postDelayed(pollingRunnable, CLASSIC_BLUETOOTH_CONFIRM_POLL_MS)
                return
            }

            ClassicBluetoothConfirmOutcome.CONFIRM_HIJACK -> {
                val availableHeadset = headset ?: return
                val availableBuiltInDevice = builtInDevice ?: return
                classicBluetoothState =
                    classicBluetoothState.copy(
                        confirm =
                            classicBluetoothState.confirm.copy(
                                builtInType = availableBuiltInDevice.type
                            )
                    )
                stopClassicBluetoothConfirm("已确认持续劫持", announce = false)
                val fixed = restoreCommunicationToHeadset(
                    preferredOutputDevice = availableHeadset,
                    reason = classicBluetoothState.confirm.reason
                        ?: "经典蓝牙确认${builtinDeviceName(availableBuiltInDevice.type)}路由持续存在，尝试恢复声道"
                )
                if (fixed) {
                    startRecoveryWindow("经典蓝牙确认劫持后恢复")
                } else {
                    reportStatus(GuardStatus.HIJACKED)
                }
                return
            }

            ClassicBluetoothConfirmOutcome.STOP_TIMEOUT -> {
                clearBuiltinRouteEvidence()
                stopClassicBluetoothConfirm("观察窗口结束，未确认持续劫持")
                reportStatus(GuardStatus.NORMAL)
                return
            }

            ClassicBluetoothConfirmOutcome.CONTINUE_CONFIRMING -> {
                val availableBuiltInDevice = builtInDevice ?: return
                classicBluetoothState =
                    classicBluetoothState.copy(
                        confirm =
                            classicBluetoothState.confirm.copy(
                                builtInType = availableBuiltInDevice.type
                            )
                    )
                handler.postDelayed(pollingRunnable, CLASSIC_BLUETOOTH_CONFIRM_POLL_MS)
                return
            }
        }
    }

    private fun handleClearProbeTick() {
        if (pollingMode != PollingMode.CLEAR_PROBE) return

        val headset = clearProbeHeadset ?: findConnectedHeadset()
        val commDevice = audioManager.communicationDevice
        val communicationHeadset = headset?.let { findAvailableCommunicationHeadset(it) }
        val restoredNaturally =
            commDevice != null && communicationHeadset != null && isSamePhysicalDevice(commDevice, communicationHeadset)
        when (
            ClearProbeResolver.resolve(
                ClearProbeDecisionInput(
                    hasHeadset = headset != null,
                    restoredNaturally = restoredNaturally,
                    communicationDeviceKind = communicationDeviceKind(commDevice),
                    timedOut = System.currentTimeMillis() >= clearProbeDeadlineMs,
                )
            ).outcome
        ) {
            ClearProbeOutcome.STOP_NO_HEADSET -> {
                stopClearProbe("观察期间未检测到耳机")
                if (enhancedModeEnabled) {
                    updateEnhancedState(EnhancedState.WAITING_HEADSET)
                }
                reportStatus(GuardStatus.NO_HEADSET)
                return
            }

            ClearProbeOutcome.FINISH_RESTORED_NATURALLY -> {
                stopClearProbe("系统已自动恢复到耳机")
                updateEnhancedState(EnhancedState.ACTIVE)
                reportStatus(GuardStatus.NORMAL)
                return
            }

            ClearProbeOutcome.FORCE_RESTORE_BUILTIN_ROUTE -> {
                val availableHeadset = headset ?: return
                rememberBuiltinRouteEvidence(commDevice?.type)
                stopClearProbe("释放后仍停留在${builtinDeviceName(commDevice?.type)}")
                tryEnterCommunicationMode("增强守护重新申请通信模式")
                val fixed = restoreCommunicationToHeadset(
                    preferredOutputDevice = availableHeadset,
                    reason = "释放后仍被劫持到${builtinDeviceName(commDevice?.type)}，强制恢复到耳机"
                )
                updateEnhancedState(EnhancedState.ACTIVE)
                if (fixed) {
                    startRecoveryWindow("增强守护强制接管后观察稳定性")
                }
                return
            }

            ClearProbeOutcome.FINISH_NORMAL -> {
                stopClearProbe("观察窗口结束")
                updateEnhancedState(EnhancedState.ACTIVE)
                reportStatus(GuardStatus.NORMAL)
                return
            }

            ClearProbeOutcome.CONTINUE_POLLING -> {
                handler.postDelayed(pollingRunnable, CLEAR_PROBE_POLL_MS)
                return
            }
        }
    }

    private fun handleRecoveryWindowTick() {
        if (pollingMode != PollingMode.RECOVERY_WINDOW) return

        val headset = findConnectedHeadset()
        val commDevice = audioManager.communicationDevice
        val communicationHeadset = headset?.let { findAvailableCommunicationHeadset(it) }
        val decision = RecoveryWindowResolver.resolve(
            RecoveryWindowDecisionInput(
                timedOut = System.currentTimeMillis() >= recoveryDeadlineMs,
                hasHeadset = headset != null,
                communicationDeviceKind = communicationDeviceKind(commDevice),
                communicationDeviceMatchesHeadset =
                    commDevice != null &&
                        communicationHeadset != null &&
                        isSamePhysicalDevice(commDevice, communicationHeadset),
                stableHitCount = stableHitCount,
                requiredStableHits = REQUIRED_STABLE_HITS,
                isClassicBluetoothPassiveCandidate =
                    headset?.let { shouldUsePassiveClassicBluetoothConfirmation(it) } ?: false,
                shouldKeepHeldRouteAfterRecovery =
                    headset?.let { shouldKeepHeldRouteAfterRecovery(it) } ?: false,
                hasGuardCommunicationHold = hasGuardCommunicationHold(),
            )
        )
        stableHitCount = decision.nextStableHitCount

        if (decision.outcome == RecoveryWindowOutcome.STOP_TIMEOUT) {
            stopRecoveryWindow("恢复观察窗口超时")
            return
        }

        if (decision.outcome == RecoveryWindowOutcome.STOP_NO_HEADSET) {
            stopRecoveryWindow("恢复观察窗口内未检测到耳机")
            reportStatus(GuardStatus.NO_HEADSET)
            return
        }

        val availableHeadset = headset ?: return
        when (decision.outcome) {
            RecoveryWindowOutcome.START_CLASSIC_BLUETOOTH_CONFIRM -> {
                clearBuiltinRouteEvidence()
                startClassicBluetoothConfirm(
                    headset = availableHeadset,
                    builtInType = commDevice?.type,
                    reason = "恢复观察窗口检测到声道切到${builtinDeviceName(commDevice?.type)}"
                )
                return
            }

            RecoveryWindowOutcome.RESTORE_BUILTIN_ROUTE -> {
                rememberBuiltinRouteEvidence(commDevice?.type)
                restoreCommunicationToHeadset(
                    preferredOutputDevice = availableHeadset,
                    reason = "恢复观察窗口检测到声道再次被劫持到${builtinDeviceName(commDevice?.type)}"
                )
                handler.postDelayed(pollingRunnable, RECOVERY_POLL_MS)
                return
            }

            RecoveryWindowOutcome.ENTER_HELD_ROUTE -> {
                val reason = "路由已连续稳定 $REQUIRED_STABLE_HITS 次"
                stopRecoveryWindow(reason)
                if (enhancedModeEnabled && enhancedState != EnhancedState.SUSPENDED_BY_CALL) {
                    updateEnhancedState(EnhancedState.ACTIVE)
                }
                if (!hasActiveHeldRoute(availableHeadset)) {
                    enterHeldRoute(availableHeadset, reason)
                } else {
                    syncHeldRouteTracking(availableHeadset)
                }
                reportStatus(GuardStatus.FIXED)
                return
            }

            RecoveryWindowOutcome.START_RELEASE_PROBE -> {
                startReleaseProbe(availableHeadset, "路由已连续稳定 $REQUIRED_STABLE_HITS 次")
                return
            }

            RecoveryWindowOutcome.FINISH_NORMAL -> {
                stopRecoveryWindow("路由已连续稳定 $REQUIRED_STABLE_HITS 次")
                if (enhancedModeEnabled && enhancedState != EnhancedState.SUSPENDED_BY_CALL) {
                    updateEnhancedState(EnhancedState.ACTIVE)
                }
                reportStatus(GuardStatus.NORMAL)
                return
            }

            RecoveryWindowOutcome.CONTINUE_POLLING -> {
                handler.postDelayed(pollingRunnable, RECOVERY_POLL_MS)
                return
            }

            RecoveryWindowOutcome.STOP_TIMEOUT,
            RecoveryWindowOutcome.STOP_NO_HEADSET -> return
        }
    }

    private fun handleReleaseProbeTick() {
        if (pollingMode != PollingMode.RELEASE_PROBE) return

        val headset = releaseProbeHeadset ?: findConnectedHeadset()
        val commDevice = audioManager.communicationDevice
        when (
            ReleaseProbeResolver.resolve(
                ReleaseProbeDecisionInput(
                    hasHeadset = headset != null,
                    communicationDeviceKind = communicationDeviceKind(commDevice),
                    timedOut = System.currentTimeMillis() >= releaseProbeDeadlineMs,
                )
            ).outcome
        ) {
            ReleaseProbeOutcome.STOP_NO_HEADSET -> {
                stopReleaseProbe("归还观察期间未检测到耳机")
                if (enhancedModeEnabled) {
                    updateEnhancedState(EnhancedState.WAITING_HEADSET)
                }
                reportStatus(GuardStatus.NO_HEADSET)
                return
            }

            ReleaseProbeOutcome.HANDLE_BUILTIN_ROUTE -> {
                val availableHeadset = headset ?: return
                val builtInDevice = commDevice ?: return
                if (maybeContinueClassicBluetoothManualReleaseObservation(availableHeadset, builtInDevice)) {
                    return
                }
                rememberBuiltinRouteEvidence(builtInDevice.type)
                handleReleaseProbeBuiltinRouteDetected(availableHeadset, builtInDevice)
                return
            }

            ReleaseProbeOutcome.FINISH_NORMAL -> {
                clearBuiltinRouteEvidence()
                stopReleaseProbe("归还系统后未再确认持续劫持")
                clearHeldRouteState()
                if (enhancedModeEnabled && enhancedState != EnhancedState.SUSPENDED_BY_CALL) {
                    updateEnhancedState(EnhancedState.ACTIVE)
                }
                reportStatus(GuardStatus.NORMAL)
                return
            }

            ReleaseProbeOutcome.CONTINUE_POLLING -> {
                handler.postDelayed(pollingRunnable, RELEASE_PROBE_POLL_MS)
                return
            }
        }
    }

    private fun startClearProbe(preferredHeadset: AudioDeviceInfo?, reason: String) {
        if (!running || !enhancedModeEnabled) return
        if (pollingMode == PollingMode.CLEAR_PROBE) return

        val headset = preferredHeadset ?: findConnectedHeadset()
        if (headset == null) {
            updateEnhancedState(EnhancedState.WAITING_HEADSET)
            reportStatus(GuardStatus.NO_HEADSET)
            return
        }

        stopClassicBluetoothConfirm("切换到增强观察模式", announce = false)
        stopClassicBluetoothStartupObservation("切换到增强观察模式", announce = false)
        stopRecoveryWindow("切换到增强观察模式")
        stopReleaseProbe("切换到增强观察模式")
        clearProbeHeadset = headset
        clearProbeDeadlineMs = System.currentTimeMillis() + CLEAR_PROBE_WINDOW_MS
        pollingMode = PollingMode.CLEAR_PROBE
        addLog(
            "$reason，先释放本应用占用并观察系统是否自动恢复",
            code = FixEventCode.CLEAR_PROBE_STARTED,
            level = FixEventLevel.WARNING,
        )
        clearCommunicationDeviceSafely()
        updateEnhancedState(EnhancedState.CLEAR_PROBE)
        reportStatus(GuardStatus.HIJACKED)
        handler.removeCallbacks(pollingRunnable)
        handler.post(pollingRunnable)
        syncClassicBluetoothSoftGuard("切换到增强观察模式")
    }

    private fun stopClearProbe(reason: String) {
        if (pollingMode != PollingMode.CLEAR_PROBE) return
        pollingMode = PollingMode.IDLE
        clearProbeHeadset = null
        handler.removeCallbacks(pollingRunnable)
        addLog(
            "退出增强观察: $reason",
            code = FixEventCode.CLEAR_PROBE_STOPPED,
        )
        syncClassicBluetoothSoftGuard("退出增强观察")
    }

    private fun startRecoveryWindow(reason: String) {
        stopClassicBluetoothConfirm("切换到恢复观察窗口", announce = false)
        stopClassicBluetoothStartupObservation("切换到恢复观察窗口", announce = false)
        val currentComm = audioManager.communicationDevice?.productName
        val currentHeadset = findConnectedHeadset()?.productName
        addLog(
            "进入恢复观察窗口: $reason, comm=$currentComm, headset=$currentHeadset",
            code = FixEventCode.RECOVERY_WINDOW_STARTED,
        )
        pollingMode = PollingMode.RECOVERY_WINDOW
        recoveryDeadlineMs = System.currentTimeMillis() + RECOVERY_WINDOW_MS
        stableHitCount = 0
        handler.removeCallbacks(pollingRunnable)
        handler.post(pollingRunnable)
        syncClassicBluetoothSoftGuard("进入恢复观察窗口")
    }

    private fun stopRecoveryWindow(reason: String) {
        if (pollingMode != PollingMode.RECOVERY_WINDOW) return
        pollingMode = PollingMode.IDLE
        stableHitCount = 0
        handler.removeCallbacks(pollingRunnable)
        addLog(
            "退出恢复观察窗口: $reason",
            code = FixEventCode.RECOVERY_WINDOW_STOPPED,
        )
        syncClassicBluetoothSoftGuard("退出恢复观察窗口")
    }

    private fun startReleaseProbe(preferredHeadset: AudioDeviceInfo?, reason: String) {
        if (!running) return
        if (!hasGuardCommunicationHold()) {
            stopRecoveryWindow("无需归还系统")
            reportStatus(GuardStatus.NORMAL)
            return
        }

        val headset = preferredHeadset ?: findConnectedHeadset()
        if (headset == null) {
            stopRecoveryWindow("归还系统前未检测到耳机")
            if (enhancedModeEnabled) {
                updateEnhancedState(EnhancedState.WAITING_HEADSET)
            }
            reportStatus(GuardStatus.NO_HEADSET)
            return
        }

        stopClassicBluetoothConfirm("切换到归还系统观察", announce = false)
        stopClassicBluetoothStartupObservation("切换到归还系统观察", announce = false)
        stopClearProbe("切换到归还系统观察")
        stopRecoveryWindow("切换到归还系统观察")
        releaseProbeHeadset = headset
        releaseProbeDeadlineMs = System.currentTimeMillis() + RELEASE_PROBE_WINDOW_MS
        pollingMode = PollingMode.RELEASE_PROBE
        addLog(
            "$reason，尝试归还系统并观察是否仍存在劫持",
            code = FixEventCode.RELEASE_PROBE_STARTED,
            level = FixEventLevel.WARNING,
        )
        clearCommunicationDeviceSafely()
        if (enhancedModeEnabled) {
            tryLeaveCommunicationMode("$reason，尝试归还系统")
        }
        reportStatus(GuardStatus.NORMAL)
        handler.removeCallbacks(pollingRunnable)
        handler.post(pollingRunnable)
        syncClassicBluetoothSoftGuard("进入归还观察")
    }

    private fun stopReleaseProbe(reason: String) {
        if (pollingMode != PollingMode.RELEASE_PROBE) return
        pollingMode = PollingMode.IDLE
        releaseProbeHeadset = null
        handler.removeCallbacks(pollingRunnable)
        addLog(
            "退出归还观察: $reason",
            code = FixEventCode.RELEASE_PROBE_STOPPED,
        )
        syncClassicBluetoothSoftGuard("退出归还观察")
    }

    fun start() {
        runOnMonitorThread {
            if (running) return@runOnMonitorThread
            running = true
            audioManager.addOnCommunicationDeviceChangedListener(
                callbackExecutor,
                commDeviceListener
            )
            audioManager.registerAudioDeviceCallback(deviceCallback, handler)
            if (enhancedModeEnabled) {
                registerModeListenerIfNeeded()
            }
            addLog(
                "守护已启动",
                code = FixEventCode.GUARD_STARTED,
                level = FixEventLevel.SUCCESS,
            )
            val headset = findConnectedHeadset()
            if (headset == null) {
                if (enhancedModeEnabled) {
                    updateEnhancedState(EnhancedState.WAITING_HEADSET)
                }
                reportStatus(GuardStatus.NO_HEADSET)
            } else if (enhancedModeEnabled) {
                refreshEnhancedObservationState("增强守护启动", headset)
            } else if (audioManager.communicationDevice?.type in BUILTIN_TYPES) {
                if (shouldUsePassiveClassicBluetoothConfirmation(headset)) {
                    val builtInType = audioManager.communicationDevice?.type
                    startClassicBluetoothStartupObservation(
                        headset = headset,
                        builtInType = builtInType,
                        reason = "启动时通信设备显示为${builtinDeviceName(builtInType)}"
                    )
                } else {
                    restoreCommunicationToHeadset(
                        preferredOutputDevice = headset,
                        reason = "启动时检测到声道仍在${builtinDeviceName(audioManager.communicationDevice?.type)}"
                    )
                    startRecoveryWindow("启动阶段检测到内建设备")
                }
            } else {
                reportStatus(GuardStatus.NORMAL)
            }
            syncClassicBluetoothSoftGuard("守护启动后同步保真守护")
        }
    }

    fun stop() {
        callOnMonitorThread(Unit) {
            if (running) {
                running = false
                handler.removeCallbacks(reacquireEnhancedRunnable)
                stopClearProbe("守护停止")
                stopClassicBluetoothConfirm("守护停止", announce = false)
                stopClassicBluetoothStartupObservation("守护停止", announce = false)
                stopRecoveryWindow("守护停止")
                stopReleaseProbe("守护停止")
                unregisterModeListenerIfNeeded()
                tryLeaveCommunicationMode("守护停止")
                clearClassicBluetoothWidebandAttempts()
                clearBuiltinRouteEvidence()
                clearHeldRouteState()
                stopClassicBluetoothSoftGuard("守护停止")
                audioManager.removeOnCommunicationDeviceChangedListener(commDeviceListener)
                audioManager.unregisterAudioDeviceCallback(deviceCallback)
                clearCommunicationDeviceSafely()
                updateEnhancedState(EnhancedState.DISABLED)
                addLog(
                    "守护已停止",
                    code = FixEventCode.GUARD_STOPPED,
                )
            }
            monitorThread.quitSafely()
        }
    }

    fun fixNow(): Boolean {
        return callOnMonitorThread(false) {
            if (enhancedModeEnabled) {
                tryEnterCommunicationMode("用户手动触发修复")
            }
            val fixed = restoreCommunicationToHeadset(reason = "手动修复：尝试恢复声道")
            if (fixed) {
                if (enhancedModeEnabled) {
                    updateEnhancedState(EnhancedState.ACTIVE)
                }
                startRecoveryWindow("用户手动触发修复")
            }
            fixed
        }
    }

    fun setEnhancedModeEnabled(enabled: Boolean) {
        runOnMonitorThread {
            if (enhancedModeEnabled == enabled) {
                if (running && enabled) {
                    refreshEnhancedObservationState("增强守护配置已同步")
                }
                return@runOnMonitorThread
            }

            enhancedModeEnabled = enabled
            if (!running) {
                updateEnhancedState(if (enabled) EnhancedState.WAITING_HEADSET else EnhancedState.DISABLED)
                return@runOnMonitorThread
            }

            if (enabled) {
                stopClassicBluetoothConfirm("增强守护已开启", announce = false)
                stopClassicBluetoothStartupObservation("增强守护已开启", announce = false)
                registerModeListenerIfNeeded()
                refreshEnhancedObservationState("增强守护已开启")
            } else {
                handler.removeCallbacks(reacquireEnhancedRunnable)
                stopClearProbe("增强守护已关闭")
                stopClassicBluetoothConfirm("增强守护已关闭", announce = false)
                stopClassicBluetoothStartupObservation("增强守护已关闭", announce = false)
                stopRecoveryWindow("增强守护已关闭")
                stopReleaseProbe("增强守护已关闭")
                unregisterModeListenerIfNeeded()
                tryLeaveCommunicationMode("增强守护已关闭")
                clearHeldRouteState()
                clearCommunicationDeviceSafely()
                updateEnhancedState(EnhancedState.DISABLED)
            }
            syncClassicBluetoothSoftGuard("增强守护配置变化")
        }
    }

    fun isEnhancedModeEnabled(): Boolean = callOnMonitorThread(false) { enhancedModeEnabled }

    fun setClassicBluetoothSoftGuardEnabled(enabled: Boolean) {
        runOnMonitorThread {
            if (classicBluetoothState.softGuardEnabled == enabled) return@runOnMonitorThread
            classicBluetoothState = classicBluetoothState.copy(softGuardEnabled = enabled)
            if (!enabled) {
                stopClassicBluetoothStartupObservation("经典蓝牙保真守护已关闭", announce = false)
            }
            syncClassicBluetoothSoftGuard("经典蓝牙保真守护配置变化")
        }
    }

    fun isClassicBluetoothSoftGuardEnabled(): Boolean =
        callOnMonitorThread(false) { classicBluetoothState.softGuardEnabled }

    fun setClassicBluetoothWidebandEnabled(enabled: Boolean) {
        runOnMonitorThread {
            if (classicBluetoothState.wideband.enabled == enabled) return@runOnMonitorThread
            classicBluetoothState =
                classicBluetoothState.copy(
                    wideband = classicBluetoothState.wideband.copy(enabled = enabled)
                )
            if (!enabled) {
                clearClassicBluetoothWidebandAttempts()
            }
        }
    }

    fun isClassicBluetoothWidebandEnabled(): Boolean =
        callOnMonitorThread(false) { classicBluetoothState.wideband.enabled }

    fun canManuallyReleaseHeldRoute(): Boolean {
        return callOnMonitorThread(false) {
            hasManualHeldRouteRelease(findConnectedHeadset())
        }
    }

    fun getHeldRouteMessage(): String? {
        return callOnMonitorThread<String?>(null) {
            currentHeldRouteMessage(findConnectedHeadset())
        }
    }

    fun getPublicProjectionInput(statusOverride: GuardStatus? = null): GuardPublicProjectionInput {
        return callOnMonitorThread(
            GuardPublicProjectionInput(
                status = GuardStatus.NO_HEADSET,
                enhancedState = EnhancedState.DISABLED,
                enhancedModeEnabled = false,
                headsetName = null,
                heldRouteMessage = null,
                canManuallyReleaseHeldRoute = false,
            )
        ) {
            val headset = findConnectedHeadset()
            MonitorSnapshotProjector.publicProjectionInput(
                snapshot = buildMonitorSnapshot(headset),
                statusOverride = statusOverride,
            )
        }
    }

    internal fun getNotificationSnapshotInputs(
        statusOverride: GuardStatus? = null,
    ): NotificationSnapshotInputs {
        return callOnMonitorThread(
            NotificationSnapshotInputs(
                publicProjectionInput =
                    GuardPublicProjectionInput(
                        status = GuardStatus.NO_HEADSET,
                        enhancedState = EnhancedState.DISABLED,
                        enhancedModeEnabled = false,
                        headsetName = null,
                        heldRouteMessage = null,
                        canManuallyReleaseHeldRoute = false,
                    ),
                alertSnapshot =
                    GuardStatusAlertController.Snapshot(
                        status = GuardStatus.NO_HEADSET,
                        headsetName = null,
                        hasHeadset = false,
                        heldRouteMessage = null,
                        canManuallyReleaseHeldRoute = false,
                    ),
            )
        ) {
            val headset = findConnectedHeadset()
            MonitorSnapshotProjector.notificationSnapshotInputs(
                snapshot = buildMonitorSnapshot(headset),
                statusOverride = statusOverride,
            )
        }
    }

    fun tryManualReleaseHeldRoute(trigger: String): Boolean {
        return callOnMonitorThread(false) {
            if (!running || !hasGuardCommunicationHold()) return@callOnMonitorThread false

            val headset = findConnectedHeadset() ?: return@callOnMonitorThread false
            if (!hasActiveHeldRoute(headset) || heldRouteState.manualReleaseInProgress) {
                return@callOnMonitorThread false
            }

            val kind = heldRouteKindFor(headset)
            heldRouteState =
                HeldRouteStateReducer.startManualRelease(
                    headsetKey = deviceIdentityKey(headset),
                    kind = kind,
                    message = "正在尝试归还${heldRouteSubject(kind)}控制权",
                )
            notifyCurrentStatusChanged()
            startReleaseProbe(headset, "$trigger，尝试归还${heldRouteSubject(kind)}控制权")
            true
        }
    }

    fun getEnhancedState(): EnhancedState {
        return callOnMonitorThread(EnhancedState.DISABLED) {
            if (enhancedModeEnabled) enhancedState else EnhancedState.DISABLED
        }
    }

    fun getStatus(): GuardStatus {
        return callOnMonitorThread(GuardStatus.NO_HEADSET) {
            resolveCurrentGuardStatus(findConnectedHeadset())
        }
    }

    fun findConnectedHeadset(): AudioDeviceInfo? {
        return callOnMonitorThread<AudioDeviceInfo?>(null) {
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .firstOrNull { it.type in HEADSET_TYPES && it.isSink }
        }
    }

    fun getCommunicationDevice(): AudioDeviceInfo? {
        return callOnMonitorThread<AudioDeviceInfo?>(null) {
            audioManager.communicationDevice
        }
    }

    private fun updateEnhancedState(state: EnhancedState) {
        if (enhancedState == state) return
        enhancedState = state
        val listeners = synchronized(listenerLock) { enhancedStateListeners.toList() }
        postToMainThread {
            onEnhancedStateChanged?.invoke(state)
            listeners.forEach { it(state) }
        }
    }

    private fun registerModeListenerIfNeeded() {
        if (modeListenerRegistered) return
        audioManager.addOnModeChangedListener(
            callbackExecutor,
            modeChangedListener
        )
        modeListenerRegistered = true
    }

    private fun unregisterModeListenerIfNeeded() {
        if (!modeListenerRegistered) return
        audioManager.removeOnModeChangedListener(modeChangedListener)
        modeListenerRegistered = false
    }

    private fun refreshEnhancedObservationState(
        reason: String,
        preferredHeadset: AudioDeviceInfo? = null,
    ) {
        if (!running || !enhancedModeEnabled) return

        if (shouldSuspendForCall(audioManager.mode)) {
            updateEnhancedState(EnhancedState.SUSPENDED_BY_CALL)
            return
        }

        val headset = preferredHeadset ?: findConnectedHeadset()
        if (headset == null) {
            updateEnhancedState(EnhancedState.WAITING_HEADSET)
            reportStatus(GuardStatus.NO_HEADSET)
            return
        }

        val commDevice = audioManager.communicationDevice
        if (commDevice?.type in BUILTIN_TYPES) {
            rememberBuiltinRouteEvidence(commDevice?.type)
            startClearProbe(headset, "$reason，检测到声道在${builtinDeviceName(commDevice?.type)}")
            return
        }

        if (pollingMode == PollingMode.IDLE) {
            updateEnhancedState(EnhancedState.ACTIVE)
        }
        syncHeldRouteTracking(headset)
        reportStatus(currentStableRouteStatus(headset))
        syncClassicBluetoothSoftGuard("增强守护监听状态变化")
    }

    private fun handleEnhancedBuiltinRouteDetected(
        headset: AudioDeviceInfo,
        builtInDevice: AudioDeviceInfo,
    ) {
        rememberBuiltinRouteEvidence(builtInDevice.type)
        val builtInName = builtinDeviceName(builtInDevice.type)
        when (pollingMode) {
            PollingMode.RECOVERY_WINDOW -> {
                stableHitCount = 0
                restoreCommunicationToHeadset(
                    preferredOutputDevice = headset,
                    reason = "恢复观察窗口内检测到声道再次被劫持到$builtInName，立即恢复到耳机"
                )
            }

            PollingMode.RELEASE_PROBE -> {
                stopReleaseProbe("归还系统后回调检测到劫持再次出现")
                tryEnterCommunicationMode("归还系统后回调检测到劫持再次出现")
                val fixed = restoreCommunicationToHeadset(
                    preferredOutputDevice = headset,
                    reason = "归还系统后仍被劫持到$builtInName，重新接管到耳机"
                )
                if (enhancedState != EnhancedState.SUSPENDED_BY_CALL) {
                    updateEnhancedState(EnhancedState.ACTIVE)
                }
                if (fixed) {
                    startRecoveryWindow("归还系统后回调检测到劫持，重新接管")
                }
            }

            else -> startClearProbe(headset, "检测到声道被劫持到$builtInName")
        }
    }

    private fun maybeReacquireEnhancedMode(reason: String) {
        if (!running || !enhancedModeEnabled || !hasGuardCommunicationHold()) return

        if (shouldSuspendForCall(audioManager.mode)) {
            updateEnhancedState(EnhancedState.SUSPENDED_BY_CALL)
            return
        }

        val headset = findConnectedHeadset()
        if (headset == null) {
            updateEnhancedState(EnhancedState.WAITING_HEADSET)
            return
        }

        tryEnterCommunicationMode(reason)
        updateEnhancedState(EnhancedState.ACTIVE)

        val commDevice = audioManager.communicationDevice
        val communicationHeadset = findAvailableCommunicationHeadset(headset)
        when {
            commDevice?.type in BUILTIN_TYPES -> {
                startClearProbe(headset, "$reason，检测到声道在${builtinDeviceName(commDevice?.type)}")
            }

            communicationHeadset != null &&
                (commDevice == null || !isSamePhysicalDevice(commDevice, communicationHeadset)) -> {
                val fixed = restoreCommunicationToHeadset(
                    preferredOutputDevice = headset,
                    reason = "$reason，维持耳机通信路由"
                )
                if (fixed) {
                    startRecoveryWindow("$reason，已主动保持耳机通信路由")
                }
            }

            else -> {
                syncHeldRouteTracking(headset)
                reportStatus(currentStableRouteStatus(headset))
            }
        }
        syncClassicBluetoothSoftGuard("增强守护尝试重新接管后同步保真守护")
    }

    private fun shouldKeepHeldRouteAfterRecovery(headset: AudioDeviceInfo): Boolean {
        val commDevice = audioManager.communicationDevice
        val communicationHeadset = findAvailableCommunicationHeadset(headset)
        return HeldRouteRecoveryResolver.resolve(
            HeldRouteRecoveryDecisionInput(
                hasGuardCommunicationHold = hasGuardCommunicationHold(),
                hasActiveHeldRoute = hasActiveHeldRoute(headset),
                communicationDeviceIsClassicBluetooth =
                    commDevice?.let { isClassicBluetoothDevice(it) } ?: false,
                communicationDeviceMatchesAvailableHeadset =
                    commDevice != null &&
                        communicationHeadset != null &&
                        isSamePhysicalDevice(commDevice, communicationHeadset),
            )
        ).outcome == HeldRouteRecoveryOutcome.KEEP_HELD_ROUTE
    }

    private fun enterHeldRoute(headset: AudioDeviceInfo, reason: String) {
        val kind = heldRouteKindFor(headset)
        val headsetKey = deviceIdentityKey(headset)
        val message = heldRouteIdleMessage(kind)
        val nextState = HeldRouteStateReducer.enter(headsetKey, kind, message)
        val changed = heldRouteState != nextState

        heldRouteState = nextState
        addLog(
            "$reason，${heldRoutePinnedLabel(headset, kind)}暂不自动归还，等待用户手动解除限制",
            code = FixEventCode.HELD_ROUTE_ENTERED,
            level = FixEventLevel.WARNING,
        )
        if (changed) {
            notifyCurrentStatusChanged()
        }
        syncClassicBluetoothSoftGuard("进入限制保持")
    }

    private fun markHeldRouteReleaseFailed(headset: AudioDeviceInfo, builtInName: String) {
        val kind = heldRouteKindFor(headset)
        val headsetKey = deviceIdentityKey(headset)
        val message = heldRouteRetryMessage(kind, heldRouteState.manualReleaseInProgress)
        val nextState = HeldRouteStateReducer.reclaim(headsetKey, kind, message)
        val changed = heldRouteState != nextState

        heldRouteState = nextState
        addLog(
            "检测到声道重新被劫持到$builtInName，已重新接管${heldRouteSubject(kind)}",
            code = FixEventCode.HELD_ROUTE_RECLAIMED,
            level = FixEventLevel.WARNING,
        )
        if (changed) {
            notifyCurrentStatusChanged()
        }
        syncClassicBluetoothSoftGuard("归还失败后继续保持限制")
    }

    private fun clearHeldRouteState() {
        val clearedState = HeldRouteStateReducer.clear()
        val changed = heldRouteState != clearedState

        heldRouteState = clearedState
        if (changed) {
            notifyCurrentStatusChanged()
        }
        syncClassicBluetoothSoftGuard("清理限制保持")
    }

    private fun isClassicBluetoothOutputDevice(device: AudioDeviceInfo): Boolean {
        return device.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
            device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
    }

    private fun communicationDeviceKind(device: AudioDeviceInfo?): CommunicationDeviceKind {
        return when {
            device == null -> CommunicationDeviceKind.NONE
            device.type in BUILTIN_TYPES -> CommunicationDeviceKind.BUILTIN
            else -> CommunicationDeviceKind.EXTERNAL
        }
    }

    private fun handleReleaseProbeBuiltinRouteDetected(
        headset: AudioDeviceInfo,
        builtInDevice: AudioDeviceInfo,
    ) {
        rememberBuiltinRouteEvidence(builtInDevice.type)
        val builtInName = builtinDeviceName(builtInDevice.type)
        stopReleaseProbe("归还系统后回调检测到劫持再次出现")
        if (enhancedModeEnabled) {
            tryEnterCommunicationMode("归还系统后回调检测到劫持再次出现")
        }
        val fixed = restoreCommunicationToHeadset(
            preferredOutputDevice = headset,
            reason = "归还系统后仍被劫持到$builtInName，重新接管到耳机"
        )
        if (fixed) {
            markHeldRouteReleaseFailed(headset, builtInName)
        } else {
            clearHeldRouteState()
        }
        if (enhancedModeEnabled && enhancedState != EnhancedState.SUSPENDED_BY_CALL) {
            updateEnhancedState(EnhancedState.ACTIVE)
        }
        if (fixed) {
            startRecoveryWindow("归还系统后回调检测到劫持，重新接管")
        }
    }

    private fun tryEnterCommunicationMode(reason: String) {
        if (shouldSuspendForCall(audioManager.mode)) return

        if (audioManager.mode == AudioManager.MODE_IN_COMMUNICATION) {
            return
        }

        try {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            modeRequestedByEnhanced = audioManager.mode == AudioManager.MODE_IN_COMMUNICATION
            if (modeRequestedByEnhanced) {
                addLog(
                    "增强守护已申请通信模式: $reason",
                    code = FixEventCode.COMMUNICATION_MODE_ACQUIRED,
                    level = FixEventLevel.SUCCESS,
                )
            } else {
                addLog(
                    "增强守护申请通信模式未生效: $reason",
                    code = FixEventCode.COMMUNICATION_MODE_FAILED,
                    level = FixEventLevel.WARNING,
                )
            }
        } catch (exception: RuntimeException) {
            addLog(
                "增强守护申请通信模式失败(${exception.javaClass.simpleName})",
                code = FixEventCode.COMMUNICATION_MODE_FAILED,
                level = FixEventLevel.ERROR,
            )
        }
        syncClassicBluetoothSoftGuard("申请通信模式")
    }

    private fun tryLeaveCommunicationMode(reason: String) {
        if (!modeRequestedByEnhanced) return

        try {
            if (audioManager.mode == AudioManager.MODE_IN_COMMUNICATION && !shouldSuspendForCall(audioManager.mode)) {
                audioManager.mode = AudioManager.MODE_NORMAL
            }
            addLog(
                "增强守护已释放通信模式: $reason",
                code = FixEventCode.COMMUNICATION_MODE_RELEASED,
            )
        } catch (exception: RuntimeException) {
            Log.w(TAG, "leaveCommunicationMode failed", exception)
        } finally {
            modeRequestedByEnhanced = false
        }
        syncClassicBluetoothSoftGuard("释放通信模式")
    }

    private fun shouldSuspendForCall(mode: Int): Boolean {
        return mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_RINGTONE
    }

    private fun restoreCommunicationToHeadset(
        preferredOutputDevice: AudioDeviceInfo? = null,
        reason: String,
    ): Boolean {
        val outputHeadset = preferredOutputDevice ?: findConnectedHeadset()
        if (outputHeadset == null) {
            addLog(
                "无法修复：未检测到耳机",
                code = FixEventCode.RESTORE_FAILED,
                level = FixEventLevel.WARNING,
            )
            reportStatus(GuardStatus.NO_HEADSET)
            return false
        }

        addLog(
            reason,
            code = FixEventCode.RESTORE_REQUESTED,
        )

        val communicationHeadset = findAvailableCommunicationHeadset(outputHeadset)
        if (communicationHeadset == null) {
            addLog(
                "已连接 ${outputHeadset.productName}，但系统当前未提供可用的通信耳机设备",
                code = FixEventCode.RESTORE_SKIPPED,
                level = FixEventLevel.WARNING,
            )
            reportStatus(GuardStatus.HIJACKED)
            return false
        }

        val restoreAttemptKey = deviceIdentityKey(communicationHeadset)
        val now = SystemClock.elapsedRealtime()
        val lastAttemptKey = lastRestoreAttemptDeviceKey
        if (
            restoreAttemptKey != null &&
            restoreAttemptKey == lastAttemptKey &&
            now - lastRestoreAttemptAtElapsedMs < RESTORE_REQUEST_DEBOUNCE_MS
        ) {
            return true
        }

        lastRestoreAttemptDeviceKey = restoreAttemptKey
        lastRestoreAttemptAtElapsedMs = now
        return setCommunicationDeviceSafely(communicationHeadset)
    }

    private fun findAvailableCommunicationHeadset(preferredOutputDevice: AudioDeviceInfo? = null): AudioDeviceInfo? {
        val candidates = audioManager.availableCommunicationDevices
            .filter { it.type in COMMUNICATION_HEADSET_TYPES }

        if (preferredOutputDevice != null) {
            candidates.firstOrNull { isSamePhysicalDevice(it, preferredOutputDevice) }?.let { return it }
        }

        return candidates.firstOrNull()
    }

    private fun isSamePhysicalDevice(first: AudioDeviceInfo, second: AudioDeviceInfo): Boolean {
        return AudioDeviceIdentityResolver.isSamePhysicalDevice(
            firstAddress = first.address,
            firstProductName = first.productName?.toString(),
            secondAddress = second.address,
            secondProductName = second.productName?.toString(),
        )
    }

    private fun setCommunicationDeviceSafely(device: AudioDeviceInfo): Boolean {
        return try {
            val previousCommunicationDevice = audioManager.communicationDevice
            val result = audioManager.setCommunicationDevice(device)
            if (result) {
                guardOwnsCommunicationDevice = true
                syncHeldRouteTracking(device)
                addLog(
                    "已将声道恢复到 ${device.productName}",
                    code = FixEventCode.RESTORE_SUCCEEDED,
                    level = FixEventLevel.SUCCESS,
                )
                reportStatus(GuardStatus.FIXED)
                maybeApplyClassicBluetoothWidebandHint(device, previousCommunicationDevice)
                syncClassicBluetoothSoftGuard("已建立强制通信路由")
            } else {
                addLog(
                    "系统未接受通信设备切换请求: ${device.productName}",
                    code = FixEventCode.RESTORE_REJECTED,
                    level = FixEventLevel.WARNING,
                )
                reportStatus(GuardStatus.HIJACKED)
            }
            result
        } catch (exception: IllegalArgumentException) {
            addLog(
                "通信设备切换失败(${exception.javaClass.simpleName}): ${device.productName}",
                code = FixEventCode.RESTORE_FAILED,
                level = FixEventLevel.ERROR,
            )
            reportStatus(GuardStatus.HIJACKED)
            false
        } catch (exception: IllegalStateException) {
            addLog(
                "通信设备切换失败(${exception.javaClass.simpleName}): ${device.productName}",
                code = FixEventCode.RESTORE_FAILED,
                level = FixEventLevel.ERROR,
            )
            reportStatus(GuardStatus.HIJACKED)
            false
        } catch (exception: SecurityException) {
            addLog(
                "通信设备切换失败(${exception.javaClass.simpleName}): ${device.productName}",
                code = FixEventCode.RESTORE_FAILED,
                level = FixEventLevel.ERROR,
            )
            reportStatus(GuardStatus.HIJACKED)
            false
        }
    }

    // This parameter is platform-dependent; treat it as a best-effort hint only.
    private fun maybeApplyClassicBluetoothWidebandHint(
        targetDevice: AudioDeviceInfo,
        previousCommunicationDevice: AudioDeviceInfo?,
    ) {
        val attemptKey = classicBluetoothWidebandAttemptKey(targetDevice)
        val now = System.currentTimeMillis()
        val lastAttemptAt = classicBluetoothState.wideband.attemptTimesMs[attemptKey]
        val decision = ClassicBluetoothWidebandAttemptResolver.resolve(
            ClassicBluetoothWidebandAttemptInput(
                monitorRunning = running,
                widebandEnabled = classicBluetoothState.wideband.enabled,
                isClassicBluetoothTarget = isClassicBluetoothDevice(targetDevice),
                suspendedByCall = shouldSuspendForCall(audioManager.mode),
                previousDeviceKind = communicationDeviceKind(previousCommunicationDevice),
                previousDeviceMatchesTarget =
                    previousCommunicationDevice?.let { isSamePhysicalDevice(it, targetDevice) } ?: false,
                hasRecentAttempt =
                    lastAttemptAt != null &&
                        now - lastAttemptAt < CLASSIC_BLUETOOTH_WIDEBAND_COOLDOWN_MS,
            )
        )
        if (decision.outcome != ClassicBluetoothWidebandAttemptOutcome.APPLY_HINT) {
            return
        }

        rememberClassicBluetoothWidebandAttempt(attemptKey, now)
        applyClassicBluetoothWidebandHint(
            targetDevice,
            "已尝试为经典蓝牙争取更清晰的通话音质（16k）"
        )
        CLASSIC_BLUETOOTH_WIDEBAND_RETRY_DELAYS_MS.forEach { delayMs ->
            handler.postDelayed({
                retryClassicBluetoothWidebandHint(targetDevice, delayMs)
            }, delayMs)
        }
    }

    private fun retryClassicBluetoothWidebandHint(targetDevice: AudioDeviceInfo, delayMs: Long) {
        if (
            !running ||
            !classicBluetoothState.wideband.enabled ||
            !isClassicBluetoothDevice(targetDevice) ||
            shouldSuspendForCall(audioManager.mode)
        ) {
            return
        }

        val currentCommunicationDevice = audioManager.communicationDevice ?: return
        if (!isSamePhysicalDevice(currentCommunicationDevice, targetDevice)) return

        applyClassicBluetoothWidebandHint(
            targetDevice,
            "已再次尝试保持经典蓝牙更清晰通话音质（延迟${delayMs}ms）"
        )
    }

    private fun applyClassicBluetoothWidebandHint(targetDevice: AudioDeviceInfo, successMessage: String) {
        try {
            audioManager.setParameters(CLASSIC_BLUETOOTH_WIDEBAND_HINT)
            addLog(
                "$successMessage: ${targetDevice.productName}",
                code = FixEventCode.WIDEBAND_HINT_SUCCEEDED,
                level = FixEventLevel.SUCCESS,
            )
        } catch (exception: RuntimeException) {
            addLog(
                "尝试提升经典蓝牙通话音质失败(${exception.javaClass.simpleName}): ${targetDevice.productName}",
                code = FixEventCode.WIDEBAND_HINT_FAILED,
                level = FixEventLevel.WARNING,
            )
        }
    }

    private fun isClassicBluetoothDevice(device: AudioDeviceInfo): Boolean {
        return device.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
            device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
    }

    private fun classicBluetoothWidebandAttemptKey(device: AudioDeviceInfo): String {
        val address = device.address.orEmpty()
        if (address.isNotEmpty()) {
            return "${device.type}:$address"
        }
        return "${device.type}:${device.productName}"
    }

    private fun rememberClassicBluetoothWidebandAttempt(attemptKey: String, atMs: Long) {
        classicBluetoothState =
            classicBluetoothState.copy(
                wideband =
                    classicBluetoothState.wideband.copy(
                        attemptTimesMs = classicBluetoothState.wideband.attemptTimesMs + (attemptKey to atMs)
                    )
            )
    }

    private fun clearClassicBluetoothWidebandAttempts() {
        if (classicBluetoothState.wideband.attemptTimesMs.isEmpty()) {
            return
        }
        classicBluetoothState =
            classicBluetoothState.copy(
                wideband = classicBluetoothState.wideband.copy(attemptTimesMs = emptyMap())
            )
    }

    private fun clearCommunicationDeviceSafely() {
        try {
            audioManager.clearCommunicationDevice()
            guardOwnsCommunicationDevice = false
        } catch (exception: IllegalStateException) {
            Log.w(TAG, "clearCommunicationDevice failed", exception)
        } catch (exception: SecurityException) {
            Log.w(TAG, "clearCommunicationDevice failed", exception)
        }
    }

    private fun rememberBuiltinRouteEvidence(deviceType: Int?) {
        if (deviceType !in BUILTIN_TYPES) return
        lastBuiltinRouteEvidenceAtElapsedMs = SystemClock.elapsedRealtime()
    }

    private fun clearBuiltinRouteEvidence() {
        lastBuiltinRouteEvidenceAtElapsedMs = 0L
    }

    private fun hasRecentBuiltinRouteEvidence(nowElapsedMs: Long = SystemClock.elapsedRealtime()): Boolean {
        val lastEvidenceAt = lastBuiltinRouteEvidenceAtElapsedMs
        return lastEvidenceAt != 0L &&
            nowElapsedMs - lastEvidenceAt <= SOFT_GUARD_HIJACK_EVIDENCE_WINDOW_MS
    }

    private fun maybeLogSoftGuardPassiveSkip(
        headset: AudioDeviceInfo,
        builtInDevice: AudioDeviceInfo,
        nowElapsedMs: Long,
    ) {
        if (
            nowElapsedMs - classicBluetoothState.softGuardRuntime.lastPassiveSkipLoggedAtElapsedMs <
            SOFT_GUARD_PASSIVE_SKIP_LOG_COOLDOWN_MS
        ) {
            return
        }
        classicBluetoothState =
            classicBluetoothState.copy(
                softGuardRuntime =
                    classicBluetoothState.softGuardRuntime.copy(
                        lastPassiveSkipLoggedAtElapsedMs = nowElapsedMs
                    )
            )
        addLog(
            "经典蓝牙保真守护检测到无障碍音频落到${builtinDeviceName(builtInDevice.type)}，" +
                "但未发现明确声道劫持，暂不强制接管 ${headset.productName}",
            code = FixEventCode.SOFT_GUARD_SKIPPED,
        )
    }

    private fun syncClassicBluetoothSoftGuard(reason: String) {
        handler.removeCallbacks(classicBluetoothSoftGuardVerificationRunnable)

        val headset = findConnectedHeadset()
        val decision = ClassicBluetoothSoftGuardSyncResolver.resolve(
            ClassicBluetoothSoftGuardSyncInput(
                monitorRunning = running,
                softGuardEnabled = classicBluetoothState.softGuardEnabled,
                hasHeadset = headset != null,
                isClassicBluetoothHeadset =
                    headset?.let { isClassicBluetoothOutputDevice(it) } ?: false,
                pollingPhase = pollingMode.toRoutePollingPhase(),
                hasStartupObservation = hasClassicBluetoothStartupObservation(),
                manualReleaseInProgress = heldRouteState.manualReleaseInProgress,
                hasGuardCommunicationHold = hasGuardCommunicationHold(),
                suspendedByCall = shouldSuspendForCall(audioManager.mode),
            )
        )

        if (!decision.shouldRun) {
            stopClassicBluetoothSoftGuard(reason)
            return
        }

        val targetHeadset = headset ?: return
        val previousTarget = classicBluetoothSoftGuard.getTargetDevice()
        val previousMode = classicBluetoothSoftGuard.getRoutingMode()
        val wasRunning = classicBluetoothSoftGuard.isRunning()
        // In passive confirmation windows we only want to observe the actual accessibility route.
        val requestedMode = AccessibilitySoftRouteGuard.RoutingMode.OBSERVE
        if (!classicBluetoothSoftGuard.startOrUpdate(targetHeadset, requestedMode)) {
            stopClassicBluetoothSoftGuard("$reason，保真守护启动失败")
            addLog(
                "经典蓝牙保真守护启动失败: ${targetHeadset.productName}",
                code = FixEventCode.SOFT_GUARD_START_FAILED,
                level = FixEventLevel.WARNING,
            )
            return
        }

        val retargeted =
            !wasRunning ||
                previousTarget == null ||
                !isSamePhysicalDevice(previousTarget, targetHeadset) ||
                previousMode != requestedMode
        if (retargeted) {
            classicBluetoothState =
                classicBluetoothState.copy(
                    softGuardRuntime =
                        classicBluetoothState.softGuardRuntime.copy(
                            startedAtElapsedMs = SystemClock.elapsedRealtime()
                        )
                )
            addLog(
                "经典蓝牙保真守护已启动(观察模式): ${targetHeadset.productName}",
                code = FixEventCode.SOFT_GUARD_STARTED,
            )
        }
        if (pollingMode == PollingMode.IDLE) {
            handler.postDelayed(
                classicBluetoothSoftGuardVerificationRunnable,
                SOFT_GUARD_VERIFY_DELAY_MS
            )
        }
    }

    private fun stopClassicBluetoothSoftGuard(reason: String) {
        val wasRunning = classicBluetoothSoftGuard.isRunning()
        handler.removeCallbacks(classicBluetoothSoftGuardVerificationRunnable)
        classicBluetoothSoftGuard.stop()
        if (wasRunning) {
            addLog(
                "经典蓝牙保真守护已停止: $reason",
                code = FixEventCode.SOFT_GUARD_STOPPED,
            )
        }
    }

    private fun handleClassicBluetoothSoftGuardRouteChanged(routedDevice: AudioDeviceInfo?) {
        evaluateClassicBluetoothSoftGuardRoute("经典蓝牙保真守护检测到路由变化", routedDevice)
    }

    private fun evaluateClassicBluetoothSoftGuardRoute(
        reason: String,
        routedDevice: AudioDeviceInfo? = classicBluetoothSoftGuard.getRoutedDevice(),
    ) {
        val now = SystemClock.elapsedRealtime()
        val headset = findConnectedHeadset()
        val currentRoutedDevice = routedDevice
        when (
            ClassicBluetoothSoftGuardResolver.resolve(
                ClassicBluetoothSoftGuardDecisionInput(
                    monitorRunning = running,
                    softGuardEnabled = classicBluetoothState.softGuardEnabled,
                    softGuardRunning = classicBluetoothSoftGuard.isRunning(),
                    pollingPhase = pollingMode.toRoutePollingPhase(),
                    hasGuardCommunicationHold = hasGuardCommunicationHold(),
                    suspendedByCall = shouldSuspendForCall(audioManager.mode),
                    hasHeadset = headset != null,
                    isClassicBluetoothHeadset =
                        headset?.let { isClassicBluetoothOutputDevice(it) } ?: false,
                    routedDeviceKind = communicationDeviceKind(currentRoutedDevice),
                    hasRecentBuiltinRouteEvidence = hasRecentBuiltinRouteEvidence(now),
                    hasWaitedForVerifyDelay =
                        now - classicBluetoothState.softGuardRuntime.startedAtElapsedMs >= SOFT_GUARD_VERIFY_DELAY_MS,
                    isEscalationCooldownElapsed =
                        now - classicBluetoothState.softGuardRuntime.lastEscalationAtElapsedMs >=
                            SOFT_GUARD_HARD_RECLAIM_COOLDOWN_MS,
                )
            ).outcome
        ) {
            ClassicBluetoothSoftGuardOutcome.IGNORE -> return

            ClassicBluetoothSoftGuardOutcome.PASSIVE_SKIP -> {
                val availableHeadset = headset ?: return
                val builtInRoutedDevice = currentRoutedDevice ?: return
                maybeLogSoftGuardPassiveSkip(availableHeadset, builtInRoutedDevice, now)
                return
            }

            ClassicBluetoothSoftGuardOutcome.WAIT_FOR_VERIFY_DELAY -> {
                handler.removeCallbacks(classicBluetoothSoftGuardVerificationRunnable)
                handler.postDelayed(
                    classicBluetoothSoftGuardVerificationRunnable,
                    SOFT_GUARD_VERIFY_DELAY_MS
                )
                return
            }

            ClassicBluetoothSoftGuardOutcome.WAIT_FOR_ESCALATION_COOLDOWN -> return

            ClassicBluetoothSoftGuardOutcome.ESCALATE -> {
                classicBluetoothState =
                    classicBluetoothState.copy(
                        softGuardRuntime =
                            classicBluetoothState.softGuardRuntime.copy(
                                lastEscalationAtElapsedMs = now
                            )
                    )
            }
        }

        val availableHeadset = headset ?: return
        val builtInRoutedDevice = currentRoutedDevice ?: return

        stopClassicBluetoothStartupObservation(
            "保真守护已确认实际出声落到${builtinDeviceName(builtInRoutedDevice.type)}",
            announce = false,
            clearEvidence = false
        )
        addLog(
            "$reason，已升级为强制恢复: ${availableHeadset.productName}",
            code = FixEventCode.SOFT_GUARD_ESCALATED,
            level = FixEventLevel.WARNING,
        )
        if (enhancedModeEnabled) {
            tryEnterCommunicationMode("经典蓝牙保真守护升级接管")
            updateEnhancedState(EnhancedState.ACTIVE)
        }
        val fixed = restoreCommunicationToHeadset(
            preferredOutputDevice = availableHeadset,
            reason = "经典蓝牙保真守护未能维持耳机路由，强制恢复到耳机"
        )
        if (fixed) {
            startRecoveryWindow("经典蓝牙保真守护升级为强制恢复")
        }
    }

    private fun hasGuardCommunicationHold(): Boolean {
        return guardOwnsCommunicationDevice || modeRequestedByEnhanced
    }

    private fun notifyCurrentStatusChanged() {
        val status = lastReportedStatus
        val listeners = synchronized(listenerLock) { statusListeners.toList() }
        postToMainThread {
            onStatusChanged?.invoke(status)
            listeners.forEach { it(status) }
        }
    }

    private fun builtinDeviceName(type: Int?): String {
        return if (type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE) "听筒" else "扬声器"
    }

    private fun heldRouteKindFor(headset: AudioDeviceInfo): HeldRouteKind {
        return if (isClassicBluetoothOutputDevice(headset)) {
            HeldRouteKind.CLASSIC_BLUETOOTH
        } else {
            HeldRouteKind.HEADSET
        }
    }

    private fun heldRouteSubject(kind: HeldRouteKind): String {
        return HeldRoutePresentationResolver.subject(kind)
    }

    private fun heldRoutePinnedLabel(headset: AudioDeviceInfo, kind: HeldRouteKind): String {
        return HeldRoutePresentationResolver.pinnedLabel(
            kind = kind,
            headsetName = headset.productName?.toString(),
        )
    }

    private fun heldRouteIdleMessage(kind: HeldRouteKind): String {
        return HeldRoutePresentationResolver.idleMessage(kind)
    }

    private fun heldRouteRetryMessage(kind: HeldRouteKind, wasManualRelease: Boolean): String {
        return HeldRoutePresentationResolver.retryMessage(kind, wasManualRelease)
    }

    private fun hasActiveHeldRoute(headset: AudioDeviceInfo? = findConnectedHeadset()): Boolean {
        if (!heldRouteState.active || headset == null) return false
        val expectedKey = heldRouteState.headsetKey ?: return true
        return deviceIdentityKey(headset) == expectedKey
    }

    private fun syncHeldRouteTracking(headset: AudioDeviceInfo) {
        heldRouteState =
            HeldRouteStateReducer.syncTracking(
                currentState = heldRouteState,
                shouldTrack = hasActiveHeldRoute(headset) || heldRouteState.manualReleaseInProgress,
                headsetKey = deviceIdentityKey(headset),
                kind = heldRouteKindFor(headset),
            )
    }

    private fun currentStableRouteStatus(headset: AudioDeviceInfo? = findConnectedHeadset()): GuardStatus {
        return if (hasActiveHeldRoute(headset) && hasGuardCommunicationHold()) {
            GuardStatus.FIXED
        } else {
            GuardStatus.NORMAL
        }
    }

    private fun buildMonitorSnapshot(headset: AudioDeviceInfo?): MonitorSnapshot {
        return MonitorSnapshot(
            hasHeadset = headset != null,
            headsetName = headset?.productName?.toString(),
            communicationDeviceKind = communicationDeviceKind(audioManager.communicationDevice),
            communicationDeviceName = audioManager.communicationDevice?.productName?.toString(),
            pollingPhase = pollingMode.toRoutePollingPhase(),
            lastReportedStatus = lastReportedStatus,
            enhancedModeEnabled = enhancedModeEnabled,
            enhancedState = enhancedState,
            isClassicBluetoothPassiveCandidate =
                headset != null && shouldUsePassiveClassicBluetoothConfirmation(headset),
            hasRecentBuiltinRouteEvidence = hasRecentBuiltinRouteEvidence(),
            hasClassicBluetoothStartupObservation = hasClassicBluetoothStartupObservation(),
            hasActiveHeldRoute = headset != null && hasActiveHeldRoute(headset),
            hasGuardCommunicationHold = hasGuardCommunicationHold(),
            heldRouteMessage = currentHeldRouteMessage(headset),
            canManuallyReleaseHeldRoute = hasManualHeldRouteRelease(headset),
            heldRouteManualReleaseInProgress = heldRouteState.manualReleaseInProgress,
        )
    }

    private fun resolveCurrentGuardStatus(headset: AudioDeviceInfo? = findConnectedHeadset()): GuardStatus {
        return MonitorSnapshotProjector.resolvedStatus(buildMonitorSnapshot(headset))
    }

    private fun currentHeldRouteMessage(headset: AudioDeviceInfo? = findConnectedHeadset()): String? {
        return HeldRouteStateReducer.currentMessage(
            state = heldRouteState,
            hasActiveHeldRoute = hasActiveHeldRoute(headset),
        )
    }

    private fun hasManualHeldRouteRelease(headset: AudioDeviceInfo? = findConnectedHeadset()): Boolean {
        return HeldRouteStateReducer.canManualRelease(
            state = heldRouteState,
            hasHeadset = headset != null,
            hasActiveHeldRoute = hasActiveHeldRoute(headset),
        )
    }

    private fun deviceIdentityKey(device: AudioDeviceInfo?): String? {
        if (device == null) return null
        return AudioDeviceIdentityResolver.identityKey(
            type = device.type,
            address = device.address,
            productName = device.productName?.toString(),
        )
    }

    private fun currentFixEventSnapshot(headset: AudioDeviceInfo? = findConnectedHeadset()): FixEventSnapshot {
        return MonitorSnapshotProjector.fixEventSnapshot(buildMonitorSnapshot(headset))
    }

    private fun addLog(
        message: String,
        code: FixEventCode = FixEventCode.GENERAL,
        level: FixEventLevel = FixEventLevel.INFO,
        snapshot: FixEventSnapshot? = currentFixEventSnapshot(),
    ) {
        Log.i(TAG, message)
        _fixLog.add(
            0,
            FixEvent(
                timestamp = System.currentTimeMillis(),
                code = code,
                level = level,
                message = message,
                snapshot = snapshot,
            )
        )
        if (_fixLog.size > 50) _fixLog.removeAt(_fixLog.lastIndex)
        val listeners = synchronized(listenerLock) { fixLogListeners.toList() }
        postToMainThread {
            onFixLogUpdated?.invoke()
            listeners.forEach { it() }
        }
    }

    private fun postToMainThread(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }

    private fun runOnMonitorThread(block: () -> Unit) {
        if (Looper.myLooper() == handler.looper) {
            block()
        } else {
            handler.post(block)
        }
    }

    private fun <T> callOnMonitorThread(defaultValue: T, block: () -> T): T {
        if (Looper.myLooper() == handler.looper) {
            return block()
        }
        val task = FutureTask(Callable { block() })
        if (!handler.post(task)) {
            return defaultValue
        }
        return try {
            task.get(2, TimeUnit.SECONDS)
        } catch (_: Exception) {
            defaultValue
        }
    }

    private fun PollingMode.toRoutePollingPhase(): RoutePollingPhase {
        return when (this) {
            PollingMode.IDLE -> RoutePollingPhase.IDLE
            PollingMode.CLASSIC_BLUETOOTH_CONFIRM -> RoutePollingPhase.CLASSIC_BLUETOOTH_CONFIRM
            PollingMode.CLEAR_PROBE -> RoutePollingPhase.CLEAR_PROBE
            PollingMode.RECOVERY_WINDOW -> RoutePollingPhase.RECOVERY_WINDOW
            PollingMode.RELEASE_PROBE -> RoutePollingPhase.RELEASE_PROBE
        }
    }
}
