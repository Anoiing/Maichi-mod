package com.maichimod.prototype.session

import com.maichimod.prototype.protocol.CallCommand
import com.maichimod.prototype.protocol.CallPacket
import com.maichimod.prototype.protocol.CallPackets
import com.maichimod.prototype.protocol.JpegAssembler
import com.maichimod.prototype.protocol.MediaFragment
import com.maichimod.prototype.protocol.MonitorIdentity
import com.maichimod.prototype.protocol.TerminalProfile
import com.maichimod.prototype.transport.DatagramTransport
import com.maichimod.prototype.transport.ReceivedDatagram
import java.io.Closeable
import java.io.IOException
import java.net.Inet4Address

internal enum class CallStatus { IDLE, RINGING, ANSWERED, ENDED, TIMED_OUT, DISCONNECTED }

internal interface CallObserver<Frame : Any> {
    fun onStateChanged(status: CallStatus)
    fun decodeJpeg(jpeg: ByteArray): Frame?
    fun onFrame(frame: Frame)
    fun onAudio(pcm: ByteArray)
    fun onTransportFailure(failure: Exception) = Unit
}

internal class IncomingCallSession<Frame : Any>(
    private val profile: TerminalProfile,
    private val transport: DatagramTransport,
    private val scheduler: SessionScheduler,
    private val observer: CallObserver<Frame>,
) : Closeable {
    var status = CallStatus.IDLE
        private set

    private var peer: Inet4Address? = null
    private var queriedBy: Inet4Address? = null
    private var queriedAt = 0L
    private var lastActivity = 0L
    private var periodic: Cancellation? = null
    private var timeout: Cancellation? = null
    private var unlockHangUp: Cancellation? = null
    private var closed = false
    private var requireLookup = false
    private var sequence = 0
    private val assembler = JpegAssembler()

    fun receive(datagram: ReceivedDatagram) {
        if (closed || datagram.localPort != datagram.sourcePort || !allowed(datagram.source)) return
        when (val packet = CallPackets.decode(datagram.localPort, datagram.payload)) {
            is CallPacket.Query -> if (packet.indoorId == profile.indoorId) {
                queriedBy = datagram.source; queriedAt = scheduler.nowMs
                send(10008, datagram.source, CallPackets.encodeDiscoveryReply(profile.indoorId))
            }
            CallPacket.Lookup -> if (datagram.source == profile.expectedPeerAddress ||
                (datagram.source == queriedBy && scheduler.nowMs - queriedAt <= 5_000)
            ) {
                if (send(10000, datagram.source, CallPackets.encodeRoster(profile))) requireLookup = false
            }
            is CallPacket.Signal -> {
                if (!matches(datagram.source, packet.identity)) return
                if (packet.type == 1) {
                    if (peer != null && peer != datagram.source) return
                    if (peer == null && requireLookup) return
                    if (status == CallStatus.ANSWERED) return
                    if (peer == null) {
                        peer = datagram.source; sequence = 0; assembler.clear()
                        setStatus(CallStatus.RINGING)
                        lastActivity = scheduler.nowMs
                        armTimeout(8_000)
                        keepAlive()
                    }
                    lastActivity = scheduler.nowMs
                    sendSignal(CallCommand.NEGOTIATE)
                } else if (peer == datagram.source) {
                    lastActivity = scheduler.nowMs
                    if (packet.type == CallCommand.CLOSE.type) end(CallStatus.ENDED, false)
                }
            }
            is CallPacket.Media -> if (peer == datagram.source && matches(datagram.source, packet.identity)) {
                lastActivity = scheduler.nowMs
                val fragment = packet.fragment
                if (fragment.kind == MediaFragment.AUDIO) {
                    if (status == CallStatus.ANSWERED && fragment.total == 1 && fragment.bytes.size == 512) {
                        observer.onAudio(fragment.bytes)
                    }
                } else {
                    assembler.accept(fragment, scheduler.nowMs, observer::decodeJpeg)?.let(observer::onFrame)
                }
            }
            null -> Unit
        }
    }

    fun answer() {
        if (!closed && status == CallStatus.RINGING && sendSignal(CallCommand.ANSWER)) setStatus(CallStatus.ANSWERED)
    }

    fun unlock(autoHangUp: Boolean = false): Boolean {
        if (closed || peer == null || !sendSignal(CallCommand.UNLOCK)) return false
        unlockHangUp?.cancel()
        unlockHangUp = if (autoHangUp) scheduler.schedule(3_000) { hangUp() } else null
        return true
    }
    fun hangUp() = end(CallStatus.ENDED, true)

    fun sendAudio(pcm: ByteArray) {
        val target = peer ?: return
        if (closed || status != CallStatus.ANSWERED || pcm.size != 512) return
        sequence = (sequence + 1) and 0xffff
        send(10000, target, CallPackets.encodeAudio(profile, target, sequence, pcm))
    }

    fun disconnect() {
        closed = true
        end(CallStatus.DISCONNECTED, false)
    }

    override fun close() {
        end(CallStatus.ENDED, true)
        closed = true
    }

    private fun allowed(source: Inet4Address) = source != profile.localAddress &&
        !source.isAnyLocalAddress && !source.isMulticastAddress && !source.isLoopbackAddress &&
        (profile.expectedPeerAddress == null || source == profile.expectedPeerAddress)

    private fun matches(source: Inet4Address, identity: MonitorIdentity) = identity.peerAddress == source &&
        identity.peerId == profile.peerId && identity.indoorId == profile.indoorId &&
        identity.indoorAddress == profile.localAddress

    private fun keepAlive() {
        periodic = scheduler.schedule(110) {
            if (peer != null && sendSignal(CallCommand.KEEP_ALIVE)) keepAlive()
        }
    }

    private fun armTimeout(delay: Long) {
        timeout = scheduler.schedule(delay) {
            val remaining = 8_000 - (scheduler.nowMs - lastActivity)
            if (remaining <= 0) end(CallStatus.TIMED_OUT, true) else armTimeout(remaining)
        }
    }

    private fun end(next: CallStatus, notify: Boolean) {
        val target = peer
        peer = null
        if (target != null) requireLookup = true
        periodic?.cancel(); periodic = null
        timeout?.cancel(); timeout = null
        unlockHangUp?.cancel(); unlockHangUp = null
        assembler.clear()
        if (target != null || next == CallStatus.DISCONNECTED) setStatus(next)
        if (target != null && notify && !closed) send(10000, target, CallPackets.encodeSignal(CallCommand.CLOSE, profile, target))
    }

    private fun sendSignal(command: CallCommand): Boolean {
        val target = peer ?: return false
        return send(10000, target, CallPackets.encodeSignal(command, profile, target))
    }

    private fun send(port: Int, target: Inet4Address, bytes: ByteArray): Boolean = try {
        transport.send(port, target, bytes)
        true
    } catch (failure: Exception) {
        if (failure !is IOException && failure !is SecurityException) throw failure
        disconnect()
        observer.onTransportFailure(failure)
        false
    }

    private fun setStatus(next: CallStatus) {
        if (status == next) return
        status = next; observer.onStateChanged(next)
    }
}
