package com.shilapi.xcertplay.network.backend

import com.shilapi.xcertplay.network.backend.system.SystemSessionNetwork
import com.shilapi.xcertplay.network.backend.system.EndpointOperation
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

/** Real loopback traffic; no Android or native library substitutes. */
class SessionNetworkContractTest {
    private fun address(network: SessionNetwork, port: Int = 0) =
        NetworkEndpoint(byteArrayOf(127, 0, 0, 1), port, network.linkId)

    @Test fun ephemeralPortConflictAndSessionIsolation() {
        SystemSessionNetwork("first").use { first ->
            SystemSessionNetwork("first").use { second ->
                assertNotEquals("A reused label must not reuse a session identity", first.linkId, second.linkId)
                first.listen(address(first)).use { listener ->
                    assertTrue(listener.localEndpoint.port in 1..65535)
                    assertThrows(BackendFailure::class.java) { first.listen(listener.localEndpoint) }
                    assertThrows(IllegalArgumentException::class.java) {
                        second.connect(listener.localEndpoint)
                    }
                }
            }
        }
    }

    @Test fun duplexBytesEofAndHalfClose() {
        SystemSessionNetwork("duplex").use { network ->
            network.listen(address(network)).use { listener ->
                network.connect(listener.localEndpoint).use { client ->
                    listener.accept().use { peer ->
                        val bytes = ByteArray(8193) { (it * 31).toByte() }
                        client.write(bytes)
                        client.shutdownOutput()
                        val received = ArrayList<Byte>()
                        val chunk = ByteArray(97)
                        while (true) {
                            val count = peer.read(chunk, deadlineMillis = network.clock.nowMillis() + 3000)
                            if (count == -1) break
                            assertTrue(count > 0)
                            received.addAll(chunk.take(count))
                        }
                        assertArrayEquals(bytes, received.toByteArray())
                        peer.write(byteArrayOf(42))
                        assertEquals(1, client.read(chunk))
                        assertEquals(42, chunk[0].toInt())
                        assertEquals(network.linkId, peer.remoteEndpoint.linkId)
                    }
                }
            }
        }
    }

    @Test fun datagramBoundariesSourceAndOriginalLength() {
        SystemSessionNetwork("udp").use { network ->
            network.bindUdp(address(network)).use { receiver ->
                network.bindUdp(address(network)).use { sender ->
                    for (size in listOf(0, 1, 1400)) {
                        sender.send(ByteArray(size) { 7 }, receiver.localEndpoint)
                        val result = receiver.receive(ByteArray(10), deadlineMillis = network.clock.nowMillis() + 3000)
                        assertEquals(size, result.originalLength)
                        assertEquals(minOf(size, 10), result.copiedLength)
                        assertEquals(size > 10, result.truncated)
                        assertEquals(sender.localEndpoint, result.source)
                    }
                    assertThrows(IllegalArgumentException::class.java) {
                        sender.send(ByteArray(65528), receiver.localEndpoint)
                    }
                }
            }
        }
    }

    @Test fun closeCancelsAcceptReadAndReceiveAndIsIdempotent() {
        val executor = Executors.newFixedThreadPool(3)
        try {
            val network = SystemSessionNetwork("cancel")
            val listener = network.listen(address(network))
            val client = network.connect(listener.localEndpoint)
            val peer = listener.accept()
            val udp = network.bindUdp(address(network))
            val tasks = listOf<() -> Unit>(
                { listener.accept().close() },
                { peer.read(ByteArray(1)) },
                { udp.receive(ByteArray(1)) },
            ).map { operation -> executor.submit(Callable {
                try { operation(); null } catch (error: BackendFailure) { error.kind }
            }) }
            network.close()
            network.close()
            tasks.forEach { assertEquals(BackendFailure.Kind.CANCELLED, it.get(3, TimeUnit.SECONDS)) }
            assertThrows(BackendFailure::class.java) { client.write(byteArrayOf(1)) }
            assertThrows(BackendFailure::class.java) { network.listen(address(network)) }
        } finally {
            executor.shutdownNow()
        }
    }

    @Test fun deadlinesAndInputValidation() {
        var now = 100L
        val clock = MonotonicClock { now }
        assertEquals(50L, Deadline.remainingMillis(150, clock))
        now = 151
        assertThrows(BackendFailure::class.java) { Deadline.remainingMillis(150, clock) }
        assertEquals(0L, Deadline.remainingMillis(0, clock))
        assertThrows(IllegalArgumentException::class.java) { Deadline.remainingMillis(-1, clock) }
        assertThrows(IllegalArgumentException::class.java) { NetworkEndpoint(ByteArray(5), 0, "x") }
        assertThrows(IllegalArgumentException::class.java) { NetworkEndpoint(ByteArray(16), 65536, "x") }
        SystemSessionNetwork("timeout").use { network ->
            network.listen(address(network)).use { listener ->
                val failure = assertThrows(BackendFailure::class.java) {
                    listener.accept(network.clock.nowMillis())
                }
                assertEquals(BackendFailure.Kind.TIMEOUT, failure.kind)
            }
        }
    }

