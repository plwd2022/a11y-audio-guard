package com.plwd.audiochannelguard

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRouting
import android.media.AudioTrack
import android.os.Handler
import android.os.Process
import kotlin.math.max

class AccessibilitySoftRouteGuard(
    private val callbackHandler: Handler,
    private val onRoutedDeviceChanged: (AudioDeviceInfo?) -> Unit,
) {

    enum class RoutingMode {
        OBSERVE,
        PINNED,
    }

    companion object {
        private const val SAMPLE_RATE_HZ = 48_000
        private const val CHANNEL_MASK = AudioFormat.CHANNEL_OUT_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        private const val MIN_BUFFER_BYTES = 4096
        private const val KEEP_ALIVE_VOLUME = 0.001f
    }

    private val lock = Any()
    private val silenceBuffer = ByteArray(MIN_BUFFER_BYTES)

    @Volatile
    private var running = false

    private var track: AudioTrack? = null
    private var targetDevice: AudioDeviceInfo? = null
    private var routingMode = RoutingMode.OBSERVE
    private var writerThread: Thread? = null

    private val routingChangedListener = AudioRouting.OnRoutingChangedListener { routing ->
        val routedDevice = (routing as? AudioTrack)?.routedDevice
        callbackHandler.post {
            if (running) {
                onRoutedDeviceChanged(routedDevice)
            }
        }
    }

    fun startOrUpdate(
        device: AudioDeviceInfo,
        mode: RoutingMode,
    ): Boolean {
        synchronized(lock) {
            val currentTrack = track
            if (running && currentTrack != null) {
                targetDevice = device
                routingMode = mode
                applyRoutingMode(currentTrack, device, mode)
                dispatchCurrentRoute(currentTrack)
                return true
            }

            val createdTrack = createTrack() ?: return false
            targetDevice = device
            routingMode = mode
            createdTrack.addOnRoutingChangedListener(routingChangedListener, callbackHandler)
            applyRoutingMode(createdTrack, device, mode)
            createdTrack.setVolume(KEEP_ALIVE_VOLUME)
            createdTrack.play()

            running = true
            track = createdTrack
            startWriterThread(createdTrack)
            dispatchCurrentRoute(createdTrack)
            return true
        }
    }

    fun stop() {
        val currentTrack: AudioTrack?
        val currentThread: Thread?
        synchronized(lock) {
            if (!running && track == null) return
            running = false
            currentTrack = track
            currentThread = writerThread
            track = null
            writerThread = null
            targetDevice = null
            routingMode = RoutingMode.OBSERVE
        }

        currentTrack?.removeOnRoutingChangedListener(routingChangedListener)
        try {
            currentTrack?.pause()
        } catch (_: IllegalStateException) {
        }
        try {
            currentTrack?.flush()
        } catch (_: IllegalStateException) {
        }
        try {
            currentTrack?.stop()
        } catch (_: IllegalStateException) {
        }
        currentTrack?.release()

        currentThread?.join(200)
    }

    fun isRunning(): Boolean = running && track != null

    fun getTargetDevice(): AudioDeviceInfo? = synchronized(lock) { targetDevice }

    fun getRoutedDevice(): AudioDeviceInfo? = synchronized(lock) { track?.routedDevice }

    fun getRoutingMode(): RoutingMode = synchronized(lock) { routingMode }

    private fun createTrack(): AudioTrack? {
        return try {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()

            val format = AudioFormat.Builder()
                .setSampleRate(SAMPLE_RATE_HZ)
                .setEncoding(ENCODING)
                .setChannelMask(CHANNEL_MASK)
                .build()

            val minBuffer = AudioTrack.getMinBufferSize(SAMPLE_RATE_HZ, CHANNEL_MASK, ENCODING)
            val bufferSize = max(minBuffer.takeIf { it > 0 } ?: MIN_BUFFER_BYTES, MIN_BUFFER_BYTES)

            AudioTrack(
                attrs,
                format,
                bufferSize,
                AudioTrack.MODE_STREAM,
                AudioManager.AUDIO_SESSION_ID_GENERATE
            )
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: UnsupportedOperationException) {
            null
        }
    }

    private fun startWriterThread(currentTrack: AudioTrack) {
        val thread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            while (running) {
                try {
                    val written = currentTrack.write(
                        silenceBuffer,
                        0,
                        silenceBuffer.size,
                        AudioTrack.WRITE_BLOCKING
                    )
                    if (written < 0) {
                        break
                    }
                } catch (_: IllegalStateException) {
                    break
                }
            }
        }, "AccessibilitySoftRouteGuard")
        thread.isDaemon = true
        writerThread = thread
        thread.start()
    }

    private fun applyRoutingMode(
        currentTrack: AudioTrack,
        device: AudioDeviceInfo,
        mode: RoutingMode,
    ) {
        when (mode) {
            RoutingMode.OBSERVE -> currentTrack.setPreferredDevice(null)
            RoutingMode.PINNED -> currentTrack.setPreferredDevice(device)
        }
    }

    private fun dispatchCurrentRoute(currentTrack: AudioTrack) {
        val routedDevice = currentTrack.routedDevice
        callbackHandler.post {
            if (running) {
                onRoutedDeviceChanged(routedDevice)
            }
        }
    }
}
