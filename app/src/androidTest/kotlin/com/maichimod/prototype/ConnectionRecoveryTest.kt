package com.maichimod.prototype

import com.maichimod.prototype.transport.EthernetState
import com.maichimod.prototype.transport.DatagramTransport
import com.maichimod.prototype.transport.ReceivedDatagram
import com.maichimod.prototype.protocol.DEMO_PEER_IP
import com.maichimod.prototype.protocol.DEMO_PROFILE
import com.maichimod.prototype.protocol.CallPackets
import com.maichimod.prototype.protocol.CallCommand
import com.maichimod.prototype.session.SessionScheduler
import com.maichimod.prototype.session.Cancellation
import androidx.test.platform.app.InstrumentationRegistry
import java.net.Inet4Address
import android.os.Handler

import com.maichimod.prototype.session.UnlockResult

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConnectionRecoveryTest {
    @Test fun expiredUnlockIsRejectedWithoutReportingAnUncertainSend() {
        val context = IsolatedContext()
        lateinit var clock: WorkerClock
        lateinit var receive: (ReceivedDatagram) -> Unit
        val sent = java.util.concurrent.CopyOnWriteArrayList<ByteArray>()
        val owner = AppConnection(context, openEthernet = { _, incoming, _ ->
            receive = incoming
            object : DatagramTransport {
                override fun send(localPort: Int, destination: Inet4Address, payload: ByteArray) { sent.add(payload) }
                override fun close() = Unit
            }
        }, openLinks = { _, _, _, change -> change(EthernetState(testLink())); Closeable {} },
            createScheduler = { WorkerClock(it).also { clock = it } })
        try {
            owner.start(wiredTestOptions()); clock.flush()
            receive(ReceivedDatagram(DEMO_PEER_IP, 10000, 10000,
                CallPackets.encodeSignal(CallCommand.NEGOTIATE,
                    DEMO_PROFILE, DEMO_PEER_IP).copyOf(256).apply { this[10] = 1; this[14] = 0; this[15] = 1 }))
            clock.flush()
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            val key = requireNotNull(owner.currentState.callKey)
            clock.sleep(8_000)
            val checked = CountDownLatch(1)
            var checkedReason: CallEndReason? = null
            owner.checkDeadlines { checkedReason = owner.currentState.callEndReason; checked.countDown() }
            assertTrue(checked.await(2, TimeUnit.SECONDS))
            assertEquals(CallEndReason.TIMED_OUT, checkedReason)
            val done = CountDownLatch(1)
            var result: UnlockResult? = null
            owner.unlockCall(key, false) { result = it; done.countDown() }
            assertTrue(done.await(2, TimeUnit.SECONDS))
            assertEquals(UnlockResult.REJECTED, result)
            assertEquals(0, sent.count { it.size >= 80 && it[10].toInt() == 6 })
            assertEquals(CallEndReason.TIMED_OUT, owner.currentState.callEndReason)
        } finally { owner.close(); context.clear() }
    }

    @Test fun repeatedManualRetryWhileBindingDoesNotCreateMoreOwners() {
        val context = IsolatedContext()
        val count = AtomicInteger()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        lateinit var clock: WorkerClock
        val owner = AppConnection(context, openLinks = { _, _, _, _ ->
            if (count.incrementAndGet() == 2) { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
            Closeable {}
        }, createScheduler = { WorkerClock(it).also { clock = it } })
        try {
            owner.start(wiredTestOptions()); clock.flush()
            owner.retry()
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            repeat(20) { owner.retry() }
            release.countDown(); clock.flush()
            assertEquals(2, count.get())
        } finally { release.countDown(); owner.close(); context.clear() }
    }

    @Test fun queuedStopCannotPublishDisabledAfterANewerStartHasBound() {
        val context = IsolatedContext()
        val starts = AtomicInteger()
        val restarted = CountDownLatch(1)
        lateinit var clock: WorkerClock
        val owner = AppConnection(context, openLinks = { _, _, _, _ ->
            if (starts.incrementAndGet() == 2) restarted.countDown()
            Closeable {}
        }, createScheduler = { WorkerClock(it).also { clock = it } })
        try {
            owner.start(wiredTestOptions()); clock.flush()
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            instrumentation.waitForIdleSync()
            var disabled = 0
            val sub = owner.observe(object : IntercomObserver { override fun onState(state: IntercomState) { if (!state.enabled) disabled++ } })
            try {
                instrumentation.runOnMainSync {
                    owner.stop(); owner.start(wiredTestOptions())
                    assertTrue(restarted.await(2, TimeUnit.SECONDS))
                }
                instrumentation.waitForIdleSync()
                assertEquals(0, disabled)
                assertTrue(owner.currentState.enabled)
            } finally { sub.cancel() }
        } finally { owner.close(); context.clear() }
    }

    @Test fun missingAddressExplainsWaitAfterTenSecondsAndNoLinkDoesNotSpin() {
        val context = IsolatedContext()
        lateinit var clock: WorkerClock
        lateinit var changed: (EthernetState) -> Unit
        val attempts = AtomicInteger()
        val owner = AppConnection(context, openEthernet = { _, _, _ -> attempts.incrementAndGet(); throw IOException("bind") },
            openLinks = { _, _, _, change -> changed = change; change(EthernetState(present = true)); Closeable {} },
            createScheduler = { WorkerClock(it).also { clock = it } })
        try {
            owner.start(wiredTestOptions()); clock.flush()
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            assertEquals(LinkStatus.WIRED_ADDRESS_PENDING, owner.currentState.link)
            assertNull(owner.currentState.issue)
            clock.advance(10000)
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            assertNotNull(owner.currentState.issue)
            clock.run { changed(EthernetState()) }
            clock.advance(60000)
            assertEquals(0, attempts.get())
            clock.run { changed(EthernetState(testLink())) }
            assertEquals(1, attempts.get())
        } finally { owner.close(); context.clear() }
    }

    @Test fun retryDelaysAreBoundedAndBindingDoesNotResetFailures() {
        val context = IsolatedContext()
        lateinit var clock: WorkerClock
        val attempts = mutableListOf<Long>()
        var readFailure: ((IOException) -> Unit)? = null
        var incoming: ((ReceivedDatagram) -> Unit)? = null
        var bindingFails = true
        val owner = AppConnection(context, openEthernet = { _, receive, failed ->
            attempts.add(clock.nowMs)
            if (bindingFails) throw IOException("Bind failed")
            readFailure = failed
            incoming = receive
            object : DatagramTransport {
                override fun send(localPort: Int, destination: Inet4Address, payload: ByteArray) = Unit
                override fun close() = Unit
            }
        }, openLinks = { _, _, _, change -> change(EthernetState(testLink())); Closeable {} }, createScheduler = { WorkerClock(it).also { clock = it } })
        try {
            owner.start(wiredTestOptions()); clock.flush()
            val expected = mutableListOf(0L)
            for (delay in listOf(500L, 1000L, 2000L, 5000L, 10000L, 30000L, 30000L)) {
                clock.advance(delay - 1)
                assertEquals(expected, attempts)
                clock.advance(1)
                expected.add(clock.nowMs)
                assertEquals(expected, attempts)
            }
            bindingFails = false
            clock.advance(30000)
            val count = attempts.size
            val oldFailure = requireNotNull(readFailure)
            oldFailure(IOException("Read failed after binding")); clock.flush()
            clock.advance(29999)
            assertEquals(count, attempts.size)
            clock.advance(1)
            assertEquals(count + 1, attempts.size)
            oldFailure(IOException("Late callback from retired socket")); clock.flush()
            clock.advance(1000)
            assertEquals(count + 1, attempts.size)
            val profile = DEMO_PROFILE
            val query = ByteArray(35).apply { this[0] = 1; profile.indoorId.toByteArray().copyInto(this, 1) }
            requireNotNull(incoming)(ReceivedDatagram(DEMO_PEER_IP, 10008, 10008, query))
            clock.flush()
            requireNotNull(readFailure)(IOException("Failure after valid peer packet")); clock.flush()
            clock.advance(499); assertEquals(count + 1, attempts.size)
            clock.advance(1); assertEquals(count + 2, attempts.size)
            clock.advance(60_000)
            requireNotNull(readFailure)(IOException("Failure after stable minute")); clock.flush()
            clock.advance(499); assertEquals(count + 2, attempts.size)
            clock.advance(1); assertEquals(count + 3, attempts.size)
        } finally { owner.close(); context.clear() }
    }

    @Test fun transientBindFailuresKeepRetryingWithoutUserInteraction() {
        val context = IsolatedContext()
        val attempts = AtomicInteger()
        val third = CountDownLatch(1)
        val owner = AppConnection(context, openEthernet = { _, _, _ ->
            if (attempts.incrementAndGet() == 3) third.countDown()
            throw IOException("Temporary bind failure")
        }, openLinks = { _, _, _, change -> change(EthernetState(testLink())); Closeable {} })
        try {
            owner.start(wiredTestOptions())
            assertTrue("stopped retrying after the second bind", third.await(3, TimeUnit.SECONDS))
        } finally { owner.close(); context.clear() }
    }

    @Test fun permissionFailureDoesNotAutomaticallyRetry() {
        val context = IsolatedContext()
        val attempts = AtomicInteger()
        val first = CountDownLatch(1)
        val owner = AppConnection(context, openEthernet = { _, _, _ ->
            attempts.incrementAndGet(); first.countDown()
            throw SecurityException("Permission denied")
        }, openLinks = { _, _, _, change -> change(EthernetState(testLink())); Closeable {} })
        try {
            owner.start(wiredTestOptions())
            assertTrue(first.await(2, TimeUnit.SECONDS))
            android.os.SystemClock.sleep(750)
            assertEquals(1, attempts.get())
        } finally { owner.close(); context.clear() }
    }
}

internal class WorkerClock(private val handler: Handler) : SessionScheduler {
    private data class Job(val at: Long, val action: () -> Unit, var cancelled: Boolean = false)
    private val jobs = mutableListOf<Job>()
    @Volatile override var nowMs = 0L
        private set
    override fun schedule(delayMs: Long, action: () -> Unit): Cancellation {
        val job = Job(nowMs + delayMs, action)
        jobs.add(job)
        return Cancellation { job.cancelled = true }
    }
    fun flush() = run {}
    fun sleep(ms: Long) = run { nowMs += ms }
    fun run(action: () -> Unit) {
        val done = CountDownLatch(1)
        var failure: Throwable? = null
        check(handler.post { try { action() } catch (caught: Throwable) { failure = caught } finally { done.countDown() } })
        check(done.await(5, TimeUnit.SECONDS))
        failure?.let { throw it }
    }
    fun advance(ms: Long) = run {
        val target = nowMs + ms
        while (true) {
            val next = jobs.filter { !it.cancelled && it.at <= target }.minByOrNull { it.at } ?: break
            jobs.remove(next); nowMs = next.at; next.action()
        }
        nowMs = target
    }
}
