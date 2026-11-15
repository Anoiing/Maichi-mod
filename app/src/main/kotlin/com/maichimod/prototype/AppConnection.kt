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
import com.maichimod.prototype.session.UnlockResult
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
import com.maichimod.prototype.transport.EthernetState
import com.maichimod.prototype.transport.EthernetTransport
import com.maichimod.prototype.transport.FixtureTransport
import com.maichimod.prototype.transport.ReceivedDatagram
import com.maichimod.prototype.transport.WiredLink
import java.io.Closeable
import java.io.IOException
import java.net.Inet4Address
import java.util.UUID
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

internal enum class LinkStatus {
    SAMPLE, SAMPLE_MISSING, WIRED_WAITING, WIRED_ADDRESS_PENDING, WIRED_NEEDS_IDS, WIRED_PROFILE_MISMATCH,
    WIRED_CONNECTED, WIRED_RECONNECTING, WIRED_ERROR, RECEPTION_BLOCKED
}

internal data class CallKey(val runId: String, val sequence: Int)

internal enum class CallEndReason { LOCAL, REMOTE, TIMED_OUT, CONNECTION_LOST }
internal enum class VideoHealth { INACTIVE, WAITING, MISSING, AVAILABLE, STALLED }

internal data class IntercomState(
    val options: ConnectionOptions = ConnectionOptions(),
    val enabled: Boolean = true,
    val link: LinkStatus? = null,
    val ethernet: WiredLink? = null,
    val terminal: TerminalState = TerminalState(),
    val call: CallStatus = CallStatus.IDLE,
    val callEnding: Boolean = false,
    val callKey: CallKey? = null,
    val issue: String? = null,
    val configurationLoaded: Boolean = false,
    val configurationError: Boolean = false,
    val notificationsAllowed: Boolean = true,
    val fullScreenAllowed: Boolean = true,
    val ringerMuted: Boolean = false,
    val microphone: AudioHealth = AudioHealth.INACTIVE,
    val playback: AudioHealth = AudioHealth.INACTIVE,
    val unlockPending: Boolean = false,
    val unlockResult: UnlockResult? = null,
    val unlockReadyAtMs: Long = 0,
    val video: VideoHealth = VideoHealth.INACTIVE,
    val callEndReason: CallEndReason? = null,
    val callEndedAtMs: Long? = null,
    val retryAttempt: Int = 0,
    val retryAtMs: Long? = null,
    val externalPower: Boolean? = null,

) {
    val ready: Boolean get() = enabled && (link == LinkStatus.WIRED_CONNECTED || link == LinkStatus.SAMPLE)
    val inCall: Boolean get() = call == CallStatus.RINGING || call == CallStatus.ANSWERED
    val canMonitor: Boolean get() = ready && !inCall
    val canCallElevator: Boolean get() = ready && terminal.elevator != ElevatorStatus.DISCOVERING && terminal.elevator != ElevatorStatus.WAITING
    fun endSummaryRemainingMs(now: Long): Long = if (callEndReason == null || callEndReason == CallEndReason.LOCAL || callEndedAtMs == null) 0
        else (callEndedAtMs + 3000 - now).coerceAtLeast(0)
}

internal interface IntercomObserver {
    fun onState(state: IntercomState)
    fun onMonitorFrame(frame: Bitmap) {}
    fun onCallFrame(frame: Bitmap) {}
}

