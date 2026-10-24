package com.plwd.audiochannelguard

import android.app.Application
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioRouting
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.AudioDeviceInfoBuilder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 33], manifest = Config.NONE, application = Application::class,
    shadows = [SilentAudioTrackLifecycleTest.FailingAudioTrack::class])
class SilentAudioTrackLifecycleTest {
    @Before fun reset() {
        FailingAudioTrack.releaseLatch = CountDownLatch(1)
        FailingAudioTrack.created.set(0)
        FailingAudioTrack.failOnPlay = false
    }

    @Test fun `dead communication track clears state and can be recreated`() {
        val guard = CommunicationModeKeepAlive()
        try {
            assertTrue(guard.start())
            assertTrue(FailingAudioTrack.releaseLatch.await(5, TimeUnit.SECONDS))
            assertFalse(guard.isRunning())
            FailingAudioTrack.releaseLatch = CountDownLatch(1)
            assertTrue(guard.start())
            assertTrue(FailingAudioTrack.releaseLatch.await(5, TimeUnit.SECONDS))
            assertEquals(2, FailingAudioTrack.created.get())
            assertFalse(guard.isRunning())
        } finally { guard.stop() }
    }

    @Test fun `dead accessibility probe clears target and can be recreated`() {
        val guard = AccessibilitySoftRouteGuard(Handler(Looper.getMainLooper())) { }
        val headset = AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP).build()
        try {
            assertTrue(guard.startOrUpdate(headset))
            assertTrue(FailingAudioTrack.releaseLatch.await(5, TimeUnit.SECONDS))
            assertFalse(guard.isRunning())
            assertNull(guard.getTargetDevice())
            FailingAudioTrack.releaseLatch = CountDownLatch(1)
            assertTrue(guard.startOrUpdate(headset))
            assertTrue(FailingAudioTrack.releaseLatch.await(5, TimeUnit.SECONDS))
            assertEquals(2, FailingAudioTrack.created.get())
        } finally { guard.stop() }
    }

    @Test fun `failed accessibility probe startup does not retain a target`() {
        val guard = AccessibilitySoftRouteGuard(Handler(Looper.getMainLooper())) { }
        val headset = AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP).build()
        FailingAudioTrack.failOnPlay = true
        try {
            assertFalse(guard.startOrUpdate(headset))
            assertFalse(guard.isRunning())
            assertNull(guard.getTargetDevice())
            assertTrue(FailingAudioTrack.releaseLatch.await(5, TimeUnit.SECONDS))
        } finally { guard.stop() }
    }

    @Implements(AudioTrack::class)
    @Suppress("UNUSED_PARAMETER")
    class FailingAudioTrack {
        companion object {
            @JvmField var releaseLatch = CountDownLatch(1)
            @JvmField val created = AtomicInteger()
            @JvmField var failOnPlay = false
            @Implementation @JvmStatic fun getMinBufferSize(rate: Int, channels: Int, encoding: Int) = 4096
        }
        @Implementation fun __constructor__(attrs: AudioAttributes, format: AudioFormat, size: Int, mode: Int, session: Int) {
            created.incrementAndGet()
        }
        @Implementation fun getState() = AudioTrack.STATE_INITIALIZED
        @Implementation fun setVolume(volume: Float) = AudioTrack.SUCCESS
        @Implementation fun setPreferredDevice(device: AudioDeviceInfo?) = true
        @Implementation fun getRoutedDevice(): AudioDeviceInfo? = null
        @Implementation fun play() {
            if (failOnPlay) throw IllegalStateException("Cannot start track")
        }
        @Implementation fun pause() { }
        @Implementation fun flush() { }
        @Implementation fun stop() { }
        @Implementation fun release() { releaseLatch.countDown() }
        @Implementation fun write(buffer: ByteArray, offset: Int, size: Int, mode: Int) = AudioTrack.ERROR_DEAD_OBJECT
        @Implementation fun addOnRoutingChangedListener(listener: AudioRouting.OnRoutingChangedListener, handler: Handler) { }
        @Implementation fun removeOnRoutingChangedListener(listener: AudioRouting.OnRoutingChangedListener) { }
    }
}
