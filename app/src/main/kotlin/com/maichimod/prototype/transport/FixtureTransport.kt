package com.maichimod.prototype.transport

import com.maichimod.prototype.protocol.DEMO_PEER_IP
import com.maichimod.prototype.protocol.DEMO_PROFILE
import com.maichimod.prototype.protocol.MaichiPackets
import com.maichimod.prototype.protocol.MonitorCommand
import com.maichimod.prototype.session.Cancellation
import com.maichimod.prototype.session.SessionScheduler
import java.net.Inet4Address

internal class FixtureTransport(
    private val scheduler: SessionScheduler,
    monitor: List<CaptureRow>,
    private val elevatorReply: CaptureRow?,
    private val onReceive: (ReceivedDatagram) -> Unit,
) : DatagramTransport {
    private val discovery = monitor.firstOrNull { it.incoming && it.localPort == MaichiPackets.DISCOVERY_PORT }
    private val video = monitor.filter { it.incoming && it.localPort == MaichiPackets.SESSION_PORT }
    private val query = MaichiPackets.encodeDiscovery(DEMO_PROFILE.peerId)
    private val start = MaichiPackets.encodeMonitor(MonitorCommand.OPEN, DEMO_PROFILE, DEMO_PEER_IP)
    private val stop = MaichiPackets.encodeMonitor(MonitorCommand.CLOSE, DEMO_PROFILE, DEMO_PEER_IP)
    private val elevatorRequest = MaichiPackets.encodeElevator(DEMO_PROFILE.indoorId)

    private var playback: Cancellation? = null
    private var discoveryPending: Cancellation? = null
    private var elevatorPending: Cancellation? = null
    private var playbackGeneration = 0
    private var closed = false

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
