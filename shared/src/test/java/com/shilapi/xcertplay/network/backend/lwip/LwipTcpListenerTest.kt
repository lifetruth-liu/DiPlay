package com.shilapi.xcertplay.network.backend.lwip

import com.shilapi.xcertplay.network.backend.*
import com.shilapi.xcertplay.transport.NcmSendResult
import java.util.concurrent.*
import java.util.concurrent.atomic.*
import org.junit.Assert.*
import org.junit.Test

class LwipTcpListenerTest {
    private val ip = ByteArray(16).also { it[0] = 0xfd.toByte(); it[15] = 1 }
    private val peer = ip.copyOf().also { it[15] = 2 }
    private fun endpoint(port: Int = 0) = NetworkEndpoint(ip, port, "link")
    private fun failure(kind: BackendFailure.Kind, body: () -> Unit) {
        val error = runCatching(body).exceptionOrNull()
        assertTrue("$error", error is BackendFailure); assertEquals(kind, (error as BackendFailure).kind)
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
        val port = Port(); val core = Core()
        val runner = LwipPrototype(port) { core.also { it.owner = Thread.currentThread() } }
        init { runner.start() }
        fun bind() = LwipTcpListener.bind(runner, endpoint())
        fun offer() { core.pending.incrementAndGet(); port.frames.offer(ByteArray(14)) }
        fun deadline() = runner.commandClock.nowMillis() + 40
        override fun close() = runner.close()
    }
    @Test(timeout = 10_000) fun wildcardBindPublishesActualEndpointAndCapsAdvisoryBacklog() = Fixture().use { f ->
        val listener = LwipTcpListener.bind(f.runner, NetworkEndpoint(ByteArray(16), 0, "link"))
        assertEquals(endpoint(45000), listener.localEndpoint); assertEquals(32, f.core.backlog.get())
        listener.localEndpoint.address.fill(0); assertArrayEquals(ip, listener.localEndpoint.address)
        listener.close(); listener.close()
        await { f.core.listenerCloses.get() == 1 && f.runner.snapshot().resourceScopes == 0 }
        failure(BackendFailure.Kind.CANCELLED) { listener.accept() }
    }
    @Test(timeout = 10_000) fun acceptedStreamOwnsItsScopeAndSurvivesListenerClose() = Fixture().use { f ->
        val listener = f.bind(); f.offer(); val stream = listener.accept()
        assertEquals(endpoint(45000), stream.localEndpoint)
        assertEquals(NetworkEndpoint(peer, 50000, "link"), stream.remoteEndpoint)
        listener.close(); await { f.core.listenerCloses.get() == 1 }
        assertEquals(1, stream.read(ByteArray(1))); stream.write(byteArrayOf(5))
        stream.close(); await { f.runner.snapshot().resourceScopes == 0 }
        assertEquals(1, f.core.graceful.get()); assertEquals(0, f.core.aborts.get())
    }
    @Test(timeout = 10_000) fun pendingAcceptRejectsOverlapAndCloseCancelsIt() = Fixture().use { f ->
        val listener = f.bind(); val accept = Call { listener.accept() }
        assertTrue(f.core.accepting.await(2, TimeUnit.SECONDS))
        failure(BackendFailure.Kind.RESOURCE_LIMIT) { listener.accept() }
        listener.close()
        failure(BackendFailure.Kind.CANCELLED) { accept.finish().getOrThrow() }
        await { f.runner.snapshot().resourceScopes == 0 }
        assertEquals(0, f.core.streams.size)
    }
    @Test(timeout = 10_000) fun timeoutAndInterruptionLeaveListenerUsableWithoutChildScopeLeaks() = Fixture().use { f ->
        val listener = f.bind()
        failure(BackendFailure.Kind.TIMEOUT) { listener.accept(f.deadline()) }
        await { f.runner.snapshot().resourceScopes == 1 }
        val accept = Call { listener.accept() }
        assertTrue(f.core.accepting.await(2, TimeUnit.SECONDS)); accept.thread.interrupt()
        failure(BackendFailure.Kind.CANCELLED) { accept.finish().getOrThrow() }
        await { f.runner.snapshot().resourceScopes == 1 }
        f.offer(); listener.accept().close(); listener.close()
        await { f.runner.snapshot().resourceScopes == 0 }
    }
    @Test(timeout = 10_000) fun lateAcceptedChildIsAbortedAfterDeadlineAndListenerCanAcceptAgain() = Fixture().use { f ->
        val listener = f.bind(); val gate = CountDownLatch(1); f.core.acceptGate.set(gate); f.offer()
        val accept = Call { listener.accept(f.runner.commandClock.nowMillis() + 200) }
        try {
            assertTrue(f.core.accepting.await(2, TimeUnit.SECONDS))
            failure(BackendFailure.Kind.TIMEOUT) { accept.finish().getOrThrow() }
        } finally { gate.countDown() }
        await { f.core.aborts.get() == 1 && f.runner.snapshot().resourceScopes == 1 }
        f.offer(); listener.accept().close(); listener.close()
        await { f.runner.snapshot().resourceScopes == 0 }
        assertNull(f.runner.snapshot().failure)
    }
    @Test(timeout = 10_000) fun closeDuringRunningAcceptReclaimsItsLateChildBeforePortReuse() = Fixture().use { f ->
        val listener = f.bind(); val gate = CountDownLatch(1); f.core.acceptGate.set(gate); f.offer()
        val accept = Call { listener.accept() }
        try {
            assertTrue(f.core.accepting.await(2, TimeUnit.SECONDS)); listener.close()
            failure(BackendFailure.Kind.CANCELLED) { accept.finish().getOrThrow() }
        } finally { gate.countDown() }
        await { f.core.aborts.get() == 1 && f.core.listenerCloses.get() == 1 && f.runner.snapshot().resourceScopes == 0 }
        val fresh = f.bind(); f.offer(); fresh.accept().close(); fresh.close()
        await { f.runner.snapshot().resourceScopes == 0 }
        assertNull(f.runner.snapshot().failure)
    }
    @Test(timeout = 10_000) fun explicitBindPortCannotBeSilentlyChangedByBackendMetadata() = Fixture().use { f ->
        failure(BackendFailure.Kind.PROTOCOL) { LwipTcpListener.bind(f.runner, endpoint(45001)) }
        await { f.core.listenerCloses.get() == 1 && f.runner.snapshot().resourceScopes == 0 }
    }
    @Test(timeout = 10_000) fun metadataFailureAbortsOnlyNewChildAndPreservesListener() = Fixture().use { f ->
        val listener = f.bind(); f.core.invalidPeer.set(true); f.offer()
        failure(BackendFailure.Kind.PROTOCOL) { listener.accept() }
        await { f.core.aborts.get() == 1 && f.runner.snapshot().resourceScopes == 1 }
        f.offer(); listener.accept().close(); listener.close()
        await { f.runner.snapshot().resourceScopes == 0 }
    }
    @Test(timeout = 10_000) fun admissionFailureDoesNotConsumePendingConnection() = Fixture().use { f ->
        val listener = f.bind(); f.offer()
        val held = List(79) { f.runner.newResourceScope { } }
        failure(BackendFailure.Kind.RESOURCE_LIMIT) { listener.accept() }
        assertEquals(0, f.core.acceptCalls.get()); assertEquals(1, f.core.pending.get())
        held.forEach { f.runner.closeResourceScope(it) }
        await { f.runner.snapshot().resourceScopes == 1 }
        listener.accept().close(); listener.close()
        await { f.runner.snapshot().resourceScopes == 0 }
    }
    @Test(timeout = 10_000) fun bindAndAcceptAllocationFailuresStayStructuredAndReleaseReservations() = Fixture().use { f ->
        f.core.bindFailure.set(OutOfMemoryError("bind"))
        failure(BackendFailure.Kind.RESOURCE_LIMIT) { f.bind() }
        await { f.runner.snapshot().resourceScopes == 0 }
        val listener = f.bind(); f.core.acceptFailure.set(OutOfMemoryError("accept"))
        failure(BackendFailure.Kind.RESOURCE_LIMIT) { listener.accept() }
        await { f.runner.snapshot().resourceScopes == 1 }
        assertNull(f.runner.snapshot().failure)
        f.offer(); listener.accept().close(); listener.close()
    }
    @Test(timeout = 10_000) fun badBindArgumentsAndConflictsDoNotLeakScopes() = Fixture().use { f ->
        val listener = f.bind()
        failure(BackendFailure.Kind.ADDRESS_CONFLICT) { f.bind() }
        assertTrue(runCatching { LwipTcpListener.bind(f.runner, NetworkEndpoint(peer, 0, "link")) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { LwipTcpListener.bind(f.runner, endpoint(), 0) }.exceptionOrNull() is IllegalArgumentException)
        await { f.runner.snapshot().resourceScopes == 1 }; listener.close()
    }
    @Test(timeout = 10_000) fun bindBeforeStartNeverReservesAResource() {
        val runner = LwipPrototype(Port()) { error("must not create") }
        try {
            failure(BackendFailure.Kind.CANCELLED) { LwipTcpListener.bind(runner, endpoint()) }
            assertEquals(0, runner.snapshot().resourceScopes)
        } finally { runner.close() }
    }
    private class Port : EthernetFramePort {
        override val hostMac: ByteArray? = null
        val frames = LinkedBlockingQueue<ByteArray>()
        override fun send(frame: ByteArray, timeoutMillis: Int) = NcmSendResult.Sent
        override fun recv(timeoutMillis: Long) = frames.poll(timeoutMillis, TimeUnit.MILLISECONDS)
        override fun close() { frames.offer(ByteArray(14)) }
    }
    private inner class Core : LwipTcpCore {
        lateinit var owner: Thread
        val listeners = ConcurrentHashMap.newKeySet<Long>(); val streams = ConcurrentHashMap.newKeySet<Long>()
        val pending = AtomicInteger(); val backlog = AtomicInteger(); val acceptCalls = AtomicInteger()
        val accepting = CountDownLatch(1); val acceptGate = AtomicReference<CountDownLatch>()
        val listenerCloses = AtomicInteger(); val aborts = AtomicInteger(); val graceful = AtomicInteger()
        val invalidPeer = AtomicBoolean(); val bindFailure = AtomicReference<Throwable>(); val acceptFailure = AtomicReference<Throwable>()
        private var next = 1L
        private fun owner() { assertSame(owner, Thread.currentThread()) }
        override val tcpLocalAddress: ByteArray get() { owner(); return ip.copyOf() }
        override fun tcpListen(port: Int, backlog: Int): LwipTcpCore.Listener {
            owner(); bindFailure.getAndSet(null)?.let { throw it }
            if (listeners.isNotEmpty()) throw BackendFailure(BackendFailure.Kind.ADDRESS_CONFLICT)
            this.backlog.set(backlog)
            return LwipTcpCore.Listener(next++.also { listeners.add(it) }, 45000)
        }
        override fun tcpAccept(listener: Long): CoreAttempt<LwipTcpCore.Accepted> {
            owner(); assertTrue(listeners.contains(listener)); acceptCalls.incrementAndGet(); accepting.countDown()
            acceptGate.getAndSet(null)?.let { assertTrue(it.await(2, TimeUnit.SECONDS)) }
            acceptFailure.getAndSet(null)?.let { throw it }
            if (pending.get() == 0) return CoreAttempt.Pending
            pending.decrementAndGet()
            val token = next++.also { streams.add(it) }
            return CoreAttempt.Ready(LwipTcpCore.Accepted(token, LwipTcpCore.Peer(ip.copyOf(),
                if (invalidPeer.getAndSet(false)) 45001 else 45000, peer.copyOf(), 50000)))
        }
        override fun tcpListenerClose(token: Long) { owner(); assertTrue(listeners.remove(token)); pending.set(0); listenerCloses.incrementAndGet() }
        override fun tcpClose(token: Long) { owner(); assertTrue(streams.remove(token)); aborts.incrementAndGet() }
        override fun tcpCloseGraceful(token: Long) { owner(); assertTrue(streams.remove(token)); graceful.incrementAndGet() }
        override fun tcpRead(token: Long, bytes: ByteArray, offset: Int, length: Int): CoreAttempt<Int> {
            owner(); assertTrue(streams.contains(token)); bytes[offset] = 7; return CoreAttempt.Ready(1)
        }
        override fun tcpWrite(token: Long, bytes: ByteArray, offset: Int, length: Int): CoreAttempt<Int> {
            owner(); assertTrue(streams.contains(token)); return CoreAttempt.Ready(length)
        }
        override fun tcpConnect(address: ByteArray, port: Int): CoreAttempt<Long> = error("not used")
        override fun tcpConnectStatus(token: Long): CoreAttempt<LwipTcpCore.Peer> = error("not used")
        override fun tcpShutdownOutput(token: Long): CoreAttempt<Unit> = error("not used")
        override fun tcpConfigureLiveness(token: Long) { error("not used") }
        override fun prepare() = owner()
        override fun input(frame: ByteArray) = owner()
        override fun poll() = owner()
        override fun nextTimeoutMillis(): Long { owner(); return 60_000 }
        override fun takeOutput(): ByteArray? { owner(); return null }
        override fun setLinkReady(ready: Boolean) = owner()
        override fun close() { owner(); listeners.clear(); streams.clear(); pending.set(0) }
    }
}
