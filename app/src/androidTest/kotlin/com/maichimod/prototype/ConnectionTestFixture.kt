package com.maichimod.prototype

import android.content.Context
import android.content.ContextWrapper
import android.net.Network
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.maichimod.prototype.protocol.*
import com.maichimod.prototype.session.CallStatus
import com.maichimod.prototype.transport.*
import java.io.Closeable
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import com.maichimod.prototype.transport.WiredLink
import java.net.Inet4Address
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
        if (android.os.Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(context.packageName, android.Manifest.permission.POST_NOTIFICATIONS)
        }
        AppSettings(context).load()
        check(AppSettings(context).setReceptionEnabled(true))
        shell("input keyevent KEYCODE_WAKEUP")
        shell("wm dismiss-keyguard")
        scenario = androidx.test.core.app.ActivityScenario.launch(MainActivity::class.java)
        bound = context.bindService(android.content.Intent(context, IntercomService::class.java), binding, Context.BIND_AUTO_CREATE)
        check(bound && binding.connected.await(5, java.util.concurrent.TimeUnit.SECONDS))
        val ready = java.util.concurrent.CountDownLatch(1)
        val sub = owner.observe(object : IntercomObserver {
            override fun onState(state: IntercomState) { if (state.link == LinkStatus.SAMPLE) ready.countDown() }
        })
        try {
            check(ready.await(20, java.util.concurrent.TimeUnit.SECONDS)) { "Replay not ready: enabled=${owner.currentState.enabled} link=${owner.currentState.link} call=${owner.currentState.call} issue=${owner.currentState.issue}" }
        } catch (failure: Throwable) {
            close()
            throw failure
        } finally { sub.cancel() }
    }
    fun unbind() { if (bound) context.unbindService(binding); bound = false }
    fun shell(command: String) {
        val automation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation
        android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).use { it.readBytes() }
    }
    override fun close() {
        if (::service.isInitialized && owner.currentState.inCall) {
            val ended = java.util.concurrent.CountDownLatch(1)
            val sub = owner.observe(object : IntercomObserver {
                override fun onState(state: IntercomState) { if (!state.inCall) ended.countDown() }
            })
            owner.currentState.callKey?.let(owner::hangUpCall)
            ended.await(2, java.util.concurrent.TimeUnit.SECONDS)
            sub.cancel()
        }
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val monitor = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
            listOf(androidx.test.runner.lifecycle.Stage.RESUMED, androidx.test.runner.lifecycle.Stage.STARTED,
                androidx.test.runner.lifecycle.Stage.STOPPED, androidx.test.runner.lifecycle.Stage.PAUSED,
                androidx.test.runner.lifecycle.Stage.CREATED).flatMap { monitor.getActivitiesInStage(it) }.distinct()
                .filterIsInstance<IncomingCallActivity>().forEach { if (!it.isFinishing) it.finishAndRemoveTask() }
        }
        scenario.close()
        unbind()
        context.stopService(android.content.Intent(context, IntercomService::class.java))
    }
}

internal class WiredIntercomFixture : Closeable {
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
    }, openLinks = { _, _, _, changed -> changed(com.maichimod.prototype.transport.EthernetState(testLink())); Closeable {} })
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

internal fun awaitIncomingCall(allowExisting: Boolean = false, action: () -> Unit = {}): IncomingCallActivity {
    val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
    val resumed = CountDownLatch(1)
    var call: IncomingCallActivity? = null
    val monitor = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
    val callback = androidx.test.runner.lifecycle.ActivityLifecycleCallback { activity, stage ->
        if (activity is IncomingCallActivity && stage == androidx.test.runner.lifecycle.Stage.RESUMED) { call = activity; resumed.countDown() }
    }
    instrumentation.runOnMainSync {
        monitor.addLifecycleCallback(callback)
        if (allowExisting) monitor.getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED)
            .filterIsInstance<IncomingCallActivity>().firstOrNull()?.let { call = it; resumed.countDown() }
    }
    try {
        action()
        assertTrue("call activity did not appear", resumed.await(6, TimeUnit.SECONDS))
        return requireNotNull(call)
    } finally { instrumentation.runOnMainSync { monitor.removeLifecycleCallback(callback) } }
}
