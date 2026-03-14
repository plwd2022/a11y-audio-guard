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

    companion object {
        private const val TAG = "AudioRouteMonitor"

        val HEADSET_TYPES = setOf(
            AudioDeviceInfo.TYPE_WIRED_HEADSET,     // 3
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,  // 4
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,    // 8
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,     // 7
            AudioDeviceInfo.TYPE_USB_HEADSET,       // 22
            AudioDeviceInfo.TYPE_BLE_HEADSET,       // 26
        )

        private val COMMUNICATION_HEADSET_TYPES = setOf(
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,    // Some BT devices report as A2DP in comm devices
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
        )

        private val BUILTIN_TYPES = setOf(
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE,
        )
    }

    private val audioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    
    // Polling runnable for background detection when callbacks are delayed
    private val pollingRunnable = object : Runnable {
        override fun run() {
            if (!running) return
            val commDevice = audioManager.communicationDevice
            if (commDevice?.type in BUILTIN_TYPES) {
                val headset = findConnectedHeadset()
                if (headset != null) {
                    restoreCommunicationToHeadset(
                        preferredOutputDevice = headset,
                        reason = "轮询检测到声道被劫持到${builtinDeviceName(commDevice?.type)}"
                    )
                }
            }
            // Poll every 500ms when running
            handler.postDelayed(this, 500)
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
                } else {
                    onStatusChanged?.invoke(GuardStatus.NORMAL)
                }
            }
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            val headset = removedDevices.firstOrNull { it.type in HEADSET_TYPES && it.isSink }
            if (headset != null) {
                addLog("耳机已断开: ${headset.productName}")
                if (findConnectedHeadset() == null) {
                    onStatusChanged?.invoke(GuardStatus.NO_HEADSET)
                }
            }
        }
    }

    fun start() {
        if (running) return
        running = true
        // Use a dedicated handler thread instead of mainExecutor to avoid
        // delayed callbacks when the app is in background
        val callbackHandler = Handler(handler.looper)
        audioManager.addOnCommunicationDeviceChangedListener(
            { callbackHandler.post(it) }, commDeviceListener
        )
        audioManager.registerAudioDeviceCallback(deviceCallback, handler)
        // Start polling for immediate detection in background
        handler.post(pollingRunnable)
        addLog("守护已启动")
        val headset = findConnectedHeadset()
        if (headset == null) {
            onStatusChanged?.invoke(GuardStatus.NO_HEADSET)
        } else if (audioManager.communicationDevice?.type in BUILTIN_TYPES) {
            restoreCommunicationToHeadset(
                preferredOutputDevice = headset,
                reason = "启动时检测到声道仍在${builtinDeviceName(audioManager.communicationDevice?.type)}"
            )
        } else {
            onStatusChanged?.invoke(GuardStatus.NORMAL)
        }
    }

    fun stop() {
        if (!running) return
        running = false
        handler.removeCallbacks(pollingRunnable)
        audioManager.removeOnCommunicationDeviceChangedListener(commDeviceListener)
        audioManager.unregisterAudioDeviceCallback(deviceCallback)
        clearCommunicationDeviceSafely()
        addLog("守护已停止")
    }

    fun fixNow(): Boolean {
        return restoreCommunicationToHeadset(reason = "手动修复：尝试恢复声道")
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
