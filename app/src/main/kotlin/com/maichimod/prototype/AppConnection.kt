package com.maichimod.prototype

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.maichimod.prototype.protocol.DEMO_PROFILE
import com.maichimod.prototype.protocol.MaichiPackets
import com.maichimod.prototype.protocol.TerminalProfile
import com.maichimod.prototype.session.Cancellation
import com.maichimod.prototype.session.ElevatorStatus
import com.maichimod.prototype.session.MonitorStatus
import com.maichimod.prototype.session.SessionScheduler
import com.maichimod.prototype.session.TerminalObserver
import com.maichimod.prototype.session.TerminalSession
import com.maichimod.prototype.session.TerminalState
import com.maichimod.prototype.session.CallObserver
import com.maichimod.prototype.session.CallStatus
import com.maichimod.prototype.session.IncomingCallSession
import com.maichimod.prototype.transport.CaptureFixtures
import com.maichimod.prototype.transport.CaptureRow
import com.maichimod.prototype.transport.DatagramTransport
import com.maichimod.prototype.transport.EthernetLinks
import com.maichimod.prototype.transport.EthernetTransport
import com.maichimod.prototype.transport.FixtureTransport
import com.maichimod.prototype.transport.ReceivedDatagram
import com.maichimod.prototype.transport.WiredLink
import java.io.Closeable
import java.io.IOException
import java.net.Inet4Address
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

internal enum class LinkStatus {
    SAMPLE, SAMPLE_MISSING, WIRED_WAITING, WIRED_NEEDS_IDS, WIRED_PROFILE_MISMATCH,
    WIRED_CONNECTED, WIRED_RECONNECTING, WIRED_ERROR
}

