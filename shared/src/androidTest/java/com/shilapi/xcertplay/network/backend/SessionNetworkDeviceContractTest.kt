package com.shilapi.xcertplay.network.backend

import com.shilapi.xcertplay.network.backend.lwip.LwipSessionNetwork
import com.shilapi.xcertplay.network.backend.system.SystemSessionNetwork
import com.shilapi.xcertplay.transport.NcmSendResult
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** Identical public API assertions on Android sockets and the actual JNI core.
 * The disconnected Ethernet port deliberately supplies no peer: these cases cover
 * local resource ownership, validation and cancellation, not packet interoperability.
 */
@RunWith(Parameterized::class)
class SessionNetworkDeviceContractTest(private val backend: String) {
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

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}")
        fun backends() = listOf(arrayOf("system"), arrayOf("lwip-jni"))
    }
}
