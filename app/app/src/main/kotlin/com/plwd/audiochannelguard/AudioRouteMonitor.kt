package com.plwd.audiochannelguard

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log

data class FixEvent(
    val timestamp: Long,
    val message: String
)

enum class GuardStatus {
    NORMAL,
    FIXED,
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
        CLEAR_PROBE,
        RECOVERY_WINDOW,
    }

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
        private const val MODE_REACQUIRE_DELAY_MS = 250L
        private const val RECOVERY_POLL_MS = 500L
        private const val RECOVERY_WINDOW_MS = 6000L
        private const val REQUIRED_STABLE_HITS = 3
    }

    private val audioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val handler = Handler(Looper.getMainLooper())
    private var running = false

    private var pollingMode = PollingMode.IDLE
    private var clearProbeDeadlineMs = 0L
    private var recoveryDeadlineMs = 0L
    private var stableHitCount = 0
    private var clearProbeHeadset: AudioDeviceInfo? = null
    private var enhancedModeEnabled = false
    private var enhancedState = EnhancedState.DISABLED
    private var modeListenerRegistered = false
    private var modeRequestedByEnhanced = false

    private val pollingRunnable = object : Runnable {
        override fun run() {
            if (!running) return

            when (pollingMode) {
                PollingMode.IDLE -> return
                PollingMode.CLEAR_PROBE -> handleClearProbeTick()
                PollingMode.RECOVERY_WINDOW -> handleRecoveryWindowTick()
            }
        }
    }

    private val reacquireEnhancedRunnable = Runnable {
        if (!running || !enhancedModeEnabled) return@Runnable
        maybeReacquireEnhancedMode("增强守护尝试重新接管")
    }

    private val _fixLog = mutableListOf<FixEvent>()
    val fixLog: List<FixEvent> get() = _fixLog.toList()

    var onStatusChanged: ((GuardStatus) -> Unit)? = null
    var onFixLogUpdated: (() -> Unit)? = null
    var onEnhancedStateChanged: ((EnhancedState) -> Unit)? = null

    private val modeChangedListener =
        AudioManager.OnModeChangedListener { mode ->
            Log.i(TAG, "Audio mode changed: $mode")
            if (!running || !enhancedModeEnabled) return@OnModeChangedListener

            if (shouldSuspendForCall(mode)) {
                handler.removeCallbacks(reacquireEnhancedRunnable)
                stopClearProbe("检测到电话模式")
                if (enhancedState != EnhancedState.SUSPENDED_BY_CALL) {
                    addLog("检测到电话模式，暂停增强守护")
                }
                modeRequestedByEnhanced = false
                updateEnhancedState(EnhancedState.SUSPENDED_BY_CALL)
                return@OnModeChangedListener
            }

            if (findConnectedHeadset() == null) {
                updateEnhancedState(EnhancedState.WAITING_HEADSET)
                return@OnModeChangedListener
            }

            if (mode != AudioManager.MODE_IN_COMMUNICATION || enhancedState == EnhancedState.SUSPENDED_BY_CALL) {
                handler.removeCallbacks(reacquireEnhancedRunnable)
                handler.postDelayed(reacquireEnhancedRunnable, MODE_REACQUIRE_DELAY_MS)
            } else if (pollingMode == PollingMode.IDLE) {
                updateEnhancedState(EnhancedState.ACTIVE)
            }
        }

    private val commDeviceListener =
        AudioManager.OnCommunicationDeviceChangedListener { device ->
            Log.i(TAG, "Communication device changed: type=${device?.type} name=${device?.productName}")
            val headset = findConnectedHeadset()
            if (headset == null) {
                if (enhancedModeEnabled) {
                    updateEnhancedState(EnhancedState.WAITING_HEADSET)
                }
                onStatusChanged?.invoke(GuardStatus.NO_HEADSET)
                return@OnCommunicationDeviceChangedListener
            }

            if (pollingMode == PollingMode.CLEAR_PROBE && device == null) {
                return@OnCommunicationDeviceChangedListener
            }

            if (device?.type in BUILTIN_TYPES) {
                if (enhancedModeEnabled) {
                    startClearProbe(headset, "检测到声道被劫持到${builtinDeviceName(device?.type)}")
                } else {
                    restoreCommunicationToHeadset(
                        preferredOutputDevice = headset,
                        reason = "检测到声道被劫持到${builtinDeviceName(device?.type)}"
                    )
                    startRecoveryWindow("回调检测到路由劫持")
                }
            } else {
                if (enhancedModeEnabled && enhancedState != EnhancedState.SUSPENDED_BY_CALL) {
                    updateEnhancedState(EnhancedState.ACTIVE)
                }
                onStatusChanged?.invoke(GuardStatus.NORMAL)
            }
        }

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            val headset = addedDevices.firstOrNull { it.type in HEADSET_TYPES && it.isSink }
            if (headset != null) {
                addLog("耳机已连接: ${headset.productName}")
                if (enhancedModeEnabled) {
                    maybeReacquireEnhancedMode("检测到耳机接入")
                    return
                }

                val commDevice = audioManager.communicationDevice
                if (commDevice?.type in BUILTIN_TYPES) {
                    restoreCommunicationToHeadset(
                        preferredOutputDevice = headset,
                        reason = "检测到耳机接入，尝试恢复声道"
                    )
                    startRecoveryWindow("耳机接入后检测到内建设备")
                } else {
                    startRecoveryWindow("耳机接入后确认路由稳定")
                    onStatusChanged?.invoke(GuardStatus.NORMAL)
                }
            }
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            val headset = removedDevices.firstOrNull { it.type in HEADSET_TYPES && it.isSink }
            if (headset != null) {
                addLog("耳机已断开: ${headset.productName}")
                if (findConnectedHeadset() == null) {
                    stopClearProbe("耳机全部断开")
                    stopRecoveryWindow("耳机全部断开")
                    clearCommunicationDeviceSafely()
                    if (enhancedModeEnabled) {
                        tryLeaveCommunicationMode("耳机全部断开")
                        updateEnhancedState(EnhancedState.WAITING_HEADSET)
                    }
                    onStatusChanged?.invoke(GuardStatus.NO_HEADSET)
                }
            }
        }
    }

    private fun handleClearProbeTick() {
        if (pollingMode != PollingMode.CLEAR_PROBE) return

        val headset = clearProbeHeadset ?: findConnectedHeadset()
        if (headset == null) {
            stopClearProbe("观察期间未检测到耳机")
            if (enhancedModeEnabled) {
                updateEnhancedState(EnhancedState.WAITING_HEADSET)
            }
            onStatusChanged?.invoke(GuardStatus.NO_HEADSET)
            return
        }

        val commDevice = audioManager.communicationDevice
        val communicationHeadset = findAvailableCommunicationHeadset(headset)
        val restoredNaturally =
            commDevice != null && communicationHeadset != null && isSamePhysicalDevice(commDevice, communicationHeadset)

        if (restoredNaturally) {
            stopClearProbe("系统已自动恢复到耳机")
            updateEnhancedState(EnhancedState.ACTIVE)
            onStatusChanged?.invoke(GuardStatus.NORMAL)
            return
        }

        if (commDevice?.type in BUILTIN_TYPES) {
            stopClearProbe("释放后仍停留在${builtinDeviceName(commDevice?.type)}")
            tryEnterCommunicationMode("增强守护重新申请通信模式")
            val fixed = restoreCommunicationToHeadset(
                preferredOutputDevice = headset,
                reason = "释放后仍被劫持到${builtinDeviceName(commDevice?.type)}，强制恢复到耳机"
            )
            updateEnhancedState(EnhancedState.ACTIVE)
            if (fixed) {
                startRecoveryWindow("增强守护强制接管后观察稳定性")
            }
            return
        }

        if (System.currentTimeMillis() >= clearProbeDeadlineMs) {
            stopClearProbe("观察窗口结束")
            updateEnhancedState(EnhancedState.ACTIVE)
            onStatusChanged?.invoke(GuardStatus.NORMAL)
            return
        }

        handler.postDelayed(pollingRunnable, CLEAR_PROBE_POLL_MS)
    }

    private fun handleRecoveryWindowTick() {
        if (pollingMode != PollingMode.RECOVERY_WINDOW) return

        if (System.currentTimeMillis() >= recoveryDeadlineMs) {
            stopRecoveryWindow("恢复观察窗口超时")
            return
        }

        val headset = findConnectedHeadset()
        if (headset == null) {
            stopRecoveryWindow("恢复观察窗口内未检测到耳机")
            onStatusChanged?.invoke(GuardStatus.NO_HEADSET)
            return
        }

        val commDevice = audioManager.communicationDevice
        if (commDevice?.type in BUILTIN_TYPES) {
            stableHitCount = 0
            restoreCommunicationToHeadset(
                preferredOutputDevice = headset,
                reason = "恢复观察窗口检测到声道再次被劫持到${builtinDeviceName(commDevice?.type)}"
            )
            handler.postDelayed(pollingRunnable, RECOVERY_POLL_MS)
            return
        }

        val communicationHeadset = findAvailableCommunicationHeadset(headset)
        val isStable =
            commDevice != null && communicationHeadset != null && isSamePhysicalDevice(commDevice, communicationHeadset)

        if (isStable) {
            stableHitCount++
            if (stableHitCount >= REQUIRED_STABLE_HITS) {
                stopRecoveryWindow("路由已连续稳定 $REQUIRED_STABLE_HITS 次")
                onStatusChanged?.invoke(GuardStatus.NORMAL)
                return
            }
        } else {
            stableHitCount = 0
        }

        handler.postDelayed(pollingRunnable, RECOVERY_POLL_MS)
    }

    private fun startClearProbe(preferredHeadset: AudioDeviceInfo?, reason: String) {
        if (!running || !enhancedModeEnabled) return
        if (pollingMode == PollingMode.CLEAR_PROBE) return

        val headset = preferredHeadset ?: findConnectedHeadset()
        if (headset == null) {
            updateEnhancedState(EnhancedState.WAITING_HEADSET)
            onStatusChanged?.invoke(GuardStatus.NO_HEADSET)
            return
        }

        stopRecoveryWindow("切换到增强观察模式")
        clearProbeHeadset = headset
        clearProbeDeadlineMs = System.currentTimeMillis() + CLEAR_PROBE_WINDOW_MS
        pollingMode = PollingMode.CLEAR_PROBE
        addLog("$reason，先释放本应用占用并观察系统是否自动恢复")
        clearCommunicationDeviceSafely()
        updateEnhancedState(EnhancedState.CLEAR_PROBE)
        onStatusChanged?.invoke(GuardStatus.HIJACKED)
        handler.removeCallbacks(pollingRunnable)
        handler.post(pollingRunnable)
    }

    private fun stopClearProbe(reason: String) {
        if (pollingMode != PollingMode.CLEAR_PROBE) return
        pollingMode = PollingMode.IDLE
        clearProbeHeadset = null
        handler.removeCallbacks(pollingRunnable)
        addLog("退出增强观察: $reason")
    }

    private fun startRecoveryWindow(reason: String) {
        val currentComm = audioManager.communicationDevice?.productName
        val currentHeadset = findConnectedHeadset()?.productName
        addLog("进入恢复观察窗口: $reason, comm=$currentComm, headset=$currentHeadset")
        pollingMode = PollingMode.RECOVERY_WINDOW
        recoveryDeadlineMs = System.currentTimeMillis() + RECOVERY_WINDOW_MS
        stableHitCount = 0
        handler.removeCallbacks(pollingRunnable)
        handler.post(pollingRunnable)
    }

    private fun stopRecoveryWindow(reason: String) {
        if (pollingMode != PollingMode.RECOVERY_WINDOW) return
        pollingMode = PollingMode.IDLE
        stableHitCount = 0
        handler.removeCallbacks(pollingRunnable)
        addLog("退出恢复观察窗口: $reason")
    }

    fun start() {
        if (running) return
        running = true
        // Use a handler-backed executor to avoid mainExecutor callback delays in background.
        val callbackHandler = Handler(handler.looper)
        audioManager.addOnCommunicationDeviceChangedListener(
            { callbackHandler.post(it) }, commDeviceListener
        )
        audioManager.registerAudioDeviceCallback(deviceCallback, handler)
        if (enhancedModeEnabled) {
            registerModeListenerIfNeeded()
        }
        addLog("守护已启动")
        val headset = findConnectedHeadset()
        if (headset == null) {
            if (enhancedModeEnabled) {
                updateEnhancedState(EnhancedState.WAITING_HEADSET)
            }
            onStatusChanged?.invoke(GuardStatus.NO_HEADSET)
        } else if (enhancedModeEnabled) {
            maybeReacquireEnhancedMode("增强守护启动")
        } else if (audioManager.communicationDevice?.type in BUILTIN_TYPES) {
            restoreCommunicationToHeadset(
                preferredOutputDevice = headset,
                reason = "启动时检测到声道仍在${builtinDeviceName(audioManager.communicationDevice?.type)}"
            )
            startRecoveryWindow("启动阶段检测到内建设备")
        } else {
            onStatusChanged?.invoke(GuardStatus.NORMAL)
        }
    }

    fun stop() {
        if (!running) return
        running = false
        handler.removeCallbacks(reacquireEnhancedRunnable)
        stopClearProbe("守护停止")
        stopRecoveryWindow("守护停止")
        unregisterModeListenerIfNeeded()
        tryLeaveCommunicationMode("守护停止")
        audioManager.removeOnCommunicationDeviceChangedListener(commDeviceListener)
        audioManager.unregisterAudioDeviceCallback(deviceCallback)
        clearCommunicationDeviceSafely()
        updateEnhancedState(EnhancedState.DISABLED)
        addLog("守护已停止")
    }

    fun fixNow(): Boolean {
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
        return fixed
    }

    fun setEnhancedModeEnabled(enabled: Boolean) {
        if (enhancedModeEnabled == enabled) {
            if (running && enabled) {
                maybeReacquireEnhancedMode("增强守护配置已同步")
            }
            return
        }

        enhancedModeEnabled = enabled
        if (!running) {
            updateEnhancedState(if (enabled) EnhancedState.WAITING_HEADSET else EnhancedState.DISABLED)
            return
        }

        if (enabled) {
            registerModeListenerIfNeeded()
            maybeReacquireEnhancedMode("增强守护已开启")
        } else {
            handler.removeCallbacks(reacquireEnhancedRunnable)
            stopClearProbe("增强守护已关闭")
            unregisterModeListenerIfNeeded()
            tryLeaveCommunicationMode("增强守护已关闭")
            updateEnhancedState(EnhancedState.DISABLED)
        }
    }

    fun isEnhancedModeEnabled(): Boolean = enhancedModeEnabled

    fun getEnhancedState(): EnhancedState {
        return if (enhancedModeEnabled) enhancedState else EnhancedState.DISABLED
    }

    fun getStatus(): GuardStatus {
        val headset = findConnectedHeadset()
        if (headset == null) return GuardStatus.NO_HEADSET
        val commDevice = audioManager.communicationDevice
        return if (pollingMode == PollingMode.CLEAR_PROBE || commDevice?.type in BUILTIN_TYPES) {
            GuardStatus.HIJACKED
        } else {
            GuardStatus.NORMAL
        }
    }

    fun findConnectedHeadset(): AudioDeviceInfo? {
        return audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .firstOrNull { it.type in HEADSET_TYPES && it.isSink }
    }

    fun getCommunicationDevice(): AudioDeviceInfo? {
        return audioManager.communicationDevice
    }

    private fun updateEnhancedState(state: EnhancedState) {
        if (enhancedState == state) return
        enhancedState = state
        onEnhancedStateChanged?.invoke(state)
    }

    private fun registerModeListenerIfNeeded() {
        if (modeListenerRegistered) return
        audioManager.addOnModeChangedListener(
            { handler.post(it) }, modeChangedListener
        )
        modeListenerRegistered = true
    }

    private fun unregisterModeListenerIfNeeded() {
        if (!modeListenerRegistered) return
        audioManager.removeOnModeChangedListener(modeChangedListener)
        modeListenerRegistered = false
    }

    private fun maybeReacquireEnhancedMode(reason: String) {
        if (!running || !enhancedModeEnabled) return

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

            else -> onStatusChanged?.invoke(GuardStatus.NORMAL)
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
                addLog("增强守护已申请通信模式: $reason")
            } else {
                addLog("增强守护申请通信模式未生效: $reason")
            }
        } catch (exception: RuntimeException) {
            addLog("增强守护申请通信模式失败(${exception.javaClass.simpleName})")
        }
    }

    private fun tryLeaveCommunicationMode(reason: String) {
        if (!modeRequestedByEnhanced) return

        try {
            if (audioManager.mode == AudioManager.MODE_IN_COMMUNICATION && !shouldSuspendForCall(audioManager.mode)) {
                audioManager.mode = AudioManager.MODE_NORMAL
            }
            addLog("增强守护已释放通信模式: $reason")
        } catch (exception: RuntimeException) {
            Log.w(TAG, "leaveCommunicationMode failed", exception)
        } finally {
            modeRequestedByEnhanced = false
        }
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
            addLog("无法修复：未检测到耳机")
            onStatusChanged?.invoke(GuardStatus.NO_HEADSET)
            return false
        }

        addLog(reason)

        val communicationHeadset = findAvailableCommunicationHeadset(outputHeadset)
        if (communicationHeadset == null) {
            addLog("已连接 ${outputHeadset.productName}，但系统当前未提供可用的通信耳机设备")
            onStatusChanged?.invoke(GuardStatus.HIJACKED)
            return false
        }

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
        val firstAddress = first.address.orEmpty()
        val secondAddress = second.address.orEmpty()
        if (firstAddress.isNotEmpty() && firstAddress == secondAddress) return true
        return first.productName?.toString() == second.productName?.toString()
    }

    private fun setCommunicationDeviceSafely(device: AudioDeviceInfo): Boolean {
        return try {
            val result = audioManager.setCommunicationDevice(device)
            if (result) {
                addLog("已将声道恢复到 ${device.productName}")
                onStatusChanged?.invoke(GuardStatus.FIXED)
            } else {
                addLog("系统未接受通信设备切换请求: ${device.productName}")
                onStatusChanged?.invoke(GuardStatus.HIJACKED)
            }
            result
        } catch (exception: IllegalArgumentException) {
            addLog("通信设备切换失败(${exception.javaClass.simpleName}): ${device.productName}")
            onStatusChanged?.invoke(GuardStatus.HIJACKED)
            false
        } catch (exception: IllegalStateException) {
            addLog("通信设备切换失败(${exception.javaClass.simpleName}): ${device.productName}")
            onStatusChanged?.invoke(GuardStatus.HIJACKED)
            false
        } catch (exception: SecurityException) {
            addLog("通信设备切换失败(${exception.javaClass.simpleName}): ${device.productName}")
            onStatusChanged?.invoke(GuardStatus.HIJACKED)
            false
        }
    }

    private fun clearCommunicationDeviceSafely() {
        try {
            audioManager.clearCommunicationDevice()
        } catch (exception: IllegalStateException) {
            Log.w(TAG, "clearCommunicationDevice failed", exception)
        } catch (exception: SecurityException) {
            Log.w(TAG, "clearCommunicationDevice failed", exception)
        }
    }

    private fun builtinDeviceName(type: Int?): String {
        return if (type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE) "听筒" else "扬声器"
    }

    private fun addLog(message: String) {
        Log.i(TAG, message)
        _fixLog.add(0, FixEvent(System.currentTimeMillis(), message))
        if (_fixLog.size > 50) _fixLog.removeAt(_fixLog.lastIndex)
        onFixLogUpdated?.invoke()
    }
}
