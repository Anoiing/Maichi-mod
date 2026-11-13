package com.maichimod.prototype

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.Ringtone
import android.media.RingtoneManager
import android.media.audiofx.AcousticEchoCanceler
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

internal enum class AudioDirection { CAPTURE, PLAYBACK, RINGTONE }
internal enum class AudioHealth { INACTIVE, STARTING, AVAILABLE, UNAVAILABLE }

/** One call owns its ringtone, audio focus and route. Each media direction can fail independently. */
internal class CallAudio(
    private val context: Context,
    private val createTrack: (AudioFormat, AudioAttributes, Int) -> AudioTrack = { format, attributes, size ->
        AudioTrack.Builder().setAudioAttributes(attributes).setAudioFormat(format)
            .setBufferSizeInBytes(size).setTransferMode(AudioTrack.MODE_STREAM).build()
    },
    private val onHealth: (AudioDirection, AudioHealth) -> Unit,
) {
    private val generation = AtomicInteger()
    private val resources = Any()
    private val queue = ArrayBlockingQueue<ByteArray>(8)
    private val audio = context.getSystemService(AudioManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var record: AudioRecord? = null
    @Volatile private var playback: AudioTrack? = null
    private var restoreRoute: (() -> Unit)? = null
    private var ringtone: Ringtone? = null
    private var focus: AudioFocusRequest? = null
    @Volatile private var hasFocus = false
    private var ringCheck: Runnable? = null

    fun startRinging() {
        stop()
        if (audio.ringerMode != AudioManager.RINGER_MODE_NORMAL) return
        val token = generation.get()
        try {
            if (!requestFocus(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)) {
                report(token, AudioDirection.RINGTONE, AudioHealth.UNAVAILABLE)
                return
            }
            ringtone = RingtoneManager.getRingtone(context, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE))
            ringtone?.audioAttributes = attributes(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
            if (Build.VERSION.SDK_INT >= 28) ringtone?.isLooping = true
            ringtone?.play()
            if (ringtone == null) report(token, AudioDirection.RINGTONE, AudioHealth.UNAVAILABLE)
            else report(token, AudioDirection.RINGTONE, AudioHealth.AVAILABLE)
            if (Build.VERSION.SDK_INT < 28) {
                ringCheck = object : Runnable {
                    override fun run() {
                        if (token != generation.get()) return
                        runCatching { if (hasFocus && ringtone?.isPlaying == false) ringtone?.play() }
                            .onFailure { report(token, AudioDirection.RINGTONE, AudioHealth.UNAVAILABLE) }
                        main.postDelayed(this, 2000)
                    }
                }.also { main.postDelayed(it, 2000) }
            }
        } catch (_: RuntimeException) { report(token, AudioDirection.RINGTONE, AudioHealth.UNAVAILABLE) }
    }

    fun start(capture: Boolean, send: (ByteArray) -> Unit) {
        stop()
        val token = generation.get()
        val oldMode = audio.mode
        try {
            audio.mode = AudioManager.MODE_IN_COMMUNICATION
            val restoreSpeaker = routeSpeaker()
            restoreRoute = {
                try { restoreSpeaker() }
                finally { if (audio.mode == AudioManager.MODE_IN_COMMUNICATION) audio.mode = oldMode }
            }
        } catch (_: RuntimeException) {
            restoreRoute = { if (audio.mode == AudioManager.MODE_IN_COMMUNICATION) audio.mode = oldMode }
        }
        report(token, AudioDirection.PLAYBACK, AudioHealth.STARTING)
        if (requestFocus(AudioAttributes.USAGE_VOICE_COMMUNICATION)) {
            Thread({ play(token) }, "Maichi-speaker").start()
        } else report(token, AudioDirection.PLAYBACK, AudioHealth.UNAVAILABLE)
        if (capture) {
            report(token, AudioDirection.CAPTURE, AudioHealth.STARTING)
            Thread({ capture(token, send) }, "Maichi-microphone").start()
        } else report(token, AudioDirection.CAPTURE, AudioHealth.INACTIVE)
    }

    fun enqueue(pcm: ByteArray) {
        if (!queue.offer(pcm)) { queue.poll(); queue.offer(pcm) }
    }

    fun stop() {
        synchronized(resources) {
            generation.incrementAndGet()
            hasFocus = false
            queue.clear()
            runCatching { record?.stop() }
            runCatching { playback?.pause(); playback?.flush(); playback?.stop() }
        }
        ringCheck?.let(main::removeCallbacks); ringCheck = null
        runCatching { ringtone?.stop() }; ringtone = null
        runCatching { restoreRoute?.invoke() }; restoreRoute = null
        focus?.let { runCatching { audio.abandonAudioFocusRequest(it) } }; focus = null
    }

    private fun play(token: Int) {
        var track: AudioTrack? = null
        try {
            val minimum = AudioTrack.getMinBufferSize(8000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            require(minimum > 0)
            track = createTrack(format(AudioFormat.CHANNEL_OUT_MONO), attributes(AudioAttributes.USAGE_VOICE_COMMUNICATION), maxOf(minimum * 2, 2048))
            synchronized(resources) {
                if (token != generation.get()) return
                playback = track
                if (hasFocus) {
                    track.play()
                    report(token, AudioDirection.PLAYBACK, AudioHealth.AVAILABLE)
                } else report(token, AudioDirection.PLAYBACK, AudioHealth.UNAVAILABLE)
            }
            while (token == generation.get()) {
                val bytes = queue.poll(100, TimeUnit.MILLISECONDS) ?: continue
                if (!hasFocus) continue
                var offset = 0
                while (offset < bytes.size && token == generation.get() && hasFocus) {
                    val count = track.write(bytes, offset, bytes.size - offset, AudioTrack.WRITE_BLOCKING)
                    check(count > 0) { "Audio playback failed" }
                    offset += count
                }
            }
        } catch (_: RuntimeException) { report(token, AudioDirection.PLAYBACK, AudioHealth.UNAVAILABLE) }
        catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        finally {
            synchronized(resources) { if (playback === track) playback = null }
            runCatching { track?.release() }
        }
    }

    private fun capture(token: Int, send: (ByteArray) -> Unit) {
        var recorder: AudioRecord? = null
        var echo: AcousticEchoCanceler? = null
        try {
            Thread.sleep(1100)
            if (token != generation.get()) return
            check(context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
            val minimum = AudioRecord.getMinBufferSize(8000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            require(minimum > 0)
            recorder = AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                .setAudioFormat(format(AudioFormat.CHANNEL_IN_MONO)).setBufferSizeInBytes(maxOf(minimum * 2, 2048)).build()
            if (AcousticEchoCanceler.isAvailable()) echo = AcousticEchoCanceler.create(recorder.audioSessionId)?.apply { enabled = true }
            synchronized(resources) {
                if (token != generation.get()) return
                record = recorder
                recorder.startRecording()
            }
            report(token, AudioDirection.CAPTURE, AudioHealth.AVAILABLE)
            val bytes = ByteArray(512)
            var offset = 0
            while (token == generation.get()) {
                val count = recorder.read(bytes, offset, bytes.size - offset, AudioRecord.READ_BLOCKING)
                check(count > 0) { "Audio capture failed" }
                offset += count
                if (offset == bytes.size) {
                    if (token == generation.get()) send(bytes.copyOf())
                    offset = 0
                }
            }
        } catch (_: RuntimeException) { report(token, AudioDirection.CAPTURE, AudioHealth.UNAVAILABLE) }
        catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        finally {
            synchronized(resources) { if (record === recorder) record = null }
            runCatching { echo?.release() }
            runCatching { recorder?.release() }
        }
    }

    private fun report(token: Int, direction: AudioDirection, health: AudioHealth) {
        main.post {
            if (token == generation.get() && !(health == AudioHealth.AVAILABLE && direction != AudioDirection.CAPTURE && !hasFocus)) {
                onHealth(direction, health)
            }
        }
    }

    private fun attributes(usage: Int) = AudioAttributes.Builder().setUsage(usage)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private fun format(channel: Int) = AudioFormat.Builder().setSampleRate(8000)
        .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(channel).build()

    private fun requestFocus(usage: Int): Boolean = try {
        val token = generation.get()
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attributes(usage)).setOnAudioFocusChangeListener({ change ->
                synchronized(resources) {
                    if (token != generation.get()) return@setOnAudioFocusChangeListener
                    val direction = if (usage == AudioAttributes.USAGE_VOICE_COMMUNICATION) AudioDirection.PLAYBACK else AudioDirection.RINGTONE
                    if (change < 0) {
                        hasFocus = false
                        queue.clear()
                        runCatching { ringtone?.stop(); playback?.pause(); playback?.flush() }
                        report(token, direction, AudioHealth.UNAVAILABLE)
                    } else if (change == AudioManager.AUDIOFOCUS_GAIN) {
                        hasFocus = true
                        val restored = runCatching {
                            playback?.play()
                            if (audio.ringerMode == AudioManager.RINGER_MODE_NORMAL) ringtone?.play()
                        }.isSuccess
                        if (playback != null || ringtone != null) report(token, direction,
                            if (restored) AudioHealth.AVAILABLE else AudioHealth.UNAVAILABLE)
                    }
                }
            }, main).build()
        focus = request
        (audio.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED).also {
            synchronized(resources) { hasFocus = it }
        }
    } catch (_: RuntimeException) { false }

    @Suppress("DEPRECATION")
    private fun routeSpeaker(): () -> Unit {
        if (Build.VERSION.SDK_INT >= 31) {
            val previous = audio.communicationDevice
            audio.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                ?.let { check(audio.setCommunicationDevice(it)) }
            return { if (previous == null || !audio.setCommunicationDevice(previous)) audio.clearCommunicationDevice() }
        }
        val previous = audio.isSpeakerphoneOn
        audio.isSpeakerphoneOn = true
        return { audio.isSpeakerphoneOn = previous }
    }
}
