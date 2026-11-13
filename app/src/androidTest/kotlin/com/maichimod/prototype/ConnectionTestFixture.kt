package com.maichimod.prototype

import android.content.Context
import android.content.ContextWrapper
import android.net.Network
import androidx.test.core.app.ApplicationProvider
import com.maichimod.prototype.protocol.DEMO_PROFILE
import com.maichimod.prototype.transport.WiredLink
import java.io.File
import java.util.UUID

internal class IsolatedContext(private val base: Context = ApplicationProvider.getApplicationContext(),
    private val key: String = "test-${UUID.randomUUID()}", private val device: Boolean = false) : ContextWrapper(base) {
    private val root get() = File(base.cacheDir, key)
    override fun getApplicationContext(): Context = this
    override fun createDeviceProtectedStorageContext(): Context = IsolatedContext(base, key, true)
    override fun getFilesDir(): File = File(root, if (device) "device" else "credential").apply { mkdirs() }
    override fun getSharedPreferences(name: String, mode: Int) = base.getSharedPreferences("$key-$name", mode)
    fun clear() { root.deleteRecursively(); base.deleteSharedPreferences("$key-MainActivity") }
}

internal fun testLink() = WiredLink(Network.fromNetworkHandle(0x7cafed00dL), DEMO_PROFILE.localAddress, 24, null)
internal fun wiredTestOptions() = ConnectionOptions(wired = true)
    .withField(ConnectionField.INDOOR_ID, DEMO_PROFILE.indoorId)!!
    .withField(ConnectionField.PEER_ID, DEMO_PROFILE.peerId)!!

/** This harness is restricted to the isolated emulator; it never changes a field tablet. */
internal class RunningIntercom : java.io.Closeable {
    val context: Context = ApplicationProvider.getApplicationContext()
    val scenario: androidx.test.core.app.ActivityScenario<MainActivity>
    lateinit var service: IntercomService
        private set
    val owner get() = service.connection
    private var bound = false
    private val binding = object : android.content.ServiceConnection {
        val connected = java.util.concurrent.CountDownLatch(1)
        override fun onServiceConnected(name: android.content.ComponentName, binder: android.os.IBinder) {
            service = (binder as IntercomService.LocalBinder).service
            connected.countDown()
        }
        override fun onServiceDisconnected(name: android.content.ComponentName) = Unit
    }
    init {
        org.junit.Assume.assumeTrue(android.os.Build.HARDWARE == "ranchu")
        shell("input keyevent KEYCODE_WAKEUP")
        shell("wm dismiss-keyguard")
        scenario = androidx.test.core.app.ActivityScenario.launch(MainActivity::class.java)
        bound = context.bindService(android.content.Intent(context, IntercomService::class.java), binding, Context.BIND_AUTO_CREATE)
        check(bound && binding.connected.await(5, java.util.concurrent.TimeUnit.SECONDS))
        val ready = java.util.concurrent.CountDownLatch(1)
        val sub = owner.observe(object : IntercomObserver {
            override fun onState(state: IntercomState) { if (state.link == LinkStatus.SAMPLE) ready.countDown() }
        })
        try { check(ready.await(5, java.util.concurrent.TimeUnit.SECONDS)) } finally { sub.cancel() }
    }
    fun unbind() { if (bound) context.unbindService(binding); bound = false }
    fun shell(command: String) {
        val automation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation
        android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).use { it.readBytes() }
    }
    override fun close() {
        scenario.close()
        unbind()
        context.stopService(android.content.Intent(context, IntercomService::class.java))
    }
}
