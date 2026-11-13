package com.maichimod.prototype

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.maichimod.prototype.protocol.*
import com.maichimod.prototype.session.CallStatus
import com.maichimod.prototype.transport.*
import java.io.Closeable
import java.io.IOException
import java.net.Inet4Address
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConnectionCallsTest {
    @Test fun slowUnlockStaysPendingUntilItsResultAndStillEnforcesCooldown() = Fixture().use { fixture ->
        fixture.ring()
        val key = requireNotNull(fixture.owner.currentState.callKey)
        val sending = CountDownLatch(1)
        val release = CountDownLatch(1)
        val first = CountDownLatch(1)
        val second = CountDownLatch(1)
        fixture.onSend = { if (it.getOrNull(10)?.toInt() == 6) {
            sending.countDown(); check(release.await(5, TimeUnit.SECONDS))
        } }
        try {
            fixture.owner.unlockCall(key, false) { assertEquals(UnlockResult.SENT, it); first.countDown() }
            assertTrue(sending.await(2, TimeUnit.SECONDS))
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                assertTrue(fixture.owner.currentState.unlockPending)
                fixture.owner.unlockCall(key, false) {
                    assertEquals(UnlockResult.REJECTED, it); second.countDown(); release.countDown()
                }
            }, 1600)
            assertTrue(second.await(3, TimeUnit.SECONDS))
            assertTrue(first.await(2, TimeUnit.SECONDS))
            assertEquals(1, fixture.commands(6))
            assertFalse(fixture.owner.currentState.unlockPending)
            assertNotEquals(fixture.context.getString(R.string.unlock_waiting), fixture.owner.currentState.issue)
            fixture.onSend = {}
            val third = CountDownLatch(1)
            fixture.owner.unlockCall(key, false) { assertEquals(UnlockResult.SENT, it); third.countDown() }
            assertTrue(third.await(2, TimeUnit.SECONDS))
            val fourth = CountDownLatch(1)
            fixture.owner.unlockCall(key, false) { assertEquals(UnlockResult.REJECTED, it); fourth.countDown() }
            assertTrue(fourth.await(2, TimeUnit.SECONDS))
            assertEquals(2, fixture.commands(6))
        } finally { release.countDown() }
    }

    @Test fun obsoleteProcessKeyCannotAnswerOrUnlockTheCurrentCall() = Fixture().use { fixture ->
        fixture.ring()
        val key = requireNotNull(fixture.owner.currentState.callKey)
        val result = CountDownLatch(1)
        fixture.owner.answerCall(key.copy(runId = "old-runtime"))
        fixture.owner.unlockCall(key.copy(runId = "old-runtime"), false) {
            assertEquals(UnlockResult.REJECTED, it); result.countDown()
        }
        assertTrue(result.await(2, TimeUnit.SECONDS))
        assertEquals(CallStatus.RINGING, fixture.owner.currentState.call)
        assertEquals(0, fixture.commands(5))
        assertEquals(0, fixture.commands(6))
    }

    @Test fun uncertainUnlockIsReportedOnceAndNeverRetried() = Fixture().use { fixture ->
        fixture.ring()
        fixture.onSend = { if (it.getOrNull(10)?.toInt() == 6) throw IOException("Injected uncertain send") }
        val result = CountDownLatch(1)
        val key = requireNotNull(fixture.owner.currentState.callKey)
        fixture.owner.unlockCall(key, true) {
            assertEquals(UnlockResult.UNCONFIRMED, it); result.countDown()
        }
        assertTrue(result.await(2, TimeUnit.SECONDS))
        assertEquals(key, fixture.owner.currentState.callKey)
        assertEquals(CallStatus.DISCONNECTED, fixture.owner.currentState.call)
        assertEquals(fixture.context.getString(R.string.unlock_unconfirmed), fixture.owner.currentState.issue)
        val afterRetry = CountDownLatch(1)
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ afterRetry.countDown() }, 1100)
        assertTrue(afterRetry.await(2, TimeUnit.SECONDS))
        assertEquals(1, fixture.commands(6))
    }

    @Test fun queuedUnlockExpiresBeforeItCanReachTheWire() = Fixture().use { fixture ->
        val release = CountDownLatch(1)
        fixture.onSend = { if (it.getOrNull(10)?.toInt() == 3) check(release.await(4, TimeUnit.SECONDS)) }
        try {
            fixture.ring()
            val result = CountDownLatch(1)
            fixture.owner.unlockCall(requireNotNull(fixture.owner.currentState.callKey), false) {
                assertEquals(UnlockResult.REJECTED, it); result.countDown()
            }
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ release.countDown() }, 1100)
            assertTrue(result.await(3, TimeUnit.SECONDS))
            assertEquals(0, fixture.commands(6))
            assertFalse(fixture.owner.currentState.unlockPending)
            assertNotEquals(fixture.context.getString(R.string.unlock_waiting), fixture.owner.currentState.issue)
        } finally { release.countDown() }
    }

    @Test fun garageMonitorDoesNotReplaceIncomingHouseholdProfile() = Fixture().use { fixture ->
        fixture.owner.openMonitor(MonitorSource.BASEMENT_ONE)
        fixture.ring()
        assertEquals(CallStatus.RINGING, fixture.owner.currentState.call)
        assertFalse(fixture.owner.currentState.canMonitor)
        assertTrue(fixture.owner.currentState.ready)
    }

    private class Fixture : Closeable {
        val context = IsolatedContext()
        val sent = CopyOnWriteArrayList<ByteArray>()
        @Volatile var onSend: (ByteArray) -> Unit = {}
        lateinit var receive: (ReceivedDatagram) -> Unit
        val owner = AppConnection(context, openEthernet = { _, incoming, _ ->
            receive = incoming
            object : DatagramTransport {
                override fun send(localPort: Int, destination: Inet4Address, payload: ByteArray) {
                    sent.add(payload.copyOf()); onSend(payload)
                }
                override fun close() = Unit
            }
        }, openLinks = { _, _, _, changed -> changed(testLink()); Closeable {} })
        init {
            val ready = CountDownLatch(1)
            val subscription = owner.observe(object : IntercomObserver {
                override fun onState(state: IntercomState) { if (state.link == LinkStatus.WIRED_CONNECTED) ready.countDown() }
            })
            owner.start(wiredTestOptions().withField(ConnectionField.BASEMENT_ONE_ID, "00000000002")!!)
            check(ready.await(3, TimeUnit.SECONDS))
            subscription.cancel()
        }
        fun ring() {
            val ringing = CountDownLatch(1)
            val subscription = owner.observe(object : IntercomObserver {
                override fun onState(state: IntercomState) { if (state.call == CallStatus.RINGING) ringing.countDown() }
            })
            receive(ReceivedDatagram(DEMO_PEER_IP, 10000, 10000,
                CallPackets.encodeSignal(CallCommand.NEGOTIATE, DEMO_PROFILE, DEMO_PEER_IP).copyOf(256).apply {
                    this[10] = 1; this[14] = 0; this[15] = 1
                }))
            assertTrue(ringing.await(3, TimeUnit.SECONDS)); subscription.cancel()
        }
        fun commands(type: Int) = sent.count { it.size >= 80 && it[8] == 0xb7.toByte() && it[10].toInt() == type }
        override fun close() { owner.close(); context.clear(); InstrumentationRegistry.getInstrumentation().waitForIdleSync() }
    }
}
