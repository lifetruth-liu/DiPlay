package com.shilapi.xcertplay.network.backend

import com.shilapi.xcertplay.network.backend.lwip.LwipSessionNetwork
import com.shilapi.xcertplay.network.backend.lwip.LwipPrototype
import com.shilapi.xcertplay.network.backend.lwip.NativePrototypeCore
import com.shilapi.xcertplay.network.backend.lwip.CoreAttempt
import com.shilapi.xcertplay.network.backend.lwip.coreCommandClock
import com.shilapi.xcertplay.network.backend.system.SystemSessionNetwork
import com.shilapi.xcertplay.transport.NcmSendResult
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.net.DatagramSocket
import java.net.DatagramPacket
import java.net.InetAddress
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** Identical public API assertions on Android sockets and the actual JNI core.
 * Local ownership cases use a disconnected port. UDP traffic uses a kernel socket
 * peer for system and a deterministic Ethernet peer for JNI; this does not replace
 * the independent kernel interoperability harness for the native endpoint API.
 */
@RunWith(Parameterized::class)
class SessionNetworkDeviceContractTest(private val backend: String) {
    @Test fun datagramPayloadSourceTruncationAndBoundaries() {
        udpPeer().use { fixture ->
            val network = fixture.network
            network.bindUdp(NetworkEndpoint(fixture.localAddress, 0, network.linkId)).use { receiver ->
                val deadline = { network.clock.nowMillis() + 3000 }
                for ((size, capacity) in listOf(0 to 16, 1 to 16, 1200 to 1200, 1200 to 17, 19 to 0,
                    8192 to 8192, 65527 to 65527, 65527 to 17)) {
                    val payload = ByteArray(size) { (it * 31 + size).toByte() }
                    fixture.send(receiver.localEndpoint.port, payload)
                    // A following datagram must not be merged into the first or discarded on truncation.
                    fixture.send(receiver.localEndpoint.port, byteArrayOf(42, 43))
                    val target = ByteArray(capacity) { 0x55 }
                    val received = receiver.receive(target, deadline())
                    assertEquals(fixture.source, received.source)
                    assertEquals(size, received.originalLength)
                    assertEquals(minOf(size, capacity), received.copiedLength)
                    assertEquals(size > capacity, received.truncated)
                    assertArrayEquals(payload.copyOf(minOf(size, capacity)), target.copyOf(received.copiedLength))
                    for (index in received.copiedLength until target.size) assertEquals(0x55.toByte(), target[index])
                    val next = ByteArray(8)
                    val following = receiver.receive(next, deadline())
                    assertEquals(fixture.source, following.source)
                    assertEquals(2, following.originalLength)
                    assertEquals(2, following.copiedLength)
                    assertFalse(following.truncated)
                    assertArrayEquals(byteArrayOf(42, 43), next.copyOf(2))
                }
                for (size in listOf(0, 1, 1200, 8192, 65487, 65488, 65527)) {
                    val payload = ByteArray(size) { (it * 17 + 3).toByte() }
                    try { receiver.send(payload, fixture.destination, deadline()) }
                    catch (failure: BackendFailure) { throw AssertionError("UDP send payload=$size backend=$backend", failure) }
                    fixture.assertSent(receiver.localEndpoint.port, payload)
                }
                assertThrows(IllegalArgumentException::class.java) {
                    receiver.send(ByteArray(65528), fixture.source, deadline())
                }
            }
        }
    }

    private interface UdpPeer : java.io.Closeable {
        val network: SessionNetwork
        val localAddress: ByteArray
        val source: NetworkEndpoint
        val destination: NetworkEndpoint
        fun send(destinationPort: Int, payload: ByteArray)
        fun assertSent(sourcePort: Int, payload: ByteArray)
    }

