package com.maichimod.prototype

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.maichimod.prototype.protocol.CallPacket
import com.maichimod.prototype.protocol.CallPackets
import com.maichimod.prototype.protocol.DEMO_PROFILE
import com.maichimod.prototype.protocol.DEMO_PEER_IP
import com.maichimod.prototype.protocol.MediaFragment
import com.maichimod.prototype.session.CallStatus
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CallAudioUplinkTest {
    @Test fun answeredMicrophoneProducesUplinkPacketsAndHangupStopsThem() = withMicrophone { fixture ->
        val owner = fixture.owner
        val sent = CountDownLatch(3)
        fixture.onSend = { if (isAudio(it)) sent.countDown() }
        fixture.ring()
        val key = requireNotNull(owner.currentState.callKey)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        assertEquals(AudioHealth.INACTIVE, owner.currentState.microphone)
        assertTrue(audioPackets(fixture).isEmpty())

        owner.answerCall(key, capture = true)
        assertTrue("Microphone PCM never reached the call transport", sent.await(5, TimeUnit.SECONDS))
        awaitState(owner) { it.microphone == AudioHealth.AVAILABLE }
        val packets = audioPackets(fixture)
        assertTrue(packets.size >= 3)
        packets.take(3).forEachIndexed { index, bytes ->
            val media = CallPackets.decode(10000, bytes) as? CallPacket.Media
                ?: throw AssertionError("Captured PCM did not produce a valid call media packet")
            assertEquals(MediaFragment.AUDIO, media.fragment.kind)
            assertEquals(512, media.fragment.bytes.size)
            assertEquals(index + 1, media.fragment.frameNumber)
            assertEquals(DEMO_PROFILE.indoorId, media.identity.indoorId)
            assertEquals(DEMO_PROFILE.localAddress, media.identity.indoorAddress)
            assertEquals(DEMO_PROFILE.peerId, media.identity.peerId)
            assertEquals(DEMO_PEER_IP, media.identity.peerAddress)
        }
        owner.hangUpCall(key)
        awaitState(owner) { it.call == CallStatus.ENDED }
        val endedCount = audioPackets(fixture).size
        android.os.SystemClock.sleep(250)
        assertEquals("Microphone audio continued to reach transport after hangup", endedCount, audioPackets(fixture).size)
    }

    @Test fun microphoneRetryProducesUplinkWithoutSendingAnotherAnswer() = withMicrophone { fixture ->
        val owner = fixture.owner
        val sent = CountDownLatch(3)
        fixture.onSend = { if (isAudio(it)) sent.countDown() }
        fixture.ring()
        val key = requireNotNull(owner.currentState.callKey)
        owner.answerCall(key, capture = false)
        awaitState(owner) { it.call == CallStatus.ANSWERED &&
            it.playback in listOf(AudioHealth.AVAILABLE, AudioHealth.UNAVAILABLE) }
        assertEquals(AudioHealth.INACTIVE, owner.currentState.microphone)
        assertTrue(audioPackets(fixture).isEmpty())

        owner.retryCallAudio(key, capture = true)
        assertTrue("Audio retry never delivered captured PCM", sent.await(5, TimeUnit.SECONDS))
        awaitState(owner) { it.microphone == AudioHealth.AVAILABLE }
        assertEquals("Audio retry repeated the peer answer command", 1, fixture.commands(5))
    }

    @Test fun queuedAudioRetryCannotRestartCaptureAfterHangup() {
        assumeTrue(Build.HARDWARE == "ranchu")
        ActivityScenario.launch(MainActivity::class.java).use {
            WiredIntercomFixture().use { fixture ->
                val owner = fixture.owner
                fixture.ring()
                val key = requireNotNull(owner.currentState.callKey)
                owner.answerCall(key, capture = false)
                awaitState(owner) { state -> state.call == CallStatus.ANSWERED &&
                    state.playback in listOf(AudioHealth.AVAILABLE, AudioHealth.UNAVAILABLE) }
                val unexpectedCapture = CountDownLatch(1)
                val subscription = owner.observe(object : IntercomObserver {
                    override fun onState(state: IntercomState) {
                        if (state.callEnding && state.microphone != AudioHealth.INACTIVE) unexpectedCapture.countDown()
                    }
                })
                val blocked = CountDownLatch(1)
                val release = CountDownLatch(1)
                val worker = AppConnection::class.java.getDeclaredField("worker").apply { isAccessible = true }.get(owner) as Handler
                try {
                    InstrumentationRegistry.getInstrumentation().runOnMainSync {
                        owner.retryCallAudio(key, capture = true)
                        // The retry has queued its main callback before this worker gate is reached.
                        worker.post { blocked.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
                        assertTrue(blocked.await(2, TimeUnit.SECONDS))
                        owner.hangUpCall(key)
                        assertTrue(owner.currentState.callEnding)
                    }
                    InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                    assertEquals("Queued retry restarted microphone capture while the call was ending",
                        1L, unexpectedCapture.count)
                } finally {
                    release.countDown()
                    subscription.cancel()
                }
                awaitState(owner) { state -> state.call == CallStatus.ENDED }
            }
        }
    }

    private fun awaitState(owner: AppConnection, predicate: (IntercomState) -> Boolean) {
        val ready = CountDownLatch(1)
        val subscription = owner.observe(object : IntercomObserver {
            override fun onState(state: IntercomState) { if (predicate(state)) ready.countDown() }
        })
        try { assertTrue("Call audio state did not settle", ready.await(5, TimeUnit.SECONDS)) }
        finally { subscription.cancel() }
    }

    private fun withMicrophone(action: (WiredIntercomFixture) -> Unit) {
        // Keep real AudioRecord checks on an isolated emulator; PCM need not contain audible sound.
        assumeTrue(Build.HARDWARE == "ranchu")
        val context = ApplicationProvider.getApplicationContext<Context>()
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            InstrumentationRegistry.getInstrumentation().uiAutomation
                .grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        }
        ActivityScenario.launch(MainActivity::class.java).use { WiredIntercomFixture().use(action) }
    }

    private fun isAudio(bytes: ByteArray) = bytes.size == 602 && bytes[8] == 0xb7.toByte() && bytes[10].toInt() == 10
    private fun audioPackets(fixture: WiredIntercomFixture) = fixture.sent.filter(::isAudio)
}
