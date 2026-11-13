package com.maichimod.prototype

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maichimod.prototype.protocol.*
import com.maichimod.prototype.session.CallStatus
import com.maichimod.prototype.transport.ReceivedDatagram
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReceptionStatusTest {
    @Test fun startupClearsObsoleteCallNoticesAndPreservesOtherNotices() {
        org.junit.Assume.assumeTrue(android.os.Build.HARDWARE == "ranchu")
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.grantRuntimePermission(context.packageName, android.Manifest.permission.POST_NOTIFICATIONS)
        context.stopService(android.content.Intent(context, IntercomService::class.java))
        instrumentation.waitForIdleSync()
        val manager = context.getSystemService(android.app.NotificationManager::class.java)
        manager.createNotificationChannel(android.app.NotificationChannel("test-cleanup", "test", android.app.NotificationManager.IMPORTANCE_LOW))
        val notice = android.app.Notification.Builder(context, "test-cleanup").setSmallIcon(R.drawable.ic_answer_call).setContentTitle("test").build()
        manager.notify("call:obsolete:1", 101, notice)
        manager.notify("other-test", 102, notice)
        val deadline = android.os.SystemClock.elapsedRealtime() + 2000
        while (manager.activeNotifications.none { it.tag == "call:obsolete:1" } && android.os.SystemClock.elapsedRealtime() < deadline) {
            android.os.SystemClock.sleep(20)
        }
        assertTrue(manager.activeNotifications.any { it.tag == "call:obsolete:1" })
        try {
            RunningIntercom().use {
                assertFalse(manager.activeNotifications.any { it.id == 101 && it.tag == "call:obsolete:1" })
                assertTrue(manager.activeNotifications.any { it.id == 102 && it.tag == "other-test" })
            }
        } finally {
            manager.cancel("call:obsolete:1", 101)
            manager.cancel("other-test", 102)
        }
    }

    @Test fun missingAndInvalidVideoDegradeWithoutEndingCallAndValidVideoRecovers() = WiredIntercomFixture().use { fixture ->
        fixture.ring()
        awaitState(fixture.owner, 6) { it.video == VideoHealth.MISSING }
        assertEquals(CallStatus.RINGING, fixture.owner.currentState.call)
        fixture.receive(ReceivedDatagram(DEMO_PEER_IP, 10000, 10000,
            CallPackets.encodeSignal(CallCommand.KEEP_ALIVE, DEMO_PROFILE, DEMO_PEER_IP)))
        fixture.receive(video(1, validJpeg()))
        awaitState(fixture.owner, 2) { it.video == VideoHealth.AVAILABLE }
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            fixture.receive(video(2, byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xd9.toByte())))
        }, 2500)
        awaitState(fixture.owner, 4) { it.video == VideoHealth.STALLED }
        assertTrue(fixture.owner.currentState.inCall)
        assertTrue(fixture.owner.currentState.ready)
        fixture.receive(video(3, validJpeg()))
        awaitState(fixture.owner, 2) { it.video == VideoHealth.AVAILABLE }
    }

    @Test fun retryingVoiceDoesNotSendAnotherAnswerCommand() = WiredIntercomFixture().use { fixture ->
        fixture.ring()
        val key = requireNotNull(fixture.owner.currentState.callKey)
        fixture.owner.answerCall(key)
        awaitState(fixture.owner, 2) { it.call == CallStatus.ANSWERED }
        fixture.owner.retryCallAudio(key, false)
        val barrier = CountDownLatch(1)
        fixture.owner.updateOptions(fixture.owner.currentState.options) { barrier.countDown() }
        assertTrue(barrier.await(2, TimeUnit.SECONDS))
        assertEquals(1, fixture.commands(5))
        assertEquals(key, fixture.owner.currentState.callKey)
        assertEquals(CallStatus.ANSWERED, fixture.owner.currentState.call)
    }

    @Test fun networkIconsRemainNonInteractive() = RunningIntercom().use { fixture ->
        fixture.scenario.onActivity { activity ->
            for (id in listOf(R.id.connection_state, R.id.sources_state, R.id.monitor_state)) {
                val icon = activity.findViewById<android.view.View>(id)
                assertFalse(icon.isClickable)
                assertFalse(icon.isFocusable)
                assertNull(icon.background)
                assertNull(icon.tooltipText)
            }
        }
        Unit
    }

    private fun awaitState(owner: AppConnection, seconds: Long, predicate: (IntercomState) -> Boolean) {
        val ready = CountDownLatch(1)
        val sub = owner.observe(object : IntercomObserver {
            override fun onState(state: IntercomState) { if (predicate(state)) ready.countDown() }
        })
        try { assertTrue(ready.await(seconds, TimeUnit.SECONDS)) } finally { sub.cancel() }
    }
    private fun validJpeg(): ByteArray = java.io.ByteArrayOutputStream().use { output ->
        val bitmap = android.graphics.Bitmap.createBitmap(4, 4, android.graphics.Bitmap.Config.ARGB_8888)
        try { bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, output); output.toByteArray() }
        finally { bitmap.recycle() }
    }
    private fun video(sequence: Int, jpeg: ByteArray): ReceivedDatagram {
        val bytes = CallPackets.encodeAudio(DEMO_PROFILE, DEMO_PEER_IP, sequence, ByteArray(512)).copyOf(90 + jpeg.size)
        fun put16(at: Int, number: Int) { bytes[at] = number.toByte(); bytes[at + 1] = (number ushr 8).toByte() }
        put16(14, bytes.size); put16(80, 1); put16(88, jpeg.size)
        jpeg.copyInto(bytes, 90)
        return ReceivedDatagram(DEMO_PEER_IP, 10000, 10000, bytes)
    }
}