    private fun udpPeer(): UdpPeer {
        if (backend == "system") {
            val network = network()
            val address = InetAddress.getByName("::1")
            val socket = DatagramSocket(0, address)
            socket.soTimeout = 3000
            return object : UdpPeer {
                override val network = network
                override val localAddress = address.address
                override val source = NetworkEndpoint(address.address, socket.localPort, network.linkId)
                override val destination get() = source
                override fun send(destinationPort: Int, payload: ByteArray) {
                    socket.send(DatagramPacket(payload, payload.size, address, destinationPort))
                }
                override fun assertSent(sourcePort: Int, payload: ByteArray) {
                    val packet = DatagramPacket(ByteArray(65535), 65535)
                    socket.receive(packet)
                    assertEquals(sourcePort, packet.port)
                    assertArrayEquals(address.address, packet.address.address)
                    assertArrayEquals(payload, packet.data.copyOfRange(packet.offset, packet.offset + packet.length))
                }
                override fun close() { try { socket.close() } finally { network.close() } }
            }
        }
        val incoming = LinkedBlockingQueue<ByteArray>(32)
        val outgoing = LinkedBlockingQueue<ByteArray>(64)
        val mac = byteArrayOf(2, 0, 0, 0, 0, 1)
        val ip = ByteArray(16).also { it[0] = 0xfe.toByte(); it[1] = 0x80.toByte(); it[15] = 1 }
        val peer = ip.copyOf().also { it[15] = 2 }
        val port = object : EthernetFramePort {
            override val hostMac = mac.copyOf()
            override fun send(frame: ByteArray, timeoutMillis: Int): NcmSendResult {
                check(outgoing.offer(frame.copyOf(), timeoutMillis.toLong(), TimeUnit.MILLISECONDS))
                return NcmSendResult.Sent
            }
            override fun recv(timeoutMillis: Long) = incoming.poll(timeoutMillis, TimeUnit.MILLISECONDS)
            override fun close() { incoming.offer(ByteArray(14)) }
        }
        val base = coreCommandClock()
        val offset = AtomicLong()
        val clock = MonotonicClock { base.nowMillis() + offset.get() }
        val runner = LwipPrototype(port, commandClock = clock) {
            NativePrototypeCore.create(mac, ip, 1500, echoServices = false, clockMillis = clock::nowMillis)
        }
        val network = LwipSessionNetwork.start(runner, "udp-contract")
        try {
            // Finish DAD using owner-thread timer polling, without waiting on wall-clock sleeps.
            repeat(16) { offset.addAndGet(250); runner.executeCommand { it.poll(); CoreAttempt.Ready(Unit) } }
        } catch (error: Throwable) { network.close(); throw error }
        return object : UdpPeer {
            override val network = network
            override val localAddress = ip.copyOf()
            override val source = NetworkEndpoint(peer, 50000, network.linkId)
            // Multicast avoids coupling this UDP contract to neighbor-cache implementation.
            override val destination = NetworkEndpoint(ByteArray(16).also {
                it[0] = 0xff.toByte(); it[1] = 2; it[15] = 1
            }, 50000, network.linkId)
            override fun assertSent(sourcePort: Int, payload: ByteArray) {
                val assembled = ByteArray(payload.size + 8)
                val seen = BooleanArray(assembled.size)
                var copied = 0
                var last = false
                var identifier: List<Byte>? = null
                fun validateUdp(udp: ByteArray) {
                    fun read16(at: Int) = ((udp[at].toInt() and 255) shl 8) or (udp[at + 1].toInt() and 255)
                    assertEquals(sourcePort, read16(0)); assertEquals(destination.port, read16(2))
                    assertEquals(payload.size + 8, read16(4))
                    assertArrayEquals(payload, udp.copyOfRange(8, udp.size))
                    assertNotEquals(0, read16(6))
                    var sum = 17 + udp.size
                    for (address in listOf(ip, destination.address)) {
                        for (at in address.indices step 2) sum += ((address[at].toInt() and 255) shl 8) or (address[at + 1].toInt() and 255)
                    }
                    for (at in udp.indices step 2) {
                        sum += (udp[at].toInt() and 255) shl 8
                        if (at + 1 < udp.size) sum += udp[at + 1].toInt() and 255
                    }
                    while (sum ushr 16 != 0) sum = (sum and 65535) + (sum ushr 16)
                    assertEquals(65535, sum)
                }
                val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
                while (System.nanoTime() < end) {
                    val frame = outgoing.poll(100, TimeUnit.MILLISECONDS) ?: continue
                    if (frame.size < 62 || frame[20].toInt() !in listOf(17, 44)) continue
                    fun read16(at: Int) = ((frame[at].toInt() and 255) shl 8) or (frame[at + 1].toInt() and 255)
                    assertEquals(0x86dd, read16(12))
                    assertArrayEquals(ip, frame.copyOfRange(22, 38))
                    assertArrayEquals(destination.address, frame.copyOfRange(38, 54))
                    assertEquals(frame.size - 54, read16(18))
                    assertTrue(frame.size <= 1514)
                    if (frame[20] == 17.toByte()) {
                        validateUdp(frame.copyOfRange(54, frame.size)); return
                    }
                    assertEquals(17, frame[54].toInt())
                    val id = frame.copyOfRange(58, 62).toList()
                    if (identifier == null) identifier = id else assertEquals(identifier, id)
                    val fragment = read16(56)
                    val start = fragment and 0xfff8
                    val length = frame.size - 62
                    assertTrue(start + length <= assembled.size)
                    if (fragment and 1 == 0) {
                        assertFalse(last); last = true
                        assertEquals(assembled.size, start + length)
                    } else assertEquals(0, length % 8)
                    repeat(length) { index ->
                        assertFalse("Overlapping output fragments", seen[start + index])
                        seen[start + index] = true
                    }
                    frame.copyInto(assembled, start, 62, frame.size); copied += length
                    if (last && copied == assembled.size) { validateUdp(assembled); return }
                }
                fail("No UDP output frame before deadline")
            }
            override fun send(destinationPort: Int, payload: ByteArray) {
                val frame = ByteArray(62 + payload.size)
                fun put16(at: Int, value: Int) { frame[at] = (value ushr 8).toByte(); frame[at + 1] = value.toByte() }
                mac.copyInto(frame); byteArrayOf(2, 0, 0, 0, 0, 2).copyInto(frame, 6)
                put16(12, 0x86dd); frame[14] = 0x60; put16(18, payload.size + 8)
                frame[20] = 17; frame[21] = 64; peer.copyInto(frame, 22); ip.copyInto(frame, 38)
                put16(54, source.port); put16(56, destinationPort); put16(58, payload.size + 8)
                payload.copyInto(frame, 62)
                var sum = 17 + payload.size + 8
                for (at in 22 until frame.size step 2) {
                    sum += (frame[at].toInt() and 255) shl 8
                    if (at + 1 < frame.size) sum += frame[at + 1].toInt() and 255
                }
                while (sum ushr 16 != 0) sum = (sum and 65535) + (sum ushr 16)
                val checksum = sum.inv() and 65535
                put16(60, if (checksum == 0) 65535 else checksum)
                if (frame.size <= 1514) {
                    check(incoming.offer(frame, 2, TimeUnit.SECONDS))
                } else {
                    val fragments = ArrayList<ByteArray>()
                    var start = 0
                    while (start < payload.size + 8) {
                        val length = minOf(1448, payload.size + 8 - start)
                        val part = ByteArray(62 + length)
                        frame.copyInto(part, 0, 0, 54)
                        part[18] = ((length + 8) ushr 8).toByte(); part[19] = (length + 8).toByte()
                        part[20] = 44; part[54] = 17
                        val bits = start or if (start + length < payload.size + 8) 1 else 0
                        part[56] = (bits ushr 8).toByte(); part[57] = bits.toByte()
                        // Each vector has a distinct ID, including the two maximum-size cases.
                        val id = fragmentId.incrementAndGet()
                        repeat(4) { n -> part[58 + n] = (id ushr (24 - n * 8)).toByte() }
                        frame.copyInto(part, 62, 54 + start, 54 + start + length)
                        fragments.add(part); start += length
                    }
                    // All fragments of a datagram share one ID; deliver first fragment last.
                    val id = fragments.first().copyOfRange(58, 62)
                    for (part in fragments.asReversed()) {
                        id.copyInto(part, 58)
                        check(incoming.offer(part, 2, TimeUnit.SECONDS))
                    }
                }
            }
            override fun close() = network.close()
            private val fragmentId = java.util.concurrent.atomic.AtomicInteger()
        }
    }

