package com.maichimod.prototype.transport

import java.io.Closeable
import java.net.Inet4Address

internal data class ReceivedDatagram(
    val source: Inet4Address,
    val sourcePort: Int,
    val localPort: Int,
    val payload: ByteArray,
)

internal interface DatagramTransport : Closeable {
    fun send(localPort: Int, destination: Inet4Address, payload: ByteArray)
}
