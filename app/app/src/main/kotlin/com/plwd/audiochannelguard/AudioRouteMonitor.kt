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
    NO_HEADSET
}

class AudioRouteMonitor(private val context: Context) {

    companion object {
        private const val TAG = "AudioRouteMonitor"

        val HEADSET_TYPES = setOf(
            AudioDeviceInfo.TYPE_WIRED_HEADSET,     // 3
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,  // 4
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,    // 8
            AudioDeviceInfo.TYPE_USB_HEADSET,       // 22
            AudioDeviceInfo.TYPE_BLE_HEADSET,       // 26
            AudioDeviceInfo.TYPE_BLE_SPEAKER,       // 27
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
                    addLog("检测到声道被劫持到${if (device?.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE) "听筒" else "扬声器"}")
                    audioManager.setCommunicationDevice(headset)
                    addLog("已将声道恢复到 ${headset.productName}")
                    onStatusChanged?.invoke(GuardStatus.FIXED)
                }
            }
        }

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            val headset = addedDevices.firstOrNull { it.type in HEADSET_TYPES && it.isSink }
            if (headset != null) {
                addLog("耳机已连接: ${headset.productName}")
                val commDevice = audioManager.communicationDevice
                if (commDevice?.type in BUILTIN_TYPES) {
                    audioManager.setCommunicationDevice(headset)
                    addLog("已将声道恢复到 ${headset.productName}")
                    onStatusChanged?.invoke(GuardStatus.FIXED)
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
        audioManager.addOnCommunicationDeviceChangedListener(
            context.mainExecutor, commDeviceListener
        )
        audioManager.registerAudioDeviceCallback(deviceCallback, handler)
        addLog("守护已启动")
        onStatusChanged?.invoke(
            if (findConnectedHeadset() != null) GuardStatus.NORMAL else GuardStatus.NO_HEADSET
        )
    }

    fun stop() {
        if (!running) return
        running = false
        audioManager.removeOnCommunicationDeviceChangedListener(commDeviceListener)
        audioManager.unregisterAudioDeviceCallback(deviceCallback)
        addLog("守护已停止")
    }

    fun fixNow(): Boolean {
        val headset = findConnectedHeadset()
        if (headset == null) {
            addLog("无法修复：未检测到耳机")
            onStatusChanged?.invoke(GuardStatus.NO_HEADSET)
            return false
        }
        audioManager.setCommunicationDevice(headset)
        addLog("手动修复：已将声道设置到 ${headset.productName}")
        onStatusChanged?.invoke(GuardStatus.FIXED)
        return true
    }

    fun getStatus(): GuardStatus {
        val headset = findConnectedHeadset()
        if (headset == null) return GuardStatus.NO_HEADSET
        val commDevice = audioManager.communicationDevice
        return if (commDevice?.type in BUILTIN_TYPES) {
            GuardStatus.FIXED
        } else {
            GuardStatus.NORMAL
        }
    }

    fun findConnectedHeadset(): AudioDeviceInfo? {
        return audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .firstOrNull { it.type in HEADSET_TYPES }
    }

    fun getCommunicationDevice(): AudioDeviceInfo? {
        return audioManager.communicationDevice
    }

    private fun addLog(message: String) {
        Log.i(TAG, message)
        _fixLog.add(0, FixEvent(System.currentTimeMillis(), message))
        if (_fixLog.size > 50) _fixLog.removeAt(_fixLog.lastIndex)
        onFixLogUpdated?.invoke()
    }
}
