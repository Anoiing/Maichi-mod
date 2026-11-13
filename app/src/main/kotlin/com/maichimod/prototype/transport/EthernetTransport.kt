package com.maichimod.prototype.transport

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import com.maichimod.prototype.protocol.MaichiPackets
import java.io.Closeable
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.SocketException
import java.util.concurrent.atomic.AtomicBoolean

internal data class WiredLink(
    val network: Network,
    val address: Inet4Address,
    val prefixLength: Int,
    val gateway: Inet4Address?,
)

internal class EthernetLinks(
    context: Context,
    handler: Handler,
    private val preferredAddress: Inet4Address?,
    private val onChange: (WiredLink?) -> Unit,
) : Closeable {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val available = linkedMapOf<Network, WiredLink>()
    private var selected: WiredLink? = null
    private var closed = false
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) {
            if (closed) return
            val ipv4Addresses = properties.linkAddresses.filter { linkAddress ->
                val address = linkAddress.address
                address is Inet4Address && !address.isAnyLocalAddress && !address.isLoopbackAddress &&
                    !address.isLinkLocalAddress && linkAddress.prefixLength in 1..30
            }
            val ipv4 = ipv4Addresses.firstOrNull { it.address == preferredAddress } ?: ipv4Addresses.firstOrNull()
            if (ipv4 == null) {
                available.remove(network)
            } else {
                val gateway = properties.routes.firstOrNull {
                    val address = it.gateway
                    it.isDefaultRoute && address is Inet4Address && !address.isAnyLocalAddress
                }?.gateway as? Inet4Address
                available[network] = WiredLink(network, ipv4.address as Inet4Address, ipv4.prefixLength, gateway)
            }
            selectLink()
        }

        override fun onLost(network: Network) {
            if (closed) return
            available.remove(network)
            selectLink()
        }
    }

    init {
        val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET).build()
        connectivity.registerNetworkCallback(request, callback, handler)
    }

    override fun close() {
        if (closed) return
        closed = true
        connectivity.unregisterNetworkCallback(callback)
    }

    private fun selectLink() {
        val next = available.values.firstOrNull { it.address == preferredAddress }
            ?: selected?.let { available[it.network] } ?: available.values.firstOrNull()
        if (next == selected) return
        selected = next
        onChange(next)
    }
}

internal class EthernetTransport(
    link: WiredLink,
    private val onReceive: (ReceivedDatagram) -> Unit,
    private val onFailure: (IOException) -> Unit,
) : DatagramTransport {
    private val closed = AtomicBoolean(false)
    private val sockets = openSockets(link)

    init {
        sockets.forEach { (port, socket) ->
            Thread({ receive(port, socket) }, "Maichi-UDP-$port").start()
        }
    }

    override fun send(localPort: Int, destination: Inet4Address, payload: ByteArray) {
        val socket = sockets[localPort] ?: throw SocketException("Unknown UDP port $localPort")
        socket.send(DatagramPacket(payload, payload.size, destination, localPort))
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) sockets.values.forEach(DatagramSocket::close)
    }

    private fun receive(port: Int, socket: DatagramSocket) {
        val buffer = ByteArray(65_507)
        val packet = DatagramPacket(buffer, buffer.size)
        try {
            while (!closed.get()) {
                packet.length = buffer.size
                socket.receive(packet)
                val source = packet.address as? Inet4Address ?: continue
                onReceive(ReceivedDatagram(source, packet.port, port, buffer.copyOf(packet.length)))
            }
        } catch (failure: IOException) {
            if (!closed.get()) onFailure(failure)
        }
    }

    private fun openSockets(link: WiredLink): Map<Int, DatagramSocket> {
        val opened = linkedMapOf<Int, DatagramSocket>()
        try {
            for (port in listOf(MaichiPackets.DISCOVERY_PORT, MaichiPackets.SESSION_PORT)) {
                val socket = DatagramSocket(null)
                opened[port] = socket
                link.network.bindSocket(socket)
                socket.broadcast = port == MaichiPackets.DISCOVERY_PORT
                // Indoor discovery arrives at the subnet broadcast address, before the unicast call.
                socket.bind(if (port == MaichiPackets.DISCOVERY_PORT) InetSocketAddress("0.0.0.0", port)
                    else InetSocketAddress(link.address, port))
            }
            return opened
        } catch (failure: Exception) {
            opened.values.forEach(DatagramSocket::close)
            throw failure
        }
    }
}
