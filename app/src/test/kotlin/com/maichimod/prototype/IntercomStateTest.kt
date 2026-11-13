package com.maichimod.prototype

import com.maichimod.prototype.session.CallStatus
import org.junit.Assert.*
import org.junit.Test

class IntercomStateTest {
    @Test fun disabledReceptionNeverOffersNetworkCommands() {
        val state = IntercomState(link = LinkStatus.WIRED_CONNECTED, enabled = false)
        assertFalse(state.canMonitor)
        assertFalse(state.canCallElevator)
    }

    @Test fun incomingCallPreemptsMonitoringButPreservesListening() {
        val state = IntercomState(link = LinkStatus.WIRED_CONNECTED, call = CallStatus.RINGING)
        assertTrue(state.ready)
        assertTrue(state.inCall)
        assertFalse(state.canMonitor)
    }

    @Test fun identicalSequenceFromAnotherProcessIsNotTheSameCall() {
        assertNotEquals(CallKey("old-process", 7), CallKey("new-process", 7))
    }

    @Test fun unavailableConnectionCannotOfferCommands() {
        for (link in listOf(LinkStatus.WIRED_WAITING, LinkStatus.WIRED_NEEDS_IDS,
                LinkStatus.WIRED_PROFILE_MISMATCH, LinkStatus.WIRED_ERROR, LinkStatus.SAMPLE_MISSING)) {
            assertFalse(IntercomState(link = link).canMonitor)
            assertFalse(IntercomState(link = link).canCallElevator)
        }
    }
}
