package com.shilapi.xcertplay.network.backend.lwip

import com.shilapi.xcertplay.network.backend.*
import com.shilapi.xcertplay.transport.NcmSendResult
import java.io.ByteArrayOutputStream
import java.util.concurrent.*
import java.util.concurrent.atomic.*
import org.junit.Assert.*
import org.junit.Test

class LwipTcpStreamTest {
    private val ip = ByteArray(16).also { it[0] = 0xfd.toByte(); it[15] = 1 }
    private val peer = ip.copyOf().also { it[15] = 2 }
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
        fun connect(deadline: Long = 0) = LwipTcpStream.connect(runner, NetworkEndpoint(peer, 9000, "link"), deadline)
        fun progress() { port.frames.offer(ByteArray(14)) }
        fun deadline() = runner.commandClock.nowMillis() + 40
        override fun close() = runner.close()
    }
    @Test(timeout = 10_000) fun connectReportsRealEndpointsAndClosesIdempotentlyOnOwner() = Fixture().use { f ->
        val stream = f.connect()
        assertEquals(NetworkEndpoint(ip, 45000, "link"), stream.localEndpoint)
        assertEquals(NetworkEndpoint(peer, 9000, "link"), stream.remoteEndpoint)
        stream.localEndpoint.address.fill(0)
        assertArrayEquals(ip, stream.localEndpoint.address)
        stream.close(); stream.close()
        await { f.core.graceful.get() == 1 && f.runner.snapshot().resourceScopes == 0 }
        assertEquals(0, f.core.aborts.get())
        failure(BackendFailure.Kind.CANCELLED) { stream.read(ByteArray(1)) }
        assertNull(f.runner.snapshot().failure)
    }
    @Test(timeout = 10_000) fun largeWriteCompletesShortWritesWithBoundedOwnedChunks() = Fixture().use { f ->
        val stream = f.connect(); val bytes = ByteArray(300_004) { (it % 251).toByte() }
        stream.write(bytes, 2, 300_000)
        assertArrayEquals(bytes.copyOfRange(2, 300_002), f.core.sent.toByteArray())
        assertTrue(f.core.maxWrite.get() <= 32768)
        assertEquals(0, f.runner.snapshot().commandBytes)
        stream.close()
    }
    @Test(timeout = 10_000) fun pendingWriteOwnsCopyAllowsReaderAndRejectsOverlappingWriter() = Fixture().use { f ->
        val stream = f.connect(); f.core.writeBlocked.set(true)
        val bytes = byteArrayOf(1, 2, 3)
        val write = Call { stream.write(bytes) }
        assertTrue(f.core.writing.await(2, TimeUnit.SECONDS)); bytes.fill(0)
        assertEquals(1, f.runner.snapshot().directBufferLeasedCount)
        failure(BackendFailure.Kind.RESOURCE_LIMIT) { stream.write(byteArrayOf(4)) }
        failure(BackendFailure.Kind.RESOURCE_LIMIT) { stream.shutdownOutput() }
        f.core.incoming.add(byteArrayOf(8)); f.progress()
        val target = ByteArray(3) { 9 }
        assertEquals(1, stream.read(target, 1, 1)); assertArrayEquals(byteArrayOf(9, 8, 9), target)
        f.core.writeBlocked.set(false); f.progress(); write.finish().getOrThrow()
        assertArrayEquals(byteArrayOf(1, 2, 3), f.core.sent.toByteArray())
        assertEquals(0, f.runner.snapshot().directBufferLeasedCount)
        stream.close()
        f.runner.close()
        assertEquals(0, f.runner.snapshot().directBufferAllocatedBytes)
    }
    @Test(timeout = 10_000) fun timeoutWithoutReadCommitLeavesTargetAndStreamUsable() = Fixture().use { f ->
        val stream = f.connect(); val bytes = byteArrayOf(99)
        failure(BackendFailure.Kind.TIMEOUT) { stream.read(bytes, 0, 1, f.deadline()) }
        assertArrayEquals(byteArrayOf(99), bytes)
        f.core.incoming.add(byteArrayOf(42)); f.progress()
        assertEquals(1, stream.read(bytes)); assertArrayEquals(byteArrayOf(42), bytes)
        f.core.eof.set(true); assertEquals(-1, stream.read(bytes)); assertEquals(0, stream.read(bytes, 0, 0))
        assertTrue(runCatching { stream.read(bytes, Int.MAX_VALUE, 1) }.exceptionOrNull() is IllegalArgumentException)
        stream.close()
    }
    @Test(timeout = 10_000) fun closeCancelsBothDirectionsAndLeavesOtherStreamUsable() = Fixture().use { f ->
        val first = f.connect(); val second = f.connect(); f.core.writeBlocked.set(true)
        val read = Call { first.read(ByteArray(1)) }; val write = Call { first.write(byteArrayOf(1)) }
        assertTrue(f.core.reading.await(2, TimeUnit.SECONDS)); assertTrue(f.core.writing.await(2, TimeUnit.SECONDS))
        first.close()
        failure(BackendFailure.Kind.CANCELLED) { read.finish().getOrThrow() }
        failure(BackendFailure.Kind.CANCELLED) { write.finish().getOrThrow() }
        f.core.incoming.add(byteArrayOf(4)); f.progress(); assertEquals(1, second.read(ByteArray(1)))
        second.close(); await { f.runner.snapshot().resourceScopes == 0 }
    }
    @Test(timeout = 10_000) fun handshakeTimeoutAbortsOneCandidateWithoutLeakingScope() = Fixture().use { f ->
        f.core.handshake.set(false)
        failure(BackendFailure.Kind.TIMEOUT) { f.connect(f.deadline()) }
        await { f.core.aborts.get() == 1 && f.runner.snapshot().resourceScopes == 0 }
        assertEquals(1, f.core.begins.get())
        f.core.handshake.set(true); f.connect().close()
    }
    @Test(timeout = 10_000) fun resetOptionAndShutdownUseOwnerAndLivenessNeverUsesSystemFd() = Fixture().use { f ->
        val stream = f.connect(); stream.configure(TcpOptions(resetOnClose = true))
        var diagnostic = ""
        stream.configureLiveness { diagnostic = it }
        assertTrue(diagnostic.contains("lwIP")); assertEquals(1, f.core.health.get())
        stream.shutdownOutput(); stream.shutdownOutput()
        failure(BackendFailure.Kind.PEER_CLOSED) { stream.write(byteArrayOf(1)) }
        stream.close(); await { f.core.aborts.get() == 1 }
        assertEquals(0, f.core.graceful.get())
    }
    @Test(timeout = 10_000) fun abandonedCommittedReadClosesStreamRatherThanHidingMissingBytes() = Fixture().use { f ->
        val stream = f.connect(); val gate = CountDownLatch(1); f.core.readGate.set(gate)
        f.core.incoming.add(byteArrayOf(3)); val target = byteArrayOf(99)
        val read = Call { stream.read(target, 0, 1, f.runner.commandClock.nowMillis() + 200) }
        try {
            assertTrue(f.core.reading.await(2, TimeUnit.SECONDS))
            failure(BackendFailure.Kind.TIMEOUT) { read.finish().getOrThrow() }
            assertArrayEquals(byteArrayOf(99), target)
            assertEquals(1, f.runner.snapshot().directBufferLeasedCount)
        } finally { gate.countDown() }
        await { f.runner.snapshot().resourceScopes == 0 }
        assertEquals(0, f.runner.snapshot().directBufferLeasedCount)
        failure(BackendFailure.Kind.CANCELLED) { stream.read(target) }
    }
    @Test(timeout = 10_000) fun allocationFailureIsStructuredAndDoesNotStopOwner() = Fixture().use { f ->
        val stream = f.connect(); f.core.writeFailure.set(OutOfMemoryError("injected"))
        failure(BackendFailure.Kind.RESOURCE_LIMIT) { stream.write(byteArrayOf(1)) }
        assertEquals(0, f.runner.snapshot().directBufferLeasedCount)
        assertNull(f.runner.snapshot().failure); stream.write(byteArrayOf(2)); stream.close()
    }
    @Test(timeout = 10_000) fun writeDeadlineAfterPrefixNeverReplaysAbandonedSuffix() = Fixture().use { f ->
        val stream = f.connect(); f.core.blockAfter.set(1)
        val write = Call { stream.write(ByteArray(40_000) { 7 }, 0, 40_000, f.runner.commandClock.nowMillis() + 200) }
        await { f.core.writeCalls.get() >= 2 }
        failure(BackendFailure.Kind.TIMEOUT) { write.finish().getOrThrow() }
        assertEquals(4096, f.core.sent.size())
        f.core.blockAfter.set(Int.MAX_VALUE); f.progress(); stream.write(byteArrayOf(8, 9))
        assertEquals(4098, f.core.sent.size()); assertArrayEquals(byteArrayOf(8, 9), f.core.sent.toByteArray().takeLast(2).toByteArray())
        stream.close()
    }
    @Test(timeout = 10_000) fun interruptionCancelsPendingReadAndRestoresDirectionGate() = Fixture().use { f ->
        val stream = f.connect(); val target = byteArrayOf(99)
        val read = Call { stream.read(target) }
        assertTrue(f.core.reading.await(2, TimeUnit.SECONDS)); read.thread.interrupt()
        failure(BackendFailure.Kind.CANCELLED) { read.finish().getOrThrow() }
        assertArrayEquals(byteArrayOf(99), target)
        f.core.incoming.add(byteArrayOf(5)); f.progress(); assertEquals(1, stream.read(target))
        stream.close()
    }
    @Test(timeout = 10_000) fun failedReadAfterConsumptionRetiresStreamWithoutTouchingCallerBuffer() = Fixture().use { f ->
        val stream = f.connect(); val target = byteArrayOf(99)
        f.core.incoming.add(byteArrayOf(4)); f.core.readFailure.set(OutOfMemoryError("after consumption"))
        failure(BackendFailure.Kind.RESOURCE_LIMIT) { stream.read(target) }
        assertArrayEquals(byteArrayOf(99), target)
        await { f.runner.snapshot().resourceScopes == 0 }
        failure(BackendFailure.Kind.CANCELLED) { stream.read(target) }
        assertNull(f.runner.snapshot().failure)
    }
    @Test(timeout = 10_000) fun gracefulCloseModeCanBeSetWhileCoreIsBlockedInAnotherOperation() = Fixture().use { f ->
        val stream = f.connect()
        stream.configure(TcpOptions(resetOnClose = true))
        val gate = CountDownLatch(1)
        f.core.readGate.set(gate)
        f.core.incoming.add(byteArrayOf(1))
        val read = Call { stream.read(ByteArray(1)) }
        val configured = CountDownLatch(1)
        var configure: Call<Unit>? = null
        try {
            assertTrue(f.core.reading.await(2, TimeUnit.SECONDS))
            configure = Call { stream.configure(TcpOptions(resetOnClose = false)); configured.countDown() }
            assertTrue("local close mode must not wait for the core", configured.await(100, TimeUnit.MILLISECONDS))
        } finally {
            gate.countDown()
            read.finish().getOrThrow()
            configure?.finish()?.getOrThrow()
            stream.close()
        }
        await { f.core.graceful.get() == 1 }
        assertEquals(0, f.core.aborts.get())
    }
    private class Port : EthernetFramePort {
        override val hostMac: ByteArray? = null
        val frames = LinkedBlockingQueue<ByteArray>()
        override fun send(frame: ByteArray, timeoutMillis: Int) = NcmSendResult.Sent
        override fun recv(timeoutMillis: Long) = frames.poll(timeoutMillis, TimeUnit.MILLISECONDS)
        override fun close() { frames.offer(ByteArray(14)) }
    }
    private inner class Core : LwipTcpCore {
        override val tcpLocalAddress: ByteArray get() { owner(); return ip.copyOf() }
        lateinit var owner: Thread
        val tokens = ConcurrentHashMap.newKeySet<Long>()
        val handshake = AtomicBoolean(true); val writeBlocked = AtomicBoolean(); val eof = AtomicBoolean()
        val begins = AtomicInteger(); val aborts = AtomicInteger(); val graceful = AtomicInteger(); val health = AtomicInteger()
        val maxWrite = AtomicInteger(); val writing = CountDownLatch(1); val reading = CountDownLatch(1)
        val writeCalls = AtomicInteger(); val blockAfter = AtomicInteger(Int.MAX_VALUE)
        val incoming = ConcurrentLinkedQueue<ByteArray>(); val sent = ByteArrayOutputStream()
        val readGate = AtomicReference<CountDownLatch>(); val writeFailure = AtomicReference<Throwable>()
        val readFailure = AtomicReference<Throwable>()
        private var next = 1L; private var outputClosed = false
        private fun owner() { assertSame(owner, Thread.currentThread()) }
        private fun valid(token: Long) { owner(); assertTrue(tokens.contains(token)) }
        override fun tcpConnect(address: ByteArray, port: Int): CoreAttempt<Long> {
            owner(); assertArrayEquals(peer, address); assertEquals(9000, port); begins.incrementAndGet()
            return CoreAttempt.Ready(next++.also { tokens.add(it) })
        }
        override fun tcpConnectStatus(token: Long): CoreAttempt<LwipTcpCore.Peer> {
            valid(token)
            return if (handshake.get()) CoreAttempt.Ready(LwipTcpCore.Peer(ip.copyOf(), 45000, peer.copyOf(), 9000)) else CoreAttempt.Pending
        }
        override fun tcpRead(token: Long, bytes: ByteArray, offset: Int, length: Int): CoreAttempt<Int> {
            valid(token); reading.countDown(); readGate.getAndSet(null)?.let { assertTrue(it.await(2, TimeUnit.SECONDS)) }
            val packet = incoming.poll() ?: return if (eof.get()) CoreAttempt.Ready(-1) else CoreAttempt.Pending
            val count = minOf(length, packet.size); packet.copyInto(bytes, offset, 0, count)
            if (count < packet.size) incoming.add(packet.copyOfRange(count, packet.size))
            readFailure.getAndSet(null)?.let { throw it }
            return CoreAttempt.Ready(count)
        }
        override fun tcpWrite(token: Long, bytes: ByteArray, offset: Int, length: Int): CoreAttempt<Int> {
            valid(token); writing.countDown(); writeFailure.getAndSet(null)?.let { throw it }
            if (outputClosed) throw BackendFailure(BackendFailure.Kind.PEER_CLOSED)
            maxWrite.accumulateAndGet(length, ::maxOf)
            if (writeCalls.incrementAndGet() > blockAfter.get() || writeBlocked.get()) return CoreAttempt.Pending
            val count = minOf(length, 4096); sent.write(bytes, offset, count); return CoreAttempt.Ready(count)
        }
        override fun tcpConfigureLiveness(token: Long) { valid(token); health.incrementAndGet() }
        override fun tcpShutdownOutput(token: Long): CoreAttempt<Unit> { valid(token); outputClosed = true; return CoreAttempt.Ready(Unit) }
        override fun tcpClose(token: Long) { valid(token); tokens.remove(token); aborts.incrementAndGet() }
        override fun tcpCloseGraceful(token: Long) { valid(token); tokens.remove(token); graceful.incrementAndGet() }
        override fun tcpListen(port: Int, backlog: Int, reuseAddress: Boolean): LwipTcpCore.Listener = error("not used")
        override fun tcpAccept(listener: Long): CoreAttempt<LwipTcpCore.Accepted> = error("not used")
        override fun tcpListenerClose(token: Long) = error("not used")
        override fun prepare() = owner()
        override fun input(frame: ByteArray) = owner()
        override fun poll() = owner()
        override fun nextTimeoutMillis(): Long { owner(); return 60_000 }
        override fun takeOutput(): ByteArray? { owner(); return null }
        override fun setLinkReady(ready: Boolean) = owner()
        override fun close() { owner(); tokens.clear() }
    }
}