internal class AppConnection(
    context: Context,
    private val openEthernet: (WiredLink, (ReceivedDatagram) -> Unit, (IOException) -> Unit) -> DatagramTransport =
        ::EthernetTransport,
    private val openLinks: (Context, Handler, Inet4Address?, (EthernetState) -> Unit) -> Closeable = ::EthernetLinks,
    private val createScheduler: (Handler) -> SessionScheduler = { handler -> object : SessionScheduler {
        override val nowMs: Long get() = SystemClock.elapsedRealtime()
        override fun schedule(delayMs: Long, action: () -> Unit): Cancellation {
            val callback = Runnable(action)
            handler.postDelayed(callback, delayMs)
            return Cancellation { handler.removeCallbacks(callback) }
        }
    } },
) : Closeable {
    private data class PendingFrame(val token: Int, val socketToken: Int, val monitorToken: Int, val bitmap: Bitmap)

    private val app = context.applicationContext
    private val settings = AppSettings(app)
    private val runId = UUID.randomUUID().toString()
    private val observers = CopyOnWriteArraySet<IntercomObserver>()
    private val closed = AtomicBoolean()
    @Volatile var currentState = IntercomState(enabled = false)
        private set
    private var running = false

    fun observe(observer: IntercomObserver): Cancellation {
        checkDeadlines()
        observers.add(observer)
        val initial = {
            if (observer in observers) {
                observer.onState(currentState)
                if (currentState.inCall) lastCallFrame?.let(observer::onCallFrame)
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) initial() else main.post(initial)
        return Cancellation { observers.remove(observer) }
    }

    private fun updateState(updated: IntercomState) {
        currentState = updated
        observers.forEach { if (it in observers) it.onState(updated) }
    }
    fun reportIssue(message: String) { main.post { if (!closed.get()) updateState(currentState.copy(issue = message)) } }
    private fun freshState(options: ConnectionOptions = activeOptions, enabled: Boolean = true,
        loaded: Boolean = false, configurationError: Boolean = false, issue: String? = null) = IntercomState(
        options = options, enabled = enabled, configurationLoaded = loaded, configurationError = configurationError, issue = issue,
        notificationsAllowed = currentState.notificationsAllowed, fullScreenAllowed = currentState.fullScreenAllowed,
        ringerMuted = currentState.ringerMuted, externalPower = currentState.externalPower)

    fun updateExternalPower(connected: Boolean) {
        main.post { if (!closed.get() && currentState.externalPower != connected) updateState(currentState.copy(externalPower = connected)) }
    }

    fun checkDeadlines(onChecked: (() -> Unit)? = null) {
        worker.post {
            if (closed.get()) return@post
            session?.checkDeadlines()
            calls?.checkDeadlines()
            watchCallVideo(generation.get(), socketGeneration.get(), callGeneration.get())
            if (onChecked != null) main.post { if (!closed.get()) onChecked() }
        }
    }

    fun updatePresentationCapabilities(notifications: Boolean, fullScreen: Boolean, muted: Boolean) {
        main.post {
            if (!closed.get() && (currentState.notificationsAllowed != notifications || currentState.fullScreenAllowed != fullScreen || currentState.ringerMuted != muted)) {
                updateState(currentState.copy(notificationsAllowed = notifications, fullScreenAllowed = fullScreen, ringerMuted = muted))
            }
        }
    }

    fun resetConnectionOptions(onSaved: (Boolean) -> Unit) {
        val queued = worker.post {
            val saved = synchronized(closed) { !closed.get() && currentState.configurationError && settings.resetConnectionOptions() }
            if (saved) restoreSavedOptions()
            main.post { onSaved(saved) }
        }
        if (!queued) main.post { onSaved(false) }
    }

    private fun onLink(status: LinkStatus, link: WiredLink?) =
        updateState(currentState.copy(options = activeOptions, enabled = true, configurationLoaded = true, configurationError = false, link = status, ethernet = link))
    private fun onState(state: TerminalState) = updateState(currentState.copy(terminal = state))
    private fun onCall(status: CallStatus, token: Int, endReason: CallEndReason? = null) {
        val key = CallKey(runId, token)
        val changed = currentState.call != status || currentState.callKey != key
        val newCall = status == CallStatus.RINGING && currentState.callKey != key
        val active = status == CallStatus.RINGING || status == CallStatus.ANSWERED
        if (!active || newCall) lastCallFrame = null
        val ended = status == CallStatus.ENDED || status == CallStatus.TIMED_OUT || status == CallStatus.DISCONNECTED
        val reason = when (status) {
            CallStatus.ENDED -> endReason ?: CallEndReason.REMOTE
            CallStatus.TIMED_OUT -> CallEndReason.TIMED_OUT
            CallStatus.DISCONNECTED -> CallEndReason.CONNECTION_LOST
            else -> null
        }
        if (changed && !active) audio.stop()
        updateState(currentState.copy(call = status, callKey = key,
            callEnding = active && !newCall && currentState.callEnding,
            callEndReason = reason,
            callEndedAtMs = if (ended) { if (changed) SystemClock.elapsedRealtime() else currentState.callEndedAtMs } else null,
            video = if (newCall) VideoHealth.WAITING else if (!active) VideoHealth.INACTIVE else currentState.video,
            microphone = if (changed) AudioHealth.INACTIVE else currentState.microphone,
            playback = if (changed) AudioHealth.INACTIVE else currentState.playback,
            unlockPending = if (active && !newCall) currentState.unlockPending else false,
            unlockResult = if (newCall) null else currentState.unlockResult,
            unlockReadyAtMs = if (newCall) 0 else currentState.unlockReadyAtMs,
            issue = if (status == CallStatus.RINGING || (changed && status == CallStatus.ANSWERED)) null else currentState.issue))
        if (changed && !currentState.callEnding) when (status) {
            CallStatus.RINGING -> audio.startRinging()
            CallStatus.ANSWERED -> audio.start(activeOptions.wired && captureForCall == key) { sendCallAudio(it, key) }
            else -> Unit
        }
    }
    private fun onFrame(frame: Bitmap) { observers.forEach { if (it in observers) it.onMonitorFrame(frame) } }
    private var lastCallFrame: Bitmap? = null
    private fun onCallFrame(frame: Bitmap) {
        lastCallFrame = frame
        observers.forEach { if (it in observers) it.onCallFrame(frame) }
    }
    private val audio = CallAudio(app) { direction, health ->
        if (BuildConfig.DEBUG) Log.i(TAG, "Call audio $direction=$health")
        if (currentState.inCall) updateState(when (direction) {
            AudioDirection.CAPTURE -> currentState.copy(microphone = health)
            AudioDirection.PLAYBACK -> currentState.copy(playback = health)
            AudioDirection.RINGTONE -> currentState.copy(issue = when {
                health == AudioHealth.UNAVAILABLE -> app.getString(R.string.ringtone_unavailable)
                currentState.issue == app.getString(R.string.ringtone_unavailable) -> null
                else -> currentState.issue
            })
        })
    }

    private val thread = HandlerThread("Maichi-session").apply { start() }
    private val worker = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())
    private val generation = AtomicInteger()
    private val socketGeneration = AtomicInteger()
    private val monitorGeneration = AtomicInteger()
    private val callGeneration = AtomicInteger()
    @Volatile private var captureForCall: CallKey? = null
    private val unlockInFlight = AtomicBoolean()
    private val retryQueued = AtomicBoolean()
    private var lastUnlockAt: Long? = null
    private data class PendingCallFrame(val token: Int, val socket: Int, val call: Int, val bitmap: Bitmap)
    private val pendingCallFrame = AtomicReference<PendingCallFrame?>()
    private val renderCallFrame = Runnable {
        pendingCallFrame.getAndSet(null)?.let { frame ->
            if (isCurrent(frame.token, frame.socket) && frame.call == callGeneration.get() && currentState.inCall) onCallFrame(frame.bitmap)
        }
    }
    private val pendingFrame = AtomicReference<PendingFrame?>()
    private val render = Runnable {
        pendingFrame.getAndSet(null)?.let { (token, socketToken, monitorToken, bitmap) ->
            if (isCurrent(token, socketToken) && monitorToken == monitorGeneration.get()) onFrame(bitmap)
        }
    }
    private val scheduler = createScheduler(worker)

    private var links: Closeable? = null
    private var session: TerminalSession<Bitmap>? = null
    private var calls: IncomingCallSession<Bitmap>? = null
    private var wiredRetry: Cancellation? = null
    private var wiredStable: Cancellation? = null
    private var addressWait: Cancellation? = null
    private var wiredFailures = 0
    private var videoWatch: Cancellation? = null
    private var videoStartedAt = 0L
    private var lastVideoAt: Long? = null
    private var videoStalled = false
    private var activeMonitorToken = 0
    private var activeOptions = ConnectionOptions()
    private var activeLink: WiredLink? = null
    private var replayTransport: FixtureTransport? = null
    private var monitorSamples = emptyMap<MonitorSource, List<CaptureRow>>()

    fun start(options: ConnectionOptions) {
        worker.post {
            if (closed.get()) return@post
            applyOptions(options)
        }
    }

    private fun applyOptions(options: ConnectionOptions) {
        if (running && !options.requiresReconnect(activeOptions)) {
            activeOptions = options
            val token = generation.get(); val socket = socketGeneration.get()
            publish(token, socket) { updateState(currentState.copy(options = options, configurationLoaded = true)) }
        } else restart(options)
    }

    fun restore() {
        worker.post { if (!closed.get()) restoreSavedOptions() }
    }

    fun failReceptionStart(message: String) {
        worker.post {
            if (closed.get()) return@post
            val token = generation.incrementAndGet()
            release(); running = false
            try {
                val options = settings.load()
                val enabled = settings.isReceptionEnabled()
                main.post { if (token == generation.get()) {
                    audio.stop()
                    updateState(freshState(options, enabled, loaded = true,
                        issue = if (enabled) message else null).copy(link = if (enabled) LinkStatus.RECEPTION_BLOCKED else null))
                } }
            } catch (_: IllegalStateException) {
                main.post { if (token == generation.get()) updateState(freshState(enabled = false, loaded = true,
                    configurationError = true, issue = app.getString(R.string.settings_read_failed))) }
            }
        }
    }

    private fun restoreSavedOptions() {
        try {
            val options = settings.load()
            if (settings.isReceptionEnabled()) applyOptions(options)
            else {
                val token = generation.incrementAndGet()
                release(); running = false; activeOptions = options
                main.post { if (token == generation.get()) {
                    audio.stop(); lastCallFrame = null
                    updateState(freshState(options = options, enabled = false, loaded = true))
                } }
            }
        } catch (_: IllegalStateException) {
            val token = generation.incrementAndGet()
            release(); running = false
            main.post { if (token == generation.get()) {
                audio.stop(); lastCallFrame = null
                updateState(freshState(enabled = false, loaded = true, configurationError = true,
                    issue = app.getString(R.string.settings_read_failed)))
            } }
        }
    }

    fun setReceptionEnabled(enabled: Boolean, onSaved: (Boolean) -> Unit) {
        val queued = worker.post {
            val saved = synchronized(closed) { !closed.get() && settings.setReceptionEnabled(enabled) }
            if (saved) restoreSavedOptions()
            main.post { onSaved(saved) }
        }
        if (!queued) main.post { onSaved(false) }
    }

    fun retry() {
        if (!retryQueued.compareAndSet(false, true)) return
        if (!worker.post {
            if (!closed.get() && running && calls?.status != CallStatus.RINGING && calls?.status != CallStatus.ANSWERED) {
                restart(activeOptions)
            }
            main.post { retryQueued.set(false) }
        }) retryQueued.set(false)
    }

    private fun restart(options: ConnectionOptions) {
        val token = generation.incrementAndGet()
        release()
        running = true
        activeOptions = options
        wiredFailures = 0
        val socketToken = socketGeneration.get()
        publish(token, socketToken) { updateState(freshState(options = options)) }
        if (options.wired) startWired(options, token) else startReplay(options, token)
    }

    fun updateOptions(updated: ConnectionOptions, expected: ConnectionOptions = currentState.options, onSaved: (Boolean) -> Unit) {
        val queued = worker.post {
            if (closed.get()) {
                main.post { onSaved(false) }
                return@post
            }
            val reconnect = updated.requiresReconnect(activeOptions)
            val inCall = calls?.status == CallStatus.RINGING || calls?.status == CallStatus.ANSWERED
            val saved = synchronized(closed) {
                !closed.get() && expected == activeOptions && !(inCall && reconnect) && settings.save(updated)
            }
            if (saved) {
                activeOptions = updated
                if (running && reconnect) restart(updated)
                else {
                    val token = generation.get(); val socketToken = socketGeneration.get()
                    publish(token, socketToken) { updateState(currentState.copy(options = updated)) }
                }
            }
            main.post { onSaved(saved) }
        }
        if (!queued) main.post { onSaved(false) }
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

    fun answerCall(key: CallKey, capture: Boolean = false) = withCall(key) {
        if (status != CallStatus.RINGING) return@withCall
        captureForCall = if (capture) key else null
        answer()
    }
    fun retryCallAudio(key: CallKey, capture: Boolean) = withCall(key) {
        if (status != CallStatus.ANSWERED) return@withCall
        captureForCall = if (capture) key else null
        val token = generation.get(); val socket = socketGeneration.get()
        publish(token, socket) {
            if (currentState.callKey == key && currentState.call == CallStatus.ANSWERED && !currentState.callEnding) {
                audio.start(activeOptions.wired && captureForCall == key) { sendCallAudio(it, key) }
            }
        }
    }
    fun hangUpCall(key: CallKey) {
        val markEnding = {
            if (isCurrentCall(key) && currentState.callKey == key && currentState.inCall) {
                audio.stop()
                updateState(currentState.copy(callEnding = true))
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) markEnding() else main.post(markEnding)
        withCall(key, allowEnding = true) { hangUp() }
    }

    fun unlockCall(key: CallKey, autoHangUp: Boolean, onResult: (UnlockResult) -> Unit) {
        val requestedAt = SystemClock.elapsedRealtime()
        if (closed.get() || currentState.callEnding || !isCurrentCall(key) || !unlockInFlight.compareAndSet(false, true)) {
            main.post { onResult(UnlockResult.REJECTED) }
            return
        }
        main.post { if (currentState.callKey == key && currentState.inCall) updateState(currentState.copy(unlockPending = true)) }
        val waiting = Runnable {
            if (currentState.callKey == key && currentState.unlockPending) updateState(currentState.copy(issue = app.getString(R.string.unlock_waiting)))
        }
        main.postDelayed(waiting, 1000)
        val token = generation.get(); val socket = socketGeneration.get()
        val queued = worker.post {
            calls?.checkDeadlines()
            val now = SystemClock.elapsedRealtime()
            val result = if (!isCurrent(token, socket) || !isCurrentCall(key) || closed.get() ||
                currentState.callEnding || calls?.status !in listOf(CallStatus.RINGING, CallStatus.ANSWERED) || now - requestedAt >= 1000 ||
                lastUnlockAt?.let { now - it < 1500 } == true) {
                UnlockResult.REJECTED
            } else {
                lastUnlockAt = now
                calls?.unlock(autoHangUp) ?: UnlockResult.REJECTED
            }
            val readyAt = (lastUnlockAt ?: 0) + 1500
            main.post {
                main.removeCallbacks(waiting)
                unlockInFlight.set(false)
                if (result == UnlockResult.UNCONFIRMED && currentState.callKey == key) onCall(CallStatus.DISCONNECTED, key.sequence)
                if (currentState.callKey == key) updateState(currentState.copy(unlockPending = false, unlockResult = result,
                    unlockReadyAtMs = readyAt,
                    issue = when {
                        result == UnlockResult.UNCONFIRMED -> app.getString(R.string.unlock_unconfirmed)
                        currentState.issue == app.getString(R.string.unlock_waiting) -> null
                        else -> currentState.issue
                    }))
                onResult(result)
            }
        }
        if (!queued) {
            unlockInFlight.set(false)
            main.removeCallbacks(waiting)
            main.post {
                if (currentState.callKey == key) updateState(currentState.copy(unlockPending = false))
                onResult(UnlockResult.REJECTED)
            }
        }
    }
    fun sendCallAudio(pcm: ByteArray, key: CallKey) = withCall(key) { sendAudio(pcm) }

    fun simulateIncoming(delayMs: Long = 0) {
        require(delayMs in 0L..30_000L)
        val token = generation.get()
        worker.postDelayed({ if (token == generation.get()) replayTransport?.simulateIncoming() }, delayMs)
    }

    private fun isCurrentCall(key: CallKey): Boolean = key.runId == runId && key.sequence == callGeneration.get()

    private fun withCall(key: CallKey, allowEnding: Boolean = false, action: IncomingCallSession<Bitmap>.() -> Unit) {
        val token = generation.get(); val socket = socketGeneration.get()
        worker.post {
            calls?.checkDeadlines()
            if (!closed.get() && (allowEnding || !currentState.callEnding) && isCurrent(token, socket) && isCurrentCall(key) &&
                calls?.status in listOf(CallStatus.RINGING, CallStatus.ANSWERED)) calls?.action()
        }
    }

    fun stop() {
        val token = generation.incrementAndGet()
        main.post { if (token == generation.get()) audio.stop() }
        monitorGeneration.incrementAndGet()
        pendingFrame.set(null)
        main.removeCallbacks(render)
        worker.post {
            release()
            running = false
            main.post { if (token == generation.get()) updateState(currentState.copy(enabled = false, link = null, ethernet = null,
                terminal = TerminalState(), call = CallStatus.IDLE, callKey = null)) }
        }
    }

    override fun close() {
        if (!synchronized(closed) { closed.compareAndSet(false, true) }) return
        stop()
        observers.clear()
        main.post { audio.stop(); lastCallFrame = null }
        thread.quitSafely()
    }

    private fun withSession(action: TerminalSession<Bitmap>.() -> Unit) {
        val token = generation.get()
        val socketToken = socketGeneration.get()
        worker.post { if (isCurrent(token, socketToken)) session?.action() }
    }

    private fun startReplay(options: ConnectionOptions, token: Int) {
        val loadStartedAt = SystemClock.elapsedRealtime()
        monitorSamples = MonitorSource.entries.associateWith { readSample(it.fixture) }
        val monitor = monitorSamples[MonitorSource.UNIT].orEmpty()
        val elevatorReply = if (options.simulateTimeout) null else readSample(CaptureFixtures.ELEVATOR_RX)
            .firstOrNull { it.incoming && it.localPort == MaichiPackets.SESSION_PORT }
        val socketToken = socketGeneration.get()
        Log.i(TAG, "Replay resources loaded in ${SystemClock.elapsedRealtime() - loadStartedAt}ms")
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
            links = openLinks(app, worker, options.preferredLocalIp) { network ->
                if (token == generation.get()) selectWiredLink(options, token, network)
            }
        } catch (failure: RuntimeException) {
            Log.w(TAG, "Unable to observe Ethernet", failure)
            publish(token, socketToken) { onLink(LinkStatus.WIRED_ERROR, null) }
        }
    }

    private fun selectWiredLink(options: ConnectionOptions, token: Int, network: EthernetState) {
        val link = network.link
        val socketToken = retireSession()
        activeLink = link
        when {
            !options.hasTerminalIds -> publishWiredStatus(token, socketToken, LinkStatus.WIRED_NEEDS_IDS, link)
            link == null -> {
                val status = if (network.present) LinkStatus.WIRED_ADDRESS_PENDING else LinkStatus.WIRED_WAITING
                publishWiredStatus(token, socketToken, status, null)
                if (network.present) addressWait = scheduler.schedule(10_000) {
                    publish(token, socketToken) { if (currentState.link == LinkStatus.WIRED_ADDRESS_PENDING) updateState(currentState.copy(issue = app.getString(R.string.wired_address_delayed))) }
                }
            }
            !options.matchesEthernet(link.address, link.prefixLength) ->
                publishWiredStatus(token, socketToken, LinkStatus.WIRED_PROFILE_MISMATCH, link)
            else -> bindWiredSession(options, link, token, socketToken)
        }
    }

    private fun bindWiredSession(
        options: ConnectionOptions,
        link: WiredLink,
        token: Int,
        socketToken: Int,
    ) {
        publishWiredStatus(token, socketToken, LinkStatus.WIRED_RECONNECTING, link)
        val onTransportFailure: (Exception) -> Unit = { failure ->
            recoverWiredSession(options, link, token, socketToken, failure)
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
            installSession(profile, transport, token, socketToken, onTransportFailure) {
                wiredFailures = 0
                wiredStable?.cancel(); wiredStable = null
            }
            wiredStable = scheduler.schedule(60_000) {
                if (isCurrent(token, socketToken)) { wiredFailures = 0; wiredStable = null }
            }
            publishWiredStatus(token, socketToken, LinkStatus.WIRED_CONNECTED, link)
        } catch (failure: Exception) {
            if (failure !is IOException && failure !is SecurityException && failure !is IllegalArgumentException) throw failure
            recoverWiredSession(options, link, token, socketToken, failure)
        }
    }

    private fun recoverWiredSession(
        options: ConnectionOptions,
        link: WiredLink,
        token: Int,
        socketToken: Int,
        failure: Exception,
    ) {
        if (!isCurrent(token, socketToken)) return
        Log.w(TAG, "Wired UDP connection failed", failure)
        val retryToken = retireSession()
        if (failure !is IOException) {
            publishWiredStatus(token, retryToken, LinkStatus.WIRED_ERROR, link)
        } else {
            val delay = WIRED_RETRY_DELAYS[wiredFailures.coerceAtMost(WIRED_RETRY_DELAYS.lastIndex)]
            wiredFailures = (wiredFailures + 1).coerceAtMost(WIRED_RETRY_DELAYS.size)
            val attempt = wiredFailures
            val retryAt = scheduler.nowMs + delay
            publishWiredStatus(token, retryToken, LinkStatus.WIRED_RECONNECTING, link, attempt, retryAt)
            wiredRetry = scheduler.schedule(delay) {
                if (isCurrent(token, retryToken)) {
                    wiredRetry = null
                    bindWiredSession(options, link, token, socketGeneration.incrementAndGet())
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
        // Debug-only metadata distinguishes capture silence from a failed uplink; never retains PCM.
        var activeAudioSummary = false
        var sentAudioBlocks = 0L
        var receivedAudioBlocks = 0L
        var nonSilentAudioBlocks = 0L
        var audioPeak = 0
        var audioEnergy = 0.0
        val logged = object : DatagramTransport {
            override fun send(localPort: Int, destination: Inet4Address, payload: ByteArray) {
                transport.send(localPort, destination, payload)
                if (BuildConfig.DEBUG && activeAudioSummary && localPort == MaichiPackets.SESSION_PORT &&
                    payload.size == 602 && payload[8] == 0xb7.toByte() && payload[10].toInt() == 10 && payload[80].toInt() == 3
                ) {
                    sentAudioBlocks++
                    var peak = 0
                    for (offset in 90 until payload.size step 2) {
                        val sample = ((payload[offset].toInt() and 0xff) or (payload[offset + 1].toInt() shl 8)).toShort().toInt()
                        peak = maxOf(peak, kotlin.math.abs(sample))
                        audioEnergy += sample.toDouble() * sample
                    }
                    audioPeak = maxOf(audioPeak, peak)
                    if (peak != 0) nonSilentAudioBlocks++
                }
                logDatagram("TX", localPort, payload)
            }

            override fun close() = transport.close()
        }
        val observer = object : TerminalObserver<Bitmap> {
            override fun onPeerActivity() = onOperational()
            override fun onStateChanged(state: TerminalState) {
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
        // Incoming calls currently use the captured unit-door profile. Basement 1 routing stays
        // pending its own call capture; see docs/protocol/basement-one-incoming-call-pending.md.
        calls = IncomingCallSession(profile, logged, sessionScheduler, object : CallObserver<Bitmap> {
            override fun onPeerActivity() = onOperational()
            override fun onStateChanged(status: CallStatus) {
                if (status == CallStatus.RINGING) {
                    if (BuildConfig.DEBUG) {
                        activeAudioSummary = true
                        sentAudioBlocks = 0; receivedAudioBlocks = 0; nonSilentAudioBlocks = 0
                        audioPeak = 0; audioEnergy = 0.0
                    }
                    callGeneration.incrementAndGet()
                    videoStartedAt = scheduler.nowMs; lastVideoAt = null; videoStalled = false
                    lastUnlockAt = null
                    captureForCall = null
                    session?.closeMonitor()
                }
                if (BuildConfig.DEBUG && activeAudioSummary && status != CallStatus.RINGING && status != CallStatus.ANSWERED) {
                    val rms = if (sentAudioBlocks == 0L) 0.0 else kotlin.math.sqrt(audioEnergy / (sentAudioBlocks * 256.0))
                    Log.i(TAG, "Call audio summary sent=$sentAudioBlocks received=$receivedAudioBlocks non_silent=$nonSilentAudioBlocks peak=$audioPeak rms=${rms.toInt()}")
                    activeAudioSummary = false
                }
                val callToken = callGeneration.get()
                if (status == CallStatus.RINGING) watchCallVideo(token, socketToken, callToken)
                else if (status != CallStatus.ANSWERED) { videoWatch?.cancel(); videoWatch = null }
                val reason = if (status == CallStatus.ENDED && calls?.endedLocally == true) CallEndReason.LOCAL else null
                publish(token, socketToken) { if (callToken == callGeneration.get()) onCall(status, callToken, reason) }
            }
            override fun decodeJpeg(jpeg: ByteArray): Bitmap? = observer.decodeJpeg(jpeg)
            override fun onFrame(frame: Bitmap) {
                val callToken = callGeneration.get()
                val firstOrRestored = lastVideoAt == null || videoStalled
                lastVideoAt = scheduler.nowMs
                if (firstOrRestored) {
                    videoStalled = false
                    watchCallVideo(token, socketToken, callToken)
                    publish(token, socketToken) { if (currentState.callKey?.sequence == callToken && currentState.inCall) updateState(currentState.copy(video = VideoHealth.AVAILABLE)) }
                }
                pendingCallFrame.set(PendingCallFrame(token, socketToken, callToken, frame))
                main.removeCallbacks(renderCallFrame)
                main.post(renderCallFrame)
            }
            override fun onAudio(pcm: ByteArray) {
                if (BuildConfig.DEBUG && activeAudioSummary) receivedAudioBlocks++
                val callToken = callGeneration.get()
                publish(token, socketToken) { if (callToken == callGeneration.get()) audio.enqueue(pcm) }
            }
            override fun onTransportFailure(failure: Exception) = handleTransportFailure(failure)
        })
    }

    private fun watchCallVideo(token: Int, socket: Int, call: Int) {
        videoWatch?.cancel()
        if (!isCurrent(token, socket) || call != callGeneration.get() || calls?.status !in listOf(CallStatus.RINGING, CallStatus.ANSWERED)) return
        val remaining = lastVideoAt?.let { 3000 - (scheduler.nowMs - it) } ?: (5000 - (scheduler.nowMs - videoStartedAt))
        if (remaining <= 0) {
            videoStalled = true
            val health = if (lastVideoAt == null) VideoHealth.MISSING else VideoHealth.STALLED
            publish(token, socket) { if (currentState.callKey?.sequence == call && currentState.inCall) updateState(currentState.copy(video = health)) }
        } else videoWatch = scheduler.schedule(remaining) { watchCallVideo(token, socket, call) }
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

    private fun publishWiredStatus(token: Int, socketToken: Int, status: LinkStatus, link: WiredLink?,
        attempt: Int = 0, retryAt: Long? = null) {
        publish(token, socketToken) {
            onState(TerminalState())
            if (currentState.inCall) onCall(CallStatus.DISCONNECTED, currentState.callKey?.sequence ?: callGeneration.get())
            else if (currentState.call == CallStatus.IDLE) onCall(CallStatus.IDLE, callGeneration.get())
            onLink(status, link)
            updateState(currentState.copy(retryAttempt = attempt, retryAtMs = retryAt,
                issue = if (currentState.issue == app.getString(R.string.wired_address_delayed)) null else currentState.issue))
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
        wiredStable?.cancel(); wiredStable = null
        addressWait?.cancel(); addressWait = null
        videoWatch?.cancel(); videoWatch = null
        lastVideoAt = null
        pendingFrame.set(null)
        pendingCallFrame.set(null)
        main.removeCallbacks(render)
        main.removeCallbacks(renderCallFrame)
        captureForCall = null
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
        private val WIRED_RETRY_DELAYS = longArrayOf(500, 1000, 2000, 5000, 10000, 30000)
    }
}
