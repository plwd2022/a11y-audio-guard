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

class AudioRouteMonitor(private val context: Context) {

    private enum class PollingMode {
        IDLE,
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

        private const val RECOVERY_POLL_MS = 500L
        private const val RECOVERY_WINDOW_MS = 6000L
        private const val REQUIRED_STABLE_HITS = 3
    }

    private val audioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val handler = Handler(Looper.getMainLooper())
    private var running = false

    private var pollingMode = PollingMode.IDLE
    private var recoveryDeadlineMs = 0L
    private var stableHitCount = 0

    private val recoveryPollingRunnable = object : Runnable {
        override fun run() {
            if (!running) return

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
                handler.postDelayed(this, RECOVERY_POLL_MS)
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

            handler.postDelayed(this, RECOVERY_POLL_MS)
        }
    }

    private val _fixLog = mutableListOf<FixEvent>()
    val fixLog: List<FixEvent> get() = _fixLog.toList()

    var onStatusChanged: ((GuardStatus) -> Unit)? = null
    var onFixLogUpdated: (() -> Unit)? = null

    private val commDeviceListener =
        AudioManager.OnCommunicationDeviceChangedListener { device ->
            Log.i(TAG, "Communication device changed: type=${device?.type} name=${device?.productName}")
            if (device?.type in BUILTIN_TYPES) {
                val headset = findConnectedHeadset()
                if (headset != null) {
                    restoreCommunicationToHeadset(
                        preferredOutputDevice = headset,
                        reason = "检测到声道被劫持到${builtinDeviceName(device?.type)}"
                    )
                    startRecoveryWindow("回调检测到路由劫持")
                }
            } else if (findConnectedHeadset() != null) {
                onStatusChanged?.invoke(GuardStatus.NORMAL)
            }
        }

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            val headset = addedDevices.firstOrNull { it.type in HEADSET_TYPES && it.isSink }
            if (headset != null) {
                addLog("耳机已连接: ${headset.productName}")
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
                    stopRecoveryWindow("耳机全部断开")
                    onStatusChanged?.invoke(GuardStatus.NO_HEADSET)
                }
            }
        }
    }

    private fun startRecoveryWindow(reason: String) {
        val currentComm = audioManager.communicationDevice?.productName
        val currentHeadset = findConnectedHeadset()?.productName
        addLog("进入恢复观察窗口: $reason, comm=$currentComm, headset=$currentHeadset")
        pollingMode = PollingMode.RECOVERY_WINDOW
        recoveryDeadlineMs = System.currentTimeMillis() + RECOVERY_WINDOW_MS
        stableHitCount = 0
        handler.removeCallbacks(recoveryPollingRunnable)
        handler.post(recoveryPollingRunnable)
    }

    private fun stopRecoveryWindow(reason: String) {
        if (pollingMode == PollingMode.IDLE) return
        pollingMode = PollingMode.IDLE
        stableHitCount = 0
        handler.removeCallbacks(recoveryPollingRunnable)
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
        addLog("守护已启动")
        val headset = findConnectedHeadset()
        if (headset == null) {
            onStatusChanged?.invoke(GuardStatus.NO_HEADSET)
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
        stopRecoveryWindow("守护停止")
        audioManager.removeOnCommunicationDeviceChangedListener(commDeviceListener)
        audioManager.unregisterAudioDeviceCallback(deviceCallback)
        clearCommunicationDeviceSafely()
        addLog("守护已停止")
    }

    fun fixNow(): Boolean {
        val fixed = restoreCommunicationToHeadset(reason = "手动修复：尝试恢复声道")
        if (fixed) {
            startRecoveryWindow("用户手动触发修复")
        }
        return fixed
    }

    fun getStatus(): GuardStatus {
        val headset = findConnectedHeadset()
        if (headset == null) return GuardStatus.NO_HEADSET
        val commDevice = audioManager.communicationDevice
        return if (commDevice?.type in BUILTIN_TYPES) {
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