    private fun network(): SessionNetwork {
        if (backend == "system") return SystemSessionNetwork("device-contract")
        val frames = LinkedBlockingQueue<ByteArray>()
        val mac = byteArrayOf(2, 0, 0, 0, 0, 1)
        val ip = ByteArray(16).also { it[0] = 0xfe.toByte(); it[1] = 0x80.toByte(); it[15] = 1 }
        val port = object : EthernetFramePort {
            override val hostMac = mac.copyOf()
            override fun send(frame: ByteArray, timeoutMillis: Int) = NcmSendResult.Sent
            override fun recv(timeoutMillis: Long) = frames.poll(timeoutMillis, TimeUnit.MILLISECONDS)
            override fun close() { frames.offer(ByteArray(14)) }
        }
        return LwipSessionNetwork.native(port, mac, ip, "device-contract")
    }

    private fun local(network: SessionNetwork, port: Int = 0) =
        NetworkEndpoint(ByteArray(16), port, network.linkId)

    @Test fun ephemeralPortsConflictAndCanBeRebound() {
        network().use { network ->
            val first = network.listen(local(network))
            val second = network.listen(local(network))
            try {
                assertTrue(first.localEndpoint.port in 1..65535)
                assertTrue(second.localEndpoint.port in 1..65535)
                assertNotEquals(first.localEndpoint.port, second.localEndpoint.port)
                assertEquals(network.linkId, first.localEndpoint.linkId)
                assertEquals(BackendFailure.Kind.ADDRESS_CONFLICT,
                    assertThrows(BackendFailure::class.java) { network.listen(first.localEndpoint) }.kind)
                val released = first.localEndpoint
                first.close(); first.close()
                network.listen(released).use { assertEquals(released, it.localEndpoint) }
            } finally { first.close(); second.close() }
        }
    }

