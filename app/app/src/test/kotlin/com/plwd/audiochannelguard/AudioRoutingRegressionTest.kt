package com.plwd.audiochannelguard

import android.app.Application
import android.media.AudioDeviceInfo
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Handler
import android.os.SystemClock
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.AudioDeviceInfoBuilder
import org.robolectric.shadows.ShadowAudioManager
import java.util.concurrent.Executor

@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = [31, 33], manifest = Config.NONE, application = Application::class,
    shadows = [AudioRoutingRegressionTest.RoutingAudioManager::class,
        AudioRoutingRegressionTest.Device::class])
class AudioRoutingRegressionTest {
    private lateinit var monitor: AudioRouteMonitor
    private lateinit var manager: AudioManager
    private lateinit var audio: RoutingAudioManager

    @Before fun setup() {
        val app = RuntimeEnvironment.getApplication()
        manager = app.getSystemService(AudioManager::class.java)
        audio = Shadow.extract(manager)
        monitor = AudioRouteMonitor(app)
        monitor.start()
        monitor.getStatus() // Wait for the monitor thread to finish startup.
    }

    @After fun stop() { monitor.stop() }

    @Test fun `manual fix and device callback do not reclaim route during call`() {
        val headset = device(1, AudioDeviceInfo.TYPE_WIRED_HEADSET, "usb")
        audio.outputs = listOf(headset)
        audio.available = listOf(headset)
        audio.route = device(2, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, "speaker")
        manager.mode = AudioManager.MODE_IN_CALL
        assertFalse(monitor.fixNow())
        audio.dispatchRouteChanged()
        monitor.getStatus()
        assertEquals(0, audio.requests)
    }

    @Test fun `does not switch to unrelated communication headset`() {
        val usb = device(1, AudioDeviceInfo.TYPE_USB_HEADSET, "usb")
        val bluetooth = device(2, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, "bluetooth")
        audio.outputs = listOf(usb)
        audio.available = listOf(bluetooth)
        assertFalse(monitor.fixNow())
        assertEquals(0, audio.requests)
    }

    @Test fun `system communication capability permits a matching USB device endpoint`() {
        val output = device(1, AudioDeviceInfo.TYPE_USB_HEADSET, "usb")
        val endpoint = device(2, AudioDeviceInfo.TYPE_USB_DEVICE, "usb")
        audio.outputs = listOf(output)
        audio.available = listOf(endpoint)
        assertTrue(monitor.fixNow())
        assertEquals(endpoint, audio.requestedDevice)
    }

    @Test fun `communication capability alone does not select an external speaker`() {
        val speaker = device(1, AudioDeviceInfo.TYPE_BLE_SPEAKER, "speaker")
        audio.outputs = listOf(speaker)
        audio.available = listOf(speaker)
        assertFalse(monitor.fixNow())
        assertEquals(0, audio.requests)
    }

    @Test fun `ambiguous communication endpoints never pick an arbitrary headset`() {
        audio.outputs = listOf(device(1, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, "same"))
        audio.available = listOf(
            device(2, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, "same"),
            device(3, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, "same"),
        )
        assertFalse(monitor.fixNow())
        assertEquals(0, audio.requests)
    }

    @Test fun `notification collects communication device once for selection and once for snapshot`() {
        val headset = device(1, AudioDeviceInfo.TYPE_WIRED_HEADSET, "usb")
        audio.outputs = listOf(headset)
        audio.route = headset
        audio.routeReads = 0
        val snapshot = monitor.getNotificationSnapshotInputs()
        assertEquals(2, audio.routeReads)
        assertEquals("usb", snapshot.publicProjectionInput.headsetName)
        assertEquals(snapshot.publicProjectionInput.status, snapshot.alertSnapshot.status)
    }

