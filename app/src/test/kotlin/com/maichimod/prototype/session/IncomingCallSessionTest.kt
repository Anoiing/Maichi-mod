package com.maichimod.prototype.session

import com.maichimod.prototype.protocol.*
import com.maichimod.prototype.transport.*
import java.net.Inet4Address
import org.junit.Assert.*
import org.junit.Test

class IncomingCallSessionTest {
    private val clock = Clock()
    private val sent = mutableListOf<Pair<Int, ByteArray>>()
    private val frames = mutableListOf<ByteArray>()
    private val audio = mutableListOf<ByteArray>()
    private var failure: Exception? = null
    private val session = IncomingCallSession(DEMO_PROFILE, object : DatagramTransport {
        override fun send(localPort: Int, destination: Inet4Address, payload: ByteArray) {
            assertEquals(DEMO_PEER_IP, destination)
            sent.add(localPort to payload)
            failure?.let { throw it }
        }
        override fun close() = Unit
    }, clock, object : CallObserver<ByteArray> {
        override fun onStateChanged(status: CallStatus) = Unit
        override fun decodeJpeg(jpeg: ByteArray) = jpeg
        override fun onFrame(frame: ByteArray) { frames.add(frame) }
        override fun onAudio(pcm: ByteArray) { audio.add(pcm) }
    })

    @Test fun repliesToThisHouseholdAndNegotiatesWithoutAnsweringOrUnlocking() {
        session.receive(rx(10000, hex("50 45 4e 47 55 49 4e 30 98 00 01 00 00 00 20 00") + ByteArray(16)))
        assertTrue(sent.isEmpty())
        session.receive(rx(10008, byteArrayOf(1) + DEMO_PROFILE.indoorId.toByteArray() + ByteArray(22)))
        assertEquals(2, sent.single().second[0].toInt())
        session.receive(rx(10000, hex("50 45 4e 47 55 49 4e 30 98 00 01 00 00 00 20 00") + ByteArray(16)))
        assertEquals(898, sent.last().second.size)
        session.receive(ring())
        assertEquals(CallStatus.RINGING, session.status)
        assertEquals(3, sent.last().second[10].toInt())
        assertFalse(sent.any { it.first == 10000 && it.second[10].toInt() in listOf(5, 6) })
    }

    @Test fun answeringOnceEnablesAudioAndUnlockDoesNotEndTheCall() {
        session.receive(ring())
        session.receive(rx(10000, CallPackets.encodeAudio(DEMO_PROFILE, DEMO_PEER_IP, 1, ByteArray(512))))
        assertTrue(audio.isEmpty())
        session.answer(); session.answer()
        assertEquals(CallStatus.ANSWERED, session.status)
        assertEquals(1, sent.count { it.second[10].toInt() == 5 })
        session.receive(rx(10000, CallPackets.encodeAudio(DEMO_PROFILE, DEMO_PEER_IP, 2, ByteArray(512))))
        assertEquals(1, audio.size)
        assertTrue(session.unlock())
        assertEquals(6, sent.last().second[10].toInt())
        assertEquals(CallStatus.ANSWERED, session.status)
        session.hangUp()
        val count = sent.size
        clock.advance(60_000)
        session.sendAudio(ByteArray(512))
        assertFalse(session.unlock())
        assertEquals(count, sent.size)
        assertEquals(CallStatus.ENDED, session.status)
    }

    @Test fun callActivityContinuesBeyondTheActiveMonitorThirtySecondLimit() {
        session.receive(ring()); session.answer()
        repeat(40) {
            clock.advance(1_000)
            session.receive(rx(10000, CallPackets.encodeSignal(CallCommand.KEEP_ALIVE, DEMO_PROFILE, DEMO_PEER_IP)))
        }
        assertEquals(CallStatus.ANSWERED, session.status)
        clock.advance(8_000)
        assertEquals(CallStatus.TIMED_OUT, session.status)
    }

    @Test fun unlockCanHangUpExactlyThreeSecondsAfterSending() {
        session.receive(ring())
        assertTrue(session.unlock(autoHangUp = true))
        clock.advance(2_999)
        assertEquals(CallStatus.RINGING, session.status)
        assertEquals(0, closeCount())
        clock.advance(1)
        assertEquals(CallStatus.ENDED, session.status)
        assertEquals(1, closeCount())
        val count = sent.size
        clock.advance(1_000)
        assertEquals(count, sent.size)
    }

    @Test fun disabledAutoHangUpKeepsTheCallForManualHangUp() {
        session.receive(ring()); session.answer()
        assertTrue(session.unlock(autoHangUp = false))
        clock.advance(4_000)
        assertEquals(CallStatus.ANSWERED, session.status)
        assertEquals(0, closeCount())
        session.hangUp()
        assertEquals(1, closeCount())
    }

