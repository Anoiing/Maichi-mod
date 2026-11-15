package com.maichimod.prototype

import org.junit.Assert.*
import org.junit.Test

class ScreenPolicyTest {
    private val options = StandbyOptions(enabled = true, presenceEnabled = true)
    private val visible = ScreenEnvironment(visible = true, cameraAllowed = true)
    private fun policy() = ScreenPolicy(0).apply { update(0, options, visible) }

    @Test fun idleDimsAtThirtySecondsAndUsesOnlyTheAppWindow() {
        val policy = policy()
        assertFalse(policy.demand(29999).dimmed)
        val dim = policy.demand(30000)
        assertTrue(dim.dimmed)
        assertEquals(0.05f, dim.brightness)
        assertTrue(dim.keepScreenOn)
        assertEquals(2000L, dim.detectionIntervalMs)
    }

    @Test fun touchRestoresSystemBrightnessAndReplacesTheOldDeadline() {
        val policy = policy()
        assertTrue(policy.demand(30000).dimmed)
        policy.interaction(30000)
        assertEquals(-1f, policy.demand(30000).brightness)
        assertFalse(policy.demand(59999).dimmed)
        assertTrue(policy.demand(60000).dimmed)
    }

    @Test fun continuousPresenceNeedsEightHundredMillisecondsToWake() {
        val policy = policy()
        val generation = policy.sensingGeneration
        policy.onPresence(Presence.PRESENT, 30000, generation)
        assertTrue(policy.demand(30000).dimmed)
        policy.onPresence(Presence.PRESENT, 30799, generation)
        assertTrue(policy.demand(30799).dimmed)
        policy.onPresence(Presence.PRESENT, 30800, generation)
        assertFalse(policy.demand(30800).dimmed)
        assertEquals(-1f, policy.demand(30800).brightness)
    }

    @Test fun absentOrUnknownBreaksPresenceConfirmation() {
        for (breakResult in listOf(Presence.ABSENT, Presence.UNKNOWN)) {
            val policy = policy()
            val generation = policy.sensingGeneration
            policy.onPresence(Presence.PRESENT, 30000, generation)
            policy.onPresence(breakResult, 30700, generation)
            policy.onPresence(Presence.PRESENT, 30800, generation)
            assertTrue(policy.demand(30800).dimmed)
            policy.onPresence(Presence.PRESENT, 31600, generation)
            assertFalse(policy.demand(31600).dimmed)
        }
    }

    @Test fun aSingleFaceResultOrAStalledDetectorNeverKeepsTheScreenBrightForever() {
        val policy = policy()
        policy.onPresence(Presence.PRESENT, 0, policy.sensingGeneration)
        assertTrue(policy.demand(30000).dimmed)
        policy.onPresence(Presence.PRESENT, 31000, policy.sensingGeneration)
        assertTrue("A long gap cannot confirm continuous presence", policy.demand(31000).dimmed)
    }

    @Test fun confirmedPresenceKeepsAwakeThenGivesThirtySecondsAfterTheLastFace() {
        val policy = policy()
        for (at in 0L..32000L step 800L) policy.onPresence(Presence.PRESENT, at, policy.sensingGeneration)
        assertFalse(policy.demand(32000).dimmed)
        policy.onPresence(Presence.ABSENT, 32200, policy.sensingGeneration)
        assertFalse(policy.demand(61999).dimmed)
        assertTrue(policy.demand(62000).dimmed)
    }

    @Test fun unknownUsesTouchIdleFallbackWithoutPretendingToBeAbsence() {
        val policy = policy()
        policy.onPresence(Presence.UNKNOWN, 10000, policy.sensingGeneration)
        assertEquals(Presence.UNKNOWN, policy.presence)
        assertFalse(policy.demand(29999).dimmed)
        assertTrue(policy.demand(30000).dimmed)
    }

    @Test fun slowSamplingConfirmsUsingTwoFreshPositiveSamplesInsteadOfATimer() {
        val policy = policy()
        policy.update(30000, options, visible.copy(hour = 23))
        policy.onPresence(Presence.PRESENT, 30000, policy.sensingGeneration)
        assertTrue(policy.demand(30800).dimmed)
        policy.onPresence(Presence.PRESENT, 32000, policy.sensingGeneration)
        assertFalse(policy.demand(32000).dimmed)
        assertEquals(2000L, policy.demand(32000).detectionIntervalMs)
    }

    @Test fun callAndMonitorDemandAlwaysWinAndFinishingRestartsTheIdlePeriod() {
        val policy = policy()
        val oldGeneration = policy.sensingGeneration
        val active = policy.update(30000, options, visible.copy(engaged = true))
        assertFalse(active.dimmed)
        assertTrue(active.keepScreenOn)
        assertNull(active.detectionIntervalMs)
        policy.onPresence(Presence.ABSENT, 31000, oldGeneration)
        assertFalse(policy.demand(90000).dimmed)
        policy.update(90000, options, visible)
        assertFalse(policy.demand(119999).dimmed)
        assertTrue(policy.demand(120000).dimmed)
    }

