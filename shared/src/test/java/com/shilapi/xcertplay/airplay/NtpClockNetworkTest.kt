package com.shilapi.xcertplay.airplay

import com.shilapi.xcertplay.network.backend.*
import java.net.InetAddress
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Test

class NtpClockNetworkTest {
    @Test fun networkOwnerCancellationStopsReceiver() {
        val network = FakeNetwork()
        val clock = NtpClock(network)
        try {
            clock.listen()
            network.endpointClosed = true
            network.incoming.put(ByteArray(0))
            assertTrue(network.cancelledReceive.await(2, TimeUnit.SECONDS))
            assertFalse(network.repeatedCancellation.await(200, TimeUnit.MILLISECONDS))
        } finally { clock.close() }
    }

    @Test fun failedReplyDoesNotStopReceiving() {
        val network = FakeNetwork().apply { failNextSend = true }
        val clock = NtpClock(network)
        try {
            clock.listen()
            network.incoming.put(ByteArray(32).apply { this[1] = 210.toByte() })
            assertTrue(network.sendFailed.await(2, TimeUnit.SECONDS))
            network.incoming.put(ByteArray(32).apply { this[1] = 210.toByte(); this[24] = 51 })
            val reply = network.outgoing.poll(2, TimeUnit.SECONDS)!!
            assertEquals(51, reply.first[8].toInt())
        } finally { clock.close() }
    }

    @Test fun requestAndResponseSendsAreSerialized() {
        val network = FakeNetwork().apply { blockFirstSend = true }
        val clock = NtpClock(network)
        try {
            clock.listen()
            clock.start(InetAddress.getByAddress(network.peer.address), network.peer.port)
            assertTrue(network.sendEntered.await(2, TimeUnit.SECONDS))
            network.incoming.put(ByteArray(32).apply { this[1] = 210.toByte() })
            assertTrue(network.received.await(2, TimeUnit.SECONDS))
            // Keep the request in flight until a competing send has a chance to run.
            network.overlap.await(200, TimeUnit.MILLISECONDS)
            network.releaseSend.countDown()
            val first = network.outgoing.poll(2, TimeUnit.SECONDS)!!
            val second = network.outgoing.poll(2, TimeUnit.SECONDS)!!
            assertEquals(setOf(210, 211), setOf(first.first[1].toInt() and 255, second.first[1].toInt() and 255))
            assertEquals(1L, network.overlap.count)
        } finally { network.releaseSend.countDown(); clock.close() }
    }

    @Test fun closeIsTerminalEvenWithSharedNetwork() {
        val network = FakeNetwork()
        val clock = NtpClock(network)
        clock.close()
        try { clock.listen(); fail("closed timing channel must not bind") }
        catch (_: IllegalStateException) { }
        assertFalse(network.closed)
    }

    @Test fun truncatedRequestIsDroppedBeforeResponding() {
        val network = FakeNetwork()
        val clock = NtpClock(network)
        try {
            clock.listen()
            network.incoming.put(ByteArray(4096).apply { this[1] = 210.toByte() })
            network.incoming.put(ByteArray(32).apply { this[1] = 210.toByte(); this[24] = 42 })
            val reply = network.outgoing.poll(2, TimeUnit.SECONDS)!!
            assertEquals(42, reply.first[8].toInt())
            assertTrue(network.outgoing.isEmpty())
        } finally { clock.close() }
    }

    @Test fun defaultSystemAdapterRespondsToLoopbackRequest() {
        val clock = NtpClock()
        DatagramSocket(null).use { phone ->
            phone.bind(InetSocketAddress(InetAddress.getByName("::1"), 0))
            phone.soTimeout = 2000
            try {
                val port = clock.listen()
                val request = ByteArray(32).apply { this[1] = 210.toByte(); this[24] = 73 }
                phone.send(DatagramPacket(request, request.size, InetAddress.getByName("::1"), port))
                val reply = DatagramPacket(ByteArray(64), 64)
                phone.receive(reply)
                assertEquals(port, reply.port)
                assertEquals(32, reply.length)
                assertEquals(73, reply.data[8].toInt())
            } finally { clock.close() }
        }
    }

