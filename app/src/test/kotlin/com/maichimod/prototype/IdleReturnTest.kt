package com.maichimod.prototype

import com.maichimod.prototype.session.Cancellation
import com.maichimod.prototype.session.SessionScheduler
import org.junit.Assert.*
import org.junit.Test

class IdleReturnTest {
    private val clock = Clock()
    private var returns = 0
    private var allowed = true
    private fun loop() = IdleReturn(clock) { returns++; allowed }.apply { configure(true, 3, true) }
    private fun IdleReturn.outside(interactive: Boolean = true, unlocked: Boolean = true, inCall: Boolean = false) =
        update(ownAppVisible = false, interactive = interactive, unlocked = unlocked, inCall = inCall)

    @Test fun returnsOnceAfterThreeMinuteExternalIdle() {
        val loop = loop(); loop.outside()
        clock.advance(179999); assertEquals(0, returns)
        clock.advance(1); assertEquals(1, returns)
        loop.outside(); clock.advance(600000); assertEquals(1, returns)
    }

    @Test fun interactionAt179999RestartsDeadline() {
        val loop = loop(); loop.outside()
        clock.advance(179999); loop.interaction()
        clock.advance(179999); assertEquals(0, returns)
        clock.advance(1); assertEquals(1, returns)
    }

    @Test fun ownPagesNeverAutoNavigate() {
        val loop = loop()
        loop.update(ownAppVisible = true, interactive = true, unlocked = true, inCall = false)
        clock.advance(600000); assertEquals(0, returns)
    }

    @Test fun windowRefreshDoesNotCountAsUserInput() {
        val loop = loop(); loop.outside()
        repeat(17) { clock.advance(10000); loop.outside() }
        clock.advance(10000); assertEquals(1, returns)
    }

    @Test fun disabledOrUnauthorizedNeverReturns() {
        for ((enabled, authorized) in listOf(false to true, true to false)) {
            val loop = loop(); loop.configure(enabled, 3, authorized); loop.outside()
            clock.advance(600000); assertEquals(0, returns); loop.close()
        }
    }

    @Test fun lockedDeadlineWaitsForNormalUnlock() {
        val loop = loop(); loop.outside()
        clock.advance(1000); loop.outside(interactive = false, unlocked = false)
        clock.advance(180000); loop.interaction(); assertEquals(0, returns)
        loop.outside(interactive = true, unlocked = false); assertEquals(0, returns)
        loop.outside(); assertEquals(1, returns)
    }

    @Test fun activeCallSuspendsReturnAndEndingGivesAFullGracePeriod() {
        val loop = loop(); loop.outside()
        clock.advance(179999); loop.outside(inCall = true)
        clock.advance(600000); loop.interaction(); assertEquals(0, returns)
        loop.outside(); clock.advance(179999); assertEquals(0, returns)
        clock.advance(1); assertEquals(1, returns)
    }

    @Test fun failedForegroundStartDoesNotLoop() {
        allowed = false
        val loop = loop(); loop.outside()
        clock.advance(180000); assertEquals(1, returns); assertTrue(loop.returnFailed)
        repeat(10) { loop.outside(); clock.advance(180000) }
        assertEquals(1, returns)
        loop.interaction(); clock.advance(180000); assertEquals(2, returns)
    }

    @Test fun serviceRecoveryStartsFreshDeadline() {
        val first = loop(); first.outside(); clock.advance(179999); first.close()
        val restored = loop(); restored.outside()
        clock.advance(179999); assertEquals(0, returns)
        clock.advance(1); assertEquals(1, returns)
    }

    @Test fun disablingOrChangingDurationCancelsThePreviousTask() {
        val loop = loop(); loop.outside(); clock.advance(179999)
        loop.configure(true, 1, true)
        clock.advance(59999); assertEquals(0, returns)
        loop.configure(false, 1, true)
        clock.advance(600000); assertEquals(0, returns)
        assertEquals(0, clock.pending())
    }

    @Test fun unknownForegroundWaitsWithoutExtendingTheDeadline() {
        val loop = loop(); loop.outside(); clock.advance(1000)
        loop.update(false, true, true, false, foregroundKnown = false)
        clock.advance(180000); assertEquals(0, returns)
        loop.update(false, true, true, false, foregroundKnown = true)
        assertEquals(1, returns)
    }

    @Test fun silentSystemRefusalIsReportedWithoutRetrying() {
        val loop = loop(); loop.outside(); clock.advance(180000)
        loop.returnNotObserved(); assertTrue(loop.returnFailed)
        loop.outside(); clock.advance(180000); assertEquals(1, returns)
        loop.interaction(); loop.returnNotObserved()
        assertFalse(loop.returnFailed)
    }

    @Test fun deliveredInteractionAtAnOverdueDeadlineWinsBeforeNavigation() {
        val loop = loop(); loop.outside()
        // The main thread was busy: input is delivered before an already overdue timer callback.
        clock.elapseWithoutDispatch(180001)
        loop.update(false, true, true, false, userInteraction = true)
        assertEquals(0, returns)
        clock.advance(179999); assertEquals(0, returns)
        clock.advance(1); assertEquals(1, returns)
    }

    private class Clock : SessionScheduler {
        private data class Job(val at: Long, val action: () -> Unit, var cancelled: Boolean = false)
        private val jobs = mutableListOf<Job>()
        override var nowMs = 0L
            private set
        override fun schedule(delayMs: Long, action: () -> Unit): Cancellation {
            val job = Job(nowMs + delayMs, action); jobs.add(job)
            return Cancellation { job.cancelled = true }
        }
        fun advance(ms: Long) {
            val target = nowMs + ms
            while (true) {
                val next = jobs.filter { !it.cancelled && it.at <= target }.minByOrNull { it.at } ?: break
                jobs.remove(next); nowMs = next.at; next.action()
            }
            nowMs = target
        }
        fun pending() = jobs.count { !it.cancelled }
        fun elapseWithoutDispatch(ms: Long) { nowMs += ms }
    }
}