    @Test fun leavingTheAppStopsCameraAndReleasesItsWindowRequest() {
        val policy = policy()
        val oldGeneration = policy.sensingGeneration
        val hidden = policy.update(30000, options, visible.copy(visible = false))
        assertEquals(-1f, hidden.brightness)
        assertFalse(hidden.keepScreenOn)
        assertNull(hidden.detectionIntervalMs)
        policy.onPresence(Presence.PRESENT, 30800, oldGeneration)
        assertEquals(hidden, policy.demand(30800))
        policy.update(40000, options, visible)
        assertFalse(policy.demand(69999).dimmed)
    }

    @Test fun previousGenerationCannotWakeAResumedWindow() {
        val policy = policy()
        val oldGeneration = policy.sensingGeneration
        policy.update(1, options, visible.copy(visible = false))
        policy.update(2, options, visible)
        policy.onPresence(Presence.PRESENT, 30002, oldGeneration)
        policy.onPresence(Presence.PRESENT, 30802, oldGeneration)
        assertTrue(policy.demand(30802).dimmed)
    }

    @Test fun resultsCapturedBeforeTheLatestTouchAndOutOfOrderResultsAreIgnored() {
        val policy = policy()
        policy.interaction(30000)
        policy.onPresence(Presence.PRESENT, 29000, policy.sensingGeneration)
        policy.onPresence(Presence.PRESENT, 29800, policy.sensingGeneration)
        assertEquals(Presence.UNKNOWN, policy.presence)
        policy.onPresence(Presence.ABSENT, 31000, policy.sensingGeneration)
        policy.onPresence(Presence.PRESENT, 30500, policy.sensingGeneration)
        assertEquals(Presence.ABSENT, policy.presence)
        assertTrue(policy.demand(60000).dimmed)
    }

    @Test fun nighttimeAndUnpluggedDetectionUseTwoSecondIntervals() {
        val policy = policy()
        assertEquals(200L, policy.demand(0).detectionIntervalMs)
        for (hour in listOf(22, 23, 0, 5)) {
            assertEquals(2000L, policy.update(0, options, visible.copy(hour = hour)).detectionIntervalMs)
        }
        for (hour in listOf(6, 12, 21)) {
            assertEquals(200L, policy.update(0, options, visible.copy(hour = hour)).detectionIntervalMs)
        }
        assertEquals(2000L, policy.update(0, options, visible.copy(externalPower = false)).detectionIntervalMs)
    }

    @Test fun batteryAtTenPercentSuspendsDetectionAndIdleKeepAwake() {
        val policy = policy()
        assertEquals(200L, policy.update(0, options, visible.copy(batteryPercent = 11)).detectionIntervalMs)
        val low = policy.update(1, options, visible.copy(batteryPercent = 10))
        assertNull(low.detectionIntervalMs)
        assertFalse(low.keepScreenOn)
        assertTrue(policy.demand(30000).dimmed)
        val call = policy.update(30000, options, visible.copy(batteryPercent = 10, engaged = true))
        assertTrue(call.keepScreenOn)
        assertFalse(call.dimmed)
    }

    @Test fun trueScreenOffOrLockNeverRequestsAWakeOrStartsCamera() {
        for (environment in listOf(visible.copy(interactive = false), visible.copy(unlocked = false))) {
            val demand = policy().update(30000, options, environment)
            assertEquals(-1f, demand.brightness)
            assertFalse(demand.keepScreenOn)
            assertNull(demand.detectionIntervalMs)
        }
    }

    @Test fun rejectedCameraAndDisabledPresenceStillAllowTouchStandby() {
        val policy = policy()
        assertNull(policy.update(0, options, visible.copy(cameraAllowed = false)).detectionIntervalMs)
        assertTrue(policy.demand(30000).dimmed)
        assertNull(policy.update(30001, options.copy(presenceEnabled = false), visible).detectionIntervalMs)
        assertTrue(policy.demand(60001).dimmed)
    }

    @Test fun disablingStandbyRestoresSystemBehaviorAndReenableStartsFresh() {
        val policy = policy()
        val disabled = policy.update(30000, StandbyOptions(), visible)
        assertEquals(-1f, disabled.brightness)
        assertFalse(disabled.keepScreenOn)
        assertFalse(disabled.dimmed)
        assertNull(disabled.detectionIntervalMs)
        policy.update(40000, options, visible)
        assertFalse(policy.demand(69999).dimmed)
        assertTrue(policy.demand(70000).dimmed)
    }
}
