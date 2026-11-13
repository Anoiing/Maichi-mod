package com.maichimod.prototype

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

internal class CallAudio(private val context: Context, private val onFailure: () -> Unit) {
    private val generation = AtomicInteger()
    private val queue = ArrayBlockingQueue<ByteArray>(8)
    private val audio = context.getSystemService(AudioManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var record: AudioRecord? = null
    @Volatile private var playback: AudioTrack? = null
    private var restoreRoute: (() -> Unit)? = null

    fun start(capture: Boolean, send: (ByteArray) -> Unit) {
        stop()
        val token = generation.get()
        val oldMode = audio.mode
        restoreRoute = { if (audio.mode == AudioManager.MODE_IN_COMMUNICATION) audio.mode = oldMode }
        try {
            audio.mode = AudioManager.MODE_IN_COMMUNICATION
            val restoreSpeaker = routeSpeaker()
            restoreRoute = {
                restoreSpeaker()
                if (audio.mode == AudioManager.MODE_IN_COMMUNICATION) audio.mode = oldMode
            }
        } catch (_: RuntimeException) {
            reportFailure(token)
            return
        }
        Thread({
            var track: AudioTrack? = null
            try {
                val minimum = AudioTrack.getMinBufferSize(8000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
                require(minimum > 0)
                track = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    .setAudioFormat(format(AudioFormat.CHANNEL_OUT_MONO))
                    .setBufferSizeInBytes(maxOf(minimum * 2, 2048)).setTransferMode(AudioTrack.MODE_STREAM).build()
                if (token != generation.get()) return@Thread
                playback = track
                track.play()
                while (token == generation.get()) {
                    val bytes = queue.poll(100, TimeUnit.MILLISECONDS) ?: continue
                    if (track.write(bytes, 0, bytes.size, AudioTrack.WRITE_BLOCKING) < 0) error("Audio playback failed")
                }
            } catch (_: RuntimeException) {
                reportFailure(token)
            } finally {
                if (playback === track) playback = null
                runCatching { track?.release() }
            }
        }, "Maichi-speaker").start()
        if (capture) Thread({ capture(token, send) }, "Maichi-microphone").start()
    }

    fun enqueue(pcm: ByteArray) {
        if (!queue.offer(pcm)) { queue.poll(); queue.offer(pcm) }
    }

    fun stop() {
        generation.incrementAndGet()
        queue.clear()
        runCatching { record?.stop() }
        runCatching { playback?.pause(); playback?.flush() }
        runCatching { restoreRoute?.invoke() }; restoreRoute = null
    }

    private fun capture(token: Int, send: (ByteArray) -> Unit) {
        var recorder: AudioRecord? = null
        var echo: AcousticEchoCanceler? = null
        try {
            Thread.sleep(1100)
            if (token != generation.get() || context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
            val minimum = AudioRecord.getMinBufferSize(8000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            require(minimum > 0)
            recorder = AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                .setAudioFormat(format(AudioFormat.CHANNEL_IN_MONO)).setBufferSizeInBytes(maxOf(minimum * 2, 2048)).build()
            if (token != generation.get()) return
            record = recorder
            if (AcousticEchoCanceler.isAvailable()) echo = AcousticEchoCanceler.create(recorder.audioSessionId)?.apply { enabled = true }
            recorder.startRecording()
            val bytes = ByteArray(512)
            var offset = 0
            while (token == generation.get()) {
                val count = recorder.read(bytes, offset, bytes.size - offset, AudioRecord.READ_BLOCKING)
                if (count <= 0) error("Audio capture failed")
                offset += count
                if (offset == bytes.size) {
                    if (token == generation.get()) send(bytes.copyOf())
                    offset = 0
                }
            }
        } catch (_: RuntimeException) {
            reportFailure(token)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            if (record === recorder) record = null
            runCatching { echo?.release(); recorder?.release() }
        }
    }

    private fun format(channel: Int) = AudioFormat.Builder().setSampleRate(8000)
        .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(channel).build()

    private fun reportFailure(token: Int) {
        main.post { if (token == generation.get()) { stop(); onFailure() } }
    }

    @Suppress("DEPRECATION")
    private fun routeSpeaker(): () -> Unit {
        if (Build.VERSION.SDK_INT >= 31) {
            val previous = audio.communicationDevice
            audio.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                ?.let { audio.setCommunicationDevice(it) }
            return { if (previous != null) audio.setCommunicationDevice(previous) else audio.clearCommunicationDevice() }
        }
        val previous = audio.isSpeakerphoneOn
        audio.isSpeakerphoneOn = true
        return { audio.isSpeakerphoneOn = previous }
    }
}