    @Test fun endpointOwnsAddressBytesAndRedactsLogs() {
        val bytes = ByteArray(16) { it.toByte() }
        val endpoint = NetworkEndpoint(bytes, 1234, "private-session")
        bytes[0] = 99
        endpoint.address[1] = 99
        assertEquals(0, endpoint.address[0].toInt())
        assertEquals(1, endpoint.address[1].toInt())
        assertEquals("IPv6:1234", endpoint.toString())
    }

    @Test fun blockedWriteIsCancelledAndFullDuplexDoesNotShareALock() {
        val executor = Executors.newFixedThreadPool(2)
        try {
            SystemSessionNetwork("backpressure").use { network ->
                network.listen(address(network)).use { listener ->
                    network.connect(listener.localEndpoint).use { client ->
                        listener.accept().use { peer ->
                            val entered = CountDownLatch(1)
                            val writing = executor.submit(Callable {
                                entered.countDown()
                                try {
                                    // Larger than the socket send/receive windows; peer never drains.
                                    client.write(ByteArray(32 * 1024 * 1024))
                                    null
                                } catch (failure: BackendFailure) { failure.kind }
                            })
                            assertTrue(entered.await(3, TimeUnit.SECONDS))
                            peer.write(byteArrayOf(13))
                            val reading = executor.submit(Callable {
                                val buffer = ByteArray(1)
                                assertEquals(1, client.read(buffer, deadlineMillis = network.clock.nowMillis() + 3000))
                                buffer[0]
                            })
                            assertEquals(13.toByte(), reading.get(4, TimeUnit.SECONDS))
                            client.close()
                            assertEquals(BackendFailure.Kind.CANCELLED, writing.get(3, TimeUnit.SECONDS))
                        }
                    }
                }
            }
        } finally { executor.shutdownNow() }
    }

    @Test fun resumedConsumerGetsEveryByteUnderBackpressure() {
        val executor = Executors.newSingleThreadExecutor()
        try {
            SystemSessionNetwork("resume").use { network ->
                network.listen(address(network)).use { listener ->
                    network.connect(listener.localEndpoint).use { client ->
                        listener.accept().use { peer ->
                            val expected = ByteArray(4 * 1024 * 1024) { (it * 71).toByte() }
                            val sending = executor.submit(Callable {
                                client.write(expected, deadlineMillis = network.clock.nowMillis() + 10000)
                                client.shutdownOutput()
                            })
                            val actual = ByteArray(expected.size)
                            var offset = 0
                            while (offset < actual.size) {
                                val count = peer.read(actual, offset, minOf(16381, actual.size - offset),
                                    deadlineMillis = network.clock.nowMillis() + 10000)
                                assertTrue(count > 0)
                                offset += count
                            }
                            assertArrayEquals(expected, actual)
                            assertEquals(-1, peer.read(ByteArray(1), deadlineMillis = network.clock.nowMillis() + 3000))
                            sending.get(3, TimeUnit.SECONDS)
                        }
                    }
                }
            }
        } finally { executor.shutdownNow() }
    }

    @Test fun overlappingOperationIsRejectedWithoutWaitingForTheFirst() {
        val executor = Executors.newSingleThreadExecutor()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val operation = EndpointOperation(MonotonicClock { 100 })
        try {
            val first = executor.submit(Callable {
                operation.run(0) {
                    entered.countDown()
                    assertTrue(release.await(3, TimeUnit.SECONDS))
                }
            })
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            assertEquals(BackendFailure.Kind.RESOURCE_LIMIT,
                assertThrows(BackendFailure::class.java) { operation.run(101) {} }.kind)
            release.countDown()
            first.get(3, TimeUnit.SECONDS)
            operation.run(101) {} // lock released after success
            assertThrows(IllegalStateException::class.java) { operation.run(101) { error("injected") } }
            operation.run(101) {} // and after failure
        } finally { release.countDown(); executor.shutdownNow() }
    }

    @Test fun udpReceivesAndSendsConcurrentlyIncludingEmptyDatagrams() {
        val executor = Executors.newSingleThreadExecutor()
        try {
            SystemSessionNetwork("udp-duplex").use { network ->
                network.bindUdp(address(network)).use { first ->
                    network.bindUdp(address(network)).use { second ->
                        val receiving = executor.submit(Callable {
                            first.receive(ByteArray(1), deadlineMillis = network.clock.nowMillis() + 3000)
                        })
                        first.send(ByteArray(0), second.localEndpoint, network.clock.nowMillis() + 3000)
                        assertEquals(0, second.receive(ByteArray(1), network.clock.nowMillis() + 3000).originalLength)
                        second.send(byteArrayOf(1), first.localEndpoint)
                        assertEquals(1, receiving.get(4, TimeUnit.SECONDS).originalLength)
                    }
                }
            }
        } finally { executor.shutdownNow() }
    }
}