    @Test fun answeringAfterUnlockDoesNotCancelTheThreeSecondDeadline() {
        session.receive(ring()); session.unlock(autoHangUp = true)
        clock.advance(1_500); session.answer()
        clock.advance(1_499)
        assertEquals(CallStatus.ANSWERED, session.status)
        clock.advance(1)
        assertEquals(CallStatus.ENDED, session.status)
        assertEquals(1, closeCount())
    }

    @Test fun anotherUnlockRestartsTheDeadlineAndManualModeCancelsIt() {
        session.receive(ring()); session.unlock(autoHangUp = true)
        clock.advance(2_000); session.unlock(autoHangUp = true)
        clock.advance(2_999)
        assertEquals(CallStatus.RINGING, session.status)
        session.unlock(autoHangUp = false)
        clock.advance(1_001)
        assertEquals(CallStatus.RINGING, session.status)
        assertEquals(0, closeCount())
    }

    @Test fun previousUnlockTimerCannotHangUpTheNextCall() {
        session.receive(ring()); session.unlock(autoHangUp = true)
        clock.advance(1_000); session.hangUp()
        session.receive(rx(10008, byteArrayOf(1) + DEMO_PROFILE.indoorId.toByteArray() + ByteArray(22)))
        session.receive(rx(10000, hex("50 45 4e 47 55 49 4e 30 98 00 01 00 00 00 20 00") + ByteArray(16)))
        session.receive(ring())
        clock.advance(2_001)
        assertEquals(CallStatus.RINGING, session.status)
        assertEquals(1, closeCount())
    }

    @Test fun repeatedUnlockUsesThreeSecondsFromTheLatestRequest() {
        session.receive(ring()); session.unlock(autoHangUp = true)
        clock.advance(2_000); session.unlock(autoHangUp = true)
        clock.advance(2_999)
        assertEquals(CallStatus.RINGING, session.status)
        clock.advance(1)
        assertEquals(CallStatus.ENDED, session.status)
        assertEquals(1, closeCount())
    }

    @Test fun failedUnlockDoesNotStartAnAutomaticHangUp() {
        session.receive(ring())
        failure = java.io.IOException("test failure")
        assertFalse(session.unlock(autoHangUp = true))
        clock.advance(3_000)
        assertEquals(CallStatus.DISCONNECTED, session.status)
        assertEquals(0, closeCount())
    }

    @Test fun hangingUpRejectsLateRingRetriesUntilANewLookupBegins() {
        session.receive(ring()); session.hangUp()
        val count = sent.size
        session.receive(ring())
        assertEquals(CallStatus.ENDED, session.status)
        assertEquals(count, sent.size)
        session.receive(rx(10008, byteArrayOf(1) + DEMO_PROFILE.indoorId.toByteArray() + ByteArray(22)))
        session.receive(rx(10000, hex("50 45 4e 47 55 49 4e 30 98 00 01 00 00 00 20 00") + ByteArray(16)))
        session.receive(ring())
        assertEquals(CallStatus.RINGING, session.status)
    }

    @Test fun wrongHouseholdIsIgnoredAndSendFailureDoesNotRetryUnlock() {
        session.receive(ring().copy(payload = ring().payload.copyOf().apply { this[67] = '2'.code.toByte() }))
        assertEquals(CallStatus.IDLE, session.status)
        session.receive(ring())
        failure = java.io.IOException("test failure")
        assertFalse(session.unlock())
        clock.advance(20_000)
        assertEquals(1, sent.count { it.second[10].toInt() == 6 })
        assertEquals(CallStatus.DISCONNECTED, session.status)
    }

    private fun ring() = rx(10000, CallPackets.encodeSignal(CallCommand.NEGOTIATE, DEMO_PROFILE, DEMO_PEER_IP)
        .copyOf(256).apply { this[10] = 1; this[14] = 0; this[15] = 1 })
    private fun rx(port: Int, data: ByteArray) = ReceivedDatagram(DEMO_PEER_IP, port, port, data)
    private fun closeCount() = sent.count { it.first == 10000 && it.second[10].toInt() == 30 }

    private class Clock : SessionScheduler {
        private data class Job(val time: Long, val action: () -> Unit, var cancelled: Boolean = false)
        private val jobs = mutableListOf<Job>()
        override var nowMs = 0L
        override fun schedule(delayMs: Long, action: () -> Unit): Cancellation {
            val job = Job(nowMs + delayMs, action); jobs.add(job)
            return Cancellation { job.cancelled = true }
        }
        fun advance(ms: Long) {
            val target = nowMs + ms
            while (true) {
                val job = jobs.filter { !it.cancelled && it.time <= target }.minByOrNull { it.time } ?: break
                jobs.remove(job); nowMs = job.time; job.action()
            }
            nowMs = target
        }
    }
}