    @Test fun foreignSessionAndExpiredDeadlinesFailWithoutConsumingEndpoints() {
        network().use { network ->
            val foreign = NetworkEndpoint(ByteArray(16), 12345, network.linkId + "-other")
            assertThrows(IllegalArgumentException::class.java) { network.listen(foreign) }
            assertThrows(IllegalArgumentException::class.java) { network.bindUdp(foreign) }
            assertThrows(IllegalArgumentException::class.java) { network.connect(foreign) }
            val zeroPort = NetworkEndpoint(ByteArray(16), 0, network.linkId)
            assertThrows(IllegalArgumentException::class.java) {
                network.connect(zeroPort, network.clock.nowMillis() + 3000)
            }
            network.bindUdp(local(network)).use { endpoint ->
                assertThrows(IllegalArgumentException::class.java) {
                    endpoint.send(byteArrayOf(1), zeroPort, network.clock.nowMillis() + 3000)
                }
            }
            network.listen(local(network)).use { listener ->
                assertThrows(IllegalArgumentException::class.java) { listener.accept(-1) }
                assertEquals(BackendFailure.Kind.TIMEOUT,
                    assertThrows(BackendFailure::class.java) { listener.accept(network.clock.nowMillis()) }.kind)
            }
            network.bindUdp(local(network)).use { udp ->
                assertThrows(IllegalArgumentException::class.java) { udp.receive(ByteArray(1), deadlineMillis = -1) }
                assertEquals(BackendFailure.Kind.TIMEOUT,
                    assertThrows(BackendFailure::class.java) {
                        udp.receive(ByteArray(1), deadlineMillis = network.clock.nowMillis())
                    }.kind)
            }
        }
    }

