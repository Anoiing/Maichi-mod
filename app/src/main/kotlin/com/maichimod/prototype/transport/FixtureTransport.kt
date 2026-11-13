package com.maichimod.prototype.transport

import com.maichimod.prototype.protocol.DEMO_PEER_IP
import com.maichimod.prototype.protocol.DEMO_PROFILE
import com.maichimod.prototype.protocol.MaichiPackets
import com.maichimod.prototype.protocol.MonitorCommand
import com.maichimod.prototype.protocol.CallCommand
import com.maichimod.prototype.protocol.CallPackets
import com.maichimod.prototype.session.Cancellation
import com.maichimod.prototype.session.SessionScheduler
import java.net.Inet4Address

internal class FixtureTransport(
    private val scheduler: SessionScheduler,
    monitor: List<CaptureRow>,
    private val elevatorReply: CaptureRow?,
    private val callRows: List<CaptureRow> = emptyList(),
    private val onReceive: (ReceivedDatagram) -> Unit,
) : DatagramTransport {
    private var discovery = monitor.firstOrNull { it.incoming && it.localPort == MaichiPackets.DISCOVERY_PORT }
    private var video = monitor.filter { it.incoming && it.localPort == MaichiPackets.SESSION_PORT }
    private val query = MaichiPackets.encodeDiscovery(DEMO_PROFILE.peerId)
    private val start = MaichiPackets.encodeMonitor(MonitorCommand.OPEN, DEMO_PROFILE, DEMO_PEER_IP)
    private val stop = MaichiPackets.encodeMonitor(MonitorCommand.CLOSE, DEMO_PROFILE, DEMO_PEER_IP)
    private val elevatorRequest = MaichiPackets.encodeElevator(DEMO_PROFILE.indoorId)

    private var playback: Cancellation? = null
    private var discoveryPending: Cancellation? = null
    private var elevatorPending: Cancellation? = null
    private var playbackGeneration = 0
    private var closed = false
    private var callPlayback: Cancellation? = null
    private var callGeneration = 0

    fun simulateIncoming(): Boolean {
        if (closed || callPlayback != null || callRows.isEmpty()) return false
        val end = callRows.indexOfFirst { it.localPort == 10000 && it.payload.getOrNull(8) == 0xb7.toByte() &&
            it.payload.getOrNull(10)?.toInt() == 30 }
        if (end < 0) return false
        val rows = callRows.take(end + 1)
        val generation = ++callGeneration
        val start = scheduler.nowMs
        var index = 0
        fun next() {
            if (index == rows.size || generation != callGeneration) { callPlayback = null; return }
            callPlayback = scheduler.schedule((start + rows[index].timeMs - scheduler.nowMs).coerceAtLeast(0)) {
                while (generation == callGeneration && index < rows.size && rows[index].timeMs <= scheduler.nowMs - start) {
                    onReceive(rows[index++].received())
                }
                next()
            }
        }
        next()
        return true
    }

    fun selectMonitor(monitor: List<CaptureRow>) {
        stopPlayback()
        discovery = monitor.firstOrNull { it.incoming && it.localPort == MaichiPackets.DISCOVERY_PORT }
        video = monitor.filter { it.incoming && it.localPort == MaichiPackets.SESSION_PORT }
    }

    override fun send(localPort: Int, destination: Inet4Address, payload: ByteArray) {
        if (closed) return
        if (localPort == MaichiPackets.DISCOVERY_PORT && destination == DEMO_PROFILE.broadcastAddress &&
            payload.contentEquals(query)
        ) {
            discoveryPending?.cancel()
            discoveryPending = scheduler.schedule(50) {
                val reply = discovery?.received()
                    ?: ReceivedDatagram(DEMO_PEER_IP, localPort, localPort, query.copyOf().apply { this[0] = 2 })
                onReceive(reply)
            }
            return
        }
        if (localPort != MaichiPackets.SESSION_PORT || destination != DEMO_PEER_IP) return
        if (payload.contentEquals(CallPackets.encodeSignal(CallCommand.CLOSE, DEMO_PROFILE, DEMO_PEER_IP))) {
            callGeneration++; callPlayback?.cancel(); callPlayback = null
            return
        }
        when {
            payload.contentEquals(start) -> startPlayback()
            payload.contentEquals(stop) -> stopPlayback()
            payload.contentEquals(elevatorRequest) -> elevatorReply?.let { reply ->
                elevatorPending?.cancel()
                elevatorPending = scheduler.schedule(550) { onReceive(reply.received()) }
            }
        }
    }

    override fun close() {
        closed = true
        discoveryPending?.cancel()
        elevatorPending?.cancel()
        callGeneration++; callPlayback?.cancel(); callPlayback = null
        stopPlayback()
    }

    private fun startPlayback() {
        stopPlayback()
        if (video.isEmpty()) return
        val generation = playbackGeneration
        val startedAt = scheduler.nowMs
        var index = 0

        fun scheduleNext() {
            if (index == video.size || generation != playbackGeneration) return
            val delay = (startedAt + video[index].timeMs - scheduler.nowMs).coerceAtLeast(0)
            playback = scheduler.schedule(delay) {
                val elapsed = scheduler.nowMs - startedAt
                while (generation == playbackGeneration && index < video.size && video[index].timeMs <= elapsed) {
                    onReceive(video[index++].received())
                }
                scheduleNext()
            }
        }
        scheduleNext()
    }

    private fun stopPlayback() {
        playbackGeneration++
        playback?.cancel()
        playback = null
    }
}