internal class AppConnection @JvmOverloads constructor(
    context: Context,
    private val onLink: (LinkStatus, WiredLink?) -> Unit,
    private val onState: (TerminalState) -> Unit,
    private val onFrame: (Bitmap) -> Unit,
    private val openEthernet: (WiredLink, (ReceivedDatagram) -> Unit, (IOException) -> Unit) -> DatagramTransport =
        ::EthernetTransport,
    private val onCall: (CallStatus, Int) -> Unit = { _, _ -> },
    private val onCallFrame: (Bitmap) -> Unit = {},
    private val onCallAudio: (ByteArray) -> Unit = {},
) : Closeable {
    private data class PendingFrame(val token: Int, val socketToken: Int, val monitorToken: Int, val bitmap: Bitmap)

    private val app = context.applicationContext
    private val thread = HandlerThread("Maichi-session").apply { start() }
    private val worker = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())
    private val generation = AtomicInteger()
    private val socketGeneration = AtomicInteger()
    private val monitorGeneration = AtomicInteger()
    private val callGeneration = AtomicInteger()
    val callToken: Int get() = callGeneration.get()
    private val pendingFrame = AtomicReference<PendingFrame?>()
    private val render = Runnable {
        pendingFrame.getAndSet(null)?.let { (token, socketToken, monitorToken, bitmap) ->
            if (isCurrent(token, socketToken) && monitorToken == monitorGeneration.get()) onFrame(bitmap)
        }
    }
    private val scheduler = object : SessionScheduler {
        override val nowMs: Long get() = SystemClock.uptimeMillis()

        override fun schedule(delayMs: Long, action: () -> Unit): Cancellation {
            val callback = Runnable(action)
            worker.postDelayed(callback, delayMs)
            return Cancellation { worker.removeCallbacks(callback) }
        }
    }

    private var links: EthernetLinks? = null
    private var session: TerminalSession<Bitmap>? = null
    private var calls: IncomingCallSession<Bitmap>? = null
    private var wiredRetry: Cancellation? = null
    private var activeMonitorToken = 0
    private var activeOptions = ConnectionOptions()
    private var activeLink: WiredLink? = null
    private var replayTransport: FixtureTransport? = null
    private var monitorSamples = emptyMap<MonitorSource, List<CaptureRow>>()

    fun start(options: ConnectionOptions) {
        val token = generation.incrementAndGet()
        worker.post {
            release()
            if (token != generation.get()) return@post
            activeOptions = options
            if (options.wired) startWired(options, token) else startReplay(options, token)
        }
    }

    @JvmOverloads
    fun openMonitor(source: MonitorSource = MonitorSource.UNIT) {
        val monitorToken = monitorGeneration.incrementAndGet()
        withSession {
            if (calls?.status == CallStatus.RINGING || calls?.status == CallStatus.ANSWERED) return@withSession
            activeMonitorToken = monitorToken
            if (activeOptions.wired) {
                val link = activeLink ?: return@withSession
                if (!activeOptions.hasMonitorIds(source)) return@withSession
                openMonitor(activeOptions.terminalProfile(link.address, link.prefixLength, source))
            } else {
                replayTransport?.selectMonitor(monitorSamples[source].orEmpty())
                openMonitor()
            }
        }
    }

    fun closeMonitor() {
        monitorGeneration.incrementAndGet()
        withSession { closeMonitor() }
    }
    fun callElevator() = withSession { callElevator() }

    fun answerCall(callToken: Int) = withCall(callToken) { answer() }
    fun hangUpCall(callToken: Int) {
        if (!callGeneration.compareAndSet(callToken, callToken + 1)) return
        withCall(callToken + 1) { hangUp() }
    }
    fun unlockCall(callToken: Int, autoHangUp: Boolean, onSent: (Boolean) -> Unit) = withCall(callToken) {
        val sent = unlock(autoHangUp)
        val token = generation.get(); val socketToken = socketGeneration.get()
        publish(token, socketToken) { if (callToken == callGeneration.get()) onSent(sent) }
    }
    fun sendCallAudio(pcm: ByteArray, callToken: Int) = withCall(callToken) { sendAudio(pcm) }
    fun simulateIncoming() { worker.post { replayTransport?.simulateIncoming() } }

    private fun withCall(callToken: Int = callGeneration.get(), action: IncomingCallSession<Bitmap>.() -> Unit) {
        val token = generation.get(); val socketToken = socketGeneration.get()
        worker.post {
            if (isCurrent(token, socketToken) && callToken == callGeneration.get()) calls?.action()
        }
    }

    fun stop() {
        generation.incrementAndGet()
        monitorGeneration.incrementAndGet()
        pendingFrame.set(null)
        main.removeCallbacks(render)
        worker.post { release() }
    }

    override fun close() {
        stop()
        thread.quitSafely()
    }

    private fun withSession(action: TerminalSession<Bitmap>.() -> Unit) {
        val token = generation.get()
        val socketToken = socketGeneration.get()
        worker.post { if (isCurrent(token, socketToken)) session?.action() }
    }

    private fun startReplay(options: ConnectionOptions, token: Int) {
        monitorSamples = MonitorSource.entries.associateWith { readSample(it.fixture) }
        val monitor = monitorSamples[MonitorSource.UNIT].orEmpty()
        val elevatorReply = if (options.simulateTimeout) null else readSample(CaptureFixtures.ELEVATOR_RX)
            .firstOrNull { it.incoming && it.localPort == MaichiPackets.SESSION_PORT }
        val socketToken = socketGeneration.get()
        if (!isCurrent(token, socketToken)) return
        val transport = FixtureTransport(scheduler, monitor, elevatorReply, readSample(CaptureFixtures.CALL_RX)) {
            if (isCurrent(token, socketToken)) receive(it)
        }
        replayTransport = transport
        installSession(DEMO_PROFILE, transport, token, socketToken)
        Log.i(TAG, "Offline replay monitor_datagrams=${monitor.size} elevator_reply=${elevatorReply != null}")
        publish(token, socketToken) {
            onCall(CallStatus.IDLE, callGeneration.get())
            onLink(if (monitor.isEmpty()) LinkStatus.SAMPLE_MISSING else LinkStatus.SAMPLE, null)
        }
    }

    private fun readSample(file: String): List<CaptureRow> {
        // AAPT expands .gz assets and strips the extension. Keep the gzip bytes under .tsvz.
        val asset = "${CaptureFixtures.DIRECTORY}/${file.removeSuffix(".gz")}z"
        return try {
            app.assets.open(asset).use(CaptureFixtures::read)
        } catch (failure: IOException) {
            Log.w(TAG, "Unable to read $asset", failure)
            emptyList()
        } catch (failure: IllegalArgumentException) {
            Log.w(TAG, "Invalid sample $asset", failure)
            emptyList()
        }
    }

    private fun startWired(options: ConnectionOptions, token: Int) {
        val socketToken = socketGeneration.get()
        publish(token, socketToken) {
            onLink(if (options.hasTerminalIds) LinkStatus.WIRED_WAITING else LinkStatus.WIRED_NEEDS_IDS, null)
        }
        try {
            links = EthernetLinks(app, worker, options.preferredLocalIp) { link ->
                if (token == generation.get()) selectWiredLink(options, token, link)
            }
        } catch (failure: RuntimeException) {
            Log.w(TAG, "Unable to observe Ethernet", failure)
            publish(token, socketToken) { onLink(LinkStatus.WIRED_ERROR, null) }
        }
    }

    private fun selectWiredLink(options: ConnectionOptions, token: Int, link: WiredLink?) {
        val socketToken = retireSession()
        activeLink = link
        when {
            !options.hasTerminalIds -> publishWiredStatus(token, socketToken, LinkStatus.WIRED_NEEDS_IDS, link)
            link == null -> publishWiredStatus(token, socketToken, LinkStatus.WIRED_WAITING, null)
            !options.matchesEthernet(link.address, link.prefixLength) ->
                publishWiredStatus(token, socketToken, LinkStatus.WIRED_PROFILE_MISMATCH, link)
            else -> bindWiredSession(options, link, token, socketToken, canRetry = true)
        }
    }

    private fun bindWiredSession(
        options: ConnectionOptions,
        link: WiredLink,
        token: Int,
        socketToken: Int,
        canRetry: Boolean,
    ) {
        var canRecover = canRetry
        val onTransportFailure: (Exception) -> Unit = { failure ->
            recoverWiredSession(options, link, token, socketToken, failure, canRecover)
        }
        try {
            val profile = options.terminalProfile(link.address, link.prefixLength)
            val transport = openEthernet(link, { datagram ->
                worker.post {
                    if (isCurrent(token, socketToken)) receive(datagram)
                }
            }, { failure ->
                worker.post { onTransportFailure(failure) }
            })
            installSession(profile, transport, token, socketToken, onTransportFailure) { canRecover = true }
            publishWiredStatus(token, socketToken, LinkStatus.WIRED_CONNECTED, link)
        } catch (failure: Exception) {
            if (failure !is IOException && failure !is SecurityException && failure !is IllegalArgumentException) throw failure
            recoverWiredSession(options, link, token, socketToken, failure, canRetry)
        }
    }

    private fun recoverWiredSession(
        options: ConnectionOptions,
        link: WiredLink,
        token: Int,
        socketToken: Int,
        failure: Exception,
        canRetry: Boolean,
    ) {
        if (!isCurrent(token, socketToken)) return
        Log.w(TAG, "Wired UDP connection failed", failure)
        val retryToken = retireSession()
        val status = if (canRetry) LinkStatus.WIRED_RECONNECTING else LinkStatus.WIRED_ERROR
        publishWiredStatus(token, retryToken, status, link)
        if (canRetry) {
            wiredRetry = scheduler.schedule(WIRED_RETRY_DELAY_MS) {
                if (isCurrent(token, retryToken)) {
                    wiredRetry = null
                    bindWiredSession(options, link, token, socketGeneration.incrementAndGet(), canRetry = false)
                }
            }
        }
    }

    private fun installSession(
        profile: TerminalProfile,
        transport: DatagramTransport,
        token: Int,
        socketToken: Int,
        handleTransportFailure: (Exception) -> Unit = {},
        onOperational: () -> Unit = {},
    ) {
        val logged = object : DatagramTransport {
            override fun send(localPort: Int, destination: Inet4Address, payload: ByteArray) {
                transport.send(localPort, destination, payload)
                logDatagram("TX", localPort, payload)
            }

            override fun close() = transport.close()
        }
        val observer = object : TerminalObserver<Bitmap> {
            override fun onStateChanged(state: TerminalState) {
                if (state.monitor == MonitorStatus.VIEWING || state.elevator == ElevatorStatus.RESPONDED) {
                    onOperational()
                }
                Log.i(TAG, "monitor=${state.monitor} elevator=${state.elevator}")
                val monitorToken = activeMonitorToken
                publish(token, socketToken) {
                    onState(if (monitorToken == monitorGeneration.get()) state else state.copy(monitor = MonitorStatus.IDLE))
                }
            }

            override fun decodeJpeg(jpeg: ByteArray): Bitmap? {
                val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
                return BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, options)
            }

            override fun onFrame(frame: Bitmap) {
                pendingFrame.set(PendingFrame(token, socketToken, activeMonitorToken, frame))
                main.removeCallbacks(render)
                main.post(render)
            }

            override fun onTransportFailure(failure: Exception) = handleTransportFailure(failure)

            override fun onStreamEnded(frames: Int, audioPackets: Int) {
                Log.i(TAG, "Monitor stream ended frames=$frames audio_packets=$audioPackets")
            }
        }
        val sessionScheduler = object : SessionScheduler {
            override val nowMs: Long get() = scheduler.nowMs

            override fun schedule(delayMs: Long, action: () -> Unit): Cancellation =
                scheduler.schedule(delayMs) { if (isCurrent(token, socketToken)) action() }
        }
        session = TerminalSession(profile, logged, sessionScheduler, observer)
        calls = IncomingCallSession(profile, logged, sessionScheduler, object : CallObserver<Bitmap> {
            override fun onStateChanged(status: CallStatus) {
                if (status == CallStatus.RINGING || status == CallStatus.ENDED ||
                    status == CallStatus.DISCONNECTED || status == CallStatus.TIMED_OUT
                ) callGeneration.incrementAndGet()
                if (status == CallStatus.RINGING) session?.closeMonitor()
                val callToken = callGeneration.get()
                publish(token, socketToken) { if (callToken == callGeneration.get()) onCall(status, callToken) }
            }
            override fun decodeJpeg(jpeg: ByteArray): Bitmap? = observer.decodeJpeg(jpeg)
            override fun onFrame(frame: Bitmap) {
                val callToken = callGeneration.get()
                publish(token, socketToken) { if (callToken == callGeneration.get()) onCallFrame(frame) }
            }
            override fun onAudio(pcm: ByteArray) {
                val callToken = callGeneration.get()
                publish(token, socketToken) { if (callToken == callGeneration.get()) onCallAudio(pcm) }
            }
            override fun onTransportFailure(failure: Exception) = handleTransportFailure(failure)
        })
    }

    private fun receive(datagram: ReceivedDatagram) {
        logDatagram("RX", datagram.localPort, datagram.payload)
        session?.receive(datagram)
        calls?.receive(datagram)
    }

    private fun isCurrent(token: Int, socketToken: Int): Boolean =
        token == generation.get() && socketToken == socketGeneration.get()

    private fun publish(token: Int, socketToken: Int, action: () -> Unit) {
        main.post { if (isCurrent(token, socketToken)) action() }
    }

    private fun publishWiredStatus(token: Int, socketToken: Int, status: LinkStatus, link: WiredLink?) {
        publish(token, socketToken) {
            onState(TerminalState())
            onCall(CallStatus.IDLE, callGeneration.get())
            onLink(status, link)
        }
    }

    private fun release() {
        links?.close()
        links = null
        retireSession(closeMonitor = true)
        activeLink = null
        replayTransport = null
        monitorSamples = emptyMap()
    }

    private fun retireSession(closeMonitor: Boolean = false): Int {
        val socketToken = socketGeneration.incrementAndGet()
        callGeneration.incrementAndGet()
        wiredRetry?.cancel()
        wiredRetry = null
        pendingFrame.set(null)
        main.removeCallbacks(render)
        if (closeMonitor) calls?.close() else calls?.disconnect()
        calls = null
        if (closeMonitor) session?.close() else session?.disconnect()
        session = null
        return socketToken
    }

    private fun logDatagram(direction: String, port: Int, payload: ByteArray) {
        val sessionPort = port == MaichiPackets.SESSION_PORT
        val type = if (sessionPort) payload.getOrNull(10) else payload.firstOrNull()
        if (sessionPort && type?.toInt() == 0x0a && payload.size > 90) return
        Log.d(TAG, "$direction UDP $port type=${type?.toInt()?.and(0xff)} bytes=${payload.size}")
    }

    companion object {
        private const val TAG = "Maichi"
        private const val WIRED_RETRY_DELAY_MS = 500L
    }
}
