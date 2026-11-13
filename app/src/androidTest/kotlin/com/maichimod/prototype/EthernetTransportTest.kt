package com.maichimod.prototype

import android.content.Context
import android.net.ConnectivityManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maichimod.prototype.protocol.ipv4Address
import com.maichimod.prototype.transport.EthernetTransport
import com.maichimod.prototype.transport.WiredLink
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EthernetTransportTest {
    @Test fun subnetBroadcastIsReceivedExactlyOnce() {
        assumeTrue(android.os.Build.HARDWARE == "ranchu")
        val context = ApplicationProvider.getApplicationContext<Context>()
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val network = requireNotNull(connectivity.activeNetwork)
        val address = requireNotNull(connectivity.getLinkProperties(network)).linkAddresses
            .first { it.address is java.net.Inet4Address && it.prefixLength in 1..30 }
        val local = address.address as java.net.Inet4Address
        val broadcast = com.maichimod.prototype.protocol.DEMO_PROFILE.copy(localAddress = local, prefixLength = address.prefixLength).broadcastAddress
        val payload = java.util.UUID.randomUUID().toString().toByteArray()
        val first = CountDownLatch(1)
        val duplicate = CountDownLatch(1)
        val count = java.util.concurrent.atomic.AtomicInteger()
        DatagramSocket(null).use { peer ->
            peer.reuseAddress = true; peer.broadcast = true
            network.bindSocket(peer)
            peer.bind(InetSocketAddress(local, 10008))
            EthernetTransport(WiredLink(network, local, address.prefixLength, null), {
                if (it.payload.contentEquals(payload)) {
                    if (count.incrementAndGet() == 1) first.countDown() else duplicate.countDown()
                }
            }, {}).use {
                peer.send(DatagramPacket(payload, payload.size, broadcast, 10008))
                assertTrue("subnet broadcast not received", first.await(2, TimeUnit.SECONDS))
                assertFalse("broadcast delivered more than once", duplicate.await(200, TimeUnit.MILLISECONDS))
                assertEquals(1, count.get())
            }
        }
    }

    @Test fun failedBindingAndNormalCloseReleaseEverySocket() {
        assumeTrue(android.os.Build.HARDWARE == "ranchu")
        val context = ApplicationProvider.getApplicationContext<Context>()
        val network = requireNotNull(context.getSystemService(ConnectivityManager::class.java).activeNetwork)
        val selected = requireNotNull(ipv4Address("127.0.0.2"))
        val created = mutableListOf<DatagramSocket>()
        val link = WiredLink(network, selected, 8, null)
        DatagramSocket(InetSocketAddress(selected, 10000)).use {
            try {
                EthernetTransport(link, {}, {}, createSocket = { DatagramSocket(null).also(created::add) })
                fail("occupied session port was accepted")
            } catch (_: java.net.SocketException) { }
            assertTrue(created.isNotEmpty())
            assertTrue(created.all { it.isClosed })
        }
        created.clear()
        EthernetTransport(link, {}, {}, createSocket = { DatagramSocket(null).also(created::add) }).use { }
        assertTrue(created.all { it.isClosed })
    }

    @Test fun discoveryRepliesUseSelectedAddressAndBothReceivePathsRemainOpen() {
        assumeTrue(android.os.Build.HARDWARE == "ranchu")
        val context = ApplicationProvider.getApplicationContext<Context>()
        val network = requireNotNull(context.getSystemService(ConnectivityManager::class.java).activeNetwork)
        val selected = requireNotNull(ipv4Address("127.0.0.2"))
        val peerAddress = requireNotNull(ipv4Address("127.0.0.1"))
        val received = CountDownLatch(2)
        val delivered = java.util.concurrent.CopyOnWriteArrayList<Int>()
        val duplicate = CountDownLatch(1)
        DatagramSocket(null).use { peer ->
            peer.reuseAddress = true
            peer.bind(InetSocketAddress(peerAddress, 10008))
            peer.soTimeout = 2000
            // Real loopback sockets model a secondary interface address without changing a device.
            EthernetTransport(WiredLink(network, selected, 8, null), {
                delivered.add(it.payload.single().toInt())
                if (delivered.size > 2) duplicate.countDown()
                received.countDown()
            }, {},
                createSocket = { DatagramSocket(null).apply { reuseAddress = true } }).use { transport ->
                peer.send(DatagramPacket(byteArrayOf(1), 1, selected, 10008))
                peer.send(DatagramPacket(byteArrayOf(2), 1, requireNotNull(ipv4Address("127.0.0.3")), 10008))
                assertTrue("configured-address or wildcard receive path lost", received.await(2, TimeUnit.SECONDS))
                transport.send(10008, peerAddress, byteArrayOf(3))
                val reply = DatagramPacket(ByteArray(32), 32)
                peer.receive(reply)
                assertEquals("discovery response used the primary address instead of the configured address", selected, reply.address)
                assertEquals(10008, reply.port)
                assertEquals(3, reply.data[0].toInt())
                assertFalse(duplicate.await(200, TimeUnit.MILLISECONDS))
                assertEquals(listOf(1, 2), delivered.sorted())
            }
        }
    }
}
