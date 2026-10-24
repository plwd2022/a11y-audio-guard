package com.plwd.audiochannelguard

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Process
import android.util.Log
import kotlin.math.max

/**
 * Holds a near-silent USAGE_VOICE_COMMUNICATION AudioTrack while the enhanced
 * guard owns MODE_IN_COMMUNICATION, so platform mode-for-UID resets do not
 * drop the mode after ~6s for a UID with no communication usage.
 */
internal class CommunicationModeKeepAlive {

    companion object {
        private const val TAG = "ModeKeepAlive"
        private const val SAMPLE_RATE_HZ = 48_000
        private const val MIN_BUFFER_BYTES = 4096
        private const val KEEP_ALIVE_VOLUME = 0.001f
    }

    private val lock = Any()
    private val silenceBuffer = ByteArray(MIN_BUFFER_BYTES)

    @Volatile
    private var running = false

    private var track: AudioTrack? = null
    private var writerThread: Thread? = null

    fun start(): Boolean {
        synchronized(lock) {
            if (running && track != null) return true

            val createdTrack = createTrack() ?: return false
            try {
                createdTrack.setVolume(KEEP_ALIVE_VOLUME)
                createdTrack.play()
            } catch (exception: IllegalStateException) {
                Log.w(TAG, "Failed to start keep-alive AudioTrack", exception)
                releaseTrack(createdTrack)
                return false
            } catch (exception: RuntimeException) {
                Log.w(TAG, "Failed to configure keep-alive AudioTrack", exception)
                releaseTrack(createdTrack)
                return false
            }

            running = true
            track = createdTrack
            startWriterThread(createdTrack)
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
        }

        currentThread?.interrupt()
        releaseTrack(currentTrack)
    }

    fun isRunning(): Boolean = synchronized(lock) { running && track != null }

    private fun createTrack(): AudioTrack? {
        return try {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()

            val format = AudioFormat.Builder()
                .setSampleRate(SAMPLE_RATE_HZ)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()

            val minBuffer = AudioTrack.getMinBufferSize(
                SAMPLE_RATE_HZ,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            val bufferSize = max(minBuffer.takeIf { it > 0 } ?: MIN_BUFFER_BYTES, MIN_BUFFER_BYTES)

            val track = AudioTrack(
                attrs,
                format,
                bufferSize,
                AudioTrack.MODE_STREAM,
                AudioManager.AUDIO_SESSION_ID_GENERATE,
            )
            if (track.state != AudioTrack.STATE_INITIALIZED) {
                track.release()
                return null
            }
            track
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: UnsupportedOperationException) {
            null
        } catch (_: IllegalStateException) {
            null
        }
    }

    private fun startWriterThread(currentTrack: AudioTrack) {
        val thread = Thread({
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            } catch (_: RuntimeException) {
            }
            try {
                while (running && !Thread.currentThread().isInterrupted) {
                    val written = currentTrack.write(
                        silenceBuffer,
                        0,
                        silenceBuffer.size,
                        AudioTrack.WRITE_BLOCKING,
                    )
                    if (written < 0) {
                        Log.w(TAG, "Keep-alive write failed: $written")
                        break
                    }
                    if (written == 0) Thread.sleep(10)
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (exception: RuntimeException) {
                Log.w(TAG, "Keep-alive writer stopped", exception)
            } finally {
                synchronized(lock) {
                    // A stopped writer must never clear a newer session.
                    if (track === currentTrack) {
                        running = false
                        track = null
                        writerThread = null
                        releaseTrack(currentTrack)
                    }
                }
            }
        }, "CommunicationModeKeepAlive")
        thread.isDaemon = true
        writerThread = thread
        thread.start()
    }

    private fun releaseTrack(currentTrack: AudioTrack?) {
        currentTrack ?: return
        try {
            currentTrack.pause()
        } catch (_: IllegalStateException) {
        }
        try {
            currentTrack.flush()
        } catch (_: IllegalStateException) {
        }
        try {
            currentTrack.stop()
        } catch (_: IllegalStateException) {
        }
        currentTrack.release()
    }
}
