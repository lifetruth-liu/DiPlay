package com.shilapi.xcertplay.network.backend

import com.shilapi.xcertplay.network.backend.lwip.*
import com.shilapi.xcertplay.network.backend.system.SystemSessionNetwork
import com.shilapi.xcertplay.transport.NcmSendResult
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.util.Random
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class TcpSessionDeviceContractTest(private val backend: String) {
    @Test fun peerResetIsDistinctFromEofTimeoutAndLocalCancellation() {
        peer(resetAfterData = true).use { peer ->
            val deadline = peer.network.clock.nowMillis() + 3000
            peer.stream.write(byteArrayOf(1), deadlineMillis = deadline)
            val target = ByteArray(7) { 0x55 }
            val failure = assertThrows(BackendFailure::class.java) {
                peer.stream.read(target, deadlineMillis = deadline)
            }
            assertEquals(generateSequence<Throwable>(failure) { it.cause }.take(16).joinToString(" caused by "),
                BackendFailure.Kind.PEER_RESET, failure.kind)
            assertArrayEquals(ByteArray(7) { 0x55 }, target)
            peer.verify()
        }
    }

    @Test fun repeatedSessionHandshakeAndEmptyHalfClose() {
        repeat(12) {
            peer().use { peer ->
                peer.stream.shutdownOutput()
                assertEquals(-1, peer.stream.read(ByteArray(1), deadlineMillis = peer.network.clock.nowMillis() + 3000))
                peer.verify()
            }
        }
    }

    @Test fun timedOutReadPreservesBufferAndAllowsConcurrentWriteAndResponse() {
        peer().use { peer ->
            val stream = peer.stream
            val target = ByteArray(19) { 0x55 }
            assertEquals(BackendFailure.Kind.TIMEOUT,
                assertThrows(BackendFailure::class.java) {
                    stream.read(target, 2, 13, peer.network.clock.nowMillis() + 100)
                }.kind)
            assertArrayEquals(ByteArray(19) { 0x55 }, target)
            val executor = Executors.newSingleThreadExecutor()
            try {
                val started = CountDownLatch(1)
                val reader = AtomicReference<Thread>()
                val deadline = peer.network.clock.nowMillis() + 5000
                val response = executor.submit<ByteArray> {
                    reader.set(Thread.currentThread())
                    started.countDown()
                    val result = ByteArrayOutputStream()
                    val bytes = ByteArray(113)
                    while (true) {
                        val count = stream.read(bytes, deadlineMillis = deadline)
                        if (count == -1) break
                        check(count > 0 && result.size() + count <= 4097)
                        result.write(bytes, 0, count)
                    }
                    result.toByteArray()
                }
                assertTrue(started.await(2, TimeUnit.SECONDS))
                awaitReadEntry(reader.get())
                val payload = ByteArray(4097).also { Random(7).nextBytes(it) }
                stream.write(payload, deadlineMillis = deadline)
                stream.shutdownOutput()
                assertArrayEquals(payload, response.get(6, TimeUnit.SECONDS))
                peer.verify()
            } finally { executor.shutdownNow() }
        }
    }

    @Test fun closeCancelsAConcurrentReadAndIsIdempotent() {
        peer().use { peer ->
            val executor = Executors.newSingleThreadExecutor()
            try {
                val started = CountDownLatch(1)
                val reader = AtomicReference<Thread>()
                val target = ByteArray(11) { 0x55 }
                val result = executor.submit<BackendFailure.Kind> {
                    reader.set(Thread.currentThread())
                    started.countDown()
                    try { peer.stream.read(target); error("Expected cancellation") }
                    catch (error: BackendFailure) { error.kind }
                }
                assertTrue(started.await(2, TimeUnit.SECONDS))
                awaitReadEntry(reader.get())
                peer.stream.close(); peer.stream.close()
                assertEquals(BackendFailure.Kind.CANCELLED, result.get(3, TimeUnit.SECONDS))
                assertArrayEquals(ByteArray(11) { 0x55 }, target)
                assertEquals(BackendFailure.Kind.CANCELLED,
                    assertThrows(BackendFailure::class.java) { peer.stream.read(ByteArray(1)) }.kind)
            } finally { executor.shutdownNow() }
        }
    }

    private fun awaitReadEntry(reader: Thread) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (System.nanoTime() < end) {
            if (reader.stackTrace.any {
                it.methodName == "read" &&
                    (it.className.contains("SystemSessionNetwork") || it.className.contains("LwipTcpStream"))
            }) return
            Thread.sleep(1)
        }
        fail("Reader did not enter the backend read before the deadline")
    }

    @Test fun chunkedBytesHalfCloseResponseAndEof() {
        val payload = ByteArray(65537).also { Random(73).nextBytes(it) }
        peer().use { peer ->
            val stream = peer.stream
            val deadline = peer.network.clock.nowMillis() + 30000
            val random = Random(91)
            var offset = 0
            // Includes one-byte and larger-than-native-write-chunk calls, with nonzero offsets.
            for (size in listOf(1, 32769, payload.size - 32770)) {
                val padded = ByteArray(size + 6) { 0x55 }
                payload.copyInto(padded, 3, offset, offset + size)
                stream.write(padded, 3, size, deadline)
                offset += size
            }
            stream.shutdownOutput()
            val response = ByteArrayOutputStream()
            val buffer = ByteArray(4099)
            while (true) {
                val capacity = if (response.size() == 0) 1 else 1 + random.nextInt(4096)
                val count = stream.read(buffer, 2, capacity, deadline)
                if (count == -1) break
                assertTrue("Nonempty read must progress", count in 1..capacity)
                assertTrue("Peer exceeded the bounded response", response.size() + count <= payload.size)
                response.write(buffer, 2, count)
            }
            assertArrayEquals(payload, response.toByteArray())
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            assertArrayEquals(digest.digest(payload), digest.digest(response.toByteArray()))
            assertEquals(-1, stream.read(ByteArray(1), deadlineMillis = deadline))
            peer.verify()
        }
    }

    private interface Peer : Closeable {
        val network: SessionNetwork
        val stream: TcpStream
        fun verify()
    }

    private fun peer(resetAfterData: Boolean = false): Peer =
        if (backend == "system") systemPeer(resetAfterData) else nativePeer(resetAfterData)

    private fun systemPeer(resetAfterData: Boolean): Peer {
        val server = ServerSocket(0, 1, InetAddress.getByName("::1"))
        val network = SystemSessionNetwork("tcp-contract")
        val error = AtomicReference<Throwable>()
        val worker = Thread {
            try {
                server.accept().use { socket ->
                    socket.soTimeout = 30000
                    if (resetAfterData) {
                        check(socket.getInputStream().read() == 1)
                        socket.setSoLinger(true, 0)
                        return@use
                    }
                    val bytes = ByteArrayOutputStream()
                    val chunk = ByteArray(997)
                    while (true) {
                        val count = socket.getInputStream().read(chunk)
                        if (count == -1) break
                        check(bytes.size() + count <= 131072)
                        bytes.write(chunk, 0, count)
                    }
                    socket.getOutputStream().write(bytes.toByteArray())
                    socket.shutdownOutput()
                }
            } catch (failure: Throwable) { error.set(failure) }
        }.apply { start() }
        try {
            val stream = network.connect(NetworkEndpoint(InetAddress.getByName("::1").address, server.localPort, network.linkId), network.clock.nowMillis() + 3000)
            return object : Peer {
                override val network = network
                override val stream = stream
                override fun verify() { worker.join(3000); assertFalse(worker.isAlive); error.get()?.let { throw AssertionError(it) } }
                override fun close() { try { network.close(); server.close() } finally { worker.join(3000) } }
            }
        } catch (failure: Throwable) { network.close(); server.close(); worker.join(3000); throw failure }
    }

    private fun nativePeer(resetAfterData: Boolean): Peer {
        val wire = TcpWirePeer(resetAfterData)
        val base = coreCommandClock()
        val offset = AtomicLong()
        val clock = MonotonicClock { base.nowMillis() + offset.get() }
        val runner = LwipPrototype(wire, commandClock = clock) {
            NativePrototypeCore.create(wire.mac, wire.ip, 1500, echoServices = false, clockMillis = clock::nowMillis)
        }
        val network = LwipSessionNetwork.start(runner, "tcp-contract")
        try {
            repeat(16) { offset.addAndGet(250); runner.executeCommand { it.poll(); CoreAttempt.Ready(Unit) } }
            val stream = network.connect(NetworkEndpoint(wire.peerIp, 9000, network.linkId), clock.nowMillis() + 3000)
            return object : Peer {
                override val network = network
                override val stream = stream
                override fun verify() { wire.checkFailure(); assertNull(runner.snapshot().failure) }
                override fun close() = network.close()
            }
        } catch (failure: Throwable) {
            try { wire.checkFailure() } catch (peerFailure: Throwable) { failure.addSuppressed(peerFailure) }
            failure.addSuppressed(AssertionError("wire=${wire.trace} runner=${runner.snapshot()}"))
            network.close(); throw failure
        }
    }

    /** Test-only, stop-and-wait echo after FIN. No native API or fake core calls. */
    private class TcpWirePeer(private val resetAfterData: Boolean) : EthernetFramePort {
        val mac = byteArrayOf(2, 0, 0, 0, 0, 1)
        val ip = ByteArray(16).also { it[0] = 0xfe.toByte(); it[1] = 0x80.toByte(); it[15] = 1 }
        val peerIp = ip.copyOf().also { it[15] = 2 }
        private val peerMac = byteArrayOf(2, 0, 0, 0, 0, 2)
        override val hostMac = mac.copyOf()
        private val input = LinkedBlockingQueue<ByteArray>(64)
        private val output = LinkedBlockingQueue<ByteArray>(64)
        private val closed = AtomicBoolean()
        private val error = AtomicReference<Throwable>()
        val trace = java.util.concurrent.CopyOnWriteArrayList<String>()
        private val worker = Thread {
            try { runPeer() } catch (failure: Throwable) { if (!closed.get()) error.set(failure) }
        }.apply { start() }
        override fun send(frame: ByteArray, timeoutMillis: Int): NcmSendResult {
            check(output.offer(frame.copyOf(), timeoutMillis.toLong(), TimeUnit.MILLISECONDS))
            return NcmSendResult.Sent
        }
        override fun recv(timeoutMillis: Long) = input.poll(timeoutMillis, TimeUnit.MILLISECONDS)
        override fun close() { closed.set(true); worker.interrupt(); input.offer(ByteArray(14)); worker.join(3000); check(!worker.isAlive) }
        fun checkFailure() { error.get()?.let { throw AssertionError(it) } }
        private fun emit(frame: ByteArray) { check(input.offer(frame, 2, TimeUnit.SECONDS)) }
        private fun runPeer() {
            var localPort = 0; var localNext = 0L; var peerNext = 7000L
            var acknowledged = 7000L; var finished = false; var sentFin = false; var sent = 0
            val received = ByteArrayOutputStream()
            while (!closed.get()) {
                val frame = output.poll(100, TimeUnit.MILLISECONDS) ?: continue
                if (trace.size < 20) trace.add("out proto=${frame.getOrNull(20)} size=${frame.size} type=${frame.getOrNull(54)} flags=${frame.getOrNull(67)}")
                if (frame.size >= 78 && frame[20] == 58.toByte() && frame[54] == 135.toByte() &&
                    frame.copyOfRange(62, 78).contentEquals(peerIp)) {
                    emit(packet(58, 32).also {
                        it[21] = 255.toByte(); it[54] = 136.toByte(); it[58] = 0x60
                        peerIp.copyInto(it, 62); it[78] = 2; it[79] = 1
                        peerMac.copyInto(it, 80); checksum(it, 58, 56)
                    })
                    continue
                }
                if (frame.size < 74 || frame[20] != 6.toByte()) continue
                check(read16(frame, 56) == 9000)
                val flags = frame[67].toInt() and 255
                check(flags and 4 == 0) { "Unexpected reset" }
                val seq = read32(frame, 58); val ack = read32(frame, 62)
                val header = ((frame[66].toInt() and 255) ushr 4) * 4
                val data = frame.copyOfRange(54 + header, frame.size)
                if (flags and 2 != 0) {
                    if (trace.size < 20) trace.add("syn seq=$seq peer=$peerNext")
                    localPort = read16(frame, 54); localNext = seq + 1
                    emit(tcp(localPort, 18, peerNext, localNext)); peerNext++
                    continue
                }
                if (flags and 16 != 0) acknowledged = maxOf(acknowledged, ack)
                if (seq == localNext && (data.isNotEmpty() || flags and 1 != 0)) {
                    if (resetAfterData && data.isNotEmpty()) {
                        emit(tcp(localPort, 20, peerNext, seq + data.size))
                        return
                    }
                    check(received.size() + data.size <= 131072)
                    received.write(data); localNext += data.size
                    if (flags and 1 != 0) { localNext++; finished = true }
                    emit(tcp(localPort, 16, peerNext, localNext))
                }
                if (finished && acknowledged == peerNext && !sentFin) {
                    val bytes = received.toByteArray()
                    if (sent < bytes.size) {
                        val count = minOf(1400, bytes.size - sent)
                        emit(tcp(localPort, 24, peerNext, localNext, bytes.copyOfRange(sent, sent + count)))
                        peerNext += count; sent += count
                    } else {
                        emit(tcp(localPort, 17, peerNext, localNext)); peerNext++; sentFin = true
                    }
                }
            }
        }
        private fun tcp(port: Int, flags: Int, sequence: Long, acknowledgment: Long, bytes: ByteArray = byteArrayOf()) = packet(6, 20 + bytes.size).also {
            put16(it, 54, 9000); put16(it, 56, port)
            repeat(4) { n -> it[58 + n] = (sequence ushr (24 - n * 8)).toByte(); it[62 + n] = (acknowledgment ushr (24 - n * 8)).toByte() }
            it[66] = 0x50; it[67] = flags.toByte(); put16(it, 68, 32768)
            bytes.copyInto(it, 74); checksum(it, 6, 70)
        }
        private fun packet(protocol: Int, length: Int) = ByteArray(54 + length).also {
            mac.copyInto(it); peerMac.copyInto(it, 6); put16(it, 12, 0x86dd)
            it[14] = 0x60; put16(it, 18, length); it[20] = protocol.toByte(); it[21] = 64
            peerIp.copyInto(it, 22); ip.copyInto(it, 38)
        }
        private fun checksum(frame: ByteArray, protocol: Int, at: Int) {
            var sum = protocol + frame.size - 54
            for (index in 22 until frame.size step 2) {
                sum += (frame[index].toInt() and 255) shl 8
                if (index + 1 < frame.size) sum += frame[index + 1].toInt() and 255
            }
            while (sum ushr 16 != 0) sum = (sum and 65535) + (sum ushr 16)
            val value = sum.inv() and 65535; put16(frame, at, if (value == 0) 65535 else value)
        }
        private fun put16(frame: ByteArray, at: Int, value: Int) { frame[at] = (value ushr 8).toByte(); frame[at + 1] = value.toByte() }
        private fun read16(frame: ByteArray, at: Int) = ((frame[at].toInt() and 255) shl 8) or (frame[at + 1].toInt() and 255)
        private fun read32(frame: ByteArray, at: Int) = (read16(frame, at).toLong() shl 16) or read16(frame, at + 2).toLong()
    }

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}")
        fun backends() = listOf(arrayOf("system"), arrayOf("lwip-jni"))
    }
}
