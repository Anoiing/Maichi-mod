package com.maichimod.prototype

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppConnectionTest {
    @Test fun closedOwnerCannotCommitAQueuedSaveBehindItsReplacement() {
        val ctx = IsolatedContext()
        val original = wiredTestOptions()
        assertTrue(AppSettings(ctx).save(original))
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val complete = CountDownLatch(1)
        var saved = true
        val connection = AppConnection(ctx, openLinks = { _, _, _, _ ->
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            java.io.Closeable { }
        })
        try {
            connection.start(original)
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            connection.updateOptions(original.copy(autoHangUpAfterUnlock = false), original) { saved = it; complete.countDown() }
            connection.close()
            assertEquals(original, AppSettings(ctx).load())
            release.countDown()
            assertTrue(complete.await(2, TimeUnit.SECONDS))
            assertFalse(saved)
            assertEquals(original, AppSettings(ctx).load())
        } finally { release.countDown(); connection.close(); ctx.clear() }
    }

    @Test fun staleSaveCannotSilentlyUndoAnotherSavedField() {
        val ctx = IsolatedContext()
        val connection = AppConnection(ctx)
        val done = CountDownLatch(2)
        var firstSaved = false
        var secondSaved = true
        try {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                val original = connection.currentState.options
                connection.updateOptions(original.copy(autoHangUpAfterUnlock = false)) { firstSaved = it; done.countDown() }
                connection.updateOptions(original.withField(ConnectionField.CENTER_IP, "192.0.2.30")!!) { secondSaved = it; done.countDown() }
            }
            assertTrue(done.await(3, TimeUnit.SECONDS))
            assertTrue(firstSaved)
            assertFalse(secondSaved)
            assertFalse(AppSettings(ctx).load().autoHangUpAfterUnlock)
        } finally { connection.close(); ctx.clear() }
    }

    @Test fun manualRetryRebindsAfterFailureWithUnchangedOptions() {
        val ctx = IsolatedContext()
        val bindings = java.util.concurrent.atomic.AtomicInteger()
        val failed = CountDownLatch(1)
        val retried = CountDownLatch(1)
        val connection = AppConnection(ctx, openEthernet = { _, _, _ ->
            if (bindings.incrementAndGet() >= 3) retried.countDown()
            throw java.io.IOException("Injected bind failure")
        }, openLinks = { _, _, _, changed ->
            changed(com.maichimod.prototype.transport.EthernetState(testLink()))
            java.io.Closeable { }
        })
        val sub = connection.observe(object : IntercomObserver {
            override fun onState(state: IntercomState) {
                if (state.link == LinkStatus.WIRED_RECONNECTING && state.retryAttempt >= 2) failed.countDown()
            }
        })
        try {
            connection.start(wiredTestOptions())
            assertTrue(failed.await(5, TimeUnit.SECONDS))
            connection.retry()
            assertTrue(retried.await(3, TimeUnit.SECONDS))
        } finally { sub.cancel(); connection.close(); ctx.clear() }
    }

    @Test fun observerReceivesCurrentStateAndCanDetach() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val ctx = IsolatedContext()
        // Subscription delivery does not depend on decoding the large replay assets on a cold device.
        val connection = AppConnection(ctx, openEthernet = { _, _, _ ->
            object : com.maichimod.prototype.transport.DatagramTransport {
                override fun send(localPort: Int, destination: java.net.Inet4Address, payload: ByteArray) = Unit
                override fun close() = Unit
            }
        }, openLinks = { _, _, _, changed ->
            changed(com.maichimod.prototype.transport.EthernetState(testLink()))
            java.io.Closeable { }
        })
        var callbacks = 0
        var onMain = false
        val ready = CountDownLatch(1)
        lateinit var subscription: com.maichimod.prototype.session.Cancellation
        instrumentation.runOnMainSync {
            subscription = connection.observe(object : IntercomObserver {
                override fun onState(state: IntercomState) {
                    callbacks++
                    onMain = android.os.Looper.myLooper() == android.os.Looper.getMainLooper()
                    if (state.link != null) ready.countDown()
                }
            })
            assertEquals(1, callbacks)
        }
        try {
            connection.start(wiredTestOptions().copy(simulateTimeout = true))
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            instrumentation.runOnMainSync {
                assertTrue(onMain)
                assertTrue(connection.currentState.options.simulateTimeout)
                subscription.cancel()
            }
            val count = callbacks
            connection.stop()
            instrumentation.waitForIdleSync()
            val barrier = CountDownLatch(1)
            instrumentation.runOnMainSync { android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ barrier.countDown() }, 150) }
            assertTrue(barrier.await(2, TimeUnit.SECONDS))
            assertEquals(count, callbacks)
        } finally { connection.close(); ctx.clear() }
    }
}
