package com.maichimod.prototype.transport

import com.maichimod.prototype.protocol.ipv4Address
import java.io.InputStream
import java.net.Inet4Address
import java.util.zip.GZIPInputStream

internal data class CaptureRow(
    val timeMs: Long,
    val incoming: Boolean,
    val localPort: Int,
    val remote: Inet4Address,
    val remotePort: Int,
    val payload: ByteArray,
) {
    fun received() = ReceivedDatagram(remote, remotePort, localPort, payload)
}

internal object CaptureFixtures {
    const val DIRECTORY = "maichi-local"
    const val MONITOR_RX = "monitor-rx.tsv.gz"
    const val BASEMENT_ONE_RX = "monitor-basement-1-rx.tsv.gz"
    const val BASEMENT_ONE_BUSY_RX = "monitor-basement-1-busy-rx.tsv.gz"
    const val BASEMENT_ONE_TX = "monitor-basement-1-tx.tsv.gz"
    const val BASEMENT_TWO_RX = "monitor-basement-2-rx.tsv.gz"
    const val MONITOR_RJ4_RX = "monitor-rj4-rx.tsv.gz"
    const val MONITOR_TX = "monitor-tx.tsv.gz"
    const val ELEVATOR_TX = "elevator-tx.tsv.gz"
    const val ELEVATOR_RX = "elevator-rx.tsv.gz"
    const val CALL_RX = "call-rx.tsv.gz"
    const val CALL_TX = "call-tx.tsv.gz"

    fun read(input: InputStream): List<CaptureRow> = GZIPInputStream(input).bufferedReader(Charsets.US_ASCII).useLines { rows ->
        rows.map { line ->
            val fields = line.split('\t', limit = 6)
            require(fields.size == 6 && (fields[1] == "rx" || fields[1] == "tx"))
            val hex = fields[5]
            require(hex.length % 2 == 0)
            val bytes = ByteArray(hex.length / 2) { index ->
                val high = hex[index * 2].digitToInt(16)
                val low = hex[index * 2 + 1].digitToInt(16)
                ((high shl 4) or low).toByte()
            }
            CaptureRow(
                fields[0].toLong(), fields[1] == "rx", fields[2].toInt(),
                requireNotNull(ipv4Address(fields[3])), fields[4].toInt(), bytes,
            )
        }.toList()
    }
}