    @Test fun respondsThroughInjectedEndpointAndKeepsSharedNetworkAlive() {
        val network = FakeNetwork()
        val clock = NtpClock(network)
        try {
            assertEquals(43210, clock.listen())
            val request = ByteArray(32).apply {
                this[0] = 0x80.toByte(); this[1] = 210.toByte()
                for (index in 24..31) this[index] = index.toByte()
            }
            network.incoming.put(request)
            val reply = network.outgoing.poll(2, TimeUnit.SECONDS)!!
            assertEquals(network.peer, reply.second)
            assertEquals(211, reply.first[1].toInt() and 255)
            assertArrayEquals(request.copyOfRange(24, 32), reply.first.copyOfRange(8, 16))
        } finally { clock.close() }
        assertTrue(network.endpointClosed)
        assertFalse(network.closed)
    }

    @Test fun requestsUseVirtualPeerAndBindingFailureCanBeRetried() {
        val network = FakeNetwork()
        val clock = NtpClock(network)
        network.failBind = true
        try {
            try { clock.listen(); fail("bind must fail") } catch (_: BackendFailure) { }
            network.failBind = false
            assertEquals(43210, clock.listen())
            clock.start(InetAddress.getByAddress(network.peer.address), network.peer.port)
            val request = network.outgoing.poll(2, TimeUnit.SECONDS)!!
            assertEquals(network.peer, request.second)
            assertEquals(210, request.first[1].toInt() and 255)
        } finally { clock.close() }
    }

    private class FakeNetwork : SessionNetwork {
        override val linkId = "virtual-timing"
        override val clock = MonotonicClock { System.nanoTime() / 1_000_000 }
        val peer = NetworkEndpoint(ByteArray(16).apply { this[0] = 0xfd.toByte(); this[15] = 2 }, 34567, linkId)
        val incoming = LinkedBlockingQueue<ByteArray>()
        val outgoing = LinkedBlockingQueue<Pair<ByteArray, NetworkEndpoint>>()
        var closed = false
        @Volatile var endpointClosed = false
        var failBind = false
        var blockFirstSend = false
        var failNextSend = false
        val sendFailed = CountDownLatch(1)
        val sendEntered = CountDownLatch(1)
        val releaseSend = CountDownLatch(1)
        val received = CountDownLatch(1)
        val overlap = CountDownLatch(1)
        val sending = AtomicBoolean(false)
        val cancelledReceive = CountDownLatch(1)
        val repeatedCancellation = CountDownLatch(1)
        override fun listen(local: NetworkEndpoint, backlog: Int): TcpListener = error("TCP forbidden")
        override fun connect(remote: NetworkEndpoint, deadlineMillis: Long): TcpStream = error("TCP forbidden")
        override fun bindUdp(local: NetworkEndpoint): UdpEndpoint {
            check(local.linkId == linkId && local.port == 0)
            if (failBind) throw BackendFailure(BackendFailure.Kind.RESOURCE_LIMIT)
            return object : UdpEndpoint {
                override val localEndpoint = NetworkEndpoint(local.address, 43210, linkId)
                override fun send(bytes: ByteArray, destination: NetworkEndpoint, deadlineMillis: Long) {
                    if (!sending.compareAndSet(false, true)) {
                        overlap.countDown()
                        throw BackendFailure(BackendFailure.Kind.RESOURCE_LIMIT)
                    }
                    try {
                        if (failNextSend) {
                            failNextSend = false
                            sendFailed.countDown()
                            throw BackendFailure(BackendFailure.Kind.RESOURCE_LIMIT)
                        }
                        if (blockFirstSend && sendEntered.count != 0L) {
                            sendEntered.countDown()
                            check(releaseSend.await(3, TimeUnit.SECONDS))
                        }
                        outgoing.put(bytes.copyOf() to destination)
                    } finally { sending.set(false) }
                }
                override fun receive(bytes: ByteArray, deadlineMillis: Long): ReceivedDatagram {
                    val packet = if (endpointClosed) ByteArray(0) else incoming.take()
                    received.countDown()
                    if (endpointClosed) {
                        if (cancelledReceive.count == 0L) repeatedCancellation.countDown()
                        cancelledReceive.countDown()
                        throw BackendFailure(BackendFailure.Kind.CANCELLED)
                    }
                    val copied = minOf(bytes.size, packet.size)
                    packet.copyInto(bytes, endIndex = copied)
                    return ReceivedDatagram(peer, packet.size, copied)
                }
                override fun close() { endpointClosed = true; incoming.offer(ByteArray(0)) }
            }
        }
        override fun close() { closed = true }
    }
}
