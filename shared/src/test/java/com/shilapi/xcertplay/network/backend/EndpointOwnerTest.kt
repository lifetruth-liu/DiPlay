package com.shilapi.xcertplay.network.backend

import com.shilapi.xcertplay.network.backend.system.EndpointOwner
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors
import org.junit.Assert.*
import org.junit.Test

class EndpointOwnerTest {
    @Test fun closeCancelsAListenerPublishedAfterTheOwnerWasClosed() {
        val network = DelayedNetwork()
        val owner = EndpointOwner(network, "test")
        val executor = Executors.newSingleThreadExecutor()
        try {
            val result = executor.submit<BackendFailure.Kind> {
                try { owner.listen(network.local); error("closed owner published a listener") }
                catch (failure: BackendFailure) { failure.kind }
            }
            assertTrue(network.entered.await(2, TimeUnit.SECONDS))
            owner.close()
            network.proceed.countDown()
            assertEquals(BackendFailure.Kind.CANCELLED, result.get(2, TimeUnit.SECONDS))
            assertTrue(network.listenerClosed)
            assertFalse(network.closed)
        } finally { network.proceed.countDown(); owner.close(); executor.shutdownNow() }
    }

    @Test fun closeCancelsAnAcceptedStreamPublishedAfterTheOwnerWasClosed() {
        val network = DelayedNetwork()
        val owner = EndpointOwner(network, "test")
        val executor = Executors.newSingleThreadExecutor()
        try {
            val result = executor.submit<BackendFailure.Kind> {
                try { owner.accept(network.listener); error("closed owner published a stream") }
                catch (failure: BackendFailure) { failure.kind }
            }
            assertTrue(network.entered.await(2, TimeUnit.SECONDS))
            owner.close()
            network.proceed.countDown()
            assertEquals(BackendFailure.Kind.CANCELLED, result.get(2, TimeUnit.SECONDS))
            assertTrue(network.streamClosed)
            assertFalse(network.closed)
        } finally { network.proceed.countDown(); owner.close(); executor.shutdownNow() }
    }

    private class DelayedNetwork : SessionNetwork {
        override val linkId = "test"
        override val clock = MonotonicClock { 0 }
        val local = NetworkEndpoint(ByteArray(16), 7000, linkId)
        val entered = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        var closed = false
        var listenerClosed = false
        var streamClosed = false
        val listener = object : TcpListener {
            override val localEndpoint = local
            override fun accept(deadlineMillis: Long): TcpStream {
                entered.countDown(); check(proceed.await(2, TimeUnit.SECONDS)); return stream
            }
            override fun close() { listenerClosed = true }
        }
        val stream = object : TcpStream {
            override val localEndpoint = local
            override val remoteEndpoint = local
            override fun read(bytes: ByteArray, offset: Int, length: Int, deadlineMillis: Long): Int = error("unused")
            override fun write(bytes: ByteArray, offset: Int, length: Int, deadlineMillis: Long) = error("unused")
            override fun shutdownOutput() {}
            override fun configure(options: TcpOptions) {}
            override fun configureLiveness(diagnostic: (String) -> Unit) {}
            override fun close() { streamClosed = true }
        }
        override fun listen(local: NetworkEndpoint, backlog: Int, reuseAddress: Boolean): TcpListener {
            entered.countDown(); check(proceed.await(2, TimeUnit.SECONDS)); return listener
        }
        override fun connect(remote: NetworkEndpoint, deadlineMillis: Long): TcpStream = error("unused")
        override fun bindUdp(local: NetworkEndpoint): UdpEndpoint = error("unused")
        override fun close() { closed = true }
    }
}
