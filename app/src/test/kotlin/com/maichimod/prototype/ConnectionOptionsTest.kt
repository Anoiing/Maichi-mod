package com.maichimod.prototype

import com.maichimod.prototype.protocol.ipv4Address
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionOptionsTest {
    @Test
    fun fieldValuesAreNormalizedAndInvalidInputDoesNotReplaceTheProfile() {
        val options = optionsWith(
            ConnectionField.INDOOR_ID to " 00012345678 ",
            ConnectionField.PEER_ID to "m00000001234",
            ConnectionField.LOCAL_IP to "192.0.2.010",
            ConnectionField.SUBNET_MASK to "/20",
            ConnectionField.INDOOR_MAC to "02-00-5e-00-53-01",
        )
        assertEquals("S00012345678", options[ConnectionField.INDOOR_ID])
        assertEquals("M00000001234", options[ConnectionField.PEER_ID])
        assertEquals("192.0.2.10", options[ConnectionField.LOCAL_IP])
        assertEquals("255.255.240.0", options[ConnectionField.SUBNET_MASK])
        assertEquals("02:00:5E:00:53:01", options[ConnectionField.INDOOR_MAC])
        assertNull(options.withField(ConnectionField.INDOOR_ID, "M00012345678"))
        assertNull(options.withField(ConnectionField.LOCAL_IP, "192.0.2.999"))
        assertNull(options.withField(ConnectionField.SUBNET_MASK, "255.0.255.0"))
        assertNull(options.withField(ConnectionField.SUBNET_MASK, "/31"))
        assertNull(options.withField(ConnectionField.INDOOR_MAC, "02:ZZ:5E:00:53:01"))
    }

    @Test
    fun savedValuesSelectTheWiredProfileAndEmptyNetworkFieldsUseTheLink() {
        val address = requireNotNull(ipv4Address("192.0.2.10"))
        val empty = ConnectionOptions(wired = true)
        assertFalse(empty.hasTerminalIds)
        assertTrue(empty.matchesEthernet(address, 24))

        val configured = optionsWith(
            ConnectionField.INDOOR_ID to "00012345678",
            ConnectionField.PEER_ID to "00000001234",
            ConnectionField.LOCAL_IP to "192.0.2.10",
            ConnectionField.SUBNET_MASK to "255.255.255.0",
            ConnectionField.PEER_IP to "192.0.2.20",
        )
        assertTrue(configured.hasTerminalIds)
        assertTrue(configured.matchesEthernet(address, 24))
        assertFalse(configured.matchesEthernet(address, 20))
        assertFalse(configured.matchesEthernet(requireNotNull(ipv4Address("192.0.2.12")), 24))
        val profile = configured.terminalProfile(address, 24)
        assertEquals("S00012345678", profile.indoorId)
        assertEquals("M00000001234", profile.peerId)
        assertEquals("192.0.2.20", profile.expectedPeerAddress?.hostAddress)
        assertEquals("192.0.2.255", profile.broadcastAddress.hostAddress)
        val cleared = requireNotNull(configured.withField(ConnectionField.PEER_IP, " "))
        assertNull(cleared.terminalProfile(address, 24).expectedPeerAddress)
    }

    @Test
    fun eachMonitorUsesItsOwnDoorStationWithoutChangingTheUnitElevatorProfile() {
        val options = optionsWith(
            ConnectionField.INDOOR_ID to "00000000001",
            ConnectionField.PEER_ID to "00000000002",
            ConnectionField.PEER_IP to "192.0.2.20",
            ConnectionField.BASEMENT_ONE_ID to "00000000003",
            ConnectionField.BASEMENT_ONE_IP to "192.0.2.21",
            ConnectionField.BASEMENT_TWO_ID to "m00000000004",
        )
        val local = requireNotNull(ipv4Address("192.0.2.10"))
        assertTrue(MonitorSource.entries.all(options::hasMonitorIds))
        assertEquals("M00000000003", options.terminalProfile(local, 24, MonitorSource.BASEMENT_ONE).peerId)
        assertEquals("192.0.2.21", options.terminalProfile(local, 24, MonitorSource.BASEMENT_ONE).expectedPeerAddress?.hostAddress)
        assertEquals("M00000000004", options.terminalProfile(local, 24, MonitorSource.BASEMENT_TWO).peerId)
        assertNull(options.terminalProfile(local, 24, MonitorSource.BASEMENT_TWO).expectedPeerAddress)
        assertEquals("M00000000002", options.terminalProfile(local, 24).peerId)
        assertEquals("192.0.2.20", options.terminalProfile(local, 24).expectedPeerAddress?.hostAddress)
        assertFalse(optionsWith(ConnectionField.INDOOR_ID to "00000000001").hasMonitorIds(MonitorSource.BASEMENT_ONE))
        assertNull(options.withField(ConnectionField.BASEMENT_ONE_ID, "S00000000003"))
        assertNull(options.withField(ConnectionField.BASEMENT_TWO_IP, "127.0.0.1"))
    }

    @Test
    fun editingRecordsKeepsTheCurrentSessionAndModeChangesReconnect() {
        val wired = ConnectionOptions(wired = true)
        val record = requireNotNull(wired.withField(ConnectionField.CENTER_IP, "192.0.2.30"))
        assertFalse(record.requiresReconnect(wired))
        assertTrue(requireNotNull(wired.withField(ConnectionField.LOCAL_IP, "192.0.2.10")).requiresReconnect(wired))
        val sample = wired.copy(wired = false)
        assertTrue(sample.requiresReconnect(wired))
        assertTrue(sample.copy(simulateTimeout = true).requiresReconnect(sample))
        assertFalse(requireNotNull(sample.withField(ConnectionField.LOCAL_IP, "192.0.2.10")).requiresReconnect(sample))
    }

    private fun optionsWith(vararg fields: Pair<ConnectionField, String>): ConnectionOptions =
        fields.fold(ConnectionOptions(wired = true)) { options, (field, value) ->
            requireNotNull(options.withField(field, value))
        }

    @Test
    fun automaticHangUpDefaultsOnAndDoesNotReconnectTheNetwork() {
        for (wired in listOf(false, true)) {
            val options = ConnectionOptions(wired = wired)
            assertTrue(options.autoHangUpAfterUnlock)
            val manual = options.copy(autoHangUpAfterUnlock = false)
            assertFalse(manual.autoHangUpAfterUnlock)
            assertFalse(manual.requiresReconnect(options))
            assertFalse(options.requiresReconnect(manual))
        }
    }
}