    @Test fun `stop only clears an accepted request owned by this monitor`() {
        monitor.stop()
        assertEquals(0, audio.clears)
        monitor = AudioRouteMonitor(RuntimeEnvironment.getApplication())
        monitor.start()
        monitor.getStatus()
        val headset = device(1, AudioDeviceInfo.TYPE_WIRED_HEADSET, "usb")
        audio.outputs = listOf(headset)
        audio.available = listOf(headset)
        assertTrue(monitor.fixNow())
        monitor.stop()
        assertEquals(1, audio.clears)
    }

    @Test fun `current routed headset wins over output enumeration order`() {
        val usb = device(1, AudioDeviceInfo.TYPE_USB_HEADSET, "usb")
        val bluetooth = device(2, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, "bluetooth")
        audio.outputs = listOf(usb, bluetooth)
        audio.route = bluetooth
        assertEquals(bluetooth, monitor.findConnectedHeadset())
        audio.route = null
        assertEquals(bluetooth, monitor.findConnectedHeadset())
    }

    @Test @Config(sdk = [33])
    fun `predicted accessibility route selects target when no previous route exists`() {
        val usb = device(1, AudioDeviceInfo.TYPE_USB_HEADSET, "usb")
        val bluetooth = device(2, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, "bluetooth")
        audio.outputs = listOf(usb, bluetooth)
        audio.predicted = listOf(bluetooth)
        assertEquals(bluetooth, monitor.findConnectedHeadset())
    }

    @Test fun `phone mode cancels recovery even with enhanced mode disabled`() {
        val headset = device(1, AudioDeviceInfo.TYPE_WIRED_HEADSET, "usb")
        audio.outputs = listOf(headset)
        audio.available = listOf(headset)
        assertTrue(monitor.fixNow())
        manager.mode = AudioManager.MODE_IN_CALL
        monitor.getStatus()
        val phase = AudioRouteMonitor::class.java.getDeclaredField("pollingMode").apply { isAccessible = true }
        assertEquals("IDLE", phase.get(monitor)?.toString())
        assertFalse(monitor.fixNow())
    }

    @Test fun `starting during call defers recovery until call ends`() {
        monitor.stop()
        val headset = device(1, AudioDeviceInfo.TYPE_WIRED_HEADSET, "usb")
        audio.outputs = listOf(headset)
        audio.available = listOf(headset)
        audio.route = device(2, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, "speaker")
        manager.mode = AudioManager.MODE_IN_CALL
        monitor = AudioRouteMonitor(RuntimeEnvironment.getApplication())
        monitor.start()
        monitor.getStatus()
        assertEquals(0, audio.requests)
        manager.mode = AudioManager.MODE_NORMAL
        monitor.getStatus()
        monitor.getStatus()
        assertTrue(audio.requests > 0)
    }

    @Test fun `recovery deadline uses monotonic time rather than calendar time`() {
        val headset = device(1, AudioDeviceInfo.TYPE_WIRED_HEADSET, "usb")
        audio.outputs = listOf(headset)
        audio.available = listOf(headset)
        val before = SystemClock.elapsedRealtime()
        assertTrue(monitor.fixNow())
        val deadline = AudioRouteMonitor::class.java.getDeclaredField("recoveryDeadlineMs").apply { isAccessible = true }.getLong(monitor)
        assertTrue(deadline >= before + 6000)
        assertTrue(deadline <= SystemClock.elapsedRealtime() + 6000)
    }

    @Test fun `accepted request is not reported as restored before readback`() {
        val headset = device(1, AudioDeviceInfo.TYPE_WIRED_HEADSET, "usb")
        audio.outputs = listOf(headset)
        audio.available = listOf(headset)
        audio.route = device(2, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, "speaker")
        assertTrue(monitor.fixNow())
        assertEquals(GuardStatus.HIJACKED, monitor.getStatus())
        assertFalse(monitor.fixLog.any { it.code == FixEventCode.RESTORE_SUCCEEDED })
        audio.route = headset
        audio.dispatchRouteChanged()
        monitor.getStatus()
        assertTrue(monitor.fixLog.any { it.code == FixEventCode.RESTORE_SUCCEEDED })
    }

