package com.shilapi.xcertplay.network.backend.lwip

import com.shilapi.xcertplay.network.backend.*
import com.shilapi.xcertplay.transport.NcmSendResult
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

class LwipUdpEndpointTest {
    @Test(timeout = 10_000) fun bindAllocationFailureIsStructuredAndLeavesRunnerUsable() = Fixture().use { f ->
        f.core.bindFailure.set(OutOfMemoryError("injected"))
        failure(BackendFailure.Kind.RESOURCE_LIMIT) { f.bind() }
        await { f.runner.snapshot().resourceScopes == 0 }
        assertNull(f.runner.snapshot().failure)
        f.bind().close()
    }
    @Test(timeout = 10_000) fun bindBeforeStartDoesNotReserveResourceSlots() {
        val runner = LwipPrototype(Port()) { fail("must not create core"); Core() }
        try {
            failure(BackendFailure.Kind.CANCELLED) { LwipUdpEndpoint.bind(runner, endpoint()) }
            assertEquals(0, runner.snapshot().resourceScopes)
        } finally { runner.close() }
    }
    private val ip = ByteArray(16).also { it[0] = 0xfd.toByte(); it[15] = 1 }
    private val peer = ip.copyOf().also { it[15] = 2 }
    private fun endpoint(port: Int = 0) = NetworkEndpoint(ip, port, "link")
    private fun failure(kind: BackendFailure.Kind, body: () -> Unit) {
        val error = runCatching(body).exceptionOrNull()
        assertTrue("$error", error is BackendFailure)
        assertEquals(kind, (error as BackendFailure).kind)
    }
    private fun await(condition: () -> Boolean) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (!condition() && System.nanoTime() < end) Thread.sleep(1)
        assertTrue(condition())
    }
    private class Call<T>(body: () -> T) {
        val result = AtomicReference<Result<T>>()
        val thread = Thread { result.set(runCatching(body)) }.apply { isDaemon = true; start() }
        fun finish(): Result<T> { thread.join(2000); assertFalse(thread.isAlive); return result.get() }
    }
    private inner class Fixture : AutoCloseable {
        val port = Port()
        val core = Core()
        val runner = LwipPrototype(port) { core.also { it.owner = Thread.currentThread() } }
        init { runner.start() }
        fun bind(port: Int = 0) = LwipUdpEndpoint.bind(runner, endpoint(port))
        fun packet(bytes: ByteArray) { core.incoming.add(bytes.copyOf()); progress() }
        fun progress() { port.frames.put(ByteArray(14)) }
        override fun close() = runner.close()
    }

    @Test(timeout = 10_000) fun bindsActualAddressAndPortAndClosesIdempotentlyOnCore() = Fixture().use { f ->
        val udp = f.bind()
        assertEquals(endpoint(45000), udp.localEndpoint)
        assertEquals(0, udp.receiveBufferSize(1024))
        assertTrue(f.core.bound.containsKey(1L))
        udp.close(); udp.close()
        await { f.core.closes.get() == 1 && f.runner.snapshot().resourceScopes == 0 }
        assertEquals(0, f.runner.snapshot().resourceScopes)
        failure(BackendFailure.Kind.CANCELLED) { udp.receive(ByteArray(1)) }
        failure(BackendFailure.Kind.CANCELLED) { udp.send(byteArrayOf(), NetworkEndpoint(peer, 123, "link")) }
        assertNull(f.runner.snapshot().failure)
    }

    @Test(timeout = 10_000) fun receivesTruncationEmptyAndZeroCapacityWithoutKernelSockets() = Fixture().use { f ->
        val udp = f.bind()
        f.packet("hello".toByteArray())
        val target = ByteArray(2)
        val received = udp.receive(target, f.runner.commandClock.nowMillis() + 1000)
        assertArrayEquals("he".toByteArray(), target)
        assertEquals(NetworkEndpoint(peer, 50000, "link"), received.source)
        assertEquals(5, received.originalLength); assertEquals(2, received.copiedLength); assertTrue(received.truncated)
        f.packet(byteArrayOf()); assertEquals(0, udp.receive(ByteArray(0)).originalLength)
        f.packet(byteArrayOf(1)); assertTrue(udp.receive(ByteArray(0)).truncated)
        assertEquals(0, f.runner.snapshot().commandBytes)
        udp.close()
    }

    @Test(timeout = 10_000) fun pendingSendOwnsCopyAndAllowsOneConcurrentReceiver() = Fixture().use { f ->
        val udp = f.bind()
        f.core.sendBlocked.set(true)
        val bytes = byteArrayOf(1, 2, 3)
        val send = Call { udp.send(bytes, NetworkEndpoint(peer, 123, "link")) }
        assertTrue(f.core.sending.await(2, TimeUnit.SECONDS))
        bytes.fill(0)
        failure(BackendFailure.Kind.RESOURCE_LIMIT) { udp.send(byteArrayOf(), NetworkEndpoint(peer, 123, "link")) }
        f.packet(byteArrayOf(9)); assertEquals(1, udp.receive(ByteArray(1)).copiedLength)
        f.core.sendBlocked.set(false); f.progress()
        send.finish().getOrThrow()
        assertArrayEquals(byteArrayOf(1, 2, 3), f.core.sent.single())
        udp.close()
    }

    @Test(timeout = 10_000) fun closeCancelsPendingReceiveAndLeavesOtherEndpointUsable() = Fixture().use { f ->
        val first = f.bind()
        val second = f.bind(45001)
        val receive = Call { first.receive(ByteArray(1)) }
        assertTrue(f.core.receiving.await(2, TimeUnit.SECONDS))
        failure(BackendFailure.Kind.RESOURCE_LIMIT) { first.receive(ByteArray(1)) }
        first.close()
        failure(BackendFailure.Kind.CANCELLED) { receive.finish().getOrThrow() }
        f.packet(byteArrayOf(7)); assertEquals(1, second.receive(ByteArray(1)).originalLength)
        second.close()
        await { f.core.closes.get() == 2 }
    }

    @Test(timeout = 10_000) fun timeoutLeavesTargetUntouchedAndEndpointReusable() = Fixture().use { f ->
        val udp = f.bind()
        val bytes = byteArrayOf(99)
        failure(BackendFailure.Kind.TIMEOUT) { udp.receive(bytes, f.runner.commandClock.nowMillis() + 30) }
        assertArrayEquals(byteArrayOf(99), bytes)
        f.packet(byteArrayOf(42)); assertEquals(1, udp.receive(bytes).copiedLength)
        assertArrayEquals(byteArrayOf(42), bytes)
        udp.close()
    }

    @Test(timeout = 10_000) fun failedBindAndInvalidDestinationsDoNotLeakResourceScopes() = Fixture().use { f ->
        val udp = f.bind(45000)
        failure(BackendFailure.Kind.ADDRESS_CONFLICT) { f.bind(45000) }
        await { f.runner.snapshot().resourceScopes == 1 }
        assertTrue(runCatching { LwipUdpEndpoint.bind(f.runner, NetworkEndpoint(peer, 0, "link")) }.exceptionOrNull() is IllegalArgumentException)
        await { f.runner.snapshot().resourceScopes == 1 }
        assertTrue(runCatching { udp.send(byteArrayOf(), NetworkEndpoint(peer, 1, "other")) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { udp.send(byteArrayOf(), NetworkEndpoint(ByteArray(4), 1, "link")) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { udp.send(ByteArray(65528), NetworkEndpoint(peer, 1, "link")) }.exceptionOrNull() is IllegalArgumentException)
        assertEquals(0, f.runner.snapshot().commandBytes)
        udp.close()
    }

    private class Port : EthernetFramePort {
        override val hostMac: ByteArray? = null
        val frames = LinkedBlockingQueue<ByteArray>()
        override fun send(frame: ByteArray, timeoutMillis: Int) = NcmSendResult.Sent
        override fun recv(timeoutMillis: Long): ByteArray? = frames.poll(timeoutMillis, TimeUnit.MILLISECONDS)
        override fun close() { frames.offer(ByteArray(14)) }
    }
    private inner class Core : LwipUdpCore {
        lateinit var owner: Thread
        val bound = java.util.concurrent.ConcurrentHashMap<Long, Int>()
        val closes = AtomicInteger()
        val incoming = ConcurrentLinkedQueue<ByteArray>()
        val sent = ConcurrentLinkedQueue<ByteArray>()
        val sendBlocked = AtomicBoolean()
        val sending = CountDownLatch(1)
        val receiving = CountDownLatch(1)
        val bindFailure = AtomicReference<Throwable>()
        private var next = 1L
        private fun checkOwner() { assertSame(owner, Thread.currentThread()) }
        override val udpLocalAddress: ByteArray get() { checkOwner(); return ip.copyOf() }
        override fun udpBind(port: Int): LwipUdpCore.Binding {
            checkOwner()
            bindFailure.getAndSet(null)?.let { throw it }
            val actual = if (port == 0) 45000 else port
            if (bound.containsValue(actual)) throw BackendFailure(BackendFailure.Kind.ADDRESS_CONFLICT)
            return LwipUdpCore.Binding(next++, actual).also { bound[it.token] = actual }
        }
        override fun udpSend(token: Long, address: ByteArray, port: Int, bytes: ByteArray): CoreAttempt<Unit> {
            checkOwner(); assertTrue(bound.containsKey(token)); sending.countDown()
            if (sendBlocked.get()) return CoreAttempt.Pending
            sent.add(bytes.copyOf()); return CoreAttempt.Ready(Unit)
        }
        override fun udpReceive(token: Long, bytes: ByteArray, source: ByteArray, metadata: IntArray): CoreAttempt<Unit> {
            checkOwner(); assertTrue(bound.containsKey(token)); receiving.countDown()
            val packet = incoming.poll() ?: return CoreAttempt.Pending
            peer.copyInto(source)
            val copied = minOf(bytes.size, packet.size)
            packet.copyInto(bytes, endIndex = copied)
            metadata[0] = 50000; metadata[1] = packet.size; metadata[2] = copied
            return CoreAttempt.Ready(Unit)
        }
        override fun udpClose(token: Long) { checkOwner(); assertNotNull(bound.remove(token)); closes.incrementAndGet() }
        override fun prepare() = checkOwner()
        override fun input(frame: ByteArray) = checkOwner()
        override fun poll() = checkOwner()
        override fun nextTimeoutMillis(): Long { checkOwner(); return 60_000 }
        override fun takeOutput(): ByteArray? { checkOwner(); return null }
        override fun setLinkReady(ready: Boolean) = checkOwner()
        override fun close() { checkOwner(); bound.clear() }
    }
}
