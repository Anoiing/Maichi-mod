package com.maichimod.prototype

import android.content.SharedPreferences
import com.maichimod.prototype.protocol.TerminalProfile
import com.maichimod.prototype.protocol.ipv4Address
import com.maichimod.prototype.protocol.isTerminalId
import com.maichimod.prototype.transport.CaptureFixtures
import java.net.Inet4Address
import java.util.Locale

internal enum class ConnectionField(
    val key: String,
    val label: Int,
    val help: Int,
    val error: Int,
    val emptyValue: Int = R.string.not_configured,
    val affectsSession: Boolean = true,
) {
    INDOOR_ID("indoorId", R.string.indoor_id, R.string.indoor_id_help, R.string.invalid_terminal_id),
    LOCAL_IP("localIp", R.string.local_ip, R.string.local_ip_help, R.string.invalid_ipv4, R.string.read_from_ethernet),
    SUBNET_MASK("subnetMask", R.string.subnet_mask, R.string.subnet_mask_help, R.string.invalid_subnet_mask,
        R.string.read_from_ethernet),
    GATEWAY("gateway", R.string.gateway, R.string.gateway_help, R.string.invalid_ipv4, affectsSession = false),
    INDOOR_MAC("indoorMac", R.string.indoor_mac, R.string.indoor_mac_help, R.string.invalid_mac, affectsSession = false),
    PEER_ID("peerId", R.string.peer_id, R.string.peer_id_help, R.string.invalid_terminal_id),
    PEER_IP("peerIp", R.string.peer_ip, R.string.peer_ip_help, R.string.invalid_ipv4, R.string.discover_automatically),
    CENTER_IP("centerIp", R.string.center_ip, R.string.center_ip_help, R.string.invalid_ipv4, affectsSession = false),
    BASEMENT_ONE_ID("basementOneId", R.string.basement_one_id, R.string.peer_id_help, R.string.invalid_terminal_id),
    BASEMENT_ONE_IP("basementOneIp", R.string.basement_one_ip, R.string.peer_ip_help, R.string.invalid_ipv4,
        R.string.discover_automatically),
    BASEMENT_TWO_ID("basementTwoId", R.string.basement_two_id, R.string.peer_id_help, R.string.invalid_terminal_id),
    BASEMENT_TWO_IP("basementTwoIp", R.string.basement_two_ip, R.string.peer_ip_help, R.string.invalid_ipv4,
        R.string.discover_automatically),
    ;

    fun normalize(raw: String): String? {
        val input = raw.trim()
        if (input.isEmpty()) return ""
        return when (this) {
            INDOOR_ID -> terminalId(input, 'S')
            PEER_ID, BASEMENT_ONE_ID, BASEMENT_TWO_ID -> terminalId(input, 'M')
            SUBNET_MASK -> subnetPrefix(input)?.let(::maskForPrefix)
            INDOOR_MAC -> macAddress(input)
            LOCAL_IP, GATEWAY, PEER_IP, CENTER_IP, BASEMENT_ONE_IP, BASEMENT_TWO_IP -> ipv4Address(input)?.takeIf { address ->
                (address.address[0].toInt() and 255) in 1..223 &&
                    !address.isLoopbackAddress && !address.isLinkLocalAddress
            }?.hostAddress
        }
    }

    fun display(value: String): String = when (this) {
        INDOOR_ID -> value.removePrefix("S")
        PEER_ID, BASEMENT_ONE_ID, BASEMENT_TWO_ID -> value.removePrefix("M")
        else -> value
    }
}

internal enum class MonitorSource(
    val label: Int,
    val tile: Int,
    val idField: ConnectionField,
    val ipField: ConnectionField,
    val fixture: String,
) {
    UNIT(R.string.unit_door_station, R.id.unit_monitor, ConnectionField.PEER_ID, ConnectionField.PEER_IP,
        CaptureFixtures.MONITOR_RX),
    BASEMENT_ONE(R.string.basement_door_station_one, R.id.basement_one_monitor,
        ConnectionField.BASEMENT_ONE_ID, ConnectionField.BASEMENT_ONE_IP, CaptureFixtures.BASEMENT_ONE_RX),
    BASEMENT_TWO(R.string.basement_door_station_two, R.id.basement_two_monitor,
        ConnectionField.BASEMENT_TWO_ID, ConnectionField.BASEMENT_TWO_IP, CaptureFixtures.BASEMENT_TWO_RX),
}

