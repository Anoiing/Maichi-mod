package com.maichimod.prototype.session

import com.maichimod.prototype.protocol.JpegAssembler
import com.maichimod.prototype.protocol.MaichiPacket
import com.maichimod.prototype.protocol.MaichiPackets
import com.maichimod.prototype.protocol.MediaFragment
import com.maichimod.prototype.protocol.MonitorCommand
import com.maichimod.prototype.protocol.MonitorIdentity
import com.maichimod.prototype.protocol.TerminalProfile
import com.maichimod.prototype.transport.DatagramTransport
import com.maichimod.prototype.transport.ReceivedDatagram
import java.io.Closeable
import java.io.IOException
import java.net.Inet4Address

internal fun interface Cancellation {
    fun cancel()
}

internal interface SessionScheduler {
    val nowMs: Long
    fun schedule(delayMs: Long, action: () -> Unit): Cancellation
}

internal enum class MonitorStatus { IDLE, DISCOVERING, CONNECTING, VIEWING, ENDED, BUSY, TIMED_OUT, DISCONNECTED }
internal enum class ElevatorStatus { IDLE, DISCOVERING, WAITING, RESPONDED, TIMED_OUT, DISCONNECTED }

internal data class TerminalState(
    val monitor: MonitorStatus = MonitorStatus.IDLE,
    val elevator: ElevatorStatus = ElevatorStatus.IDLE,
)

internal interface TerminalObserver<Frame : Any> {
    fun onStateChanged(state: TerminalState)
    fun decodeJpeg(jpeg: ByteArray): Frame?
    fun onFrame(frame: Frame)
    fun onPeerActivity() = Unit
    fun onStreamEnded(frames: Int, audioPackets: Int) = Unit
    fun onTransportFailure(failure: Exception) = Unit
}