    @Test fun sessionCloseCancelsWaitersAndRejectsNewEndpoints() {
        val network = network()
        val executor = Executors.newFixedThreadPool(2)
        try {
            val listener = network.listen(local(network))
            val udp = network.bindUdp(local(network))
            val started = CountDownLatch(2)
            val waiters = listOf<() -> Unit>(
                { listener.accept().close() }, { udp.receive(ByteArray(1)) }
            ).map { operation -> executor.submit(Callable {
                started.countDown()
                try { operation(); null } catch (error: BackendFailure) { error.kind }
            }) }
            assertTrue(started.await(2, TimeUnit.SECONDS))
            network.close(); network.close()
            waiters.forEach { assertEquals(BackendFailure.Kind.CANCELLED, it.get(3, TimeUnit.SECONDS)) }
            listener.close(); udp.close()
            assertEquals(BackendFailure.Kind.CANCELLED,
                assertThrows(BackendFailure::class.java) { network.listen(local(network)) }.kind)
            assertEquals(BackendFailure.Kind.CANCELLED,
                assertThrows(BackendFailure::class.java) { network.bindUdp(local(network)) }.kind)
        } finally { network.close(); executor.shutdownNow() }
    }

    @Test fun endpointCloseCancelsBlockedAcceptWithoutClosingSession() {
        val network = network()
        val listener = network.listen(local(network))
        val executor = Executors.newSingleThreadExecutor()
        try {
            val started = CountDownLatch(1)
            val worker = AtomicReference<Thread>()
            val result = executor.submit<BackendFailure.Kind?> {
                worker.set(Thread.currentThread())
                started.countDown()
                try { listener.accept(); null }
                catch (error: BackendFailure) { error.kind }
            }
            assertTrue(started.await(2, TimeUnit.SECONDS))
            awaitBackendEntry(worker.get(), "SystemSessionNetwork", "LwipTcpListener")
            listener.close(); listener.close()
            assertEquals(BackendFailure.Kind.CANCELLED, result.get(3, TimeUnit.SECONDS))
            network.listen(local(network)).use { assertEquals(network.linkId, it.localEndpoint.linkId) }
        } finally {
            listener.close(); network.close(); executor.shutdownNow()
        }
    }

    @Test fun endpointCloseCancelsBlockedReceiveWithoutClosingSession() {
        val network = network()
        val udp = network.bindUdp(local(network))
        val executor = Executors.newSingleThreadExecutor()
        try {
            val started = CountDownLatch(1)
            val worker = AtomicReference<Thread>()
            val result = executor.submit<BackendFailure.Kind?> {
                worker.set(Thread.currentThread())
                started.countDown()
                try { udp.receive(ByteArray(16)); null }
                catch (error: BackendFailure) { error.kind }
            }
            assertTrue(started.await(2, TimeUnit.SECONDS))
            awaitBackendEntry(worker.get(), "SystemSessionNetwork", "LwipUdpEndpoint")
            udp.close(); udp.close()
            assertEquals(BackendFailure.Kind.CANCELLED, result.get(3, TimeUnit.SECONDS))
            network.bindUdp(local(network)).use { assertEquals(network.linkId, it.localEndpoint.linkId) }
        } finally {
            udp.close(); network.close(); executor.shutdownNow()
        }
    }

    private fun awaitBackendEntry(worker: Thread, systemMarker: String, lwipMarker: String) {
        val marker = if (backend == "system") systemMarker else lwipMarker
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (System.nanoTime() < end) {
            if (worker.stackTrace.any { it.className.contains(marker) }) return
            Thread.sleep(1)
        }
        fail("Worker did not enter $marker before endpoint close")
    }

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}")
        fun backends() = listOf(arrayOf("system"), arrayOf("lwip-jni"))
    }
}