    @Test fun `configuration change cancels queued retry verification`() {
        val marker = Runnable { fail("Cancelled retry must not execute") }
        val pending = AudioRouteMonitor::class.java.getDeclaredField("recoveryRetryVerification").apply { isAccessible = true }
        val handler = AudioRouteMonitor::class.java.getDeclaredField("handler").apply { isAccessible = true }.get(monitor) as Handler
        handler.postDelayed(marker, 60_000)
        pending.set(monitor, marker)
        monitor.setEnhancedModeEnabled(true)
        monitor.getStatus()
        assertNull(pending.get(monitor))
        assertFalse(handler.hasCallbacks(marker))
    }

    private fun device(id: Int, type: Int, address: String): AudioDeviceInfo {
        val info = AudioDeviceInfoBuilder.newBuilder().setType(type).build()
        Shadow.extract<Device>(info).apply {
            deviceId = id
            deviceType = type
            deviceAddress = address
        }
        return info
    }

    @Implements(AudioDeviceInfo::class)
    class Device {
        var deviceId = 0
        var deviceType = 0
        var deviceAddress = ""
        @Implementation fun getId() = deviceId
        @Implementation fun getType() = deviceType
        @Implementation fun getAddress() = deviceAddress
        @Implementation fun getProductName(): CharSequence = deviceAddress
        @Implementation fun isSink() = true
    }

    @Implements(AudioManager::class)
    @Suppress("UNUSED_PARAMETER")
    class RoutingAudioManager : ShadowAudioManager() {
        var outputs = emptyList<AudioDeviceInfo>()
        var available = emptyList<AudioDeviceInfo>()
        var route: AudioDeviceInfo? = null
        var predicted = emptyList<AudioDeviceInfo>()
        var requests = 0
        var clears = 0
        var routeReads = 0
        var requestedDevice: AudioDeviceInfo? = null
        private val routeListeners = mutableListOf<Pair<Executor, AudioManager.OnCommunicationDeviceChangedListener>>()
        private val modeListeners = mutableListOf<Pair<Executor, AudioManager.OnModeChangedListener>>()
        @Implementation override fun getDevices(flags: Int) = outputs.toTypedArray()
        @Implementation fun getAvailableCommunicationDevices() = available
        @Implementation fun getAudioDevicesForAttributes(attributes: AudioAttributes) = predicted
        @Implementation override fun getCommunicationDevice(): AudioDeviceInfo? {
            routeReads++
            return route
        }
        @Implementation override fun setCommunicationDevice(device: AudioDeviceInfo): Boolean {
            requests++
            requestedDevice = device
            return true
        }
        @Implementation override fun clearCommunicationDevice() { clears++ }
        @Implementation fun addOnCommunicationDeviceChangedListener(executor: Executor, listener: AudioManager.OnCommunicationDeviceChangedListener) {
            routeListeners.add(executor to listener)
        }
        @Implementation fun removeOnCommunicationDeviceChangedListener(listener: AudioManager.OnCommunicationDeviceChangedListener) {
            routeListeners.removeAll { it.second == listener }
        }
        @Implementation fun addOnModeChangedListener(executor: Executor, listener: AudioManager.OnModeChangedListener) {
            modeListeners.add(executor to listener)
        }
        @Implementation fun removeOnModeChangedListener(listener: AudioManager.OnModeChangedListener) {
            modeListeners.removeAll { it.second == listener }
        }
        @Implementation override fun setMode(mode: Int) {
            super.setMode(mode)
            modeListeners.toList().forEach { (executor, listener) -> executor.execute { listener.onModeChanged(mode) } }
        }
        fun dispatchRouteChanged() {
            routeListeners.toList().forEach { (executor, listener) -> executor.execute { listener.onCommunicationDeviceChanged(route) } }
        }
    }
}