// Calls and scheduler callbacks are serialized by the transport owner.
internal class TerminalSession<Frame : Any>(
    private val profile: TerminalProfile,
    private val transport: DatagramTransport,
    private val scheduler: SessionScheduler,
    private val observer: TerminalObserver<Frame>,
) : Closeable {
    var state = TerminalState()
        private set

    private val jpegAssembler = JpegAssembler()
    private var monitorPeer: Inet4Address? = null
    private var monitorProfile = profile
    private var elevatorPeer: Inet4Address? = null
    private var monitorTimeout: Cancellation? = null
    private var monitorLimit: Cancellation? = null
    private var monitorPeriodic: Cancellation? = null
    private var elevatorTimeout: Cancellation? = null
    private var lastFrameTime = 0L
    private var monitorDeadline: Long? = null
    private var monitorLimitAt: Long? = null
    private var elevatorDeadline: Long? = null
    private var videoFrames = 0
    private var audioPackets = 0
    private var closed = false

    fun openMonitor(selectedProfile: TerminalProfile = profile) {
        checkDeadlines()
        if (closed || state.monitor == MonitorStatus.DISCOVERING || monitorPeer != null) return
        monitorProfile = selectedProfile
        val discoveryPending = state.elevator == ElevatorStatus.DISCOVERING && monitorProfile.peerId == profile.peerId
        setMonitor(MonitorStatus.DISCOVERING)
        monitorDeadline = scheduler.nowMs + DISCOVERY_TIMEOUT_MS
        monitorLimitAt = scheduler.nowMs + MONITOR_LIMIT_MS
        monitorTimeout = scheduler.schedule(DISCOVERY_TIMEOUT_MS) { checkDeadlines() }
        monitorLimit = scheduler.schedule(MONITOR_LIMIT_MS) { checkDeadlines() }
        if (!discoveryPending) discover(monitorProfile)
    }

    fun closeMonitor() = endMonitor(MonitorStatus.IDLE, notifyPeer = true)

    fun callElevator() {
        checkDeadlines()
        if (closed || state.elevator == ElevatorStatus.DISCOVERING || state.elevator == ElevatorStatus.WAITING) return
        val discoveryPending = state.monitor == MonitorStatus.DISCOVERING && monitorProfile.peerId == profile.peerId
        setElevator(ElevatorStatus.DISCOVERING)
        elevatorDeadline = scheduler.nowMs + DISCOVERY_TIMEOUT_MS
        elevatorTimeout = scheduler.schedule(DISCOVERY_TIMEOUT_MS) { checkDeadlines() }
        if (!discoveryPending) discover(profile)
    }

    fun receive(datagram: ReceivedDatagram) {
        checkDeadlines()
        if (closed || datagram.sourcePort != datagram.localPort) return
        when (val packet = MaichiPackets.decode(datagram.localPort, datagram.payload)) {
            is MaichiPacket.DiscoveryReply -> {
                if (acceptsDiscovery(datagram.source, packet, profile) || acceptsDiscovery(datagram.source, packet, monitorProfile)) observer.onPeerActivity()
                if (state.monitor == MonitorStatus.DISCOVERING && acceptsDiscovery(datagram.source, packet, monitorProfile)) {
                    beginMonitor(datagram.source)
                }
                if (state.elevator == ElevatorStatus.DISCOVERING && acceptsDiscovery(datagram.source, packet, profile)) {
                    beginElevator(datagram.source)
                }
            }
            is MaichiPacket.MonitorControl -> {
                if (packet.type == MonitorCommand.CLOSE.type && isMonitorPeer(datagram.source, packet.identity)) {
                    observer.onPeerActivity()
                    endMonitor(MonitorStatus.ENDED, notifyPeer = false)
                }
            }
            is MaichiPacket.Media -> {
                if (isMonitorPeer(datagram.source, packet.identity)) receiveMedia(packet.fragment)
            }
            is MaichiPacket.MonitorBusy -> {
                if (state.monitor == MonitorStatus.CONNECTING && isMonitorPeer(datagram.source, packet.identity)) {
                    observer.onPeerActivity()
                    endMonitor(MonitorStatus.BUSY, notifyPeer = false)
                }
            }
            MaichiPacket.ElevatorReply -> {
                if (state.elevator == ElevatorStatus.WAITING && datagram.source == elevatorPeer) {
                    observer.onPeerActivity()
                    endElevator(ElevatorStatus.RESPONDED)
                }
            }
            null -> Unit
        }
    }

    fun disconnect() {
        if (closed) return
        closed = true
        endMonitor(MonitorStatus.DISCONNECTED, notifyPeer = false)
        endElevator(ElevatorStatus.DISCONNECTED)
        transport.close()
    }

    override fun close() {
        if (closed) return
        closeMonitor()
        if (closed) return
        endElevator(ElevatorStatus.IDLE)
        closed = true
        transport.close()
    }

    private fun discover(selectedProfile: TerminalProfile) {
        send(MaichiPackets.DISCOVERY_PORT, selectedProfile.broadcastAddress, MaichiPackets.encodeDiscovery(selectedProfile.peerId))
    }

    private fun acceptsDiscovery(source: Inet4Address, packet: MaichiPacket.DiscoveryReply, selected: TerminalProfile): Boolean =
        packet.peerId == selected.peerId && source != selected.localAddress &&
            (selected.expectedPeerAddress == null || source == selected.expectedPeerAddress) &&
            !source.isAnyLocalAddress && !source.isMulticastAddress

    private fun beginMonitor(peer: Inet4Address) {
        monitorTimeout?.cancel()
        monitorPeer = peer
        lastFrameTime = scheduler.nowMs
        monitorDeadline = lastFrameTime + VIDEO_TIMEOUT_MS
        setMonitor(MonitorStatus.CONNECTING)
        if (!sendMonitor(MonitorCommand.OPEN, peer)) return
        scheduleVideoTimeout(VIDEO_TIMEOUT_MS)
        monitorPeriodic = scheduler.schedule(MONITOR_PROBE_DELAY_MS) {
            checkDeadlines()
            if (monitorPeer == peer && sendMonitor(MonitorCommand.PROBE, peer)) {
                scheduleKeepAlive(peer, FIRST_KEEP_ALIVE_DELAY_MS)
            }
        }
    }

    private fun scheduleKeepAlive(peer: Inet4Address, delayMs: Long) {
        monitorPeriodic = scheduler.schedule(delayMs) {
            checkDeadlines()
            if (monitorPeer == peer && sendMonitor(MonitorCommand.KEEP_ALIVE, peer)) {
                scheduleKeepAlive(peer, KEEP_ALIVE_PERIOD_MS)
            }
        }
    }

    private fun scheduleVideoTimeout(delayMs: Long) {
        monitorTimeout = scheduler.schedule(delayMs) {
            checkDeadlines()
            if (monitorPeer == null) return@schedule
            val remaining = VIDEO_TIMEOUT_MS - (scheduler.nowMs - lastFrameTime)
            if (remaining <= 0) endMonitor(MonitorStatus.TIMED_OUT, notifyPeer = true)
            else scheduleVideoTimeout(remaining)
        }
    }

    private fun receiveMedia(fragment: MediaFragment) {
        observer.onPeerActivity()
        if (fragment.kind == MediaFragment.AUDIO) {
            audioPackets++
            return
        }
        val frame = jpegAssembler.accept(fragment, scheduler.nowMs, observer::decodeJpeg) ?: return
        lastFrameTime = scheduler.nowMs
        monitorDeadline = lastFrameTime + VIDEO_TIMEOUT_MS
        videoFrames++
        setMonitor(MonitorStatus.VIEWING)
        observer.onFrame(frame)
    }

    private fun isMonitorPeer(source: Inet4Address, identity: MonitorIdentity): Boolean =
        source == monitorPeer && source == identity.peerAddress && identity.peerId == monitorProfile.peerId &&
            identity.indoorAddress == monitorProfile.localAddress && identity.indoorId == monitorProfile.indoorId

    private fun endMonitor(status: MonitorStatus, notifyPeer: Boolean) {
        monitorDeadline = null; monitorLimitAt = null
        val peer = monitorPeer
        monitorPeer = null
        monitorTimeout?.cancel()
        monitorTimeout = null
        monitorLimit?.cancel()
        monitorLimit = null
        monitorPeriodic?.cancel()
        monitorPeriodic = null
        jpegAssembler.clear()
        if (videoFrames != 0 || audioPackets != 0) observer.onStreamEnded(videoFrames, audioPackets)
        videoFrames = 0
        audioPackets = 0
        setMonitor(status)
        if (peer != null && notifyPeer) sendMonitor(MonitorCommand.CLOSE, peer)
    }

    private fun beginElevator(peer: Inet4Address) {
        elevatorTimeout?.cancel()
        elevatorPeer = peer
        setElevator(ElevatorStatus.WAITING)
        elevatorDeadline = scheduler.nowMs + ELEVATOR_TIMEOUT_MS
        if (send(MaichiPackets.SESSION_PORT, peer, MaichiPackets.encodeElevator(profile.indoorId))) {
            elevatorTimeout = scheduler.schedule(ELEVATOR_TIMEOUT_MS) { checkDeadlines() }
        }
    }

    private fun endElevator(status: ElevatorStatus) {
        elevatorDeadline = null
        elevatorPeer = null
        elevatorTimeout?.cancel()
        elevatorTimeout = null
        setElevator(status)
    }

    fun checkDeadlines() {
        if (closed) return
        val timeoutAt = monitorDeadline ?: Long.MAX_VALUE
        val limitAt = monitorLimitAt ?: Long.MAX_VALUE
        if (scheduler.nowMs >= minOf(timeoutAt, limitAt)) {
            endMonitor(if (limitAt < timeoutAt) MonitorStatus.ENDED else MonitorStatus.TIMED_OUT, notifyPeer = monitorPeer != null)
        }
        elevatorDeadline?.let { if (scheduler.nowMs >= it) endElevator(ElevatorStatus.TIMED_OUT) }
    }

    private fun sendMonitor(command: MonitorCommand, peer: Inet4Address): Boolean =
        send(MaichiPackets.SESSION_PORT, peer, MaichiPackets.encodeMonitor(command, monitorProfile, peer))

    private fun send(port: Int, peer: Inet4Address, payload: ByteArray): Boolean {
        if (closed) return false
        return try {
            transport.send(port, peer, payload)
            true
        } catch (failure: Exception) {
            if (failure !is IOException && failure !is SecurityException) throw failure
            disconnect()
            observer.onTransportFailure(failure)
            false
        }
    }

    private fun setMonitor(status: MonitorStatus) {
        if (state.monitor == status) return
        state = state.copy(monitor = status)
        observer.onStateChanged(state)
    }

    private fun setElevator(status: ElevatorStatus) {
        if (state.elevator == status) return
        state = state.copy(elevator = status)
        observer.onStateChanged(state)
    }

    companion object {
        private const val MONITOR_LIMIT_MS = 30_000L
        private const val DISCOVERY_TIMEOUT_MS = 2_000L
        private const val VIDEO_TIMEOUT_MS = 5_000L
        private const val ELEVATOR_TIMEOUT_MS = 3_000L
        private const val MONITOR_PROBE_DELAY_MS = 1_010L
        private const val FIRST_KEEP_ALIVE_DELAY_MS = 230L
        private const val KEEP_ALIVE_PERIOD_MS = 110L
    }
}
