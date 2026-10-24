package com.shilapi.xcertplay.network.backend.lwip

import com.shilapi.xcertplay.network.backend.*
import com.shilapi.xcertplay.transport.NcmSendResult
import java.util.concurrent.*
import java.util.concurrent.atomic.*
import org.junit.Assert.*
import org.junit.Test

class LwipSessionNetworkTest {
    private val ip = ByteArray(16).also { it[0] = 0xfd.toByte(); it[15] = 1 }
    private val peer = ip.copyOf().also { it[15] = 2 }
    private fun endpoint(address: ByteArray = ip, port: Int = 0, link: String = "session") = NetworkEndpoint(address, port, link)
    private fun failure(kind: BackendFailure.Kind, body: () -> Unit) {
        val error = runCatching(body).exceptionOrNull()
        assertTrue("$error", error is BackendFailure); assertEquals(kind, (error as BackendFailure).kind)
    }
    private fun await(condition: () -> Boolean) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (!condition() && System.nanoTime() < end) Thread.sleep(1)
        assertTrue(condition())
    }
    private class Call(body: () -> Unit) {
        val result = AtomicReference<Result<Unit>>()
        private val thread = Thread { result.set(runCatching(body)) }.apply { isDaemon = true; start() }
        fun finish() { thread.join(2000); assertFalse(thread.isAlive); result.get().getOrThrow() }
    }
    private inner class Fixture : AutoCloseable {
        val port = Port(); val core = Core()
        val runner = LwipPrototype(port) { core.also { it.owner = Thread.currentThread() } }
        val network = LwipSessionNetwork.start(runner, "session")
        override fun close() = network.close()
    }
    @Test(timeout = 10_000) fun preservesLinkClockAndPassesReusePolicyToOwner() = Fixture().use { f ->
        assertEquals("session", f.network.linkId); assertSame(f.runner.commandClock, f.network.clock)
        val listener = f.network.listen(endpoint(), reuseAddress = true)
        assertTrue(f.core.reuse.get()); assertEquals(endpoint(port = 45000), listener.localEndpoint)
        f.network.bindUdp(endpoint()).close()
        val stream = f.network.connect(endpoint(peer, 9000))
        assertEquals(endpoint(peer, 9000), stream.remoteEndpoint)
        stream.close(); listener.close()
    }
    @Test(timeout = 10_000) fun foreignLinkAndIpv4NeverReachCoreOrReserveResources() = Fixture().use { f ->
        assertTrue(runCatching { f.network.listen(endpoint(link = "other")) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { f.network.connect(endpoint(peer, 9000, "other")) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { f.network.bindUdp(endpoint(ByteArray(4))) }.exceptionOrNull() is IllegalArgumentException)
        assertEquals(0, f.runner.snapshot().resourceScopes); assertEquals(0, f.core.tokens.size)
    }
    @Test(timeout = 10_000) fun closeCancelsAllKindsOfPendingOperationsAndDestroysContextOnce() = Fixture().use { f ->
        val listener = f.network.listen(endpoint()); val udp = f.network.bindUdp(endpoint())
        val stream = f.network.connect(endpoint(peer, 9000)); f.core.handshake.set(false)
        val calls = listOf(
            Call { listener.accept() }, Call { f.network.connect(endpoint(peer, 9000)) },
            Call { stream.read(ByteArray(1)) }, Call { stream.write(byteArrayOf(1)) },
            Call { udp.receive(ByteArray(1)) }, Call { udp.send(byteArrayOf(1), endpoint(peer, 9000)) },
        )
        await { f.runner.snapshot().commandCount == 6 }
        f.network.close(); calls.forEach { call -> failure(BackendFailure.Kind.CANCELLED) { call.finish() } }
        f.network.close(); listener.close(); stream.close(); udp.close()
        assertEquals(1, f.core.destroys.get()); assertEquals(0, f.core.tokens.size)
        assertEquals(0, f.runner.snapshot().resourceScopes); assertEquals(0, f.runner.snapshot().commandCount)
        failure(BackendFailure.Kind.CANCELLED) { f.network.listen(endpoint()) }
        failure(BackendFailure.Kind.CANCELLED) { f.network.bindUdp(endpoint()) }
        failure(BackendFailure.Kind.CANCELLED) { f.network.connect(endpoint(peer, 9000)) }
    }
    @Test(timeout = 10_000) fun startupFailureClosesOwnedPortAndPreservesCause() {
        val port = Port(); val runner = LwipPrototype(port) { throw BackendFailure(BackendFailure.Kind.NATIVE_UNAVAILABLE) }
        failure(BackendFailure.Kind.NATIVE_UNAVAILABLE) { LwipSessionNetwork.start(runner, "session") }
        assertEquals(1, port.closes.get()); assertEquals(LwipPrototype.State.STOPPED, runner.snapshot().state)
    }
    @Test fun missingNativeLibraryBecomesAClassifiedUnavailableFailure() {
        val error = runCatching {
            NativePrototypeCore.create(
                mac = ByteArray(6),
                ip = ByteArray(16),
                mtu = 1500,
                ensureNativeLoaded = { throw UnsatisfiedLinkError("test missing library") },
            )
        }.exceptionOrNull()
        assertTrue("$error", error is BackendFailure)
        assertEquals(BackendFailure.Kind.NATIVE_UNAVAILABLE, (error as BackendFailure).kind)
    }
    private class Port : EthernetFramePort {
        override val hostMac: ByteArray? = null
        val frames = LinkedBlockingQueue<ByteArray>(); val closes = AtomicInteger()
        override fun send(frame: ByteArray, timeoutMillis: Int) = NcmSendResult.Sent
        override fun recv(timeoutMillis: Long) = frames.poll(timeoutMillis, TimeUnit.MILLISECONDS)
        override fun close() { closes.incrementAndGet(); frames.offer(ByteArray(14)) }
    }
    private inner class Core : LwipTcpCore, LwipUdpCore {
        lateinit var owner: Thread
        val tokens = ConcurrentHashMap.newKeySet<Long>(); val destroys = AtomicInteger()
        val reuse = AtomicBoolean(); val handshake = AtomicBoolean(true); private var next = 1L
        private fun owner() { assertSame(owner, Thread.currentThread()) }
        private fun token(): Long { owner(); return next++.also { tokens.add(it) } }
        private fun valid(token: Long) { owner(); assertTrue(tokens.contains(token)) }
        override val tcpLocalAddress: ByteArray get() { owner(); return ip.copyOf() }
        override val udpLocalAddress: ByteArray get() { owner(); return ip.copyOf() }
        override fun tcpListen(port: Int, backlog: Int, reuseAddress: Boolean): LwipTcpCore.Listener {
            owner(); reuse.set(reuseAddress); return LwipTcpCore.Listener(token(), 45000)
        }
        override fun tcpAccept(listener: Long): CoreAttempt<LwipTcpCore.Accepted> { valid(listener); return CoreAttempt.Pending }
        override fun tcpConnect(address: ByteArray, port: Int): CoreAttempt<Long> = CoreAttempt.Ready(token())
        override fun tcpConnectStatus(token: Long): CoreAttempt<LwipTcpCore.Peer> {
            valid(token); return if (handshake.get()) CoreAttempt.Ready(LwipTcpCore.Peer(ip.copyOf(), 45001, peer.copyOf(), 9000)) else CoreAttempt.Pending
        }
        override fun tcpRead(token: Long, bytes: ByteArray, offset: Int, length: Int): CoreAttempt<Int> { valid(token); return CoreAttempt.Pending }
        override fun tcpWrite(token: Long, bytes: ByteArray, offset: Int, length: Int): CoreAttempt<Int> { valid(token); return CoreAttempt.Pending }
        override fun tcpShutdownOutput(token: Long): CoreAttempt<Unit> { valid(token); return CoreAttempt.Ready(Unit) }
        override fun tcpConfigureLiveness(token: Long) = valid(token)
        override fun tcpClose(token: Long) { valid(token); tokens.remove(token) }
        override fun tcpCloseGraceful(token: Long) = tcpClose(token)
        override fun tcpListenerClose(token: Long) = tcpClose(token)
        override fun udpBind(port: Int): LwipUdpCore.Binding = LwipUdpCore.Binding(token(), 45002)
        override fun udpReceive(token: Long, bytes: ByteArray, source: ByteArray, metadata: IntArray): CoreAttempt<Unit> { valid(token); return CoreAttempt.Pending }
        override fun udpSend(token: Long, address: ByteArray, port: Int, bytes: ByteArray): CoreAttempt<Unit> { valid(token); return CoreAttempt.Pending }
        override fun udpClose(token: Long) = tcpClose(token)
        override fun udpSetQueueMaxAge(token: Long, maxAgeMillis: Long) { valid(token) }
        override fun udpExpiredCount(token: Long): Long { valid(token); return 0 }
        override fun prepare() = owner()
        override fun input(frame: ByteArray) = owner()
        override fun poll() = owner()
        override fun nextTimeoutMillis(): Long { owner(); return 60_000 }
        override fun takeOutput(): ByteArray? { owner(); return null }
        override fun setLinkReady(ready: Boolean) = owner()
        override fun close() { owner(); destroys.incrementAndGet(); tokens.clear() }
    }
}