internal data class ConnectionOptions(
    val wired: Boolean = false,
    val simulateTimeout: Boolean = false,
    private val values: Map<ConnectionField, String> = emptyMap(),
    val autoHangUpAfterUnlock: Boolean = true,
) {
    operator fun get(field: ConnectionField): String = values[field].orEmpty()

    val hasTerminalIds: Boolean
        get() = hasMonitorIds(MonitorSource.UNIT)

    fun hasMonitorIds(source: MonitorSource): Boolean =
        get(ConnectionField.INDOOR_ID).isTerminalId('S') && get(source.idField).isTerminalId('M')

    val preferredLocalIp: Inet4Address?
        get() = ipv4Address(get(ConnectionField.LOCAL_IP))

    fun withField(field: ConnectionField, input: String): ConnectionOptions? {
        val value = field.normalize(input) ?: return null
        return copy(values = if (value.isEmpty()) values - field else values + (field to value))
    }

    fun matchesEthernet(address: Inet4Address, prefix: Int): Boolean {
        val expectedIp = preferredLocalIp
        val expectedPrefix = subnetPrefix(get(ConnectionField.SUBNET_MASK))
        return (expectedIp == null || expectedIp == address) && (expectedPrefix == null || expectedPrefix == prefix)
    }

    @JvmOverloads
    fun terminalProfile(address: Inet4Address, prefix: Int, source: MonitorSource = MonitorSource.UNIT): TerminalProfile = TerminalProfile(
        indoorId = get(ConnectionField.INDOOR_ID),
        peerId = get(source.idField),
        localAddress = address,
        prefixLength = prefix,
        expectedPeerAddress = ipv4Address(get(source.ipField)),
    )

    fun requiresReconnect(previous: ConnectionOptions): Boolean =
        wired != previous.wired || if (wired) {
            ConnectionField.entries.any { it.affectsSession && this[it] != previous[it] }
        } else {
            simulateTimeout != previous.simulateTimeout
        }

    fun save(preferences: SharedPreferences) {
        val editor = preferences.edit()
            .putBoolean("wired", wired)
            .putBoolean("simulateTimeout", simulateTimeout)
            .putBoolean("autoHangUpAfterUnlock", autoHangUpAfterUnlock)
        for (field in ConnectionField.entries) editor.putString(field.key, this[field])
        editor.apply()
    }

    companion object {
        fun load(preferences: SharedPreferences, defaultWired: Boolean): ConnectionOptions = ConnectionOptions(
            wired = preferences.getBoolean("wired", defaultWired),
            simulateTimeout = preferences.getBoolean("simulateTimeout", false),
            autoHangUpAfterUnlock = preferences.getBoolean("autoHangUpAfterUnlock", true),
            values = buildMap {
                for (field in ConnectionField.entries) {
                    val value = field.normalize(preferences.getString(field.key, "").orEmpty())
                    if (!value.isNullOrEmpty()) put(field, value)
                }
            },
        )
    }
}

private fun terminalId(input: String, prefix: Char): String? {
    val upper = input.uppercase(Locale.ROOT)
    val id = if (upper.length == 11) prefix + upper else upper
    return id.takeIf { it.isTerminalId(prefix) }
}

private fun macAddress(input: String): String? {
    val upper = input.uppercase(Locale.ROOT).replace('-', ':')
    return when {
        upper.matches(Regex("[0-9A-F]{12}")) -> upper.chunked(2).joinToString(":")
        upper.matches(Regex("(?:[0-9A-F]{2}:){5}[0-9A-F]{2}")) -> upper
        else -> null
    }
}

internal fun subnetPrefix(input: String): Int? {
    val cidr = input.removePrefix("/")
    if (cidr.isNotEmpty() && cidr.all { it in '0'..'9' }) {
        return cidr.toIntOrNull()?.takeIf { it in 1..30 }
    }
    val mask = ipv4Address(input) ?: return null
    val bits = mask.address.fold(0) { value, byte -> (value shl 8) or (byte.toInt() and 255) }
    val prefix = Integer.bitCount(bits)
    return prefix.takeIf { it in 1..30 && bits == (-1 shl (32 - it)) }
}

internal fun maskForPrefix(prefix: Int): String {
    val bits = -1 shl (32 - prefix)
    return (24 downTo 0 step 8).joinToString(".") { ((bits ushr it) and 255).toString() }
}
