package com.shilapi.xcertplay.network.backend.lwip

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.shilapi.xcertplay.network.backend.EthernetFramePort
import com.shilapi.xcertplay.network.backend.BackendFailure
import com.shilapi.xcertplay.network.backend.MonotonicClock
import com.shilapi.xcertplay.network.backend.NetworkEndpoint
import com.shilapi.xcertplay.transport.NcmSendResult
import java.security.SecureRandom
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativePrototypeInstrumentationTest {
    @Test fun nativeSessionHasNoEchoOwnsEndpointsAndCancelsWaiters() {
        val incoming = LinkedBlockingQueue<ByteArray>()
        val port = object : EthernetFramePort {
            override val hostMac = mac.copyOf()
            override fun send(frame: ByteArray, timeoutMillis: Int) = NcmSendResult.Sent
            override fun recv(timeoutMillis: Long) = incoming.poll(timeoutMillis, TimeUnit.MILLISECONDS)
            override fun close() { incoming.offer(ByteArray(14)) }
        }
        val network = LwipSessionNetwork.native(port, mac, ip, "session")
        try {
            // These are the prototype echo ports. Both must be free in a session core.
            val listener = network.listen(NetworkEndpoint(ip, 7000, "session"), reuseAddress = true)
            val udp = network.bindUdp(NetworkEndpoint(ip, 7001, "session"))
            try { network.listen(listener.localEndpoint, reuseAddress = true); fail("duplicate listener") }
            catch (error: BackendFailure) { assertEquals(BackendFailure.Kind.ADDRESS_CONFLICT, error.kind) }
            val acceptError = AtomicReference<Throwable>(); val receiveError = AtomicReference<Throwable>()
            val started = java.util.concurrent.CountDownLatch(2)
            val accepter = Thread { started.countDown(); acceptError.set(runCatching { listener.accept() }.exceptionOrNull()) }
            val receiver = Thread { started.countDown(); receiveError.set(runCatching { udp.receive(ByteArray(1)) }.exceptionOrNull()) }
            accepter.start(); receiver.start(); assertTrue(started.await(2, TimeUnit.SECONDS))
            network.close(); network.close()
            accepter.join(2000); receiver.join(2000); assertFalse(accepter.isAlive); assertFalse(receiver.isAlive)
            assertEquals(BackendFailure.Kind.CANCELLED, (acceptError.get() as BackendFailure).kind)
            assertEquals(BackendFailure.Kind.CANCELLED, (receiveError.get() as BackendFailure).kind)
            listener.close(); udp.close()
        } finally { network.close() }
    }

    @Test fun publicTcpListenerAcceptsRealPeerAndChildSurvivesListenerClose() {
        val incoming = LinkedBlockingQueue<ByteArray>(); val outgoing = LinkedBlockingQueue<ByteArray>()
        val port = object : EthernetFramePort {
            override val hostMac = mac.copyOf()
            override fun send(frame: ByteArray, timeoutMillis: Int): NcmSendResult {
                outgoing.add(frame.copyOf()); return NcmSendResult.Sent
            }
            override fun recv(timeoutMillis: Long) = incoming.poll(timeoutMillis, TimeUnit.MILLISECONDS)
            override fun close() { incoming.offer(ByteArray(14)) }
        }
        val base = coreCommandClock(); val offset = AtomicLong(100)
        val clock = MonotonicClock { base.nowMillis() + offset.get() }
        val runner = LwipPrototype(port, commandClock = clock) { NativePrototypeCore.create(mac, ip, 1500, clockMillis = clock::nowMillis) }
        var accepter: Thread? = null
        fun nextTcp(accept: (ByteArray) -> Boolean): ByteArray {
            val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (System.nanoTime() < end) {
                val frame = outgoing.poll(100, TimeUnit.MILLISECONDS) ?: continue
                if (frame.size >= 74 && frame[20] == 6.toByte() && accept(frame)) return frame
            }
            throw AssertionError("TCP frame timeout")
        }
        try {
            runner.start()
            val listener = LwipTcpListener.bind(runner, NetworkEndpoint(ByteArray(16), 0, "native-test"))
            assertArrayEquals(ip, listener.localEndpoint.address); val localPort = listener.localEndpoint.port
            assertTrue(localPort in 1..65535)
            repeat(16) { offset.addAndGet(250); runner.executeCommand { it.poll(); CoreAttempt.Ready(Unit) } }
            incoming.put(packet(58, 32).also {
                it[21] = 255.toByte(); it[54] = 135.toByte(); ip.copyInto(it, 62)
                it[78] = 1; it[79] = 1; peerMac.copyInto(it, 80); checksum(it, 58, 56)
            })
            val result = AtomicReference<Result<com.shilapi.xcertplay.network.backend.TcpStream>>()
            accepter = Thread { result.set(runCatching { listener.accept(clock.nowMillis() + 2000) }) }.apply { start() }
            incoming.put(tcpPacket(50000, localPort, 2, 1000, 0))
            val syn = nextTcp { it[67].toInt() and 18 == 18 }; val sequence = read32(syn, 58)
            incoming.put(tcpPacket(50000, localPort, 24, 1001, sequence + 1, "hi".toByteArray()))
            accepter.join(2000); assertFalse(accepter.isAlive)
            val stream = result.get().getOrThrow()
            assertEquals(listener.localEndpoint, stream.localEndpoint)
            assertEquals(NetworkEndpoint(ip.copyOf().also { it[15] = 2 }, 50000, "native-test"), stream.remoteEndpoint)
            listener.close(); listener.close()
            val target = ByteArray(2)
            assertEquals(2, stream.read(target, 0, 2, clock.nowMillis() + 2000)); assertArrayEquals("hi".toByteArray(), target)
            stream.write("ok".toByteArray(), 0, 2, clock.nowMillis() + 2000)
            val sent = nextTcp { it.size > 54 + ((it[66].toInt() and 255) ushr 4) * 4 }
            assertArrayEquals("ok".toByteArray(), sent.copyOfRange(54 + ((sent[66].toInt() and 255) ushr 4) * 4, sent.size))
            incoming.put(tcpPacket(50000, localPort, 16, 1003, sequence + 3))
            stream.close(); val fin = nextTcp { it[67].toInt() and 1 != 0 }
            assertEquals(0, fin[67].toInt() and 4)
            incoming.put(tcpPacket(50000, localPort, 17, 1003, sequence + 4))
            val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (runner.snapshot().resourceScopes != 0 && System.nanoTime() < end) Thread.sleep(1)
            assertEquals(0, runner.snapshot().resourceScopes); assertNull(runner.snapshot().failure)
        } finally { runner.close(); accepter?.join(2000) }
    }

    @Test fun publicTcpStreamTraversesRunnerHandshakeReadWriteAndGracefulClose() {
        val incoming = LinkedBlockingQueue<ByteArray>(); val outgoing = LinkedBlockingQueue<ByteArray>()
        val port = object : EthernetFramePort {
            override val hostMac = mac.copyOf()
            override fun send(frame: ByteArray, timeoutMillis: Int): NcmSendResult {
                outgoing.add(frame.copyOf()); return NcmSendResult.Sent
            }
            override fun recv(timeoutMillis: Long) = incoming.poll(timeoutMillis, TimeUnit.MILLISECONDS)
            override fun close() { incoming.offer(ByteArray(14)) }
        }
        val base = coreCommandClock(); val offset = AtomicLong(100)
        val clock = MonotonicClock { base.nowMillis() + offset.get() }
        val runner = LwipPrototype(port, commandClock = clock) { NativePrototypeCore.create(mac, ip, 1500, clockMillis = clock::nowMillis) }
        var connector: Thread? = null
        fun nextTcp(accept: (ByteArray) -> Boolean): ByteArray {
            val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (System.nanoTime() < end) {
                val frame = outgoing.poll(100, TimeUnit.MILLISECONDS) ?: continue
                if (frame.size >= 74 && frame[20] == 6.toByte() && accept(frame)) return frame
            }
            throw AssertionError("TCP frame timeout")
        }
        try {
            runner.start()
            repeat(16) {
                offset.addAndGet(250); runner.executeCommand { it.poll(); CoreAttempt.Ready(Unit) }
            }
            incoming.put(packet(58, 32).also {
                it[21] = 255.toByte(); it[54] = 135.toByte(); ip.copyInto(it, 62)
                it[78] = 1; it[79] = 1; peerMac.copyInto(it, 80); checksum(it, 58, 56)
            })
            val result = AtomicReference<Result<LwipTcpStream>>()
            connector = Thread { result.set(runCatching {
                LwipTcpStream.connect(runner, NetworkEndpoint(ip.copyOf().also { it[15] = 2 }, 9000, "native-test"), clock.nowMillis() + 2000)
            }) }.apply { start() }
            val syn = nextTcp { it[67].toInt() and 2 != 0 }; val localPort = read16(syn, 54); val sequence = read32(syn, 58)
            incoming.put(tcpPacket(9000, localPort, 18, 7000, sequence + 1))
            connector.join(2000); assertFalse(connector.isAlive)
            val stream = result.get().getOrThrow()
            assertEquals(localPort, stream.localEndpoint.port); assertEquals(9000, stream.remoteEndpoint.port)
            stream.configureLiveness { assertTrue(it.contains("lwIP")) }
            incoming.put(tcpPacket(9000, localPort, 25, 7001, sequence + 1, "hello".toByteArray()))
            val target = ByteArray(5) { 9 }
            assertEquals(3, stream.read(target, 1, 3, clock.nowMillis() + 2000))
            assertArrayEquals(byteArrayOf(9, 104, 101, 108, 9), target)
            assertEquals(2, stream.read(target, 0, 5, clock.nowMillis() + 2000))
            assertEquals(-1, stream.read(target, 0, 5, clock.nowMillis() + 2000))
            stream.write("reply".toByteArray(), 0, 5, clock.nowMillis() + 2000)
            val sent = nextTcp { it.size > 54 + ((it[66].toInt() and 255) ushr 4) * 4 }
            assertArrayEquals("reply".toByteArray(), sent.copyOfRange(54 + ((sent[66].toInt() and 255) ushr 4) * 4, sent.size))
            incoming.put(tcpPacket(9000, localPort, 16, 7007, sequence + 6))
            stream.close(); stream.close()
            val fin = nextTcp { it[67].toInt() and 1 != 0 }
            assertEquals(0, fin[67].toInt() and 4)
            incoming.put(tcpPacket(9000, localPort, 16, 7007, sequence + 7))
            val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (runner.snapshot().resourceScopes != 0 && System.nanoTime() < end) Thread.sleep(1)
            assertEquals(0, runner.snapshot().resourceScopes); assertNull(runner.snapshot().failure)
        } finally { runner.close(); connector?.join(2000) }
    }

    @Test fun tcpGracefulJniHandsFinToProtocolAndInvalidatesApplicationToken() {
        LwipNativeBindings.ensureLoaded()
        val h = LwipNativeBindings.create(mac, ip, 1500, 100, ByteArray(1024).also { SecureRandom().nextBytes(it) })
        try {
            rawReady(h)
            val listener = LongArray(2)
            assertEquals(0, LwipNativeBindings.tcpListen(h, 7000, 2, listener))
            assertEquals(0, LwipNativeBindings.input(h, tcpPacket(50000, 7000, 2, 1000, 0)))
            val syn = rawDrain(h).first { it[20] == 6.toByte() }; val server = read32(syn, 58)
            assertEquals(0, LwipNativeBindings.input(h, tcpPacket(50000, 7000, 16, 1001, server + 1)))
            val stream = LongArray(1)
            assertEquals(0, LwipNativeBindings.tcpAccept(h, listener[0], stream, ByteArray(16), ByteArray(16), IntArray(2)))
            val count = IntArray(1)
            assertEquals(0, LwipNativeBindings.tcpWrite(h, stream[0], "tail".toByteArray(), 0, 4, count))
            assertEquals(0, LwipNativeBindings.tcpCloseGraceful(h, stream[0]))
            assertEquals(-1, LwipNativeBindings.tcpCloseGraceful(h, stream[0]))
            val frames = rawDrain(h).filter { it[20] == 6.toByte() }
            assertTrue(frames.any { it[67].toInt() and 1 != 0 })
            assertTrue(frames.none { it[67].toInt() and 4 != 0 })
            assertEquals(-1, LwipNativeBindings.tcpRead(h, stream[0], ByteArray(1), 0, 1, count))
            assertEquals(0, LwipNativeBindings.input(h, tcpPacket(50000, 7000, 17, 1001, server + 6)))
            assertEquals(0, LwipNativeBindings.tcpListenerClose(h, listener[0]))
        } finally { assertEquals(0, LwipNativeBindings.destroy(h)) }
    }

    @Test fun typedTcpCorePreservesPendingEofResetAndFailedContextCleanup() {
        var now = 100L
        val core = NativePrototypeCore.create(mac, ip, 1500) { now }
        try {
            val tcp: LwipTcpCore = core
            val peer = ip.copyOf().also { it[15] = 2 }
            assertEquals(CoreAttempt.Pending, tcp.tcpConnect(peer, 9000))
            val listener = tcp.tcpListen(7001, 2)
            assertEquals(7001, listener.port)
            assertEquals(CoreAttempt.Pending, tcp.tcpAccept(listener.token))
            core.setLinkReady(true)
            repeat(16) { now += 250; core.poll() }; drain(core)
            val ns = packet(58, 32).also {
                it[21] = 255.toByte(); it[54] = 135.toByte(); ip.copyInto(it, 62)
                it[78] = 1; it[79] = 1; peerMac.copyInto(it, 80); checksum(it, 58, 56)
            }
            core.input(ns); drain(core)
            core.input(tcpPacket(50000, 7001, 2, 1000, 0))
            val syn = drain(core).first { it[20] == 6.toByte() }; val server = read32(syn, 58)
            core.input(tcpPacket(50000, 7001, 16, 1001, server + 1))
            val accepted = (tcp.tcpAccept(listener.token) as CoreAttempt.Ready).value
            assertArrayEquals(ip, accepted.peer.localAddress)
            assertArrayEquals(peer, accepted.peer.remoteAddress)
            assertEquals(50000, accepted.peer.remotePort)
            val token = accepted.token; val target = ByteArray(5) { 9 }
            assertEquals(CoreAttempt.Pending, tcp.tcpRead(token, target, 0, 5))
            core.input(tcpPacket(50000, 7001, 25, 1001, server + 1, "abc".toByteArray()))
            assertEquals(CoreAttempt.Ready(3), tcp.tcpRead(token, target, 1, 3))
            assertArrayEquals(byteArrayOf(9, 97, 98, 99, 9), target)
            assertEquals(CoreAttempt.Ready(-1), tcp.tcpRead(token, target, 0, 5))
            assertEquals(CoreAttempt.Ready(0), tcp.tcpRead(token, target, 0, 0))
            assertEquals(CoreAttempt.Ready(Unit), tcp.tcpShutdownOutput(token))
            try { tcp.tcpWrite(token, target, 0, 1); fail("closed output") }
            catch (error: BackendFailure) { assertEquals(BackendFailure.Kind.PEER_CLOSED, error.kind) }
            tcp.tcpCloseGraceful(token)
            val active = (tcp.tcpConnect(peer, 9000) as CoreAttempt.Ready).value
            assertEquals(CoreAttempt.Pending, tcp.tcpConnectStatus(active))
            val outgoing = drain(core).last { it[20] == 6.toByte() && it[67].toInt() and 2 != 0 }
            val port = read16(outgoing, 54); val seq = read32(outgoing, 58)
            core.input(tcpPacket(9000, port, 18, 7000, seq + 1))
            val connected = (tcp.tcpConnectStatus(active) as CoreAttempt.Ready).value
            assertEquals(port, connected.localPort); assertEquals(9000, connected.remotePort)
            assertEquals(CoreAttempt.Ready(32768), tcp.tcpWrite(active, ByteArray(100000), 0, 100000))
            assertEquals(CoreAttempt.Pending, tcp.tcpWrite(active, target, 0, 1))
            core.input(tcpPacket(9000, port, 20, 7001, seq + 1))
            try { tcp.tcpRead(active, target, 0, 1); fail("peer reset") }
            catch (error: BackendFailure) { assertEquals(BackendFailure.Kind.PEER_RESET, error.kind) }
            now--
            try { core.poll(); fail("clock regression") } catch (_: BackendFailure) { }
            tcp.tcpCloseGraceful(active); tcp.tcpListenerClose(listener.token)
        } finally { core.close() }
    }

    @Test fun tcpJniAcceptReadWriteAndHalfCloseValidateBeforeConsuming() {
        LwipNativeBindings.ensureLoaded()
        assertEquals(6, LwipNativeBindings.abiVersion())
        val h = LwipNativeBindings.create(mac, ip, 1500, 100, ByteArray(1024).also { SecureRandom().nextBytes(it) })
        assertTrue(h > 0)
        try {
            val bound = LongArray(2)
            assertEquals(-1, LwipNativeBindings.tcpListenWithOptions(h, 7000, 2, true, LongArray(1)))
            assertEquals(-1, LwipNativeBindings.tcpListenWithOptions(h, 7000, 33, true, bound))
            assertEquals(-1, LwipNativeBindings.tcpListen(h, 7000, 2, LongArray(1)))
            assertEquals(0, LwipNativeBindings.tcpListen(h, 7000, 2, bound))
            assertEquals(7000L, bound[1])
            val listener = bound[0]
            assertEquals(-10, LwipNativeBindings.tcpListen(h, 7000, 2, LongArray(2)))
            rawReady(h)
            val handle = LongArray(1); val local = ByteArray(16); val remote = ByteArray(16); val ports = IntArray(2)
            assertEquals(-11, LwipNativeBindings.tcpAccept(h, listener, handle, local, remote, ports))
            assertEquals(0, LwipNativeBindings.input(h, tcpPacket(50000, 7000, 2, 1000, 0)))
            val syn = rawDrain(h).first { it[20] == 6.toByte() }
            val server = read32(syn, 58)
            assertEquals(0, LwipNativeBindings.input(h, tcpPacket(50000, 7000, 16, 1001, server + 1)))
            val data = tcpPacket(50000, 7000, 24, 1001, server + 1, "hello".toByteArray())
            assertEquals(0, LwipNativeBindings.input(h, data)); data.fill(0)
            assertEquals(-1, LwipNativeBindings.tcpAccept(h, listener, LongArray(0), local, remote, ports))
            assertEquals(-1, LwipNativeBindings.tcpAccept(h, listener, handle, local, local, ports))
            assertEquals(-1, LwipNativeBindings.tcpAccept(h, listener, handle, local, remote, IntArray(1)))
            assertEquals(0, LwipNativeBindings.tcpAccept(h, listener, handle, local, remote, ports))
            val stream = handle[0]
            assertArrayEquals(ip, local); assertArrayEquals(ip.copyOf().also { it[15] = 2 }, remote)
            assertArrayEquals(intArrayOf(7000, 50000), ports)
            val target = ByteArray(4) { 9 }; val count = intArrayOf(99)
            assertEquals(-1, LwipNativeBindings.tcpRead(h, stream, target, Int.MAX_VALUE, 2, count))
            assertEquals(-1, LwipNativeBindings.tcpRead(h, stream, target, 0, 1, IntArray(0)))
            assertEquals(0, LwipNativeBindings.tcpRead(h, stream, target, 1, 2, count))
            assertEquals(2, count[0]); assertArrayEquals(byteArrayOf(9, 104, 101, 9), target)
            assertEquals(0, LwipNativeBindings.tcpRead(h, stream, target, 0, 0, count)); assertEquals(0, count[0])
            assertEquals(0, LwipNativeBindings.tcpRead(h, stream, target, 0, 4, count))
            assertEquals(3, count[0]); assertArrayEquals("llo".toByteArray(), target.copyOf(3))
            assertEquals(-1, LwipNativeBindings.tcpWrite(h, stream, target, 0, -1, count))
            assertEquals(0, LwipNativeBindings.tcpWrite(h, stream, byteArrayOf(9, 111, 107, 9), 1, 2, count)); assertEquals(2, count[0])
            val sent = rawDrain(h).first { it[20] == 6.toByte() && it.size > 54 + ((it[66].toInt() and 255) ushr 4) * 4 }
            assertArrayEquals("ok".toByteArray(), sent.copyOfRange(54 + ((sent[66].toInt() and 255) ushr 4) * 4, sent.size))
            assertEquals(0, LwipNativeBindings.input(h, tcpPacket(50000, 7000, 16, 1006, server + 3)))
            val wrong = AtomicInteger()
            Thread { wrong.set(LwipNativeBindings.tcpClose(h, stream)) }.apply { start(); join(2000); assertFalse(isAlive) }
            assertEquals(-3, wrong.get())
            assertEquals(-1, LwipNativeBindings.tcpClose(h, listener))
            assertEquals(0, LwipNativeBindings.tcpShutdownOutput(h, stream))
            assertEquals(-12, LwipNativeBindings.tcpWrite(h, stream, target, 0, 1, count)); assertEquals(0, count[0])
            assertEquals(0, LwipNativeBindings.input(h, tcpPacket(50000, 7000, 17, 1006, server + 4)))
            assertEquals(-12, LwipNativeBindings.tcpRead(h, stream, target, 0, 1, count)); assertEquals(0, count[0])
            assertEquals(0, LwipNativeBindings.tcpClose(h, stream)); assertEquals(-1, LwipNativeBindings.tcpClose(h, stream))
            assertEquals(0, LwipNativeBindings.tcpListenerClose(h, listener))
        } finally { assertEquals(0, LwipNativeBindings.destroy(h)) }
    }

    @Test fun tcpJniActiveConnectShortWriteAndStaleTokens() {
        LwipNativeBindings.ensureLoaded()
        val entropy = ByteArray(1024).also { SecureRandom().nextBytes(it) }
        val h = LwipNativeBindings.create(mac, ip, 1500, 100, entropy)
        var stale = 0L
        try {
            val peer = ip.copyOf().also { it[15] = 2 }; val handle = LongArray(1)
            assertEquals(-11, LwipNativeBindings.tcpConnect(h, peer, 9000, handle)); assertEquals(0L, handle[0])
            rawReady(h)
            assertEquals(-1, LwipNativeBindings.tcpConnect(h, ByteArray(15), 9000, handle))
            assertEquals(-1, LwipNativeBindings.tcpConnect(h, peer, 9000, LongArray(0)))
            assertEquals(0, LwipNativeBindings.tcpConnect(h, peer, 9000, handle))
            val stream = handle[0]; val local = ByteArray(16); val remote = ByteArray(16); val ports = IntArray(2)
            assertEquals(-11, LwipNativeBindings.tcpConnectStatus(h, stream, local, remote, ports))
            assertEquals(-1, LwipNativeBindings.tcpConnectStatus(h, stream, local, local, ports))
            val syn = rawDrain(h).first { it[20] == 6.toByte() }
            val port = read16(syn, 54); val sequence = read32(syn, 58)
            assertEquals(0, LwipNativeBindings.input(h, tcpPacket(9000, port, 18, 7000, sequence + 1)))
            assertEquals(0, LwipNativeBindings.tcpConnectStatus(h, stream, local, remote, ports))
            assertArrayEquals(intArrayOf(port, 9000), ports); assertArrayEquals(peer, remote)
            val source = ByteArray(100000) { 90 }; val count = IntArray(1)
            assertEquals(0, LwipNativeBindings.tcpWrite(h, stream, source, 0, source.size, count)); assertEquals(32768, count[0])
            source.fill(0)
            assertEquals(-11, LwipNativeBindings.tcpWrite(h, stream, source, 0, 1, count)); assertEquals(0, count[0])
            assertEquals(0, LwipNativeBindings.supply(h, 10000, null)); assertEquals(0, LwipNativeBindings.poll(h))
            val payloads = rawDrain(h).filter { it[20] == 6.toByte() && it.size > 54 + ((it[66].toInt() and 255) ushr 4) * 4 }
            assertTrue(payloads.isNotEmpty())
            for (frame in payloads) assertTrue(frame.copyOfRange(54 + ((frame[66].toInt() and 255) ushr 4) * 4, frame.size).all { it == 90.toByte() })
            assertEquals(0, LwipNativeBindings.input(h, tcpPacket(9000, port, 20, 7001, sequence + 1)))
            assertEquals(-13, LwipNativeBindings.tcpConnectStatus(h, stream, local, remote, ports))
            assertEquals(0, LwipNativeBindings.tcpClose(h, stream))
            assertEquals(0, LwipNativeBindings.tcpConnect(h, peer, 9000, handle)); stale = handle[0]
            assertNotEquals(stream, stale)
            assertEquals(-1, LwipNativeBindings.tcpClose(h, stream))
            assertEquals(-8, LwipNativeBindings.supply(h, 9999, null))
            assertEquals(0, LwipNativeBindings.tcpClose(h, stale))
        } finally { assertEquals(0, LwipNativeBindings.destroy(h)) }
        val fresh = LwipNativeBindings.create(mac, ip, 1500, 100, entropy)
        try { assertEquals(-1, LwipNativeBindings.tcpClose(fresh, stale)) }
        finally { assertEquals(0, LwipNativeBindings.destroy(fresh)) }
    }
    @Test fun publicUdpAdapterTraversesOwnedRunnerAndNativeEndpointLifecycle() {
        val incoming = LinkedBlockingQueue<ByteArray>()
        val outgoing = LinkedBlockingQueue<ByteArray>()
        val port = object : EthernetFramePort {
            override val hostMac = mac.copyOf()
            override fun send(frame: ByteArray, timeoutMillis: Int): NcmSendResult {
                outgoing.add(frame.copyOf()); return NcmSendResult.Sent
            }
            override fun recv(timeoutMillis: Long): ByteArray? = incoming.poll(timeoutMillis, TimeUnit.MILLISECONDS)
            override fun close() { incoming.offer(ByteArray(14)) }
        }
        val base = coreCommandClock()
        val offset = AtomicLong(100)
        val clock = MonotonicClock { base.nowMillis() + offset.get() }
        val runner = LwipPrototype(port, commandClock = clock) { NativePrototypeCore.create(mac, ip, 1500, clockMillis = clock::nowMillis) }
        try {
            runner.start()
            val udp = LwipUdpEndpoint.bind(runner, NetworkEndpoint(ip, 0, "native-test"))
            assertTrue(udp.localEndpoint.port in 1..65535)
            runner.executeCommand { core -> core.setLinkReady(true); CoreAttempt.Ready(Unit) }
            repeat(16) {
                offset.addAndGet(250)
                runner.executeCommand { core -> core.poll(); CoreAttempt.Ready(Unit) }
            }
            val solicitation = packet(58, 32)
            solicitation[21] = 255.toByte(); solicitation[54] = 135.toByte()
            ip.copyInto(solicitation, 62); solicitation[78] = 1; solicitation[79] = 1
            peerMac.copyInto(solicitation, 80); checksum(solicitation, 58, 56)
            incoming.put(solicitation)
            fun sendPacket(payload: ByteArray) {
                incoming.put(packet(17, 8 + payload.size).also {
                    write16(it, 54, 50000); write16(it, 56, udp.localEndpoint.port); write16(it, 58, 8 + payload.size)
                    payload.copyInto(it, 62); checksum(it, 17, 60)
                })
            }
            sendPacket("hello".toByteArray())
            val target = ByteArray(2)
            val received = udp.receive(target, clock.nowMillis() + 2000)
            assertArrayEquals("he".toByteArray(), target)
            assertEquals(5, received.originalLength); assertEquals(2, received.copiedLength)
            assertEquals(NetworkEndpoint(ip.copyOf().also { it[15] = 2 }, 50000, "native-test"), received.source)
            sendPacket(byteArrayOf()); assertEquals(0, udp.receive(ByteArray(0), clock.nowMillis() + 2000).originalLength)
            outgoing.clear()
            udp.send("reply".toByteArray(), received.source, clock.nowMillis() + 2000)
            val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            var sent: ByteArray? = null
            while (sent == null && System.nanoTime() < end) {
                outgoing.poll(100, TimeUnit.MILLISECONDS)?.let { if (it.size >= 62 && it[20] == 17.toByte()) sent = it }
            }
            assertNotNull(sent)
            assertEquals(udp.localEndpoint.port, read16(sent!!, 54))
            assertArrayEquals("reply".toByteArray(), sent!!.copyOfRange(62, sent!!.size))
            val failure = AtomicReference<Throwable>()
            val waiter = Thread { failure.set(runCatching { udp.receive(ByteArray(1)) }.exceptionOrNull()) }
            waiter.start()
            val waitingUntil = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (runner.snapshot().commandCount == 0 && System.nanoTime() < waitingUntil) Thread.sleep(1)
            assertEquals(1, runner.snapshot().commandCount)
            udp.close(); udp.close()
            waiter.join(2000); assertFalse(waiter.isAlive)
            assertEquals(BackendFailure.Kind.CANCELLED, (failure.get() as BackendFailure).kind)
            val closingUntil = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (runner.snapshot().resourceScopes != 0 && System.nanoTime() < closingUntil) Thread.sleep(1)
            assertEquals(0, runner.snapshot().resourceScopes)
            assertEquals(1, runner.executeCommand { CoreAttempt.Ready((it as NativePrototypeCore).stats().udpEndpoints) })
            LwipUdpEndpoint.bind(runner, udp.localEndpoint).close()
            assertNull(runner.snapshot().failure)
        } finally { runner.close() }
    }
    @Test fun nativeUdpBindingsPreserveDatagramsAndRejectStaleTokens() {
        LwipNativeBindings.ensureLoaded()
        assertEquals(6, LwipNativeBindings.abiVersion())
        val entropy = ByteArray(1024).also { SecureRandom().nextBytes(it) }
        val context = LwipNativeBindings.create(mac, ip, 1500, 100, entropy)
        assertTrue(context > 0)
        var lastEndpoint = 0L
        try {
            val bound = LongArray(2)
            assertEquals(-1, LwipNativeBindings.udpBind(context, 0, LongArray(1)))
            assertEquals(0, LwipNativeBindings.udpBind(context, 0, bound))
            val endpoint = bound[0]
            val port = bound[1].toInt()
            assertTrue(endpoint > 0 && endpoint != context && port in 1..65535)
            assertEquals(-10, LwipNativeBindings.udpBind(context, port, LongArray(2)))
            val source = ByteArray(16)
            val metadata = IntArray(3)
            assertEquals(-11, LwipNativeBindings.udpReceive(context, endpoint, ByteArray(0), source, metadata))
            assertEquals(0, LwipNativeBindings.setReady(context, true))
            repeat(16) { n ->
                assertEquals(0, LwipNativeBindings.supply(context, 350L + n * 250, null))
                assertEquals(0, LwipNativeBindings.poll(context))
            }
            fun drainRaw(): List<ByteArray> {
                val frames = mutableListOf<ByteArray>()
                val bytes = ByteArray(1514)
                while (true) {
                    val length = LwipNativeBindings.takeOutput(context, bytes)
                    assertTrue(length >= 0)
                    if (length == 0) return frames
                    frames.add(bytes.copyOf(length))
                }
            }
            drainRaw()
            val solicitation = packet(58, 32)
            solicitation[21] = 255.toByte(); solicitation[54] = 135.toByte()
            ip.copyInto(solicitation, 62); solicitation[78] = 1; solicitation[79] = 1
            peerMac.copyInto(solicitation, 80); checksum(solicitation, 58, 56)
            assertEquals(0, LwipNativeBindings.input(context, solicitation))
            drainRaw()
            fun incoming(payload: ByteArray) {
                val frame = packet(17, 8 + payload.size)
                write16(frame, 54, 50000); write16(frame, 56, port); write16(frame, 58, 8 + payload.size)
                payload.copyInto(frame, 62); checksum(frame, 17, 60)
                assertEquals(0, LwipNativeBindings.input(context, frame))
                frame.fill(0)
            }
            incoming("hello".toByteArray())
            assertEquals(-1, LwipNativeBindings.udpReceive(context, endpoint, ByteArray(2), ByteArray(15), metadata))
            assertEquals(-1, LwipNativeBindings.udpReceive(context, endpoint, ByteArray(2), source, IntArray(2)))
            assertEquals(-1, LwipNativeBindings.udpReceive(context, endpoint, source, source, metadata))
            val target = ByteArray(2)
            assertEquals(0, LwipNativeBindings.udpReceive(context, endpoint, target, source, metadata))
            assertArrayEquals("he".toByteArray(), target)
            assertArrayEquals(intArrayOf(50000, 5, 2), metadata)
            val peer = ip.copyOf().also { it[15] = 2 }
            assertArrayEquals(peer, source)
            incoming(byteArrayOf())
            assertEquals(0, LwipNativeBindings.udpReceive(context, endpoint, ByteArray(0), source, metadata))
            assertArrayEquals(intArrayOf(50000, 0, 0), metadata)
            val payload = byteArrayOf(99, 111, 107, 88)
            assertEquals(-1, LwipNativeBindings.udpSend(context, endpoint, peer, 50000, payload, Int.MAX_VALUE, 2))
            assertEquals(-1, LwipNativeBindings.udpSend(context, endpoint, peer, 50000, payload, 0, -1))
            assertEquals(0, LwipNativeBindings.udpSend(context, endpoint, peer, 50000, payload, 1, 2))
            val sent = drainRaw().first { it[20] == 17.toByte() }
            assertEquals(port, read16(sent, 54))
            assertArrayEquals(byteArrayOf(111, 107), sent.copyOfRange(62, sent.size))
            val wrongThread = AtomicInteger()
            val worker = Thread { wrongThread.set(LwipNativeBindings.udpClose(context, endpoint)) }
            worker.start(); worker.join(2000)
            assertFalse(worker.isAlive); assertEquals(-3, wrongThread.get())
            assertEquals(-1, LwipNativeBindings.udpClose(context, context))
            assertEquals(0, LwipNativeBindings.udpClose(context, endpoint))
            assertEquals(-1, LwipNativeBindings.udpClose(context, endpoint))
            assertEquals(0, LwipNativeBindings.udpBind(context, port, bound))
            lastEndpoint = bound[0]
            assertNotEquals(endpoint, lastEndpoint)
            assertEquals(-1, LwipNativeBindings.udpReceive(context, endpoint, target, source, metadata))
        } finally { assertEquals(0, LwipNativeBindings.destroy(context)) }
        val next = LwipNativeBindings.create(mac, ip, 1500, 100, entropy)
        assertTrue(next > 0)
        try {
            assertEquals(-1, LwipNativeBindings.udpClose(next, lastEndpoint))
            assertEquals(-1, LwipNativeBindings.udpBind(context, 0, LongArray(2)))
        } finally { assertEquals(0, LwipNativeBindings.destroy(next)) }
    }
    @Test fun realIpv6InputReachesNativeUdpAndTcpEchoWithoutKernelSockets() {
        var now = 100L
        NativePrototypeCore.create(mac, ip, 1500) { now }.use { core ->
            core.prepare()
            core.setLinkReady(true)
            repeat(16) { now += 250; core.poll() }
            drain(core)
            val solicitation = packet(58, 32)
            solicitation[21] = 255.toByte()
            solicitation[54] = 135.toByte()
            ip.copyInto(solicitation, 62)
            solicitation[78] = 1; solicitation[79] = 1
            peerMac.copyInto(solicitation, 80)
            checksum(solicitation, 58, 56)
            core.input(solicitation)
            assertTrue(drain(core).any { it[20] == 58.toByte() && it[54] == 136.toByte() })

            val payload = "JNI echo".toByteArray(Charsets.US_ASCII)
            val udp = packet(17, 8 + payload.size)
            write16(udp, 54, 50000); write16(udp, 56, 6000); write16(udp, 58, 8 + payload.size)
            payload.copyInto(udp, 62)
            checksum(udp, 17, 60)
            core.input(udp)
            val echoed = drain(core).first { it[20] == 17.toByte() }
            assertEquals(6000, read16(echoed, 54))
            assertEquals(50000, read16(echoed, 56))
            assertArrayEquals(payload, echoed.copyOfRange(62, echoed.size))

            fun tcp(flags: Int, sequence: Long, ack: Long, data: ByteArray = byteArrayOf()): ByteArray {
                val frame = packet(6, 20 + data.size)
                write16(frame, 54, 50000); write16(frame, 56, 7000)
                write32(frame, 58, sequence); write32(frame, 62, ack)
                frame[66] = 0x50; frame[67] = flags.toByte(); write16(frame, 68, 32768)
                data.copyInto(frame, 74); checksum(frame, 6, 70)
                return frame
            }
            core.input(tcp(2, 1000, 0))
            val synAck = drain(core).first { it[20] == 6.toByte() }
            assertEquals(0x12, synAck[67].toInt() and 0x12)
            val server = (0..3).fold(0L) { value, n -> (value shl 8) or (synAck[58 + n].toLong() and 255) }
            core.input(tcp(16, 1001, server + 1))
            core.input(tcp(24, 1001, server + 1, payload))
            assertTrue(drain(core).any { frame ->
                frame[20] == 6.toByte() && frame.copyOfRange(54 + ((frame[66].toInt() and 255) ushr 4) * 4, frame.size).contentEquals(payload)
            })
        }
    }

    @Test fun explicitNativeFactoryRunsThroughTheOwnedTransportWorkers() {
        val sent = LinkedBlockingQueue<ByteArray>()
        val closed = java.util.concurrent.CountDownLatch(1)
        val port = object : EthernetFramePort {
            override val hostMac = mac.copyOf()
            override fun send(frame: ByteArray, timeoutMillis: Int): NcmSendResult {
                sent.offer(frame.copyOf())
                return NcmSendResult.Sent
            }
            override fun recv(timeoutMillis: Long): ByteArray? {
                closed.await(timeoutMillis, TimeUnit.MILLISECONDS)
                return null
            }
            override fun close() { closed.countDown() }
        }
        val prototype = LwipPrototype.nativeEcho(port, mac, ip, 1500)
        prototype.use {
            it.start()
            val stats = it.executeCommand(deadlineMillis = it.commandClock.nowMillis() + 2_000) { core ->
                CoreAttempt.Ready((core as NativePrototypeCore).stats())
            }
            assertEquals(1, stats.tcpListeners)
            assertEquals(1, stats.udpEndpoints)
            val frame = sent.poll(5, TimeUnit.SECONDS)
            assertNotNull("native timer output must reach the port", frame)
            assertEquals(0x86, frame!![12].toInt() and 255)
            assertEquals(0xdd, frame[13].toInt() and 255)
        }
        assertEquals(LwipPrototype.State.STOPPED, prototype.snapshot().state)
        assertNull(prototype.snapshot().failure)
        assertEquals(0L, closed.count)
    }

    @Test fun loadsAndRunsRealNativeTimersAndCopiesQueuedFrames() {
        var now = 100L
        NativePrototypeCore.create(mac, ip, 1500) { now }.use { core ->
            core.prepare()
            assertEquals(1, core.stats().tcpListeners)
            assertEquals(1, core.stats().udpEndpoints)
            assertTrue(core.stats().configuredBytes <= 256 * 1024)
            core.setLinkReady(true)
            now += 2000
            core.poll()
            val first = core.takeOutput()!!
            assertEquals(0x86, first[12].toInt() and 255)
            assertEquals(0xdd, first[13].toInt() and 255)
            assertTrue(core.nextTimeoutMillis() in 0..0xffff_ffffL)
        }
    }

    @Test fun generationsAndThreadAffinitySurviveRepeatedNativeRecreation() {
        LwipNativeBindings.ensureLoaded()
        val entropy = ByteArray(1024).also { SecureRandom().nextBytes(it) }
        var previous = 0L
        repeat(100) {
            val h = LwipNativeBindings.create(mac, ip, 1500, 100, entropy)
            assertTrue(h > 0)
            try {
            if (previous != 0L) assertEquals(-1, LwipNativeBindings.poll(previous))
            val result = AtomicInteger()
            Thread { result.set(LwipNativeBindings.poll(h)) }.apply { start(); join(2_000); assertFalse(isAlive) }
            assertEquals(-3, result.get())
            } finally { assertEquals(0, LwipNativeBindings.destroy(h)) }
            previous = h
        }
    }

    @Test fun jniRejectsInvalidArraysAndPreservesOutputOnASmallDestination() {
        LwipNativeBindings.ensureLoaded()
        val entropy = ByteArray(1024).also { SecureRandom().nextBytes(it) }
        assertEquals(-1L, LwipNativeBindings.create(ByteArray(5), ip, 1500, 100, entropy))
        val h = LwipNativeBindings.create(mac, ip, 1500, 100, entropy)
        assertTrue(h > 0)
        try {
            assertEquals(-1, LwipNativeBindings.input(h, ByteArray(1515)))
            assertEquals(-1, LwipNativeBindings.supply(h, 100, ByteArray(1025)))
            assertEquals(0, LwipNativeBindings.setReady(h, true))
            assertEquals(0, LwipNativeBindings.supply(h, 2100, null))
            assertEquals(0, LwipNativeBindings.poll(h))
            assertEquals(-2, LwipNativeBindings.takeOutput(h, ByteArray(1)))
            val frame = ByteArray(1514)
            assertTrue(LwipNativeBindings.takeOutput(h, frame) >= 14)
            assertEquals(-8, LwipNativeBindings.supply(h, 2099, null))
            assertEquals(-8, LwipNativeBindings.poll(h))
        } finally { assertEquals(0, LwipNativeBindings.destroy(h)) }
    }

    companion object {
        private fun read32(frame: ByteArray, at: Int) = (read16(frame, at).toLong() shl 16) or read16(frame, at + 2).toLong()
        private fun tcpPacket(source: Int, destination: Int, flags: Int, seq: Long, ack: Long, data: ByteArray = byteArrayOf()) = packet(6, 20 + data.size).also {
            write16(it, 54, source); write16(it, 56, destination); write32(it, 58, seq); write32(it, 62, ack)
            it[66] = 0x50; it[67] = flags.toByte(); write16(it, 68, 32768)
            data.copyInto(it, 74); checksum(it, 6, 70)
        }
        private fun rawDrain(context: Long): List<ByteArray> {
            val frames = mutableListOf<ByteArray>(); val target = ByteArray(1514)
            while (true) {
                val count = LwipNativeBindings.takeOutput(context, target)
                assertTrue(count >= 0)
                if (count == 0) return frames
                frames.add(target.copyOf(count))
            }
        }
        private fun rawReady(context: Long) {
            assertEquals(0, LwipNativeBindings.setReady(context, true))
            repeat(16) { n ->
                assertEquals(0, LwipNativeBindings.supply(context, 350L + n * 250, null))
                assertEquals(0, LwipNativeBindings.poll(context))
            }
            rawDrain(context)
            val solicitation = packet(58, 32)
            solicitation[21] = 255.toByte(); solicitation[54] = 135.toByte()
            ip.copyInto(solicitation, 62); solicitation[78] = 1; solicitation[79] = 1
            peerMac.copyInto(solicitation, 80); checksum(solicitation, 58, 56)
            assertEquals(0, LwipNativeBindings.input(context, solicitation)); rawDrain(context)
        }
        private val mac = byteArrayOf(2, 0, 0, 0, 0, 1)
        private val peerMac = byteArrayOf(2, 0, 0, 0, 0, 2)
        private val ip = ByteArray(16).also { it[0] = 0xfe.toByte(); it[1] = 0x80.toByte(); it[15] = 1 }
        private fun drain(core: NativePrototypeCore): List<ByteArray> {
            val frames = mutableListOf<ByteArray>()
            while (true) frames.add(core.takeOutput() ?: return frames)
        }
        private fun read16(frame: ByteArray, offset: Int) = ((frame[offset].toInt() and 255) shl 8) or (frame[offset + 1].toInt() and 255)
        private fun write16(frame: ByteArray, offset: Int, value: Int) {
            frame[offset] = (value ushr 8).toByte(); frame[offset + 1] = value.toByte()
        }
        private fun write32(frame: ByteArray, offset: Int, value: Long) {
            repeat(4) { frame[offset + it] = (value ushr (24 - it * 8)).toByte() }
        }
        private fun packet(protocol: Int, payloadLength: Int) = ByteArray(54 + payloadLength).also {
            mac.copyInto(it); peerMac.copyInto(it, 6); write16(it, 12, 0x86dd)
            it[14] = 0x60; write16(it, 18, payloadLength); it[20] = protocol.toByte(); it[21] = 64
            ip.copyInto(it, 22); it[37] = 2; ip.copyInto(it, 38)
        }
        private fun checksum(frame: ByteArray, protocol: Int, checksumOffset: Int) {
            var sum = protocol + frame.size - 54
            for (offset in 22 until frame.size step 2) {
                sum += (frame[offset].toInt() and 255) shl 8
                if (offset + 1 < frame.size) sum += frame[offset + 1].toInt() and 255
            }
            while (sum ushr 16 != 0) sum = (sum and 65535) + (sum ushr 16)
            val value = sum.inv() and 65535
            write16(frame, checksumOffset, if (value == 0) 65535 else value)
        }
    }
}
