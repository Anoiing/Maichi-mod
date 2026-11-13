package com.maichimod.prototype

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StartupRecoveryTest {
    @Test fun startupRespectsSavedDisableAndLockedStorage() {
        val isolated = IsolatedContext()
        var starts = 0
        val context = object : ContextWrapper(isolated) {
            override fun startForegroundService(intent: Intent): android.content.ComponentName {
                starts++
                assertEquals(IntercomService::class.java.name, intent.component?.className)
                return requireNotNull(intent.component)
            }
        }
        try {
            val unlocked = AppSettings(isolated) { true }
            assertTrue(unlocked.save(wiredTestOptions()))
            StartupReceiver.restore(context, unlocked)
            assertEquals(0, starts)
            assertTrue(unlocked.setReceptionEnabled(true))
            val lockedContext = object : ContextWrapper(isolated) {
                override fun getSharedPreferences(name: String, mode: Int): android.content.SharedPreferences = error("CE preferences read while locked")
                override fun getFilesDir(): java.io.File = error("CE files read while locked")
            }
            StartupReceiver.restore(context, AppSettings(lockedContext) { false })
            assertEquals(1, starts)
            assertTrue(unlocked.setReceptionEnabled(false))
            StartupReceiver.restore(context, AppSettings(lockedContext) { false })
            assertEquals(1, starts)
        } finally { isolated.clear() }
    }

    @Test fun validEthernetRetryKeepsCpuLeaseButBlockedReceptionReleasesIt() {
        org.junit.Assume.assumeTrue(android.os.Build.HARDWARE == "ranchu")
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<Context>()
        val connected = java.util.concurrent.CountDownLatch(1)
        lateinit var service: IntercomService
        val binding = object : android.content.ServiceConnection {
            override fun onServiceConnected(name: android.content.ComponentName, binder: android.os.IBinder) {
                service = (binder as IntercomService.LocalBinder).service; connected.countDown()
            }
            override fun onServiceDisconnected(name: android.content.ComponentName) = Unit
        }
        val bound = context.bindService(Intent(context, IntercomService::class.java), binding, Context.BIND_AUTO_CREATE)
        assertTrue(bound)
        try {
            assertTrue(connected.await(5, java.util.concurrent.TimeUnit.SECONDS))
            val state = IntercomState(options = wiredTestOptions(), enabled = true, ethernet = testLink(), link = LinkStatus.WIRED_RECONNECTING)
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().runOnMainSync {
                assertTrue(service.syncCpuLease(state))
                assertTrue(service.syncCpuLease(state))
                assertFalse(service.syncCpuLease(state.copy(link = LinkStatus.WIRED_ERROR)))
                assertFalse(service.syncCpuLease(state.copy(ethernet = null, link = LinkStatus.WIRED_WAITING)))
                assertFalse(service.syncCpuLease(state.copy(enabled = false)))
                assertTrue(service.syncCpuLease(state.copy(ethernet = null, call = com.maichimod.prototype.session.CallStatus.RINGING)))
                assertFalse(service.syncCpuLease(state.copy(enabled = false)))
            }
        } finally { context.unbindService(binding) }
    }
}
